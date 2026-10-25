package com.car.xiaoouscreen.domain

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.data.QWeatherMinutelyResponse
import com.car.xiaoouscreen.data.QWeatherNowDto
import com.car.xiaoouscreen.data.QWeatherRepository
import com.car.xiaoouscreen.location.CarGpsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 双气象引擎融合与车载调度核心调度器
 * 严格落实《小欧屏双天气融合与车载交互规范》：
 * 1. 降水守门人 (Rain Gatekeeper): Open-Meteo POP >= 20% 唤醒和风分钟降水
 * 2. 8分钟物理硬冷却 + 15分钟定时 / 5km 位移复合门禁
 * 3. 雨前 5~10 分钟单次复验锁 (One-Shot Lock)
 * 4. 本地时间轴推演引擎 (120分钟倒计时自动滑动，单次有效覆盖30~45分钟)
 * 5. 地下车库冷启动零报错离线容灾 + 出网 5秒防抖静默补偿拉取
 */
class WeatherFusionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val openMeteoRepo: OpenMeteoRepository,
    val qWeatherRepo: QWeatherRepository,
    private val gpsManager: CarGpsManager
) {

    private val _sceneConfig = MutableStateFlow(WeatherSceneConfig.DEFAULT)
    val sceneConfig: StateFlow<WeatherSceneConfig> = _sceneConfig.asStateFlow()

    private var pollJob: Job? = null
    private var tickerJob: Job? = null
    private var silentDebounceJob: Job? = null

    // 冷却与单次校验标志
    private var lastQWeatherNowTimeMs: Long = 0L
    private var lastQWeatherMinutelyTimeMs: Long = 0L
    private var isPreRainVerified: Boolean = false

    // 本地时间轴推演参数
    private var predictedRainTargetUnix: Long = 0L // 预测降水开始或结束的目标秒级时间戳
    private var isTargetRainStart: Boolean = true   // true: 预测雨起，false: 预测雨停
    private var lastObservedPrecip: Float = 0f

    // 缓存最新一次有效的各源数据
    private var lastWmoCode: Int = 0
    private var lastQNow: QWeatherNowDto? = null
    private var lastMinutelyResp: QWeatherMinutelyResponse? = null
    private var lastQWeatherLat: Double = 0.0
    private var lastQWeatherLon: Double = 0.0

    init {
        // 冷启动优先读取 L2 本地持久化缓存，避免网络就绪前的瞬间晴天跳变
        restoreCachedScene()
    }

    private fun restoreCachedScene() {
        val cachedQNow = qWeatherRepo.lastCachedNow
        val cachedMinutely = qWeatherRepo.lastCachedMinutely
        val nowUnix = System.currentTimeMillis() / 1000L
        val sunriseUnix = openMeteoRepo.lastSunriseUnix
        val sunsetUnix = openMeteoRepo.lastSunsetUnix

        if (cachedQNow != null && qWeatherRepo.configManager.isEnabled && qWeatherRepo.configManager.isConfigured()) {
            lastQNow = cachedQNow
            // 确保分钟级雷达降水在有效期内才恢复
            val isMinutelyFresh = (System.currentTimeMillis() - qWeatherRepo.lastMinutelyTimestamp) < 35 * 60 * 1000L && qWeatherRepo.lastMinutelyTimestamp > 0L
            val validMinutely = if (isMinutelyFresh) cachedMinutely else null
            lastMinutelyResp = validMinutely
            val restoredScene = WeatherSceneMapper.mapQWeatherToScene(
                qNow = cachedQNow,
                minutely = validMinutely,
                openMeteoWmoCode = 3,
                nowUnix = nowUnix,
                sunriseUnix = sunriseUnix,
                sunsetUnix = sunsetUnix
            )
            lastWmoCode = when (restoredScene.weatherState) {
                WeatherState.CLEAR -> 0
                WeatherState.CLOUDY -> 2
                WeatherState.OVERCAST -> 3
                WeatherState.RAIN -> if (restoredScene.intensity == Intensity.HEAVY) 65 else 63
                WeatherState.SNOW -> if (restoredScene.intensity == Intensity.HEAVY) 75 else 73
                WeatherState.STORM -> 95
                WeatherState.SLEET -> 68
            }
            _sceneConfig.value = restoredScene
            Log.i(TAG, "📦 Cold-start restored cached weather: state=${restoredScene.weatherState}, desc=${restoredScene.weatherDescription}")
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 出库网络恢复：5秒防抖，静默补偿拉取
            silentDebounceJob?.cancel()
            silentDebounceJob = scope.launch {
                delay(5000L)
                Log.i(TAG, "Network restored & stable for 5s, performing silent compensation fetch")
                refreshWeather(force = false)
            }
        }
    }

    fun start() {
        registerNetworkCallback()

        // 1. 若已有已知坐标，立即触发一次静默刷新
        if (gpsManager.currentLatitude != 0.0 || gpsManager.currentLongitude != 0.0) {
            refreshWeather(gpsManager.currentLatitude, gpsManager.currentLongitude, force = false)
        }

        // 2. GPS 就绪监听 (自带 15min / 5km 复合防连击门禁)
        gpsManager.startLocationUpdates { lat, lon ->
            refreshWeather(lat, lon, force = false)
        }

        // 3. 启动 15 分钟长周期常规轮询
        startPeriodicPolling()

        // 4. 启动 1 分钟短周期本地时间轴与昼夜推演
        startLocalTimelineTicker()
    }

    fun stop() {
        pollJob?.cancel()
        tickerJob?.cancel()
        silentDebounceJob?.cancel()
        gpsManager.stopLocationUpdates()
        unregisterNetworkCallback()
    }

    /**
     * 核心刷新方法：执行双源融合调度
     */
    fun refreshWeather(
        lat: Double = gpsManager.currentLatitude,
        lon: Double = gpsManager.currentLongitude,
        force: Boolean = false
    ) {
        scope.launch {
            val nowMs = System.currentTimeMillis()
            val nowUnix = nowMs / 1000L
            Log.e(TAG, "🌐【双天气引擎触发刷新】传入经纬度: lat=$lat, lon=$lon (force=$force)")

            // 步骤 1：请求 Open-Meteo 基础气象 (ECMWF IFS 0.25° + 日出日落秒级时间戳)
            val omResult = openMeteoRepo.getWeatherScene(lat, lon)
            val baseScene = omResult.getOrNull() ?: _sceneConfig.value
            Log.e(TAG, "🌦️ Open-Meteo 返回: temp=${baseScene.temperatureCelsius}℃, state=${baseScene.weatherState}, wind=${baseScene.windSpeedKmh}km/h")

            val sunriseUnix = openMeteoRepo.lastSunriseUnix
            val sunsetUnix = openMeteoRepo.lastSunsetUnix
            val pop = openMeteoRepo.lastMaxPrecipProbability
            lastWmoCode = when (baseScene.weatherState) {
                WeatherState.CLEAR -> 0
                WeatherState.CLOUDY -> 2
                WeatherState.OVERCAST -> 3
                WeatherState.RAIN -> 63
                WeatherState.SNOW -> 73
                WeatherState.STORM -> 95
                WeatherState.SLEET -> 68
            }

            // 步骤 2：判断是否启用了和风天气且配置了 Key
            if (!qWeatherRepo.configManager.isEnabled || !qWeatherRepo.configManager.isConfigured() || qWeatherRepo.isCircuitBroken) {
                // 纯免费 Open-Meteo 兜底模式
                _sceneConfig.value = baseScene
                return@launch
            }

            // 步骤 3：高精地面站实况 (weather/now) - 15分钟或位移超3km或强制刷新
            val distMeters = if (lastQWeatherLat != 0.0 && lastQWeatherLon != 0.0) {
                val results = FloatArray(1)
                Location.distanceBetween(lastQWeatherLat, lastQWeatherLon, lat, lon, results)
                results[0]
            } else {
                Float.MAX_VALUE
            }
            val isLocationMoved = distMeters >= 3000f
            val needNowUpdate = force || isLocationMoved || (nowMs - lastQWeatherNowTimeMs >= 15 * 60 * 1000L)
            if (needNowUpdate) {
                Log.e(TAG, "📡 正在请求和风实况 weather/now (lon=$lon, lat=$lat, moved=$isLocationMoved, force=$force)...")
                val nowResult = qWeatherRepo.getWeatherNow(lat, lon)
                if (nowResult.isSuccess) {
                    lastQNow = nowResult.getOrNull()
                    lastQWeatherNowTimeMs = nowMs
                    lastQWeatherLat = lat
                    lastQWeatherLon = lon
                    Log.e(TAG, "✅ 和风实况返回: temp=${lastQNow?.temp}℃, text=${lastQNow?.text}, wind=${lastQNow?.windDir} ${lastQNow?.windScale}级")
                } else {
                    Log.e(TAG, "❌ 和风实况请求失败: ${nowResult.exceptionOrNull()?.message}")
                }
            }

            // 步骤 4：降水守门人 (Rain Gatekeeper) —— POP >= 20% 或当前基础为雨雪天时唤醒分钟降水
            val isRainSign = pop >= 20 || baseScene.weatherState in listOf(
                WeatherState.RAIN, WeatherState.SNOW, WeatherState.STORM, WeatherState.SLEET
            )
            val cooldownOk = (nowMs - lastQWeatherMinutelyTimeMs >= MIN_COOLDOWN_MS)

            if ((isRainSign && cooldownOk) || force || isLocationMoved) {
                val minResult = qWeatherRepo.getMinutelyPrecipitation(lat, lon)
                if (minResult.isSuccess) {
                    lastMinutelyResp = minResult.getOrNull()
                    lastQWeatherMinutelyTimeMs = nowMs
                    parseMinutelyTimeline(lastMinutelyResp)
                }
            }

            // 步骤 5：综合融合仲裁映射
            val mergedScene = WeatherSceneMapper.mapQWeatherToScene(
                qNow = lastQNow,
                minutely = lastMinutelyResp,
                openMeteoWmoCode = lastWmoCode,
                nowUnix = nowUnix,
                sunriseUnix = sunriseUnix,
                sunsetUnix = sunsetUnix,
                fallbackWindSpeed = baseScene.windSpeedKmh,
                fallbackTemp = baseScene.temperatureCelsius,
                fallbackWindDir = baseScene.windDirectionDegree
            )

            // 保持 lastWmoCode 与当前融合后的实况天气现象绝对一致
            lastWmoCode = when (mergedScene.weatherState) {
                WeatherState.CLEAR -> 0
                WeatherState.CLOUDY -> 2
                WeatherState.OVERCAST -> 3
                WeatherState.RAIN -> if (mergedScene.intensity == Intensity.HEAVY) 65 else 63
                WeatherState.SNOW -> if (mergedScene.intensity == Intensity.HEAVY) 75 else 73
                WeatherState.STORM -> 95
                WeatherState.SLEET -> 68
            }

            _sceneConfig.value = mergedScene
        }
    }

    /**
     * 雨前 5~10 分钟单次复验请求 (One-Shot Lock)
     */
    private fun verifyPreRain() {
        if (!qWeatherRepo.configManager.isEnabled || !qWeatherRepo.configManager.isConfigured() || qWeatherRepo.isCircuitBroken) return
        if (isPreRainVerified) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastQWeatherMinutelyTimeMs < MIN_COOLDOWN_MS) return

        isPreRainVerified = true
        Log.i(TAG, "Triggering one-shot pre-rain verification check")

        scope.launch {
            val lat = gpsManager.currentLatitude
            val lon = gpsManager.currentLongitude
            val minResult = qWeatherRepo.getMinutelyPrecipitation(lat, lon)
            if (minResult.isSuccess) {
                lastMinutelyResp = minResult.getOrNull()
                lastQWeatherMinutelyTimeMs = nowMs
                parseMinutelyTimeline(lastMinutelyResp)

                val mergedScene = WeatherSceneMapper.mapQWeatherToScene(
                    qNow = lastQNow,
                    minutely = lastMinutelyResp,
                    openMeteoWmoCode = lastWmoCode,
                    nowUnix = nowMs / 1000L,
                    sunriseUnix = openMeteoRepo.lastSunriseUnix,
                    sunsetUnix = openMeteoRepo.lastSunsetUnix,
                    fallbackWindSpeed = _sceneConfig.value.windSpeedKmh,
                    fallbackTemp = _sceneConfig.value.temperatureCelsius,
                    fallbackWindDir = _sceneConfig.value.windDirectionDegree
                )
                lastWmoCode = when (mergedScene.weatherState) {
                    WeatherState.CLEAR -> 0
                    WeatherState.CLOUDY -> 2
                    WeatherState.OVERCAST -> 3
                    WeatherState.RAIN -> if (mergedScene.intensity == Intensity.HEAVY) 65 else 63
                    WeatherState.SNOW -> if (mergedScene.intensity == Intensity.HEAVY) 75 else 73
                    WeatherState.STORM -> 95
                    WeatherState.SLEET -> 68
                }
                _sceneConfig.value = mergedScene
            }
        }
    }

    /**
     * 解析分钟级雷达切片时间戳，建立本地递减时间轴
     */
    private fun parseMinutelyTimeline(resp: QWeatherMinutelyResponse?) {
        val items = resp?.minutely ?: return
        if (items.isEmpty()) return

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.US)
        sdf.timeZone = TimeZone.getDefault()

        val firstPrecip = items.firstOrNull()?.precip?.toFloatOrNull() ?: 0f
        lastObservedPrecip = firstPrecip

        if (firstPrecip > 0.05f) {
            // 当前有雨，寻找何时雨停
            isTargetRainStart = false
            val stopItem = items.firstOrNull { (it.precip.toFloatOrNull() ?: 0f) <= 0.05f }
            if (stopItem != null) {
                try {
                    predictedRainTargetUnix = (sdf.parse(stopItem.fxTime)?.time ?: 0L) / 1000L
                } catch (_: Exception) {
                    predictedRainTargetUnix = 0L
                }
            } else {
                predictedRainTargetUnix = 0L
            }
        } else {
            // 当前无雨，寻找何时开始下雨
            isTargetRainStart = true
            val startItem = items.firstOrNull { (it.precip.toFloatOrNull() ?: 0f) > 0.05f }
            if (startItem != null) {
                try {
                    predictedRainTargetUnix = (sdf.parse(startItem.fxTime)?.time ?: 0L) / 1000L
                } catch (_: Exception) {
                    predictedRainTargetUnix = 0L
                }
            } else {
                predictedRainTargetUnix = 0L
                isPreRainVerified = false // 彻底无雨时重置复验锁
            }
        }
    }

    /**
     * 15分钟长周期轮询
     */
    private fun startPeriodicPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                refreshWeather(force = false)
            }
        }
    }

    /**
     * 本地 1 分钟时间轴推演器：
     * 1. 自适应滑动分钟降水胶囊文字（0 网络消耗）
     * 2. 雨前 5~10 分钟触发单次复验锁
     * 3. 本地平滑跨阶昼夜晨昏光影过渡
     */
    private fun startLocalTimelineTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(60 * 1000L)

                val nowUnix = System.currentTimeMillis() / 1000L
                val current = _sceneConfig.value

                // A. 本地分钟降水倒计时推进
                var updatedSummary = current.minuteRainSummary
                var isRainActive = current.isMinuteRainActive

                if (predictedRainTargetUnix > nowUnix) {
                    val remainingMinutes = ((predictedRainTargetUnix - nowUnix) / 60).toInt()
                    if (isTargetRainStart) {
                        updatedSummary = "预计 $remainingMinutes 分钟后降雨"
                        isRainActive = true
                        // 雨前 5~10 分钟单次复验触发
                        if (remainingMinutes in 5..10 && !isPreRainVerified) {
                            verifyPreRain()
                        }
                    } else {
                        updatedSummary = "预计 $remainingMinutes 分钟后雨停"
                        isRainActive = true
                    }
                } else if (predictedRainTargetUnix > 0L && predictedRainTargetUnix <= nowUnix) {
                    // 到达临界点：清空推演，触发一次刷新以同步实况
                    predictedRainTargetUnix = 0L
                    refreshWeather(force = false)
                }

                // B. 本地昼夜光影平滑过渡计算 (严格保留当前确立的气象现象，防止将阴天错误重置为多云/晴天)
                val updatedScene = WeatherSceneMapper.updateSceneTimeline(
                    current = current,
                    nowUnix = nowUnix,
                    updatedMinuteRainSummary = updatedSummary,
                    isMinuteRainActive = isRainActive
                )

                if (updatedScene.timeOfDay != current.timeOfDay ||
                    updatedScene.minuteRainSummary != current.minuteRainSummary ||
                    updatedScene.weatherState != current.weatherState) {
                    _sceneConfig.value = updatedScene
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WeatherFusionEngine"
        private const val MIN_COOLDOWN_MS = 8 * 60 * 1000L
    }
}

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
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrivacyUtil
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
    private var retryJob: Job? = null
    private var wakeupJob: Job? = null
    private var refreshJob: Job? = null

    // 冷却与单次校验标志（仅真实在线拉取成功时才更新，断网回退缓存绝不毒化！）
    private var lastQWeatherNowTimeMs: Long = 0L
    private var lastQWeatherMinutelyTimeMs: Long = 0L
    @Volatile
    private var lastOnlineSuccessTimeMs: Long = 0L
    private var consecutiveFailureCount: Int = 0
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
                sunsetUnix = sunsetUnix,
                predictedRainTargetUnix = predictedRainTargetUnix,
                isTargetRainStart = isTargetRainStart
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
            // 出库/点火 4G 联网恢复：3 秒防抖，若尚未拿到新鲜在线天气或距上次成功超 10 分钟，直接强制静默补偿拉取
            silentDebounceJob?.cancel()
            silentDebounceJob = scope.launch {
                delay(3000L)
                val staleMs = System.currentTimeMillis() - lastOnlineSuccessTimeMs
                if (lastOnlineSuccessTimeMs > 0L && staleMs < 60_000L) {
                    return@launch
                }
                val needForce = (lastOnlineSuccessTimeMs == 0L || staleMs >= 10 * 60 * 1000L)
                AppLogger.i(TAG, "🌐 [网络恢复补偿] 车机网络已就绪，触发天气刷新 (force=$needForce, 距上次在线成功=${if (lastOnlineSuccessTimeMs == 0L) "从未" else "${staleMs / 1000}s"})")
                gpsManager.requestFreshLocationForWakeup()
                refreshWeather(force = needForce)
            }
        }
    }

    fun start() {
        registerNetworkCallback()

        // 1. GPS 就绪监听 (自带首次实时定位快速纠偏 + 常规 15min/5km 复合门禁)
        // 注意：startLocationUpdates 内部会自动回调一次已知坐标触发首轮刷新，无需重复发两次并发请求
        gpsManager.startLocationUpdates { lat, lon ->
            val needForce = (lastOnlineSuccessTimeMs == 0L)
            refreshWeather(lat, lon, force = needForce)
        }

        // 2. 启动 15 分钟长周期常规轮询
        startPeriodicPolling()

        // 3. 启动 1 分钟短周期本地时间轴、昼夜推演与挂钟超时保底检测
        startLocalTimelineTicker()
    }

    /**
     * 车机 STR 休眠唤醒 / 屏幕点亮 / ACC_ON 点火上车调用：
     * 解决车机熄火休眠一整晚后，早起上车亮屏时协程 uptimeMillis 冻结导致天气停留在昨天的痛点！
     */
    fun onDeviceWakeUp(reason: String = "屏幕唤醒") {
        val nowMs = System.currentTimeMillis()
        val staleMs = nowMs - lastOnlineSuccessTimeMs
        // 只要从未在线成功，或距离上次真实在线更新已超 5 分钟，立即预约唤醒补偿刷新
        if (lastOnlineSuccessTimeMs == 0L || staleMs >= 5 * 60 * 1000L) {
            AppLogger.i(TAG, "☀️ [上车唤醒补偿·$reason] 距上次真实在线刷新已过 ${if (lastOnlineSuccessTimeMs == 0L) "从未成功" else "${staleMs / 1000}s"}，3.5s 后等待车机 4G/GPS 苏醒并强制拉取实况")
            gpsManager.requestFreshLocationForWakeup()
            wakeupJob?.cancel()
            wakeupJob = scope.launch {
                delay(3500L)
                gpsManager.requestFreshLocationForWakeup()
                refreshWeather(gpsManager.currentLatitude, gpsManager.currentLongitude, force = true)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        tickerJob?.cancel()
        silentDebounceJob?.cancel()
        retryJob?.cancel()
        wakeupJob?.cancel()
        refreshJob?.cancel()
        gpsManager.stopLocationUpdates()
        unregisterNetworkCallback()
    }

    /**
     * 断网/弱网阶梯退避重试：针对车机冷启动前 1~2 分钟 T-BOX 尚未完全就绪或刚出地库丢包场景
     * 15s -> 30s -> 60s -> 120s 快速补偿，一旦成功立即终止重试
     */
    private fun scheduleFailureRetry(lat: Double, lon: Double) {
        if (consecutiveFailureCount >= RETRY_DELAYS_MS.size) {
            AppLogger.w(TAG, "⏳ [天气退避重试] 已达快速重试上限 (${RETRY_DELAYS_MS.size}次)，交由网络恢复回调与常规轮询兜底")
            return
        }
        val delayMs = RETRY_DELAYS_MS[consecutiveFailureCount]
        consecutiveFailureCount++
        retryJob?.cancel()
        retryJob = scope.launch {
            AppLogger.i(TAG, "🔁 [天气退避重试] 本次在线拉取失败，预约 ${delayMs / 1000}s 后执行第 $consecutiveFailureCount 次补偿重试...")
            delay(delayMs)
            gpsManager.requestFreshLocationForWakeup()
            refreshWeather(gpsManager.currentLatitude, gpsManager.currentLongitude, force = true)
        }
    }

    /**
     * 核心刷新方法：执行双源并发融合调度
     */
        fun refreshWeather(
        lat: Double = gpsManager.currentLatitude,
        lon: Double = gpsManager.currentLongitude,
        force: Boolean = false
    ) {
        // 若已有相同/非强制刷新任务在途，避免重复打断并取消底层的 HTTPS/SSL 握手
        if (!force && refreshJob?.isActive == true) {
            AppLogger.d(TAG, "⏳ [天气引擎] 已有在线刷新在途，跳过非强制重叠触发以保护连接复用")
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val nowMs = System.currentTimeMillis()
            val nowUnix = nowMs / 1000L
            AppLogger.i(TAG, "🌐【双天气引擎刷新】坐标: lat=${PrivacyUtil.maskCoordinate(lat)}, lon=${PrivacyUtil.maskCoordinate(lon)} (force=$force)")

            // 强制刷新时重置熔断标记
            if (force) {
                qWeatherRepo.resetCircuitBreaker()
            }

            val qWeatherEnabled = qWeatherRepo.configManager.isEnabled &&
                qWeatherRepo.configManager.isConfigured() &&
                !qWeatherRepo.isCircuitBroken

            val distMeters = if (lastQWeatherLat != 0.0 && lastQWeatherLon != 0.0) {
                val results = FloatArray(1)
                Location.distanceBetween(lastQWeatherLat, lastQWeatherLon, lat, lon, results)
                results[0]
            } else {
                Float.MAX_VALUE
            }
            val isLocationMoved = distMeters >= 3000f
            val needNowUpdate = qWeatherEnabled && (force || isLocationMoved || lastQWeatherNowTimeMs == 0L || (nowMs - lastQWeatherNowTimeMs >= 15 * 60 * 1000L))

            // 步骤 1 & 2 并发执行：Open-Meteo 与和风实况同时发起，绝不因海外 Open-Meteo 偶发超时 8 秒而卡死国内和风实况！
            var omScene: WeatherSceneConfig? = null
            var omOnlineOk = false
            var qNowOnlineOk = false

            coroutineScope {
                val omDeferred = async {
                    val res = openMeteoRepo.getWeatherScene(lat, lon)
                    Pair(res.getOrNull(), openMeteoRepo.lastFetchFromNetwork)
                }
                val qNowDeferred = if (needNowUpdate) {
                    async { qWeatherRepo.getWeatherNow(lat, lon) }
                } else null

                val (fetchedOm, isOmNet) = omDeferred.await()
                omScene = fetchedOm
                omOnlineOk = isOmNet

                if (qNowDeferred != null) {
                    val nowResult = qNowDeferred.await()
                    if (nowResult.isSuccess) {
                        val freshNow = nowResult.getOrNull()
                        if (freshNow != null) {
                            lastQNow = freshNow
                            lastQWeatherNowTimeMs = nowMs
                            lastQWeatherLat = lat
                            lastQWeatherLon = lon
                            qNowOnlineOk = true
                            AppLogger.i(TAG, "✅ [和风实况在线更新] temp=${freshNow.temp}℃, text=${freshNow.text}, wind=${freshNow.windDir} ${freshNow.windScale}级")
                        }
                    } else {
                        AppLogger.w(TAG, "❌ [和风实况请求失败] 保留冷却锁未毒化，等待重试: ${nowResult.exceptionOrNull()?.message}")
                    }
                }
            }

            val baseScene = omScene ?: _sceneConfig.value
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

            val anyOnlineSuccess = if (qWeatherEnabled) (qNowOnlineOk || (!needNowUpdate && omOnlineOk)) else omOnlineOk
            if (anyOnlineSuccess) {
                lastOnlineSuccessTimeMs = nowMs
                consecutiveFailureCount = 0
                retryJob?.cancel()
            } else {
                // 在线拉取全部失败（刚点火断网/地库弱网），自动启动阶梯退避重试！
                scheduleFailureRetry(lat, lon)
            }

            if (!qWeatherEnabled) {
                // 纯免费 Open-Meteo 模式：仅当在线成功或当前还是默认空场景时更新界面，防止覆盖有效缓存
                if (omOnlineOk || _sceneConfig.value == WeatherSceneConfig.DEFAULT) {
                    _sceneConfig.value = baseScene
                }
                return@launch
            }

            // 步骤 3：降水守门人 (Rain Gatekeeper) —— POP >= 20% 或当前基础为雨雪天时唤醒分钟降水
            val isRainSign = pop >= 20 || baseScene.weatherState in listOf(
                WeatherState.RAIN, WeatherState.SNOW, WeatherState.STORM, WeatherState.SLEET
            )
            val cooldownOk = (lastQWeatherMinutelyTimeMs == 0L || nowMs - lastQWeatherMinutelyTimeMs >= MIN_COOLDOWN_MS)

            if ((isRainSign && cooldownOk) || (qNowOnlineOk && (force || isLocationMoved))) {
                val minResult = qWeatherRepo.getMinutelyPrecipitation(lat, lon)
                if (minResult.isSuccess) {
                    lastMinutelyResp = minResult.getOrNull()
                    lastQWeatherMinutelyTimeMs = nowMs
                    parseMinutelyTimeline(lastMinutelyResp)
                }
            }

            // 步骤 4：综合融合仲裁映射
            val mergedScene = WeatherSceneMapper.mapQWeatherToScene(
                qNow = lastQNow,
                minutely = lastMinutelyResp,
                openMeteoWmoCode = lastWmoCode,
                nowUnix = nowUnix,
                sunriseUnix = sunriseUnix,
                sunsetUnix = sunsetUnix,
                fallbackWindSpeed = baseScene.windSpeedKmh,
                fallbackTemp = baseScene.temperatureCelsius,
                fallbackWindDir = baseScene.windDirectionDegree,
                predictedRainTargetUnix = predictedRainTargetUnix,
                isTargetRainStart = isTargetRainStart
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
                    fallbackWindDir = _sceneConfig.value.windDirectionDegree,
                    predictedRainTargetUnix = predictedRainTargetUnix,
                    isTargetRainStart = isTargetRainStart
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
                        updatedSummary = "预计${remainingMinutes}分钟后降雨"
                        isRainActive = true
                        // 雨前 5~10 分钟单次复验触发
                        if (remainingMinutes in 5..10 && !isPreRainVerified) {
                            verifyPreRain()
                        }
                    } else {
                        updatedSummary = "预计${remainingMinutes}分钟后雨停"
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

                // C. 挂钟时间保底检测：车机 STR 休眠时协程 uptimeMillis 会暂停计时，导致 15 分钟 pollJob 延后；
                //    此处以真实挂钟时间 (System.currentTimeMillis) 校验，一旦距上次真实在线成功超 15 分钟且当前不在刷新，立即补偿拉取！
                val nowMs = System.currentTimeMillis()
                val isStaleByWallClock = (lastOnlineSuccessTimeMs > 0L && nowMs - lastOnlineSuccessTimeMs >= 15 * 60 * 1000L)
                if (isStaleByWallClock && refreshJob?.isActive != true) {
                    AppLogger.i(TAG, "🕒 [挂钟保底] 距上次真实在线刷新已过 ${(nowMs - lastOnlineSuccessTimeMs) / 1000}s，主动触发补偿拉取")
                    refreshWeather(force = false)
                }
            }
        }
    }

    @Volatile
    private var isNetworkCallbackRegistered = false

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, networkCallback)
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isNetworkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WeatherFusionEngine"
        private const val MIN_COOLDOWN_MS = 8 * 60 * 1000L
        private val RETRY_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)
    }
}

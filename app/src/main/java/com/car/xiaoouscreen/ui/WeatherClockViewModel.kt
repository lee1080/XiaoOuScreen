package com.car.xiaoouscreen.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherSceneMapper
import com.car.xiaoouscreen.location.CarGpsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 车机副屏状态调度 ViewModel
 * - 定时轮询网络气象 (默认每 15 分钟)
 * - 纯本地定时检查昼夜切换 (每 60 秒)，实现拂晓/正午/落日/夜晚的无感知秒级平滑过渡
 */
class WeatherClockViewModel(
    private val repository: OpenMeteoRepository,
    private val gpsManager: CarGpsManager
) : ViewModel() {

    private val _sceneConfig = MutableStateFlow(WeatherSceneConfig.DEFAULT)
    val sceneConfig: StateFlow<WeatherSceneConfig> = _sceneConfig.asStateFlow()

    private var weatherPollJob: Job? = null
    private var timeTransitionJob: Job? = null

    // 缓存最新一次成功获取的气象关键字段，用于本地分钟级重新映射昼夜
    private var lastWmoCode: Int = 0
    private var lastSunriseUnix: Long = 0L
    private var lastSunsetUnix: Long = 0L
    private var lastWindSpeed: Float = 10f
    private var lastTemp: Float = 22f
    private var lastWindDir: Float = 0f

    fun start() {
        // 1. 监听 GPS 定位，就绪后立即触发初次拉取
        gpsManager.startLocationUpdates { lat, lon ->
            refreshWeather(lat, lon)
        }

        // 2. 启动 15 分钟长周期气象网络轮询
        startWeatherPolling()

        // 3. 启动 1 分钟短周期本地时间段监测
        startTimeTransitionTicker()
    }

    fun stop() {
        weatherPollJob?.cancel()
        timeTransitionJob?.cancel()
        gpsManager.stopLocationUpdates()
    }

    /**
     * 手动或按需刷新天气
     */
    fun refreshWeather(
        lat: Double = gpsManager.currentLatitude,
        lon: Double = gpsManager.currentLongitude
    ) {
        viewModelScope.launch {
            repository.getWeatherScene(lat, lon).onSuccess { newScene ->
                lastWmoCode = when (newScene.weatherState) {
                    com.car.xiaoouscreen.domain.WeatherState.CLEAR -> 0
                    com.car.xiaoouscreen.domain.WeatherState.CLOUDY -> 2
                    com.car.xiaoouscreen.domain.WeatherState.OVERCAST -> 3
                    com.car.xiaoouscreen.domain.WeatherState.RAIN -> 63
                    com.car.xiaoouscreen.domain.WeatherState.SNOW -> 73
                    com.car.xiaoouscreen.domain.WeatherState.STORM -> 95
                    com.car.xiaoouscreen.domain.WeatherState.SLEET -> 68
                }
                lastSunriseUnix = newScene.sunriseUnix
                lastSunsetUnix = newScene.sunsetUnix
                lastWindSpeed = newScene.windSpeedKmh
                lastTemp = newScene.temperatureCelsius
                lastWindDir = newScene.windDirectionDegree

                _sceneConfig.value = newScene
            }
        }
    }

    private fun startWeatherPolling() {
        weatherPollJob?.cancel()
        weatherPollJob = viewModelScope.launch {
            while (isActive) {
                // 每隔 15 分钟更新一次网络数据
                delay(15 * 60 * 1000L)
                refreshWeather()
            }
        }
    }

    /**
     * 本地时间走时监测：每分钟检测当前 Unix 时间戳是否穿过 日出/日落 ±45 分钟边界
     * 一旦时间跨越时段（例如 18:15 刚好进入黄昏），立刻重新计算场景并触发 1.5s 平滑跨阶渐变，无需等待 15 分钟网络轮询
     */
    private fun startTimeTransitionTicker() {
        timeTransitionJob?.cancel()
        timeTransitionJob = viewModelScope.launch {
            while (isActive) {
                delay(60 * 1000L) // 每 60 秒自检一次

                val nowUnix = System.currentTimeMillis() / 1000L
                val updatedScene = WeatherSceneMapper.mapToScene(
                    wmoCode = lastWmoCode,
                    nowUnix = nowUnix,
                    sunriseUnix = lastSunriseUnix,
                    sunsetUnix = lastSunsetUnix,
                    windSpeed = lastWindSpeed,
                    temperature = lastTemp,
                    windDirection = lastWindDir
                )

                // 若时段或颜色发生改变，下发新的 SceneConfig 驱动 View 内部 cross-fade
                if (updatedScene.timeOfDay != _sceneConfig.value.timeOfDay ||
                    updatedScene.skyTopColor != _sceneConfig.value.skyTopColor) {
                    _sceneConfig.value = updatedScene
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

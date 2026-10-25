package com.car.xiaoouscreen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.data.QWeatherRepository
import com.car.xiaoouscreen.domain.WeatherFusionEngine
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.location.CarGpsManager
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机副屏状态调度 ViewModel
 * 接入 WeatherFusionEngine 实现双气象引擎融合与防刷调度
 */
class WeatherClockViewModel(
    application: Application,
    private val repository: OpenMeteoRepository,
    private val gpsManager: CarGpsManager,
    private val qWeatherRepo: QWeatherRepository = QWeatherRepository(application)
) : AndroidViewModel(application) {

    // 辅助构造函数保持原有双参数兼容
    constructor(
        repository: OpenMeteoRepository,
        gpsManager: CarGpsManager
    ) : this(
        application = com.car.xiaoouscreen.XiaoOuApplication.instance,
        repository = repository,
        gpsManager = gpsManager
    )

    private val fusionEngine = WeatherFusionEngine(
        context = application,
        scope = viewModelScope,
        openMeteoRepo = repository,
        qWeatherRepo = qWeatherRepo,
        gpsManager = gpsManager
    )

    val sceneConfig: StateFlow<WeatherSceneConfig> = fusionEngine.sceneConfig

    fun start() {
        fusionEngine.start()
    }

    fun stop() {
        fusionEngine.stop()
    }

    fun refreshWeather(
        lat: Double = gpsManager.currentLatitude,
        lon: Double = gpsManager.currentLongitude
    ) {
        fusionEngine.refreshWeather(lat, lon, force = true)
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

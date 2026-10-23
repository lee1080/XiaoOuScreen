package com.car.xiaoouscreen.domain

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * 天气基本状态类型 (聚合 WMO 规范，4x7=28 核心组合之一)
 */
enum class WeatherState {
    CLEAR,      // 晴天
    CLOUDY,     // 多云
    OVERCAST,   // 阴天 / 雾
    RAIN,       // 雨 (含小中大雨、毛毛雨、阵雨)
    SNOW,       // 雪 (含小中大雪、阵雪、吹雪、冰针)
    STORM,      // 雷暴 (雷雨、强雷暴、冰雹)
    SLEET       // 雨夹雪 (50%雨 + 50%雪)
}

/**
 * 昼夜时段划分 (基于太阳高度角与日出日落时间，4x7=28 核心组合之一)
 */
enum class TimeOfDay {
    DAWN,   // 清晨 / 晨曦 (日出前后 45 分钟)
    DAY,    // 白天 / 正午
    DUSK,   // 傍晚 / 黄昏 (日落前后 45 分钟)
    NIGHT   // 夜晚 / 深夜
}

/**
 * 降水/风力强度
 */
enum class Intensity {
    LIGHT,  // 轻度 / 柔和
    HEAVY   // 强降水 / 剧烈
}

/**
 * 车机副屏 (1920×384) 渲染场景全量配置对象
 * 将纯气象数据解耦为 Android Canvas 渲染层可直接消费的颜色、粒子密度与光学参数
 */
data class WeatherSceneConfig(
    val weatherState: WeatherState = WeatherState.CLEAR,
    val timeOfDay: TimeOfDay = TimeOfDay.DAY,
    val intensity: Intensity = Intensity.LIGHT,
    val temperatureCelsius: Float = 22.0f,
    val windSpeedKmh: Float = 8.0f,
    val windDirectionDegree: Float = 45.0f,
    val weatherDescription: String = "晴朗",
    val sunriseUnix: Long = 0L,
    val sunsetUnix: Long = 0L,
    val lastUpdateUnix: Long = System.currentTimeMillis() / 1000L,

    // --- 动态天空渐变基色 (顶色与底色) ---
    @ColorInt val skyTopColor: Int = Color.parseColor("#0284C7"),
    @ColorInt val skyBottomColor: Int = Color.parseColor("#BAE6FD"),

    // --- 融入天气的大气数字时钟光学参数 ---
    @ColorInt val clockTextColor: Int = Color.WHITE,
    @ColorInt val clockGlowColor: Int = Color.parseColor("#38BDF8"),
    val clockGlowRadius: Float = 16.0f,
    val clockAtmosphericShadowOffset: Float = 4.0f,

    // --- iOS 风格多层拟真体积云控制参数 ---
    val cloudCirrusAlpha: Float = 0.30f,         // 高空卷云透明度
    val cloudCumulusBackAlpha: Float = 0.0f,     // 远景体积积云透明度
    val cloudCumulusFrontAlpha: Float = 0.0f,    // 前景体积积云透明度
    val cloudMistAlpha: Float = 0.05f,           // 贴地地平线水汽/薄雾透明度
    val cloudOvercastAlpha: Float = 0.0f,        // 阴天/暴雨厚重云盖透明度
    @ColorInt val cloudTintColor: Int = Color.WHITE, // 云层光照物理环境色

    // --- 天体与粒子开关与密度 ---
    val celestialVisible: Boolean = true,
    val starsVisible: Boolean = false,
    val rainParticleCount: Int = 0,
    val snowParticleCount: Int = 0,
    val lightningEnabled: Boolean = false
) {
    companion object {
        val DEFAULT = WeatherSceneConfig()
    }
}

package com.car.xiaoouscreen.domain

import android.graphics.Color
import java.util.Calendar

/**
 * 领域层：纯函数式气象场景映射器
 * 严格执行 WMO 0-99 完整规范与兜底机制，输出 4 时段 × 7 天气 = 28 种拟真场景配置
 */
object WeatherSceneMapper {

    // 现实民用晨昏与大气物理光学非对称过渡窗口 (贴合人体肉眼对晨曦晚霞与黑夜初临的实际感知)
    private const val DAWN_BEFORE_SUNRISE_SECONDS = 25 * 60L // 日出前 25 分钟：东方天际微露鱼肚白、晨光初透
    private const val DAWN_AFTER_SUNRISE_SECONDS = 20 * 60L  // 日出后 20 分钟：旭日跃出地平线、阳光普照，转入白天
    private const val DUSK_BEFORE_SUNSET_SECONDS = 20 * 60L  // 日落前 20 分钟：夕阳金光斜射、晚霞火烧云初现
    private const val DUSK_AFTER_SUNSET_SECONDS = 25 * 60L   // 日落后 25 分钟：民用昏影结束、天空暮色彻底消退，切入黑夜

    /**
     * 重载入口 1：直接通过 WeatherState × TimeOfDay 进行 28 状态模拟（供主屏 4×7 矩阵控制台）
     */
    fun mapToScene(
        state: WeatherState,
        timeOfDay: TimeOfDay,
        intensity: Intensity = Intensity.LIGHT,
        windSpeed: Float = 10.0f,
        temperature: Float = 22.0f,
        windDirection: Float = 45.0f,
        customDescription: String? = null
    ): WeatherSceneConfig {
        val description = customDescription ?: when (state) {
            WeatherState.CLEAR -> "晴朗"
            WeatherState.CLOUDY -> "多云"
            WeatherState.OVERCAST -> "阴天/雾"
            WeatherState.RAIN -> if (intensity == Intensity.HEAVY) "大暴雨" else "中雨"
            WeatherState.SNOW -> if (intensity == Intensity.HEAVY) "大暴雪" else "小雪"
            WeatherState.STORM -> "雷暴天气"
            WeatherState.SLEET -> "雨夹雪"
        }

        val actualTemp = if (state == WeatherState.SLEET && temperature == 22.0f) 1.5f
                         else if (state == WeatherState.SNOW && temperature == 22.0f) -2.0f
                         else temperature

        return buildSceneConfig(
            weatherState = state,
            timeOfDay = timeOfDay,
            intensity = intensity,
            description = description,
            temperature = actualTemp,
            windSpeed = windSpeed,
            windDirection = windDirection,
            sunriseUnix = 0L,
            sunsetUnix = 0L,
            nowUnix = System.currentTimeMillis() / 1000L
        )
    }

    /**
     * 重载入口 2：通过 WMO 代码与强制 TimeOfDay 模拟
     */
    fun mapToScene(
        wmoCode: Int,
        timeOfDay: TimeOfDay,
        windSpeed: Float = 10.0f,
        temperature: Float = 22.0f,
        windDirection: Float = 45.0f,
        nowUnix: Long = System.currentTimeMillis() / 1000L
    ): WeatherSceneConfig {
        val (weatherState, intensity, description) = resolveWmoCode(wmoCode)

        return buildSceneConfig(
            weatherState = weatherState,
            timeOfDay = timeOfDay,
            intensity = intensity,
            description = description,
            temperature = temperature,
            windSpeed = windSpeed,
            windDirection = windDirection,
            sunriseUnix = 0L,
            sunsetUnix = 0L,
            nowUnix = nowUnix
        )
    }

    /**
     * 核心生产入口：通过 WMO 代码与 GPS 经纬度返回的日出日落时间戳计算
     */
    fun mapToScene(
        wmoCode: Int,
        nowUnix: Long,
        sunriseUnix: Long,
        sunsetUnix: Long,
        windSpeed: Float = 10.0f,
        temperature: Float = 22.0f,
        windDirection: Float = 45.0f
    ): WeatherSceneConfig {
        // 1. 判定昼夜 4 时段
        val timeOfDay = resolveTimeOfDay(nowUnix, sunriseUnix, sunsetUnix)

        // 2. 映射 WMO 0-99 状态 (支持 SLEET 自动识别)
        val (weatherState, intensity, description) = resolveWmoCode(wmoCode)

        return buildSceneConfig(
            weatherState = weatherState,
            timeOfDay = timeOfDay,
            intensity = intensity,
            description = description,
            temperature = temperature,
            windSpeed = windSpeed,
            windDirection = windDirection,
            sunriseUnix = sunriseUnix,
            sunsetUnix = sunsetUnix,
            nowUnix = nowUnix
        )
    }

    /**
     * 和风天气实况 + 分钟降水雷达 + Open-Meteo 天文时刻 融合映射核心入口
     */
    fun mapQWeatherToScene(
        qNow: com.car.xiaoouscreen.data.QWeatherNowDto?,
        minutely: com.car.xiaoouscreen.data.QWeatherMinutelyResponse?,
        openMeteoWmoCode: Int,
        nowUnix: Long,
        sunriseUnix: Long,
        sunsetUnix: Long,
        fallbackWindSpeed: Float = 10f,
        fallbackTemp: Float = 22f,
        fallbackWindDir: Float = 45f
    ): WeatherSceneConfig {
        val timeOfDay = resolveTimeOfDay(nowUnix, sunriseUnix, sunsetUnix)

        // 基础温湿与风力解析
        val temp = qNow?.temp?.toFloatOrNull() ?: fallbackTemp
        val feelsLike = qNow?.feelsLike?.toFloatOrNull() ?: temp
        val windSpeed = qNow?.windSpeed?.toFloatOrNull() ?: fallbackWindSpeed
        val windDirDegree = qNow?.wind360?.toFloatOrNull() ?: fallbackWindDir
        val windDirText = qNow?.windDir ?: resolveWindDirText(windDirDegree)
        val windScaleText = qNow?.windScale?.let { "${it}级" } ?: resolveWindScaleText(windSpeed)

        // 分钟级降水雷达外推切片解析
        val summary = minutely?.summary
        val hasRainSummary = !summary.isNullOrBlank() && summary != "未来两小时无降水"
        val currentSlot = minutely?.minutely?.firstOrNull()
        val currentPrecip = currentSlot?.precip?.toFloatOrNull() ?: 0f
        val isRadarRaining = currentPrecip > 0.05f

        val weatherState: WeatherState
        val intensity: Intensity
        val description: String

        if (isRadarRaining) {
            // 雷达超短临实况见降水：最高优先级裁决覆盖
            if (currentSlot?.type == "snow") {
                weatherState = WeatherState.SNOW
                intensity = if (currentPrecip > 2.0f) Intensity.HEAVY else Intensity.LIGHT
                description = if (intensity == Intensity.HEAVY) "大暴雪" else "小雪"
            } else {
                if (temp <= 2.0f) {
                    weatherState = WeatherState.SLEET
                    intensity = Intensity.LIGHT
                    description = "雨夹雪"
                } else if (qNow?.icon in listOf("302", "303", "304") || openMeteoWmoCode in listOf(95, 96, 99)) {
                    weatherState = WeatherState.STORM
                    intensity = Intensity.HEAVY
                    description = "雷暴天气"
                } else {
                    weatherState = WeatherState.RAIN
                    intensity = if (currentPrecip > 1.5f) Intensity.HEAVY else Intensity.LIGHT
                    description = if (intensity == Intensity.HEAVY) "大暴雨" else "中雨"
                }
            }
        } else if (qNow != null) {
            // 雷达无雨但有和风地面实况
            val (qState, qIntensity, qDesc) = mapQWeatherIconToState(qNow.icon, qNow.text)
            weatherState = qState
            intensity = qIntensity
            description = qDesc
        } else {
            // 回退纯 Open-Meteo WMO 代码
            val (wState, wIntensity, wDesc) = resolveWmoCode(openMeteoWmoCode)
            weatherState = wState
            intensity = wIntensity
            description = wDesc
        }

        return buildSceneConfig(
            weatherState = weatherState,
            timeOfDay = timeOfDay,
            intensity = intensity,
            description = description,
            temperature = temp,
            windSpeed = windSpeed,
            windDirection = windDirDegree,
            sunriseUnix = sunriseUnix,
            sunsetUnix = sunsetUnix,
            nowUnix = nowUnix,
            feelsLike = feelsLike,
            windDirText = windDirText,
            windScaleText = windScaleText,
            minuteRainSummary = summary,
            isMinuteRainActive = hasRainSummary
        )
    }

    /**
     * 本地时间轴推进与光影重算（严格保留当前已确立的气象现象 WeatherState 与降水强度）
     * 杜绝将阴天、暴雨等天气降级为多云或晴天
     */
    fun updateSceneTimeline(
        current: WeatherSceneConfig,
        nowUnix: Long,
        updatedMinuteRainSummary: String?,
        isMinuteRainActive: Boolean
    ): WeatherSceneConfig {
        val newTimeOfDay = resolveTimeOfDay(nowUnix, current.sunriseUnix, current.sunsetUnix)
        return buildSceneConfig(
            weatherState = current.weatherState,
            timeOfDay = newTimeOfDay,
            intensity = current.intensity,
            description = current.weatherDescription,
            temperature = current.temperatureCelsius,
            windSpeed = current.windSpeedKmh,
            windDirection = current.windDirectionDegree,
            sunriseUnix = current.sunriseUnix,
            sunsetUnix = current.sunsetUnix,
            nowUnix = nowUnix,
            feelsLike = current.feelsLikeCelsius,
            windDirText = current.windDirectionText,
            windScaleText = current.windScaleText,
            minuteRainSummary = updatedMinuteRainSummary,
            isMinuteRainActive = isMinuteRainActive
        )
    }

    fun mapQWeatherIconToState(icon: String, text: String): Triple<WeatherState, Intensity, String> {
        val cleanIcon = icon.trim()
        val cleanText = text.trim()

        // 1. 语义最高优先级对齐：确保屏幕上呈现的文字与后面的背景动画绝对 100% 吻合！
        if (cleanText.isNotEmpty()) {
            when {
                cleanText.contains("雷") -> {
                    return Triple(WeatherState.STORM, Intensity.HEAVY, cleanText)
                }
                cleanText.contains("雨夹雪") -> {
                    return Triple(WeatherState.SLEET, Intensity.LIGHT, cleanText)
                }
                cleanText.contains("雪") -> {
                    val isHeavy = cleanIcon in listOf("402", "403", "409", "410") || cleanText.contains("暴雪") || cleanText.contains("大雪")
                    return Triple(WeatherState.SNOW, if (isHeavy) Intensity.HEAVY else Intensity.LIGHT, cleanText)
                }
                cleanText.contains("雨") -> {
                    val isHeavy = cleanIcon in listOf("301", "307", "308", "310", "311", "312", "316", "317", "318", "351") ||
                                  cleanText.contains("大雨") || cleanText.contains("暴雨")
                    return Triple(WeatherState.RAIN, if (isHeavy) Intensity.HEAVY else Intensity.LIGHT, cleanText)
                }
                cleanText.contains("阴") || cleanText.contains("雾") || cleanText.contains("霾") ||
                cleanText.contains("沙") || cleanText.contains("尘") -> {
                    return Triple(WeatherState.OVERCAST, Intensity.LIGHT, cleanText)
                }
                cleanText.contains("多云") || cleanText.contains("少云") -> {
                    return Triple(WeatherState.CLOUDY, Intensity.LIGHT, cleanText)
                }
                cleanText.contains("晴") -> {
                    return Triple(WeatherState.CLEAR, Intensity.LIGHT, cleanText)
                }
            }
        }

        // 2. 代码解析兜底 (当 text 为空时依据标准和风 Weather Code 映射)
        return when (cleanIcon) {
            "100", "150" -> Triple(WeatherState.CLEAR, Intensity.LIGHT, cleanText.ifBlank { "晴朗" })
            "101", "102", "103", "151", "152", "153" -> Triple(WeatherState.CLOUDY, Intensity.LIGHT, cleanText.ifBlank { "多云" })
            "104" -> Triple(WeatherState.OVERCAST, Intensity.LIGHT, cleanText.ifBlank { "阴天" })
            "302", "303", "304" -> Triple(WeatherState.STORM, Intensity.HEAVY, cleanText.ifBlank { "雷暴天气" })
            "313", "404", "405", "406", "456" -> Triple(WeatherState.SLEET, Intensity.LIGHT, cleanText.ifBlank { "雨夹雪" })
            in "300".."399" -> {
                val isHeavy = cleanIcon in listOf("301", "307", "308", "310", "311", "312", "316", "317", "318", "351")
                Triple(WeatherState.RAIN, if (isHeavy) Intensity.HEAVY else Intensity.LIGHT, cleanText.ifBlank { "降雨" })
            }
            in "400".."499" -> {
                val isHeavy = cleanIcon in listOf("402", "403", "409", "410")
                Triple(WeatherState.SNOW, if (isHeavy) Intensity.HEAVY else Intensity.LIGHT, cleanText.ifBlank { "降雪" })
            }
            in "500".."515" -> Triple(WeatherState.OVERCAST, Intensity.LIGHT, cleanText.ifBlank { "阴天/霾" })
            else -> Triple(WeatherState.CLEAR, Intensity.LIGHT, cleanText.ifBlank { "晴朗" })
        }
    }

    fun resolveWindDirText(degrees: Float): String {
        val deg = (degrees % 360 + 360) % 360
        return when {
            deg >= 337.5 || deg < 22.5 -> "北风"
            deg < 67.5 -> "东北风"
            deg < 112.5 -> "东风"
            deg < 157.5 -> "东南风"
            deg < 202.5 -> "南风"
            deg < 247.5 -> "西南风"
            deg < 292.5 -> "西风"
            else -> "西北风"
        }
    }

    fun resolveWindScaleText(speedKmh: Float): String {
        return when {
            speedKmh < 1f -> "0级"
            speedKmh <= 5f -> "1级"
            speedKmh <= 11f -> "2级"
            speedKmh <= 19f -> "3级"
            speedKmh <= 28f -> "4级"
            speedKmh <= 38f -> "5级"
            speedKmh <= 49f -> "6级"
            speedKmh <= 61f -> "7级"
            speedKmh <= 74f -> "8级"
            speedKmh <= 88f -> "9级"
            speedKmh <= 102f -> "10级"
            speedKmh <= 117f -> "11级"
            else -> "12级"
        }
    }

    /**
     * 内部装配器：装配天空渐变基色、iOS 多层云参数、粒子数量与时钟环境光
     */
    private fun buildSceneConfig(
        weatherState: WeatherState,
        timeOfDay: TimeOfDay,
        intensity: Intensity,
        description: String,
        temperature: Float,
        windSpeed: Float,
        windDirection: Float,
        sunriseUnix: Long,
        sunsetUnix: Long,
        nowUnix: Long,
        feelsLike: Float = temperature,
        windDirText: String = resolveWindDirText(windDirection),
        windScaleText: String = resolveWindScaleText(windSpeed),
        minuteRainSummary: String? = null,
        isMinuteRainActive: Boolean = false
    ): WeatherSceneConfig {
        // 计算 4x7=28 种天空色彩组合
        val (skyTop, skyBottom) = resolveSkyColors(timeOfDay, weatherState)

        // 计算 iOS 风格拟真云层各图层透明度与物理光照色温
        val (cirrusA, cumulusBackA, cumulusFrontA, mistA, overcastA, cloudTint) =
            resolveCloudLayerParams(timeOfDay, weatherState)

        // 计算融入天气的大气数字时钟发光
        val (clockText, clockGlow, glowRadius) = resolveClockAtmosphere(timeOfDay, weatherState)

        // 天体与星空可见性
        val celestialVisible = (weatherState == WeatherState.CLEAR || weatherState == WeatherState.CLOUDY)
        val starsVisible = (timeOfDay == TimeOfDay.NIGHT && celestialVisible)

        // 雨雪粒子分配 (支持雨夹雪 SLEET 同时开启 50% 雨 + 50% 雪)
        val rainParticles: Int
        val snowParticles: Int
        when (weatherState) {
            WeatherState.SLEET -> {
                rainParticles = 65
                snowParticles = 50
            }
            WeatherState.RAIN -> {
                rainParticles = if (intensity == Intensity.HEAVY) 150 else 75
                snowParticles = 0
            }
            WeatherState.SNOW -> {
                rainParticles = 0
                snowParticles = if (intensity == Intensity.HEAVY) 110 else 55
            }
            WeatherState.STORM -> {
                rainParticles = 160
                snowParticles = 0
            }
            else -> {
                rainParticles = 0
                snowParticles = 0
            }
        }

        val lightningEnabled = (weatherState == WeatherState.STORM)

        return WeatherSceneConfig(
            weatherState = weatherState,
            timeOfDay = timeOfDay,
            intensity = intensity,
            temperatureCelsius = temperature,
            windSpeedKmh = windSpeed,
            windDirectionDegree = windDirection,
            weatherDescription = description,
            sunriseUnix = sunriseUnix,
            sunsetUnix = sunsetUnix,
            lastUpdateUnix = nowUnix,
            skyTopColor = skyTop,
            skyBottomColor = skyBottom,
            clockTextColor = clockText,
            clockGlowColor = clockGlow,
            clockGlowRadius = glowRadius,
            cloudCirrusAlpha = cirrusA,
            cloudCumulusBackAlpha = cumulusBackA,
            cloudCumulusFrontAlpha = cumulusFrontA,
            cloudMistAlpha = mistA,
            cloudOvercastAlpha = overcastA,
            cloudTintColor = cloudTint,
            celestialVisible = celestialVisible,
            starsVisible = starsVisible,
            rainParticleCount = rainParticles,
            snowParticleCount = snowParticles,
            lightningEnabled = lightningEnabled,
            feelsLikeCelsius = feelsLike,
            windDirectionText = windDirText,
            windScaleText = windScaleText,
            minuteRainSummary = minuteRainSummary,
            isMinuteRainActive = isMinuteRainActive
        )
    }

    /**
     * 精准判断 DAWN / DAY / DUSK / NIGHT (4 时段)
     * 1. 优先使用实车经纬度天文日出日落 + 非对称民用晨昏窗口计算 (日落后 25 分钟准时切入夜晚，天黑透不再显示傍晚)
     * 2. 无天文数据时，采用区分秋冬季 (10~3月) 与春夏季 (4~9月) 的智能动态钟表兜底
     */
    fun resolveTimeOfDay(nowUnix: Long, sunriseUnix: Long, sunsetUnix: Long): TimeOfDay {
        if (sunriseUnix <= 0L || sunsetUnix <= 0L || sunsetUnix <= sunriseUnix) {
            val cal = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) + 1 // 1 ~ 12
            val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            // 区分秋冬季 (10月~3月，昼短夜长) 与春夏季 (4月~9月，昼长夜短)
            val isWinterHalf = (month in 10..12 || month in 1..3)
            return if (isWinterHalf) {
                // 秋冬季：清晨 06:30~07:15, 白天 07:15~17:30, 傍晚 17:30~18:15, 夜晚 18:15~06:30 (18:15 准时切入夜景，彻底告别粗糙拖延至20点)
                when (minuteOfDay) {
                    in (6 * 60 + 30) until (7 * 60 + 15) -> TimeOfDay.DAWN
                    in (7 * 60 + 15) until (17 * 60 + 30) -> TimeOfDay.DAY
                    in (17 * 60 + 30) until (18 * 60 + 15) -> TimeOfDay.DUSK
                    else -> TimeOfDay.NIGHT
                }
            } else {
                // 春夏季：清晨 05:15~06:00, 白天 06:00~18:45, 傍晚 18:45~19:30, 夜晚 19:30~05:15
                when (minuteOfDay) {
                    in (5 * 60 + 15) until (6 * 60 + 0) -> TimeOfDay.DAWN
                    in (6 * 60 + 0) until (18 * 60 + 45) -> TimeOfDay.DAY
                    in (18 * 60 + 45) until (19 * 60 + 30) -> TimeOfDay.DUSK
                    else -> TimeOfDay.NIGHT
                }
            }
        }

        return when {
            // 清晨：日出前 25 分钟至日出后 20 分钟 (东方破晓 -> 旭日完全升起，共 45 分钟)
            nowUnix in (sunriseUnix - DAWN_BEFORE_SUNRISE_SECONDS)..(sunriseUnix + DAWN_AFTER_SUNRISE_SECONDS) -> TimeOfDay.DAWN

            // 傍晚：日落前 20 分钟至日落后 25 分钟 (夕阳斜照晚霞 -> 民用暮光结束，天黑透准时切夜景，共 45 分钟)
            nowUnix in (sunsetUnix - DUSK_BEFORE_SUNSET_SECONDS)..(sunsetUnix + DUSK_AFTER_SUNSET_SECONDS) -> TimeOfDay.DUSK

            // 白天：清晨结束后至傍晚开始前
            nowUnix in (sunriseUnix + DAWN_AFTER_SUNRISE_SECONDS)..(sunsetUnix - DUSK_BEFORE_SUNSET_SECONDS) -> TimeOfDay.DAY

            // 其余时段一律为黑夜
            else -> TimeOfDay.NIGHT
        }
    }

    /**
     * 完整 WMO (0-99) 区间逻辑与兜底方案
     * 自动识别雨夹雪 (SLEET) WMO: 68, 69, 83, 84
     */
    data class WmoResult(
        val state: WeatherState,
        val intensity: Intensity,
        val description: String
    )

    fun resolveWmoCode(code: Int): WmoResult {
        return when (code) {
            // 00 - 19 (无降水与视程障碍)
            0 -> WmoResult(WeatherState.CLEAR, Intensity.LIGHT, "晴朗")
            1 -> WmoResult(WeatherState.CLOUDY, Intensity.LIGHT, "大部晴朗")
            2 -> WmoResult(WeatherState.CLOUDY, Intensity.LIGHT, "局部多云")
            3 -> WmoResult(WeatherState.OVERCAST, Intensity.LIGHT, "阴天")
            in 4..9 -> WmoResult(WeatherState.OVERCAST, Intensity.LIGHT, "浮尘霾") // 烟霾沙尘兜底
            in 10..12 -> WmoResult(WeatherState.OVERCAST, Intensity.LIGHT, "轻雾")
            in 13..16 -> WmoResult(WeatherState.STORM, Intensity.LIGHT, "远景雷电")
            in 17..19 -> WmoResult(WeatherState.STORM, Intensity.HEAVY, "飑线暴风")

            // 20 - 29 (过去一小时发生过、当前已停止)
            in 20..29 -> WmoResult(WeatherState.CLOUDY, Intensity.LIGHT, "降水后转多云")

            // 30 - 39 (沙尘暴与吹雪)
            in 30..35 -> WmoResult(WeatherState.OVERCAST, Intensity.HEAVY, "沙尘暴")
            in 36..39 -> WmoResult(WeatherState.SNOW, Intensity.HEAVY, "暴风吹雪")

            // 40 - 49 (大雾 Fog)
            in 40..47 -> WmoResult(WeatherState.OVERCAST, Intensity.LIGHT, "浓雾")
            48, 49 -> WmoResult(WeatherState.OVERCAST, Intensity.HEAVY, "冻雾伴霜")

            // 50 - 59 (毛毛雨 Drizzle)
            51, 53 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "毛毛细雨")
            55 -> WmoResult(WeatherState.RAIN, Intensity.HEAVY, "密毛雨")
            56, 57 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "冻毛毛雨")
            58, 59 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "毛毛雨夹雨")

            // 60 - 69 (持续性降雨 Rain 与雨夹雪 Sleet)
            60, 61 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "小雨")
            62, 63 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "中雨")
            64, 65 -> WmoResult(WeatherState.RAIN, Intensity.HEAVY, "大暴雨")
            66, 67 -> WmoResult(WeatherState.RAIN, Intensity.HEAVY, "冻雨")
            68, 69 -> WmoResult(WeatherState.SLEET, Intensity.LIGHT, "雨夹雪") // 自动识别雨夹雪

            // 70 - 79 (持续性固态降水 Snow)
            70, 71 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "小雪")
            72, 73 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "中雪")
            74, 75 -> WmoResult(WeatherState.SNOW, Intensity.HEAVY, "大暴雪")
            76 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "冰针")
            77 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "米雪")
            78 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "雪晶")
            79 -> WmoResult(WeatherState.SNOW, Intensity.HEAVY, "冰粒")

            // 80 - 90 (阵性降水 Showers)
            80 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "弱阵雨")
            81 -> WmoResult(WeatherState.RAIN, Intensity.LIGHT, "阵雨")
            82 -> WmoResult(WeatherState.RAIN, Intensity.HEAVY, "暴烈阵雨")
            83, 84 -> WmoResult(WeatherState.SLEET, Intensity.LIGHT, "阵性雨夹雪") // 自动识别阵性雨夹雪
            85 -> WmoResult(WeatherState.SNOW, Intensity.LIGHT, "阵雪")
            86 -> WmoResult(WeatherState.SNOW, Intensity.HEAVY, "暴阵雪")
            in 87..90 -> WmoResult(WeatherState.RAIN, Intensity.HEAVY, "霰与冰雹阵雨")

            // 91 - 99 (雷暴 Thunderstorm)
            in 91..94 -> WmoResult(WeatherState.STORM, Intensity.LIGHT, "轻度雷暴")
            95 -> WmoResult(WeatherState.STORM, Intensity.LIGHT, "雷暴")
            96, 99 -> WmoResult(WeatherState.STORM, Intensity.HEAVY, "强雷暴伴冰雹")

            // 兜底 Fallback
            else -> WmoResult(WeatherState.CLEAR, Intensity.LIGHT, "晴朗")
        }
    }

    /**
     * 计算 4 时段 × 7 天气 = 28 种真实物理天空色彩
     */
    private fun resolveSkyColors(timeOfDay: TimeOfDay, state: WeatherState): Pair<Int, Int> {
        return when (timeOfDay) {
            TimeOfDay.DAWN -> when (state) {
                WeatherState.CLEAR -> Pair(Color.parseColor("#1E1B4B"), Color.parseColor("#FDBA74")) // 深靛蓝紫 -> 晨曦金桃
                WeatherState.CLOUDY -> Pair(Color.parseColor("#2E1065"), Color.parseColor("#FB923C")) // 晨曦蓝紫 -> 晨光粉橘
                WeatherState.OVERCAST -> Pair(Color.parseColor("#334155"), Color.parseColor("#94A3B8")) // 晨曦青灰大雾
                WeatherState.RAIN -> Pair(Color.parseColor("#1E293B"), Color.parseColor("#475569")) // 晨雨青黑 -> 潮湿冷灰
                WeatherState.SNOW -> Pair(Color.parseColor("#334155"), Color.parseColor("#CBD5E1")) // 晨雪暗灰 -> 晨曦霜白
                WeatherState.STORM -> Pair(Color.parseColor("#1E1B4B"), Color.parseColor("#0F172A")) // 黎明惊雷紫黑
                WeatherState.SLEET -> Pair(Color.parseColor("#1E293B"), Color.parseColor("#64748B")) // 晨曦冷雨雪青暗灰
            }
            TimeOfDay.DAY -> when (state) {
                WeatherState.CLEAR -> Pair(Color.parseColor("#0284C7"), Color.parseColor("#BAE6FD")) // 纯粹天蓝 -> 浅天青
                WeatherState.CLOUDY -> Pair(Color.parseColor("#0369A1"), Color.parseColor("#7DD3FC")) // 晴空蔚蓝 -> 柔和天蓝
                WeatherState.OVERCAST -> Pair(Color.parseColor("#475569"), Color.parseColor("#94A3B8")) // 铅灰阴霾 -> 银灰雾气
                WeatherState.RAIN -> Pair(Color.parseColor("#334155"), Color.parseColor("#64748B")) // 阴雨沉沉
                WeatherState.SNOW -> Pair(Color.parseColor("#64748B"), Color.parseColor("#CBD5E1")) // 白雪银灰
                WeatherState.STORM -> Pair(Color.parseColor("#18181B"), Color.parseColor("#27272A")) // 白昼黑云压顶
                WeatherState.SLEET -> Pair(Color.parseColor("#334155"), Color.parseColor("#94A3B8")) // 湿冷冰蓝灰
            }
            TimeOfDay.DUSK -> when (state) {
                WeatherState.CLEAR -> Pair(Color.parseColor("#3B0764"), Color.parseColor("#F97316")) // 暮色晚霞深紫 -> 炙烈橘红
                WeatherState.CLOUDY -> Pair(Color.parseColor("#4C0519"), Color.parseColor("#EA580C")) // 瑰丽火烧云暗紫褐 -> 晚霞金红
                WeatherState.OVERCAST -> Pair(Color.parseColor("#27272A"), Color.parseColor("#52525B")) // 暮霭沉沉铜灰
                WeatherState.RAIN -> Pair(Color.parseColor("#1E1B4B"), Color.parseColor("#3F3F46")) // 暮雨冷紫灰
                WeatherState.SNOW -> Pair(Color.parseColor("#2E1065"), Color.parseColor("#94A3B8")) // 暮雪紫灰粉霜
                WeatherState.STORM -> Pair(Color.parseColor("#18181B"), Color.parseColor("#450A0A")) // 晚霞雷暴血色惊雷
                WeatherState.SLEET -> Pair(Color.parseColor("#1E1B4B"), Color.parseColor("#4B5563")) // 暮色残霞冷雨雪
            }
            TimeOfDay.NIGHT -> when (state) {
                WeatherState.CLEAR -> Pair(Color.parseColor("#030712"), Color.parseColor("#0F172A")) // 深空黑 -> 冷板岩蓝
                WeatherState.CLOUDY -> Pair(Color.parseColor("#020617"), Color.parseColor("#1E293B")) // 暗夜流云黑蓝
                WeatherState.OVERCAST -> Pair(Color.parseColor("#09090B"), Color.parseColor("#18181B")) // 浓密夜雾幽暗碳黑
                WeatherState.RAIN -> Pair(Color.parseColor("#020617"), Color.parseColor("#0F172A")) // 暗夜冷雨
                WeatherState.SNOW -> Pair(Color.parseColor("#0A0F1D"), Color.parseColor("#1E293B")) // 静谧夜雪
                WeatherState.STORM -> Pair(Color.parseColor("#000000"), Color.parseColor("#09090B")) // 极夜雷暴纯黑电弧
                WeatherState.SLEET -> Pair(Color.parseColor("#020617"), Color.parseColor("#0F172A")) // 深夜冷黑雨雪
            }
        }
    }

    /**
     * iOS 风格 5 层云物理参数：卷积卷云、远积云、近积云、地平线水汽、阴天乌云盖
     */
    data class CloudLayerParams(
        val cirrusAlpha: Float,
        val cumulusBackAlpha: Float,
        val cumulusFrontAlpha: Float,
        val mistAlpha: Float,
        val overcastAlpha: Float,
        val tintColor: Int
    )

    private fun resolveCloudLayerParams(timeOfDay: TimeOfDay, state: WeatherState): CloudLayerParams {
        val tint = when (timeOfDay) {
            TimeOfDay.DAWN -> when (state) {
                WeatherState.CLEAR, WeatherState.CLOUDY -> Color.parseColor("#FED7AA") // 晨曦暖金
                WeatherState.OVERCAST -> Color.parseColor("#CBD5E1")
                WeatherState.RAIN, WeatherState.SLEET -> Color.parseColor("#94A3B8")
                WeatherState.SNOW -> Color.parseColor("#E2E8F0")
                WeatherState.STORM -> Color.parseColor("#475569")
            }
            TimeOfDay.DAY -> when (state) {
                WeatherState.CLEAR, WeatherState.CLOUDY -> Color.parseColor("#FFFFFF") // 纯白透亮
                WeatherState.OVERCAST -> Color.parseColor("#CBD5E1")
                WeatherState.RAIN, WeatherState.SLEET -> Color.parseColor("#94A3B8")
                WeatherState.SNOW -> Color.parseColor("#F1F5F9")
                WeatherState.STORM -> Color.parseColor("#475569")
            }
            TimeOfDay.DUSK -> when (state) {
                WeatherState.CLEAR, WeatherState.CLOUDY -> Color.parseColor("#FDBA74") // 晚霞炽金红
                WeatherState.OVERCAST -> Color.parseColor("#71717A")
                WeatherState.RAIN, WeatherState.SLEET -> Color.parseColor("#4B5563")
                WeatherState.SNOW -> Color.parseColor("#CBD5E1")
                WeatherState.STORM -> Color.parseColor("#3B0764")
            }
            TimeOfDay.NIGHT -> when (state) {
                WeatherState.CLEAR, WeatherState.CLOUDY -> Color.parseColor("#94A3B8") // 月光微青
                WeatherState.OVERCAST -> Color.parseColor("#3F3F46")
                WeatherState.RAIN, WeatherState.SLEET -> Color.parseColor("#334155")
                WeatherState.SNOW -> Color.parseColor("#64748B")
                WeatherState.STORM -> Color.parseColor("#1E1B4B")
            }
        }

        return when (state) {
            WeatherState.CLEAR -> CloudLayerParams(
                cirrusAlpha = 0.35f,
                cumulusBackAlpha = 0.0f,
                cumulusFrontAlpha = 0.0f,
                mistAlpha = 0.08f,
                overcastAlpha = 0.0f,
                tintColor = tint
            )
            WeatherState.CLOUDY -> CloudLayerParams(
                cirrusAlpha = 0.40f,
                cumulusBackAlpha = 0.65f,
                cumulusFrontAlpha = 0.85f,
                mistAlpha = 0.18f,
                overcastAlpha = 0.0f,
                tintColor = tint
            )
            WeatherState.OVERCAST -> CloudLayerParams(
                cirrusAlpha = 0.0f,
                cumulusBackAlpha = 0.0f,
                cumulusFrontAlpha = 0.0f,
                mistAlpha = 0.08f,
                overcastAlpha = 0.96f,
                tintColor = tint
            )
            WeatherState.RAIN -> CloudLayerParams(
                cirrusAlpha = 0.0f,
                cumulusBackAlpha = 0.50f,
                cumulusFrontAlpha = 0.65f,
                mistAlpha = 0.55f,
                overcastAlpha = 0.95f,
                tintColor = tint
            )
            WeatherState.SNOW -> CloudLayerParams(
                cirrusAlpha = 0.20f,
                cumulusBackAlpha = 0.45f,
                cumulusFrontAlpha = 0.60f,
                mistAlpha = 0.45f,
                overcastAlpha = 0.82f,
                tintColor = tint
            )
            WeatherState.STORM -> CloudLayerParams(
                cirrusAlpha = 0.0f,
                cumulusBackAlpha = 0.70f,
                cumulusFrontAlpha = 0.88f,
                mistAlpha = 0.65f,
                overcastAlpha = 1.0f,
                tintColor = tint
            )
            WeatherState.SLEET -> CloudLayerParams(
                cirrusAlpha = 0.0f,
                cumulusBackAlpha = 0.45f,
                cumulusFrontAlpha = 0.60f,
                mistAlpha = 0.60f,
                overcastAlpha = 0.88f,
                tintColor = tint
            )
        }
    }

    /**
     * 大气时钟色温与发光晕影 (4 时段 × 7 天气 = 28 种精细专属光学调优)
     * 解决恶劣天气/阴雨天在拂晓或黄昏时，文字发光与冷色背景剧烈冲突的突兀问题
     * 严格遵循 WCAG 车机对比度规范，确保高亮天空不被冲淡、暗夜不刺眼眩光
     */
    private fun resolveClockAtmosphere(timeOfDay: TimeOfDay, state: WeatherState): Triple<Int, Int, Float> {
        return when (timeOfDay) {
            TimeOfDay.DAWN -> when (state) {
                WeatherState.CLEAR -> Triple(Color.parseColor("#FFF7ED"), Color.parseColor("#FB923C"), 24.0f) // 晨曦玫瑰金白 + 朝霞金橙漫射
                WeatherState.CLOUDY -> Triple(Color.parseColor("#FFF7ED"), Color.parseColor("#FB923C"), 22.0f) // 晨曦玫瑰白 + 柔和朝霞粉橘
                WeatherState.OVERCAST -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#475569"), 14.0f) // 纯净白 + 晨雾深板岩灰微光 (彻底去除违和橙光)
                WeatherState.RAIN -> Triple(Color.parseColor("#F8FAFC"), Color.parseColor("#334155"), 14.0f) // 晨雨纯白 + 湿冷暗灰微光
                WeatherState.SNOW -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#64748B"), 16.0f) // 晨雪纯白 + 冰川板岩灰微光 (提升雪地对比度)
                WeatherState.STORM -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#A855F7"), 20.0f) // 晨雷电弧亮紫
                WeatherState.SLEET -> Triple(Color.parseColor("#F8FAFC"), Color.parseColor("#475569"), 14.0f) // 晨冷雨雪青暗灰微光
            }
            TimeOfDay.DAY -> when (state) {
                WeatherState.CLEAR -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#0284C7"), 18.0f) // 纯白 + 蔚蓝深天青散射 (告别浅蓝冲淡对比度)
                WeatherState.CLOUDY -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#0369A1"), 16.0f) // 纯白 + 晴空深海天蓝散射
                WeatherState.OVERCAST -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#334155"), 12.0f) // 纯白 + 铅灰阴霾暗石板沉稳微光
                WeatherState.RAIN -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#1E293B"), 12.0f) // 纯白 + 雨幕深冷灰微光
                WeatherState.SNOW -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#475569"), 14.0f) // 纯白 + 雪原深板岩灰微光 (雪景字迹分明)
                WeatherState.STORM -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#818CF8"), 20.0f) // 纯白 + 昼雷电光青紫
                WeatherState.SLEET -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#334155"), 13.0f) // 纯白 + 冰水深灰微光
            }
            TimeOfDay.DUSK -> when (state) {
                WeatherState.CLEAR -> Triple(Color.parseColor("#FFFBEB"), Color.parseColor("#F97316"), 26.0f) // 暖金白 + 落日赤金光晕
                WeatherState.CLOUDY -> Triple(Color.parseColor("#FFFBEB"), Color.parseColor("#EA580C"), 24.0f) // 暖金白 + 晚霞火烧云金红
                WeatherState.OVERCAST -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#3F3F46"), 13.0f) // 纯亮白 + 暮色暗炭灰微光 (根除实拍图中突兀橙光，与铜灰云海绝配)
                WeatherState.RAIN -> Triple(Color.parseColor("#F8FAFC"), Color.parseColor("#1E1B4B"), 13.0f) // 纯净白 + 暮雨深紫黑微光
                WeatherState.SNOW -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#4C1D95"), 16.0f) // 纯白 + 暮雪暗紫暮霜微光
                WeatherState.STORM -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#E11D48"), 22.0f) // 纯白 + 暮色血雷炽红微光
                WeatherState.SLEET -> Triple(Color.parseColor("#F8FAFC"), Color.parseColor("#1E293B"), 14.0f) // 纯净白 + 暮冷黑蓝灰微光
            }
            TimeOfDay.NIGHT -> when (state) {
                WeatherState.CLEAR -> Triple(Color.parseColor("#E0F2FE"), Color.parseColor("#0284C7"), 20.0f) // 冷银青月光白 + 幽蓝深空暗夜微光
                WeatherState.CLOUDY -> Triple(Color.parseColor("#E0F2FE"), Color.parseColor("#0369A1"), 18.0f) // 冷银青 + 夜空深蓝云隙微光
                WeatherState.OVERCAST -> Triple(Color.parseColor("#F1F5F9"), Color.parseColor("#334155"), 12.0f) // 纯净微冷白 + 夜雾碳黑沉稳灰光
                WeatherState.RAIN -> Triple(Color.parseColor("#E2E8F0"), Color.parseColor("#1E293B"), 12.0f) // 柔和银白 + 暗夜冷雨黑蓝微光
                WeatherState.SNOW -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#475569"), 16.0f) // 纯白 + 静谧夜雪霜华微光
                WeatherState.STORM -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#9333EA"), 24.0f) // 纯白 + 极夜高能紫雷电弧
                WeatherState.SLEET -> Triple(Color.parseColor("#E2E8F0"), Color.parseColor("#334155"), 13.0f) // 柔和银白 + 暗夜冰雪冷灰微光
            }
        }
    }
}

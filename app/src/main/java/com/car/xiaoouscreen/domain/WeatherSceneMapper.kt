package com.car.xiaoouscreen.domain

import android.graphics.Color
import java.util.Calendar

/**
 * 领域层：纯函数式气象场景映射器
 * 严格执行 WMO 0-99 完整规范与兜底机制，输出 4 时段 × 7 天气 = 28 种拟真场景配置
 */
object WeatherSceneMapper {

    private const val TWILIGHT_WINDOW_SECONDS = 2700L // 45 分钟过渡窗口

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
        nowUnix: Long
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
            lightningEnabled = lightningEnabled
        )
    }

    /**
     * 精准判断 DAWN / DAY / DUSK / NIGHT (4 时段)
     */
    fun resolveTimeOfDay(nowUnix: Long, sunriseUnix: Long, sunsetUnix: Long): TimeOfDay {
        if (sunriseUnix <= 0L || sunsetUnix <= 0L || sunsetUnix <= sunriseUnix) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..6 -> TimeOfDay.DAWN
                in 7..17 -> TimeOfDay.DAY
                in 18..19 -> TimeOfDay.DUSK
                else -> TimeOfDay.NIGHT
            }
        }

        return when {
            // 拂晓/凌晨：日出前后 45 分钟
            nowUnix in (sunriseUnix - TWILIGHT_WINDOW_SECONDS)..(sunriseUnix + TWILIGHT_WINDOW_SECONDS) -> TimeOfDay.DAWN

            // 傍晚/黄昏：日落前后 45 分钟
            nowUnix in (sunsetUnix - TWILIGHT_WINDOW_SECONDS)..(sunsetUnix + TWILIGHT_WINDOW_SECONDS) -> TimeOfDay.DUSK

            // 白天：在拂晓后到黄昏前
            nowUnix in (sunriseUnix + TWILIGHT_WINDOW_SECONDS)..(sunsetUnix - TWILIGHT_WINDOW_SECONDS) -> TimeOfDay.DAY

            // 其余为夜晚
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

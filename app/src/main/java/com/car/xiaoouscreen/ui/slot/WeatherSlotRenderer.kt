package com.car.xiaoouscreen.ui.slot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.car.xiaoouscreen.domain.WeatherSceneConfig

/**
 * 天气信息模块渲染器 (支持 A 区域与 C 区域无差别自适应居中渲染，严格零 GC)
 *
 * 复用原小欧屏左侧信息枢纽全部视觉规范：
 * - 歌词模式上方小时间（72px）
 * - 实时温度大字（72px -> 62px）+ 天气主标 + 风速/体感温副标
 * - 日期与星期
 * - 实时环境感知舒适度（随温度变色）或分钟级降水预警（安全右边界保护防越界）
 */
class WeatherSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.WEATHER

    private data class ComfortLevel(val statusText: String, @ColorInt val color: Int)

    private val smallTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }

    private val tempNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }

    private val weatherLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }

    private val weatherSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#F1F5F9")
    }

    private val dateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }

    private val dateSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#F8FAFC")
    }

    private val comfortLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.03f
        textAlign = Paint.Align.LEFT
    }

    // 预缓存舒适度枚举实例，彻底避免测量时反复 new 对象
    private val comfortExtremeCold = ComfortLevel("极冷", Color.parseColor("#38BDF8"))
    private val comfortCold = ComfortLevel("冷", Color.parseColor("#7DD3FC"))
    private val comfortCool = ComfortLevel("微凉", Color.parseColor("#5EEAD4"))
    private val comfortNice = ComfortLevel("舒适", Color.parseColor("#4ADE80"))
    private val comfortWarm = ComfortLevel("温热", Color.parseColor("#FBBF24"))
    private val comfortHot = ComfortLevel("燥热", Color.parseColor("#FB923C"))
    private val comfortExtremeHot = ComfortLevel("酷热", Color.parseColor("#F87171"))

    private val colorLightningGlow = Color.parseColor("#F0ABFC")

    // 文字与测量宽度零 GC 缓存
    private var cachedHudTempText = ""
    private var cachedHudWeatherText = ""
    private var cachedHudSubInfoText = ""
    private var cachedHudRainText = ""
    private val cachedHudPrefixText = "实时环境感知 · "
    private var cachedHudComfort: ComfortLevel = comfortNice
    private var cachedHudHasValidRainAlert = false
    private var cachedHudWeatherWidth = 0f
    private var cachedHudSubInfoWidth = 0f
    private var cachedHudPrefixWidth = 0f
    private var cachedHudComfortWidth = 0f
    private var cachedHudNoteWidth = 0f
    private var cachedHudDateWidth = 0f
    private var lastMeasuredDateStr = ""
    private var lastMeasuredConfig: WeatherSceneConfig? = null
    private var dataDirty = true

    // 降水预警截断结果缓存（避免每帧 ellipsize 分配 CharSequence）
    private var cachedRainEllipsizeSource = ""
    private var cachedRainMaxWidth = -1f
    private var cachedRainTextToDraw = ""
    private var cachedRainTextSize = 21f

    private var lastLeftAnchorX = 185f

    override fun invalidateData() {
        dataDirty = true
    }

    override fun getLastLeftAnchorX(): Float = lastLeftAnchorX

    private fun getComfortLevel(temp: Float): ComfortLevel {
        return when {
            temp < 0f -> comfortExtremeCold
            temp < 10f -> comfortCold
            temp < 18f -> comfortCool
            temp <= 24f -> comfortNice
            temp <= 28f -> comfortWarm
            temp <= 33f -> comfortHot
            else -> comfortExtremeHot
        }
    }

    private fun updateHudTextCacheIfNeeded(ctx: XiaoOuSlotRenderContext) {
        val cfg = ctx.currentConfig
        if (dataDirty || lastMeasuredConfig !== cfg) {
            dataDirty = false
            lastMeasuredConfig = cfg
            cachedHudTempText = "${cfg.temperatureCelsius.toInt()}°"
            cachedHudWeatherText = cfg.weatherDescription
            val windDir = cfg.windDirectionText
            val windScale = cfg.windScaleText
            cachedHudSubInfoText = if (windScale.isNotBlank()) {
                "$windDir $windScale · 体感 ${cfg.feelsLikeCelsius.toInt()}°"
            } else {
                "风速 ${cfg.windSpeedKmh.toInt()} km/h · 优"
            }
            cachedHudComfort = getComfortLevel(cfg.temperatureCelsius)
            val rawSummary = cfg.minuteRainSummary
            cachedHudHasValidRainAlert = cfg.isMinuteRainActive &&
                !rawSummary.isNullOrBlank() &&
                !rawSummary.contains("无降水") &&
                !rawSummary.contains("没有降水")
            val summaryText = rawSummary.orEmpty().trim()
            cachedHudRainText = if (cachedHudHasValidRainAlert) {
                if (summaryText.startsWith("🌧️") || summaryText.startsWith("🌧")) {
                    summaryText
                } else {
                    "🌧️ $summaryText"
                }
            } else {
                ""
            }

            cachedHudWeatherWidth = weatherLabelPaint.measureText(cachedHudWeatherText)
            cachedHudSubInfoWidth = weatherSubPaint.measureText(cachedHudSubInfoText)
            cachedHudPrefixWidth = dateSubPaint.measureText(cachedHudPrefixText)
            cachedHudComfortWidth = comfortLabelPaint.measureText(cachedHudComfort.statusText)
            cachedHudNoteWidth = cachedHudPrefixWidth + cachedHudComfortWidth
            cachedRainEllipsizeSource = ""
        }
        if (ctx.dateStr != lastMeasuredDateStr) {
            lastMeasuredDateStr = ctx.dateStr
            cachedHudDateWidth = dateLabelPaint.measureText(ctx.dateStr)
        }
    }

    override fun draw(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        ctx: XiaoOuSlotRenderContext,
        alphaFactor: Float
    ) {
        val clampedAlpha = alphaFactor.coerceIn(0f, 1f)
        if (clampedAlpha <= 0.005f) return

        updateHudTextCacheIfNeeded(ctx)

        val lyricsP = if (ctx.showSmallTimeInWeatherSlot) ctx.lyricsModeProgress else 0f

        // 1. 动态自适应字号：大时钟模式 72px，歌词模式 62px
        tempNumPaint.textSize = lerp(72f, 62f, lyricsP)

        // 2. 测量各行宽度并构建矩形组合 (Bounding Box)
        val timeWidth = if (lyricsP > 0.005f) smallTimePaint.measureText(ctx.timeStr) else 0f
        val tempWidth = tempNumPaint.measureText(cachedHudTempText)
        val row2Width = tempWidth + 14f + maxOf(cachedHudWeatherWidth, cachedHudSubInfoWidth)
        val dateWidth = cachedHudDateWidth
        val noteWidth = cachedHudNoteWidth

        val idleBlockWidth = maxOf(row2Width, dateWidth, noteWidth)
        val lyricsBlockWidth = maxOf(timeWidth, row2Width, dateWidth, noteWidth)
        val blockWidth = lerp(idleBlockWidth, lyricsBlockWidth, lyricsP)

        // 3. 在对应区域 [region.leftX, region.rightX] 内水平居中，严格防止越出安全边界
        val rawStartX = region.leftX + (region.width - blockWidth) * 0.5f
        val hudStartX = rawStartX.coerceAtLeast(region.leftX + 8f)
        lastLeftAnchorX = hudStartX

        // 4. 垂直布局计算
        val timeBaselineY = 112f
        val tempBaselineY = lerp(178f, 206f, lyricsP)
        val dateBaselineY = lerp(226f, 256f, lyricsP)
        val noteBaselineY = lerp(266f, 296f, lyricsP)

        // 0. 歌词模式小时间
        if (lyricsP > 0.005f) {
            val targetTextColor = ctx.currentConfig.clockTextColor
            val targetGlowColor = ctx.currentConfig.clockGlowColor
            val targetGlowRadius = ctx.currentConfig.clockGlowRadius
            val textColor = ColorUtils.blendARGB(ctx.previousConfig.clockTextColor, targetTextColor, ctx.transitionProgress.coerceIn(0f, 1f))
            val glowColor = ColorUtils.blendARGB(ctx.previousConfig.clockGlowColor, targetGlowColor, ctx.transitionProgress.coerceIn(0f, 1f))
            val finalTextColor = if (ctx.lightningActive && ctx.lightningIntensity > 0.35f) Color.WHITE else textColor
            val finalGlowColor = if (ctx.lightningActive) colorLightningGlow else glowColor

            val alphaInt = (lyricsP.coerceIn(0f, 1f) * 255f * clampedAlpha).toInt()
            smallTimePaint.color = finalTextColor
            val glowAlphaInt = (Color.alpha(finalGlowColor) * lyricsP * clampedAlpha).toInt().coerceIn(0, 255)
            if (glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
                com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithHaloAndShadow(
                    canvas, ctx.timeStr, hudStartX, timeBaselineY, smallTimePaint,
                    alphaInt, finalGlowColor, glowAlphaInt, dyGlow = 2f
                )
            } else {
                smallTimePaint.alpha = alphaInt
                canvas.drawText(ctx.timeStr, hudStartX, timeBaselineY, smallTimePaint)
            }
        }

        // 1. 温度数值
        val tAlpha = (255 * clampedAlpha).toInt()
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedHudTempText, hudStartX, tempBaselineY,
            tempNumPaint, tAlpha, 0f, 2f, 160
        )

        // 2. 天气主标签
        val weatherX = hudStartX + tempWidth + 14f
        val wlAlpha = (255 * clampedAlpha).toInt()
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedHudWeatherText, weatherX, tempBaselineY - 28f,
            weatherLabelPaint, wlAlpha, 0f, 2f, 180
        )

        // 3. 风速与体感温副标
        val wsAlpha = (240 * clampedAlpha).toInt()
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedHudSubInfoText, weatherX, tempBaselineY + 2f,
            weatherSubPaint, wsAlpha, 0f, 2f, 200
        )

        // 4. 日期与星期
        val dlAlpha = (255 * clampedAlpha).toInt()
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, ctx.dateStr, hudStartX, dateBaselineY,
            dateLabelPaint, dlAlpha, 0f, 2f, 180
        )

        // 5. 实时环境感知舒适度 或 动态降水预警文字
        if (cachedHudHasValidRainAlert) {
            val rpAlpha = (245 * clampedAlpha).toInt()
            val maxRainWidth = (region.rightX - 12f - hudStartX).coerceAtLeast(100f)
            if (cachedRainEllipsizeSource != cachedHudRainText || kotlin.math.abs(cachedRainMaxWidth - maxRainWidth) > 1f) {
                cachedRainEllipsizeSource = cachedHudRainText
                cachedRainMaxWidth = maxRainWidth
                val originalSize = 21f
                dateSubPaint.textSize = originalSize
                if (dateSubPaint.measureText(cachedHudRainText) <= maxRainWidth) {
                    cachedRainTextToDraw = cachedHudRainText
                    cachedRainTextSize = originalSize
                } else {
                    dateSubPaint.textSize = 18f
                    if (dateSubPaint.measureText(cachedHudRainText) <= maxRainWidth) {
                        cachedRainTextToDraw = cachedHudRainText
                        cachedRainTextSize = 18f
                    } else {
                        cachedRainTextToDraw = TextUtils.ellipsize(
                            cachedHudRainText,
                            dateSubPaint,
                            maxRainWidth,
                            TextUtils.TruncateAt.END
                        ).toString()
                        cachedRainTextSize = 18f
                    }
                }
            }

            dateSubPaint.textSize = cachedRainTextSize
            com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, cachedRainTextToDraw, hudStartX, noteBaselineY,
                dateSubPaint, rpAlpha, 0f, 2f, 180
            )
            dateSubPaint.textSize = 21f
        } else {
            val dsAlpha = (248 * clampedAlpha).toInt()
            com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, cachedHudPrefixText, hudStartX, noteBaselineY,
                dateSubPaint, dsAlpha, 0f, 2f, 180
            )

            val comfortX = hudStartX + cachedHudPrefixWidth
            val noteAlpha = (255 * clampedAlpha).toInt()
            comfortLabelPaint.color = cachedHudComfort.color
            com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, cachedHudComfort.statusText, comfortX, noteBaselineY,
                comfortLabelPaint, noteAlpha, 0f, 2f, 180
            )
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }
}

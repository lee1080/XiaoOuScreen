package com.car.xiaoouscreen.ui.slot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.util.Locale

/**
 * 空白留白渲染器（不渲染任何前景元素）
 */
class EmptySlotRenderer : XiaoOuSlotRenderer {
    override val type: XiaoOuSlotType = XiaoOuSlotType.NONE

    override fun draw(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        ctx: XiaoOuSlotRenderContext,
        alphaFactor: Float
    ) {
        // Intentional no-op
    }
}

/**
 * 正式版「歌曲封面」模块渲染器（复用现有歌词源 / 原车酷狗 / U盘 / MediaSession 封面通道）
 *
 * 严格遵循零 GC 与 405×384 对称安全区规范：
 * - 纯粹展示专辑封面，不在下方重复绘制歌名/歌手（顶部已有常驻音乐信息岛）；
 * - 居中绘制 228×228 圆角悬浮专辑封面（Y ∈ [70, 298]，视觉居中且严格位于左下角微型档位岛 Y=314 上方，绝不重叠越界）；
 * - 与歌词保持一致的播放/停止显隐节奏：播放中展示封面，停止播放回退大时钟后平滑交叉淡出并自动由天气信息补位填充。
 */
class AlbumCoverSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.ALBUM_COVER

    private val coverRect = RectF()
    private val shadowRect = RectF()
    private val srcRect = Rect()
    private val clipPath = Path()
    private var lastClipRegion: XiaoOuSlotRegion? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val fallbackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F172A")
    }
    private val vinylGroovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.parseColor("#94A3B8")
    }
    private val vinylAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f
        color = Color.parseColor("#38BDF8")
    }
    private val vinylCenterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0EA5E9")
    }
    private val noteGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 62f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val coverBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = Color.WHITE
    }

    override fun draw(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        ctx: XiaoOuSlotRenderContext,
        alphaFactor: Float
    ) {
        // 与歌词显隐节奏完全一致：未播放音乐或停止播放超过 5 秒淡出后彻底显示空白
        val a = (alphaFactor * ctx.albumCoverSlotAlpha).coerceIn(0f, 1f)
        if (a <= 0.005f) return

        // 228×228 居中专辑封面：Y = [70, 298]，视觉居中且严格位于微型档位岛 (Y=314..374) 上方
        val coverSize = 228f
        val cornerRadius = 28f
        val left = region.centerX - coverSize * 0.5f
        val top = 70f
        coverRect.set(left, top, left + coverSize, top + coverSize)
        shadowRect.set(left + 2f, top + 6f, left + coverSize + 2f, top + coverSize + 8f)

        if (lastClipRegion != region) {
            lastClipRegion = region
            clipPath.reset()
            clipPath.addRoundRect(coverRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        val bmp = ctx.coverBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        val showBitmap = bmp != null && ctx.coverFadeAlpha > 0.005f

        // 1. 悬浮立体底影
        shadowPaint.alpha = (95 * a).toInt()
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        // 2. 圆角裁剪封面主体
        canvas.save()
        canvas.clipPath(clipPath)

        // 底层磨砂黑胶唱片底盘（仅在播放中封面尚在加载或淡入过程中可见）
        if (!showBitmap || ctx.coverFadeAlpha < 0.995f) {
            fallbackBgPaint.alpha = (145 * a).toInt()
            canvas.drawRect(coverRect, fallbackBgPaint)

            val cx = coverRect.centerX()
            val cy = coverRect.centerY()
            vinylGroovePaint.alpha = (46 * a).toInt()
            canvas.drawCircle(cx, cy, 86f, vinylGroovePaint)
            vinylGroovePaint.alpha = (34 * a).toInt()
            canvas.drawCircle(cx, cy, 67f, vinylGroovePaint)

            vinylAccentPaint.alpha = (195 * a).toInt()
            canvas.drawCircle(cx, cy, 48f, vinylAccentPaint)

            vinylCenterFillPaint.alpha = (64 * a).toInt()
            canvas.drawCircle(cx, cy, 39f, vinylCenterFillPaint)

            noteGlyphPaint.alpha = (245 * a).toInt()
            canvas.drawText("♪", cx, cy + 22f, noteGlyphPaint)
        }

        // 正式专辑封面位图（居中裁切铺满，零畸变）
        if (showBitmap && bmp != null) {
            val bw = bmp.width
            val bh = bmp.height
            val minDim = if (bw < bh) bw else bh
            val sx = (bw - minDim) / 2
            val sy = (bh - minDim) / 2
            srcRect.set(sx, sy, sx + minDim, sy + minDim)

            coverBitmapPaint.alpha = (255 * a * ctx.coverFadeAlpha.coerceIn(0f, 1f)).toInt()
            canvas.drawBitmap(bmp, srcRect, coverRect, coverBitmapPaint)
        }

        canvas.restore()

        // 3. 精致玻璃高光边框
        coverRimPaint.alpha = (if (showBitmap) 96 * a else 64 * a).toInt()
        canvas.drawRoundRect(coverRect, cornerRadius, cornerRadius, coverRimPaint)
    }
}

/**
 * 正式版「12V 蓄电池信息」模块渲染器（接入实车 CAN / MCU 电压与 BCM IBS 健康度 SOH）
 *
 * 视觉层级严格遵循用户要求 —— 「电压更大，健康度更小」：
 * - 第一层（视觉绝对主角）：88px 大号实时电压读数（如 14.2 V / 12.6 V），带大气通透阴影；
 * - 第二层（精致视觉条）：细长发光健康度/电压状态进度条；
 * - 第三层（更小辅助层级）：22px 蓄电池健康度（优先展示实车 IBS SOH 百分比与评级；无 IBS 时基于实时电压智能评估健康状态）+ 18px 补充状态说明。
 * - 与天气 HUD 保持一致的通透车规原生无框风格，在 A / C 区域均严格居中且避让底部档位 HUD。
 */
class BatteryInfoSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.BATTERY_INFO

    private val colorGood = Color.parseColor("#4ADE80")     // 翠绿（良好 / 充电正常）
    private val colorNormal = Color.parseColor("#38BDF8")   // 冰蓝（正常）
    private val colorWarn = Color.parseColor("#FBBF24")     // 琥珀（偏低 / 偏高）
    private val colorDanger = Color.parseColor("#F87171")   // 警示红（亏电 / 建议更换）
    private val colorMuted = Color.parseColor("#CBD5E1")    // 等待信号浅灰

    private val voltageNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 88f
        typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }
    private val voltageUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
    }
    private val healthPrefixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
    }
    private val healthValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 23f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.02f
        textAlign = Paint.Align.LEFT
    }
    private val subInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#CBD5E1")
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val fillRect = RectF()
    private val iconBodyRect = RectF()
    private val iconCapRect = RectF()

    // 零 GC 字符串与宽度缓存
    private var lastVoltageV: Float? = Float.NaN
    private var lastSohPercent: Int? = Int.MIN_VALUE
    private var lastSocPercent: Int? = Int.MIN_VALUE
    private var lastLowWarning: Boolean = false

    private var cachedVoltageStr = "--.-"
    private var cachedVoltageNumW = 0f
    private var cachedUnitW = 0f
    private var cachedHealthPrefix = "健康度 · "
    private var cachedHealthPrefixW = 0f
    private var cachedHealthValueStr = "等待车辆信号"
    private var cachedHealthValueW = 0f
    @ColorInt private var cachedStatusColor: Int = colorMuted
    private var cachedBarFraction: Float = 0f
    private var cachedSubStr = "12V 蓄电池 · 实车总线监测"

    init {
        cachedUnitW = voltageUnitPaint.measureText("V")
        cachedHealthPrefixW = healthPrefixPaint.measureText(cachedHealthPrefix)
        cachedVoltageNumW = voltageNumPaint.measureText(cachedVoltageStr)
        cachedHealthValueW = healthValuePaint.measureText(cachedHealthValueStr)
    }

    private fun updateCacheIfNeeded(ctx: XiaoOuSlotRenderContext) {
        val v = ctx.batteryVoltageV
        val soh = ctx.batterySohPercent
        val soc = ctx.batterySocPercent
        val low = ctx.batteryLowWarning

        if (v == lastVoltageV && soh == lastSohPercent && soc == lastSocPercent && low == lastLowWarning) {
            return
        }
        lastVoltageV = v
        lastSohPercent = soh
        lastSocPercent = soc
        lastLowWarning = low

        // 1. 电压主显示文案
        cachedVoltageStr = if (v != null) {
            String.format(Locale.US, "%.1f", v)
        } else {
            "--.-"
        }
        cachedVoltageNumW = voltageNumPaint.measureText(cachedVoltageStr)

        // 2. 健康度/运行状态文案、颜色与进度条比例
        if (soh != null && soh in 1..100) {
            // 实车真正上报了 IBS SOH (健康度)
            cachedHealthPrefix = "健康度 · "
            val grade = when {
                soh < 50 -> "建议更换"
                soh < 70 -> "偏低"
                soh < 85 -> "正常"
                else -> "良好"
            }
            cachedHealthValueStr = if (low) "${soh}% $grade (需补电)" else "${soh}% $grade"
            cachedStatusColor = when {
                soh < 50 -> colorDanger
                low || soh < 70 -> colorWarn
                soh < 85 -> colorNormal
                else -> colorGood
            }
            cachedBarFraction = (soh / 100f).coerceIn(0.06f, 1f)
        } else if (v != null) {
            // 实车无 IBS 传感器（欧尚 Z6 iDD 常态）：基于 12V 铅酸/AGM 蓄电池充放电物理规律智能评估
            cachedHealthPrefix = "运行状态 · "
            when {
                v >= 13.4f -> {
                    cachedHealthValueStr = "充电正常"
                    cachedStatusColor = colorGood
                }
                v >= 12.5f -> {
                    cachedHealthValueStr = "电量充足"
                    cachedStatusColor = colorGood
                }
                v >= 12.1f -> {
                    cachedHealthValueStr = "电量正常"
                    cachedStatusColor = colorNormal
                }
                v >= 11.6f -> {
                    cachedHealthValueStr = if (low) "电量偏低(告警)" else "电量偏低"
                    cachedStatusColor = colorWarn
                }
                else -> {
                    cachedHealthValueStr = "亏电需补电"
                    cachedStatusColor = colorDanger
                }
            }
            cachedBarFraction = ((v - 10.8f) / (14.4f - 10.8f)).coerceIn(0.08f, 1f)
        } else {
            cachedHealthPrefix = "健康度 · "
            cachedHealthValueStr = "等待车辆信号"
            cachedStatusColor = colorMuted
            cachedBarFraction = 0f
        }
        cachedHealthPrefixW = healthPrefixPaint.measureText(cachedHealthPrefix)
        cachedHealthValueW = healthValuePaint.measureText(cachedHealthValueStr)

        // 3. 底部补充状态行
        cachedSubStr = when {
            soc != null && soc in 1..100 -> {
                val modeTag = if (v != null && v >= 13.4f) "DC-DC 供电中" else "剩余电量 ${soc}%"
                "12V 蓄电池 · $modeTag"
            }
            v != null && v >= 13.4f -> "12V 蓄电池 · DC-DC 供电中"
            v != null && v >= 12.1f -> "12V 蓄电池 · 驻车供电正常"
            v != null && v >= 11.6f -> "12V 蓄电池 · 建议启动补电"
            v != null -> "12V 蓄电池 · 亏电需补电"
            else -> "12V 蓄电池 · 实车总线监测"
        }
    }

    override fun draw(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        ctx: XiaoOuSlotRenderContext,
        alphaFactor: Float
    ) {
        val a = alphaFactor.coerceIn(0f, 1f)
        if (a <= 0.005f) return

        updateCacheIfNeeded(ctx)

        val cx = region.centerX

        // ============================================================
        // 1. 巨幅实时电压读数 (88px 主数字 + 36px V 单位 —— 电压更大！)
        // ============================================================
        val unitGap = 8f
        val totalVoltageW = cachedVoltageNumW + unitGap + cachedUnitW
        val voltageStartX = cx - totalVoltageW * 0.5f
        val voltageBaselineY = 174f

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedVoltageStr, voltageStartX, voltageBaselineY,
            voltageNumPaint, (255 * a).toInt(), 0f, 3f, 180
        )

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "V", voltageStartX + cachedVoltageNumW + unitGap, voltageBaselineY - 2f,
            voltageUnitPaint, (225 * a).toInt(), 0f, 2f, 165
        )

        // ============================================================
        // 2. 细长发光健康度进度轨 (Y = 198..204)
        // ============================================================
        val barW = 216f
        val barH = 6f
        val barLeft = cx - barW * 0.5f
        val barTop = 198f
        trackRect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        trackPaint.color = Color.parseColor("#0F172A")
        trackPaint.alpha = (72 * a).toInt()
        canvas.drawRoundRect(trackRect, 3f, 3f, trackPaint)

        if (cachedBarFraction > 0.01f) {
            val fillW = (barW * cachedBarFraction).coerceAtLeast(10f)
            fillRect.set(barLeft, barTop, barLeft + fillW, barTop + barH)
            fillPaint.color = cachedStatusColor
            fillPaint.alpha = (245 * a).toInt()
            canvas.drawRoundRect(fillRect, 3f, 3f, fillPaint)
        }

        // ============================================================
        // 3. 蓄电池健康度小字行 (22px/23px —— 健康度更小！)
        // ============================================================
        val iconW = 24f
        val iconH = 14f
        val iconGap = 10f
        val totalHealthW = iconW + iconGap + cachedHealthPrefixW + cachedHealthValueW
        val healthStartX = cx - totalHealthW * 0.5f
        val healthBaselineY = 244f

        // 精致小蓄电池矢量图标（与文字垂直居中对齐）
        val iconTop = healthBaselineY - 15f
        iconBodyRect.set(healthStartX, iconTop, healthStartX + iconW, iconTop + iconH)
        iconStrokePaint.color = cachedStatusColor
        iconStrokePaint.alpha = (240 * a).toInt()
        canvas.drawRoundRect(iconBodyRect, 3f, 3f, iconStrokePaint)

        iconFillPaint.color = cachedStatusColor
        iconFillPaint.alpha = (235 * a).toInt()
        iconCapRect.set(healthStartX + 4f, iconTop - 3.5f, healthStartX + 8.5f, iconTop - 1f)
        canvas.drawRect(iconCapRect, iconFillPaint)
        iconCapRect.set(healthStartX + iconW - 8.5f, iconTop - 3.5f, healthStartX + iconW - 4f, iconTop - 1f)
        canvas.drawRect(iconCapRect, iconFillPaint)

        val textStartX = healthStartX + iconW + iconGap
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedHealthPrefix, textStartX, healthBaselineY,
            healthPrefixPaint, (240 * a).toInt(), 0f, 2f, 210
        )

        healthValuePaint.color = cachedStatusColor
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedHealthValueStr, textStartX + cachedHealthPrefixW, healthBaselineY,
            healthValuePaint, (255 * a).toInt(), 0f, 2f, 215, Color.BLACK
        )

        // ============================================================
        // 4. 底部柔和补充信息 (18px)
        // ============================================================
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedSubStr, cx, 278f,
            subInfoPaint, (225 * a).toInt(), 0f, 2f, 210
        )
    }
}

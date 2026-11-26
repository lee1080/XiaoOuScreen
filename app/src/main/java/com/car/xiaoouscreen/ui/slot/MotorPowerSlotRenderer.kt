package com.car.xiaoouscreen.ui.slot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 正式版「整车功率 · 极简放大无极弧形功率表」双向能量仪渲染器（100% 纯原生 Canvas · 零 GC）
 *
 * 核心升级：
 * 1. 彻底取消下方小字（去除底部脚标及表芯副标），空间完全释放，视觉极度开阔；
 * 2. 弧形槽大幅放大（半径扩容至 132px，槽厚增至 24px，高亮流体 21px），在小屏幕上极具视觉冲击力与辨识度；
 * 3. 严格左右镜像对称（圆心对齐 Y=160，开角 150°~390° 扫掠 240°，12点钟 270° 双向无极展开）；
 * 4. 表芯超大号双向主读数（数字 56px、正负号 40px、单位 20px），饱满居中，舒展纯粹。
 */
class MotorPowerSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.MOTOR_POWER

    companion object {
        private const val MAX_REGEN_KW = 45f
        private const val MAX_OUTPUT_KW = 110f
        private const val DEADZONE_KW = 0.08f

        // 几何中心严格对齐显示区域中心点 (384 * 0.5 = 192f)
        // 弧形槽自 150° 扫掠 240° 至 390°(30°)，顶部最高点为 cy - R - T/2，底部两端点为 cy + 0.5*R + T/2
        // 整块垂直中点 = cy - R * 0.25f。令其严格恒等于 192f，得 cy = 192f + R * 0.25f = 225f
        // 使得整块在 384px 屏幕内上下留白严格等距 (各 81px)，左右留白严格等距 (各 58.5px)，实现绝对物理与视觉居中！
        private const val SCREEN_CENTER_Y = 192f
        private const val ARC_RADIUS = 132f
        private const val DIAL_CENTER_Y = SCREEN_CENTER_Y + (ARC_RADIUS * 0.25f) // 225f
        private const val TRACK_THICKNESS = 24f
        private const val FILL_THICKNESS = 21f
        private const val GLOW_THICKNESS = 30f

        // 弧度与 1/4 零位定义 (以 3 点钟方向为 0°，顺时针递增)
        // 贯通式无极半透明槽自 150° (左下方) 延展至 390° (即 30° 右下方)，总跨度 240°
        // 零位位于弧形槽的 1/4 处：150° + 240° * (1/4) = 210° (约 10 点钟方向)
        // 左侧 1/4 (60°)：制动充电动能回收 (0 ~ -45kW)
        // 右侧 3/4 (180°)：动力消耗输出 (0 ~ +110kW)
        private const val TRACK_START_DEG = 150f
        private const val TRACK_SWEEP_DEG = 240f
        private const val ZERO_DEG = 210f
        private const val REGEN_SWEEP_MAX_DEG = 60f
        private const val OUTPUT_SWEEP_MAX_DEG = 180f
    }

    // 极性色谱
    private val colorRegenLow = Color.parseColor("#10B981")   // 翡翠绿 (轻度回收)
    private val colorRegenHigh = Color.parseColor("#34D399")  // 极光薄荷 (强力回收)
    private val colorEcoOut = Color.parseColor("#38BDF8")     // 冰川电青 (0..45kW 经济输出)
    private val colorSportOut = Color.parseColor("#FBBF24")   // 赛道琥珀 (45..80kW 澎湃输出)
    private val colorBoostOut = Color.parseColor("#F43F5E")   // 烈焰赤红 (>80kW 全力爆发)
    private val colorStandby = Color.parseColor("#CBD5E1")    // 待机银白

    // 无极半透明凹槽底轨画笔 (一体成型加粗无极槽，无杂质边框线)
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TRACK_THICKNESS
        color = Color.parseColor("#151D2F")
        strokeCap = Paint.Cap.ROUND
    }

    // 槽内不透明高亮充盈能量流画笔 (纯原生 GPU 硬件加速 strokeArc · 零 GC)
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FILL_THICKNESS
        strokeCap = Paint.Cap.ROUND
    }
    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLOW_THICKNESS
        strokeCap = Paint.Cap.ROUND
    }

    // 1/4 零位基准细刻度画笔 (极简精致车规级银灰基准线与微标)
    private val zeroTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#94A3B8")
    }
    private val zeroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
    }

    // 顶部状态标画笔
    private val statusHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val statusLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.05f
        textAlign = Paint.Align.LEFT
    }

    // 表芯主读数画笔 (放大至 56px 超大字，舒展霸气且与外环留有充沛安全净空)
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val heroNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 56f
        typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
    }

    // 复用几何矩形（零 GC）
    private val arcOval = RectF()

    // 平滑视觉插值状态
    private var visualPowerKw = 0f
    private var hasInitializedVisual = false

    // 零 GC 文本与宽度缓存
    private var lastHasSignal = false
    private var lastPowerQ = Int.MIN_VALUE
    private var lastPlugCharging = false
    private var lastIsParked = false
    private var lastIsMotiveOutput = false

    private var cachedStatusLabel = "整车功率"
    private var cachedStatusLabelW = 0f
    private var cachedSignStr = ""
    private var cachedSignW = 0f
    private var cachedMagStr = "--.-"
    private var cachedMagW = 0f
    private var cachedUnitW = 0f

    init {
        cachedStatusLabelW = statusLabelPaint.measureText(cachedStatusLabel)
        cachedMagW = heroNumPaint.measureText(cachedMagStr)
        cachedUnitW = unitPaint.measureText("kW")
    }

    @ColorInt
    private fun accentForPower(powerKw: Float, hasSignal: Boolean): Int {
        if (!hasSignal || abs(powerKw) < DEADZONE_KW) return colorStandby
        return if (powerKw < 0f) {
            val frac = (abs(powerKw) / MAX_REGEN_KW).coerceIn(0f, 1f)
            ColorUtils.blendARGB(colorRegenLow, colorRegenHigh, frac)
        } else {
            when {
                powerKw <= 45f -> colorEcoOut
                powerKw <= 80f -> ColorUtils.blendARGB(colorEcoOut, colorSportOut, ((powerKw - 45f) / 35f).coerceIn(0f, 1f))
                else -> ColorUtils.blendARGB(colorSportOut, colorBoostOut, ((powerKw - 80f) / 30f).coerceIn(0f, 1f))
            }
        }
    }

    private fun updateTextCacheIfNeeded(
        hasSignal: Boolean,
        displayKw: Float,
        isPlugCharging: Boolean,
        isParked: Boolean,
        motorRpm: Int?
    ) {
        // 智能驱动工况判定：
        // 1. 停车/驻车状态（P/N档）：100% 属于全车静态用电 -> 准确显示“整车功率”
        // 2. 功率在 1.0kW 以下：属于低压计算/照明/待机电控底噪包络线，无法构成牵引动力 -> 准确显示“整车功率”
        // 3. 功率 >= 1.0kW 时：
        //    - 若有电机转速信号，且转速 <= 50 RPM（如 D 档踩刹车开空调）：依然属于全车电气负载 -> 准确显示“整车功率”
        //    - 只有当电机转速 > 50 RPM（或无转速属性但功率显著起步）时，才判定为真正的行车动力 -> 显示“动力输出” / “澎湃输出”
        val isMotiveOutput = when {
            isParked -> false
            displayKw < 1.0f -> false
            motorRpm != null -> motorRpm > 50
            else -> true
        }

        val signalChanged = hasSignal != lastHasSignal
        val powerQ = if (hasSignal) (displayKw * 10f).roundToInt() else Int.MIN_VALUE
        if (signalChanged || powerQ != lastPowerQ || isPlugCharging != lastPlugCharging || isParked != lastIsParked || isMotiveOutput != lastIsMotiveOutput) {
            lastHasSignal = hasSignal
            lastPowerQ = powerQ
            lastPlugCharging = isPlugCharging
            lastIsParked = isParked
            lastIsMotiveOutput = isMotiveOutput

            if (!hasSignal) {
                cachedStatusLabel = "整车功率"
                cachedSignStr = ""
                cachedMagStr = "--.-"
            } else {
                val absKw = abs(displayKw)
                when {
                    displayKw >= DEADZONE_KW -> {
                        cachedStatusLabel = when {
                            !isMotiveOutput -> "整车功率"
                            displayKw >= 80f -> "澎湃输出"
                            else -> "动力输出"
                        }
                        cachedSignStr = "+"
                    }
                    displayKw <= -DEADZONE_KW -> {
                        cachedStatusLabel = if (isPlugCharging || isParked) "整车充电" else "动能回收"
                        cachedSignStr = "-"
                    }
                    else -> {
                        cachedStatusLabel = "整车就绪"
                        cachedSignStr = ""
                    }
                }
                cachedMagStr = if (absKw >= 99.95f) {
                    String.format(Locale.US, "%.0f", absKw)
                } else {
                    String.format(Locale.US, "%.1f", absKw)
                }
            }
            cachedStatusLabelW = statusLabelPaint.measureText(cachedStatusLabel)
            cachedSignW = if (cachedSignStr.isEmpty()) 0f else signPaint.measureText(cachedSignStr)
            cachedMagW = heroNumPaint.measureText(cachedMagStr)
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

        val rawPower = ctx.motorPowerKw
        val hasSignal = rawPower != null
        val targetKw = rawPower ?: 0f

        if (!hasInitializedVisual) {
            visualPowerKw = targetKw
            hasInitializedVisual = true
        } else {
            visualPowerKw += (targetKw - visualPowerKw) * 0.22f
            if (abs(visualPowerKw - targetKw) < 0.05f) {
                visualPowerKw = targetKw
            }
        }

        val accentColor = accentForPower(visualPowerKw, hasSignal)
        updateTextCacheIfNeeded(
            hasSignal = hasSignal,
            displayKw = if (abs(targetKw) <= 1.0f) targetKw else visualPowerKw,
            isPlugCharging = ctx.motorPowerIsPlugCharging,
            isParked = ctx.isParked,
            motorRpm = ctx.motorRpm
        )

        val cx = region.centerX
        val cy = DIAL_CENTER_Y

        // 预设几何外接椭圆（放大至 132px 半径）
        arcOval.set(cx - ARC_RADIUS, cy - ARC_RADIUS, cx + ARC_RADIUS, cy + ARC_RADIUS)

        // ============================================================
        // 1. 无极半透明磨砂槽 (放大加粗宽槽 · 纯净无多余边框杂线)
        // ============================================================
        trackBgPaint.alpha = (120 * a).toInt()
        canvas.drawArc(arcOval, TRACK_START_DEG, TRACK_SWEEP_DEG, false, trackBgPaint)

        // ============================================================
        // 1b. 1/4 零位基准细刻度线 (在 210° 处标识整车输出与动能回收的分界锚点)
        // ============================================================
        val zeroRad = Math.toRadians(ZERO_DEG.toDouble())
        val cosZero = Math.cos(zeroRad).toFloat()
        val sinZero = Math.sin(zeroRad).toFloat()
        val tickInnerR = ARC_RADIUS - (TRACK_THICKNESS * 0.5f) - 3f
        val tickOuterR = ARC_RADIUS + (TRACK_THICKNESS * 0.5f) + 3f
        zeroTickPaint.alpha = (210 * a).toInt()
        canvas.drawLine(
            cx + cosZero * tickInnerR,
            cy + sinZero * tickInnerR,
            cx + cosZero * tickOuterR,
            cy + sinZero * tickOuterR,
            zeroTickPaint
        )

        val labelR = ARC_RADIUS + (TRACK_THICKNESS * 0.5f) + 11f
        zeroLabelPaint.alpha = (180 * a).toInt()
        canvas.drawText("0", cx + cosZero * labelR, cy + sinZero * labelR + 4f, zeroLabelPaint)

        // ============================================================
        // 2. 槽内高亮双向能量流 (自 210° 零位向两侧展开，0.1kW起平滑羽化渐显)
        // ============================================================
        val absVisualKw = abs(visualPowerKw)
        if (hasSignal && absVisualKw >= DEADZONE_KW) {
            // 平滑羽化透明度：当功率在 0.08kW ~ 0.55kW 之间时，alpha 从 0 连续平滑过渡到 1.0f
            // 彻底解决在 0.4~0.5kW 临界点跳变闪烁（频繁出现消失）的问题
            val lowPowerAlpha = ((absVisualKw - DEADZONE_KW) / 0.45f).coerceIn(0f, 1f)
            val fillAlphaFactor = a * lowPowerAlpha

            if (visualPowerKw > 0f) {
                // 功率输出态：自 ZERO_DEG (210°) 顺时针向右下方延展 (最大 180°)
                val outFrac = (visualPowerKw / MAX_OUTPUT_KW).coerceIn(0f, 1f)
                val outSweep = outFrac * OUTPUT_SWEEP_MAX_DEG

                if (outSweep > 0.1f && fillAlphaFactor > 0.01f) {
                    // 外溢柔和光晕
                    activeGlowPaint.color = accentColor
                    activeGlowPaint.alpha = (75 * fillAlphaFactor).toInt()
                    canvas.drawArc(arcOval, ZERO_DEG, outSweep, false, activeGlowPaint)

                    // 槽内不透明实体能量光流
                    activeFillPaint.color = accentColor
                    activeFillPaint.alpha = (255 * fillAlphaFactor).toInt()
                    canvas.drawArc(arcOval, ZERO_DEG, outSweep, false, activeFillPaint)
                }
            } else {
                // 动能回收态：自 ZERO_DEG (210°) 逆时针向左下方延展 (最大 60°)
                val regenFrac = (absVisualKw / MAX_REGEN_KW).coerceIn(0f, 1f)
                val regenSweep = regenFrac * REGEN_SWEEP_MAX_DEG

                if (regenSweep > 0.1f && fillAlphaFactor > 0.01f) {
                    val startAngle = ZERO_DEG - regenSweep

                    // 外溢柔和光晕
                    activeGlowPaint.color = accentColor
                    activeGlowPaint.alpha = (75 * fillAlphaFactor).toInt()
                    canvas.drawArc(arcOval, startAngle, regenSweep, false, activeGlowPaint)

                    // 槽内不透明实体能量光流
                    activeFillPaint.color = accentColor
                    activeFillPaint.alpha = (255 * fillAlphaFactor).toInt()
                    canvas.drawArc(arcOval, startAngle, regenSweep, false, activeFillPaint)
                }
            }
        }

        // ============================================================
        // 3. 表芯中央区域：顶部状态标 + 中间超大号双向读数 (彻底取消下方小字)
        // ============================================================
        // 3a. 顶部动态极性状态标 (Y ≈ cy - 64 = 161)
        val dotR = 4.2f
        val dotGap = 7f
        val headerTotalW = dotR * 2f + dotGap + cachedStatusLabelW
        val headerStartX = cx - headerTotalW * 0.5f
        val dotCx = headerStartX + dotR
        val dotCy = cy - 64f

        val isActiveLoad = hasSignal && abs(visualPowerKw) >= DEADZONE_KW
        statusHaloPaint.color = accentColor
        statusHaloPaint.alpha = ((if (isActiveLoad) 85 else 35) * a).toInt()
        canvas.drawCircle(dotCx, dotCy, if (isActiveLoad) 9.5f else 6.5f, statusHaloPaint)

        statusDotPaint.color = accentColor
        statusDotPaint.alpha = (255 * a).toInt()
        canvas.drawCircle(dotCx, dotCy, dotR, statusDotPaint)

        statusLabelPaint.color = if (isActiveLoad) accentColor else colorStandby
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedStatusLabel, headerStartX + dotR * 2f + dotGap, cy - 58f,
            statusLabelPaint, (255 * a).toInt(), 0f, 1.5f, 215, Color.BLACK
        )

        // 3b. 中部超大号双向功率主读数：[+/-] [XX.X] [kW] (基线 Y = cy + 8 = 168)
        val signGap = if (cachedSignStr.isEmpty()) 0f else 3f
        val unitGap = 7f
        val heroTotalW = cachedSignW + signGap + cachedMagW + unitGap + cachedUnitW
        var drawX = cx - heroTotalW * 0.5f

        if (cachedSignStr.isNotEmpty()) {
            signPaint.color = accentColor
            com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, cachedSignStr, drawX, cy + 2f,
                signPaint, (255 * a).toInt(), 0f, 2f, 215, Color.BLACK
            )
            drawX += cachedSignW + signGap
        }

        val numAlpha = if (hasSignal) (255 * a).toInt() else (185 * a).toInt()
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedMagStr, drawX, cy + 8f,
            heroNumPaint, numAlpha, 0f, 2.5f, 195, Color.BLACK
        )
        drawX += cachedMagW + unitGap

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "kW", drawX, cy + 5f,
            unitPaint, (225 * a).toInt(), 0f, 1.5f, 195, Color.BLACK
        )
    }

    override fun getLastLeftAnchorX(): Float = 205f
}

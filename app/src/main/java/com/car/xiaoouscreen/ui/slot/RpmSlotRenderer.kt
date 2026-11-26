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
 * 正式版「实时转速（发动机 / 电机智能自适应）」渲染器（100% 纯原生 Canvas · 零 GC）
 *
 * 智能适配策略（油车 + 混动全兼容）：
 * - 仅发动机有转速（纯油车 / 混动单发动机工况）→ 展示大号单发动机性能转速仪（0~7000 RPM，含红线区警示）；
 * - 仅电机有转速（混动纯电行驶 / 纯电车型）→ 展示大号单驱动电机高频转速仪（0~12000 RPM，电驱青→紫光谱）；
 * - 发动机与电机同时有转速（混动油电协同工况）→ 自动切换为双层全宽性能转速仪，上下同显发动机与电机实时转速。
 *
 * 布局严格位于 A / C 对称 405×384 安全区内（Y ∈ [62, 286]），完全避让左下角微型档位岛（Y = 314..374）。
 */
class RpmSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.RPM

    private enum class DisplayMode {
        WAITING,
        ENGINE_ONLY,
        MOTOR_ONLY,
        DUAL
    }

    companion object {
        private const val ENGINE_MAX_RPM = 7000f
        private const val ENGINE_REDLINE_RPM = 5500f
        private const val MOTOR_MAX_RPM = 12000f

        private const val SINGLE_BAR_W = 328f
        private const val SINGLE_BAR_H = 16f
        private const val SINGLE_SEG_COUNT = 20

        private const val DUAL_BAR_W = 332f
        private const val DUAL_BAR_H = 12f
        private const val DUAL_SEG_COUNT = 18

        private const val SEG_GAP = 3.2f
    }

    // 发动机色谱（冰白青 → 赛道琥珀 → 红线赤红）
    private val colorEngLow = Color.parseColor("#38BDF8")
    private val colorEngMid = Color.parseColor("#FBBF24")
    private val colorEngHigh = Color.parseColor("#F43F5E")
    private val colorRedlineHint = Color.parseColor("#EF4444")

    // 电机色谱（电驱冰青 → 等离子靛蓝 → 高频紫红）
    private val colorMotLow = Color.parseColor("#22D3EE")
    private val colorMotMid = Color.parseColor("#60A5FA")
    private val colorMotHigh = Color.parseColor("#A855F7")

    private val colorMuted = Color.parseColor("#CBD5E1")
    private val colorMotLabelBright = Color.parseColor("#A5F3FC")
    private val colorScaleRedSoft = Color.parseColor("#FDA4AF")
    private val colorScaleBright = Color.parseColor("#E2E8F0")

    // 通用图形画笔
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F172A")
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.WHITE
    }
    private val segInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.WHITE
    }

    // 单表模式文本画笔
    private val singleHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.06f
        textAlign = Paint.Align.LEFT
    }
    private val singleNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }
    private val singleUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
    }
    private val singleScalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val singleFooterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#E2E8F0")
    }

    // 双表模式文本画笔
    private val dualTopCaptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.06f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#E2E8F0")
    }
    private val dualRowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.04f
        textAlign = Paint.Align.LEFT
    }
    private val dualNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = Color.WHITE
    }
    private val dualUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        color = Color.parseColor("#CBD5E1")
    }
    private val dualScaleLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#CBD5E1")
    }
    private val dualScaleRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    // 零 GC 几何复用
    private val trackRect = RectF()
    private val segRect = RectF()
    private val glowRect = RectF()

    // 平滑指针阻尼插值
    private var visualEngRpm = 0f
    private var visualMotRpm = 0f
    private var hasInitEng = false
    private var hasInitMot = false

    // 零 GC 字符串与宽度缓存
    private var lastFmtEngRpm = Int.MIN_VALUE
    private var lastFmtMotRpm = Int.MIN_VALUE
    private var lastFmtEngPeak = Int.MIN_VALUE
    private var lastFmtMotPeak = Int.MIN_VALUE
    private var lastSingleStateBand = Int.MIN_VALUE
    private var lastMode: DisplayMode = DisplayMode.WAITING

    private var cachedEngStr = "0"
    private var cachedEngSingleW = 0f
    private var cachedMotStr = "0"
    private var cachedMotSingleW = 0f
    private var cachedSingleUnitW = 0f
    private var cachedDualUnitW = 0f

    private var cachedSingleHeader = "实时转速"
    private var cachedSingleHeaderW = 0f
    private var cachedSingleFooter = "等待车辆转速信号 · 兼容油车/混动"
    private var cachedWaitingNumW = 0f

    private val engineScaleLabels = arrayOf("0", "2k", "4k", "6k", "7k")
    private val engineScaleFracs = floatArrayOf(0f, 2f / 7f, 4f / 7f, 6f / 7f, 1f)
    private val motorScaleLabels = arrayOf("0", "3k", "6k", "9k", "12k")
    private val motorScaleFracs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    init {
        cachedSingleUnitW = singleUnitPaint.measureText("RPM")
        cachedDualUnitW = dualUnitPaint.measureText("RPM")
        cachedSingleHeaderW = singleHeaderPaint.measureText(cachedSingleHeader)
        cachedWaitingNumW = singleNumPaint.measureText("----")
        cachedEngSingleW = singleNumPaint.measureText(cachedEngStr)
        cachedMotSingleW = singleNumPaint.measureText(cachedMotStr)
    }

    private fun resolveMode(ctx: XiaoOuSlotRenderContext): DisplayMode {
        return when (ctx.powertrainMode) {
            com.car.xiaoouscreen.car.CarGearManager.PowertrainMode.EV -> DisplayMode.MOTOR_ONLY
            com.car.xiaoouscreen.car.CarGearManager.PowertrainMode.HEV -> DisplayMode.DUAL
            com.car.xiaoouscreen.car.CarGearManager.PowertrainMode.PURE_ICE -> {
                // 油车没有 EV 和混动，只显示发动机
                if (ctx.engineRpm != null) DisplayMode.ENGINE_ONLY
                else if (ctx.motorRpm != null) DisplayMode.MOTOR_ONLY
                else DisplayMode.ENGINE_ONLY
            }
        }
    }

    @ColorInt
    private fun engineAccent(rpm: Float): Int = when {
        rpm <= 10f -> colorMuted
        rpm < 2800f -> colorEngLow
        rpm < ENGINE_REDLINE_RPM -> ColorUtils.blendARGB(
            colorEngLow, colorEngMid,
            ((rpm - 2800f) / (ENGINE_REDLINE_RPM - 2800f)).coerceIn(0f, 1f)
        )
        else -> ColorUtils.blendARGB(
            colorEngMid, colorEngHigh,
            ((rpm - ENGINE_REDLINE_RPM) / (ENGINE_MAX_RPM - ENGINE_REDLINE_RPM)).coerceIn(0f, 1f)
        )
    }

    @ColorInt
    private fun motorAccent(rpm: Float): Int = when {
        rpm <= 10f -> colorMuted
        rpm < 5000f -> colorMotLow
        rpm < 9000f -> ColorUtils.blendARGB(colorMotLow, colorMotMid, ((rpm - 5000f) / 4000f).coerceIn(0f, 1f))
        else -> ColorUtils.blendARGB(colorMotMid, colorMotHigh, ((rpm - 9000f) / 3000f).coerceIn(0f, 1f))
    }

    @ColorInt
    private fun engineSegColor(segIdx: Int, segCount: Int): Int {
        val frac = (segIdx + 1).toFloat() / segCount.toFloat()
        return when {
            frac <= 0.42f -> colorEngLow
            frac <= 0.76f -> ColorUtils.blendARGB(colorEngLow, colorEngMid, ((frac - 0.42f) / 0.34f).coerceIn(0f, 1f))
            else -> ColorUtils.blendARGB(colorEngMid, colorEngHigh, ((frac - 0.76f) / 0.24f).coerceIn(0f, 1f))
        }
    }

    @ColorInt
    private fun motorSegColor(segIdx: Int, segCount: Int): Int {
        val frac = (segIdx + 1).toFloat() / segCount.toFloat()
        return when {
            frac <= 0.45f -> colorMotLow
            frac <= 0.78f -> ColorUtils.blendARGB(colorMotLow, colorMotMid, ((frac - 0.45f) / 0.33f).coerceIn(0f, 1f))
            else -> ColorUtils.blendARGB(colorMotMid, colorMotHigh, ((frac - 0.78f) / 0.22f).coerceIn(0f, 1f))
        }
    }

    private fun formatRpmComma(rpm: Int): String {
        val safe = rpm.coerceAtLeast(0)
        return if (safe >= 1000) {
            String.format(Locale.US, "%,d", safe)
        } else {
            safe.toString()
        }
    }

    private fun updateTextCache(mode: DisplayMode, dispEng: Int, dispMot: Int, peakEng: Int, peakMot: Int) {
        val modeChanged = mode != lastMode
        if (dispEng != lastFmtEngRpm) {
            lastFmtEngRpm = dispEng
            cachedEngStr = formatRpmComma(dispEng)
            cachedEngSingleW = singleNumPaint.measureText(cachedEngStr)
        }
        if (dispMot != lastFmtMotRpm) {
            lastFmtMotRpm = dispMot
            cachedMotStr = formatRpmComma(dispMot)
            cachedMotSingleW = singleNumPaint.measureText(cachedMotStr)
        }

        val stateBand = when (mode) {
            DisplayMode.ENGINE_ONLY -> when {
                dispEng <= 50 -> 0
                dispEng < 1200 -> 1
                dispEng < 3200 -> 2
                dispEng < ENGINE_REDLINE_RPM.toInt() -> 3
                else -> 4
            }
            DisplayMode.MOTOR_ONLY -> when {
                dispMot <= 50 -> 10
                dispMot < 4500 -> 11
                dispMot < 8500 -> 12
                else -> 13
            }
            DisplayMode.DUAL -> 20
            DisplayMode.WAITING -> 30
        }

        val footerDirty = modeChanged ||
            peakEng != lastFmtEngPeak ||
            peakMot != lastFmtMotPeak ||
            stateBand != lastSingleStateBand

        if (!footerDirty) return

        lastMode = mode
        lastFmtEngPeak = peakEng
        lastFmtMotPeak = peakMot
        lastSingleStateBand = stateBand

        val nextHeader = when (mode) {
            DisplayMode.WAITING -> "实时转速"
            DisplayMode.ENGINE_ONLY -> if (stateBand == 4) "发动机 · 红线区" else "发动机转速"
            DisplayMode.MOTOR_ONLY -> "电机转速"
            DisplayMode.DUAL -> "双驱转速"
        }
        if (nextHeader != cachedSingleHeader) {
            cachedSingleHeader = nextHeader
            cachedSingleHeaderW = singleHeaderPaint.measureText(cachedSingleHeader)
        }

        cachedSingleFooter = when (mode) {
            DisplayMode.WAITING -> "等待车辆转速信号 · 兼容油车/混动"
            DisplayMode.ENGINE_ONLY -> {
                val stateTag = when (stateBand) {
                    0 -> "发动机熄火"
                    1 -> "平稳怠速"
                    2 -> "经济巡航"
                    3 -> "动力爆发"
                    else -> "高转红线警示"
                }
                if (peakEng > 0) "峰值 ${formatRpmComma(peakEng)} RPM  ·  $stateTag"
                else "发动机实时转速  ·  $stateTag"
            }
            DisplayMode.MOTOR_ONLY -> {
                val stateTag = when (stateBand) {
                    10 -> "电机待机"
                    11 -> "静谧纯电"
                    12 -> "澎湃电驱"
                    else -> "极速高转电驱"
                }
                if (peakMot > 0) "峰值 ${formatRpmComma(peakMot)} RPM  ·  $stateTag"
                else "驱动电机实时转速  ·  $stateTag"
            }
            DisplayMode.DUAL -> ""
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

        val mode = resolveMode(ctx)
        val targetEng = (ctx.engineRpm ?: 0).toFloat()
        val targetMot = (ctx.motorRpm ?: 0).toFloat()

        if (!hasInitEng) {
            visualEngRpm = targetEng
            hasInitEng = true
        } else {
            visualEngRpm += (targetEng - visualEngRpm) * 0.28f
            if (abs(visualEngRpm - targetEng) < 4f) visualEngRpm = targetEng
        }
        if (!hasInitMot) {
            visualMotRpm = targetMot
            hasInitMot = true
        } else {
            visualMotRpm += (targetMot - visualMotRpm) * 0.28f
            if (abs(visualMotRpm - targetMot) < 4f) visualMotRpm = targetMot
        }

        val dispEng = visualEngRpm.roundToInt().coerceAtLeast(0)
        val dispMot = visualMotRpm.roundToInt().coerceAtLeast(0)
        updateTextCache(mode, dispEng, dispMot, ctx.enginePeakRpm, ctx.motorPeakRpm)

        when (mode) {
            DisplayMode.DUAL -> drawDualLayout(canvas, region, a)
            DisplayMode.ENGINE_ONLY -> drawSingleLayout(canvas, region, isMotor = false, isWaiting = false, a = a)
            DisplayMode.MOTOR_ONLY -> drawSingleLayout(canvas, region, isMotor = true, isWaiting = false, a = a)
            DisplayMode.WAITING -> drawSingleLayout(canvas, region, isMotor = false, isWaiting = true, a = a)
        }
    }

    private fun drawSingleLayout(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        isMotor: Boolean,
        isWaiting: Boolean,
        a: Float
    ) {
        val cx = region.centerX
        val rpmVal = if (isMotor) visualMotRpm else visualEngRpm
        val maxRpm = if (isMotor) MOTOR_MAX_RPM else ENGINE_MAX_RPM
        val accent = when {
            isWaiting -> colorMuted
            isMotor -> motorAccent(rpmVal)
            else -> engineAccent(rpmVal)
        }
        val activeLoad = !isWaiting && rpmVal > 50f

        // 1. 顶部模式标 (Y = 92)
        val dotR = 5.2f
        val dotGap = 11f
        val headerTotalW = dotR * 2f + dotGap + cachedSingleHeaderW
        val headerStartX = cx - headerTotalW * 0.5f
        val dotCx = headerStartX + dotR
        val dotCy = 84f

        dotHaloPaint.color = accent
        dotHaloPaint.alpha = ((if (activeLoad) 95 else 45) * a).toInt()
        canvas.drawCircle(dotCx, dotCy, if (activeLoad) 11f else 8f, dotHaloPaint)

        dotPaint.color = accent
        dotPaint.alpha = (255 * a).toInt()
        canvas.drawCircle(dotCx, dotCy, dotR, dotPaint)

        singleHeaderPaint.color = if (activeLoad) accent else colorMuted
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedSingleHeader, headerStartX + dotR * 2f + dotGap, 92f,
            singleHeaderPaint, (255 * a).toInt(), 0f, 2f, 215, Color.BLACK
        )

        // 2. 大号数字转速读数 (Y = 172)
        val numStr = when {
            isWaiting -> "----"
            isMotor -> cachedMotStr
            else -> cachedEngStr
        }
        val numW = when {
            isWaiting -> cachedWaitingNumW
            isMotor -> cachedMotSingleW
            else -> cachedEngSingleW
        }
        val unitGap = 8f
        val totalNumW = numW + unitGap + cachedSingleUnitW
        val numStartX = cx - totalNumW * 0.5f

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, numStr, numStartX, 172f,
            singleNumPaint, ((if (isWaiting) 185 else 255) * a).toInt(), 0f, 3f, 195, Color.BLACK
        )

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "RPM", numStartX + numW + unitGap, 170f,
            singleUnitPaint, (225 * a).toInt(), 0f, 2f, 195, Color.BLACK
        )

        // 3. 20 段性能转速 LED 条 (Y = 198..214)
        val barLeft = cx - SINGLE_BAR_W * 0.5f
        val frac = if (isWaiting) 0f else (rpmVal / maxRpm).coerceIn(0f, 1f)
        drawSegmentedTachBar(
            canvas = canvas,
            barLeft = barLeft,
            barTop = 198f,
            barWidth = SINGLE_BAR_W,
            barHeight = SINGLE_BAR_H,
            segCount = SINGLE_SEG_COUNT,
            fillFraction = frac,
            isMotor = isMotor,
            hasRedlineHint = !isMotor && !isWaiting,
            a = a
        )

        // 4. 刻度数字行 (Y = 236)
        val labels = if (isMotor) motorScaleLabels else engineScaleLabels
        val fracs = if (isMotor) motorScaleFracs else engineScaleFracs
        val innerLeft = barLeft + 8f
        val innerSpan = SINGLE_BAR_W - 16f
        for (i in labels.indices) {
            val sx = innerLeft + innerSpan * fracs[i]
            val isRedTick = (!isMotor && !isWaiting && i >= labels.size - 2)
            singleScalePaint.color = if (isRedTick) colorEngHigh else colorMuted
            com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, labels[i], sx, 236f,
                singleScalePaint, ((if (isRedTick) 235 else 195) * a).toInt(), 0f, 2f, 200, Color.BLACK
            )
        }

        // 5. 底部峰值与工况脚标 (Y = 274)
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedSingleFooter, cx, 274f,
            singleFooterPaint, (220 * a).toInt(), 0f, 2f, 210, Color.BLACK
        )
    }

    private fun drawDualLayout(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        a: Float
    ) {
        val cx = region.centerX
        val barLeft = cx - DUAL_BAR_W * 0.5f
        val barRight = cx + DUAL_BAR_W * 0.5f

        // 顶部紧凑标题 (Y = 82)
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "混动双驱 · 实时转速", cx, 82f,
            dualTopCaptionPaint, (225 * a).toInt(), 0f, 2f, 205, Color.BLACK
        )

        // ============================================================
        // 上栏：发动机转速 (Y = 100..180) —— 暖琥珀金标识（红线区转赤红），一眼与电驱区分
        // ============================================================
        val engAccent = when {
            visualEngRpm >= ENGINE_REDLINE_RPM -> colorEngHigh
            else -> colorEngMid
        }
        drawDualRow(
            canvas = canvas,
            barLeft = barLeft,
            barRight = barRight,
            label = "发动机",
            rpmStr = cachedEngStr,
            rpmVal = visualEngRpm,
            maxRpm = ENGINE_MAX_RPM,
            maxScaleLabel = "7000 RPM",
            accentColor = engAccent,
            labelColor = engAccent,
            isMotor = false,
            textBaselineY = 134f,
            barTopY = 148f,
            scaleY = 176f,
            a = a
        )

        // 中央细分隔线 (Y = 188)
        dividerPaint.alpha = (72 * a).toInt()
        canvas.drawLine(barLeft + 10f, 188f, barRight - 10f, 188f, dividerPaint)

        // ============================================================
        // 下栏：驱动电机转速 (Y = 200..284，远高于左下角微型档位岛 Y=314) —— 极光电青亮白标识
        // ============================================================
        val motDotAccent = if (visualMotRpm > 50f) motorAccent(visualMotRpm) else colorMotLow
        drawDualRow(
            canvas = canvas,
            barLeft = barLeft,
            barRight = barRight,
            label = "驱动电机",
            rpmStr = cachedMotStr,
            rpmVal = visualMotRpm,
            maxRpm = MOTOR_MAX_RPM,
            maxScaleLabel = "12000 RPM",
            accentColor = motDotAccent,
            labelColor = colorMotLabelBright,
            isMotor = true,
            textBaselineY = 236f,
            barTopY = 250f,
            scaleY = 278f,
            a = a
        )
    }

    private fun drawDualRow(
        canvas: Canvas,
        barLeft: Float,
        barRight: Float,
        label: String,
        rpmStr: String,
        rpmVal: Float,
        maxRpm: Float,
        maxScaleLabel: String,
        @ColorInt accentColor: Int,
        @ColorInt labelColor: Int,
        isMotor: Boolean,
        textBaselineY: Float,
        barTopY: Float,
        scaleY: Float,
        a: Float
    ) {
        val active = rpmVal > 50f
        // 左侧彩色指示点 + 标签
        val dotCx = barLeft + 7f
        val dotCy = textBaselineY - 8f
        dotHaloPaint.color = accentColor
        dotHaloPaint.alpha = ((if (active) 95 else 45) * a).toInt()
        canvas.drawCircle(dotCx, dotCy, if (active) 10f else 7.5f, dotHaloPaint)

        dotPaint.color = accentColor
        dotPaint.alpha = (255 * a).toInt()
        canvas.drawCircle(dotCx, dotCy, 4.8f, dotPaint)

        dualRowLabelPaint.color = labelColor
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, label, barLeft + 21f, textBaselineY - 1f,
            dualRowLabelPaint, (255 * a).toInt(), 0f, 2f, 225, Color.BLACK
        )

        // 右侧右对齐实时数值 + RPM 单位
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "RPM", barRight, textBaselineY - 1f,
            dualUnitPaint, (225 * a).toInt(), 0f, 2f, 205, Color.BLACK
        )

        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, rpmStr, barRight - cachedDualUnitW - 6f, textBaselineY,
            dualNumPaint, (255 * a).toInt(), 0f, 3f, 205, Color.BLACK
        )

        // 18 段性能转速条
        drawSegmentedTachBar(
            canvas = canvas,
            barLeft = barLeft,
            barTop = barTopY,
            barWidth = barRight - barLeft,
            barHeight = DUAL_BAR_H,
            segCount = DUAL_SEG_COUNT,
            fillFraction = (rpmVal / maxRpm).coerceIn(0f, 1f),
            isMotor = isMotor,
            hasRedlineHint = !isMotor,
            a = a
        )

        // 微刻度
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "0", barLeft + 2f, scaleY,
            dualScaleLeftPaint, (205 * a).toInt(), 0f, 1.5f, 205, Color.BLACK
        )

        dualScaleRightPaint.color = if (!isMotor) colorScaleRedSoft else colorScaleBright
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, maxScaleLabel, barRight - 2f, scaleY,
            dualScaleRightPaint, (225 * a).toInt(), 0f, 1.5f, 210, Color.BLACK
        )
    }

    private fun drawSegmentedTachBar(
        canvas: Canvas,
        barLeft: Float,
        barTop: Float,
        barWidth: Float,
        barHeight: Float,
        segCount: Int,
        fillFraction: Float,
        isMotor: Boolean,
        hasRedlineHint: Boolean,
        a: Float
    ) {
        val barRight = barLeft + barWidth
        val barBottom = barTop + barHeight
        val padX = 5f
        val padY = 2.8f

        trackRect.set(barLeft, barTop, barRight, barBottom)
        trackBgPaint.alpha = (112 * a).toInt()
        canvas.drawRoundRect(trackRect, barHeight * 0.5f, barHeight * 0.5f, trackBgPaint)

        trackBorderPaint.alpha = (64 * a).toInt()
        canvas.drawRoundRect(trackRect, barHeight * 0.5f, barHeight * 0.5f, trackBorderPaint)

        val innerW = barWidth - padX * 2f
        val segW = (innerW - SEG_GAP * (segCount - 1)) / segCount
        val segTop = barTop + padY
        val segBottom = barBottom - padY
        val segR = (segBottom - segTop) * 0.42f

        val exactSegs = fillFraction * segCount
        val fullSegs = exactSegs.toInt()
        val partial = exactSegs - fullSegs
        val redlineStartIdx = (segCount * 0.78f).toInt()

        for (i in 0 until segCount) {
            val sl = barLeft + padX + i * (segW + SEG_GAP)
            val sr = sl + segW
            segRect.set(sl, segTop, sr, segBottom)

            val intensity = when {
                i < fullSegs -> 1f
                i == fullSegs && partial > 0.04f -> partial
                else -> 0f
            }

            if (intensity > 0f) {
                val color = if (isMotor) motorSegColor(i, segCount) else engineSegColor(i, segCount)
                glowRect.set(sl - 1.3f, segTop - 2.0f, sr + 1.3f, segBottom + 2.0f)
                segGlowPaint.color = color
                segGlowPaint.alpha = (66 * intensity * a).toInt()
                canvas.drawRoundRect(glowRect, segR + 1.3f, segR + 1.3f, segGlowPaint)

                segActivePaint.color = color
                segActivePaint.alpha = ((95 + 160 * intensity) * a).toInt()
                canvas.drawRoundRect(segRect, segR, segR, segActivePaint)
            } else {
                if (hasRedlineHint && i >= redlineStartIdx) {
                    segInactivePaint.color = colorRedlineHint
                    segInactivePaint.alpha = (62 * a).toInt()
                } else {
                    segInactivePaint.color = Color.WHITE
                    segInactivePaint.alpha = (38 * a).toInt()
                }
                canvas.drawRoundRect(segRect, segR, segR, segInactivePaint)
            }
        }
    }
}

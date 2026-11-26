package com.car.xiaoouscreen.ui.slot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 极简纯粹版「居中战术 G 值雷达仪」渲染器（极简跑车仪表美学 · 100% 零 GC）
 *
 * 核心设计升级：
 * 1. 绝对中心对齐：圆心严格锚定显示区域中心点 (region.centerX, 192f)；
 * 2. 尺寸与整车功率上下宽度 100% 齐平：直径精确等于整车功率垂直跨度 (222px, R=111f)；
 *    - 顶部最高点 Y=81f (与整车功率穹顶完全齐平，上留白严格恒等于 81px)；
 *    - 底部最低点 Y=303f (与整车功率端点完全齐平，下留白严格恒等于 81px)；
 * 3. 内部元素黄金自适应：
 *    - 顶部 "G-FORCE" 战术微标；
 *    - 中心战术微准星 (垂直下臂在圆心下方截断，绝不穿透下方数字)；
 *    - 实时 G 值大字读数舒展落座于下半圆 (基线 dialCy + 72f)；
 * 4. 2.0 秒物理时间戳渐隐流星拖尾 (Meteor Tail) · 零 GC 丝滑 60fps。
 */
class GForceSlotRenderer : XiaoOuSlotRenderer {

    override val type: XiaoOuSlotType = XiaoOuSlotType.G_FORCE

    companion object {
        private const val MAX_DISPLAY_G = 0.60f

        // 几何中心严格对齐显示区域中心点 (384 * 0.5 = 192f)
        // 大小精确对齐整车功率上下跨度 222px (顶部 Y=81f，底部 Y=303f)，半径为 222 / 2 = 111f
        // 使得左右两侧仪表在副屏上顶部齐平、底部齐平、中心齐平、上下留白 (各81px) 像素级对齐！
        private const val SCREEN_CENTER_Y = 192f
        private const val DIAL_CENTER_Y = SCREEN_CENTER_Y // 192f
        private const val DIAL_RADIUS = 111f

        // 2.0 秒流星尾迹物理时间戳系统 (80 节点 × 25ms = 2000ms)
        private const val TRAIL_CAPACITY = 80
        private const val TRAIL_SAMPLE_INTERVAL_MS = 25L
        private const val TRAIL_MAX_AGE_MS = 2000L
    }

    private val colorCalm = Color.parseColor("#38BDF8")   // 冰川青蓝 (0..0.18G)
    private val colorMid = Color.parseColor("#FBBF24")    // 赛道琥珀 (0.18..0.40G)
    private val colorHigh = Color.parseColor("#F43F5E")   // 烈焰赤红 (>0.40G, 0.60G 满环红光)
    private val colorDialCenter = Color.parseColor("#99091220")
    private val colorDialEdge = Color.parseColor("#38091220")

    private val dialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var cachedDialRegion: XiaoOuSlotRegion? = null

    // 极简刻度环画笔 (仅保留外圈与极弱的 0.5G 参考环)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        color = Color.WHITE
    }
    private val midRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }

    // 战术中心十字微准星画笔
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.WHITE
    }

    // 2 秒流星渐隐拖尾画笔（时间戳寿命双重物理衰减）
    private val trailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trailNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 三层发光小球画笔
    private val orbHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val orbMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val orbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // 表盘内嵌文字画笔
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
    }
    private val embeddedNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }
    private val embeddedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 19f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
    }

    // 平滑视觉插值
    private var visualGx = 0f
    private var visualGy = 0f

    // 2.0 秒流星时间戳环形缓冲（100% 零 GC 预分配）
    private val trailGx = FloatArray(TRAIL_CAPACITY)
    private val trailGy = FloatArray(TRAIL_CAPACITY)
    private val trailTimeMs = LongArray(TRAIL_CAPACITY)
    private var trailHead = 0
    private var trailCount = 0
    private var lastTrailSampleMs = 0L

    // 零 GC 字符串与宽度缓存
    private var lastFmtTotalQ = -1
    private var cachedTotalStr = ".00"
    private var cachedTotalNumW = 0f
    private var cachedUnitW = 0f

    init {
        cachedTotalNumW = embeddedNumPaint.measureText(cachedTotalStr)
        cachedUnitW = embeddedUnitPaint.measureText("G")
    }

    @ColorInt
    private fun colorForG(g: Float): Int {
        return when {
            g <= 0.18f -> colorCalm
            g <= 0.40f -> ColorUtils.blendARGB(colorCalm, colorMid, ((g - 0.18f) / 0.22f).coerceIn(0f, 1f))
            else -> ColorUtils.blendARGB(colorMid, colorHigh, ((g - 0.40f) / 0.20f).coerceIn(0f, 1f))
        }
    }

    /**
     * 家用乘用车舒适视觉展开曲线（外圈 0.60G，内参考圈对应约 0.24~0.30G）：
     * 采用 (g / 0.60)^0.72 柔和指数映射，0.15G 时约推出 37% 半径，0.30G 跨越半环，0.60G 触达满环；
     * 表盘内嵌读数保持 100% 真实物理加速度。
     */
    private fun mapRadialProgressiveFrac(g: Float): Float {
        val clamped = (g / MAX_DISPLAY_G).coerceIn(0f, 1f)
        if (clamped <= 0.001f) return 0f
        return Math.pow(clamped.toDouble(), 0.72).toFloat()
    }

    /**
     * 去前导 0 紧凑排版格式化（零 GC 缓存）：
     * - 小于 1.0G 时省略前导 0（显示为 .32 / .68），大幅精简空间；
     * - 大于等于 1.0G 时完整显示整数（显示为 1.15 / 1.25）。
     */
    private fun updateTextCacheIfNeeded(totalG: Float) {
        val tQ = (totalG * 100f).toInt()
        if (tQ != lastFmtTotalQ) {
            lastFmtTotalQ = tQ
            cachedTotalStr = if (totalG < 1.0f) {
                String.format(Locale.US, ".%02d", (totalG * 100f).toInt().coerceIn(0, 99))
            } else {
                String.format(Locale.US, "%.2f", totalG)
            }
            cachedTotalNumW = embeddedNumPaint.measureText(cachedTotalStr)
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

        // 平滑追随目标 G 值，赋予赛道仪器机械阻尼质感
        visualGx += (ctx.gForceX - visualGx) * 0.28f
        visualGy += (ctx.gForceY - visualGy) * 0.28f
        if (abs(visualGx) < 0.003f && abs(ctx.gForceX) < 0.003f) visualGx = 0f
        if (abs(visualGy) < 0.003f && abs(ctx.gForceY) < 0.003f) visualGy = 0f

        val visualTotalG = hypot(visualGx.toDouble(), visualGy.toDouble()).toFloat()

        // 采样 2.0 秒流星时间戳轨迹（每 25ms 采一帧）
        if (ctx.nowUptimeMs - lastTrailSampleMs >= TRAIL_SAMPLE_INTERVAL_MS) {
            lastTrailSampleMs = ctx.nowUptimeMs
            trailGx[trailHead] = visualGx
            trailGy[trailHead] = visualGy
            trailTimeMs[trailHead] = ctx.nowUptimeMs
            trailHead = (trailHead + 1) % TRAIL_CAPACITY
            if (trailCount < TRAIL_CAPACITY) trailCount++
        }

        // 表盘绝对几何中心对齐：圆心 (region.centerX, 192f)，半径 111px (直径 222px，与整车功率上下跨度完全齐平)
        val dialCx = region.centerX
        val dialCy = DIAL_CENTER_Y
        val r = DIAL_RADIUS

        if (cachedDialRegion != region) {
            cachedDialRegion = region
            dialBgPaint.shader = RadialGradient(
                dialCx, dialCy, r,
                colorDialCenter, colorDialEdge,
                Shader.TileMode.CLAMP
            )
        }

        val accentColor = colorForG(visualTotalG)

        // ============================================================
        // 1. 深空黑曜石底板 + 极简双圈 (外圈 0.60G + 弱化中圈参考虚线)
        // 顶部最高点 Y=81f，底部最低点 Y=303f，与左侧整车功率完全齐平！
        // ============================================================
        dialBgPaint.alpha = (255 * a).toInt()
        canvas.drawCircle(dialCx, dialCy, r, dialBgPaint)

        midRingPaint.alpha = (40 * a).toInt()
        canvas.drawCircle(dialCx, dialCy, r * 0.50f, midRingPaint)

        outerRingPaint.alpha = (130 * a).toInt()
        canvas.drawCircle(dialCx, dialCy, r, outerRingPaint)

        // 顶部微标 "G-FORCE"
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "G-FORCE", dialCx, dialCy - r + 22f,
            badgePaint, (180 * a).toInt(), 0f, 1.2f, 180
        )

        // ============================================================
        // 2. 战术中心十字微准星 (避让下方大数字区，下臂在 26px 处截断)
        // ============================================================
        val centerGap = 14f
        crosshairPaint.alpha = (65 * a).toInt()

        // 水平准星左右臂 (自然收束于表盘内)
        canvas.drawLine(dialCx - r + 18f, dialCy, dialCx - centerGap, dialCy, crosshairPaint)
        canvas.drawLine(dialCx + centerGap, dialCy, dialCx + r - 18f, dialCy, crosshairPaint)

        // 垂直准星上臂
        canvas.drawLine(dialCx, dialCy - r + 36f, dialCx, dialCy - centerGap, crosshairPaint)

        // 垂直准星下臂 (在下方大数字区域前截断，绝不穿透数字！)
        canvas.drawLine(dialCx, dialCy + centerGap, dialCx, dialCy + 26f, crosshairPaint)

        // 中心定位原点微圈
        orbCorePaint.alpha = (110 * a).toInt()
        canvas.drawCircle(dialCx, dialCy, 2.2f, orbCorePaint)

        // ============================================================
        // 3. 2.0 秒物理时间戳渐隐流星拖尾 (Meteor Tail · 零 GC)
        // 最早存在的区域最先消失，尾部纤细渐隐，平滑向当前小球消融推进
        // ============================================================
        val visualAngle = if (visualTotalG > 0.001f) atan2(visualGy.toDouble(), visualGx.toDouble()) else 0.0
        val progressiveFrac = mapRadialProgressiveFrac(visualTotalG)
        val dotX = dialCx + (cos(visualAngle) * progressiveFrac * (r - 9f)).toFloat()
        val dotY = dialCy + (sin(visualAngle) * progressiveFrac * (r - 9f)).toFloat()

        if (trailCount > 1) {
            val nowMs = ctx.nowUptimeMs
            var prevTx = 0f
            var prevTy = 0f
            var hasPrev = false

            for (i in 0 until trailCount) {
                val idx = (trailHead - trailCount + i + TRAIL_CAPACITY) % TRAIL_CAPACITY
                val ageMs = nowMs - trailTimeMs[idx]
                if (ageMs > TRAIL_MAX_AGE_MS) {
                    // 超过 2 秒，最先生成的历史轨迹彻底消失
                    continue
                }

                // 流星寿命因子：1.0 表示最新生成点，0.0 表示满 2.0 秒临界点
                val life = (1f - ageMs.toFloat() / TRAIL_MAX_AGE_MS).coerceIn(0f, 1f)
                if (life <= 0.01f) continue

                val txG = trailGx[idx]
                val tyG = trailGy[idx]
                val tMag = hypot(txG.toDouble(), tyG.toDouble()).toFloat()
                val tAngle = if (tMag > 0.001f) atan2(tyG.toDouble(), txG.toDouble()) else 0.0
                val tFrac = mapRadialProgressiveFrac(tMag)
                val tx = dialCx + (cos(tAngle) * tFrac * (r - 9f)).toFloat()
                val ty = dialCy + (sin(tAngle) * tFrac * (r - 9f)).toFloat()

                if (hasPrev) {
                    // 流星主体线段：尾部纤细 (1.5px)、头部粗壮 (6.8px)；尾部平方渐隐、头部明亮
                    trailLinePaint.strokeWidth = 1.5f + life * 5.3f
                    trailLinePaint.color = accentColor
                    trailLinePaint.alpha = (life * life * 210f * a).toInt().coerceIn(0, 255)
                    canvas.drawLine(prevTx, prevTy, tx, ty, trailLinePaint)
                }

                // 流星微粒节点：尾部微弱、头部高亮
                val nodeR = 1.0f + life * 3.0f
                trailNodePaint.color = accentColor
                trailNodePaint.alpha = (life * 125f * a).toInt().coerceIn(0, 255)
                canvas.drawCircle(tx, ty, nodeR, trailNodePaint)

                prevTx = tx
                prevTy = ty
                hasPrev = true
            }

            // 最新历史点平滑连接到当前小球实时坐标 dotX, dotY
            if (hasPrev && visualTotalG > 0.015f) {
                trailLinePaint.strokeWidth = 6.8f
                trailLinePaint.color = accentColor
                trailLinePaint.alpha = (220f * a).toInt().coerceIn(0, 255)
                canvas.drawLine(prevTx, prevTy, dotX, dotY, trailLinePaint)
            }
        }

        // ============================================================
        // 4. 三层发光小球（外层漫射光晕 -> 中层霓虹环 -> 纯白内核）
        // ============================================================
        orbHaloPaint.color = accentColor
        orbHaloPaint.alpha = ((if (visualTotalG > 0.05f) 80 else 50) * a).toInt()
        canvas.drawCircle(dotX, dotY, if (visualTotalG > 0.05f) 19f else 13.5f, orbHaloPaint)

        orbMidPaint.color = accentColor
        orbMidPaint.alpha = (235 * a).toInt()
        canvas.drawCircle(dotX, dotY, 9.0f, orbMidPaint)

        orbCorePaint.alpha = (255 * a).toInt()
        canvas.drawCircle(dotX, dotY, 4.0f, orbCorePaint)

        // ============================================================
        // 5. 实时 G 值大字读数（靠下布局：基线定位于 dialCy + 72f，开阔沉稳）
        // ============================================================
        updateTextCacheIfNeeded(visualTotalG)

        val totalW = cachedTotalNumW + 4f + cachedUnitW
        val numStartX = dialCx - totalW * 0.5f
        val numY = dialCy + 72f

        embeddedNumPaint.color = if (visualTotalG > 0.05f) accentColor else Color.WHITE
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, cachedTotalStr, numStartX, numY,
            embeddedNumPaint, (255 * a).toInt(), 0f, 2.5f, 215, Color.BLACK
        )

        embeddedUnitPaint.color = accentColor
        com.car.xiaoouscreen.ui.CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, "G", numStartX + cachedTotalNumW + 4f, numY - 2f,
            embeddedUnitPaint, (230 * a).toInt(), 0f, 1.5f, 200, Color.BLACK
        )
    }
}

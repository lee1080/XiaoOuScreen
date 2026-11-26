package com.car.xiaoouscreen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.carlyrics.util.PrefsManager
import com.car.xiaoouscreen.car.CarGearManager
import kotlin.math.sin

/**
 * 智能场景联动超宽副屏置顶图层 View (1920 × 384)
 *
 * 核心职责：
 * 1. 拥有副屏绝对最高的物理显示层级 (置顶于歌词、3D档位立方体、驾驶模式光刃、HUD之上)
 * 2. 转向灯联动：极简车规 HUD 加粗外框流光包络线，安全内移防黑边遮挡
 * 3. 变道辅助联动：大面积柔光盲区警示光幕 (1级大范围柔和黄呼吸光幕，2级高危红黄纯色急促循环光幕)
 * 4. 极致车规性能：非活跃期 0fps 零消耗，活跃期 60fps 丝滑渲染，零 GC 预分配
 * 5. 全面点击穿透：不拦截任何触摸与手势事件
 */
class CarSmartSceneOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val LOGICAL_WIDTH = 1920f
        const val LOGICAL_HEIGHT = 384f

        // 变道辅助盲区警示周期
        private const val LCA_LEVEL1_PULSE_PERIOD_MS = 1200L
        private const val LCA_LEVEL2_DANGER_PERIOD_MS = 500L
    }

    private var isTurnSignalSyncEnabled: Boolean = PrefsManager.isTurnSignalSyncEnabled()
    private var isLaneChangeAssistEnabled: Boolean = PrefsManager.isLaneChangeAssistEnabled()

    private var currentTurnSignalInfo = CarGearManager.TurnSignalInfo()
    private var currentLaneChangeAlert = CarGearManager.LaneChangeAlert()

    // 绘制画笔 (车规级晶体高质感线条：放大 2.5 倍至 7.0px 锐利晶体核心线 + 15.0px 透亮微光晕)
    private val frameCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val frameGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 15.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val curtainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 方案一：暗底吸光衬底画笔 (Contrast Underlay Paint)
    // 压暗烈日/高亮晴空背景，在最底层形成平滑羽化光槽，使上层暖色光幕获得车规级极佳对比度与饱和度
    private val contrastUnderlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 变道辅助边缘微光指示条 (放大 2.5 倍：12.5px 柔光微晕 + 6.25px 晶体微光核心，在物理外沿提供清澈余光定位)
    private val edgeAccentHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val edgeAccentCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.25f
        strokeCap = Paint.Cap.ROUND
    }

    // 路径与渐变着色器预分配
    private val leftFramePath = Path()
    private val rightFramePath = Path()

    // 变道辅助柔光羽化光幕矩形 (比转向灯边框稍宽延伸至 365px/1555px，视觉上完整填满整个转向灯框，视觉更平衡)
    private val leftCurtainRect = RectF(130f, 0f, 365f, LOGICAL_HEIGHT)
    private val rightCurtainRect = RectF(1555f, 0f, 1790f, LOGICAL_HEIGHT)

    private var leftFrameYellowShader: LinearGradient? = null
    private var rightFrameYellowShader: LinearGradient? = null

    private var leftCurtainYellowShader: LinearGradient? = null
    private var leftCurtainRedShader: LinearGradient? = null
    private var rightCurtainYellowShader: LinearGradient? = null
    private var rightCurtainRedShader: LinearGradient? = null

    // 方案一：暗底对比度吸光衬底着色器预分配
    private var leftUnderlayShader: LinearGradient? = null
    private var rightUnderlayShader: LinearGradient? = null

    // 物理闪光继电器严格同频平滑插值 (毫秒级无延迟跟随 CAN 信号)
    private var leftTurnAlpha: Float = 0f
    private var rightTurnAlpha: Float = 0f
    private var lastTurnAnimTime: Long = 0L
    private var hazardStartTime: Long = 0L

    init {
        // 设置不拦截触摸事件，实现 100% 全穿透
        isClickable = false
        isFocusable = false
        initGraphicsGeometry()
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        // 绝对穿透，不消耗任何手势与点击
        return false
    }

    private fun initGraphicsGeometry() {
        // ----------------------------------------------------
        // 1. 转向灯精致晶体边框 (纤细纯净，超长平滑渐隐过渡)
        // 左侧横线从 X=145px 向内延伸至 X=325px
        // 右侧横线从 X=1775px 向内延伸至 X=1595px
        // ----------------------------------------------------
        val cornerR = 24f
        leftFramePath.reset()
        leftFramePath.moveTo(325f, 24f)
        leftFramePath.lineTo(145f + cornerR, 24f)
        leftFramePath.quadTo(145f, 24f, 145f, 24f + cornerR)
        leftFramePath.lineTo(145f, 360f - cornerR)
        leftFramePath.quadTo(145f, 360f, 145f + cornerR, 360f)
        leftFramePath.lineTo(325f, 360f)

        rightFramePath.reset()
        rightFramePath.moveTo(1595f, 24f)
        rightFramePath.lineTo(1775f - cornerR, 24f)
        rightFramePath.quadTo(1775f, 24f, 1775f, 24f + cornerR)
        rightFramePath.lineTo(1775f, 360f - cornerR)
        rightFramePath.quadTo(1775f, 360f, 1775f - cornerR, 360f)
        rightFramePath.lineTo(1595f, 360f)

        // 边框朝屏幕内侧渐隐着色器 (超长平滑缓降坡度，贯穿整个横线长度)
        leftFrameYellowShader = LinearGradient(
            145f, 0f, 325f, 0f,
            intArrayOf(
                0xFFFF8F00.toInt(), // 0.00: 最左侧外边缘 - 纯正琥珀金
                0xFFFFA000.toInt(), // 0.20: 饱满金光
                0xCCFFB74D.toInt(), // 0.45: 柔润过渡金
                0x80FFE082.toInt(), // 0.70: 纤细微光金白
                0x30FFFDE7.toInt(), // 0.88: 极微光羽化
                0x00FFFDE7          // 1.00: 彻底消融渐隐
            ),
            floatArrayOf(0.0f, 0.20f, 0.45f, 0.70f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )
        rightFrameYellowShader = LinearGradient(
            1775f, 0f, 1595f, 0f,
            intArrayOf(
                0xFFFF8F00.toInt(), // 0.00: 最右侧外边缘 - 纯正琥珀金
                0xFFFFA000.toInt(), // 0.20: 饱满金光
                0xCCFFB74D.toInt(), // 0.45: 柔润过渡金
                0x80FFE082.toInt(), // 0.70: 纤细微光金白
                0x30FFFDE7.toInt(), // 0.88: 极微光羽化
                0x00FFFDE7          // 1.00: 彻底消融渐隐
            ),
            floatArrayOf(0.0f, 0.20f, 0.45f, 0.70f, 0.88f, 1.0f),
            Shader.TileMode.CLAMP
        )


        // ----------------------------------------------------
        // 2. 变道辅助醒目柔和羽化纯色光幕渐变 (比转向灯宽出 40px，完整填满整个框，不透明度提升 15%)
        // 左侧 130px..365px，右侧 1555px..1790px，宽度均为 235px
        // ----------------------------------------------------
        leftCurtainYellowShader = LinearGradient(
            130f, 0f, 365f, 0f,
            intArrayOf(
                0xB0FFA000.toInt(), // 0.00: 最外边缘 - 饱满醇厚金黄 (不透明度提升15%)
                0x72FFA000.toInt(), // 0.35: 柔和过渡，饱满填满转向灯框且不遮挡文字
                0x32FFA000.toInt(), // 0.70: 优雅羽化，掠过转向灯框尾部
                0x00FFA000          // 1.00: 在 X=365px 处彻底消融为完全透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        leftCurtainRedShader = LinearGradient(
            130f, 0f, 365f, 0f,
            intArrayOf(
                0xC0FF1744.toInt(), // 0.00: 最外边缘 - 醒目警示红 (不透明度提升15%)
                0x85FF1744.toInt(), // 0.35: 柔和过渡，饱满填满转向灯框且不遮挡文字
                0x38FF1744.toInt(), // 0.70: 优雅羽化
                0x00FF1744          // 1.00: 在 X=365px 处彻底消融为完全透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        rightCurtainYellowShader = LinearGradient(
            1790f, 0f, 1555f, 0f,
            intArrayOf(
                0xB0FFA000.toInt(), // 0.00: 最外边缘 - 饱满醇厚金黄
                0x72FFA000.toInt(), // 0.35: 柔和过渡，饱满填满转向灯框且不遮挡文字
                0x32FFA000.toInt(), // 0.70: 优雅羽化
                0x00FFA000          // 1.00: 在 X=1555px 处彻底消融为完全透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        rightCurtainRedShader = LinearGradient(
            1790f, 0f, 1555f, 0f,
            intArrayOf(
                0xC0FF1744.toInt(), // 0.00: 最外边缘 - 醒目警示红
                0x85FF1744.toInt(), // 0.35: 柔和过渡，饱满填满转向灯框且不遮挡文字
                0x38FF1744.toInt(), // 0.70: 优雅羽化
                0x00FF1744          // 1.00: 在 X=1555px 处彻底消融为完全透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // ----------------------------------------------------
        // 3. 变道辅助暗底吸光衬底渐变 (方案一：Contrast Underlay)
        // 范围严格与光幕区域对齐 (左: 130px..365px, 右: 1790px..1555px)
        // 从最外侧向内平滑羽化，强效吸收晴天强烈天光，使上层暖光跃出且文字对比度显著提升
        // ----------------------------------------------------
        leftUnderlayShader = LinearGradient(
            130f, 0f, 365f, 0f,
            intArrayOf(
                0x9C000000.toInt(), // 0.00: 最外侧 - 压暗约 61%，强效吸收白昼烈日高光
                0x65000000.toInt(), // 0.35: 柔和过渡，提升文字区域白昼对比度
                0x28000000.toInt(), // 0.70: 微弱过渡
                0x00000000          // 1.00: 在 365px 处完全消融透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        rightUnderlayShader = LinearGradient(
            1790f, 0f, 1555f, 0f,
            intArrayOf(
                0x9C000000.toInt(), // 0.00: 最外侧 - 压暗约 61%
                0x65000000.toInt(), // 0.35: 柔和过渡
                0x28000000.toInt(), // 0.70: 微弱过渡
                0x00000000          // 1.00: 在 1555px 处完全消融透明
            ),
            floatArrayOf(0.0f, 0.35f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    // ----------------------------------------------------
    // 数据驱动与状态同步
    // ----------------------------------------------------

    fun setTurnSignalInfo(info: CarGearManager.TurnSignalInfo) {
        if (currentTurnSignalInfo != info) {
            currentTurnSignalInfo = info
            postInvalidateOnAnimation()
        }
    }

    fun setLaneChangeAlert(alert: CarGearManager.LaneChangeAlert) {
        if (currentLaneChangeAlert != alert) {
            currentLaneChangeAlert = alert
            postInvalidateOnAnimation()
        }
    }

    fun setTurnSignalSyncEnabled(enabled: Boolean) {
        if (isTurnSignalSyncEnabled != enabled) {
            isTurnSignalSyncEnabled = enabled
            postInvalidateOnAnimation()
        }
    }

    fun setLaneChangeAssistEnabled(enabled: Boolean) {
        if (isLaneChangeAssistEnabled != enabled) {
            isLaneChangeAssistEnabled = enabled
            postInvalidateOnAnimation()
        }
    }

    // ----------------------------------------------------
    // 绘制主流程
    // ----------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || visibility != VISIBLE || alpha <= 0.005f) {
            return
        }

        val hasTurnSignal = isTurnSignalSyncEnabled && (currentTurnSignalInfo.isAnyTurnActive || leftTurnAlpha > 0.005f || rightTurnAlpha > 0.005f)
        val hasLaneChange = isLaneChangeAssistEnabled && currentLaneChangeAlert.hasAnyWarning

        // 极致性能：非活跃期直接返回，不消耗任何算力
        if (!hasTurnSignal && !hasLaneChange) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 严格保持 1920 × 384 纵横比，居中贴合车机可见视界，绝对杜绝平板/大屏畸变拉伸
        val currentAspect = w / h
        val targetAspect = LOGICAL_WIDTH / LOGICAL_HEIGHT

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (currentAspect > targetAspect) {
            scale = h / LOGICAL_HEIGHT
            offsetX = (w - LOGICAL_WIDTH * scale) / 2f
            offsetY = 0f
        } else {
            scale = w / LOGICAL_WIDTH
            offsetX = 0f
            offsetY = (h - LOGICAL_HEIGHT * scale) / 2f
        }

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)

        val now = SystemClock.uptimeMillis()

        // 1. 底层：变道辅助盲区大面积纯色渐变光幕
        if (hasLaneChange) {
            drawLaneChangeCurtains(canvas, now)
        }

        // 2. 顶层：转向灯车规级 3 层光导纤维边框 (置顶于副屏所有内容之上)
        if (hasTurnSignal) {
            drawTurnSignals(canvas, now)
        }

        canvas.restore()

        // 保持 60fps 丝滑动画
        postInvalidateOnAnimation()
    }

    /**
     * 变道辅助盲区柔光羽化微光预警 (绝不遮挡大时钟、微型档位与卡片文字，余光舒适提醒)
     * - 1级预警 (车辆在盲区，未打灯)：外边缘柔和黄色慢速平滑呼吸脉冲 (1200ms 周期，波谷完全透明，波峰温润)
     * - 2级预警 (车辆在盲区，打灯变道)：2级高危红黄柔和急促循环 (500ms 周期，250ms柔红 / 250ms柔黄，波峰通透无遮挡)
     */
    private fun drawLaneChangeCurtains(canvas: Canvas, now: Long) {
        val alert = currentLaneChangeAlert

        // 左侧盲区监测（严格仅在左侧转向激活时升级为红黄闪烁，不受右侧转向灯或 AVM 信号影响）
        if (alert.isLeftWarningActive) {
            val isLeftTurnActive = currentTurnSignalInfo.isLeftTurnActive || currentTurnSignalInfo.isLeftTurn
            val isDanger = alert.isLeftDanger || (alert.leftWarning > 0 && isLeftTurnActive)
            if (isDanger) {
                // 2级高危碰撞：红黄柔和急促循环 (500ms 周期，醒目饱满提示)
                val isRedPhase = ((now % LCA_LEVEL2_DANGER_PERIOD_MS) < (LCA_LEVEL2_DANGER_PERIOD_MS / 2))
                val subPhase = ((now % (LCA_LEVEL2_DANGER_PERIOD_MS / 2)).toFloat() / (LCA_LEVEL2_DANGER_PERIOD_MS / 2).toFloat())
                val pulse = sin(subPhase * Math.PI.toFloat()) // 0..1..0
                val curtainAlpha = (45f + pulse * 210f).toInt().coerceIn(35, 255)
                val lineHaloAlpha = (50f + pulse * 180f).toInt().coerceIn(40, 220)
                val lineCoreAlpha = (75f + pulse * 180f).toInt().coerceIn(60, 255)

                // 方案一：底层暗底吸光衬底 (压暗高亮晴空背景，形成深色对比光槽，消除白昼消色灰化)
                contrastUnderlayPaint.shader = leftUnderlayShader
                contrastUnderlayPaint.alpha = (curtainAlpha * 0.95f).toInt().coerceIn(0, 255)
                canvas.drawRect(leftCurtainRect, contrastUnderlayPaint)

                curtainPaint.shader = if (isRedPhase) leftCurtainRedShader else leftCurtainYellowShader
                curtainPaint.alpha = curtainAlpha
                canvas.drawRect(leftCurtainRect, curtainPaint)

                // 最左侧边缘车规 2 层示廓导光微线 (放大2.5倍，顶天立地占满高度，科技感极强的余光边界定位)
                val haloColor = if (isRedPhase) 0xFFFF1744.toInt() else 0xFFFFA000.toInt()
                val coreColor = if (isRedPhase) 0xFFFF8A80.toInt() else 0xFFFFE082.toInt()
                edgeAccentHaloPaint.color = haloColor
                edgeAccentHaloPaint.alpha = lineHaloAlpha
                canvas.drawLine(132f, 0f, 132f, LOGICAL_HEIGHT, edgeAccentHaloPaint)

                edgeAccentCorePaint.color = coreColor
                edgeAccentCorePaint.alpha = lineCoreAlpha
                canvas.drawLine(132f, 0f, 132f, LOGICAL_HEIGHT, edgeAccentCorePaint)
            } else {
                // 1级盲区提醒：饱满黄色平滑呼吸脉冲 (1200ms 周期，波谷微隐，波峰醒目)
                val pulsePhase = (now % LCA_LEVEL1_PULSE_PERIOD_MS).toFloat() / LCA_LEVEL1_PULSE_PERIOD_MS.toFloat()
                val rawSine = (sin(pulsePhase * 2f * Math.PI.toFloat() - (Math.PI.toFloat() / 2f)) + 1f) * 0.5f
                val pulse = rawSine * rawSine // 平滑二次方，呼吸更柔和自然
                val curtainAlpha = (pulse * 255f).toInt().coerceIn(0, 255)
                val lineHaloAlpha = (pulse * 180f).toInt().coerceIn(0, 180)
                val lineCoreAlpha = (pulse * 255f).toInt().coerceIn(0, 255)

                if (curtainAlpha > 0) {
                    // 方案一：底层暗底吸光衬底 (与呼吸同频淡入淡出，波谷透明，波峰沉底吸光衬托)
                    contrastUnderlayPaint.shader = leftUnderlayShader
                    contrastUnderlayPaint.alpha = (curtainAlpha * 0.95f).toInt().coerceIn(0, 255)
                    canvas.drawRect(leftCurtainRect, contrastUnderlayPaint)

                    curtainPaint.shader = leftCurtainYellowShader
                    curtainPaint.alpha = curtainAlpha
                    canvas.drawRect(leftCurtainRect, curtainPaint)
                }

                if (lineCoreAlpha > 0) {
                    edgeAccentHaloPaint.color = 0xFFFFA000.toInt()
                    edgeAccentHaloPaint.alpha = lineHaloAlpha
                    canvas.drawLine(132f, 0f, 132f, LOGICAL_HEIGHT, edgeAccentHaloPaint)

                    edgeAccentCorePaint.color = 0xFFFFE082.toInt()
                    edgeAccentCorePaint.alpha = lineCoreAlpha
                    canvas.drawLine(132f, 0f, 132f, LOGICAL_HEIGHT, edgeAccentCorePaint)
                }
            }
        }

        // 右侧盲区监测（严格仅在右侧转向激活时升级为红黄闪烁，不受左侧转向灯或 AVM 信号影响）
        if (alert.isRightWarningActive) {
            val isRightTurnActive = currentTurnSignalInfo.isRightTurnActive || currentTurnSignalInfo.isRightTurn
            val isDanger = alert.isRightDanger || (alert.rightWarning > 0 && isRightTurnActive)
            if (isDanger) {
                // 2级高危碰撞：红黄柔和急促循环 (500ms 周期，醒目饱满提示)
                val isRedPhase = ((now % LCA_LEVEL2_DANGER_PERIOD_MS) < (LCA_LEVEL2_DANGER_PERIOD_MS / 2))
                val subPhase = ((now % (LCA_LEVEL2_DANGER_PERIOD_MS / 2)).toFloat() / (LCA_LEVEL2_DANGER_PERIOD_MS / 2).toFloat())
                val pulse = sin(subPhase * Math.PI.toFloat())
                val curtainAlpha = (45f + pulse * 210f).toInt().coerceIn(35, 255)
                val lineHaloAlpha = (50f + pulse * 180f).toInt().coerceIn(40, 220)
                val lineCoreAlpha = (75f + pulse * 180f).toInt().coerceIn(60, 255)

                // 方案一：底层暗底吸光衬底 (压暗高亮晴空背景，形成深色对比光槽，消除白昼消色灰化)
                contrastUnderlayPaint.shader = rightUnderlayShader
                contrastUnderlayPaint.alpha = (curtainAlpha * 0.95f).toInt().coerceIn(0, 255)
                canvas.drawRect(rightCurtainRect, contrastUnderlayPaint)

                curtainPaint.shader = if (isRedPhase) rightCurtainRedShader else rightCurtainYellowShader
                curtainPaint.alpha = curtainAlpha
                canvas.drawRect(rightCurtainRect, curtainPaint)

                // 最右侧边缘车规 2 层示廓导光微线 (放大2.5倍，顶天立地占满高度，科技感极强的余光边界定位)
                val haloColor = if (isRedPhase) 0xFFFF1744.toInt() else 0xFFFFA000.toInt()
                val coreColor = if (isRedPhase) 0xFFFF8A80.toInt() else 0xFFFFE082.toInt()
                edgeAccentHaloPaint.color = haloColor
                edgeAccentHaloPaint.alpha = lineHaloAlpha
                canvas.drawLine(1788f, 0f, 1788f, LOGICAL_HEIGHT, edgeAccentHaloPaint)

                edgeAccentCorePaint.color = coreColor
                edgeAccentCorePaint.alpha = lineCoreAlpha
                canvas.drawLine(1788f, 0f, 1788f, LOGICAL_HEIGHT, edgeAccentCorePaint)
            } else {
                // 1级盲区提醒：饱满黄色平滑呼吸脉冲 (1200ms 周期，波谷微隐，波峰醒目)
                val pulsePhase = (now % LCA_LEVEL1_PULSE_PERIOD_MS).toFloat() / LCA_LEVEL1_PULSE_PERIOD_MS.toFloat()
                val rawSine = (sin(pulsePhase * 2f * Math.PI.toFloat() - (Math.PI.toFloat() / 2f)) + 1f) * 0.5f
                val pulse = rawSine * rawSine
                val curtainAlpha = (pulse * 255f).toInt().coerceIn(0, 255)
                val lineHaloAlpha = (pulse * 180f).toInt().coerceIn(0, 180)
                val lineCoreAlpha = (pulse * 255f).toInt().coerceIn(0, 255)

                if (curtainAlpha > 0) {
                    // 方案一：底层暗底吸光衬底 (与呼吸同频淡入淡出，波谷透明，波峰沉底吸光衬托)
                    contrastUnderlayPaint.shader = rightUnderlayShader
                    contrastUnderlayPaint.alpha = (curtainAlpha * 0.95f).toInt().coerceIn(0, 255)
                    canvas.drawRect(rightCurtainRect, contrastUnderlayPaint)

                    curtainPaint.shader = rightCurtainYellowShader
                    curtainPaint.alpha = curtainAlpha
                    canvas.drawRect(rightCurtainRect, curtainPaint)
                }

                if (lineCoreAlpha > 0) {
                    edgeAccentHaloPaint.color = 0xFFFFA000.toInt()
                    edgeAccentHaloPaint.alpha = lineHaloAlpha
                    canvas.drawLine(1788f, 0f, 1788f, LOGICAL_HEIGHT, edgeAccentHaloPaint)

                    edgeAccentCorePaint.color = 0xFFFFE082.toInt()
                    edgeAccentCorePaint.alpha = lineCoreAlpha
                    canvas.drawLine(1788f, 0f, 1788f, LOGICAL_HEIGHT, edgeAccentCorePaint)
                }
            }
        }
    }

    /**
     * 转向灯核心绘制：物理闪光继电器 1:1 严格同频光导纤维边框
     * 彻底废除与硬件脱节的二次定时器，帧率级紧跟实车继电器咔哒声与 CAN 信号！
     */
    private fun drawTurnSignals(canvas: Canvas, now: Long) {
        val info = currentTurnSignalInfo

        val dt = if (lastTurnAnimTime > 0L) (now - lastTurnAnimTime).coerceIn(1L, 60L) else 16L
        lastTurnAnimTime = now

        // 针对双闪（0x21400a39 ID_BODY_EMERGENCY_LIGHT）：
        // 实车总线仅上报开关稳态 (1=开, 0=关)，并不下发高频闪烁脉冲。
        // 因此当开启双闪时，由小欧屏按实车标准闪光继电器节奏 (~760ms 周期，380ms 亮 / 380ms 灭) 1:1 动态闪动；
        // 而常规单侧左/右转向灯 (0x21400a35 / 0x21400a37) 实车已由硬件闪光继电器输出物理脉冲，直接跟随。
        val hazardBlink = if (info.isHazard) {
            if (hazardStartTime <= 0L) {
                hazardStartTime = now
            }
            val elapsed = now - hazardStartTime
            if ((elapsed % 760L) < 380L) 1.0f else 0.0f
        } else {
            hazardStartTime = 0L
            0.0f
        }

        val targetLeft = if (info.isHazard) hazardBlink else if (info.isLeftTurn) 1.0f else 0.0f
        val targetRight = if (info.isHazard) hazardBlink else if (info.isRightTurn) 1.0f else 0.0f

        // 35ms 极速点亮响应实车继电器闭合，45ms 柔和淡出消解断电突兀感
        if (targetLeft > leftTurnAlpha) {
            leftTurnAlpha = (leftTurnAlpha + dt / 35f).coerceAtMost(1.0f)
        } else if (targetLeft < leftTurnAlpha) {
            leftTurnAlpha = (leftTurnAlpha - dt / 45f).coerceAtLeast(0.0f)
        }

        if (targetRight > rightTurnAlpha) {
            rightTurnAlpha = (rightTurnAlpha + dt / 35f).coerceAtMost(1.0f)
        } else if (targetRight < rightTurnAlpha) {
            rightTurnAlpha = (rightTurnAlpha - dt / 45f).coerceAtLeast(0.0f)
        }

        // 绘制左转向灯 (顶层纯正琥珀金双层晶体边框，绝不被变道辅助染红)
        if (leftTurnAlpha > 0.005f) {
            drawSingleTurnSide(
                canvas = canvas,
                framePath = leftFramePath,
                shader = leftFrameYellowShader,
                masterAlpha = leftTurnAlpha
            )
        }

        // 绘制右转向灯 (顶层纯正琥珀金双层晶体边框，绝不被变道辅助染红)
        if (rightTurnAlpha > 0.005f) {
            drawSingleTurnSide(
                canvas = canvas,
                framePath = rightFramePath,
                shader = rightFrameYellowShader,
                masterAlpha = rightTurnAlpha
            )
        }
    }

    /**
     * 单侧转向灯精致晶体双层边框绘制 (2.8px 锐利晶体核心线 + 6.0px 纤细透亮微光晕)
     * 100% 坚守纯正琥珀金，置顶于变道辅助一切光幕之上
     */
    private fun drawSingleTurnSide(
        canvas: Canvas,
        framePath: Path,
        shader: LinearGradient?,
        masterAlpha: Float
    ) {
        val activeShader = shader ?: return
        frameGlowPaint.shader = activeShader
        frameCorePaint.shader = activeShader

        val coreAlpha = (masterAlpha * 255f).toInt().coerceIn(0, 255)
        val glowAlpha = (masterAlpha * 140f).toInt().coerceIn(0, 255)

        // 第 1 层：15.0px 晶体微光晕 (放大2.5倍，饱满通透，绝不臃肿发糊)
        frameGlowPaint.alpha = glowAlpha
        canvas.drawPath(framePath, frameGlowPaint)

        // 第 2 层：7.0px 晶体核心精致高亮线 (放大2.5倍，醒目纯正琥珀金)
        frameCorePaint.alpha = coreAlpha
        canvas.drawPath(framePath, frameCorePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        leftTurnAlpha = 0f
        rightTurnAlpha = 0f
        lastTurnAnimTime = 0L
        hazardStartTime = 0L
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            leftTurnAlpha = 0f
            rightTurnAlpha = 0f
            lastTurnAnimTime = 0L
            hazardStartTime = 0L
        } else if (isTurnSignalSyncEnabled || isLaneChangeAssistEnabled) {
            postInvalidateOnAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            leftTurnAlpha = 0f
            rightTurnAlpha = 0f
            lastTurnAnimTime = 0L
            hazardStartTime = 0L
        } else if (isTurnSignalSyncEnabled || isLaneChangeAssistEnabled) {
            postInvalidateOnAnimation()
        }
    }
}


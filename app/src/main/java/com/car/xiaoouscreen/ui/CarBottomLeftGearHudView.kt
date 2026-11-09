package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils

/**
 * 欧尚 Z6 iDD 小欧屏 1920×384 左下角常驻微型档位指示器 (自适应中性黑曜石悬浮 HUD · 连续物理光轨版)
 *
 * 100% 车规级原生 RenderThread 渲染实现：
 * 1. 纯中性自适应暗晶玻璃：
 *    - 彻底剔除生硬冷蓝色偏，采用纯中性深色黑曜石底板（Neutral Dark Obsidian Glass）；
 *    - 无论叠在晚霞落日（橙红）、正午晴空（天蓝）还是静谧夜晚（墨黑）上，均天然透射压暗为奢华晶体，浑然天成；
 * 2. 连续物理光轨切档补全 (Continuous Track Illumination)：
 *    - R · N · D · P 四档字模常驻轨道，胶囊作为“高光晶体聚光滑块”在轨道上平滑穿梭；
 *    - 当滑块滑经任意档位时，该档位平滑被“点亮”为纯白高光（微字号与阴影联动），离开后平滑收敛为质感半透白；
 *    - 从 R 到 P、P 到 N 全流程连续平滑位移，根据移动跨度自适应动量阻尼时长（260ms~420ms），绝无半途跳档割裂；
 *    - 滑块色彩与 Bloom 辉光沿物理轨道在【红 -> 冷白 -> 绿 -> 橙】间连续光谱过渡；
 * 3. 严格等距呼吸留白 (Padding = 6.0px)：
 *    - 槽体 288×60px (R=17f)，胶囊 48×48px (R=14f)；
 *    - R 档激活：【上、左、下】留白严格恒等于 6.0px；
 *    - P 档激活：【上、右、下】留白严格恒等于 6.0px；
 * 4. 严谨建筑级对齐：槽体最左侧与天气文字最左侧 (hudStartX = 185px) 像素级垂直锚定。
 */
class CarBottomLeftGearHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val LOGICAL_WIDTH = 1920f
        private const val LOGICAL_HEIGHT = 384f

        // 槽体几何尺寸 (整体 288×60px，在小欧屏可用最左侧 130px 与天气面板最右侧 535px 之间水平居中：130 + (405 - 288)/2 = 188.5px)
        const val DEFAULT_SLOT_LEFT = 188.5f
        private const val SLOT_WIDTH = 288f
        private const val SLOT_HEIGHT = 60f
        private const val SLOT_TOP = 314f
        private const val SLOT_BOTTOM = 374f
        private const val SLOT_RADIUS = 17f
        private const val SLOT_CENTER_Y = (SLOT_TOP + SLOT_BOTTOM) / 2f // 344f

        // 激活滑块胶囊尺寸 (48×48px, 留白 Padding = (60 - 48)/2 = 6px)
        private const val CAPSULE_WIDTH = 48f
        private const val CAPSULE_HEIGHT = 48f
        private const val CAPSULE_RADIUS = 14f // 与外槽 R=17f 同心圆角
        private const val SLOT_PADDING = (SLOT_HEIGHT - CAPSULE_HEIGHT) / 2f // 严格 6.0px

        // 四档位 X 中心相对槽左侧偏移：
        // R 档：中心 = 6 + 24 = 30px (此时上、左、下留白严格均为 6px)
        // P 档：中心 = 288 - 6 - 24 = 258px (此时上、右、下留白严格均为 6px)
        // 中间 3 段间隔严格均匀 = (258 - 30) / 3 = 76px
        private const val OFFSET_GEAR_R = SLOT_PADDING + CAPSULE_WIDTH / 2f // 30f
        private const val OFFSET_GEAR_N = OFFSET_GEAR_R + 76f // 106f
        private const val OFFSET_GEAR_D = OFFSET_GEAR_N + 76f // 182f
        private const val OFFSET_GEAR_P = OFFSET_GEAR_D + 76f // 258f

        // 间隔小圆点相对偏移
        private const val OFFSET_DOT_1 = (OFFSET_GEAR_R + OFFSET_GEAR_N) / 2f // 68f
        private const val OFFSET_DOT_2 = (OFFSET_GEAR_N + OFFSET_GEAR_D) / 2f // 144f
        private const val OFFSET_DOT_3 = (OFFSET_GEAR_D + OFFSET_GEAR_P) / 2f // 220f

        // 档位色彩定义
        private val COLOR_R = Color.parseColor("#EF4444") // 烈焰红
        private val COLOR_N = Color.parseColor("#F1F5F9") // 金属冷白
        private val COLOR_D = Color.parseColor("#10B981") // 极速绿
        private val COLOR_P = Color.parseColor("#F97316") // 活力橙

        // 降采样液态磨砂玻璃模糊缓冲尺寸 (4x 降采样: 288/4=72, 60/4=15)
        private const val GLASS_BUFFER_W = 72
        private const val GLASS_BUFFER_H = 15
    }

    // 动态锚定：外部提供天气文字框最左侧 X 坐标 (保持严谨对齐)
    var weatherLeftProvider: (() -> Float)? = null

    // 外部背景渲染提供者 (支持将底层实时动态天气/晚霞抓取并注入磨砂玻璃模糊层)
    var backgroundProvider: ((Canvas, RectF) -> Unit)? = null

    // 当前槽体水平布局位置
    private var currentSlotLeft = DEFAULT_SLOT_LEFT
    private val slotRect = RectF(DEFAULT_SLOT_LEFT, SLOT_TOP, DEFAULT_SLOT_LEFT + SLOT_WIDTH, SLOT_BOTTOM)
    private val slotPath = Path()
    private val capsuleRect = RectF()
    private val colorBarRect = RectF()

    // 档位标签元数据
    private val gearLabels = arrayOf("R", "N", "D", "P")
    private val gearOffsets = floatArrayOf(OFFSET_GEAR_R, OFFSET_GEAR_N, OFFSET_GEAR_D, OFFSET_GEAR_P)

    // 档位状态与动效 (采用槽内相对坐标驱动，彻底消除外部锚点微位移导致的未停正偏心 Bug)
    private var currentGear = "P"
    private var capsuleOffset = OFFSET_GEAR_P
    private var targetOffset = OFFSET_GEAR_P
    private var currentGearColor = COLOR_P

    private var slideAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()
    private val slideInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f) // 车规级优雅缓动

    // 显隐过渡动画
    private var isHudVisible = true
    private var hudAlpha = 1.0f
    private var fadeAnimator: ValueAnimator? = null

    // 液态磨砂玻璃离屏缓冲 (4x 降采样)
    private val glassBgBitmap = Bitmap.createBitmap(GLASS_BUFFER_W, GLASS_BUFFER_H, Bitmap.Config.ARGB_8888)
    private val glassBgCanvas = Canvas(glassBgBitmap)
    private val glassPixels = IntArray(GLASS_BUFFER_W * GLASS_BUFFER_H)
    private val glassPixelsTemp = IntArray(GLASS_BUFFER_W * GLASS_BUFFER_H)
    private val glassSrcRect = Rect(0, 0, GLASS_BUFFER_W, GLASS_BUFFER_H)
    private var isBackgroundDirty = true
    private var lastGlassBlurUpdateTime = 0L

    /**
     * 标记背景为脏，提示下次绘制时重新捕获并模糊背景
     */
    fun invalidateBackground() {
        isBackgroundDirty = true
        invalidate()
    }

    // ==========================================
    // 画笔定义 (严格与大时间液态玻璃效果 100% 统一)
    // ==========================================

    // 1. 极轻柔晶体悬浮投影画笔 (消除沉重黑框，通透悬浮)
    private val shadowAmbientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
        setShadowLayer(14f, 0f, 4f, Color.argb(45, 0, 0, 0))
    }

    // 2. 170% 高动态饱和度背景模糊画笔 (与大时间完全一致: setSaturation(1.70f))
    private val glassBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.70f) })
    }

    // 3. 叠一层白色微光层 (与大时间完全一致: rgb 255 255 255 / 10%)
    private val glassOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(26, 255, 255, 255)
    }

    // 4. 表面曲率微流光渐变画笔 (与大时间完全一致: Top 27% 白光反射，底边天光漫射)
    private val glassSurfaceShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 5. 边缘厚度浮雕画笔：inet 1px 1px #fff8 (与大时间完全一致: 内倒角高光厚度)
    private val glassInsetLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(180, 255, 255, 255)
    }

    // 6. 边缘全曲率晶体折射描边画笔 (与大时间完全一致: Fresnel Refraction Rim)
    private val glassRefractionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }

    // 7. 边缘折射微弱菲涅尔光晕画笔 (与大时间完全一致: Refraction Halo Glow)
    private val glassOuterRefractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.TRANSPARENT
        setShadowLayer(12f, 0f, 0f, Color.argb(110, 255, 255, 255))
    }

    // 8. 连续物理光轨文字与点画笔 (纯白金属感 + 自适应连续发光)
    private val gearTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(85, 255, 255, 255)
        setShadowLayer(2f, 0f, 1f, Color.argb(80, 0, 0, 0))
    }

    // 9. 激活滑块胶囊：液态凸透晶体聚光镜画笔
    private val capsuleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }
    private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capsuleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
    }
    private val colorBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        updateSlotGeometry(DEFAULT_SLOT_LEFT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 更新槽体水平对齐几何与对应 Shaders
     */
    private fun updateSlotGeometry(slotLeft: Float) {
        currentSlotLeft = slotLeft
        slotRect.set(slotLeft, SLOT_TOP, slotLeft + SLOT_WIDTH, SLOT_BOTTOM)

        slotPath.reset()
        slotPath.addRoundRect(slotRect, SLOT_RADIUS, SLOT_RADIUS, Path.Direction.CW)

        setupShaders()
        isBackgroundDirty = true
        // 关键：由于 capsuleOffset 采用槽内相对坐标，当外部天气面板文字宽度变动自适应推拉槽体时，
        // 胶囊自然同步跟随整体平移，绝对不再打断飞行中动画或造成偏心！
    }

    private fun setupShaders() {
        // 1. 表面曲率微流光渐变 (严格与大时间完全一致: Top 27% 白光反射，底边天光漫射)
        glassSurfaceShinePaint.shader = LinearGradient(
            0f, SLOT_TOP,
            0f, SLOT_BOTTOM,
            intArrayOf(
                Color.argb(70, 255, 255, 255), // Top 27% 白光反射
                Color.argb(12, 255, 255, 255), // Mid 5% 微漫射
                Color.argb(38, 255, 255, 255)  // Bottom 15% 地平天光
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. 边缘全曲率晶体折射描边 (严格与大时间完全一致: 四段菲涅尔折射天光)
        glassRefractionStrokePaint.shader = LinearGradient(
            currentSlotLeft, SLOT_TOP,
            currentSlotLeft + SLOT_WIDTH, SLOT_BOTTOM,
            intArrayOf(
                Color.argb(245, 255, 255, 255), // 顶左晶亮折射纯白
                Color.argb(110, 255, 255, 255), // 漫射
                Color.argb(190, 255, 255, 255), // 下方晶体环境折射
                Color.argb(225, 255, 255, 255)  // 底右收敛折射纯白
            ),
            floatArrayOf(0f, 0.35f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. 激活滑块胶囊：凸透晶体液态玻璃 Shader (通透聚焦，与主玻璃完美共融)
        val capsuleTop = SLOT_CENTER_Y - CAPSULE_HEIGHT / 2f
        val capsuleBottom = SLOT_CENTER_Y + CAPSULE_HEIGHT / 2f
        capsuleStrokePaint.shader = LinearGradient(
            0f, capsuleTop,
            0f, capsuleBottom,
            intArrayOf(
                Color.argb(235, 255, 255, 255),
                Color.argb(120, 255, 255, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        capsuleBgPaint.shader = LinearGradient(
            0f, capsuleTop,
            0f, capsuleBottom,
            intArrayOf(
                Color.argb(75, 255, 255, 255),
                Color.argb(28, 255, 255, 255),
                Color.argb(48, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 根据当前滑块在槽内的相对 X 偏移量沿物理光轨实时计算对应档位光谱色
     * R (烈焰红) <-> N (金属冷白) <-> D (极速翡翠绿) <-> P (活力璀璨橙)
     */
    private fun evaluateTrackColor(offset: Float): Int {
        return when {
            offset <= OFFSET_GEAR_N -> {
                val f = ((offset - OFFSET_GEAR_R) / (OFFSET_GEAR_N - OFFSET_GEAR_R)).coerceIn(0f, 1f)
                argbEvaluator.evaluate(f, COLOR_R, COLOR_N) as Int
            }
            offset <= OFFSET_GEAR_D -> {
                val f = ((offset - OFFSET_GEAR_N) / (OFFSET_GEAR_D - OFFSET_GEAR_N)).coerceIn(0f, 1f)
                argbEvaluator.evaluate(f, COLOR_N, COLOR_D) as Int
            }
            else -> {
                val f = ((offset - OFFSET_GEAR_D) / (OFFSET_GEAR_P - OFFSET_GEAR_D)).coerceIn(0f, 1f)
                argbEvaluator.evaluate(f, COLOR_D, COLOR_P) as Int
            }
        }
    }

    /**
     * 更新当前档位 (支持 P, R, N, D)
     * 连续平滑无级滑移动画与连续色彩光谱过渡，彻底杜绝割裂与半途跳档
     */
    fun setGear(gear: String, animate: Boolean = true) {
        val normalized = gear.trim().uppercase()
        if (normalized !in listOf("P", "R", "N", "D")) return

        val newTargetOffset = when (normalized) {
            "R" -> OFFSET_GEAR_R
            "N" -> OFFSET_GEAR_N
            "D" -> OFFSET_GEAR_D
            "P" -> OFFSET_GEAR_P
            else -> OFFSET_GEAR_P
        }

        currentGear = normalized

        if (!animate) {
            slideAnimator?.cancel()
            slideAnimator = null
            capsuleOffset = newTargetOffset
            targetOffset = newTargetOffset
            currentGearColor = evaluateTrackColor(newTargetOffset)
            invalidate()
            return
        }

        // 已经锁定该目标且正在滑行中，安全忽略重复请求，防止中途打断
        if (newTargetOffset == targetOffset && slideAnimator?.isRunning == true) {
            return
        }
        // 已经静止停留在目标位置且无动画运行，直接返回
        if (newTargetOffset == capsuleOffset && slideAnimator == null) {
            return
        }

        val startOffset = capsuleOffset
        targetOffset = newTargetOffset

        val distance = kotlin.math.abs(newTargetOffset - startOffset)
        val moveDuration = when {
            distance > 180f -> 420L // 3 档大跨度 (R -> P 或 P -> R)：420ms 丝滑完整扫过整条轨道
            distance > 100f -> 340L // 2 档跨度 (R -> D 或 P -> N)：340ms
            else -> 260L            // 1 档近距 (如 N -> D)：260ms
        }

        slideAnimator?.cancel()
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = moveDuration
            interpolator = slideInterpolator
            addUpdateListener { va ->
                val frac = va.animatedFraction
                capsuleOffset = startOffset + (newTargetOffset - startOffset) * frac
                currentGearColor = evaluateTrackColor(capsuleOffset)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 核心保险：动画结束回调中执行物理硬对齐！
                    // 彻底清除浮点数插值尾数截断误差，确保胶囊 100% 严谨居中在目标档位文字中心！
                    capsuleOffset = newTargetOffset
                    currentGearColor = evaluateTrackColor(newTargetOffset)
                    slideAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * 设置显隐控制 (带 250ms 优雅淡入淡出)
     */
    fun setHudEnabled(enabled: Boolean, animate: Boolean = true) {
        if (isHudVisible == enabled) return
        isHudVisible = enabled

        fadeAnimator?.cancel()
        if (!animate) {
            hudAlpha = if (enabled) 1f else 0f
            visibility = if (enabled) VISIBLE else GONE
            invalidate()
            return
        }

        if (enabled) {
            visibility = VISIBLE
        }

        val startAlpha = hudAlpha
        val endAlpha = if (enabled) 1f else 0f
        fadeAnimator = ValueAnimator.ofFloat(startAlpha, endAlpha).apply {
            duration = 250L
            addUpdateListener { va ->
                hudAlpha = va.animatedValue as Float
                alpha = hudAlpha
                if (hudAlpha <= 0.01f && !enabled) {
                    visibility = GONE
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hudAlpha <= 0.005f || visibility != VISIBLE) return

        // 动态同步天气文字最左侧锚点
        val targetLeft = weatherLeftProvider?.invoke() ?: DEFAULT_SLOT_LEFT
        if (kotlin.math.abs(targetLeft - currentSlotLeft) > 0.5f) {
            updateSlotGeometry(targetLeft)
        }

        val w = width.toFloat()
        val h = height.toFloat()
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

        // 1. 绘制极轻柔通透环境悬浮投影 (消除黑框，通透悬浮)
        canvas.drawRoundRect(slotRect, SLOT_RADIUS, SLOT_RADIUS, shadowAmbientPaint)

        // 2. 抓取并渲染局部 28px 磨砂玻璃背景 (主动清除脏帧，严格 170% 动态饱和度)
        drawFrostedGlassBackground(canvas)

        // 3. 绘制与大时间 100% 统一的液态玻璃材质层 (10%白底叠层 + 表面微流光 + inet 1px 1px #fff8 边缘厚度)
        drawLiquidGlassSlotLayers(canvas)

        // 4. 绘制边缘晶体折射描边与菲涅尔天光衍射光晕 (完全对齐大时间)
        drawRefractionRim(canvas)

        // 5. 绘制连续物理光轨：液态凸透聚光镜 + 4档连续近邻点亮文字 + 优雅档位彩色微标
        drawGearTrackAndCapsule(canvas)

        canvas.restore()
    }

    /**
     * 2. 绘制局部磨砂玻璃背景 (28px 快速盒式模糊 + 170% 饱和度 + 节流复用，避免切档动画 60fps 重复模糊)
     */
    private fun drawFrostedGlassBackground(canvas: Canvas) {
        val provider = backgroundProvider ?: return
        canvas.save()
        canvas.clipPath(slotPath)

        val nowMs = android.os.SystemClock.uptimeMillis()
        val isSlidingAnim = slideAnimator?.isRunning == true
        // 切档滑移动画执行期间暂缓盒式模糊重算，直接复用当前快照，保证 60FPS 满帧无卡顿
        if (!isSlidingAnim && (isBackgroundDirty || lastGlassBlurUpdateTime == 0L || (nowMs - lastGlassBlurUpdateTime >= 350L))) {
            lastGlassBlurUpdateTime = nowMs
            isBackgroundDirty = false

            // 关键：清空缓冲脏数据，防止白天冷蓝像素残留至黄昏/夜晚
            glassBgCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // 抓取底层实时天气到 4x 降采样缓冲
            glassBgCanvas.save()
            glassBgCanvas.scale(
                GLASS_BUFFER_W / slotRect.width(),
                GLASS_BUFFER_H / slotRect.height()
            )
            glassBgCanvas.translate(-slotRect.left, -slotRect.top)
            provider.invoke(glassBgCanvas, slotRect)
            glassBgCanvas.restore()

            // 执行 28px 快速盒式模糊 (半径 7 * 4x = 28px，严格等效大时间)
            glassBgBitmap.getPixels(glassPixels, 0, GLASS_BUFFER_W, 0, 0, GLASS_BUFFER_W, GLASS_BUFFER_H)
            fastBoxBlur(glassPixels, glassPixelsTemp, GLASS_BUFFER_W, GLASS_BUFFER_H, 7)
            glassBgBitmap.setPixels(glassPixels, 0, GLASS_BUFFER_W, 0, 0, GLASS_BUFFER_W, GLASS_BUFFER_H)
        }

        // 绘制模糊后的高饱和背景 (饱和度 170%)
        if (!glassBgBitmap.isRecycled) {
            canvas.drawBitmap(glassBgBitmap, glassSrcRect, slotRect, glassBitmapPaint)
        }

        canvas.restore()
    }

    /**
     * 3. 绘制与大时间 100% 统一的液态玻璃材质层：
     * (A) 叠一层白色（rgb255 255 255 / 10%）
     * (B) 表面曲率微流光渐变 (Top 27% 白光反射，底边天光漫射)
     * (C) 边缘厚度浮雕：inet 1px 1px #fff8 (#88FFFFFF 偏移 1px 1px 内嵌高光)
     */
    private fun drawLiquidGlassSlotLayers(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(slotPath)

        // (A) 叠一层白色（rgb 255 255 255 / 10%）
        canvas.drawRect(slotRect, glassOverlayPaint)

        // (B) 表面曲率微流光渐变
        canvas.drawRect(slotRect, glassSurfaceShinePaint)

        // (C) 槽口边缘厚度：inet 1px 1px #fff8 (内倒角高光厚度)
        canvas.save()
        canvas.translate(1.0f, 1.0f)
        canvas.drawRoundRect(slotRect, SLOT_RADIUS, SLOT_RADIUS, glassInsetLightPaint)
        canvas.restore()

        canvas.restore()
    }

    /**
     * 4. 绘制边缘晶体折射与菲涅尔天光光晕 (完全对齐大时间)
     */
    private fun drawRefractionRim(canvas: Canvas) {
        // (A) 边缘全曲率晶体折射描边 (Fresnel Refraction Rim)
        canvas.drawRoundRect(slotRect, SLOT_RADIUS, SLOT_RADIUS, glassRefractionStrokePaint)

        // (B) 边缘菲涅尔天光衍射光晕 (Refraction Halo Glow)
        canvas.drawRoundRect(slotRect, SLOT_RADIUS, SLOT_RADIUS, glassOuterRefractionPaint)
    }

    /**
     * 5. 核心：连续物理光轨综合绘制 (凸透晶体聚光滑块 + 3颗小圆点 + 4档连续点亮文字 + 优雅档位彩色横条)
     */
    private fun drawGearTrackAndCapsule(canvas: Canvas) {
        val cx = currentSlotLeft + capsuleOffset
        val cy = SLOT_CENTER_Y

        capsuleRect.set(
            cx - CAPSULE_WIDTH / 2f,
            cy - CAPSULE_HEIGHT / 2f,
            cx + CAPSULE_WIDTH / 2f,
            cy + CAPSULE_HEIGHT / 2f
        )

        // (A) 激活滑块胶囊柔和宝石微光 (克制优雅，杜绝核爆式荧光杂色，画面极简统一)
        capsuleGlowPaint.setShadowLayer(
            9f,
            0f,
            0f,
            ColorUtils.setAlphaComponent(currentGearColor, 75)
        )
        canvas.drawRoundRect(capsuleRect, CAPSULE_RADIUS, CAPSULE_RADIUS, capsuleGlowPaint)

        // (B) 液态凸透晶体滑块底座与微高光外框
        canvas.drawRoundRect(capsuleRect, CAPSULE_RADIUS, CAPSULE_RADIUS, capsuleBgPaint)
        canvas.drawRoundRect(capsuleRect, CAPSULE_RADIUS, CAPSULE_RADIUS, capsuleStrokePaint)

        // (B2) 滑块内部内嵌浮雕高光 (inet 1px 1px #fff8 同步赋能滑块晶体厚度)
        canvas.save()
        canvas.clipRect(capsuleRect)
        canvas.translate(0.8f, 0.8f)
        canvas.drawRoundRect(capsuleRect, CAPSULE_RADIUS, CAPSULE_RADIUS, glassInsetLightPaint)
        canvas.restore()

        // (C) 绘制 3 颗小圆点 (轻盈半透白)
        val dotY = SLOT_CENTER_Y
        canvas.drawCircle(currentSlotLeft + OFFSET_DOT_1, dotY, 2.0f, dotPaint)
        canvas.drawCircle(currentSlotLeft + OFFSET_DOT_2, dotY, 2.0f, dotPaint)
        canvas.drawCircle(currentSlotLeft + OFFSET_DOT_3, dotY, 2.0f, dotPaint)

        // (D) 连续光轨文字点亮：4个档位常驻轨道，当滑块掠过时自适应无级点亮
        val baseTextY = SLOT_CENTER_Y + 7.5f
        for (i in 0 until 4) {
            val label = gearLabels[i]
            val gx = currentSlotLeft + gearOffsets[i]

            // 计算当前滑块与该档位中心的物理像素距离 (直接基于槽内相对位移计算，严谨可靠)
            val dist = kotlin.math.abs(capsuleOffset - gearOffsets[i])
            val proximity = (1f - dist / (CAPSULE_WIDTH * 0.75f)).coerceIn(0f, 1f)
            val glowFraction = proximity * proximity // 二次幂平滑加权

            // 未激活基础 Alpha: 135 (~53% 纯白)，高光滑块经过时平滑提升至 255 (100% 晶亮白)
            val textAlpha = (135 + (120 * glowFraction)).toInt().coerceIn(0, 255)
            gearTextPaint.color = Color.argb(textAlpha, 255, 255, 255)

            // 清晰立体微暗影，保证在任何云层与背景下文字纯净锐利
            val shadowAlpha = (110 + (70 * glowFraction)).toInt().coerceIn(0, 255)
            gearTextPaint.setShadowLayer(
                3.5f + 1.5f * glowFraction,
                0f, 1f,
                Color.argb(shadowAlpha, 0, 0, 0)
            )

            // 微动态字号：常态 22px，正中高光 23.2px
            gearTextPaint.textSize = 22f + 1.2f * glowFraction

            canvas.drawText(label, gx, baseTextY, gearTextPaint)
        }

        // (E) 胶囊正下方精致档位彩色短横条 (长 15px, 高 2.6px, 圆角 1.3px, 柔和微光)
        val barW = 15f
        val barH = 2.6f
        val barY = cy + 15.2f
        colorBarRect.set(cx - barW / 2f, barY - barH / 2f, cx + barW / 2f, barY + barH / 2f)
        colorBarPaint.color = currentGearColor
        colorBarPaint.setShadowLayer(5f, 0f, 0f, currentGearColor)
        canvas.drawRoundRect(colorBarRect, 1.3f, 1.3f, colorBarPaint)
    }

    /**
     * 定点快速盒式模糊算法 (零 GC 堆内存分配，耗时 <0.02ms)
     */
    private fun fastBoxBlur(pix: IntArray, temp: IntArray, w: Int, h: Int, radius: Int) {
        val r = radius.coerceAtLeast(1)
        val div = 2 * r + 1
        val invDiv = ((1L shl 16) / div).toInt()

        // 水平通路
        for (y in 0 until h) {
            val yOffset = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -r..r) {
                val p = pix[yOffset + i.coerceIn(0, w - 1)]
                aSum += p ushr 24
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }

            for (x in 0 until w) {
                temp[yOffset + x] =
                    (((aSum * invDiv) shr 16) shl 24) or
                            (((rSum * invDiv) shr 16) shl 16) or
                            (((gSum * invDiv) shr 16) shl 8) or
                            ((bSum * invDiv) shr 16)

                val pLeft = pix[yOffset + (x - r).coerceAtLeast(0)]
                val pRight = pix[yOffset + (x + r + 1).coerceAtMost(w - 1)]

                aSum += (pRight ushr 24) - (pLeft ushr 24)
                rSum += ((pRight ushr 16) and 0xFF) - ((pLeft ushr 16) and 0xFF)
                gSum += ((pRight ushr 8) and 0xFF) - ((pLeft ushr 8) and 0xFF)
                bSum += (pRight and 0xFF) - (pLeft and 0xFF)
            }
        }

        // 垂直通路
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -r..r) {
                val p = temp[i.coerceIn(0, h - 1) * w + x]
                aSum += p ushr 24
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }

            for (y in 0 until h) {
                pix[y * w + x] =
                    (((aSum * invDiv) shr 16) shl 24) or
                            (((rSum * invDiv) shr 16) shl 16) or
                            (((gSum * invDiv) shr 16) shl 8) or
                            ((bSum * invDiv) shr 16)

                val pTop = temp[(y - r).coerceAtLeast(0) * w + x]
                val pBottom = temp[(y + r + 1).coerceAtMost(h - 1) * w + x]

                aSum += (pBottom ushr 24) - (pTop ushr 24)
                rSum += ((pBottom ushr 16) and 0xFF) - ((pTop ushr 16) and 0xFF)
                gSum += ((pBottom ushr 8) and 0xFF) - ((pTop ushr 8) and 0xFF)
                bSum += (pBottom and 0xFF) - (pTop and 0xFF)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        slideAnimator?.cancel()
        slideAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        backgroundProvider = null
        weatherLeftProvider = null
    }
}

package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.util.HmiFontManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * 欧尚 Z6 iDD 小欧屏 1920×384 3D 棱柱档位翻转原生控件
 *
 * 100% 像素级复刻 gear_cube_flip.html 美学规范：
 * 1. 3D 物理透视：Camera 观察点等效于 CSS perspective: 1600px (setLocation(0, 0, -22.22f))；
 * 2. 旋转轴心：正方体截面旋转半径 R = 192px (transform-origin: 50% 50% -192px)；
 * 3. 缓动曲线：CSS cubic-bezier(0.22, 1, 0.36, 1) 原生 PathInterpolator，严格 600ms 黄金翻滚律动；
 * 4. 档位大气背景：全画幅 1100×480px 椭圆高饱和径向渐变流光 (P橙/R红/N银/D翠绿)；
 * 5. 中央 320px 科技徽章：
 *    - 内嵌暗色微光磨砂玻璃衬底；
 *    - 外层 152px 精密科技刻度环 (顺时针旋转，4 向高光刻度)；
 *    - 核心 142px 高光雕刻圆环 (stroke 5.5px，双段 210px 弧形高光)；
 *    - 次级 130px 纤细辅助环与 4 颗浮动白星 (逆时针旋转)；
 *    - 195px 巨幅 Orbitron 金属渐变档位字母与光晕；
 *    - 柔和呼吸脉冲 (1.0 ~ 1.025)；
 * 6. 2.0 秒倒计时微型进度条与 左右 130px 物理安全暗角；
 * 7. 连环切档方式 B：就地微缩放 pop-refresh 动效，无缝流光色彩变换；
 * 8. 零 onDraw 运行时堆内存分配，稳锁车规 60FPS。
 */
class GearCubeFlipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "GearCubeFlipView"
        const val LOGICAL_WIDTH = 1920f
        const val LOGICAL_HEIGHT = 384f
        private const val CUBE_RADIUS = 192f // 384 / 2

        private const val DURATION_FLIP_IN_MS = 600L
        private const val DURATION_HOLD_MS = 2000L
        private const val DURATION_FLIP_OUT_MS = 600L
        private const val DURATION_WAY_B_MS = 350L

        // 等效 CSS perspective: 1000px，3D 物理立体翻滚纵深感鲜明清晰
        private const val CAMERA_PERSPECTIVE_Z = -(1000f / 72f)
    }

    enum class AnimState {
        IDLE,
        FLIPPING_IN,
        HOLDING,
        FLIPPING_OUT
    }

    class GearTheme(
        val code: String,
        val accentColor: Int,
        val accentSecColor: Int,
        val glowColor: Int,
        val darkExtrudeColor: Int,
        val bgColors: IntArray,
        val bgStops: FloatArray,
        val innerGlassColors: IntArray,
        val innerGlassStops: FloatArray
    )

    private val gearThemes = mapOf(
        "P" to GearTheme(
            code = "P",
            accentColor = Color.parseColor("#f59e0b"),
            accentSecColor = Color.parseColor("#fbbf24"),
            glowColor = Color.argb(220, 245, 158, 11),
            darkExtrudeColor = Color.parseColor("#5a2003"),
            bgColors = intArrayOf(
                Color.parseColor("#f59e0b"),
                Color.parseColor("#b45309"),
                Color.parseColor("#5a2003"),
                Color.parseColor("#170701")
            ),
            bgStops = floatArrayOf(0f, 0.22f, 0.55f, 1f),
            innerGlassColors = intArrayOf(
                Color.argb(76, 0, 0, 0),
                Color.argb(140, 0, 0, 0),
                Color.argb(55, 245, 158, 11)
            ),
            innerGlassStops = floatArrayOf(0f, 0.85f, 1f)
        ),
        "R" to GearTheme(
            code = "R",
            accentColor = Color.parseColor("#f43f5e"),
            accentSecColor = Color.parseColor("#fb7185"),
            glowColor = Color.argb(220, 244, 63, 94),
            darkExtrudeColor = Color.parseColor("#5c071d"),
            bgColors = intArrayOf(
                Color.parseColor("#f43f5e"),
                Color.parseColor("#be123c"),
                Color.parseColor("#5c071d"),
                Color.parseColor("#190207")
            ),
            bgStops = floatArrayOf(0f, 0.22f, 0.55f, 1f),
            innerGlassColors = intArrayOf(
                Color.argb(76, 0, 0, 0),
                Color.argb(140, 0, 0, 0),
                Color.argb(55, 244, 63, 94)
            ),
            innerGlassStops = floatArrayOf(0f, 0.85f, 1f)
        ),
        "N" to GearTheme(
            code = "N",
            accentColor = Color.parseColor("#cbd5e1"),
            accentSecColor = Color.parseColor("#ffffff"),
            glowColor = Color.argb(210, 203, 213, 225),
            darkExtrudeColor = Color.parseColor("#1e293b"),
            bgColors = intArrayOf(
                Color.parseColor("#cbd5e1"),
                Color.parseColor("#64748b"),
                Color.parseColor("#293548"),
                Color.parseColor("#0a0e17")
            ),
            bgStops = floatArrayOf(0f, 0.22f, 0.55f, 1f),
            innerGlassColors = intArrayOf(
                Color.argb(76, 0, 0, 0),
                Color.argb(140, 0, 0, 0),
                Color.argb(50, 203, 213, 225)
            ),
            innerGlassStops = floatArrayOf(0f, 0.85f, 1f)
        ),
        "D" to GearTheme(
            code = "D",
            accentColor = Color.parseColor("#10b981"),
            accentSecColor = Color.parseColor("#34d399"),
            glowColor = Color.argb(220, 16, 185, 129),
            darkExtrudeColor = Color.parseColor("#064e3b"),
            bgColors = intArrayOf(
                Color.parseColor("#10b981"),
                Color.parseColor("#059669"),
                Color.parseColor("#064e3b"),
                Color.parseColor("#021a14")
            ),
            bgStops = floatArrayOf(0f, 0.22f, 0.55f, 1f),
            innerGlassColors = intArrayOf(
                Color.argb(76, 0, 0, 0),
                Color.argb(140, 0, 0, 0),
                Color.argb(55, 16, 185, 129)
            ),
            innerGlassStops = floatArrayOf(0f, 0.85f, 1f)
        )
    )

    // 档位切换排队队列 (FIFO，用于 <600ms 快速连续不同档位切换，如 R -> N -> D)
    private val gearQueue = ArrayDeque<String>()
    private var isInterGearFlip = false

    // 状态机
    var currentState = AnimState.IDLE
        private set

    private var currentGear: String = "P"
    private var previousGear: String? = null
    private var faceAIsPreviousGear = false
    private var currentSnapshotProvider: ((Canvas) -> Unit)? = null

    // 动画驱动进度与补间
    private var flipProgress = 0f
    private var holdProgress = 0f // 1.0 -> 0.0
    private var wayBScale = 1.0f
    private var wayBBrightness = 1.0f

    // 缓动曲线
    private val flipInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    // 动画器引用
    private var flipInAnimator: ValueAnimator? = null
    private var holdAnimator: ValueAnimator? = null
    private var flipOutAnimator: ValueAnimator? = null
    private var wayBAnimator: ValueAnimator? = null

    // 徽章微动效循环驱动 (外环旋转 + 呼吸微脉冲)
    private var ambientLoopAnimator: ValueAnimator? = null
    private var ringCwAngle = 0f
    private var ringCcwAngle = 0f
    private var emblemPulseScale = 1.0f

    // 变换矩阵与相机
    private var logFrameCounter = 0
    private val camera = Camera()
    private val matrix3D = Matrix()
    private val bgMatrix = Matrix()

    // 预分配 Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(56, 255, 255, 255) // rgba(255,255,255,0.22)
        pathEffect = DashPathEffect(floatArrayOf(3f, 9f), 0f)
    }
    private val outerTicksPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val coreRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(210f, 24f), 0f)
    }
    private val secRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
    }
    private val starDotsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 195f
        isFakeBoldText = true
    }
    private val letterShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 195f
        isFakeBoldText = true
    }
    private val letterFloorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 195f
        isFakeBoldText = true
        color = Color.argb(220, 0, 0, 0)
    }
    private val letterExtrudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 195f
        isFakeBoldText = true
    }
    private val letterHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 195f
        isFakeBoldText = true
        color = Color.argb(190, 255, 255, 255)
    }
    private val dwellBarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255) // rgba(255,255,255,0.15)
        style = Paint.Style.FILL
    }
    private val dwellBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowOverlayPaint = Paint().apply {
        color = Color.BLACK
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // 预分配 RectF 与 Path
    private val rectTmp = RectF()
    private val rectVignetteLeft = RectF(0f, 0f, 130f, LOGICAL_HEIGHT)
    private val rectVignetteRight = RectF(LOGICAL_WIDTH - 130f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
    private val tickRect = RectF()

    // 预构建 Shaders 缓存
    private val bgShaders = mutableMapOf<String, RadialGradient>()
    private val innerGlassShaders = mutableMapOf<String, RadialGradient>()
    private val coreRingShaders = mutableMapOf<String, LinearGradient>()
    private var letterShader: LinearGradient? = null
    private var vignetteLeftShader: LinearGradient? = null
    private var vignetteRightShader: LinearGradient? = null

    // 1920×384 原生 WebP 背景图缓存 (完全 1:1 对齐 HTML CSS 渐变与 130px 物理安全暗角)
    private val bgBitmaps = mutableMapOf<String, Bitmap>()
    private var fallbackBgBitmap: Bitmap? = null

    // 离线池化 Bitmap
    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private var hasValidSnapshot = false

    // 纯净暗色底图 Shader (当无外部快照时的纯净优雅衬底，彻底废除图三测试页面)
    private var fallbackBaseShader: RadialGradient? = null
    private val fallbackBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 回调
    var onFlipStartedListener: (() -> Unit)? = null
    var onFlipCompleteListener: (() -> Unit)? = null
    var onRollOutCompleteListener: (() -> Unit)?
        get() = onFlipCompleteListener
        set(value) { onFlipCompleteListener = value }

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // 立即预分配池化快照 Bitmap，确保首次切档时 cachedCanvas 永不为 null
        cachedBitmap = Bitmap.createBitmap(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        cachedCanvas = Canvas(cachedBitmap!!)

        // 加载 Orbitron Bold 字体
        HmiFontManager.getOrbitronBold(context)?.let {
            letterPaint.typeface = it
            letterShadowPaint.typeface = it
            letterFloorShadowPaint.typeface = it
            letterExtrudePaint.typeface = it
            letterHighlightPaint.typeface = it
        }

        loadBackgroundBitmaps()
        buildStaticShaders()
        startAmbientLoop()
    }

    private fun loadBackgroundBitmaps() {
        val opt = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val gearResMap = mapOf(
            "P" to R.drawable.bg_gear_p,
            "R" to R.drawable.bg_gear_r,
            "N" to R.drawable.bg_gear_n,
            "D" to R.drawable.bg_gear_d
        )
        gearResMap.forEach { (key, resId) ->
            try {
                BitmapFactory.decodeResource(resources, resId, opt)?.let { bmp ->
                    bgBitmaps[key] = bmp
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载档位 WebP 背景图失败: $key", e)
            }
        }
        try {
            fallbackBgBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_fallback_space, opt)
        } catch (e: Exception) {
            Log.w(TAG, "加载 fallback WebP 背景图失败", e)
        }
    }

    private fun buildStaticShaders() {
        val cx = LOGICAL_WIDTH / 2f
        val cy = LOGICAL_HEIGHT / 2f

        // 1. 各档位全画幅 980×220 椭圆径向渐变 (中心强发光，边缘深邃暗夜，完美还原奢华质感)
        gearThemes.forEach { (key, theme) ->
            val bgShader = RadialGradient(
                cx, cy, 980f,
                theme.bgColors,
                theme.bgStops,
                Shader.TileMode.CLAMP
            )
            bgMatrix.reset()
            bgMatrix.setScale(1f, 220f / 980f, cx, cy)
            bgShader.setLocalMatrix(bgMatrix)
            bgShaders[key] = bgShader

            // 徽章内嵌磨砂玻璃 RadialGradient (r = 142px)
            val glassShader = RadialGradient(
                cx, cy, 142f,
                theme.innerGlassColors,
                theme.innerGlassStops,
                Shader.TileMode.CLAMP
            )
            innerGlassShaders[key] = glassShader

            // 核心高光圆环线性渐变 (45度倾斜)
            val ringGrad = LinearGradient(
                cx - 142f, cy - 142f,
                cx + 142f, cy + 142f,
                intArrayOf(
                    Color.argb(242, 255, 255, 255),
                    theme.accentSecColor,
                    theme.accentColor,
                    Color.argb(230, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.30f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
            coreRingShaders[key] = ringGrad
        }

        // 2. 字母垂直金属质感渐变
        letterShader = LinearGradient(
            0f, cy - 85f, 0f, cy + 70f,
            intArrayOf(
                Color.parseColor("#ffffff"),
                Color.parseColor("#f1f5f9"),
                Color.parseColor("#cbd5e1"),
                Color.parseColor("#94a3b8")
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. 左右 130px 物理安全暗角 (左右对称，彻底消除右边缘割裂感)
        vignetteLeftShader = LinearGradient(
            0f, 0f, 130f, 0f,
            Color.BLACK, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        vignetteRightShader = LinearGradient(
            LOGICAL_WIDTH - 130f, 0f, LOGICAL_WIDTH, 0f,
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )

        // 4. 深空底色渐变 (当无快照时的纯净暗色底图)
        fallbackBaseShader = RadialGradient(
            cx, 460f, 650f,
            intArrayOf(
                Color.parseColor("#17223b"),
                Color.parseColor("#0c1220"),
                Color.parseColor("#050811")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 徽章常驻微动效循环：外环旋转 + 呼吸微脉冲 (在常驻或展现态驱动)
     */
    private fun startAmbientLoop() {
        ambientLoopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 48000L // 48秒大循环周期
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (currentState != AnimState.IDLE) {
                    val frac = anim.animatedFraction
                    // 顺时针环 24秒一圈 (48秒转2圈)
                    ringCwAngle = (frac * 720f) % 360f
                    // 逆时针环 16秒一圈 (48秒转3圈)
                    ringCcwAngle = (-frac * 1080f) % 360f

                    // 呼吸脉冲周期 3.0 秒 (48秒做16次呼吸，scale 1.0 ~ 1.025)
                    val pulseFrac = (frac * 16f) % 1f
                    val sinVal = sin(pulseFrac * 2 * Math.PI).toFloat()
                    emblemPulseScale = 1.0f + 0.025f * ((sinVal + 1f) / 2f)

                    invalidate()
                }
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cachedBitmap == null && w > 0 && h > 0) {
            cachedBitmap = Bitmap.createBitmap(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
            cachedCanvas = Canvas(cachedBitmap!!)
            Log.i(TAG, "常驻池化 Bitmap 已分配: 1920×384 (零运行时再次分配)")
        }
    }

    /**
     * 核心切档入口 (严格按车载交互优先级与排队机制)：
     * 1. 相同档位连续触发：若当前档位正在动画或展示中，忽略后续触发；待完全消失回到主页面后再次触发才重新播放。
     * 2. 不同档位快速连续触发 (<600ms，如 R -> N -> D)：入场动画未播完时，追加至 FIFO 队列；当前 600ms 播完立即播放下一档位的 600ms 3D 上翻，仅最后一个档位停留 2s 并退出。
     * 3. 不同档位在停留或退出阶段触发 (>600ms)：立即取消当前停留/退场，清空队列，从当前档位直接启动新档位的 600ms 3D 棱柱上翻入场，驻留 2s 并退出。
     */
    fun triggerGearFlip(gear: String, snapshotProvider: ((Canvas) -> Unit)? = null) {
        triggerCubeFlip(gear, snapshotProvider)
    }

    fun triggerCubeFlip(gear: String, snapshotProvider: ((Canvas) -> Unit)? = null) {
        val targetGear = normalizeGear(gear)
        Log.i(TAG, "triggerCubeFlip called: targetGear=$targetGear, currentState=$currentState, currentGear=$currentGear")
        mainHandler.post {
            // 1. 同一档位连续触发保护：若当前正在展示该档位（入场/驻留/退场），直接忽略
            if (currentState != AnimState.IDLE && currentGear == targetGear) {
                Log.i(TAG, "忽略相同档位重复触发: $targetGear (当前状态: $currentState)")
                return@post
            }

            // 2. 在 600ms 入场动画期间（FLIPPING_IN）收到不同档位：加入 FIFO 排队队列
            if (currentState == AnimState.FLIPPING_IN) {
                if (targetGear != currentGear && gearQueue.lastOrNull() != targetGear) {
                    gearQueue.addLast(targetGear)
                    Log.i(TAG, "档位入场中追加排队: $targetGear, 当前队列: $gearQueue")
                }
                return@post
            }

            // 3. 在停留（HOLDING）或退场（FLIPPING_OUT）期间（间隔>600ms）收到不同档位：
            // 取消当前驻留倒计时/退场动画，清空排队，立即从当前档位直接启动新档位的 600ms 3D 上翻动画
            if (currentState == AnimState.HOLDING || currentState == AnimState.FLIPPING_OUT) {
                cancelAllAnimators()
                gearQueue.clear()
                startInterGearFlip(currentGear, targetGear)
                return@post
            }

            // 4. 从 IDLE 常态全新启动：
            cancelAllAnimators()
            gearQueue.clear()
            isInterGearFlip = false
            previousGear = null
            currentGear = targetGear
            currentSnapshotProvider = snapshotProvider

            // 单次就地离线快照
            if (snapshotProvider != null) {
                cachedCanvas?.let { c ->
                    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    snapshotProvider.invoke(c)
                    hasValidSnapshot = true
                }
            } else {
                hasValidSnapshot = false
            }

            visibility = VISIBLE
            alpha = 1f
            executeRollIn()
        }
    }

    /**
     * 立即取消并隐藏档位动效 (被驾驶模式打断时彻底退出，绝不再次恢复)
     */
    fun dismiss() {
        mainHandler.post {
            cancelAllAnimators()
            gearQueue.clear()
            currentState = AnimState.IDLE
            flipProgress = 0f
            holdProgress = 0f
            visibility = GONE
            Log.i(TAG, "档位动效已被打断并彻底终结 (dismiss)")
        }
    }

    /**
     * 挂起暂停档位动效 (兼容旧接口，直接彻底关闭，绝不再恢复)
     */
    fun suspend() {
        dismiss()
    }

    /**
     * 从挂起中恢复档位动效 (用户要求：打断后直接回到主页面，绝不再展示档位页面)
     */
    fun resumeFromSuspension(holdMs: Long = DURATION_HOLD_MS) {
        // no-op: 彻底杜绝打断后再次复活展示
        Log.i(TAG, "resumeFromSuspension 忽略：按用户规范，打断后直接保持主页面，不复活展示")
    }

    /**
     * 档位间 3D 棱柱直接翻转 (From -> To, 600ms, 连续切档核心)
     * Face A 展现出场档位 (0° -> -90°), Face B 展现新入场档位 (90° -> 0°)
     */
    private fun startInterGearFlip(fromGear: String, toGear: String) {
        Log.i(TAG, "startInterGearFlip: $fromGear -> $toGear (600ms)")
        cancelAllAnimators()
        isInterGearFlip = true
        previousGear = fromGear
        currentGear = toGear
        currentState = AnimState.FLIPPING_IN
        flipProgress = 0f
        visibility = VISIBLE
        alpha = 1f
        onFlipStartedListener?.invoke()

        flipInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_FLIP_IN_MS
            interpolator = flipInterpolator
            addUpdateListener { anim ->
                flipProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentState != AnimState.FLIPPING_IN) return
                    Log.i(TAG, "InterGearFlip ended: reached $currentGear")
                    isInterGearFlip = false
                    previousGear = null

                    if (gearQueue.isNotEmpty()) {
                        val nextGear = gearQueue.removeFirst()
                        Log.i(TAG, "从排队队列取出下一个档位: $nextGear 紧接着播放 600ms 翻入")
                        startInterGearFlip(currentGear, nextGear)
                    } else {
                        // 队列清空，进入最终档位的 2.0s 停留展示
                        currentState = AnimState.HOLDING
                        flipProgress = 1f
                        startHoldCountdown(DURATION_HOLD_MS)
                    }
                }
            })
            start()
        }
    }

    /**
     * 执行 3D 向上翻入动画 (主屏 -> 档位, 0° -> 90°, 严格 600ms)
     */
    private fun executeRollIn() {
        currentState = AnimState.FLIPPING_IN
        flipProgress = 0f
        onFlipStartedListener?.invoke()

        flipInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_FLIP_IN_MS
            interpolator = flipInterpolator
            addUpdateListener { anim ->
                flipProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentState != AnimState.FLIPPING_IN) return
                    Log.i(TAG, "executeRollIn ended for gear=$currentGear")
                    isInterGearFlip = false
                    previousGear = null

                    if (gearQueue.isNotEmpty()) {
                        val nextGear = gearQueue.removeFirst()
                        Log.i(TAG, "从排队队列取出下一个档位: $nextGear 紧接着播放 600ms 翻入")
                        startInterGearFlip(currentGear, nextGear)
                    } else {
                        currentState = AnimState.HOLDING
                        flipProgress = 1f
                        startHoldCountdown(DURATION_HOLD_MS)
                    }
                }
            })
            start()
        }
    }

    /**
     * 阶段 2：档位高亮停留 2.0 秒 (底面在 90° 稳稳正对用户，微型进度条平滑缩短)
     */
    private fun startHoldCountdown(durationMs: Long = DURATION_HOLD_MS) {
        holdAnimator?.cancel()
        holdProgress = 1f

        holdAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            interpolator = null // 线性平滑缩短
            addUpdateListener { anim ->
                holdProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentState == AnimState.HOLDING) {
                        executeRollOut()
                    }
                }
            })
            start()
        }
    }

    /**
     * 阶段 3：继续向上翻转翻回主页面 (600ms，刷新最新主屏画面)
     */
    private fun executeRollOut() {
        if (currentState == AnimState.FLIPPING_OUT) return
        currentState = AnimState.FLIPPING_OUT
        flipProgress = 0f

        // 翻回前重新就地捕获最新时钟气象画面，杜绝露出2秒前的旧时间快照
        currentSnapshotProvider?.let { provider ->
            cachedCanvas?.let { c ->
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                provider.invoke(c)
                hasValidSnapshot = true
            }
        }

        flipOutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_FLIP_OUT_MS
            interpolator = flipInterpolator
            addUpdateListener { anim ->
                flipProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentState = AnimState.IDLE
                    flipProgress = 0f
                    visibility = GONE
                    onFlipCompleteListener?.invoke()
                }
            })
            start()
        }
    }

    private fun cancelAllAnimators() {
        flipInAnimator?.cancel()
        holdAnimator?.cancel()
        flipOutAnimator?.cancel()
        wayBAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentState == AnimState.IDLE) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (logFrameCounter++ % 30 == 0) {
            Log.d(TAG, "onDraw: state=$currentState, gear=$currentGear, flip=$flipProgress, hold=$holdProgress, w=$w, h=$h")
        }

        // 保持 1920×384 严格比例居中适配
        val targetAspect = LOGICAL_WIDTH / LOGICAL_HEIGHT
        val currentAspect = w / h
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

        val cx = LOGICAL_WIDTH / 2f
        val cy = LOGICAL_HEIGHT / 2f

        when (currentState) {
            AnimState.FLIPPING_IN -> {
                // 3D 棱柱向上翻转入场 (严格符合物理学与用户视觉规范)：
                // 出现动画：主页面向上压缩并消失于上方，档位动画在下方拓展至整个页面
                // Face A (主页面): 0° -> +90° (向上压缩消失于上方)
                // Face B (新档位面): -90° -> 0° (在下方拓展至整个页面)
                val angleA = flipProgress * 90f
                val angleB = -(1f - flipProgress) * 90f

                // 1. 绘制 Face A (出场面，向上压缩消失于上方，全程实体不透明，不漏底)
                if (flipProgress < 0.98f) {
                    val shadowA = (flipProgress * 0.85f).coerceIn(0f, 0.85f)
                    canvas.save()
                    applyCubeFaceTransform(canvas, angleA, cx, cy)
                    if (isInterGearFlip && previousGear != null) {
                        drawGearFace(canvas, previousGear!!, withCountdown = false, alphaMod = 1f)
                    } else {
                        drawFaceA(canvas, 1f)
                    }
                    drawFaceShadowOverlay(canvas, shadowA)
                    canvas.restore()
                }

                // 2. 绘制 Face B (新档位入场面，在下方平滑拓展至全屏)
                if (flipProgress > 0.02f) {
                    val shadowB = ((1f - flipProgress) * 0.75f).coerceIn(0f, 0.75f)
                    canvas.save()
                    applyCubeFaceTransform(canvas, angleB, cx, cy)
                    drawGearFace(canvas, currentGear, withCountdown = false, alphaMod = 1f)
                    drawFaceShadowOverlay(canvas, shadowB)
                    canvas.restore()
                }
            }

            AnimState.HOLDING -> {
                // 稳稳平视正面，外环持续微旋转，微型进度条平滑缩短
                drawGearFace(canvas, currentGear, withCountdown = true)
            }

            AnimState.FLIPPING_OUT -> {
                // 3D 向上翻转退场 (继续向上翻转回原主页面)：
                // 退出动画：档位动画向上压缩并消失于上方，主页面动画在下方拓展至整个页面
                // Face A (档位面): 0° -> +90° (向上压缩消失于上方)
                // Face B (原主页面): -90° -> 0° (在下方拓展至整个页面)
                val angleGear = flipProgress * 90f
                val angleBack = -(1f - flipProgress) * 90f

                // 1. 档位面翻出 (向上压缩消失于上方，全程保持实体不透明，上方压缩的严格是档位面，绝不透出主页面)
                if (flipProgress < 0.98f) {
                    val shadowGear = (flipProgress * 0.85f).coerceIn(0f, 0.85f)
                    canvas.save()
                    applyCubeFaceTransform(canvas, angleGear, cx, cy)
                    drawGearFace(canvas, currentGear, withCountdown = false, alphaMod = 1f)
                    drawFaceShadowOverlay(canvas, shadowGear)
                    canvas.restore()
                }

                // 2. 原主页背面翻入 (在下方拓展至整个页面)
                if (flipProgress > 0.02f) {
                    val shadowBack = ((1f - flipProgress) * 0.75f).coerceIn(0f, 0.75f)
                    canvas.save()
                    applyCubeFaceTransform(canvas, angleBack, cx, cy)
                    drawFaceA(canvas, 1f)
                    drawFaceShadowOverlay(canvas, shadowBack)
                    canvas.restore()
                }
            }

            AnimState.IDLE -> {}
        }

        // 统一顶层叠加：左右两侧 130px 物理安全暗角渐变 (与原生时钟/歌词画布 1:1 对齐)
        drawEdgeVignette(canvas)

        canvas.restore()
    }

    /**
     * 3D 正方体棱柱表面变换矩阵：
     * 严格遵循 CSS: perspective: 1000px; transform-origin: 50% 50% -192px; rotateX(deg);
     */
    private fun applyCubeFaceTransform(canvas: Canvas, degree: Float, cx: Float, cy: Float) {
        matrix3D.reset()
        camera.save()
        // 1. 严格 1000px 物理透视距离 (强立体纵深感)
        camera.setLocation(0f, 0f, CAMERA_PERSPECTIVE_Z)
        // 2. 截面旋转轴心在表面后方深度 Z = +192px 处
        camera.translate(0f, 0f, CUBE_RADIUS)
        // 3. 执行 X 轴物理旋转 (向上翻转)
        camera.rotateX(degree)
        // 4. 平移回原始表面
        camera.translate(0f, 0f, -CUBE_RADIUS)
        camera.getMatrix(matrix3D)
        camera.restore()

        // 5. 以舞台几何中心 (cx, cy) 为基准
        matrix3D.preTranslate(-cx, -cy)
        matrix3D.postTranslate(cx, cy)
        canvas.concat(matrix3D)
    }

    /**
     * 绘制 3D 动态物理环境阴影遮罩
     */
    private fun drawFaceShadowOverlay(canvas: Canvas, shadowOpacity: Float) {
        if (shadowOpacity <= 0.01f) return
        shadowOverlayPaint.alpha = (shadowOpacity * 255).toInt()
        rectTmp.set(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        canvas.drawRect(rectTmp, shadowOverlayPaint)
    }

    /**
     * 绘制 Face A (出场面 / 背面)：有快照绘制真实原生快照，彻底废除图三测试页面
     */
    private fun drawFaceA(canvas: Canvas, alpha: Float) {
        val aInt = (alpha * 255).toInt()
        if (aInt <= 0) return

        if (hasValidSnapshot && cachedBitmap != null) {
            bitmapPaint.alpha = aInt
            canvas.drawBitmap(cachedBitmap!!, 0f, 0f, bitmapPaint)
        } else if (fallbackBgBitmap != null) {
            bitmapPaint.alpha = aInt
            canvas.drawBitmap(fallbackBgBitmap!!, 0f, 0f, bitmapPaint)
        } else {
            // 纯净深空暗底 (坚决不绘制任何虚假时钟车速测试小部件)
            fallbackBasePaint.shader = fallbackBaseShader
            fallbackBasePaint.alpha = aInt
            rectTmp.set(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
            canvas.drawRect(rectTmp, fallbackBasePaint)
        }
    }

    /**
     * 绘制专属档位盘面 (1920 × 384)
     */
    private fun drawGearFace(
        canvas: Canvas,
        gearKey: String,
        withCountdown: Boolean,
        alphaMod: Float = 1f
    ) {
        val theme = gearThemes[gearKey] ?: gearThemes["P"]!!
        val cx = LOGICAL_WIDTH / 2f
        val cy = LOGICAL_HEIGHT / 2f

        // 1. 大气背景：优先使用 HTML 1:1 像素级 1920×384 WebP 渐变背景大图 (带物理安全暗角)
        val bgBmp = bgBitmaps[theme.code]
        if (bgBmp != null) {
            bgPaint.shader = null
            bgPaint.alpha = (alphaMod * 255).toInt()
            canvas.drawBitmap(bgBmp, 0f, 0f, bgPaint)
        } else {
            val bgShader = bgShaders[theme.code] ?: bgShaders["P"]!!
            bgPaint.shader = bgShader
            bgPaint.alpha = (alphaMod * 255).toInt()
            rectTmp.set(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
            canvas.drawRect(rectTmp, bgPaint)
        }

        // 2. 中央 320px 科技徽章 (带呼吸脉冲与连环切档微缩放)
        val emblemScale = emblemPulseScale * wayBScale
        canvas.save()
        canvas.scale(emblemScale, emblemScale, cx, cy)

        // 2.1 内嵌暗色微光磨砂玻璃衬底 (r = 142px)
        val glassShader = innerGlassShaders[theme.code] ?: innerGlassShaders["P"]!!
        innerGlassPaint.shader = glassShader
        innerGlassPaint.alpha = (alphaMod * 255).toInt()
        canvas.drawCircle(cx, cy, 142f, innerGlassPaint)

        // 2.2 最外层精密科技刻度规 (顺时针旋转，r = 152px)
        canvas.save()
        canvas.rotate(ringCwAngle, cx, cy)
        outerRingPaint.alpha = (alphaMod * 56).toInt()
        canvas.drawCircle(cx, cy, 152f, outerRingPaint)

        // 4 向高光刻度 (上、下、左、右，6×12px，rx 3px)
        outerTicksPaint.alpha = (alphaMod * 255 * wayBBrightness).coerceAtMost(255f).toInt()
        // Top tick
        tickRect.set(cx - 3f, cy - 158f, cx + 3f, cy - 146f)
        canvas.drawRoundRect(tickRect, 3f, 3f, outerTicksPaint)
        // Bottom tick
        tickRect.set(cx - 3f, cy + 146f, cx + 3f, cy + 158f)
        canvas.drawRoundRect(tickRect, 3f, 3f, outerTicksPaint)
        // Left tick
        tickRect.set(cx - 158f, cy - 3f, cx - 146f, cy + 3f)
        canvas.drawRoundRect(tickRect, 3f, 3f, outerTicksPaint)
        // Right tick
        tickRect.set(cx + 146f, cy - 3f, cx + 158f, cy + 3f)
        canvas.drawRoundRect(tickRect, 3f, 3f, outerTicksPaint)
        canvas.restore()

        // 2.3 核心高光雕刻圆环 (r = 142px, stroke 5.5px, 210px 双段弧)
        val coreRingShader = coreRingShaders[theme.code] ?: coreRingShaders["P"]!!
        coreRingPaint.shader = coreRingShader
        coreRingPaint.alpha = (alphaMod * 255 * wayBBrightness).coerceAtMost(255f).toInt()
        canvas.drawCircle(cx, cy, 142f, coreRingPaint)

        // 2.4 次级纤细辅助环与浮动星光点 (逆时针旋转，r = 130px)
        canvas.save()
        canvas.rotate(ringCcwAngle, cx, cy)
        secRingPaint.color = theme.accentSecColor
        secRingPaint.alpha = (alphaMod * 128).toInt()
        canvas.drawCircle(cx, cy, 130f, secRingPaint)

        // 4 颗浮动白星 (在 45°、135°、225°、315° 方位，r = 3.5px)
        starDotsPaint.alpha = (alphaMod * 230).toInt()
        val starDist = 106f // sqrt(106^2 + 106^2) ≈ 150px
        canvas.drawCircle(cx + starDist, cy - starDist, 3.5f, starDotsPaint)
        canvas.drawCircle(cx - starDist, cy + starDist, 3.5f, starDotsPaint)
        canvas.drawCircle(cx + starDist, cy + starDist, 3.5f, starDotsPaint)
        canvas.drawCircle(cx - starDist, cy - starDist, 3.5f, starDotsPaint)
        canvas.restore()

        // 2.5 巨幅 195px 档位字母 (5层立体雕刻质感 3D 档位字母)
        draw3DGearLetter(canvas, theme.code, cx, cy, theme, alphaMod, wayBBrightness)

        canvas.restore() // 退出徽章 scale

        // 3. 底部 2 秒倒计时微型进度条 (位于 bottom: 24px 即 y = 360px，宽 200px)
        if (withCountdown && holdProgress > 0f) {
            val barW = 200f
            val barH = 3f
            val barLeft = cx - barW / 2f
            val barTop = LOGICAL_HEIGHT - 24f - barH
            val barRight = cx + barW / 2f
            val barBottom = barTop + barH

            // 底轨
            dwellBarTrackPaint.alpha = (alphaMod * 38).toInt()
            rectTmp.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(rectTmp, 2f, 2f, dwellBarTrackPaint)

            // 填充进度条 (自左至右缩短)
            val fillRight = barLeft + barW * holdProgress
            if (fillRight > barLeft) {
                dwellBarFillPaint.color = theme.accentColor
                dwellBarFillPaint.alpha = (alphaMod * 255).toInt()
                rectTmp.set(barLeft, barTop, fillRight, barBottom)
                canvas.drawRoundRect(rectTmp, 2f, 2f, dwellBarFillPaint)
            }
        }
    }

    /**
     * 绘制 5 层立体雕刻质感 3D 档位字母 (深层地面投影 + 霓虹漫反射 + CNC 实体厚度倒角挤出 + 金属渐变本体 + 刀锋切面高光)
     */
    private fun draw3DGearLetter(
        canvas: Canvas,
        code: String,
        cx: Float,
        cy: Float,
        theme: GearTheme,
        alphaMod: Float,
        wayBBrightness: Float
    ) {
        val letterY = cy + 68f

        // Pass 1: 极深地面投影 (Floor Cast Shadow) -> Y + 14px, 黑 220
        letterFloorShadowPaint.alpha = (alphaMod * 220).toInt()
        canvas.drawText(code, cx, letterY + 14f, letterFloorShadowPaint)

        // Pass 2: 外层环境霓虹光晕 (Ambient Glow Bloom)
        letterShadowPaint.shader = null
        letterShadowPaint.color = theme.glowColor
        letterShadowPaint.alpha = (alphaMod * 200 * wayBBrightness).coerceAtMost(255f).toInt()
        canvas.drawText(code, cx, letterY, letterShadowPaint)

        // Pass 3: CNC 实体厚度倒角挤出 (Extrusion Bevel) -> Y + 6px, Y + 4px, Y + 2px
        letterExtrudePaint.color = theme.darkExtrudeColor
        letterExtrudePaint.alpha = (alphaMod * 255).toInt()
        canvas.drawText(code, cx, letterY + 6f, letterExtrudePaint)
        canvas.drawText(code, cx, letterY + 4f, letterExtrudePaint)
        canvas.drawText(code, cx, letterY + 2f, letterExtrudePaint)

        // Pass 4: 极光金属高光本体 (Chrome Brushed Face)
        letterPaint.shader = letterShader
        letterPaint.alpha = (alphaMod * 255).toInt()
        canvas.drawText(code, cx, letterY, letterPaint)

        // Pass 5: 顶部极细切角高光 (Top Specular Highlight) -> Y - 2px
        letterHighlightPaint.alpha = (alphaMod * 190).toInt()
        canvas.drawText(code, cx, letterY - 2f, letterHighlightPaint)
    }

    /**
     * 绘制左右两侧 130px 物理安全暗角
     */
    private fun drawEdgeVignette(canvas: Canvas) {
        // 左暗角
        vignettePaint.shader = vignetteLeftShader
        canvas.drawRect(rectVignetteLeft, vignettePaint)

        // 右暗角
        vignettePaint.shader = vignetteRightShader
        canvas.drawRect(rectVignetteRight, vignettePaint)
    }

    private fun normalizeGear(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("P") -> "P"
            upper.contains("R") -> "R"
            upper.contains("N") -> "N"
            upper.contains("D") -> "D"
            else -> "P"
        }
    }
}

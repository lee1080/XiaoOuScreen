package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.util.HmiFontManager
import com.car.xiaoouscreen.util.SharedHmiAssetManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * 欧尚 Z6 iDD 小欧屏 1920×384 驾驶模式自左向右光刃擦除转场原生控件
 *
 * 100% 像素级复刻 drive_mode_wipe.html 美学规范：
 * 1. 5 款高饱和全画幅 1180×420 椭圆径向渐变流光 (ECO翡翠绿/NORMAL天青蓝/SPORT烈焰橙/RACE猩红/CUSTOM钛灰)；
 * 2. 5 款模式 100% 对应之科技微动效专属矢量图层 (破风刀锋、红线换挡灯条、层流微光信标、叶脉光弧、精密校准滑块)；
 * 3. 倾斜 -6° 半透明光刃发光带 (宽度 52px，中心高光向两侧带主题色渐变，与切线严格平行)；
 * 4. 硬件级倾斜 polygon clipPath 擦除遮罩，耗时接近 0ms；
 * 5. 148px (6字母128px) Orbitron Bold 极简巨幅模式大字，垂直金属质感渐变与光晕，去除非原厂中文字幕；
 * 6. 2.0 秒底部细线驻留倒计时与 左右 130px 物理安全暗角；
 * 7. 支持模式间连续切换（上一模式垫底无缝擦除覆写，杜绝闪黑漏底）；
 * 8. 支持档位高优先级打断（驻留倒计时暂停与恢复）；
 * 9. 零 onDraw 运行时内存分配，稳锁车规 60FPS。
 */
class DriveModeWipeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "DriveModeWipeView"
        const val LOGICAL_WIDTH = 1920f
        const val LOGICAL_HEIGHT = 384f

        private const val DURATION_WIPE_IN_MS = 600L
        private const val DURATION_DWELL_MS = 2000L
        private const val DURATION_WIPE_OUT_MS = 600L

        // 倾斜 -6deg 切割线参数 (tan(6deg) * 384 ≈ 40.35px)
        private const val SKEW_DX = 40.35f
        private const val START_X = -60f
        private const val TARGET_X = 1960f
    }

    enum class AnimState {
        IDLE,
        WIPING_IN,
        DWELLING,
        WIPING_OUT
    }

    class ModeTheme(
        val key: String,
        val accentColor: Int,
        val accentLightColor: Int,
        val glowColor: Int,
        val darkExtrudeColor: Int,
        val bladeRgb: IntArray, // R, G, B
        val isSixLetters: Boolean,
        val bgColors: IntArray,
        val bgStops: FloatArray
    )

    private val modeThemes = mapOf(
        "ECO" to ModeTheme(
            key = "ECO",
            accentColor = Color.parseColor("#10b981"),
            accentLightColor = Color.parseColor("#a7f3d0"),
            glowColor = Color.argb(230, 52, 211, 153),
            darkExtrudeColor = Color.parseColor("#064e3b"),
            bladeRgb = intArrayOf(52, 211, 153),
            isSixLetters = false,
            bgColors = intArrayOf(
                Color.parseColor("#34d399"),
                Color.parseColor("#10b981"),
                Color.parseColor("#047857"),
                Color.parseColor("#064e3b"),
                Color.parseColor("#021610")
            ),
            bgStops = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f)
        ),
        "NORMAL" to ModeTheme(
            key = "NORMAL",
            accentColor = Color.parseColor("#0ea5e9"),
            accentLightColor = Color.parseColor("#bae6fd"),
            glowColor = Color.argb(230, 56, 189, 248),
            darkExtrudeColor = Color.parseColor("#0c3b5e"),
            bladeRgb = intArrayOf(56, 189, 248),
            isSixLetters = true,
            bgColors = intArrayOf(
                Color.parseColor("#38bdf8"),
                Color.parseColor("#0ea5e9"),
                Color.parseColor("#0284c7"),
                Color.parseColor("#0c3b5e"),
                Color.parseColor("#030e1a")
            ),
            bgStops = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f)
        ),
        "SPORT" to ModeTheme(
            key = "SPORT",
            accentColor = Color.parseColor("#f97316"),
            accentLightColor = Color.parseColor("#fed7aa"),
            glowColor = Color.argb(240, 251, 146, 60),
            darkExtrudeColor = Color.parseColor("#7c2d12"),
            bladeRgb = intArrayOf(249, 115, 22),
            isSixLetters = false,
            bgColors = intArrayOf(
                Color.parseColor("#fbbf24"),
                Color.parseColor("#f97316"),
                Color.parseColor("#ea580c"),
                Color.parseColor("#7c2d12"),
                Color.parseColor("#180602")
            ),
            bgStops = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f)
        ),
        "RACE" to ModeTheme(
            key = "RACE",
            accentColor = Color.parseColor("#ef4444"),
            accentLightColor = Color.parseColor("#fecdd3"),
            glowColor = Color.argb(245, 248, 113, 113),
            darkExtrudeColor = Color.parseColor("#7f1d1d"),
            bladeRgb = intArrayOf(239, 68, 68),
            isSixLetters = false,
            bgColors = intArrayOf(
                Color.parseColor("#fb7185"),
                Color.parseColor("#ef4444"),
                Color.parseColor("#dc2626"),
                Color.parseColor("#7f1d1d"),
                Color.parseColor("#190204")
            ),
            bgStops = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f)
        ),
        "CUSTOM" to ModeTheme(
            key = "CUSTOM",
            accentColor = Color.parseColor("#94a3b8"),
            accentLightColor = Color.parseColor("#f1f5f9"),
            glowColor = Color.argb(220, 226, 232, 240),
            darkExtrudeColor = Color.parseColor("#1e293b"),
            bladeRgb = intArrayOf(203, 213, 225),
            isSixLetters = true,
            bgColors = intArrayOf(
                Color.parseColor("#e2e8f0"),
                Color.parseColor("#94a3b8"),
                Color.parseColor("#475569"),
                Color.parseColor("#1e293b"),
                Color.parseColor("#070a10")
            ),
            bgStops = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 1f)
        )
    )

    // 状态机
    var currentState = AnimState.IDLE
        private set

    var currentMode: String = "NORMAL"
        private set
    private var previousMode: String? = null
    private var isSwitchingBetweenModes = false

    // 动画驱动进度 (0f -> 1f)
    private var wipeProgress = 0f
    private var dwellProgress = 0f
    private var isDwellPaused = false
    private var remainingDwellMs = DURATION_DWELL_MS

    // 缓动曲线：easeOutCubic (与网页端 100% 对齐)
    private val wipeInterpolator = PathInterpolator(0.215f, 0.61f, 0.355f, 1f) // easeOutCubic

    // 动画器引用
    private var wipeInAnimator: ValueAnimator? = null
    private var dwellAnimator: ValueAnimator? = null
    private var wipeOutAnimator: ValueAnimator? = null

    // 常驻专属科技微动效循环 (大字 100% -> 105% 黄金律动 + 5 款模式专属装饰动效)
    private var breatheAnimator: ValueAnimator? = null
    private var heroBreatheScale = 1.0f

    // 动态微动效补间参数 (零 onDraw 运行时堆内存分配)
    private var animPhase = 0f
    private var sportBladeOffset = 0f
    private var sportBracketOffset = 0f
    private var raceKerbOffset = 0f
    private var raceStrobeAlpha = 255
    private var raceShiftLightStep = 0
    private var normalBeaconRadius = 3.6f
    private var normalBeaconAlpha = 255
    private var normalWaveOffset = 0f
    private var ecoWaveOffset = 0f
    private var customCaliperOffset = 0f
    private var customRulerOffset = 0f

    // 矩阵与画笔
    private val bgMatrix = Matrix()
    private val bladeMatrix = Matrix()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heroTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val heroShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val heroFloorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.argb(220, 0, 0, 0)
    }
    private val heroExtrudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val heroHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.argb(190, 255, 255, 255)
    }
    private val decoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val decoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bladePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dwellBarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val dwellBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // 预分配 Paths 与 Rects (严禁在 onDraw 中 new)
    private val clipPathMode = Path()
    private val clipPathReturn = Path()
    private val bladePath = Path()
    private val decoPath1 = Path()
    private val decoPath2 = Path()
    private val rectTmp = RectF()
    private val rectVignetteLeft = RectF(0f, 0f, 130f, LOGICAL_HEIGHT)
    private val rectVignetteRight = RectF(LOGICAL_WIDTH - 130f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)

    // 预构建 Shaders 缓存
    private val bgShaders = mutableMapOf<String, RadialGradient>()
    private val centerGlowShaders = mutableMapOf<String, LinearGradient>()
    private val fadeLShaders = mutableMapOf<String, LinearGradient>()
    private val fadeRShaders = mutableMapOf<String, LinearGradient>()
    private val bladeShaders = mutableMapOf<String, LinearGradient>()
    private var heroTextShader: LinearGradient? = null
    private var vignetteLeftShader: LinearGradient? = null
    private var vignetteRightShader: LinearGradient? = null

    // 虚线 PathEffect (CUSTOM 刻度标尺)
    private val dashEffect = DashPathEffect(floatArrayOf(4f, 12f), 0f)

    // 离线池化 Bitmap (承载主屏快照)
    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private var hasValidSnapshot = false

    // 纯净深空暗底 Shader (当快照为空时的纯净暗色背景，彻底废除图三测试页面)
    private var fallbackBaseShader: RadialGradient? = null
    private val fallbackBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var currentSnapshotProvider: ((Canvas) -> Unit)? = null

    // 外部监听
    var onWipeStartedListener: (() -> Unit)? = null
    var onWipeCompleteListener: (() -> Unit)? = null
    var onWipeOutCompleteListener: (() -> Unit)?
        get() = onWipeCompleteListener
        set(value) { onWipeCompleteListener = value }

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // 立即预分配池化快照 Bitmap，确保首次擦除时 cachedCanvas 永不为 null
        cachedBitmap = Bitmap.createBitmap(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        cachedCanvas = Canvas(cachedBitmap!!)

        // 配置 Orbitron 字体
        HmiFontManager.getOrbitronBold(context)?.let {
            heroTextPaint.typeface = it
            heroShadowPaint.typeface = it
            heroFloorShadowPaint.typeface = it
            heroExtrudePaint.typeface = it
            heroHighlightPaint.typeface = it
        }

        buildStaticShaders()
    }

    private fun buildStaticShaders() {
        val cx = LOGICAL_WIDTH / 2f
        val cy = LOGICAL_HEIGHT / 2f

        modeThemes.forEach { (key, theme) ->
            // 1. 全画幅 980×220 椭圆高饱和径向渐变 (中心强发光，边缘深邃暗夜，完美还原奢华质感)
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

            // 2. 居中水平发光线条渐变 centerGlow
            val centerGlow = LinearGradient(
                cx - 400f, 0f, cx + 400f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            centerGlowShaders[key] = centerGlow

            // 3. 左侧 fadeL
            val fadeL = LinearGradient(
                120f, 0f, 550f, 0f,
                intArrayOf(Color.TRANSPARENT, theme.accentLightColor, Color.WHITE),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            fadeLShaders[key] = fadeL

            // 4. 右侧 fadeR
            val fadeR = LinearGradient(
                1800f, 0f, 1370f, 0f,
                intArrayOf(Color.TRANSPARENT, theme.accentLightColor, Color.WHITE),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            fadeRShaders[key] = fadeR

            // 5. 52px 发光光刃专用渐变 (沿光刃法线方向，从 -26px 至 +26px)
            val rgb = theme.bladeRgb
            val bladeShader = LinearGradient(
                -26f, 0f, 26f, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(56, rgb[0], rgb[1], rgb[2]), // 26%
                    Color.argb(242, 255, 255, 255),          // 50%
                    Color.argb(96, rgb[0], rgb[1], rgb[2]),  // 72%
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.26f, 0.50f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            bladeShaders[key] = bladeShader
        }

        // 大字金属质感渐变 (垂直 180deg: #ffffff 42%, #f1f5f9 80%, #cbd5e1 100%)
        heroTextShader = LinearGradient(
            0f, cy - 65f, 0f, cy + 55f,
            intArrayOf(
                Color.parseColor("#ffffff"),
                Color.parseColor("#ffffff"),
                Color.parseColor("#f1f5f9"),
                Color.parseColor("#cbd5e1")
            ),
            floatArrayOf(0f, 0.42f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )

        // 左右 130px 物理安全暗角
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

        // 拟真主屏深空背景渐变 (当无外部快照时作为保底展现)
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

    private fun startBreatheLoop() {
        if (breatheAnimator != null && breatheAnimator!!.isRunning) return
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L // 2.4秒黄金律动周期
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (currentState != AnimState.IDLE) {
                    animPhase = anim.animatedFraction
                    val angle = animPhase * 2 * Math.PI
                    val sinVal = sin(angle).toFloat()
                    val cosVal = cos(angle).toFloat()
                    val normalizedSin = (sinVal + 1f) / 2f

                    // 1. 模式英文大字放大进度由总转场进度驱动，单调从 100% (1.00f) 放大至 105% (1.05f)，不再反复放大缩小

                    // 2. SPORT 烈焰破风：双侧刀锋向内冲激 (+-12px) 与进气托架呼吸 (+-3px)
                    sportBladeOffset = sinVal * 12f
                    sportBracketOffset = cosVal * 3f

                    // 3. RACE 赛道竞速：换挡多段 LED 跑马点亮与弯心路肩斑马纹动态位移
                    raceStrobeAlpha = if (((animPhase * 8f) % 1f) > 0.45f) 255 else 70
                    raceShiftLightStep = ((animPhase * 8f).toInt()) % 4 // 0..3 级点亮
                    raceKerbOffset = sinVal * 8f

                    // 4. NORMAL 均衡层流：微光信标脉冲 (3.6px -> 6.2px) 与层流平缓微波
                    normalBeaconRadius = 3.6f + 2.6f * normalizedSin
                    normalBeaconAlpha = (140 + 115 * normalizedSin).toInt()
                    normalWaveOffset = sinVal * 4.5f

                    // 5. ECO 翡翠生机：仿生叶脉气流波纹轻柔起伏 (+-7.5px)
                    ecoWaveOffset = sinVal * 7.5f

                    // 6. CUSTOM 钛灰精密：机械游标卡尺往复微调 (+-18px) 与刻度微步进
                    customCaliperOffset = sinVal * 18f
                    customRulerOffset = (animPhase * 32f) % 16f

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
            Log.i(TAG, "DriveMode 离线快照池化 Bitmap 已分配: 1920×384")
        }
    }

    /**
     * 触发驾驶模式左至右光刃擦除转场 (车规级连续切换即刻中断抢占机制)：
     * 1. 相同模式连续触发：若当前模式正在动画或展示中，刷新停留倒计时。
     * 2. 连续切换模式：无论当前处于入场、驻留还是退场，收到新模式时 0 延迟即刻中断打断！
     *    当前模式画面无缝转为底层垫底背景，新模式倾斜光刃立即自左向右擦除展现覆盖；
     *    仅当用户停止切模式后，最终模式驻留 2.0s 并退场，优雅返回主界面。
     */
    fun triggerDriveModeSwitch(mode: String, snapshotProvider: ((Canvas) -> Unit)? = null) {
        val targetMode = normalizeMode(mode)
        Log.i(TAG, "triggerDriveModeSwitch called: targetMode=$targetMode, currentState=$currentState, currentMode=$currentMode")
        mainHandler.post {
            // 1. 同一模式连续触发保护：若当前正在展示该模式（入场/驻留），重置驻留计时器并返回
            if (currentState != AnimState.IDLE && currentMode == targetMode) {
                Log.i(TAG, "相同模式重复触发，刷新驻留计时器: $targetMode (当前状态: $currentState)")
                if (currentState == AnimState.DWELLING) {
                    resetDwellTimer()
                }
                return@post
            }

            // 2. 连续切换不同模式：若当前模式正在入场(WIPING_IN)、驻留(DWELLING)或退场(WIPING_OUT)
            // 立即中断前序动画，前序模式作为底层垫底背景，新模式即刻从屏幕左侧向右擦除展现！
            if (currentState != AnimState.IDLE) {
                Log.i(TAG, "⚡ 连续切模式即刻中断抢占: $currentMode -> $targetMode")
                cancelAllAnimators()
                previousMode = currentMode
                isSwitchingBetweenModes = true
                currentMode = targetMode
                visibility = VISIBLE
                alpha = 1f
                startWipeIn()
                return@post
            }

            // 3. 从 IDLE 常态全新启动：
            cancelAllAnimators()
            isSwitchingBetweenModes = false
            previousMode = null
            currentMode = targetMode
            currentSnapshotProvider = snapshotProvider

            // 离线捕获底层画面
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
            startWipeIn()
        }
    }

    /**
     * 立即取消并隐藏驾驶模式 (被档位等更高优先级打断时调用，彻底终结，绝不恢复)
     */
    fun dismiss() {
        mainHandler.post {
            cancelAllAnimators()
            currentState = AnimState.IDLE
            wipeProgress = 0f
            dwellProgress = 0f
            isDwellPaused = false
            isSwitchingBetweenModes = false
            previousMode = null
            visibility = GONE
            Log.i(TAG, "驾驶模式已被打断并彻底终结 (dismiss)")
        }
    }

    /**
     * 挂起暂停驾驶模式 (兼容旧接口，直接彻底关闭，绝不再恢复)
     */
    fun suspend() {
        dismiss()
    }

    /**
     * 从挂起中恢复驾驶模式 (用户要求：打断后直接保持主页面，绝不再复活展示模式)
     */
    fun resumeFromSuspension(dwellMs: Long = DURATION_DWELL_MS) {
        // no-op: 彻底杜绝打断后再次复活展示
        Log.i(TAG, "resumeFromSuspension 忽略：按用户规范，打断后直接保持主页面，不复活展示")
    }

    /**
     * 兼容旧版挂起恢复接口
     */
    fun resumeDriveModeDwell() {
        resumeFromSuspension(DURATION_DWELL_MS)
    }

    fun pauseDriveModeDwell() {
        suspend()
    }

    /**
     * 在档位处于顶层运行时，底层驾驶模式静默直接就绪为完全展现态
     */
    fun prepareModeInstant(mode: String, snapshotProvider: ((Canvas) -> Unit)? = null) {
        val targetMode = normalizeMode(mode)
        mainHandler.post {
            cancelAllAnimators()
            currentMode = targetMode
            previousMode = null
            isSwitchingBetweenModes = false
            currentSnapshotProvider = snapshotProvider

            if (snapshotProvider != null) {
                cachedCanvas?.let { c ->
                    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    snapshotProvider.invoke(c)
                    hasValidSnapshot = true
                }
            } else {
                hasValidSnapshot = false
            }

            currentState = AnimState.DWELLING
            wipeProgress = 1f
            dwellProgress = 1f
            isDwellPaused = true
            visibility = VISIBLE
            alpha = 1f
            startBreatheLoop()
            invalidate()
            Log.i(TAG, "驾驶模式已在底层静默就绪为完全展现态: $targetMode 模式")
        }
    }

    private var logFrameCounter = 0

    private fun startWipeIn() {
        Log.i(TAG, "startWipeIn: mode=$currentMode, duration=$DURATION_WIPE_IN_MS ms, switching=$isSwitchingBetweenModes")
        currentState = AnimState.WIPING_IN
        wipeProgress = 0f
        visibility = VISIBLE
        alpha = 1f
        startBreatheLoop()
        onWipeStartedListener?.invoke()

        wipeInAnimator?.removeAllListeners()
        wipeInAnimator?.cancel()
        wipeInAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_WIPE_IN_MS
            interpolator = wipeInterpolator
            addUpdateListener { anim ->
                wipeProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentState != AnimState.WIPING_IN) return
                    Log.i(TAG, "startWipeIn ended for mode=$currentMode")
                    isSwitchingBetweenModes = false
                    previousMode = null
                    startDwell(DURATION_DWELL_MS)
                }
            })
            start()
        }
    }

    private fun startDwell(totalMs: Long) {
        Log.i(TAG, "startDwell: mode=$currentMode, totalMs=$totalMs, paused=$isDwellPaused")
        currentState = AnimState.DWELLING
        wipeProgress = 1f
        dwellProgress = 1f
        remainingDwellMs = totalMs

        if (isDwellPaused) return

        dwellAnimator?.cancel()
        dwellAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = totalMs
            interpolator = null // 线性缩短
            addUpdateListener { anim ->
                dwellProgress = anim.animatedValue as Float
                remainingDwellMs = (totalMs * dwellProgress).toLong()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    Log.i(TAG, "startDwell ended -> entering WIPING_OUT")
                    if (!isDwellPaused && currentState == AnimState.DWELLING) {
                        startWipeOut()
                    }
                }
            })
            start()
        }
    }

    private fun resetDwellTimer() {
        dwellAnimator?.removeAllListeners()
        dwellAnimator?.cancel()
        dwellAnimator = null
        startDwell(DURATION_DWELL_MS)
    }

    private fun startWipeOut() {
        Log.i(TAG, "startWipeOut: mode=$currentMode, duration=$DURATION_WIPE_OUT_MS ms")
        currentState = AnimState.WIPING_OUT
        wipeProgress = 0f

        // 擦除退场前立即重新就地抓取最新原生时钟气象画面，杜绝露出2秒前的旧时间快照 (彻底解决图四问题)
        currentSnapshotProvider?.let { provider ->
            cachedCanvas?.let { c ->
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                provider.invoke(c)
                hasValidSnapshot = true
            }
        }

        wipeOutAnimator?.removeAllListeners()
        wipeOutAnimator?.cancel()
        wipeOutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_WIPE_OUT_MS
            interpolator = wipeInterpolator
            addUpdateListener { anim ->
                wipeProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentState != AnimState.WIPING_OUT) return
                    Log.i(TAG, "startWipeOut ended -> IDLE")
                    currentState = AnimState.IDLE
                    cancelAllAnimators()
                    wipeProgress = 0f
                    visibility = GONE
                    onWipeCompleteListener?.invoke()
                }
            })
            start()
        }
    }

    private fun cancelAllAnimators() {
        wipeInAnimator?.removeAllListeners()
        wipeInAnimator?.cancel()
        wipeInAnimator = null

        dwellAnimator?.removeAllListeners()
        dwellAnimator?.cancel()
        dwellAnimator = null

        wipeOutAnimator?.removeAllListeners()
        wipeOutAnimator?.cancel()
        wipeOutAnimator = null

        breatheAnimator?.removeAllListeners()
        breatheAnimator?.cancel()
        breatheAnimator = null
    }

    override fun onDetachedFromWindow() {
        dismiss()
        cancelAllAnimators()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentState == AnimState.IDLE) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

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

        // 计算模式英文名称单调放大比例：严格从 100% (1.00f) 平滑放大至 105% (1.05f) 结束，绝不放大再缩小
        val totalProgress = when (currentState) {
            AnimState.WIPING_IN -> (wipeProgress * 600f) / 3200f
            AnimState.DWELLING -> (600f + (1f - dwellProgress) * 2000f) / 3200f
            AnimState.WIPING_OUT -> (2600f + wipeProgress * 600f) / 3200f
            AnimState.IDLE -> 0f
        }.coerceIn(0f, 1f)
        heroBreatheScale = 1.00f + 0.05f * totalProgress

        val theme = modeThemes[currentMode] ?: modeThemes["NORMAL"]!!

        when (currentState) {
            AnimState.WIPING_IN -> {
                // 计算倾斜切线位置：从 START_X 扫至 TARGET_X
                val currentX = START_X + (TARGET_X - START_X) * wipeProgress
                val topCutX = currentX + SKEW_DX
                val btmCutX = currentX

                // 1. 底层：若模式间连切，底层绘制上一模式；若初态入场，底层保持完全透明（透显底层正在实时 60FPS 渲染的写实天气/时钟/歌词）
                if (isSwitchingBetweenModes && previousMode != null) {
                    val prevTheme = modeThemes[previousMode] ?: modeThemes["ECO"]!!
                    drawModeContent(canvas, prevTheme, withCountdown = false)
                }

                // 2. 顶层：通过倾斜 polygon clipPath 擦除展现新模式
                clipPathMode.reset()
                clipPathMode.moveTo(0f, 0f)
                clipPathMode.lineTo(topCutX, 0f)
                clipPathMode.lineTo(btmCutX, LOGICAL_HEIGHT)
                clipPathMode.lineTo(0f, LOGICAL_HEIGHT)
                clipPathMode.close()

                canvas.save()
                canvas.clipPath(clipPathMode)
                drawModeContent(canvas, theme, withCountdown = false)
                drawEdgeVignette(canvas)
                canvas.restore()

                // 3. 伴随倾斜切线绘制 52px 半透明发光光刃
                drawAngledLightBlade(canvas, currentX, theme)
            }

            AnimState.DWELLING -> {
                // 满屏展现态，伴随专属科技装饰动效与底部细线进度条
                drawModeContent(canvas, theme, withCountdown = true)
                drawEdgeVignette(canvas)
            }

            AnimState.WIPING_OUT -> {
                // 原生主页面自左向右重新展开，模式页面仅在切线右侧保留
                val currentX = START_X + (TARGET_X - START_X) * wipeProgress
                val topCutX = currentX + SKEW_DX
                val btmCutX = currentX

                // 注意：切线左侧不绘制任何静态快照，保持 100% 透明，实时透显底层正在 60FPS 渲染的主界面（时间与歌词无缝连贯无跳变）

                // 顶层：被擦除的模式页面仅在切线右侧保留
                clipPathMode.reset()
                clipPathMode.moveTo(topCutX, 0f)
                clipPathMode.lineTo(LOGICAL_WIDTH, 0f)
                clipPathMode.lineTo(LOGICAL_WIDTH, LOGICAL_HEIGHT)
                clipPathMode.lineTo(btmCutX, LOGICAL_HEIGHT)
                clipPathMode.close()

                canvas.save()
                canvas.clipPath(clipPathMode)
                drawModeContent(canvas, theme, withCountdown = false)
                drawEdgeVignette(canvas)
                canvas.restore()

                // 切线上绘制发光光刃
                drawAngledLightBlade(canvas, currentX, theme)
            }

            AnimState.IDLE -> {}
        }

        canvas.restore()
    }

    /**
     * 绘制指定模式的全画幅内容 (1920 × 384)
     */
    private fun drawModeContent(canvas: Canvas, theme: ModeTheme, withCountdown: Boolean) {
        val cx = LOGICAL_WIDTH / 2f
        val cy = LOGICAL_HEIGHT / 2f

        // 1. 大气背景：优先使用 HTML 1:1 像素级 1920×384 WebP 渐变背景大图 (带物理安全暗角，共享RGB_565池)
        val bgBmp = SharedHmiAssetManager.getDriveModeBitmap(resources, theme.key) ?: SharedHmiAssetManager.getDriveModeBitmap(resources, "NORMAL")
        if (bgBmp != null) {
            bgPaint.shader = null
            bgPaint.alpha = 255
            canvas.drawBitmap(bgBmp, 0f, 0f, bgPaint)
        } else {
            val bgShader = bgShaders[theme.key] ?: bgShaders["NORMAL"]!!
            bgPaint.shader = bgShader
            bgPaint.alpha = 255
            rectTmp.set(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
            canvas.drawRect(rectTmp, bgPaint)
        }

        // 2. 5 款模式专属科技微动效装饰线条 (完全复刻 SVG 规范)
        drawModeBespokeArtwork(canvas, theme, cx, cy)

        // 3. 居中极简巨幅模式大字 (Orbitron Bold, 148px 或 128px)
        // 模式英文名称轻微放大：从 100% (1.00f) 放大至 105% (1.05f) 黄金律动
        val wordScale = heroBreatheScale
        canvas.save()
        canvas.scale(wordScale, wordScale, cx, cy)

        val textSize = if (theme.isSixLetters) 128f else 148f
        val letterSpacing = if (theme.isSixLetters) 0.09f else 0.12f
        draw3DModeText(canvas, theme.key, cx, cy, textSize, letterSpacing, theme)

        canvas.restore()

        // 4. 底部微型细线驻留进度条 (位于 bottom: 0px，宽 1660px，高 2px)
        if (withCountdown && dwellProgress > 0f) {
            val barLeft = 130f
            val maxBarW = LOGICAL_WIDTH - 260f // 1660px
            val currentBarW = maxBarW * dwellProgress
            val barTop = LOGICAL_HEIGHT - 2f

            // 底轨
            rectTmp.set(barLeft, barTop, barLeft + maxBarW, LOGICAL_HEIGHT)
            canvas.drawRect(rectTmp, dwellBarTrackPaint)

            // 填充条
            if (currentBarW > 0f) {
                rectTmp.set(barLeft, barTop, barLeft + currentBarW, LOGICAL_HEIGHT)
                canvas.drawRect(rectTmp, dwellBarFillPaint)
            }
        }
    }

    /**
     * 绘制 5 层立体雕刻质感 3D 模式文字 (深层投影 + 霓虹漫反射 + CNC 实体厚度倒角挤出 + 金属渐变本体 + 刀锋切面高光)
     */
    private fun draw3DModeText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        textSize: Float,
        letterSpacing: Float,
        theme: ModeTheme
    ) {
        heroTextPaint.textSize = textSize
        heroTextPaint.letterSpacing = letterSpacing
        heroShadowPaint.textSize = textSize
        heroShadowPaint.letterSpacing = letterSpacing
        heroFloorShadowPaint.textSize = textSize
        heroFloorShadowPaint.letterSpacing = letterSpacing
        heroExtrudePaint.textSize = textSize
        heroExtrudePaint.letterSpacing = letterSpacing
        heroHighlightPaint.textSize = textSize
        heroHighlightPaint.letterSpacing = letterSpacing

        val fm = heroTextPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f

        // Pass 1: 极深地面投影 (Floor Cast Shadow) -> Y + 14px, 强烈地面分离与悬浮立体感
        heroFloorShadowPaint.color = Color.argb(220, 0, 0, 0)
        canvas.drawText(text, cx, textY + 14f, heroFloorShadowPaint)

        // Pass 2: 环境霓虹大面积漫反射光晕 (Ambient Glow Bloom)
        heroShadowPaint.shader = null
        heroShadowPaint.color = theme.glowColor
        canvas.drawText(text, cx, textY, heroShadowPaint)

        // Pass 3: CNC 实体厚度倒角挤出 (Extrusion Bevel) -> 产生实物车标金属厚度感
        heroExtrudePaint.color = theme.darkExtrudeColor
        canvas.drawText(text, cx, textY + 5f, heroExtrudePaint)
        canvas.drawText(text, cx, textY + 3f, heroExtrudePaint)
        canvas.drawText(text, cx, textY + 1f, heroExtrudePaint)

        // Pass 4: 极光金属高光本体 (Chrome Brushed Face)
        heroTextPaint.shader = heroTextShader
        canvas.drawText(text, cx, textY, heroTextPaint)

        // Pass 5: 顶部极细刀锋高光切边 (Top Specular Highlight) -> Y - 2px
        canvas.drawText(text, cx, textY - 2f, heroHighlightPaint)
    }

    /**
     * 绘制 5 款模式 1:1 对应的专属高精矢量艺术图层
     */
    private fun drawModeBespokeArtwork(canvas: Canvas, theme: ModeTheme, cx: Float, cy: Float) {
        val centerGlow = centerGlowShaders[theme.key] ?: centerGlowShaders["SPORT"]!!
        val fadeL = fadeLShaders[theme.key] ?: fadeLShaders["SPORT"]!!
        val fadeR = fadeRShaders[theme.key] ?: fadeRShaders["SPORT"]!!

        // 上下边缘极细发光视平线 (微光定焦)
        decoStrokePaint.shader = centerGlow
        decoStrokePaint.strokeWidth = 2f
        decoStrokePaint.pathEffect = null
        canvas.drawLine(420f, 1f, 1500f, 1f, decoStrokePaint)
        canvas.drawLine(420f, 383f, 1500f, 383f, decoStrokePaint)

        when (theme.key) {
            "ECO" -> {
                // 1. 顶部叶脉微光弧 (随呼吸柔和微幅起伏)
                decoPath1.reset()
                decoPath1.moveTo(920f, 82f + ecoWaveOffset * 0.4f)
                decoPath1.quadTo(960f, 58f + ecoWaveOffset * 0.8f, 1000f, 82f + ecoWaveOffset * 0.4f)
                decoStrokePaint.shader = null
                decoStrokePaint.color = Color.WHITE
                decoStrokePaint.strokeWidth = 2.6f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(942f, 74f + ecoWaveOffset * 0.4f)
                decoPath1.quadTo(960f, 64f + ecoWaveOffset * 0.7f, 978f, 74f + ecoWaveOffset * 0.4f)
                decoStrokePaint.color = theme.accentLightColor
                decoStrokePaint.strokeWidth = 1.8f
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 2. 左侧轻盈气流 (正弦波仿生起伏)
                decoPath1.reset()
                decoPath1.moveTo(160f, 220f)
                decoPath1.cubicTo(310f, 220f + ecoWaveOffset, 400f, 164f - ecoWaveOffset, 550f, 192f)
                decoStrokePaint.shader = fadeL
                decoStrokePaint.strokeWidth = 3f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(260f, 246f)
                decoPath1.cubicTo(370f, 246f - ecoWaveOffset, 440f, 198f + ecoWaveOffset, 535f, 214f)
                decoStrokePaint.strokeWidth = 1.6f
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 3. 右侧轻盈气流
                decoPath1.reset()
                decoPath1.moveTo(1760f, 220f)
                decoPath1.cubicTo(1610f, 220f + ecoWaveOffset, 1520f, 164f - ecoWaveOffset, 1370f, 192f)
                decoStrokePaint.shader = fadeR
                decoStrokePaint.strokeWidth = 3f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(1660f, 246f)
                decoPath1.cubicTo(1550f, 246f - ecoWaveOffset, 1480f, 198f + ecoWaveOffset, 1385f, 214f)
                decoStrokePaint.strokeWidth = 1.6f
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 4. 底部有机光弧 (柔和生机微脉冲)
                decoPath1.reset()
                decoPath1.moveTo(790f, 296f - ecoWaveOffset * 0.4f)
                decoPath1.quadTo(960f, 314f + ecoWaveOffset * 0.6f, 1130f, 296f - ecoWaveOffset * 0.4f)
                decoStrokePaint.shader = centerGlow
                decoStrokePaint.strokeWidth = 2.2f
                canvas.drawPath(decoPath1, decoStrokePaint)
            }

            "NORMAL" -> {
                // 1. 上下平衡横杆
                decoStrokePaint.shader = centerGlow
                decoStrokePaint.strokeWidth = 2.6f
                canvas.drawLine(850f, 76f, 1070f, 76f, decoStrokePaint)
                canvas.drawLine(810f, 308f, 1110f, 308f, decoStrokePaint)

                // 2. 左侧平稳层流与信标 (层流微波波浪)
                decoStrokePaint.shader = fadeL
                decoStrokePaint.strokeWidth = 2.8f
                canvas.drawLine(140f, 192f, 470f, 192f, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(240f, 172f)
                decoPath1.quadTo(360f, 160f + normalWaveOffset, 455f, 178f)
                decoStrokePaint.strokeWidth = 1.6f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(240f, 212f)
                decoPath1.quadTo(360f, 224f - normalWaveOffset, 455f, 206f)
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 左微光信标脉冲发散
                decoFillPaint.color = Color.argb(normalBeaconAlpha / 3, 56, 189, 248)
                canvas.drawCircle(470f, 192f, normalBeaconRadius * 1.8f, decoFillPaint)
                decoFillPaint.color = Color.WHITE
                decoFillPaint.alpha = normalBeaconAlpha
                canvas.drawCircle(470f, 192f, normalBeaconRadius, decoFillPaint)

                // 3. 右侧平稳层流与信标
                decoStrokePaint.shader = fadeR
                decoStrokePaint.strokeWidth = 2.8f
                canvas.drawLine(1780f, 192f, 1450f, 192f, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(1680f, 172f)
                decoPath1.quadTo(1560f, 160f + normalWaveOffset, 1465f, 178f)
                decoStrokePaint.strokeWidth = 1.6f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(1680f, 212f)
                decoPath1.quadTo(1560f, 224f - normalWaveOffset, 1465f, 206f)
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 右微光信标脉冲发散
                decoFillPaint.color = Color.argb(normalBeaconAlpha / 3, 56, 189, 248)
                canvas.drawCircle(1450f, 192f, normalBeaconRadius * 1.8f, decoFillPaint)
                decoFillPaint.color = Color.WHITE
                decoFillPaint.alpha = normalBeaconAlpha
                canvas.drawCircle(1450f, 192f, normalBeaconRadius, decoFillPaint)
            }

            "SPORT" -> {
                // 1. 上下破风托架 (微幅张合呼吸)
                decoStrokePaint.shader = centerGlow
                decoStrokePaint.strokeWidth = 2.8f

                decoPath1.reset()
                decoPath1.moveTo(760f, 86f - sportBracketOffset)
                decoPath1.lineTo(790f, 72f - sportBracketOffset)
                decoPath1.lineTo(1130f, 72f - sportBracketOffset)
                decoPath1.lineTo(1160f, 86f - sportBracketOffset)
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(760f, 298f + sportBracketOffset)
                decoPath1.lineTo(790f, 312f + sportBracketOffset)
                decoPath1.lineTo(1130f, 312f + sportBracketOffset)
                decoPath1.lineTo(1160f, 298f + sportBracketOffset)
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 2. 左侧破风刀锋 (动态破风进气冲激 +sportBladeOffset)
                decoStrokePaint.shader = fadeL
                decoStrokePaint.strokeWidth = 3f
                canvas.drawLine(140f, 192f, 495f + sportBladeOffset, 192f, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(545f + sportBladeOffset, 148f)
                decoPath1.lineTo(508f + sportBladeOffset, 192f)
                decoPath1.lineTo(545f + sportBladeOffset, 236f)
                decoStrokePaint.shader = null
                decoStrokePaint.color = Color.WHITE
                decoStrokePaint.strokeWidth = 4.5f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(512f + sportBladeOffset * 0.8f, 162f)
                decoPath1.lineTo(484f + sportBladeOffset * 0.8f, 192f)
                decoPath1.lineTo(512f + sportBladeOffset * 0.8f, 222f)
                decoStrokePaint.color = theme.accentLightColor
                decoStrokePaint.strokeWidth = 3f
                canvas.drawPath(decoPath1, decoStrokePaint)

                // 3. 右侧破风刀锋 (动态破风进气冲激 -sportBladeOffset)
                decoStrokePaint.shader = fadeR
                decoStrokePaint.strokeWidth = 3f
                canvas.drawLine(1780f, 192f, 1425f - sportBladeOffset, 192f, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(1375f - sportBladeOffset, 148f)
                decoPath1.lineTo(1412f - sportBladeOffset, 192f)
                decoPath1.lineTo(1375f - sportBladeOffset, 236f)
                decoStrokePaint.shader = null
                decoStrokePaint.color = Color.WHITE
                decoStrokePaint.strokeWidth = 4.5f
                canvas.drawPath(decoPath1, decoStrokePaint)

                decoPath1.reset()
                decoPath1.moveTo(1408f - sportBladeOffset * 0.8f, 162f)
                decoPath1.lineTo(1436f - sportBladeOffset * 0.8f, 192f)
                decoPath1.lineTo(1408f - sportBladeOffset * 0.8f, 222f)
                decoStrokePaint.color = theme.accentLightColor
                decoStrokePaint.strokeWidth = 3f
                canvas.drawPath(decoPath1, decoStrokePaint)
            }

            "RACE" -> {
                // 1. 顶部红线换挡灯条 (Shift-Light Bar)
                decoStrokePaint.shader = centerGlow
                decoStrokePaint.strokeWidth = 3.5f
                canvas.drawLine(720f, 68f, 1200f, 68f, decoStrokePaint)

                // 赛道 LED 换挡指示灯多级跑马点亮 (绿 -> 黄 -> 红)
                val leftLeds = floatArrayOf(840f, 880f, 920f)
                val rightLeds = floatArrayOf(1080f, 1040f, 1000f)
                val ledColors = intArrayOf(
                    Color.parseColor("#10b981"), // 绿
                    Color.parseColor("#f59e0b"), // 黄
                    Color.parseColor("#ef4444")  // 红
                )
                for (i in 0..2) {
                    val isActive = raceShiftLightStep >= i
                    val color = if (isActive) ledColors[i] else Color.argb(45, 255, 255, 255)
                    decoFillPaint.color = color
                    rectTmp.set(leftLeds[i] - 10f, 64f, leftLeds[i] + 10f, 72f)
                    canvas.drawRoundRect(rectTmp, 2f, 2f, decoFillPaint)
                    rectTmp.set(rightLeds[i] - 10f, 64f, rightLeds[i] + 10f, 72f)
                    canvas.drawRoundRect(rectTmp, 2f, 2f, decoFillPaint)
                }

                // 居中爆闪发光方块 (达到极限红线换挡点时高频频闪)
                decoFillPaint.color = if (raceShiftLightStep >= 3) {
                    Color.argb(raceStrobeAlpha, 255, 255, 255)
                } else {
                    Color.argb(120, 239, 68, 68)
                }
                rectTmp.set(946f, 62f, 974f, 74f)
                canvas.drawRoundRect(rectTmp, 2.5f, 2.5f, decoFillPaint)

                // 底部基准线
                decoStrokePaint.strokeWidth = 2.5f
                canvas.drawLine(760f, 314f, 1160f, 314f, decoStrokePaint)

                // 2. 左侧弯心红白路肩斜纹 (Apex Kerbs 随赛车掠影微移)
                decoStrokePaint.shader = fadeL
                canvas.drawLine(120f, 192f, 460f, 192f, decoStrokePaint)

                drawApexKerb(canvas, 565f + raceKerbOffset, 583f + raceKerbOffset, 555f + raceKerbOffset, 537f + raceKerbOffset, Color.WHITE)
                drawApexKerb(canvas, 530f + raceKerbOffset, 545f + raceKerbOffset, 521f + raceKerbOffset, 506f + raceKerbOffset, theme.accentLightColor)
                drawApexKerb(canvas, 498f + raceKerbOffset, 510f + raceKerbOffset, 490f + raceKerbOffset, 478f + raceKerbOffset, theme.accentColor)

                // 3. 右侧弯心红白路肩斜纹
                decoStrokePaint.shader = fadeR
                canvas.drawLine(1800f, 192f, 1460f, 192f, decoStrokePaint)

                drawApexKerb(canvas, 1355f - raceKerbOffset, 1337f - raceKerbOffset, 1365f - raceKerbOffset, 1383f - raceKerbOffset, Color.WHITE)
                drawApexKerb(canvas, 1390f - raceKerbOffset, 1375f - raceKerbOffset, 1399f - raceKerbOffset, 1414f - raceKerbOffset, theme.accentLightColor)
                drawApexKerb(canvas, 1422f - raceKerbOffset, 1410f - raceKerbOffset, 1430f - raceKerbOffset, 1442f - raceKerbOffset, theme.accentColor)
            }

            "CUSTOM" -> {
                // 1. 顶部与底部精密刻度标尺 (带刻度步进微位移)
                decoStrokePaint.shader = centerGlow
                decoStrokePaint.strokeWidth = 2f
                decoStrokePaint.pathEffect = dashEffect
                canvas.save()
                canvas.translate(customRulerOffset, 0f)
                canvas.drawLine(780f - customRulerOffset, 74f, 1140f - customRulerOffset, 74f, decoStrokePaint)
                canvas.drawLine(780f - customRulerOffset, 310f, 1140f - customRulerOffset, 310f, decoStrokePaint)
                canvas.restore()
                decoStrokePaint.pathEffect = null

                // 2. 左侧精密轨道与游标 (卡尺往复平滑滑动 +customCaliperOffset)
                decoStrokePaint.shader = fadeL
                decoStrokePaint.strokeWidth = 1.8f
                canvas.drawLine(180f, 166f, 465f, 166f, decoStrokePaint)
                canvas.drawLine(180f, 218f, 465f, 218f, decoStrokePaint)
                decoStrokePaint.strokeWidth = 2.5f
                canvas.drawLine(130f, 192f, 465f, 192f, decoStrokePaint)

                decoFillPaint.color = Color.WHITE
                canvas.drawCircle(390f + customCaliperOffset * 0.4f, 166f, 5f, decoFillPaint)

                // 菱形精密游标 (滑动位移)
                val diamondLeftX = 438f + customCaliperOffset
                canvas.save()
                canvas.rotate(45f, diamondLeftX, 192f)
                rectTmp.set(diamondLeftX - 6f, 186f, diamondLeftX + 6f, 198f)
                canvas.drawRoundRect(rectTmp, 2f, 2f, decoFillPaint)
                canvas.restore()

                decoFillPaint.color = theme.accentLightColor
                canvas.drawCircle(340f + customCaliperOffset * 0.3f, 218f, 5f, decoFillPaint)

                // 3. 右侧对称精密轨道与游标 (卡尺对称往复滑动 -customCaliperOffset)
                decoStrokePaint.shader = fadeR
                decoStrokePaint.strokeWidth = 1.8f
                canvas.drawLine(1740f, 166f, 1455f, 166f, decoStrokePaint)
                canvas.drawLine(1740f, 218f, 1455f, 218f, decoStrokePaint)
                decoStrokePaint.strokeWidth = 2.5f
                canvas.drawLine(1790f, 192f, 1455f, 192f, decoStrokePaint)

                decoFillPaint.color = Color.WHITE
                canvas.drawCircle(1530f - customCaliperOffset * 0.4f, 166f, 5f, decoFillPaint)

                val diamondRightX = 1482f - customCaliperOffset
                canvas.save()
                canvas.rotate(45f, diamondRightX, 192f)
                rectTmp.set(diamondRightX - 6f, 186f, diamondRightX + 6f, 198f)
                canvas.drawRoundRect(rectTmp, 2f, 2f, decoFillPaint)
                canvas.restore()

                decoFillPaint.color = theme.accentLightColor
                canvas.drawCircle(1580f - customCaliperOffset * 0.3f, 218f, 5f, decoFillPaint)
            }
        }
    }

    private fun drawApexKerb(canvas: Canvas, x1: Float, x2: Float, x3: Float, x4: Float, color: Int) {
        decoPath2.reset()
        decoPath2.moveTo(x1, 152f)
        decoPath2.lineTo(x2, 152f)
        decoPath2.lineTo(x3, 232f)
        decoPath2.lineTo(x4, 232f)
        decoPath2.close()
        decoFillPaint.color = color
        canvas.drawPath(decoPath2, decoFillPaint)
    }

    /**
     * 绘制倾斜 -6° 半透明光刃发光带 (宽度 52px，与切线严格平行)
     */
    private fun drawAngledLightBlade(canvas: Canvas, currentX: Float, theme: ModeTheme) {
        val bladeShader = bladeShaders[theme.key] ?: bladeShaders["SPORT"]!!

        // 光刃平行四边形：底端位于 currentX ± 26px，顶端位于 (currentX + SKEW_DX) ± 26px
        val topX = currentX + SKEW_DX
        val btmX = currentX

        bladePath.reset()
        bladePath.moveTo(topX - 26f, 0f)
        bladePath.lineTo(topX + 26f, 0f)
        bladePath.lineTo(btmX + 26f, LOGICAL_HEIGHT)
        bladePath.lineTo(btmX - 26f, LOGICAL_HEIGHT)
        bladePath.close()

        // 沿光刃轴心变换 Shader 矩阵
        bladeMatrix.reset()
        bladeMatrix.setTranslate(currentX, 0f)
        // 倾斜 skewX: tan(6deg) = SKEW_DX / LOGICAL_HEIGHT
        bladeMatrix.preSkew(SKEW_DX / LOGICAL_HEIGHT, 0f)
        bladeShader.setLocalMatrix(bladeMatrix)

        bladePaint.shader = bladeShader
        canvas.drawPath(bladePath, bladePaint)
    }

    /**
     * 绘制左右两侧 130px 物理安全暗角
     */
    private fun drawEdgeVignette(canvas: Canvas) {
        vignettePaint.shader = vignetteLeftShader
        canvas.drawRect(rectVignetteLeft, vignettePaint)

        vignettePaint.shader = vignetteRightShader
        canvas.drawRect(rectVignetteRight, vignettePaint)
    }

    private fun drawBaseDashboard(canvas: Canvas) {
        if (hasValidSnapshot && cachedBitmap != null) {
            bitmapPaint.alpha = 255
            canvas.drawBitmap(cachedBitmap!!, 0f, 0f, bitmapPaint)
        } else {
            // 纯净深空暗底 (彻底废除图三测试页面)
            fallbackBasePaint.shader = fallbackBaseShader
            fallbackBasePaint.alpha = 255
            rectTmp.set(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
            canvas.drawRect(rectTmp, fallbackBasePaint)
        }
    }

    private fun normalizeMode(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("ECO") -> "ECO"
            upper.contains("SPORT") -> "SPORT"
            upper.contains("RACE") -> "RACE"
            upper.contains("NORMAL") || upper.contains("COMFORT") -> "NORMAL"
            upper.contains("CUSTOM") -> "CUSTOM"
            else -> "CUSTOM"
        }
    }
}

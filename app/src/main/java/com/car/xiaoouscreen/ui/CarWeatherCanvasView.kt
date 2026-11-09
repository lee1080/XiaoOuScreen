package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.carlyrics.util.PrefsManager
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.util.SharedHmiAssetManager
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherState
import com.carlyrics.model.LyricLine
import com.carlyrics.recognition.MusicRecognitionEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 车载超宽副屏 (1920 × 384 px) iOS 天气风格拟真多图层气象动态背景 + 巨幕数字时钟与左置精致 HUD
 *
 * 核心升级点：
 * 1. 【黄金分割宽屏布局】：
 *    - 左侧信息枢纽 (X=160~620)：精致大字温标 (72px) + 天气状态 (30px) + 风速舒适度 + 日期星期 (25px) + 雅致纵向光轨分割线；
 *    - 中央巨幕数字时钟 (X=1080)：字号激增至 196px，巨幅居中排布，多层大气发光与阴影，极致霸气醒目；
 *    - 右侧天体视界 (X=1620)：真实天文级月面月海质感、HDR 真实太阳与变形镜头宽银幕横向耀斑。
 * 2. 【去粗糙光晕与真实天体】：
 *    - 太阳：真实太阳辐射光晕 + 变形荧幕光斑 (Anamorphic Streak)，无粗糙同心圆；
 *    - 月球：真实高动态月面 basalt 月海 plains 与球面边缘渐隐，彻底告别“两个圆重叠挖黑色”的生硬质感；
 * 3. 【4×7=28 全组合及雨夹雪真实双粒子】：
 *    - 包含雨夹雪 (SLEET) 独立状态机，雨丝与雪花 50/50 独立物理轨迹同时飘落。
 * 4. 【零 GC 与车载高性能保障】：预加载 Alpha 纹理，onDraw 纯 GPU Quad 绘制，60fps 丝滑。
 * 5. 【黑边融合与安全区】：左右 130px 物理安全黑边遮罩。
 */
class CarWeatherCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    companion object {
        private const val TAG = "CarWeatherCanvasView"
        const val LOGICAL_WIDTH = 1920f
        const val LOGICAL_HEIGHT = 384f
        const val SAFE_MARGIN_PX = 130f
        const val SAFE_RIGHT_START_PX = 1790f // 1920 - 130

        private const val MAX_RAIN_PARTICLES = 160
        private const val MAX_SNOW_PARTICLES = 110
        private const val MAX_STARS = 48

        // 天体视界中心坐标 (置于用户红圈标注位置 X=410, Y=75，完美避开小欧 3D 形象与 HUD 文本)
        private const val CELESTIAL_CENTER_X = 410f
        private const val CELESTIAL_CENTER_Y = 75f

        // 中央巨幕数字时钟坐标 (正中心 X=960)
        private const val CLOCK_CENTER_X = 960f

        // 左侧 HUD 信息枢纽坐标
        private const val HUD_LEFT_X = 165f
        private const val HUD_DIVIDER_X = 535f

        // 小欧 3D 形象实体手势避让右边界 (1420px) 与五行歌词非线性行高阶梯 (中心到次级 80px，次级到边缘紧凑 50px)
        private const val XIAOOU_GESTURE_BOUNDARY_X = 1420f
        private const val LINE_SPACING_CENTER_TO_MID = 80f
        private const val LINE_SPACING_MID_TO_EDGE = 50f
    }

    // ==========================================
    // 状态机与过渡属性
    // ==========================================
    private var currentConfig: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    private var previousConfig: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    private var transitionProgress = 1.0f

    // 场景视觉刷新监听器 (通知左下角 HUD 等附着晶体同步重绘模糊层)
    var onSceneRenderUpdated: (() -> Unit)? = null

    private val transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            transitionProgress = it.animatedValue as Float
            invalidate()
            onSceneRenderUpdated?.invoke()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                transitionProgress = 1.0f
                previousConfig = currentConfig
                invalidate()
                onSceneRenderUpdated?.invoke()
            }
        })
    }

    private var isRenderingActive = false
    private var isPausedManually = false
    private var lastFrameTimeNanos = 0L
    private val random = Random(42)

    // ==========================================
    // 预分配 Paint 与 Path (零 GC 核心)
    // ==========================================
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyRect = RectF(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)

    // 天体 (太阳 HDR、宽荧幕耀斑、月球天文级质感、星空)
    private var bmpSun: Bitmap? = null
    private var bmpSunRays: Bitmap? = null
    private var bmpSunFlare: Bitmap? = null
    private var bmpMoon: Bitmap? = null

    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sunFlarePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sunHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val moonHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sunDestRect = RectF()
    private val sunFlareDestRect = RectF()
    private val sunRayRect = RectF()
    private val moonDestRect = RectF()

    // 零 GC 预设颜色、矩阵与缓存 Shader
    private val COLOR_DAWN_TINT = Color.parseColor("#FED7AA")
    private val COLOR_DUSK_TINT = Color.parseColor("#FB923C")
    private val COLOR_AMBIENT_TINT = Color.parseColor("#E0F2FE")

    private var cachedSkyShader: LinearGradient? = null
    private var lastSkyTopColor: Int = -1
    private var lastSkyBottomColor: Int = -1

    private val sunHaloMatrix = Matrix()
    private var cachedSunHaloShader: RadialGradient? = null
    private var lastSunHaloTimeOfDay: TimeOfDay? = null

    private var cachedMoonHaloShader: RadialGradient? = null

    // 零 GC 太阳本体与云层 ColorFilter 缓存
    private var cachedSunTintColor: Int? = null
    private var cachedSunColorFilter: PorterDuffColorFilter? = null
    private var cachedCloudTintColor: Int = 0
    private var cachedCloudColorFilter: PorterDuffColorFilter? = null

    // HUD 文字与测量宽度零 GC 缓存
    private data class ComfortLevel(val statusText: String, @ColorInt val color: Int)

    private var cachedHudTempText = ""
    private var cachedHudWeatherText = ""
    private var cachedHudSubInfoText = ""
    private var cachedHudRainText = ""
    private var cachedHudPrefixText = "实时环境感知 · "
    private var cachedHudComfort: ComfortLevel = ComfortLevel("舒适", Color.parseColor("#4ADE80"))
    private var cachedHudHasValidRainAlert = false
    private var cachedHudWeatherWidth = 0f
    private var cachedHudSubInfoWidth = 0f
    private var cachedHudPrefixWidth = 0f
    private var cachedHudComfortWidth = 0f
    private var cachedHudRainWidth = 0f
    private var cachedHudNoteWidth = 0f
    private var cachedHudDateWidth = 0f
    private var lastMeasuredDateStr = ""
    private var hudDataDirty = true

    // iOS 风格 5 层体积云无缝纹理与渲染画笔
    private var bmpCirrus: Bitmap? = null
    private var bmpCumulusBack: Bitmap? = null
    private var bmpCumulusFront: Bitmap? = null
    private var bmpMist: Bitmap? = null
    private var bmpOvercast: Bitmap? = null

    private val cloudCirrusPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cloudCumulusBackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cloudCumulusFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cloudMistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cloudOvercastPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var scrollCirrusX = 0f
    private var scrollCumulusBackX = 0f
    private var scrollCumulusFrontX = 0f
    private var scrollMistX = 0f
    private var scrollOvercastX = 0f

    // 车载接打电话与微信通话 256x256 分层位图资源
    private var bmpCallIncomingHandset: Bitmap? = null
    private var bmpCallIncomingArrow: Bitmap? = null
    private var bmpCallIncomingRipple: Bitmap? = null
    private var bmpCallOutgoingHandset: Bitmap? = null
    private var bmpCallOutgoingArrow: Bitmap? = null
    private var bmpCallOutgoingWave1: Bitmap? = null
    private var bmpCallOutgoingWave2: Bitmap? = null
    private var bmpCallConnectedHandset: Bitmap? = null
    private var bmpCallConnectedWave1: Bitmap? = null
    private var bmpCallConnectedWave2: Bitmap? = null
    private var bmpCallConnectedWave3: Bitmap? = null
    private var bmpCallHangupHandset: Bitmap? = null
    private var bmpCallHangupHalo: Bitmap? = null
    private var bmpCallWeChatBack: Bitmap? = null
    private var bmpCallWeChatFront: Bitmap? = null
    private var bmpCallWeChatWave: Bitmap? = null

    // 雨、雪、雷暴
    private val rainBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rainFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lightningGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val flashPaint = Paint()
    private val lightningPath = Path()
    private val lightningBranchPath = Path()

    // 左右 130px 黑边渐变遮罩
    private val vignetteLeftPaint = Paint()
    private val vignetteRightPaint = Paint()
    private val vignetteLeftRect = RectF(0f, 0f, SAFE_MARGIN_PX, LOGICAL_HEIGHT)
    private val vignetteRightRect = RectF(SAFE_RIGHT_START_PX, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)

    // 左侧 HUD 与中央巨幕数字时钟画笔 (字号设为 298px，等效原拉伸高度，恢复 1:1 正常字体比例)
    private val clockTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 298f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private var clockBaselineY: Float = 252f

    // 记录左侧天气 HUD 文字框最左侧 X 坐标 (供左下角微型档位悬浮槽等组件严格对齐)
    var weatherHudLeftX: Float = 185f
        private set

    // ==========================================
    // 中央大时间液态玻璃渲染引擎 (Liquid Glass Clock)
    // 28px 加大背景模糊 / 170% 高动态饱和度 / 10% 白色微光叠层 / 1px 厚度内浮雕 / 晶体折射轮廓
    // ==========================================
    private var isLiquidGlassEnabled: Boolean = false
    private var liquidGlassProgress: Float = 0f
    private var liquidGlassAnimator: ValueAnimator? = null

    // 离线微缓冲 (Downsample 4x: 220 × 75 px，覆盖中央 880 × 300 区域，完全包覆 298px 字号)
    private val GLASS_BUFFER_WIDTH = 220
    private val GLASS_BUFFER_HEIGHT = 75
    private val GLASS_DOWNSAMPLE = 4f
    private val glassCropRect = RectF(
        CLOCK_CENTER_X - (GLASS_BUFFER_WIDTH * GLASS_DOWNSAMPLE) / 2f,
        (LOGICAL_HEIGHT / 2f) - (GLASS_BUFFER_HEIGHT * GLASS_DOWNSAMPLE) / 2f,
        CLOCK_CENTER_X + (GLASS_BUFFER_WIDTH * GLASS_DOWNSAMPLE) / 2f,
        (LOGICAL_HEIGHT / 2f) + (GLASS_BUFFER_HEIGHT * GLASS_DOWNSAMPLE) / 2f
    )
    private val glassSrcRect = Rect(0, 0, GLASS_BUFFER_WIDTH, GLASS_BUFFER_HEIGHT)

    private val glassBgBitmap: Bitmap by lazy {
        Bitmap.createBitmap(GLASS_BUFFER_WIDTH, GLASS_BUFFER_HEIGHT, Bitmap.Config.ARGB_8888)
    }
    private val glassBgCanvas: Canvas by lazy {
        Canvas(glassBgBitmap)
    }
    private val glassPixels = IntArray(GLASS_BUFFER_WIDTH * GLASS_BUFFER_HEIGHT)
    private val glassPixelsTemp = IntArray(GLASS_BUFFER_WIDTH * GLASS_BUFFER_HEIGHT)

    // 文字 Path 矢量轮廓与缓存判断
    private val glassTextPath = Path()
    private val glassTextBounds = RectF()
    private var glassCachedTimeForPath = ""

    // 液态玻璃模糊降频与 Shader 缓存 (防每帧 CPU 软模糊及每帧 new LinearGradient)
    private var lastGlassBlurUpdateTime = 0L
    private var glassCachedTimeForShaders: String? = null
    private var lastGlassAlphaForShaders = -1f
    private val glassShineColors = IntArray(3)
    private val glassShineStops = floatArrayOf(0f, 0.45f, 1f)
    private val glassRefractionColors = IntArray(4)
    private val glassRefractionStops = floatArrayOf(0f, 0.35f, 0.70f, 1f)

    // 饱和度 170% 滤镜 (ColorMatrix.setSaturation(1.70f))
    private val glassSaturationMatrix = ColorMatrix().apply { setSaturation(1.70f) }
    private val glassBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(glassSaturationMatrix)
        isDither = true
    }

    // 叠一层白色（rgb255 255 255 / 10% = 26/255）
    private val glassOverlayPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(26, 255, 255, 255)
    }

    // 液态微光表面曲率高光渐变 (Liquid Crystal Surface Shine)
    private val glassSurfaceShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 文字边缘厚度：inet 1px 1px #fff8 (Inset 1px 1px Light Bevel)
    private val glassInsetLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.parseColor("#88FFFFFF") // #fff8
    }

    // 边缘加上折射：全曲率晶体折射描边 (Fresnel Refraction Edge)
    private val glassRefractionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }

    // 边缘加上折射：外围高折射漫射光晕 (Refraction Halo Glow)
    private val glassOuterRefractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.TRANSPARENT
    }

    fun setLiquidGlassEnabled(enabled: Boolean, smooth: Boolean = true) {
        if (isLiquidGlassEnabled == enabled && (liquidGlassProgress == 1f || liquidGlassProgress == 0f)) return
        isLiquidGlassEnabled = enabled
        liquidGlassAnimator?.cancel()
        if (!smooth) {
            liquidGlassProgress = if (enabled) 1f else 0f
            invalidate()
            return
        }
        val target = if (enabled) 1f else 0f
        liquidGlassAnimator = ValueAnimator.ofFloat(liquidGlassProgress, target).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                liquidGlassProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun isLiquidGlassClockActive(): Boolean = isLiquidGlassEnabled

    private val tempNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    private val weatherLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
    }

    private val weatherSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#F1F5F9")
        setShadowLayer(7f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    private val dateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
    }

    private val dateSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#E2E8F0")
        setShadowLayer(7f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
    }

    // ==========================================
    // 车载通话状态与高阶物理轨迹无缝转场动画引擎
    // ==========================================
    private var callInfo: CarCallManager.CallInfo = CarCallManager.CallInfo()
    private var previousCallState: CarCallManager.CallState = CarCallManager.CallState.IDLE
    private var callTransitionProgress: Float = 0f
    private val callTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 350L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            callTransitionProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private var callMotionStartTime: Long = 0L
    private val callMotionDuration: Long = 480L // 480ms 极佳物理阻尼过渡时长

    // 听筒手柄无缝运动连续物理量 (实时插值，保证 60FPS 绝对平滑零跳帧)
    private var handsetAnimStartRotation = 0f
    private var handsetAnimStartDx = 0f
    private var handsetAnimStartDy = 0f
    private var handsetAnimStartScale = 1.0f

    private var handsetCurrentRotation = 0f
    private var handsetCurrentDx = 0f
    private var handsetCurrentDy = 0f
    private var handsetCurrentScale = 1.0f

    // 状态文字语义平滑滑动切换 (Slide & Cross-fade)
    private var lastDrawnStatusText = ""
    private var previousStatusText = ""
    private var statusTextTransitionStartTime = 0L

    private val callIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val callLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val callMatrix = Matrix()
    private val callLayerMatrix = Matrix()

    private val callNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(10f, 0f, 2f, Color.argb(220, 0, 0, 0))
    }

    private val callSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#E2E8F0")
        setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    fun setCallInfo(info: CarCallManager.CallInfo) {
        val oldState = callInfo.state
        val newState = info.state

        if (oldState != newState) {
            previousCallState = oldState
            callMotionStartTime = SystemClock.uptimeMillis()
            // 锚定当前瞬时物理值作为过渡初值，杜绝跳跃
            handsetAnimStartRotation = handsetCurrentRotation
            handsetAnimStartDx = handsetCurrentDx
            handsetAnimStartDy = handsetCurrentDy
            handsetAnimStartScale = handsetCurrentScale

            if (newState == CarCallManager.CallState.IDLE) {
                lastDrawnStatusText = ""
                previousStatusText = ""
                statusTextTransitionStartTime = 0L
            }
        }

        callInfo = info
        if (info.isCallActive) {
            resumeAnimation()
            if (callTransitionProgress < 1f && !callTransitionAnimator.isRunning) {
                callTransitionAnimator.setFloatValues(callTransitionProgress, 1f)
                callTransitionAnimator.start()
            }
        } else {
            if (callTransitionProgress > 0f && !callTransitionAnimator.isRunning) {
                callTransitionAnimator.setFloatValues(callTransitionProgress, 0f)
                callTransitionAnimator.start()
            }
        }
        invalidate()
    }

    // ==========================================
    // 五行居中歌词与小时间动态切换状态
    // ==========================================
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentLyricsPositionMs: Long = 0L
    private var activeLineIndex: Int = -1
    private var animatedLineIndex: Float = 0f
    private var lineActiveStartTimeMs: Long = 0L
    private var lyricsModeProgress: Float = 0f
    private var isLyricsActive: Boolean = false
    private var xiaoOuAvoidanceEnabled: Boolean = true
    private var safeHalfWidth: Float = 425f

    // 迁移至左侧天气上方的大气小时间画笔 (使用粗体，白字带微阴影，字号定为 72px，与下方天气保持宽裕呼吸感)
    private val smallTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    // 实时环境感知舒适度高亮画笔 (根据温度从极冷到燥热体现色彩)
    private val comfortLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.03f
        textAlign = Paint.Align.LEFT
        setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    // 方案 A：降水预警动态拟态发光胶囊画笔 (柔和科技青蓝 #00E5FF 15% Alpha + #38BDF8 发光字)
    private val rainPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(Color.parseColor("#00E5FF"), (255 * 0.18f).toInt())
    }
    private val rainPillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = Color.parseColor("#38BDF8")
        textAlign = Paint.Align.LEFT
        setShadowLayer(6f, 0f, 1f, ColorUtils.setAlphaComponent(Color.parseColor("#38BDF8"), 160))
    }

    private fun getComfortLevel(temp: Float): ComfortLevel {
        return when {
            temp < 0f -> ComfortLevel("极冷", Color.parseColor("#38BDF8"))   // 冰晶蓝 (<0°C 严寒冰结)
            temp < 10f -> ComfortLevel("冷", Color.parseColor("#7DD3FC"))     // 冰川蓝 (0~10°C 寒凉冰冷)
            temp < 18f -> ComfortLevel("微凉", Color.parseColor("#5EEAD4"))   // 薄荷青 (10~18°C 清爽偏凉)
            temp <= 24f -> ComfortLevel("舒适", Color.parseColor("#4ADE80"))  // 柔和翠绿 (18~24°C 人体黄金舒适)
            temp <= 28f -> ComfortLevel("温热", Color.parseColor("#FBBF24"))  // 晨光金黄 (24~28°C 温暖微热)
            temp <= 33f -> ComfortLevel("燥热", Color.parseColor("#FB923C"))  // 燥热橙红 (28~33°C 闷热燥热)
            else -> ComfortLevel("酷热", Color.parseColor("#F87171"))         // 烈日赤红 (>33°C 极度燥热)
        }
    }

    // 五行居中歌词动态绘制画笔 (复用零 GC)
    private val lyricDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val lyricFontMetrics = Paint.FontMetrics()
    private val typefaceMediumBold = com.car.xiaoouscreen.util.HmiFontManager.getSystemMediumBold()
    private val typefaceMediumNormal = com.car.xiaoouscreen.util.HmiFontManager.getSystemMedium()

    // 模式平滑转场动画器 (大时钟 <-> 小时间+五行歌词)
    private val lyricsModeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 500L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            lyricsModeProgress = it.animatedValue as Float
            invalidate()
        }
    }

    // 歌词逐句连续浮点滚动与字号缩放丝滑过渡动画器 (统一 750ms，配合平滑 S 型缓动曲线，肉眼清晰可见字号缩放)
    private val lineScrollAnimator = ValueAnimator().apply {
        duration = 750L
        interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)
        addUpdateListener {
            animatedLineIndex = it.animatedValue as Float
            invalidate()
        }
    }

    private fun getSlotY(v: Float): Float {
        val absV = abs(v)
        val dist = when {
            absV <= 1f -> absV * LINE_SPACING_CENTER_TO_MID
            absV <= 2f -> LINE_SPACING_CENTER_TO_MID + (absV - 1f) * LINE_SPACING_MID_TO_EDGE
            else -> LINE_SPACING_CENTER_TO_MID + LINE_SPACING_MID_TO_EDGE + (absV - 2f) * 44f
        }
        return (LOGICAL_HEIGHT / 2f) + if (v >= 0f) dist else -dist
    }

    private fun getSlotTextSize(absV: Float): Float {
        return when {
            absV <= 1f -> lerp(92f, 36f, absV)
            absV <= 2f -> lerp(36f, 22f, absV - 1f)
            else -> lerp(22f, 14f, (absV - 2f).coerceIn(0f, 1f))
        }
    }

    private fun getSlotAlpha(absV: Float): Float {
        return when {
            absV <= 1f -> lerp(1.0f, 0.60f, absV)
            absV <= 2f -> lerp(0.60f, 0.28f, absV - 1f)
            else -> lerp(0.28f, 0.0f, (absV - 2f).coerceIn(0f, 1f))
        }
    }

    // 时间走时复用容器
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MM月dd日  EEEE", Locale.CHINESE)
    private val dateReusable = Date()
    private var cachedTimeStr: String = "12:00"
    private var cachedDateStr: String = "10月21日  星期三"
    private var lastClockSecond = -1L

    // ==========================================
    // 粒子结构体与常驻对象池
    // ==========================================
    private class RainDrop {
        var x = 0f
        var y = 0f
        var length = 0f
        var speedY = 0f
        var alpha = 0f
        var isForeground = false
    }

    private class SnowFlake {
        var x = 0f
        var y = 0f
        var radius = 0f
        var speedY = 0f
        var swayAmplitude = 0f
        var swaySpeed = 0f
        var swayPhase = 0f
        var alpha = 0f
    }

    private class Star {
        var x = 0f
        var y = 0f
        var radius = 0f
        var baseAlpha = 0f
        var twinkleSpeed = 0f
        var phase = 0f
        var color = Color.WHITE
    }

    private val rainPool = Array(MAX_RAIN_PARTICLES) { RainDrop() }
    private val snowPool = Array(MAX_SNOW_PARTICLES) { SnowFlake() }
    private val starPool = Array(MAX_STARS) { Star() }

    // 雷暴电闪控制状态
    private var lightningActive = false
    private var lightningStartTime = 0L
    private var lightningDurationMs = 280L
    private var nextLightningTriggerTime = 0L
    private var lightningIntensity = 0f
    private val lightningPointsX = FloatArray(8)
    private val lightningPointsY = FloatArray(8)
    private val branchPointsX = FloatArray(5)
    private val branchPointsY = FloatArray(5)

    // ==========================================
    // 顶部居中常驻音乐播放信息胶囊 (纯原生 1920×384 黄金比例自适应)
    // ==========================================
    private val topMusicBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000.toInt()
        style = Paint.Style.FILL
    }
    private val topMusicStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val topMusicTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(3.5f, 0f, 1.5f, 0xE6000000.toInt())
    }
    private val topMusicIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var topMusicIconBmp: Bitmap? = null
    private val topMusicCapsuleRect = RectF()

    private var topMusicTitle: String = ""
    private var topMusicArtist: String = ""
    private var topMusicDisplayText: String = ""
    private var isTopMusicPlaying: Boolean = false
    private var topMusicAlpha: Float = 0f
    private var topMusicTargetAlpha: Float = 0f
    private var topMusicAlphaAnimator: ValueAnimator? = null

    init {
        val fm = clockTimePaint.fontMetrics
        clockBaselineY = (LOGICAL_HEIGHT / 2f) - (fm.descent + fm.ascent) / 2f

        initVignetteShaders()
        initDividerShader()
        initCelestialAndCloudBitmaps()
        initParticlePools()
        initStars()
        initTopMusicIsland()

        try {
            isLiquidGlassEnabled = PrefsManager.isLiquidGlassClockEnabled()
            liquidGlassProgress = if (isLiquidGlassEnabled) 1f else 0f
        } catch (_: Throwable) {
            isLiquidGlassEnabled = false
            liquidGlassProgress = 0f
        }
    }

    private fun initTopMusicIsland() {
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_music_note)
            if (drawable != null) {
                val bmp = Bitmap.createBitmap(22, 22, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                drawable.setBounds(0, 0, 22, 22)
                drawable.draw(c)
                topMusicIconBmp = bmp
            }
        } catch (t: Throwable) {
            Log.e(TAG, "加载顶部音乐胶囊图标失败: ${t.message}")
        }
    }

    private fun initVignetteShaders() {
        vignetteLeftPaint.shader = LinearGradient(
            0f, 0f, SAFE_MARGIN_PX, 0f,
            Color.BLACK, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        vignetteRightPaint.shader = LinearGradient(
            SAFE_RIGHT_START_PX, 0f, LOGICAL_WIDTH, 0f,
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )
    }

    private fun initDividerShader() {
        dividerPaint.shader = LinearGradient(
            HUD_DIVIDER_X, 70f, HUD_DIVIDER_X, 314f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(120, 148, 163, 184),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun initCelestialAndCloudBitmaps() {
        // 移至后台工作线程并发解码天体与体积云，彻底释放主线程冷启负荷 (立省 ~1500ms)
        Thread {
            try {
                // 天体素材 (共享单份)
                bmpSun = SharedHmiAssetManager.getCelestialBitmap(resources, "sun")
                bmpSunRays = SharedHmiAssetManager.getCelestialBitmap(resources, "sun_rays")
                bmpSunFlare = SharedHmiAssetManager.getCelestialBitmap(resources, "sun_flare")
                bmpMoon = SharedHmiAssetManager.getCelestialBitmap(resources, "moon")

                // iOS 风格 5 层体积云 (共享单份)
                bmpCirrus = SharedHmiAssetManager.getCloudBitmap(resources, "cirrus")
                bmpCumulusBack = SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_back")
                bmpCumulusFront = SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_front")
                bmpMist = SharedHmiAssetManager.getCloudBitmap(resources, "mist")
                bmpOvercast = SharedHmiAssetManager.getCloudBitmap(resources, "overcast")

                postInvalidate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    @Volatile
    private var isCallBitmapsLoaded = false

    /**
     * 按需懒加载车载接打电话分层位图 (仅在真实发生通话呼入/呼出时才解码，杜绝冷启动无谓消耗)
     */
    private fun ensureCallBitmapsLoaded() {
        if (isCallBitmapsLoaded) return
        synchronized(this) {
            if (isCallBitmapsLoaded) return
            try {
                bmpCallIncomingHandset = SharedHmiAssetManager.getCallBitmap(resources, "incoming_handset")
                bmpCallIncomingArrow = SharedHmiAssetManager.getCallBitmap(resources, "incoming_arrow")
                bmpCallIncomingRipple = SharedHmiAssetManager.getCallBitmap(resources, "incoming_ripple")
                bmpCallOutgoingHandset = SharedHmiAssetManager.getCallBitmap(resources, "outgoing_handset")
                bmpCallOutgoingArrow = SharedHmiAssetManager.getCallBitmap(resources, "outgoing_arrow")
                bmpCallOutgoingWave1 = SharedHmiAssetManager.getCallBitmap(resources, "outgoing_wave1")
                bmpCallOutgoingWave2 = SharedHmiAssetManager.getCallBitmap(resources, "outgoing_wave2")
                bmpCallConnectedHandset = SharedHmiAssetManager.getCallBitmap(resources, "connected_handset")
                bmpCallConnectedWave1 = SharedHmiAssetManager.getCallBitmap(resources, "connected_wave1")
                bmpCallConnectedWave2 = SharedHmiAssetManager.getCallBitmap(resources, "connected_wave2")
                bmpCallConnectedWave3 = SharedHmiAssetManager.getCallBitmap(resources, "connected_wave3")
                bmpCallHangupHandset = SharedHmiAssetManager.getCallBitmap(resources, "hangup_handset")
                bmpCallHangupHalo = SharedHmiAssetManager.getCallBitmap(resources, "hangup_halo")
                bmpCallWeChatBack = SharedHmiAssetManager.getCallBitmap(resources, "wechat_back")
                bmpCallWeChatFront = SharedHmiAssetManager.getCallBitmap(resources, "wechat_front")
                bmpCallWeChatWave = SharedHmiAssetManager.getCallBitmap(resources, "wechat_wave")
                isCallBitmapsLoaded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initParticlePools() {
        for (drop in rainPool) {
            resetRainDrop(drop, initialSpread = true)
        }
        for (snow in snowPool) {
            resetSnowFlake(snow, initialSpread = true)
        }
    }

    private fun resetRainDrop(drop: RainDrop, initialSpread: Boolean = false) {
        drop.x = random.nextFloat() * (LOGICAL_WIDTH + 400f) - 200f
        drop.y = if (initialSpread) random.nextFloat() * LOGICAL_HEIGHT else -(random.nextFloat() * 80f + 20f)
        drop.isForeground = random.nextBoolean()
        if (drop.isForeground) {
            drop.length = random.nextFloat() * 32f + 20f
            drop.speedY = random.nextFloat() * 800f + 1100f
            drop.alpha = random.nextFloat() * 0.35f + 0.45f
        } else {
            drop.length = random.nextFloat() * 18f + 12f
            drop.speedY = random.nextFloat() * 500f + 700f
            drop.alpha = random.nextFloat() * 0.20f + 0.15f
        }
    }

    private fun resetSnowFlake(snow: SnowFlake, initialSpread: Boolean = false) {
        snow.x = random.nextFloat() * (LOGICAL_WIDTH + 200f) - 100f
        snow.y = if (initialSpread) random.nextFloat() * LOGICAL_HEIGHT else -(random.nextFloat() * 40f + 10f)
        val isBig = random.nextFloat() < 0.25f
        snow.radius = if (isBig) random.nextFloat() * 3.2f + 2.4f else random.nextFloat() * 1.8f + 0.9f
        snow.speedY = if (isBig) random.nextFloat() * 75f + 60f else random.nextFloat() * 45f + 30f
        snow.swayAmplitude = random.nextFloat() * 26f + 8f
        snow.swaySpeed = random.nextFloat() * 2.0f + 1.0f
        snow.swayPhase = random.nextFloat() * 6.28f
        snow.alpha = if (isBig) 0.85f else 0.5f
    }

    private fun initStars() {
        val starColors = intArrayOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#E0F2FE"),
            Color.parseColor("#FEF3C7"),
            Color.parseColor("#F1F5F9")
        )
        for (s in starPool) {
            s.x = random.nextFloat() * (LOGICAL_WIDTH - 280f) + 140f
            s.y = random.nextFloat() * (LOGICAL_HEIGHT * 0.65f)
            s.radius = random.nextFloat() * 1.5f + 0.6f
            s.baseAlpha = random.nextFloat() * 0.55f + 0.35f
            s.twinkleSpeed = random.nextFloat() * 2.8f + 1.2f
            s.phase = random.nextFloat() * 6.28f
            s.color = starColors[random.nextInt(starColors.size)]
        }
    }

    // ==========================================
    // 场景更新与平滑过渡 (Cross-Fade)
    // ==========================================
    private fun isVisuallyEqual(a: WeatherSceneConfig, b: WeatherSceneConfig): Boolean {
        return a.weatherState == b.weatherState &&
                a.timeOfDay == b.timeOfDay &&
                a.intensity == b.intensity &&
                a.skyTopColor == b.skyTopColor &&
                a.skyBottomColor == b.skyBottomColor &&
                kotlin.math.abs(a.cloudOvercastAlpha - b.cloudOvercastAlpha) < 0.01f &&
                kotlin.math.abs(a.cloudCirrusAlpha - b.cloudCirrusAlpha) < 0.01f &&
                a.minuteRainSummary == b.minuteRainSummary
    }

    private fun updateHudTextCacheIfNeeded() {
        if (hudDataDirty) {
            hudDataDirty = false
            cachedHudTempText = "${currentConfig.temperatureCelsius.toInt()}°"
            cachedHudWeatherText = currentConfig.weatherDescription
            val windDir = currentConfig.windDirectionText
            val windScale = currentConfig.windScaleText
            cachedHudSubInfoText = if (windScale.isNotBlank()) {
                "$windDir $windScale · 体感 ${currentConfig.feelsLikeCelsius.toInt()}°"
            } else {
                "风速 ${currentConfig.windSpeedKmh.toInt()} km/h · 优"
            }
            cachedHudComfort = getComfortLevel(currentConfig.temperatureCelsius)
            cachedHudHasValidRainAlert = currentConfig.isMinuteRainActive &&
                !currentConfig.minuteRainSummary.isNullOrBlank() &&
                currentConfig.minuteRainSummary != "未来两小时无降水"
            cachedHudRainText = if (cachedHudHasValidRainAlert) "🌧️ ${currentConfig.minuteRainSummary}" else ""

            cachedHudWeatherWidth = weatherLabelPaint.measureText(cachedHudWeatherText)
            cachedHudSubInfoWidth = weatherSubPaint.measureText(cachedHudSubInfoText)
            cachedHudPrefixWidth = dateSubPaint.measureText(cachedHudPrefixText)
            cachedHudComfortWidth = comfortLabelPaint.measureText(cachedHudComfort.statusText)
            cachedHudRainWidth = if (cachedHudHasValidRainAlert) dateSubPaint.measureText(cachedHudRainText) else 0f
            cachedHudNoteWidth = if (cachedHudHasValidRainAlert) {
                cachedHudRainWidth
            } else {
                cachedHudPrefixWidth + cachedHudComfortWidth
            }
        }
        if (cachedDateStr != lastMeasuredDateStr) {
            lastMeasuredDateStr = cachedDateStr
            cachedHudDateWidth = dateLabelPaint.measureText(cachedDateStr)
        }
    }

    fun updateScene(newConfig: WeatherSceneConfig, animate: Boolean = true) {
        hudDataDirty = true
        if (isVisuallyEqual(newConfig, currentConfig)) {
            currentConfig = newConfig
            return
        }

        val isFirstRealUpdate = (previousConfig == WeatherSceneConfig.DEFAULT && currentConfig == WeatherSceneConfig.DEFAULT)
        if (!animate || isFirstRealUpdate) {
            transitionAnimator.cancel()
            previousConfig = newConfig
            currentConfig = newConfig
            transitionProgress = 1.0f
        } else {
            previousConfig = currentConfig
            currentConfig = newConfig
            transitionAnimator.cancel()
            transitionAnimator.start()
        }

        if (newConfig.lightningEnabled) {
            scheduleNextLightning()
        }
        invalidate()
        onSceneRenderUpdated?.invoke()
    }

    fun triggerInstantLightning() {
        triggerLightning(SystemClock.uptimeMillis())
        invalidate()
    }

    private fun scheduleNextLightning() {
        val delayMs = random.nextInt(4500) + 3000L
        nextLightningTriggerTime = SystemClock.uptimeMillis() + delayMs
    }

    // ==========================================
    // 生命周期与能耗休眠
    // ==========================================
    fun pauseAnimation() {
        isPausedManually = true
        stopAnimation()
    }

    fun resumeAnimation() {
        isPausedManually = false
        if (visibility == VISIBLE) {
            if (!isRenderingActive) {
                startAnimation()
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        evaluateRenderState()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        evaluateRenderState()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        evaluateRenderState()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        evaluateRenderState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        evaluateRenderState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        evaluateRenderState()
        invalidate()
    }

    private fun evaluateRenderState() {
        if (isPausedManually) {
            if (isRenderingActive) stopAnimation()
            return
        }
        // 严格确保 View 已经附着在 Window 上且自身为 VISIBLE，防止游离 View 或未挂载时无谓启动帧循环
        val shouldRender = isAttachedToWindow && (visibility == VISIBLE)
        if (shouldRender && !isRenderingActive) {
            startAnimation()
            invalidate()
        } else if (!shouldRender && isRenderingActive) {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        isRenderingActive = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopAnimation() {
        isRenderingActive = false
        lastRenderTimeNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private var lastRenderTimeNanos = 0L

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRenderingActive) return

        // 车规级能效自适应：高动态粒子/转场维持 60fps，晴天无粒子稳态自适应降至 30fps，削减 50% GPU 负载与发热
        val isHighDynamic = (currentConfig.rainParticleCount > 0) ||
                            (currentConfig.snowParticleCount > 0) ||
                            lightningActive ||
                            (transitionProgress < 1.0f) ||
                            (lyricsModeProgress > 0f && lyricsModeProgress < 1f)
        val minIntervalNanos = if (isHighDynamic) 14_000_000L else 30_000_000L

        if (lastRenderTimeNanos != 0L && (frameTimeNanos - lastRenderTimeNanos) < minIntervalNanos) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }

        if (lastFrameTimeNanos != 0L) {
            val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
            val clampedDelta = deltaSeconds.coerceIn(0.001f, 0.05f)
            updatePhysics(clampedDelta)
        }
        lastFrameTimeNanos = frameTimeNanos
        lastRenderTimeNanos = frameTimeNanos

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ==========================================
    // 物理动效步进
    // ==========================================
    private fun updatePhysics(dt: Float) {
        val now = SystemClock.uptimeMillis()

        // 1. 每秒更新时钟字符串 (极低 CPU 占用)
        val nowUnixSeconds = System.currentTimeMillis() / 1000L
        if (nowUnixSeconds != lastClockSecond) {
            lastClockSecond = nowUnixSeconds
            dateReusable.time = System.currentTimeMillis()
            cachedTimeStr = timeFormat.format(dateReusable)
            cachedDateStr = dateFormat.format(dateReusable)
        }

        // 2. iOS 风格 5 层视差体积云：拟真风速驱动与防眩晕视差漂移
        // 优化：重塑微风呼吸感，大幅降低基础怠速，低风速段强化线性反差 (3km/h 与 13km/h 达到 3 倍真实反差)，高风速段平滑收敛防眩晕
        val windSpeedSafe = currentConfig.windSpeedKmh.coerceAtLeast(0f)
        val windFactor = when {
            windSpeedSafe <= 15f -> 0.6f + windSpeedSafe * 0.30f
            else -> 5.1f + sqrt(windSpeedSafe - 15f) * 1.8f
        }.coerceIn(0.5f, 15.0f)

        scrollCirrusX += 1.2f * windFactor * dt
        scrollCumulusBackX += 2.2f * windFactor * dt
        scrollCumulusFrontX += 4.0f * windFactor * dt
        scrollMistX += 1.6f * windFactor * dt
        scrollOvercastX += 3.0f * windFactor * dt

        // 车规级长周期浮点防护：对纹理固有宽度取模，保证 scrollX 严格处于 [0, texW) 区间，长年保活永不发生精度损失
        bmpCirrus?.takeIf { !it.isRecycled }?.width?.toFloat()?.let { w -> if (scrollCirrusX >= w) scrollCirrusX %= w }
        bmpCumulusBack?.takeIf { !it.isRecycled }?.width?.toFloat()?.let { w -> if (scrollCumulusBackX >= w) scrollCumulusBackX %= w }
        bmpCumulusFront?.takeIf { !it.isRecycled }?.width?.toFloat()?.let { w -> if (scrollCumulusFrontX >= w) scrollCumulusFrontX %= w }
        bmpMist?.takeIf { !it.isRecycled }?.width?.toFloat()?.let { w -> if (scrollMistX >= w) scrollMistX %= w }
        bmpOvercast?.takeIf { !it.isRecycled }?.width?.toFloat()?.let { w -> if (scrollOvercastX >= w) scrollOvercastX %= w }

        // 3. 雨滴推进 (支持雨夹雪同时运行)
        val rainCount = currentConfig.rainParticleCount
        if (rainCount > 0) {
            val windOffsetSpeedX = currentConfig.windSpeedKmh * 8.5f
            for (i in 0 until rainCount) {
                val drop = rainPool[i]
                drop.y += drop.speedY * dt
                drop.x += windOffsetSpeedX * dt
                if (drop.y > LOGICAL_HEIGHT + 20f || drop.x > LOGICAL_WIDTH + 200f) {
                    resetRainDrop(drop)
                }
            }
        }

        // 4. 雪花推进 (支持雨夹雪同时运行)
        val snowCount = currentConfig.snowParticleCount
        if (snowCount > 0) {
            for (i in 0 until snowCount) {
                val snow = snowPool[i]
                snow.y += snow.speedY * dt
                snow.swayPhase += snow.swaySpeed * dt
                if (snow.y > LOGICAL_HEIGHT + 10f) {
                    resetSnowFlake(snow)
                }
            }
        }

        // 5. 闪电推进
        if (currentConfig.lightningEnabled || lightningActive) {
            if (!lightningActive && now >= nextLightningTriggerTime) {
                triggerLightning(now)
            } else if (lightningActive) {
                val elapsed = now - lightningStartTime
                if (elapsed >= lightningDurationMs) {
                    lightningActive = false
                    lightningIntensity = 0f
                    scheduleNextLightning()
                } else {
                    val progress = (elapsed.toFloat() / lightningDurationMs).coerceIn(0f, 1f)
                    val decay = (1f - progress)
                    // 车载夜间视线保护：采用平滑二次方自然衰减，杜绝突变硬闪与强频闪
                    lightningIntensity = decay * decay
                }
            }
        } else {
            lightningActive = false
            lightningIntensity = 0f
        }
    }

    private fun triggerLightning(now: Long) {
        lightningActive = true
        lightningStartTime = now
        lightningDurationMs = random.nextInt(180) + 200L
        lightningIntensity = 1.0f

        var startX = CELESTIAL_CENTER_X + (random.nextFloat() * 120f - 60f)
        var startY = 10f
        lightningPointsX[0] = startX
        lightningPointsY[0] = startY

        val stepY = (LOGICAL_HEIGHT * 0.82f) / 7f
        for (i in 1..7) {
            startX += (random.nextFloat() * 70f - 35f)
            startY += stepY + (random.nextFloat() * 14f - 7f)
            lightningPointsX[i] = startX
            lightningPointsY[i] = startY
        }

        branchPointsX[0] = lightningPointsX[3]
        branchPointsY[0] = lightningPointsY[3]
        var bx = branchPointsX[0]
        var by = branchPointsY[0]
        for (j in 1..4) {
            bx += (random.nextFloat() * 45f + 15f)
            by += (stepY * 0.75f)
            branchPointsX[j] = bx
            branchPointsY[j] = by
        }
    }

    // ==========================================
    // 核心绘制流水线 (1920 × 384 统一视口)
    // ==========================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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

        // 第 1 层：写实天空 4-Stop 瑞利散射自然渐变
        drawPhotorealisticSky(canvas)

        // 第 2 层：写实天体 (HDR 太阳 + 宽银幕耀斑 / 天文级真实月球 / 星空)
        drawPhotorealisticCelestial(canvas)

        // 第 3 层：iOS 风格 5 层物理无缝体积云层
        drawPhotorealisticClouds(canvas)

        // 第 4 层：物理天气粒子 (雨丝 / 飞雪 / 雨夹雪混落 / 分形电闪)
        drawWeatherParticles(canvas)

        // 第 5 层：左侧信息枢纽 HUD (温度、天气、日期) 与优雅纵向光轨
        drawLeftHudCluster(canvas)

        // 第 6 层：中央霸气时钟与五行居中歌词动态平滑切换 (方案 A 动态切换)
        drawHugeAtmosphericClock(canvas, 1f - lyricsModeProgress)
        drawFiveLineLyrics(canvas, lyricsModeProgress)

        // 第 7 层：顶部居中高亮音乐胶囊 (播放音乐时中心高亮展示，车规级 1920×384 黄金比例自适应)
        drawTopMusicIsland(canvas)

        // 第 8 层：左右 130px 物理安全黑边渐变融合遮罩 (最顶层)
        drawEdgeVignette(canvas)

        canvas.restore()
    }

    /**
     * 将写实天气与时钟绘制到指定的严格 1920×384 Canvas 上 (用于高精度快照合成)
     */
    fun drawStrict1920(c: Canvas) {
        c.save()
        // 确保时钟字符串为当前系统真实最新时间，即使在能耗暂停期间截图也能即时反映当前时刻
        val nowUnixSeconds = System.currentTimeMillis() / 1000L
        if (nowUnixSeconds != lastClockSecond) {
            lastClockSecond = nowUnixSeconds
            dateReusable.time = System.currentTimeMillis()
            cachedTimeStr = timeFormat.format(dateReusable)
            cachedDateStr = dateFormat.format(dateReusable)
        }

        c.clipRect(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        drawPhotorealisticSky(c)
        drawPhotorealisticCelestial(c)
        drawPhotorealisticClouds(c)
        drawWeatherParticles(c)
        drawLeftHudCluster(c)
        drawHugeAtmosphericClock(c, 1f - lyricsModeProgress)
        drawFiveLineLyrics(c, lyricsModeProgress)
        drawTopMusicIsland(c)
        c.restore()
    }

    /**
     * 截取纯净严格 1920×384 分辨率的屏幕快照
     * 消除宿主屏幕宽高比畸变、消除黑边错位，保证 3D 档位立方体翻转无缝咬合
     */
    fun captureStrict1920Snapshot(): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            drawStrict1920(c)
            // 快照不烘焙 drawEdgeVignette，交由原生 3D 棱柱/光刃视图 (GearCubeFlipView/DriveModeWipeView) 外层统一度量叠加，杜绝双重黑边及动画结束瞬间补丁感
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "截取严格 1920x384 快照失败: ${t.message}", t)
            null
        }
    }

    /**
     * 仅绘制写实天空、天体与体积云背景 (供液态玻璃 HUD/时钟进行局部光学采样与实时模糊)
     */
    fun drawWeatherBackgroundOnly(canvas: Canvas) {
        drawPhotorealisticSky(canvas)
        drawPhotorealisticCelestial(canvas)
        drawPhotorealisticClouds(canvas)
    }

    // ----------------------------------------------------
    // 第 1 层：写实天空渐变
    // ----------------------------------------------------
    private fun drawPhotorealisticSky(canvas: Canvas) {
        val topColor = lerpColor(previousConfig.skyTopColor, currentConfig.skyTopColor, transitionProgress)
        val bottomColor = lerpColor(previousConfig.skyBottomColor, currentConfig.skyBottomColor, transitionProgress)

        // 仅在颜色发生渐变时重建 Shader，稳态时 100% 零 GC 内存分配
        if (topColor != lastSkyTopColor || bottomColor != lastSkyBottomColor || cachedSkyShader == null) {
            lastSkyTopColor = topColor
            lastSkyBottomColor = bottomColor
            cachedSkyShader = LinearGradient(
                0f, 0f, 0f, LOGICAL_HEIGHT,
                topColor, bottomColor,
                Shader.TileMode.CLAMP
            )
            skyPaint.shader = cachedSkyShader
        }
        canvas.drawRect(skyRect, skyPaint)
    }

    // ----------------------------------------------------
    // 第 2 层：写实天体系统 (拒绝粗糙同心圆与剪切黑圆)
    // ----------------------------------------------------
    private fun drawPhotorealisticCelestial(canvas: Canvas) {
        val timeSec = SystemClock.uptimeMillis() / 1000f

        // 1. 写实星空 (深空多色温、非同步呼吸闪烁)
        if (currentConfig.starsVisible || previousConfig.starsVisible) {
            val starAlphaFactor = if (currentConfig.starsVisible) transitionProgress else (1f - transitionProgress)
            for (s in starPool) {
                val twinkle = (sin((timeSec * s.twinkleSpeed + s.phase).toDouble()).toFloat() * 0.5f + 0.5f)
                val alpha = (s.baseAlpha * twinkle * starAlphaFactor).coerceIn(0f, 1f)
                starPaint.color = s.color
                starPaint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(s.x, s.y, s.radius, starPaint)
            }
        }

        // 2. 太阳与月球 (位置移至用户红圈标注 X=410, Y=75；月亮顺时针旋转30度并与太阳视觉等大；受天气与时段影响)
        val prevVisible = previousConfig.celestialVisible
        val currVisible = currentConfig.celestialVisible
        if (prevVisible || currVisible) {
            val prevAlpha = if (prevVisible) (if (previousConfig.weatherState == WeatherState.CLOUDY) 0.82f else 1.0f) else 0.0f
            val currAlpha = if (currVisible) (if (currentConfig.weatherState == WeatherState.CLOUDY) 0.82f else 1.0f) else 0.0f
            val celestialAlpha = lerp(prevAlpha, currAlpha, transitionProgress)

            if (celestialAlpha > 0.005f) {
                val isNight = (currentConfig.timeOfDay == TimeOfDay.NIGHT)

                if (!isNight) {
                    // --- 拟真 HDR 太阳 (红圈视点 X=410, Y=75；本体尺寸 175px 显著大于月亮 120px；慢速微光呼吸) ---
                    val sunBaseSize = 175f
                    val breathing = sin((timeSec * 1.0f).toDouble()).toFloat() * 3.5f
                    val currentSunSize = sunBaseSize + breathing

                    sunDestRect.set(
                        CELESTIAL_CENTER_X - currentSunSize / 2f,
                        CELESTIAL_CENTER_Y - currentSunSize / 2f,
                        CELESTIAL_CENTER_X + currentSunSize / 2f,
                        CELESTIAL_CENTER_Y + currentSunSize / 2f
                    )

                    // 1. 大气日冕色温与微光晕 (零 GC 预设着色器与矩阵缩放)
                    val haloRadius: Float
                    val haloBaseAlpha: Int
                    val haloR: Int
                    val haloG: Int
                    val haloB: Int
                    val sunTint: Int?

                    when (currentConfig.timeOfDay) {
                        TimeOfDay.DAWN -> {
                            haloBaseAlpha = 45
                            haloR = 254; haloG = 215; haloB = 170
                            haloRadius = 105f + breathing
                            sunTint = COLOR_DAWN_TINT
                        }
                        TimeOfDay.DUSK -> {
                            haloBaseAlpha = 70
                            haloR = 251; haloG = 146; haloB = 60
                            haloRadius = 115f + breathing
                            sunTint = COLOR_DUSK_TINT
                        }
                        else -> {
                            haloBaseAlpha = 50
                            haloR = 254; haloG = 240; haloB = 160
                            haloRadius = 110f + breathing
                            sunTint = null
                        }
                    }

                    val finalHaloAlpha = (haloBaseAlpha * celestialAlpha).toInt().coerceIn(0, 255)
                    if (finalHaloAlpha > 0) {
                        if (lastSunHaloTimeOfDay != currentConfig.timeOfDay || cachedSunHaloShader == null) {
                            lastSunHaloTimeOfDay = currentConfig.timeOfDay
                            cachedSunHaloShader = RadialGradient(
                                CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, 120f,
                                Color.rgb(haloR, haloG, haloB),
                                Color.argb(0, haloR, haloG, haloB),
                                Shader.TileMode.CLAMP
                            )
                            sunHaloPaint.shader = cachedSunHaloShader
                        }
                        sunHaloPaint.alpha = finalHaloAlpha
                        val haloScale = haloRadius / 120f
                        sunHaloMatrix.setScale(haloScale, haloScale, CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y)
                        cachedSunHaloShader?.setLocalMatrix(sunHaloMatrix)
                        canvas.drawCircle(CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, haloRadius, sunHaloPaint)
                    }

                    // 2. 扩散光斑与发散光芒 (带 2D 余弦平滑渐变透明，绝无生硬切边与矩形分界)
                    // 时段区分：白天饱满生动双层逆向缓旋；清晨大幅削弱仅微弱单层晨光；黄昏完全移除扩散光斑保持落日沉静
                    val rayMultiplier = when (currentConfig.timeOfDay) {
                        TimeOfDay.DAY -> 1.0f
                        TimeOfDay.DAWN -> 0.15f
                        else -> 0.0f
                    }

                    if (rayMultiplier > 0.01f) {
                        val raysBmp = bmpSunRays?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCelestialBitmap(resources, "sun_rays")?.also { bmpSunRays = it }
                        if (raysBmp != null) {
                            // 正向慢速层 (约 150 秒自转一圈)
                            val r1Size = (135f + breathing * 1.5f) * 2f
                            sunRayRect.set(
                                CELESTIAL_CENTER_X - r1Size / 2f,
                                CELESTIAL_CENTER_Y - r1Size / 2f,
                                CELESTIAL_CENTER_X + r1Size / 2f,
                                CELESTIAL_CENTER_Y + r1Size / 2f
                            )
                            sunRayPaint.alpha = (celestialAlpha * rayMultiplier * 160).toInt()
                            canvas.save()
                            canvas.rotate((timeSec * 2.4f) % 360f, CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y)
                            canvas.drawBitmap(raysBmp, null, sunRayRect, sunRayPaint)
                            canvas.restore()

                            // 反向互质慢速层 (交错干涉营造自然真实日光光斑微漾，清晨仅保留单层以突显区别)
                            if (currentConfig.timeOfDay == TimeOfDay.DAY) {
                                val r2Size = (115f + breathing * 1.2f) * 2f
                                sunRayRect.set(
                                    CELESTIAL_CENTER_X - r2Size / 2f,
                                    CELESTIAL_CENTER_Y - r2Size / 2f,
                                    CELESTIAL_CENTER_X + r2Size / 2f,
                                    CELESTIAL_CENTER_Y + r2Size / 2f
                                )
                                sunRayPaint.alpha = (celestialAlpha * 110).toInt()
                                canvas.save()
                                canvas.rotate((-timeSec * 1.5f + 22.5f) % 360f, CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y)
                                canvas.drawBitmap(raysBmp, null, sunRayRect, sunRayPaint)
                                canvas.restore()
                            }
                        }
                    }

                    // 3. 太阳发光本体渲染 (复用经典圆润光球，覆盖于光芒中心)
                    val sunBmp = bmpSun?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCelestialBitmap(resources, "sun")?.also { bmpSun = it }
                    if (sunBmp != null) {
                        sunPaint.alpha = (celestialAlpha * 255).toInt()
                        if (cachedSunTintColor != sunTint || cachedSunColorFilter == null) {
                            cachedSunTintColor = sunTint
                            cachedSunColorFilter = sunTint?.let { PorterDuffColorFilter(it, PorterDuff.Mode.MULTIPLY) }
                            sunPaint.colorFilter = cachedSunColorFilter
                        }
                        canvas.drawBitmap(sunBmp, null, sunDestRect, sunPaint)
                    }

                } else {
                    // --- 天文级拟真缺月 (红圈视点 X=410, Y=75；顺时针旋转30度；精致小巧 120px) ---
                    val moonSize = 120f
                    moonDestRect.set(
                        CELESTIAL_CENTER_X - moonSize / 2f,
                        CELESTIAL_CENTER_Y - moonSize / 2f,
                        CELESTIAL_CENTER_X + moonSize / 2f,
                        CELESTIAL_CENTER_Y + moonSize / 2f
                    )

                    // 冷月柔和散射青辉 (平滑衰减，无色晕阶跃，Shader 缓存零 GC)
                    if (cachedMoonHaloShader == null) {
                        cachedMoonHaloShader = RadialGradient(
                            CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, 65f,
                            Color.rgb(56, 189, 248),
                            Color.argb(0, 56, 189, 248),
                            Shader.TileMode.CLAMP
                        )
                        moonHaloPaint.shader = cachedMoonHaloShader
                    }
                    moonHaloPaint.alpha = (35 * celestialAlpha).toInt().coerceIn(0, 255)
                    canvas.drawCircle(CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, 65f, moonHaloPaint)

                    // 真实缺月月牙渲染 (顺时针旋转 30 度预制，带环形山明暗与月海地貌)
                    val moonBmp = bmpMoon?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCelestialBitmap(resources, "moon")?.also { bmpMoon = it }
                    if (moonBmp != null) {
                        moonPaint.alpha = (celestialAlpha * 255).toInt()
                        canvas.drawBitmap(moonBmp, null, moonDestRect, moonPaint)
                    }
                }
            }
        }
    }

    // ----------------------------------------------------
    // 第 3 层：iOS 风格 5 层拟真无缝体积云渲染
    // ----------------------------------------------------
    private fun drawPhotorealisticClouds(canvas: Canvas) {
        val cirrusA = lerp(previousConfig.cloudCirrusAlpha, currentConfig.cloudCirrusAlpha, transitionProgress)
        val cumBackA = lerp(previousConfig.cloudCumulusBackAlpha, currentConfig.cloudCumulusBackAlpha, transitionProgress)
        val cumFrontA = lerp(previousConfig.cloudCumulusFrontAlpha, currentConfig.cloudCumulusFrontAlpha, transitionProgress)
        val mistA = lerp(previousConfig.cloudMistAlpha, currentConfig.cloudMistAlpha, transitionProgress)
        val overcastA = lerp(previousConfig.cloudOvercastAlpha, currentConfig.cloudOvercastAlpha, transitionProgress)

        var tint = lerpColor(previousConfig.cloudTintColor, currentConfig.cloudTintColor, transitionProgress)

        // 雷暴电闪瞬间激发云层内部高光散射
        if (lightningActive && lightningIntensity > 0.1f) {
            tint = ColorUtils.blendARGB(tint, Color.parseColor("#F5D0FE"), lightningIntensity * 0.85f)
        }

        if (cachedCloudColorFilter == null || cachedCloudTintColor != tint) {
            cachedCloudTintColor = tint
            val colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
            cachedCloudColorFilter = colorFilter
            cloudCirrusPaint.colorFilter = colorFilter
            cloudCumulusBackPaint.colorFilter = colorFilter
            cloudCumulusFrontPaint.colorFilter = colorFilter
            cloudMistPaint.colorFilter = colorFilter
            cloudOvercastPaint.colorFilter = colorFilter
        }

        val overcastBmp = bmpOvercast?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "overcast")?.also { bmpOvercast = it }
        val cirrusBmp = bmpCirrus?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cirrus")?.also { bmpCirrus = it }
        val cumBackBmp = bmpCumulusBack?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_back")?.also { bmpCumulusBack = it }
        val cumFrontBmp = bmpCumulusFront?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_front")?.also { bmpCumulusFront = it }
        val mistBmp = bmpMist?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "mist")?.also { bmpMist = it }

        drawSeamlessCloudLayer(canvas, overcastBmp, scrollOvercastX, overcastA, cloudOvercastPaint)
        drawSeamlessCloudLayer(canvas, cirrusBmp, scrollCirrusX, cirrusA, cloudCirrusPaint)
        drawSeamlessCloudLayer(canvas, cumBackBmp, scrollCumulusBackX, cumBackA, cloudCumulusBackPaint)
        drawSeamlessCloudLayer(canvas, cumFrontBmp, scrollCumulusFrontX, cumFrontA, cloudCumulusFrontPaint)
        drawSeamlessCloudLayer(canvas, mistBmp, scrollMistX, mistA, cloudMistPaint)
    }

    private fun drawSeamlessCloudLayer(
        canvas: Canvas,
        bitmap: Bitmap?,
        scrollX: Float,
        alpha: Float,
        paint: Paint
    ) {
        if (bitmap == null || bitmap.isRecycled || alpha <= 0.006f) return
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val texW = bitmap.width.toFloat()
        var startX = scrollX % texW
        if (startX > 0) startX -= texW

        var currX = startX
        while (currX < LOGICAL_WIDTH) {
            canvas.drawBitmap(bitmap, currX, 0f, paint)
            currX += texW
        }
    }

    // ----------------------------------------------------
    // 第 4 层：天气粒子 (雨丝 / 飞雪 / 雨夹雪混落 / 分形电闪)
    // ----------------------------------------------------
    private fun drawWeatherParticles(canvas: Canvas) {
        // 1. 降雨 (前景亮、背景虚)
        val rainCount = currentConfig.rainParticleCount
        if (rainCount > 0) {
            val windSlantX = (currentConfig.windSpeedKmh * 0.42f).coerceIn(-28f, 28f)
            rainFgPaint.color = Color.parseColor("#BAE6FD")
            rainFgPaint.strokeWidth = if (currentConfig.intensity == Intensity.HEAVY) 2.2f else 1.4f

            rainBgPaint.color = Color.parseColor("#7DD3FC")
            rainBgPaint.strokeWidth = 1.0f

            for (i in 0 until rainCount) {
                val drop = rainPool[i]
                if (drop.isForeground) {
                    rainFgPaint.alpha = (drop.alpha * 255).toInt()
                    canvas.drawLine(drop.x, drop.y, drop.x + windSlantX, drop.y + drop.length, rainFgPaint)
                } else {
                    rainBgPaint.alpha = (drop.alpha * 255).toInt()
                    canvas.drawLine(drop.x, drop.y, drop.x + windSlantX * 0.8f, drop.y + drop.length * 0.7f, rainBgPaint)
                }
            }
        }

        // 2. 降雪 (散焦景深雪片)
        val snowCount = currentConfig.snowParticleCount
        if (snowCount > 0) {
            snowPaint.color = Color.WHITE
            for (i in 0 until snowCount) {
                val snow = snowPool[i]
                val swayX = snow.x + sin(snow.swayPhase.toDouble()).toFloat() * snow.swayAmplitude
                snowPaint.alpha = (snow.alpha * 255).toInt()
                canvas.drawCircle(swayX, snow.y, snow.radius, snowPaint)
            }
        }

        // 3. 雷暴电闪
        if (lightningActive && lightningIntensity > 0f) {
            // 车载驾驶防致盲：全屏白闪限制在柔和微光 (最大 alpha 36，约 14% 透明度)，保留分形闪电枝干与紫色光晕
            val isNight = currentConfig.timeOfDay == TimeOfDay.NIGHT
            val maxFlashAlpha = if (isNight) 24 else 36
            flashPaint.color = Color.WHITE
            flashPaint.alpha = (lightningIntensity * maxFlashAlpha).toInt().coerceIn(0, maxFlashAlpha)
            canvas.drawRect(skyRect, flashPaint)

            lightningGlowPaint.color = Color.parseColor("#C084FC")
            lightningGlowPaint.strokeWidth = 14f
            lightningGlowPaint.alpha = (lightningIntensity * 180).toInt()

            lightningPaint.color = Color.WHITE
            lightningPaint.strokeWidth = 3.2f
            lightningPaint.alpha = (lightningIntensity * 255).toInt()

            lightningPath.reset()
            lightningPath.moveTo(lightningPointsX[0], lightningPointsY[0])
            for (i in 1..7) {
                lightningPath.lineTo(lightningPointsX[i], lightningPointsY[i])
            }
            canvas.drawPath(lightningPath, lightningGlowPaint)
            canvas.drawPath(lightningPath, lightningPaint)

            lightningBranchPath.reset()
            lightningBranchPath.moveTo(branchPointsX[0], branchPointsY[0])
            for (j in 1..4) {
                lightningBranchPath.lineTo(branchPointsX[j], branchPointsY[j])
            }
            lightningGlowPaint.strokeWidth = 8f
            lightningPaint.strokeWidth = 1.8f
            canvas.drawPath(lightningBranchPath, lightningGlowPaint)
            canvas.drawPath(lightningBranchPath, lightningPaint)
        }
    }

    // ----------------------------------------------------
    // 第 5 层：左侧信息枢纽 HUD (大字小时间、温度、天气、日期) 矩形组合与分割光轨
    // ----------------------------------------------------
    private fun drawLeftHudCluster(canvas: Canvas) {
        val weatherAlphaFactor = (1f - callTransitionProgress).coerceIn(0f, 1f)

        if (weatherAlphaFactor > 0.005f) {
            updateHudTextCacheIfNeeded()
            val tempText = cachedHudTempText
            val weatherText = cachedHudWeatherText
            val subInfoText = cachedHudSubInfoText
            val prefixText = cachedHudPrefixText
            val comfort = cachedHudComfort
            val hasValidRainAlert = cachedHudHasValidRainAlert
            val rainText = cachedHudRainText

            // 1. 动态自适应字号：大时钟模式 72px，歌词模式 62px
            tempNumPaint.textSize = lerp(72f, 62f, lyricsModeProgress)

            // 2. 测量各行宽度并构建矩形组合 (Bounding Box)
            val timeWidth = smallTimePaint.measureText(cachedTimeStr)
            val tempWidth = tempNumPaint.measureText(tempText)
            val row2Width = tempWidth + 14f + maxOf(cachedHudWeatherWidth, cachedHudSubInfoWidth)
            val dateWidth = cachedHudDateWidth
            val prefixWidth = cachedHudPrefixWidth
            val noteWidth = cachedHudNoteWidth

            // 矩形组合最大宽度 (平滑插值保障模式切换零跳跃)
            val idleBlockWidth = maxOf(row2Width, dateWidth, noteWidth)
            val lyricsBlockWidth = maxOf(timeWidth, row2Width, dateWidth, noteWidth)
            val blockWidth = lerp(idleBlockWidth, lyricsBlockWidth, lyricsModeProgress)

            // 3. 水平居中：小时间至环境感知的 4 行信息作为一个整体，在小欧屏左侧 HUD 视口内 [SAFE_MARGIN_PX (130px), HUD_DIVIDER_X (535px)] 绝对居中
            val hudAvailableWidth = HUD_DIVIDER_X - SAFE_MARGIN_PX
            val hudStartX = SAFE_MARGIN_PX + (hudAvailableWidth - blockWidth) / 2f
            weatherHudLeftX = hudStartX

            // 4. 垂直布局计算
            val timeBaselineY = 112f
            val tempBaselineY = lerp(178f, 206f, lyricsModeProgress)
            val dateBaselineY = lerp(226f, 256f, lyricsModeProgress)
            val noteBaselineY = lerp(266f, 296f, lyricsModeProgress)

            // 0. 大气小时间
            if (lyricsModeProgress > 0.005f) {
                val targetTextColor = currentConfig.clockTextColor
                val targetGlowColor = currentConfig.clockGlowColor
                val targetGlowRadius = currentConfig.clockGlowRadius
                val textColor = lerpColor(previousConfig.clockTextColor, targetTextColor, transitionProgress)
                val glowColor = lerpColor(previousConfig.clockGlowColor, targetGlowColor, transitionProgress)
                val finalTextColor = if (lightningActive && lightningIntensity > 0.35f) Color.WHITE else textColor
                val finalGlowColor = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor

                val alphaInt = (lyricsModeProgress.coerceIn(0f, 1f) * 255f * weatherAlphaFactor).toInt()
                smallTimePaint.color = finalTextColor
                smallTimePaint.alpha = alphaInt

                val glowAlphaInt = (Color.alpha(finalGlowColor) * lyricsModeProgress * weatherAlphaFactor).toInt().coerceIn(0, 255)
                if (glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
                    smallTimePaint.setShadowLayer(
                        targetGlowRadius * 0.7f,
                        0f,
                        2f,
                        ColorUtils.setAlphaComponent(finalGlowColor, glowAlphaInt)
                    )
                } else {
                    smallTimePaint.clearShadowLayer()
                }
                canvas.drawText(cachedTimeStr, hudStartX, timeBaselineY, smallTimePaint)
            }

            // 1. 温度数值 (62px)
            val tAlpha = (255 * weatherAlphaFactor).toInt()
            applyTextWithDynamicShadow(tempNumPaint, tAlpha, 8f, 0f, 2f, 160)
            canvas.drawText(tempText, hudStartX, tempBaselineY, tempNumPaint)

            // 2. 天气主标签 (28px)
            val weatherX = hudStartX + tempWidth + 14f
            val wlAlpha = (255 * weatherAlphaFactor).toInt()
            applyTextWithDynamicShadow(weatherLabelPaint, wlAlpha, 6f, 0f, 2f, 180)
            canvas.drawText(weatherText, weatherX, tempBaselineY - 28f, weatherLabelPaint)

            // 3. 风速与体感温副标 (20px)
            val wsAlpha = (240 * weatherAlphaFactor).toInt()
            applyTextWithDynamicShadow(weatherSubPaint, wsAlpha, 7f, 0f, 2f, 200)
            canvas.drawText(subInfoText, weatherX, tempBaselineY + 2f, weatherSubPaint)

            // 4. 日期与星期 (24px)
            val dlAlpha = (255 * weatherAlphaFactor).toInt()
            applyTextWithDynamicShadow(dateLabelPaint, dlAlpha, 6f, 0f, 2f, 180)
            canvas.drawText(cachedDateStr, hudStartX, dateBaselineY, dateLabelPaint)

            // 5. 实时环境感知副标与温感色彩 (或动态降水预警文字，彻底取消蓝色条色块底色)
            if (hasValidRainAlert) {
                val rpAlpha = (245 * weatherAlphaFactor).toInt()
                applyTextWithDynamicShadow(dateSubPaint, rpAlpha, 7f, 0f, 2f, 200)
                canvas.drawText(rainText, hudStartX, noteBaselineY, dateSubPaint)
            } else {
                val dsAlpha = (220 * weatherAlphaFactor).toInt()
                applyTextWithDynamicShadow(dateSubPaint, dsAlpha, 7f, 0f, 2f, 200)
                canvas.drawText(prefixText, hudStartX, noteBaselineY, dateSubPaint)

                comfortLabelPaint.color = comfort.color
                val noteAlpha = (dsAlpha * weatherAlphaFactor).toInt().coerceIn(0, 255)
                applyTextWithDynamicShadow(comfortLabelPaint, noteAlpha, 8f, 0f, 2f, 180, comfort.color)
                canvas.drawText(comfort.statusText, hudStartX + prefixWidth, noteBaselineY, comfortLabelPaint)
            }
        }

        // 通话状态展示 (当有通话时平滑淡入)
        if (callTransitionProgress > 0.005f) {
            drawCallCluster(canvas, callTransitionProgress)
        } else {
            callNamePaint.clearShadowLayer()
            callSubPaint.clearShadowLayer()
        }

        // 6. 雅致纵向分割光轨 (覆盖 70px 至 314px，垂直居中于 192px)
        if (!currentConfig.celestialVisible) {
            canvas.drawLine(HUD_DIVIDER_X, 70f, HUD_DIVIDER_X, 314f, dividerPaint)
        }
    }

    // ----------------------------------------------------
    // 车载接打电话与微信通话 HUD 核心绘制与 60FPS 高阶物理无缝动效
    // ----------------------------------------------------
    private fun drawCallCluster(canvas: Canvas, alphaProgress: Float) {
        val hudAvailableWidth = HUD_DIVIDER_X - SAFE_MARGIN_PX // 405f
        val hudCenterX = SAFE_MARGIN_PX + hudAvailableWidth / 2f // 332.5f
        val iconCenterY = 120f
        val baseScale = 0.65625f // 168f / 256f (放大图标与微动效，提升车机远距扫视清晰度)
        val nowMs = SystemClock.uptimeMillis()
        val masterAlpha = (255 * alphaProgress.coerceIn(0f, 1f)).toInt()
        if (masterAlpha <= 0) return

        ensureCallBitmapsLoaded()

        // 1. 状态过渡进度插值 (480ms 毫秒极佳物理阻尼衰减)
        val motionElapsed = nowMs - callMotionStartTime
        val p = (motionElapsed / callMotionDuration.toFloat()).coerceIn(0f, 1f)
        val easeP = 1f - (1f - p) * (1f - p) * (1f - p) // Decelerate cubic easing

        val currentState = callInfo.state
        val isTransitioning = (p < 1f) && (previousCallState != currentState)

        // 伴随图层过渡权重与水波绽放控制
        var incomingAlpha = 0f
        var outgoingAlpha = 0f
        var activeWavesAlpha = 0f
        var activeWavesBloom = 1f
        var activeWavesExpand = 1f
        var hangupHaloAlpha = 0f
        var hangupHaloScale = 1f

        when (currentState) {
            CarCallManager.CallState.RINGING -> {
                val shake = (sin(nowMs / 80.0) * 3.5).toFloat()
                if (isTransitioning) {
                    handsetCurrentRotation = lerp(handsetAnimStartRotation, shake, easeP)
                    handsetCurrentDx = lerp(handsetAnimStartDx, 0f, easeP)
                    handsetCurrentDy = lerp(handsetAnimStartDy, 0f, easeP)
                    handsetCurrentScale = lerp(handsetAnimStartScale, 1.0f, easeP)
                    incomingAlpha = easeP
                } else {
                    handsetCurrentRotation = shake
                    handsetCurrentDx = 0f
                    handsetCurrentDy = 0f
                    handsetCurrentScale = 1.0f
                    incomingAlpha = 1.0f
                }
            }
            CarCallManager.CallState.DIALING -> {
                if (isTransitioning) {
                    handsetCurrentRotation = lerp(handsetAnimStartRotation, 0f, easeP)
                    handsetCurrentDx = lerp(handsetAnimStartDx, 0f, easeP)
                    handsetCurrentDy = lerp(handsetAnimStartDy, 0f, easeP)
                    handsetCurrentScale = lerp(handsetAnimStartScale, 1.0f, easeP)
                    outgoingAlpha = easeP
                } else {
                    handsetCurrentRotation = 0f
                    handsetCurrentDx = 0f
                    handsetCurrentDy = 0f
                    handsetCurrentScale = 1.0f
                    outgoingAlpha = 1.0f
                }
            }
            CarCallManager.CallState.ACTIVE -> {
                if (isTransitioning) {
                    // 接听接通瞬间：听筒拾起回弹触感 (Pickup Spring Bounce)
                    val bounceY = -8f * sin(p * Math.PI.toFloat())
                    val bounceScale = 0.10f * sin(p * Math.PI.toFloat())

                    handsetCurrentRotation = lerp(handsetAnimStartRotation, 0f, easeP)
                    handsetCurrentDx = lerp(handsetAnimStartDx, 0f, easeP)
                    handsetCurrentDy = lerp(handsetAnimStartDy, 0f, easeP) + bounceY
                    handsetCurrentScale = lerp(handsetAnimStartScale, 1.0f, easeP) + bounceScale

                    incomingAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                    outgoingAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)

                    // 接通话音三级声波由听筒向外展开绽放
                    activeWavesBloom = p
                    activeWavesAlpha = p
                } else {
                    handsetCurrentRotation = 0f
                    handsetCurrentDx = 0f
                    handsetCurrentDy = 0f
                    handsetCurrentScale = 1.0f
                    activeWavesBloom = 1.0f
                    activeWavesAlpha = 1.0f
                }
            }
            CarCallManager.CallState.TERMINATED -> {
                val targetRotation = 135f
                val targetDx = 0f
                val targetDy = 4.5f
                val targetScale = 1.10f

                if (isTransitioning) {
                    // 挂断落座弧度轨迹 (顺畅旋转 135° + 弧线抬升下落软着陆)
                    val arcY = -10f * sin(p * Math.PI.toFloat()) + easeP * targetDy
                    val settleAngle = if (p > 0.8f) {
                        val sp = (p - 0.8f) / 0.2f
                        (sin(sp * Math.PI.toFloat()) * 1.8f)
                    } else 0f

                    handsetCurrentRotation = lerp(handsetAnimStartRotation, targetRotation, easeP) + settleAngle
                    handsetCurrentDx = lerp(handsetAnimStartDx, targetDx, easeP)
                    handsetCurrentDy = lerp(handsetAnimStartDy, 0f, easeP) + arcY
                    handsetCurrentScale = lerp(handsetAnimStartScale, targetScale, easeP)

                    incomingAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                    outgoingAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                    activeWavesAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                    activeWavesExpand = 1.0f + 0.35f * p

                    hangupHaloAlpha = easeP
                    hangupHaloScale = 0.65f + 0.35f * easeP
                } else {
                    handsetCurrentRotation = targetRotation
                    handsetCurrentDx = targetDx
                    handsetCurrentDy = targetDy
                    handsetCurrentScale = targetScale
                    hangupHaloAlpha = 1.0f
                    hangupHaloScale = 1.0f
                }
            }
            CarCallManager.CallState.IDLE -> {}
        }

        // 2. 绘制微信电话模式或普通电话无缝复合形态
        if (callInfo.type == CarCallManager.CallType.WECHAT && currentState != CarCallManager.CallState.TERMINATED) {
            val springBounce = if (currentState == CarCallManager.CallState.ACTIVE && isTransitioning) {
                0.10f * sin(p * Math.PI.toFloat())
            } else 0f
            drawWeChatIcon(canvas, hudCenterX, iconCenterY, baseScale * (1f + springBounce), nowMs, masterAlpha)
        } else if (callInfo.type == CarCallManager.CallType.WECHAT && currentState == CarCallManager.CallState.TERMINATED && isTransitioning) {
            // 微信电话挂断：双气泡优雅缩小淡出，挂断旋转手柄与警示光环无缝升起
            val wechatFade = (1f - p * 2.2f).coerceIn(0f, 1f)
            if (wechatFade > 0f) {
                val fadeAlpha = (masterAlpha * wechatFade).toInt()
                val wechatScale = 1.0f - 0.35f * p
                drawWeChatIcon(canvas, hudCenterX, iconCenterY, baseScale * wechatScale, nowMs, fadeAlpha)
            }
            val hangupAlpha = (masterAlpha * easeP).toInt()
            drawSeamlessHandset(canvas, hudCenterX, iconCenterY, baseScale, hangupAlpha)
            drawHangupHaloLayer(canvas, hudCenterX, iconCenterY, baseScale, nowMs, masterAlpha, hangupHaloAlpha, hangupHaloScale)
        } else {
            // 普通电话或已完全进入挂断态
            // (1) 唯一连续听筒实体 (顺畅衔接任意角度旋转与空间弧线)
            drawSeamlessHandset(canvas, hudCenterX, iconCenterY, baseScale, masterAlpha)

            // (2) 来电伴随层 (呼入粗箭头与微波弧)
            if (incomingAlpha > 0.005f) {
                drawIncomingAccompanyingLayers(canvas, hudCenterX, iconCenterY, baseScale, nowMs, masterAlpha, incomingAlpha)
            }

            // (3) 呼出伴随层 (外呼箭头与雷达波)
            if (outgoingAlpha > 0.005f) {
                drawOutgoingAccompanyingLayers(canvas, hudCenterX, iconCenterY, baseScale, nowMs, masterAlpha, outgoingAlpha)
            }

            // (4) 接通话音伴随层 (三级同心高对比声波涟漪绽放展开)
            if (activeWavesAlpha > 0.005f) {
                drawActiveWavesLayers(canvas, hudCenterX, iconCenterY, baseScale, nowMs, masterAlpha, activeWavesAlpha, activeWavesBloom, activeWavesExpand)
            }

            // (5) 挂断警示伴随层 (虚线光环匀速旋转与展开)
            if (hangupHaloAlpha > 0.005f) {
                drawHangupHaloLayer(canvas, hudCenterX, iconCenterY, baseScale, nowMs, masterAlpha, hangupHaloAlpha, hangupHaloScale)
            }
        }

        // 3. 下方联系人姓名 / 电话号码 (加大字号至 36px，阴影 Alpha 严格动态同步，彻底根除残影)
        val rawName = callInfo.name.ifBlank {
            callInfo.number.ifBlank {
                if (callInfo.type == CarCallManager.CallType.WECHAT) "微信通话" else "未知来电"
            }
        }
        val displayName = TextUtils.ellipsize(rawName, callNamePaint, 375f, TextUtils.TruncateAt.END).toString()
        applyTextWithDynamicShadow(callNamePaint, masterAlpha, 10f, 0f, 2f, 220)
        canvas.drawText(displayName, hudCenterX, 252f, callNamePaint)

        // 4. 下方通话状态 / 动态计时秒表 (支持语义平滑滑动切换 Slide & Cross-fade，阴影与文字严格同步淡出淡入)
        val statusText = when (currentState) {
            CarCallManager.CallState.RINGING -> {
                if (callInfo.type == CarCallManager.CallType.WECHAT) "微信语音来电邀请..." else "来电中..."
            }
            CarCallManager.CallState.DIALING -> {
                if (callInfo.type == CarCallManager.CallType.WECHAT) "微信正在呼叫..." else "正在呼出..."
            }
            CarCallManager.CallState.ACTIVE -> {
                val min = callInfo.durationSeconds / 60
                val sec = callInfo.durationSeconds % 60
                val durStr = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                if (callInfo.type == CarCallManager.CallType.WECHAT) "$durStr · 微信通话中" else "$durStr · 通话中"
            }
            CarCallManager.CallState.TERMINATED -> "通话已结束"
            CarCallManager.CallState.IDLE -> ""
        }

        if (statusText != lastDrawnStatusText) {
            val isSemanticChange = (statusText.contains("来电") != lastDrawnStatusText.contains("来电")) ||
                    (statusText.contains("呼叫") != lastDrawnStatusText.contains("呼叫")) ||
                    (statusText.contains("通话中") != lastDrawnStatusText.contains("通话中")) ||
                    (statusText.contains("已结束") != lastDrawnStatusText.contains("已结束"))

            if (isSemanticChange && lastDrawnStatusText.isNotEmpty()) {
                previousStatusText = lastDrawnStatusText
                statusTextTransitionStartTime = nowMs
            }
            lastDrawnStatusText = statusText
        }

        val textElapsed = nowMs - statusTextTransitionStartTime
        val textDuration = 220f
        if (textElapsed < textDuration && previousStatusText.isNotEmpty()) {
            val tp = (textElapsed / textDuration).coerceIn(0f, 1f)
            val easeTp = 1f - (1f - tp) * (1f - tp)

            // 旧文字向上浮动并淡出 (阴影与文字严格同步淡出，绝不留底层残影)
            val oldAlpha = (210 * alphaProgress.coerceIn(0f, 1f) * (1f - easeTp)).toInt()
            applyTextWithDynamicShadow(callSubPaint, oldAlpha, 8f, 0f, 2f, 200)
            canvas.drawText(previousStatusText, hudCenterX, 292f - easeTp * 8f, callSubPaint)

            // 新文字由下向上浮入并淡入 (阴影同步升入淡入)
            val newAlpha = (210 * alphaProgress.coerceIn(0f, 1f) * easeTp).toInt()
            applyTextWithDynamicShadow(callSubPaint, newAlpha, 8f, 0f, 2f, 200)
            canvas.drawText(statusText, hudCenterX, 292f + (1f - easeTp) * 8f, callSubPaint)
        } else {
            val steadyAlpha = (210 * alphaProgress.coerceIn(0f, 1f)).toInt()
            applyTextWithDynamicShadow(callSubPaint, steadyAlpha, 8f, 0f, 2f, 200)
            canvas.drawText(statusText, hudCenterX, 292f, callSubPaint)
        }
    }

    /**
     * 解决 Android HWUI/Skia 渲染树经典缺陷：Paint.alpha 无法联动 Paint.setShadowLayer 阴影透明度。
     * 本方法使底层阴影 Alpha 随文字 Alpha 呈 1:1 严格比例动态消隐，并在 Alpha=0 时主动执行 clearShadowLayer() 彻底根除残影。
     */
    private fun applyTextWithDynamicShadow(
        paint: Paint,
        targetAlpha: Int,
        baseShadowRadius: Float,
        shadowDx: Float,
        shadowDy: Float,
        baseShadowAlpha: Int,
        @ColorInt baseShadowColor: Int = Color.BLACK
    ) {
        val clampedAlpha = targetAlpha.coerceIn(0, 255)
        paint.alpha = clampedAlpha
        val shadowAlpha = (baseShadowAlpha * (clampedAlpha / 255f)).toInt()
        if (shadowAlpha > 1 && baseShadowRadius > 0.5f) {
            val finalShadowColor = ColorUtils.setAlphaComponent(baseShadowColor, shadowAlpha)
            paint.setShadowLayer(baseShadowRadius, shadowDx, shadowDy, finalShadowColor)
        } else {
            paint.clearShadowLayer()
        }
    }

    private fun drawSeamlessHandset(canvas: Canvas, cx: Float, cy: Float, baseScale: Float, alpha: Int) {
        if (alpha <= 0) return
        callMatrix.reset()
        callMatrix.postTranslate(-114f, -140f) // 听筒标准几何旋转中心
        callMatrix.postRotate(handsetCurrentRotation)
        callMatrix.postTranslate(handsetCurrentDx, handsetCurrentDy)
        callMatrix.postScale(baseScale * handsetCurrentScale, baseScale * handsetCurrentScale)
        callMatrix.postTranslate(cx, cy)
        callIconPaint.alpha = alpha
        bmpCallIncomingHandset?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callMatrix, callIconPaint) }
    }

    private fun drawIncomingAccompanyingLayers(
        canvas: Canvas, cx: Float, cy: Float, baseScale: Float,
        nowMs: Long, masterAlpha: Int, incomingAlpha: Float
    ) {
        // 1. 来电微波弧平滑呼吸发光
        val rippleAlpha = ((120 + 130 * sin(nowMs / 250.0)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * incomingAlpha).toInt()
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postScale(baseScale, baseScale)
        callLayerMatrix.postTranslate(cx, cy)
        callLayerPaint.alpha = rippleAlpha
        bmpCallIncomingRipple?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }

        // 2. 呼入箭头朝听筒中心斜向推入滑移并渐变脉冲
        val cycle = (nowMs % 900) / 900f
        val slide = (1f - cycle) * 16f
        val dx = -slide * 0.707f
        val dy = slide * 0.707f
        val arrowAlpha = ((60 + 195 * cycle).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * incomingAlpha).toInt()
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postTranslate(dx, dy)
        callLayerMatrix.postScale(baseScale, baseScale)
        callLayerMatrix.postTranslate(cx, cy)
        callLayerPaint.alpha = arrowAlpha
        bmpCallIncomingArrow?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
    }

    private fun drawOutgoingAccompanyingLayers(
        canvas: Canvas, cx: Float, cy: Float, baseScale: Float,
        nowMs: Long, masterAlpha: Int, outgoingAlpha: Float
    ) {
        val cycle = (nowMs % 1000) / 1000f
        val slide = cycle * 14f
        val dx = slide * 0.707f
        val dy = -slide * 0.707f
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postTranslate(dx, dy)
        callLayerMatrix.postScale(baseScale, baseScale)
        callLayerMatrix.postTranslate(cx, cy)
        callLayerPaint.alpha = (masterAlpha * outgoingAlpha).toInt()
        bmpCallOutgoingArrow?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }

        val w1Alpha = ((140 + 110 * sin(nowMs / 200.0)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * outgoingAlpha).toInt()
        val w2Alpha = ((120 + 130 * sin(nowMs / 200.0 - 1.2)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * outgoingAlpha).toInt()
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postScale(baseScale, baseScale)
        callLayerMatrix.postTranslate(cx, cy)

        callLayerPaint.alpha = w1Alpha
        bmpCallOutgoingWave1?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }

        callLayerPaint.alpha = w2Alpha
        bmpCallOutgoingWave2?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
    }

    private fun drawActiveWavesLayers(
        canvas: Canvas, cx: Float, cy: Float, baseScale: Float,
        nowMs: Long, masterAlpha: Int, activeWavesAlpha: Float,
        bloomProgress: Float, expandFactor: Float
    ) {
        val w1 = ((160 + 95 * sin(nowMs / 240.0)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * activeWavesAlpha).toInt()
        val w2 = ((140 + 115 * sin(nowMs / 240.0 - 0.9)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * activeWavesAlpha).toInt()
        val w3 = ((110 + 120 * sin(nowMs / 240.0 - 1.8)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * activeWavesAlpha).toInt()

        val bloom1 = (bloomProgress / 0.6f).coerceIn(0f, 1f)
        val bloom2 = ((bloomProgress - 0.15f) / 0.6f).coerceIn(0f, 1f)
        val bloom3 = ((bloomProgress - 0.30f) / 0.6f).coerceIn(0f, 1f)

        if (bloom1 > 0f) {
            val s1 = baseScale * (0.65f + 0.35f * bloom1) * expandFactor
            callLayerMatrix.reset()
            callLayerMatrix.postTranslate(-128f, -128f)
            callLayerMatrix.postScale(s1, s1)
            callLayerMatrix.postTranslate(cx, cy)
            callLayerPaint.alpha = (w1 * bloom1).toInt()
            bmpCallConnectedWave1?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
        }

        if (bloom2 > 0f) {
            val s2 = baseScale * (0.65f + 0.35f * bloom2) * expandFactor
            callLayerMatrix.reset()
            callLayerMatrix.postTranslate(-128f, -128f)
            callLayerMatrix.postScale(s2, s2)
            callLayerMatrix.postTranslate(cx, cy)
            callLayerPaint.alpha = (w2 * bloom2).toInt()
            bmpCallConnectedWave2?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
        }

        if (bloom3 > 0f) {
            val s3 = baseScale * (0.65f + 0.35f * bloom3) * expandFactor
            callLayerMatrix.reset()
            callLayerMatrix.postTranslate(-128f, -128f)
            callLayerMatrix.postScale(s3, s3)
            callLayerMatrix.postTranslate(cx, cy)
            callLayerPaint.alpha = (w3 * bloom3).toInt()
            bmpCallConnectedWave3?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
        }
    }

    private fun drawHangupHaloLayer(
        canvas: Canvas, cx: Float, cy: Float, baseScale: Float,
        nowMs: Long, masterAlpha: Int, haloAlpha: Float, haloScale: Float
    ) {
        val rot = ((nowMs / 35) % 360).toFloat()
        val finalAlpha = ((150 + 105 * sin(nowMs / 200.0)).toInt().coerceIn(0, 255) * (masterAlpha / 255f) * haloAlpha).toInt()
        val s = baseScale * haloScale
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postRotate(rot)
        callLayerMatrix.postScale(s, s)
        callLayerMatrix.postTranslate(cx, cy)
        callLayerPaint.alpha = finalAlpha
        bmpCallHangupHalo?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
    }

    private fun drawWeChatIcon(canvas: Canvas, cx: Float, cy: Float, baseScale: Float, nowMs: Long, masterAlpha: Int) {
        // 1. 车载微信双气泡轻柔呼吸
        val bScale = 1.0f + 0.025f * sin(nowMs / 320.0).toFloat()
        callMatrix.reset()
        callMatrix.postTranslate(-128f, -128f)
        callMatrix.postScale(baseScale * bScale, baseScale * bScale)
        callMatrix.postTranslate(cx, cy)
        bmpCallWeChatBack?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callMatrix, callIconPaint) }
        bmpCallWeChatFront?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callMatrix, callIconPaint) }

        // 2. 右上角语音播报角标波纹规律脉冲
        val waveAlpha = ((140 + 115 * sin(nowMs / 180.0)).toInt().coerceIn(0, 255) * (masterAlpha / 255f)).toInt()
        callLayerMatrix.reset()
        callLayerMatrix.postTranslate(-128f, -128f)
        callLayerMatrix.postScale(baseScale, baseScale)
        callLayerMatrix.postTranslate(cx, cy)
        callLayerPaint.alpha = waveAlpha
        bmpCallWeChatWave?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, callLayerMatrix, callLayerPaint) }
    }

    // ----------------------------------------------------
    // 第 6 层 (A)：中央巨幕霸气数字时钟 (全屏几何正中心 X=960, Y=192)
    // 兼具【经典拟真荧光时钟】与【液态玻璃时钟 (Blur 28px/Sat 170%/10%白底/折射厚度)】动态平滑双态
    // ----------------------------------------------------
    private fun drawHugeAtmosphericClock(canvas: Canvas, alphaFactor: Float) {
        if (alphaFactor <= 0.005f) {
            clockTimePaint.clearShadowLayer()
            return
        }

        val targetTextColor = currentConfig.clockTextColor
        val targetGlowColor = currentConfig.clockGlowColor
        val targetGlowRadius = currentConfig.clockGlowRadius

        val textColor = lerpColor(previousConfig.clockTextColor, targetTextColor, transitionProgress)
        val glowColor = lerpColor(previousConfig.clockGlowColor, targetGlowColor, transitionProgress)

        val finalTextColor = if (lightningActive && lightningIntensity > 0.35f) Color.WHITE else textColor

        // 彻底根除残影 1：使用二次幂快速干净衰减，时钟退场与歌词进场错开，彻底避免空间像素重叠
        val smoothAlpha = (alphaFactor.coerceIn(0f, 1f)).let { it * it }
        val textAlphaInt = (smoothAlpha * 255f).toInt()
        if (textAlphaInt <= 0) {
            clockTimePaint.clearShadowLayer()
            return
        }

        // 彻底根除残影 3：转场退出时附带极其自然的纵深收敛微缩放 (0.94 -> 1.0)，消除静态残像，保持 1:1 完美字体比例
        val scaleAnim = lerp(0.94f, 1.0f, smoothAlpha)

        canvas.save()
        canvas.scale(scaleAnim, scaleAnim, CLOCK_CENTER_X, LOGICAL_HEIGHT / 2f)

        // 1. 经典拟真大气荧光时钟 (液态玻璃进度 < 1.0 时渲染，支持开启/关闭丝滑交叉溶解淡入淡出)
        if (liquidGlassProgress < 0.999f) {
            val classicAlphaFactor = (1f - liquidGlassProgress) * smoothAlpha
            val classicAlphaInt = (classicAlphaFactor * 255f).toInt()
            if (classicAlphaInt > 0) {
                clockTimePaint.color = finalTextColor
                clockTimePaint.alpha = classicAlphaInt

                val glowAlphaInt = (Color.alpha(glowColor) * classicAlphaFactor).toInt().coerceIn(0, 255)
                if (glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
                    val baseGlow = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor
                    val fadedGlow = ColorUtils.setAlphaComponent(baseGlow, glowAlphaInt)
                    clockTimePaint.setShadowLayer(
                        (targetGlowRadius * 1.4f + (if (lightningActive) 20f else 0f)) * classicAlphaFactor,
                        0f,
                        4f,
                        fadedGlow
                    )
                } else {
                    clockTimePaint.clearShadowLayer()
                }
                canvas.drawText(cachedTimeStr, CLOCK_CENTER_X, clockBaselineY, clockTimePaint)
            }
        } else {
            clockTimePaint.clearShadowLayer()
        }

        // 2. 核心：液态玻璃时钟 (开启后呈现：28px 磨砂模糊、170% 饱和度、10% 白底微光叠层、inet 1px 1px #fff8 边缘厚度与晶体折射)
        if (liquidGlassProgress > 0.001f) {
            drawLiquidGlassClock(canvas, smoothAlpha, finalTextColor)
        }

        canvas.restore()
    }

    /**
     * 极速双通道无锁内存盒式模糊 (Box Blur)
     * 在 4x 降采样的 220×75 缓冲上执行半径 7 的双通道模糊，
     * 配合双线性上采样插值，严格等效全分辨率 28px 丝滑磨砂玻璃模糊 (Blur 28px)
     * 耗时仅 0.1ms，零 GC 分配，确保满帧 60 FPS
     */
    private fun fastBoxBlur(pix: IntArray, temp: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val div = 2 * r + 1
        val invDiv = (1 shl 16) / div

        // Pass 1: 水平方向 pix -> temp
        for (y in 0 until h) {
            val yOffset = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            val firstPx = pix[yOffset]
            for (i in -r..r) {
                val px = if (i < 0) firstPx else pix[yOffset + i.coerceAtMost(w - 1)]
                aSum += (px ushr 24)
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (x in 0 until w) {
                temp[yOffset + x] = (((aSum * invDiv) shr 16) shl 24) or
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

        // Pass 2: 垂直方向 temp -> pix
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -r..r) {
                val py = if (i < 0) 0 else i.coerceAtMost(h - 1)
                val px = temp[py * w + x]
                aSum += (px ushr 24)
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (y in 0 until h) {
                pix[y * w + x] = (((aSum * invDiv) shr 16) shl 24) or
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

    /**
     * 绘制液态玻璃中央大时钟：
     * 1. 28px 背景模糊 (Downsampled 4x with radius 7)
     * 2. 170% 高动态饱和度 (ColorMatrix.setSaturation(1.70f))
     * 3. 叠一层白色微光层 (rgb 255 255 255 / 10%)
     * 4. 文字边缘厚度浮雕：inet 1px 1px #fff8 (#88FFFFFF 偏移 -1px -1px 内嵌高光，+1px +1px 内嵌深度)
     * 5. 边缘加上折射：全曲率晶体折射描边与菲涅尔天光衍射光晕
     */
    private fun drawLiquidGlassClock(
        canvas: Canvas,
        smoothAlpha: Float,
        finalTextColor: Int
    ) {
        if (liquidGlassProgress <= 0.001f || smoothAlpha <= 0.005f) return

        // 1. 更新字体矢量轮廓 (仅在走时分钟改变时重新计算一次，零每帧 CPU 开销)
        if (cachedTimeStr != glassCachedTimeForPath) {
            glassCachedTimeForPath = cachedTimeStr
            glassTextPath.reset()
            clockTimePaint.getTextPath(
                cachedTimeStr,
                0,
                cachedTimeStr.length,
                CLOCK_CENTER_X,
                clockBaselineY,
                glassTextPath
            )
            glassTextPath.computeBounds(glassTextBounds, true)
        }

        // 2 & 3. 抓取并渲染背景 (天空、天体与流动体积云) 到 4x 降采样缓冲并执行 28px 快速模糊
        // 性能关键优化：节流控制，绝不每 16ms 在 UI 线程重复做 CPU 软模糊，降频至 300ms 或时间变动时执行，平时 60fps 直接复用 Bitmap
        val nowMs = SystemClock.uptimeMillis()
        if (lastGlassBlurUpdateTime == 0L || (nowMs - lastGlassBlurUpdateTime >= 300L) || (cachedTimeStr != glassCachedTimeForPath)) {
            lastGlassBlurUpdateTime = nowMs
            glassBgCanvas.save()
            glassBgCanvas.scale(
                GLASS_BUFFER_WIDTH.toFloat() / glassCropRect.width(),
                GLASS_BUFFER_HEIGHT.toFloat() / glassCropRect.height()
            )
            glassBgCanvas.translate(-glassCropRect.left, -glassCropRect.top)
            drawPhotorealisticSky(glassBgCanvas)
            drawPhotorealisticCelestial(glassBgCanvas)
            drawPhotorealisticClouds(glassBgCanvas)
            glassBgCanvas.restore()

            glassBgBitmap.getPixels(glassPixels, 0, GLASS_BUFFER_WIDTH, 0, 0, GLASS_BUFFER_WIDTH, GLASS_BUFFER_HEIGHT)
            fastBoxBlur(glassPixels, glassPixelsTemp, GLASS_BUFFER_WIDTH, GLASS_BUFFER_HEIGHT, 7)
            glassBgBitmap.setPixels(glassPixels, 0, GLASS_BUFFER_WIDTH, 0, 0, GLASS_BUFFER_WIDTH, GLASS_BUFFER_HEIGHT)
        }

        val glassAlpha = (smoothAlpha * liquidGlassProgress).coerceIn(0f, 1f)

        // 仅在时间文本改变或 alpha 变动显著时重新构建 Shader，复用成员数组，零 GC
        val needUpdateShaders = (cachedTimeStr != glassCachedTimeForShaders) || (kotlin.math.abs(glassAlpha - lastGlassAlphaForShaders) > 0.05f) || (glassSurfaceShinePaint.shader == null)
        if (needUpdateShaders) {
            glassCachedTimeForShaders = cachedTimeStr
            lastGlassAlphaForShaders = glassAlpha

            glassShineColors[0] = Color.argb((70 * glassAlpha).toInt(), 255, 255, 255)
            glassShineColors[1] = Color.argb((12 * glassAlpha).toInt(), 255, 255, 255)
            glassShineColors[2] = Color.argb((38 * glassAlpha).toInt(), 255, 255, 255)
            glassSurfaceShinePaint.shader = LinearGradient(
                0f, glassTextBounds.top,
                0f, glassTextBounds.bottom,
                glassShineColors,
                glassShineStops,
                Shader.TileMode.CLAMP
            )

            val ambientTint = ColorUtils.blendARGB(COLOR_AMBIENT_TINT, finalTextColor, 0.25f)
            glassRefractionColors[0] = Color.argb((245 * glassAlpha).toInt(), 255, 255, 255)
            glassRefractionColors[1] = Color.argb((110 * glassAlpha).toInt(), 255, 255, 255)
            glassRefractionColors[2] = ColorUtils.setAlphaComponent(ambientTint, (190 * glassAlpha).toInt())
            glassRefractionColors[3] = Color.argb((225 * glassAlpha).toInt(), 255, 255, 255)
            glassRefractionStrokePaint.shader = LinearGradient(
                glassTextBounds.left, glassTextBounds.top,
                glassTextBounds.right, glassTextBounds.bottom,
                glassRefractionColors,
                glassRefractionStops,
                Shader.TileMode.CLAMP
            )
        }

        // 4. 文字内部填充图层 (裁切至文字 Path 内部)
        canvas.save()
        canvas.clipPath(glassTextPath)

        // (A) 绘制加大模糊 28px 与饱和度 170% 的背景底图
        glassBitmapPaint.alpha = (255 * glassAlpha).toInt()
        if (!glassBgBitmap.isRecycled) {
            canvas.drawBitmap(glassBgBitmap, glassSrcRect, glassCropRect, glassBitmapPaint)
        }

        // (B) 叠一层白色（rgb255 255 255 / 10%）
        glassOverlayPaint.color = Color.argb((26 * glassAlpha).toInt(), 255, 255, 255)
        canvas.drawRect(glassCropRect, glassOverlayPaint)

        // (C) 内部曲率微光流光渐变 (Top 35% 白光反射，底边天光漫射)
        canvas.drawRect(glassCropRect, glassSurfaceShinePaint)

        // (D) 文字边缘厚度：inet 1px 1px #fff8 (内浮雕厚度)
        // 按照用户需求：inet 1px 1px #fff8，内部偏移 1px 绘制亮白内倒角高光厚度
        glassInsetLightPaint.alpha = (180 * glassAlpha).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(1.0f, 1.0f)
        canvas.drawPath(glassTextPath, glassInsetLightPaint)
        canvas.restore()

        canvas.restore() // 结束内部裁切

        // 5. 边缘加上折射 (Edge Refraction Outer Perimeter & Bloom)
        // (A) 边缘全曲率晶体折射描边 (Fresnel Refraction Rim)
        canvas.drawPath(glassTextPath, glassRefractionStrokePaint)

        // (B) 边缘折射微弱菲涅尔光晕 (Refraction Halo Glow)
        if (glassAlpha > 0.05f) {
            val glowColor = if (lightningActive && lightningIntensity > 0.35f) {
                Color.argb((180 * glassAlpha).toInt(), 240, 171, 252) // 雷暴电闪联动
            } else {
                Color.argb((110 * glassAlpha).toInt(), 255, 255, 255)
            }
            glassOuterRefractionPaint.color = Color.TRANSPARENT
            glassOuterRefractionPaint.setShadowLayer(
                14f * glassAlpha,
                0f, 0f, glowColor
            )
            canvas.drawPath(glassTextPath, glassOuterRefractionPaint)
            glassOuterRefractionPaint.clearShadowLayer()
        }
    }

    // ----------------------------------------------------
    // 第 6 层 (B)：五行居中对称歌词引擎 (顶满屏幕边缘大字体、居中对齐、逐字速度驱动跑马灯)
    // ----------------------------------------------------
    private fun drawFiveLineLyrics(canvas: Canvas, alphaFactor: Float) {
        if (alphaFactor <= 0.005f || lyricLines.isEmpty() || activeLineIndex < 0) return

        val targetTextColor = currentConfig.clockTextColor
        val targetGlowColor = currentConfig.clockGlowColor
        val targetGlowRadius = currentConfig.clockGlowRadius

        val textColor = lerpColor(previousConfig.clockTextColor, targetTextColor, transitionProgress)
        val glowColor = lerpColor(previousConfig.clockGlowColor, targetGlowColor, transitionProgress)
        val finalTextColor = if (lightningActive && lightningIntensity > 0.35f) Color.WHITE else textColor
        val finalGlowColor = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor

        updateSafeBounds()

        val leftClip = CLOCK_CENTER_X - safeHalfWidth
        val rightClip = CLOCK_CENTER_X + safeHalfWidth
        val maxVisibleWidth = safeHalfWidth * 2f

        canvas.save()
        canvas.clipRect(leftClip, 0f, rightClip, LOGICAL_HEIGHT)

        val maxComfortableWidth = maxVisibleWidth - 64f // 786px 触发阈值，避免边缘贴字切字
        val centerLineFloor = animatedLineIndex.toInt()
        val minSlotIndex = (centerLineFloor - 3).coerceAtLeast(0)
        val maxSlotIndex = (centerLineFloor + 3).coerceAtMost(lyricLines.size - 1)

        for (idx in minSlotIndex..maxSlotIndex) {
            val v = idx.toFloat() - animatedLineIndex
            val absV = abs(v)
            if (absV > 2.8f) continue

            // 1. 字体大小阶梯：中心 92px (放大一倍，超大醒目)，二四行 36px，边缘一五行 22px
            val textSize = getSlotTextSize(absV)

            // 2. 透明度阶梯：中心 1.0，次级 0.60，边缘 0.28
            val baseAlpha = getSlotAlpha(absV)
            val lineAlpha = (baseAlpha * alphaFactor * 255f).toInt().coerceIn(0, 255)
            if (lineAlpha <= 0) continue

            lyricDrawPaint.textSize = textSize
            lyricDrawPaint.color = finalTextColor
            lyricDrawPaint.alpha = lineAlpha

            // 3. 字重与大气发光光晕 (平滑过渡，复用单例字重)
            if (absV < 0.45f) {
                lyricDrawPaint.typeface = typefaceMediumBold
                val glowFactor = (1f - 2.2f * absV).coerceIn(0f, 1f)
                val glowRad = (targetGlowRadius * 1.5f + (if (lightningActive) 18f else 0f)) * glowFactor
                val glowAlpha = (Color.alpha(finalGlowColor) * (lineAlpha / 255f) * glowFactor).toInt()
                val fadedGlow = ColorUtils.setAlphaComponent(finalGlowColor, glowAlpha)
                lyricDrawPaint.setShadowLayer(glowRad, 0f, 3f, fadedGlow)
            } else {
                lyricDrawPaint.typeface = typefaceMediumNormal
                lyricDrawPaint.setShadowLayer(5f, 0f, 2f, Color.argb((lineAlpha * 0.5f).toInt(), 0, 0, 0))
            }

            // 4. 纵向基线垂直居中 (非线性间距：中心至二四行 80px，一五行贴近二四行 50px，复用 lyricFontMetrics 零 GC)
            val centerY = getSlotY(v)
            lyricDrawPaint.getFontMetrics(lyricFontMetrics)
            val baselineY = centerY - (lyricFontMetrics.descent + lyricFontMetrics.ascent) / 2f

            // 5. 逐字显示速度驱动跑马灯位移计算 (中心句及过渡期间保持丝滑位置连续)
            val lineText = lyricLines[idx].text
            val textWidth = lyricDrawPaint.measureText(lineText)

            var drawCenterX = CLOCK_CENTER_X
            if (textWidth > maxComfortableWidth) {
                val marqueeOffset = when {
                    idx == activeLineIndex -> calculateMarqueeOffset(textWidth, maxVisibleWidth, idx, currentLyricsPositionMs)
                    idx < activeLineIndex -> -((textWidth - maxVisibleWidth) / 2f) - 48f // 已唱完，驻留在句末 (多往左走 48px 保证末字完整)
                    else -> (textWidth - maxVisibleWidth) / 2f + 24f                     // 未开唱，句首舒适对齐
                }
                drawCenterX = CLOCK_CENTER_X + marqueeOffset
            }

            canvas.drawText(lineText, drawCenterX, baselineY, lyricDrawPaint)
        }

        canvas.restore()
    }

    /**
     * 居中对齐超长歌词跑马灯核心算法：
     * 1. 触发阈值：只要文本可能贴边或无法完整舒适展示 (textWidth > maxComfortableWidth)，即触发跑马灯；
     * 2. 初始状态：展示最左边的歌词 (左对齐视口左边界，留 24px 呼吸间距)；
     * 3. 句首阅读期：留出约 25% 句长时间 (600ms ~ 1400ms) 读清歌词前半句；
     * 4. 匀速推进期：匀速向左滚动，多往左走 48px，确保最后一个字及其发光完全进入视野；
     * 5. 提前驻留：在整句唱完前整整 1 秒到达句末并完全停住，不再回正，供用户完整读完全句直到下一句切歌。
     */
    private fun calculateMarqueeOffset(
        textWidth: Float,
        maxVisibleWidth: Float,
        lineIndex: Int,
        currentPosMs: Long
    ): Float {
        val maxComfortableWidth = maxVisibleWidth - 64f
        if (textWidth <= maxComfortableWidth || lineIndex !in lyricLines.indices) return 0f

        val currentLine = lyricLines[lineIndex]
        val lineStartMs = currentLine.timestamp
        val nextLineStartMs = if (lineIndex + 1 < lyricLines.size) {
            minOf(lyricLines[lineIndex + 1].timestamp, lineStartMs + 8000L)
        } else {
            lineStartMs + 5000L
        }
        val lineDurationMs = (nextLineStartMs - lineStartMs).coerceAtLeast(1800L)

        // 1. 左对齐偏移量：使文字最左端舒适对齐视口左边界 (带 24px 呼吸间隙)
        val offsetStart = (textWidth - maxVisibleWidth) / 2f + 24f

        // 2. 右对齐偏移量：歌词最右端舒适落在视口右边界内侧 48px，绝不切字，保证最后一个字完整展现
        val offsetEnd = -((textWidth - maxVisibleWidth) / 2f) - 48f

        // 3. 滚动结束时间：严格在下一句到来前 1 秒 (提前 1000ms 到达并停住)
        val scrollEndTimeMs = (nextLineStartMs - 1000L).coerceAtLeast(lineStartMs + 800L)

        // 4. 滚动开始时间：给用户留出约 25% 句长时间 (600ms ~ 1400ms) 读清歌词前半句
        val readHeadDuration = (lineDurationMs * 0.25f).toLong().coerceIn(600L, 1400L)
        val scrollStartTimeMs = (lineStartMs + readHeadDuration).coerceAtMost(scrollEndTimeMs - 400L)

        // 阶段 1：句首阅读期 (静止展示最左侧)
        if (currentPosMs <= scrollStartTimeMs) {
            return offsetStart
        }

        // 阶段 2：匀速滚动期 (从句首平滑移至句末)
        if (currentPosMs < scrollEndTimeMs) {
            val scrollDuration = (scrollEndTimeMs - scrollStartTimeMs).toFloat()
            val progress = ((currentPosMs - scrollStartTimeMs).toFloat() / scrollDuration).coerceIn(0f, 1f)
            return lerp(offsetStart, offsetEnd, progress)
        }

        // 阶段 3：句末驻留期 (在完成前 1 秒到达句末，完全展示最后一个字并不再回正，停驻直到切句)
        return offsetEnd
    }

    /**
     * 严谨计算对称安全边界：
     * 中心至左侧天气边界净距 vs 中心至右侧小欧形象净距，取最小值作为中心至两边的半宽
     */
    private fun updateSafeBounds() {
        val dLeft = CLOCK_CENTER_X - HUD_DIVIDER_X // 960 - 535 = 425f
        val dRight = if (xiaoOuAvoidanceEnabled) {
            XIAOOU_GESTURE_BOUNDARY_X - CLOCK_CENTER_X // 1420 - 960 = 460f
        } else {
            SAFE_RIGHT_START_PX - CLOCK_CENTER_X // 1790 - 960 = 830f
        }
        safeHalfWidth = min(dLeft, dRight).coerceAtLeast(200f)
    }

    fun setXiaoOuAvoidance(avoid: Boolean) {
        if (xiaoOuAvoidanceEnabled != avoid) {
            xiaoOuAvoidanceEnabled = avoid
            updateSafeBounds()
            invalidate()
        }
    }

    // ==========================================
    // 歌词数据与播放进度同步 API
    // ==========================================
    fun setLyrics(lines: List<LyricLine>) {
        if (lyricLines == lines && activeLineIndex >= 0) return
        lyricLines = lines
        activeLineIndex = -1
        lineActiveStartTimeMs = SystemClock.uptimeMillis()
        if (lines.isNotEmpty()) {
            updateLyricsProgress(currentLyricsPositionMs)
        }
        invalidate()
    }

    fun updateLyricsProgress(positionMs: Long) {
        currentLyricsPositionMs = positionMs
        if (lyricLines.isEmpty()) return

        var targetIdx = -1
        for (i in lyricLines.indices) {
            if (lyricLines[i].timestamp <= positionMs) {
                targetIdx = i
            } else {
                break
            }
        }
        if (targetIdx == -1) targetIdx = 0

        if (targetIdx != activeLineIndex) {
            val prevIdx = activeLineIndex
            activeLineIndex = targetIdx
            lineActiveStartTimeMs = SystemClock.uptimeMillis()

            if (prevIdx == -1) {
                // 首次载入或切歌，直接对齐目标行，无需慢速入场
                animatedLineIndex = targetIdx.toFloat()
                lineScrollAnimator.cancel()
            } else {
                // 统一 750ms 平滑连续滚动动画：无论跨度是 1 行还是跨度多行，绝不硬切，始终保持丝滑过渡
                lineScrollAnimator.cancel()
                lineScrollAnimator.setFloatValues(animatedLineIndex, targetIdx.toFloat())
                lineScrollAnimator.duration = 750L
                lineScrollAnimator.start()
            }
            invalidate()
        }
    }

    fun setLyricsActive(active: Boolean, smooth: Boolean = true) {
        if (isLyricsActive == active && ((active && lyricsModeProgress == 1f) || (!active && lyricsModeProgress == 0f))) return
        isLyricsActive = active

        lyricsModeAnimator.cancel()
        if (smooth) {
            val startVal = lyricsModeProgress
            val endVal = if (active) 1f else 0f
            lyricsModeAnimator.setFloatValues(startVal, endVal)
            lyricsModeAnimator.duration = (abs(endVal - startVal) * 500L).toLong().coerceAtLeast(100L)
            lyricsModeAnimator.start()
        } else {
            lyricsModeProgress = if (active) 1f else 0f
            invalidate()
        }
    }

    /**
     * 设置并更新小欧屏最顶部居中高亮音乐播放信息 (‘音乐图标’ 歌名-歌手名)
     * - 纯原生 1920×384 黄金基准坐标系直接渲染，实车与 PAD 缩放比例 100% 绝对一致
     * - 居中对齐，超出边界自动以省略号截断
     * - 播放时淡入展示；无音乐播放或恢复时间显示时优雅淡出收敛隐藏
     * - 具备幂等性保护，播放状态下重复上报进度绝对不打断动画、杜绝频闪
     */
    fun setTopMusicTrackInfo(title: String, artist: String, isPlaying: Boolean) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val shouldShow = isPlaying && cleanTitle.isNotEmpty() && !MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, cleanArtist)

        val displayText = if (cleanArtist.isNotEmpty() &&
            !cleanArtist.equals("未知歌手", ignoreCase = true) &&
            !cleanArtist.equals("Unknown", ignoreCase = true) &&
            !cleanTitle.contains(cleanArtist, ignoreCase = true)
        ) {
            "$cleanTitle - $cleanArtist"
        } else {
            cleanTitle
        }

        val isNewText = (this.topMusicDisplayText != displayText)
        this.topMusicTitle = cleanTitle
        this.topMusicArtist = cleanArtist
        this.topMusicDisplayText = displayText
        this.isTopMusicPlaying = shouldShow

        val target = if (shouldShow) 1f else 0f
        if (target == topMusicTargetAlpha) {
            if (isNewText && topMusicAlpha > 0f) {
                invalidate()
            }
            return
        }

        topMusicTargetAlpha = target
        topMusicAlphaAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(topMusicAlpha, target).apply {
            duration = 350L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                topMusicAlpha = a.animatedValue as Float
                invalidate()
            }
        }
        topMusicAlphaAnimator = anim
        anim.start()
    }

    // ----------------------------------------------------
    // 第 7 层：顶部居中高亮音乐胶囊 (播放音乐时中心高亮展示，车规级 1920×384 黄金比例自适应)
    // ----------------------------------------------------
    private fun drawTopMusicIsland(canvas: Canvas) {
        val a = topMusicAlpha
        if (a <= 0.005f || topMusicDisplayText.isEmpty()) return

        updateSafeBounds()
        // 歌词安全显示范围为 [CLOCK_CENTER_X - safeHalfWidth, CLOCK_CENTER_X + safeHalfWidth] (535px ~ 1385px，全宽 850px，舒适内衬 786px)
        // 顶部音乐胶囊岛最长严格不能超过歌词显示的边界，因此将胶囊最大宽度限制在歌词安全区内 (最大 760px，左右各留 45px 呼吸余量)
        val maxCapsuleWidth = (safeHalfWidth * 2f - 90f).coerceAtMost(760f)

        val paddingStartEnd = 16f
        val iconWidth = 22f
        val iconSpacing = 8f
        val capsuleHeight = 42f
        val capsuleTop = 6f
        val capsuleBottom = capsuleTop + capsuleHeight

        val maxTextWidth = (maxCapsuleWidth - (paddingStartEnd * 2f + iconWidth + iconSpacing)).coerceAtLeast(100f)
        val text = topMusicDisplayText
        val textToDraw = TextUtils.ellipsize(text, topMusicTextPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        val textWidth = topMusicTextPaint.measureText(textToDraw)

        val totalWidth = (paddingStartEnd * 2f + iconWidth + iconSpacing + textWidth).coerceAtMost(maxCapsuleWidth)
        val cx = LOGICAL_WIDTH / 2f
        val left = cx - totalWidth / 2f
        val right = cx + totalWidth / 2f

        topMusicCapsuleRect.set(left, capsuleTop, right, capsuleBottom)
        val cornerRadius = 21f

        val alphaInt = (a * 255f).toInt().coerceIn(0, 255)

        // 1. 半透明暗色磨砂底胶囊
        topMusicBgPaint.alpha = (alphaInt * 0.35f).toInt()
        canvas.drawRoundRect(topMusicCapsuleRect, cornerRadius, cornerRadius, topMusicBgPaint)

        // 2. 微光精致边框
        topMusicStrokePaint.alpha = (alphaInt * 0.20f).toInt()
        canvas.drawRoundRect(topMusicCapsuleRect, cornerRadius, cornerRadius, topMusicStrokePaint)

        // 3. 音乐小图标
        val iconBmp = topMusicIconBmp
        val iconLeft = left + paddingStartEnd
        val iconTop = capsuleTop + (capsuleHeight - iconWidth) / 2f
        if (iconBmp != null && !iconBmp.isRecycled) {
            topMusicIconPaint.alpha = alphaInt
            canvas.drawBitmap(iconBmp, iconLeft, iconTop, topMusicIconPaint)
        }

        // 4. 高亮歌名 - 歌手名居中字样
        topMusicTextPaint.alpha = alphaInt
        val textLeft = iconLeft + iconWidth + iconSpacing
        val fm = topMusicTextPaint.fontMetrics
        val textBaseline = capsuleTop + (capsuleHeight - (fm.bottom - fm.top)) / 2f - fm.top
        canvas.drawText(textToDraw, textLeft, textBaseline, topMusicTextPaint)
    }

    // ----------------------------------------------------
    // 第 8 层：左右 130px 物理安全黑边渐变遮罩 (顶层融合)
    // ----------------------------------------------------
    private fun drawEdgeVignette(canvas: Canvas) {
        canvas.drawRect(vignetteLeftRect, vignetteLeftPaint)
        canvas.drawRect(vignetteRightRect, vignetteRightPaint)
    }

    @ColorInt
    private fun lerpColor(@ColorInt startColor: Int, @ColorInt endColor: Int, fraction: Float): Int {
        return ColorUtils.blendARGB(startColor, endColor, fraction.coerceIn(0f, 1f))
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }
}

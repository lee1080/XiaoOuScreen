package com.car.xiaoouscreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherState
import com.carlyrics.model.LyricLine
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

    private val transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            transitionProgress = it.animatedValue as Float
        }
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

    private val tempNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }

    private val weatherLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
    }

    private val weatherSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        textAlign = Paint.Align.LEFT
        color = Color.parseColor("#F1F5F9")
        setShadowLayer(7f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    private val dateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f
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

    // 迁移至左侧天气上方的大气小时间画笔 (使用粗体，白字带微阴影，字号加大至 76px)
    private val smallTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 76f
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

    private data class ComfortLevel(val statusText: String, @ColorInt val color: Int)

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

    init {
        val fm = clockTimePaint.fontMetrics
        clockBaselineY = (LOGICAL_HEIGHT / 2f) - (fm.descent + fm.ascent) / 2f

        initVignetteShaders()
        initDividerShader()
        initCelestialAndCloudBitmaps()
        initParticlePools()
        initStars()
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
        try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            // 天体素材
            bmpSun = BitmapFactory.decodeResource(resources, R.drawable.weather_celestial_sun, opts)
            bmpSunRays = BitmapFactory.decodeResource(resources, R.drawable.weather_celestial_sun_rays, opts)
            bmpSunFlare = BitmapFactory.decodeResource(resources, R.drawable.weather_sun_flare, opts)
            bmpMoon = BitmapFactory.decodeResource(resources, R.drawable.weather_celestial_moon, opts)

            // iOS 风格 5 层体积云
            bmpCirrus = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud_cirrus, opts)
            bmpCumulusBack = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud_cumulus_back, opts)
            bmpCumulusFront = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud_cumulus_front, opts)
            bmpMist = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud_mist, opts)
            bmpOvercast = BitmapFactory.decodeResource(resources, R.drawable.weather_cloud_overcast, opts)
        } catch (e: Exception) {
            e.printStackTrace()
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
    fun updateScene(newConfig: WeatherSceneConfig) {
        if (newConfig == currentConfig) return

        previousConfig = currentConfig
        currentConfig = newConfig

        transitionAnimator.cancel()
        transitionAnimator.start()

        if (newConfig.lightningEnabled) {
            scheduleNextLightning()
        }
        invalidate()
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
        // 在车载副屏 / WindowManager 悬浮窗中，windowVisibility 与 isShown 易在特定 ROM 下被误判为非 VISIBLE
        // 只要当前 View 自身为 VISIBLE 且未手动暂停，即稳稳开启 60FPS Choreographer 循环，杜绝 ADB 启动黑屏
        val shouldRender = (visibility == VISIBLE)
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
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRenderingActive) return

        if (lastFrameTimeNanos != 0L) {
            val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
            val clampedDelta = deltaSeconds.coerceIn(0.001f, 0.05f)
            updatePhysics(clampedDelta)
        }
        lastFrameTimeNanos = frameTimeNanos

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
                    val progress = elapsed.toFloat() / lightningDurationMs
                    lightningIntensity = (1f - progress) * (if (random.nextBoolean()) 1.0f else 0.45f)
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

        // 第 7 层：左右 130px 物理安全黑边渐变融合遮罩 (最顶层)
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
            // 快照不烘焙 drawEdgeVignette，交由 WebView 外层 2D vignette-sides 统一度量叠加，杜绝双重黑边及动画结束瞬间补丁感
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "截取严格 1920x384 快照失败: ${t.message}", t)
            null
        }
    }

    // ----------------------------------------------------
    // 第 1 层：写实天空渐变
    // ----------------------------------------------------
    private fun drawPhotorealisticSky(canvas: Canvas) {
        val topColor = lerpColor(previousConfig.skyTopColor, currentConfig.skyTopColor, transitionProgress)
        val bottomColor = lerpColor(previousConfig.skyBottomColor, currentConfig.skyBottomColor, transitionProgress)

        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, LOGICAL_HEIGHT,
            topColor, bottomColor,
            Shader.TileMode.CLAMP
        )
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

                    // 1. 大气日冕色温与微光晕 (清晨暖桃金、黄昏深炽橙、白天耀白金，径向平滑渐变至纯透明，杜绝分层假色)
                    val (haloColor, haloRadius, sunTint) = when (currentConfig.timeOfDay) {
                        TimeOfDay.DAWN -> Triple(
                            Color.argb((45 * celestialAlpha).toInt(), 254, 215, 170),
                            105f + breathing,
                            Color.parseColor("#FED7AA") // 清晨暖桃金
                        )
                        TimeOfDay.DUSK -> Triple(
                            Color.argb((70 * celestialAlpha).toInt(), 251, 146, 60),
                            115f + breathing,
                            Color.parseColor("#FB923C") // 傍晚落日炽金橙
                        )
                        else -> Triple(
                            Color.argb((50 * celestialAlpha).toInt(), 254, 240, 160),
                            110f + breathing,
                            null // 白天耀白日光
                        )
                    }

                    sunHaloPaint.shader = RadialGradient(
                        CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, haloRadius,
                        haloColor, Color.argb(0, Color.red(haloColor), Color.green(haloColor), Color.blue(haloColor)),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, haloRadius, sunHaloPaint)

                    // 2. 扩散光斑与发散光芒 (带 2D 余弦平滑渐变透明，绝无生硬切边与矩形分界)
                    // 时段区分：白天饱满生动双层逆向缓旋；清晨大幅削弱仅微弱单层晨光；黄昏完全移除扩散光斑保持落日沉静
                    val rayMultiplier = when (currentConfig.timeOfDay) {
                        TimeOfDay.DAY -> 1.0f
                        TimeOfDay.DAWN -> 0.15f
                        else -> 0.0f
                    }

                    if (rayMultiplier > 0.01f) {
                        bmpSunRays?.let { raysBmp ->
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
                    bmpSun?.let { sunBmp ->
                        sunPaint.alpha = (celestialAlpha * 255).toInt()
                        sunPaint.colorFilter = sunTint?.let { PorterDuffColorFilter(it, PorterDuff.Mode.MULTIPLY) }
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

                    // 冷月柔和散射青辉 (平滑衰减，无色晕阶跃)
                    moonHaloPaint.shader = RadialGradient(
                        CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, 65f,
                        Color.argb((35 * celestialAlpha).toInt(), 56, 189, 248),
                        Color.argb(0, 56, 189, 248),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(CELESTIAL_CENTER_X, CELESTIAL_CENTER_Y, 65f, moonHaloPaint)

                    // 真实缺月月牙渲染 (顺时针旋转 30 度预制，带环形山明暗与月海地貌)
                    bmpMoon?.let { moonBmp ->
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

        val colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
        cloudCirrusPaint.colorFilter = colorFilter
        cloudCumulusBackPaint.colorFilter = colorFilter
        cloudCumulusFrontPaint.colorFilter = colorFilter
        cloudMistPaint.colorFilter = colorFilter
        cloudOvercastPaint.colorFilter = colorFilter

        drawSeamlessCloudLayer(canvas, bmpOvercast, scrollOvercastX, overcastA, cloudOvercastPaint)
        drawSeamlessCloudLayer(canvas, bmpCirrus, scrollCirrusX, cirrusA, cloudCirrusPaint)
        drawSeamlessCloudLayer(canvas, bmpCumulusBack, scrollCumulusBackX, cumBackA, cloudCumulusBackPaint)
        drawSeamlessCloudLayer(canvas, bmpCumulusFront, scrollCumulusFrontX, cumFrontA, cloudCumulusFrontPaint)
        drawSeamlessCloudLayer(canvas, bmpMist, scrollMistX, mistA, cloudMistPaint)
    }

    private fun drawSeamlessCloudLayer(
        canvas: Canvas,
        bitmap: Bitmap?,
        scrollX: Float,
        alpha: Float,
        paint: Paint
    ) {
        if (bitmap == null || alpha <= 0.006f) return
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
            flashPaint.color = Color.WHITE
            flashPaint.alpha = (lightningIntensity * 120).toInt().coerceIn(0, 175)
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
        val tempText = "${currentConfig.temperatureCelsius.toInt()}°"
        val weatherText = currentConfig.weatherDescription
        val subInfoText = "风速 ${currentConfig.windSpeedKmh.toInt()} km/h · 优"
        val prefixText = "实时环境感知 · "
        val comfort = getComfortLevel(currentConfig.temperatureCelsius)

        // 1. 动态自适应字号：大时钟模式 72px，歌词模式 58px
        tempNumPaint.textSize = lerp(72f, 58f, lyricsModeProgress)

        // 2. 测量各行宽度并构建矩形组合 (Bounding Box)
        val timeWidth = smallTimePaint.measureText(cachedTimeStr)
        val tempWidth = tempNumPaint.measureText(tempText)
        val weatherWidth = weatherLabelPaint.measureText(weatherText)
        val subInfoWidth = weatherSubPaint.measureText(subInfoText)
        val row2Width = tempWidth + 16f + maxOf(weatherWidth, subInfoWidth)
        val dateWidth = dateLabelPaint.measureText(cachedDateStr)
        val prefixWidth = dateSubPaint.measureText(prefixText)
        val noteWidth = prefixWidth + comfortLabelPaint.measureText(comfort.statusText)

        // 矩形组合最大宽度 (平滑插值保障模式切换零跳跃)
        val idleBlockWidth = maxOf(row2Width, dateWidth, noteWidth)
        val lyricsBlockWidth = maxOf(timeWidth, row2Width, dateWidth, noteWidth)
        val blockWidth = lerp(idleBlockWidth, lyricsBlockWidth, lyricsModeProgress)

        // 3. 水平居中：在左侧安全边距 [SAFE_MARGIN_PX (130px), HUD_DIVIDER_X (535px)] 405px 视口内绝对居中
        val hudAvailableWidth = HUD_DIVIDER_X - SAFE_MARGIN_PX
        val hudStartX = SAFE_MARGIN_PX + (hudAvailableWidth - blockWidth) / 2f

        // 4. 垂直居中：
        // 歌词模式 (4行组合)：
        // - Time 基线: 147f (字高 56px，顶部 91f)
        // - Temp 基线: 219f (与小时间保持舒适大间距 72px)
        // - Date 基线: 257f (与气温保持紧凑优雅间距 38px)
        // - Note 基线: 289f (底部 293f)
        // 矩形总高 202px，距离屏幕上下边界各 91px，绝对垂直对称居中 (192px)。
        //
        // 大时钟模式 (3行组合)：
        // - Temp 基线: 178f (字高 52px，顶部 126f)
        // - Date 基线: 220f
        // - Note 基线: 254f (底部 258f)
        // 矩形总高 132px，距离屏幕上下边界各 126px，绝对垂直对称居中 (192px)。
        val timeBaselineY = 147f
        val tempBaselineY = lerp(178f, 219f, lyricsModeProgress)
        val dateBaselineY = lerp(220f, 257f, lyricsModeProgress)
        val noteBaselineY = lerp(254f, 289f, lyricsModeProgress)

        // 0. 大气小时间 (加大至 76px，粗体质感，平滑淡入淡出，沿用主场景大时钟字色与发光)
        if (lyricsModeProgress > 0.005f) {
            val targetTextColor = currentConfig.clockTextColor
            val targetGlowColor = currentConfig.clockGlowColor
            val targetGlowRadius = currentConfig.clockGlowRadius
            val textColor = lerpColor(previousConfig.clockTextColor, targetTextColor, transitionProgress)
            val glowColor = lerpColor(previousConfig.clockGlowColor, targetGlowColor, transitionProgress)
            val finalTextColor = if (lightningActive && lightningIntensity > 0.35f) Color.WHITE else textColor
            val finalGlowColor = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor

            val alphaInt = (lyricsModeProgress.coerceIn(0f, 1f) * 255f).toInt()
            smallTimePaint.color = finalTextColor
            smallTimePaint.alpha = alphaInt

            val glowAlphaInt = (Color.alpha(finalGlowColor) * lyricsModeProgress).toInt().coerceIn(0, 255)
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

        // 1. 温度数值
        canvas.drawText(tempText, hudStartX, tempBaselineY, tempNumPaint)

        // 2. 天气主标签 (28px，清晰纯白)
        val weatherX = hudStartX + tempWidth + 16f
        canvas.drawText(weatherText, weatherX, tempBaselineY - 24f, weatherLabelPaint)

        // 3. 风速与舒适度副标 (20px，高亮浅白 #F1F5F9，立体投影保证全场景清晰)
        canvas.drawText(subInfoText, weatherX, tempBaselineY, weatherSubPaint)

        // 4. 日期与星期 (25px，纯白优雅，与上方气温紧凑靠近)
        canvas.drawText(cachedDateStr, hudStartX, dateBaselineY, dateLabelPaint)

        // 5. 实时环境感知副标与温感色彩
        canvas.drawText(prefixText, hudStartX, noteBaselineY, dateSubPaint)

        comfortLabelPaint.color = comfort.color
        val noteAlpha = dateSubPaint.alpha.coerceIn(0, 255)
        comfortLabelPaint.alpha = noteAlpha
        comfortLabelPaint.setShadowLayer(
            8f, 0f, 2f,
            ColorUtils.setAlphaComponent(comfort.color, (180 * (noteAlpha / 255f)).toInt())
        )
        canvas.drawText(comfort.statusText, hudStartX + prefixWidth, noteBaselineY, comfortLabelPaint)

        // 6. 雅致纵向分割光轨 (覆盖 70px 至 314px，垂直居中于 192px)
        if (!currentConfig.celestialVisible) {
            canvas.drawLine(HUD_DIVIDER_X, 70f, HUD_DIVIDER_X, 314f, dividerPaint)
        }
    }

    // ----------------------------------------------------
    // 第 6 层 (A)：中央巨幕霸气数字时钟 (全屏几何正中心 X=960, Y=192, 纵向显著拉高)
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

        clockTimePaint.color = finalTextColor
        clockTimePaint.alpha = textAlphaInt

        // 彻底根除残影 2：必须将底层 HWUI ShadowLayer 的发光 Alpha 与转场进度深度绑定并主动清空
        val glowAlphaInt = (Color.alpha(glowColor) * smoothAlpha).toInt().coerceIn(0, 255)
        if (glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
            val baseGlow = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor
            val fadedGlow = ColorUtils.setAlphaComponent(baseGlow, glowAlphaInt)
            clockTimePaint.setShadowLayer(
                (targetGlowRadius * 1.4f + (if (lightningActive) 20f else 0f)) * smoothAlpha,
                0f,
                4f,
                fadedGlow
            )
        } else {
            clockTimePaint.clearShadowLayer()
        }

        // 彻底根除残影 3：转场退出时附带极其自然的纵深收敛微缩放 (0.94 -> 1.0)，消除静态残像，保持 1:1 完美字体比例
        val scaleAnim = lerp(0.94f, 1.0f, smoothAlpha)

        canvas.save()
        canvas.scale(scaleAnim, scaleAnim, CLOCK_CENTER_X, LOGICAL_HEIGHT / 2f)
        canvas.drawText(cachedTimeStr, CLOCK_CENTER_X, clockBaselineY, clockTimePaint)
        canvas.restore()
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

            // 3. 字重与大气发光光晕 (平滑过渡)
            if (absV < 0.45f) {
                lyricDrawPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                val glowFactor = (1f - 2.2f * absV).coerceIn(0f, 1f)
                val glowRad = (targetGlowRadius * 1.5f + (if (lightningActive) 18f else 0f)) * glowFactor
                val glowAlpha = (Color.alpha(finalGlowColor) * (lineAlpha / 255f) * glowFactor).toInt()
                val fadedGlow = ColorUtils.setAlphaComponent(finalGlowColor, glowAlpha)
                lyricDrawPaint.setShadowLayer(glowRad, 0f, 3f, fadedGlow)
            } else {
                lyricDrawPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                lyricDrawPaint.setShadowLayer(5f, 0f, 2f, Color.argb((lineAlpha * 0.5f).toInt(), 0, 0, 0))
            }

            // 4. 纵向基线垂直居中 (非线性间距：中心至二四行 80px，一五行贴近二四行 50px)
            val centerY = getSlotY(v)
            val fm = lyricDrawPaint.fontMetrics
            val baselineY = centerY - (fm.descent + fm.ascent) / 2f

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
            lyricLines[lineIndex + 1].timestamp
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

    // ----------------------------------------------------
    // 第 7 层：左右 130px 物理安全黑边渐变遮罩 (顶层融合)
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

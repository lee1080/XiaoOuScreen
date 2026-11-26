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
import com.carlyrics.util.AppLogger
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGForceManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.util.SharedHmiAssetManager
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherState
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotRegion
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotRegistry
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotRenderContext
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotRenderer
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType
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
) : FrameLayout(context, attrs, defStyleAttr), Choreographer.FrameCallback {

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
        private const val CLOCK_CACHE_W = 960
        private const val CLOCK_CACHE_H = 384
        private const val CLOCK_CACHE_LEFT = CLOCK_CENTER_X - CLOCK_CACHE_W / 2f

        // 左侧 HUD 信息枢纽坐标 (A 区域: 130..535) 与右侧对称分界 (C 区域: 1385..1790)
        private const val HUD_LEFT_X = 165f
        private const val HUD_DIVIDER_X = 535f
        private const val HUD_RIGHT_DIVIDER_X = 1385f

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
            invalidateBoth()
            onSceneRenderUpdated?.invoke()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                transitionProgress = 1.0f
                previousConfig = currentConfig
                invalidateBoth()
                onSceneRenderUpdated?.invoke()
            }
        })
    }

    private enum class RenderTier { IDLE_1FPS, AMBIENT_30FPS, HIGH_60FPS }

    private var isViewsReady = false
    private var lastFgTimeStr: String = ""
    private var lastFgDateStr: String = ""
    private var lastFgBurnInX: Float = Float.NaN
    private var lastFgBurnInY: Float = Float.NaN
    private var wasLightningFg: Boolean = false
    private var activeLineMeasuredIdx: Int = -1
    private var activeLineMeasuredSafeHalfW: Float = -1f
    private var activeLineMeasuredWidth: Float = 0f
    private var lastActiveMarqueeOffset: Float = Float.NaN

    private val bgView: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawBackgroundLayer(canvas)
        }
    }

    private val fgView: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawForegroundLayer(canvas)
        }
    }

    private fun invalidateBg() {
        if (isViewsReady) bgView.invalidate()
    }

    private fun invalidateFg() {
        if (isViewsReady) fgView.invalidate()
    }

    private fun invalidateBoth() {
        if (isViewsReady) {
            bgView.invalidate()
            fgView.invalidate()
        }
    }

    override fun invalidate() {
        super.invalidate()
        if (isViewsReady) {
            bgView.invalidate()
            fgView.invalidate()
        }
    }

    private var isRenderingActive = false
    private var isPausedManually = false
    private var lastFrameTimeNanos = 0L
    private var lastRenderFrameNanos = 0L
    private var slowFrameStreak = 0
    private var adaptiveAmbientThrottleNs = 29_000_000L
    private var adaptiveHighThrottleNs = 0L
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

    // 零 GC 太阳本体与云层预染色缓存 (消除每帧全屏 ColorFilter 混合开销)
    private var cachedSunTintColor: Int? = null
    private var cachedSunColorFilter: PorterDuffColorFilter? = null
    private var cachedCloudTintColor: Int = 0
    private var cachedCloudColorFilter: PorterDuffColorFilter? = null
    private val cloudDestRect = RectF()
    private val cloudBakePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cloudBakeCanvas = Canvas()
    private var bakedTintColor: Int = Int.MIN_VALUE
    private var bakedOvercastSrc: Bitmap? = null
    private var bakedOvercastBmp: Bitmap? = null
    private var bakedCirrusSrc: Bitmap? = null
    private var bakedCirrusBmp: Bitmap? = null
    private var bakedCumBackSrc: Bitmap? = null
    private var bakedCumBackBmp: Bitmap? = null
    private var bakedCumFrontSrc: Bitmap? = null
    private var bakedCumFrontBmp: Bitmap? = null
    private var bakedMistSrc: Bitmap? = null
    private var bakedMistBmp: Bitmap? = null

    // 车规级中央大时钟离屏缓存 (仅分钟变更或外观变化时重绘，消除 30~60FPS setShadowLayer 模糊)
    private var cachedClockBitmap: Bitmap? = null
    private var cachedClockCanvas: Canvas? = null
    private val cachedClockPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cachedClockKeyStr: String = ""
    private var cachedClockKeyLiquid: Boolean = false
    private var cachedClockKeyTextColor: Int = 0
    private var cachedClockKeyGlowColor: Int = 0
    private var cachedClockKeyGlowRadius: Float = -1f

    // ==========================================
    // 小欧屏 A / C 可插拔分区渲染引擎 (零 GC 预分配)
    // ==========================================
    private val slotRenderersA: Map<XiaoOuSlotType, XiaoOuSlotRenderer> = XiaoOuSlotRegistry.createSlotRenderers()
    private val slotRenderersC: Map<XiaoOuSlotType, XiaoOuSlotRenderer> = XiaoOuSlotRegistry.createSlotRenderers()
    private val slotRenderContext = XiaoOuSlotRenderContext()

    private var slotAType: XiaoOuSlotType = XiaoOuSlotType.WEATHER
    private var prevSlotAType: XiaoOuSlotType = XiaoOuSlotType.WEATHER
    private var slotATransitionProgress: Float = 1f
    private var slotAAnimator: ValueAnimator? = null

    private var slotCType: XiaoOuSlotType = XiaoOuSlotType.ALBUM_COVER
    private var prevSlotCType: XiaoOuSlotType = XiaoOuSlotType.ALBUM_COVER
    private var slotCTransitionProgress: Float = 1f
    private var slotCAnimator: ValueAnimator? = null

    // C 区域随「是否显示小欧形象」开关平滑显隐：开启小欧形象时为 0f（不渲染 C 区内容），关闭时为 1f（显示 C 区模块）
    private var slotCVisibilityProgress: Float = 0f
    private var slotCVisibilityAnimator: ValueAnimator? = null

    // 歌曲封面模块实时状态 (异步 320×320 下采样加载，零主线程磁盘 I/O)
    private var slotTrackTitle: String = ""
    private var slotTrackArtist: String = ""
    private var slotMusicPlaying: Boolean = false
    private var albumCoverUrl: String? = null
    private var albumCoverBitmap: Bitmap? = null
    private var coverFadeAlpha: Float = 0f
    private var coverFadeAnimator: ValueAnimator? = null
    private var isAlbumCoverSlotVisible: Boolean = false
    private var albumCoverSlotAlpha: Float = 0f
    private var albumCoverSlotAnimator: ValueAnimator? = null
    private var coverRequestSeq: Long = 0L

    // 12V 蓄电池实车总线状态
    private var batteryInfo: CarGearManager.BatteryInfo = CarGearManager.BatteryInfo()

    // 室外与座舱内温度实车传感状态
    private var carOutsideTempC: Float? = null
    private var carCabinTempC: Float? = null

    // 新能源电机功率实车总线状态 (+kW 输出 / -kW 动能回收)
    private var motorPowerInfo: CarGearManager.MotorPowerInfo = CarGearManager.MotorPowerInfo()
    private var lastMotorPowerUpdateMs: Long = 0L

    // 发动机 / 电机实时转速总线状态
    private var rpmInfo: CarGearManager.RpmInfo = CarGearManager.RpmInfo()
    private var lastRpmUpdateMs: Long = 0L

    // 实车档位状态（用于功率表判断停车/驻车态）
    private var currentGear: String = CarGearManager.getInstance(context).getCurrentGear()

    private fun isMotorPowerSlotActive(): Boolean {
        val activeInA = (slotAType == XiaoOuSlotType.MOTOR_POWER) || (slotATransitionProgress < 0.999f && prevSlotAType == XiaoOuSlotType.MOTOR_POWER)
        val cVisible = !xiaoOuAvoidanceEnabled || slotCVisibilityProgress > 0.005f
        val activeInC = cVisible && ((slotCType == XiaoOuSlotType.MOTOR_POWER) || (slotCTransitionProgress < 0.999f && prevSlotCType == XiaoOuSlotType.MOTOR_POWER))
        return activeInA || activeInC
    }

    private fun isRpmSlotActive(): Boolean {
        val activeInA = (slotAType == XiaoOuSlotType.RPM) || (slotATransitionProgress < 0.999f && prevSlotAType == XiaoOuSlotType.RPM)
        val cVisible = !xiaoOuAvoidanceEnabled || slotCVisibilityProgress > 0.005f
        val activeInC = cVisible && ((slotCType == XiaoOuSlotType.RPM) || (slotCTransitionProgress < 0.999f && prevSlotCType == XiaoOuSlotType.RPM))
        return activeInA || activeInC
    }

    // 车载 G 值加速度传感器按需订阅状态（仅当画布可见且 A/C 区展示 G 值准星时激活，零后台能耗）
    private var gForceData: CarGForceManager.GForceData = CarGForceManager.GForceData()
    private var isGForceSubscribed: Boolean = false
    private val gForceListener = object : CarGForceManager.Listener {
        override fun onGForceChanged(data: CarGForceManager.GForceData) {
            gForceData = data
            invalidateFg()
        }
    }

    private fun isGForceSlotActive(): Boolean {
        val activeInA = (slotAType == XiaoOuSlotType.G_FORCE) || (slotATransitionProgress < 0.999f && prevSlotAType == XiaoOuSlotType.G_FORCE)
        val cVisible = !xiaoOuAvoidanceEnabled || slotCVisibilityProgress > 0.005f
        val activeInC = cVisible && ((slotCType == XiaoOuSlotType.G_FORCE) || (slotCTransitionProgress < 0.999f && prevSlotCType == XiaoOuSlotType.G_FORCE))
        return activeInA || activeInC
    }

    private fun updateGForceSubscription() {
        val shouldListen = isRenderingActive && isGForceSlotActive()
        if (shouldListen && !isGForceSubscribed) {
            isGForceSubscribed = true
            try {
                CarGForceManager.getInstance(context).registerListener(gForceListener)
            } catch (_: Throwable) {
                isGForceSubscribed = false
            }
        } else if (!shouldListen && isGForceSubscribed) {
            isGForceSubscribed = false
            try {
                CarGForceManager.getInstance(context).unregisterListener(gForceListener)
            } catch (_: Throwable) {}
        }
    }

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

    // 中央歌词亮背景自适应柔光暗衬 (零 GC 缓存 RadialGradient + Matrix，白天多云/雪景显著提升歌词可读性)
    private val lyricsScrimMatrix = Matrix()
    private val lyricsScrimShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.argb(255, 6, 18, 36),
            Color.argb(195, 6, 18, 36),
            Color.argb(80, 6, 18, 36),
            Color.argb(0, 6, 18, 36)
        ),
        floatArrayOf(0f, 0.42f, 0.75f, 1f),
        Shader.TileMode.CLAMP
    )
    private val lyricsScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = lyricsScrimShader
    }
    private val lyricsScrimRect = RectF()

    // ==========================================
    // 转向灯三边空心渐隐矩形与变道盲区监测预警 (零 GC 预分配)
    // ==========================================
    private var isTurnSignalSyncEnabled: Boolean = PrefsManager.isTurnSignalSyncEnabled()
    private var isLaneChangeAssistEnabled: Boolean = PrefsManager.isLaneChangeAssistEnabled()

    private var currentLaneChangeAlert: CarGearManager.LaneChangeAlert = CarGearManager.LaneChangeAlert()
    private var currentTurnSignalInfo: CarGearManager.TurnSignalInfo = CarGearManager.TurnSignalInfo()

    private val turnSignalLeftPath = Path()
    private val turnSignalRightPath = Path()
    private var turnSignalLeftShader: LinearGradient? = null
    private var turnSignalRightShader: LinearGradient? = null

    private val turnSignalCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val turnSignalGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 变道辅助大面积渐隐光幕预警 (比转向灯宽出 40px，填满转向灯框)
    private val laneChangeLeftRect = RectF(130f, 0f, 365f, LOGICAL_HEIGHT)
    private val laneChangeRightRect = RectF(1555f, 0f, 1790f, LOGICAL_HEIGHT)

    private var laneChangeLeftYellowShader: LinearGradient? = null
    private var laneChangeLeftRedShader: LinearGradient? = null
    private var laneChangeRightYellowShader: LinearGradient? = null
    private var laneChangeRightRedShader: LinearGradient? = null

    private val laneChangeCurtainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

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

    // 本帧防烧屏微距像素漂移缓存 (0 GC，供前景层与液态玻璃采样严格对齐)
    private var currentBurnInShiftX: Float = 0f
    private var currentBurnInShiftY: Float = 0f

    // ==========================================
    // 中央大时间液态玻璃渲染引擎 (Liquid Glass Clock)
    // 28px 加大背景模糊 / 170% 高动态饱和度 / 10% 白色微光叠层 / 1px 厚度内浮雕 / 晶体折射轮廓
    // ==========================================
    private var isLiquidGlassEnabled: Boolean = false
    private var liquidGlassProgress: Float = 0f
    private var liquidGlassAnimator: ValueAnimator? = null

    // 车规级严格 1920x384 快照复用缓冲 (消除转场时每次 createBitmap 2.95MB 的内存抖动)
    private var reusableSnapshotBitmap: Bitmap? = null
    private var reusableSnapshotCanvas: Canvas? = null

    // 文字 Path 矢量轮廓与缓存判断
    private val glassTextPath = Path()
    private val glassTextBounds = RectF()
    private var glassCachedTimeForPath = ""

    // 液态玻璃 Shader 缓存 (防每帧 new LinearGradient)
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
            invalidateFg()
            return
        }
        val target = if (enabled) 1f else 0f
        liquidGlassAnimator = ValueAnimator.ofFloat(liquidGlassProgress, target).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                liquidGlassProgress = it.animatedValue as Float
                invalidateFg()
            }
            start()
        }
        wakeUpAnimation()
    }

    fun isLiquidGlassClockActive(): Boolean = isLiquidGlassEnabled

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
    }

    private val rightDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            invalidateFg()
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
    }

    private val callSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.03f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#E2E8F0")
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
        wakeUpAnimation()
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
            invalidateFg()
            onSceneRenderUpdated?.invoke()
        }
    }

    // 歌词逐句连续浮点滚动与字号缩放丝滑过渡动画器 (统一 750ms，配合平滑 S 型缓动曲线，肉眼清晰可见字号缩放)
    private val lineScrollAnimator = ValueAnimator().apply {
        duration = 750L
        interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)
        addUpdateListener {
            animatedLineIndex = it.animatedValue as Float
            invalidateFg()
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

    private fun getSlotAlpha(absV: Float, brightFactor: Float = 0f): Float {
        val midAlpha = lerp(0.60f, 0.78f, brightFactor)
        val edgeAlpha = lerp(0.28f, 0.48f, brightFactor)
        return when {
            absV <= 1f -> lerp(1.0f, midAlpha, absV)
            absV <= 2f -> lerp(midAlpha, edgeAlpha, absV - 1f)
            else -> lerp(edgeAlpha, 0.0f, (absV - 2f).coerceIn(0f, 1f))
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
        var cachedDrawX = 0f
    }

    private class Star {
        var x = 0f
        var y = 0f
        var radius = 0f
        var baseAlpha = 0f
        var twinkleSpeed = 0f
        var phase = 0f
        var color = Color.WHITE
        var cachedTwinkle = 1f
    }

    private val rainPool = Array(MAX_RAIN_PARTICLES) { RainDrop() }
    private val snowPool = Array(MAX_SNOW_PARTICLES) { SnowFlake() }
    private val starPool = Array(MAX_STARS) { Star() }

    // 零 GC 合批降雨顶点缓冲 (将 160 次 drawLine 状态切换压缩为 3 次 canvas.drawLines)
    private val rainFgBrightPts = FloatArray(MAX_RAIN_PARTICLES * 4)
    private val rainFgMidPts = FloatArray(MAX_RAIN_PARTICLES * 4)
    private val rainBgPts = FloatArray(MAX_RAIN_PARTICLES * 4)

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
    }
    private val topMusicIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var topMusicIconBmp: Bitmap? = null
    private val topMusicCapsuleRect = RectF()

    private var topMusicTitle: String = ""
    private var topMusicArtist: String = ""
    private var topMusicDisplayText: String = ""
    private var cachedTopMusicRawText: String = ""
    private var cachedTopMusicMaxWidth: Float = -1f
    private var cachedTopMusicEllipsizedText: String = ""
    private var cachedTopMusicEllipsizedWidth: Float = 0f
    private var isTopMusicPlaying: Boolean = false
    private var topMusicAlpha: Float = 0f
    private var topMusicTargetAlpha: Float = 0f
    private var topMusicAlphaAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(true)
        clipChildren = false
        clipToPadding = false
        addView(bgView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fgView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isViewsReady = true

        val fm = clockTimePaint.fontMetrics
        clockBaselineY = (LOGICAL_HEIGHT / 2f) - (fm.descent + fm.ascent) / 2f

        initVignetteShaders()
        initDividerShader()
        initCelestialAndCloudBitmaps()
        initParticlePools()
        initStars()
        initTopMusicIsland()
        initTurnSignalAndAdasGraphics()

        try {
            isLiquidGlassEnabled = PrefsManager.isLiquidGlassClockEnabled()
            liquidGlassProgress = if (isLiquidGlassEnabled) 1f else 0f
            xiaoOuAvoidanceEnabled = PrefsManager.isXiaoOuAvatarEnabled()
            slotCVisibilityProgress = if (xiaoOuAvoidanceEnabled) 0f else 1f
            slotAType = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
            prevSlotAType = slotAType
            slotCType = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
            prevSlotCType = slotCType
        } catch (_: Throwable) {
            isLiquidGlassEnabled = false
            liquidGlassProgress = 0f
            xiaoOuAvoidanceEnabled = true
            slotCVisibilityProgress = 0f
            slotAType = XiaoOuSlotType.WEATHER
            prevSlotAType = XiaoOuSlotType.WEATHER
            slotCType = XiaoOuSlotType.ALBUM_COVER
            prevSlotCType = XiaoOuSlotType.ALBUM_COVER
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
            AppLogger.e(TAG, "加载顶部音乐胶囊图标失败: ${t.message}", t)
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

    private fun initTurnSignalAndAdasGraphics() {
        // 1. 左转向灯：贴着左边边缘的三边 (垂直高 352, 水平向中心延伸至 X=325)
        // 垂直边: (130, 16) -> (130, 368); 水平边向右: X=130 -> 325
        turnSignalLeftPath.reset()
        turnSignalLeftPath.moveTo(325f, 16f)
        turnSignalLeftPath.lineTo(130f, 16f)
        turnSignalLeftPath.lineTo(130f, 368f)
        turnSignalLeftPath.lineTo(325f, 368f)

        turnSignalLeftShader = LinearGradient(
            130f, 0f, 325f, 0f,
            intArrayOf(0xFFFFA000.toInt(), 0x00FFA000),
            null,
            Shader.TileMode.CLAMP
        )

        // 2. 右转向灯：贴着右边边缘的三边 (垂直高 352, 水平向中心延伸至 X=1595)
        // 垂直边: (1790, 16) -> (1790, 368); 水平边向左: X=1790 -> 1595
        turnSignalRightPath.reset()
        turnSignalRightPath.moveTo(1595f, 16f)
        turnSignalRightPath.lineTo(1790f, 16f)
        turnSignalRightPath.lineTo(1790f, 368f)
        turnSignalRightPath.lineTo(1595f, 368f)

        turnSignalRightShader = LinearGradient(
            1790f, 0f, 1595f, 0f,
            intArrayOf(0xFFFFA000.toInt(), 0x00FFA000),
            null,
            Shader.TileMode.CLAMP
        )

        // 3. 变道辅助大面积半透明渐变矩形光幕 (比转向灯宽出 40px，填满转向灯框)
        laneChangeLeftYellowShader = LinearGradient(
            130f, 0f, 365f, 0f,
            intArrayOf(0xFFFFA000.toInt(), 0x00FFA000),
            null,
            Shader.TileMode.CLAMP
        )
        laneChangeLeftRedShader = LinearGradient(
            130f, 0f, 365f, 0f,
            intArrayOf(0xFFFF2222.toInt(), 0x00FF2222),
            null,
            Shader.TileMode.CLAMP
        )
        laneChangeRightYellowShader = LinearGradient(
            1790f, 0f, 1555f, 0f,
            intArrayOf(0xFFFFA000.toInt(), 0x00FFA000),
            null,
            Shader.TileMode.CLAMP
        )
        laneChangeRightRedShader = LinearGradient(
            1790f, 0f, 1555f, 0f,
            intArrayOf(0xFFFF2222.toInt(), 0x00FF2222),
            null,
            Shader.TileMode.CLAMP
        )
    }

    private fun initDividerShader() {
        val colors = intArrayOf(
            Color.TRANSPARENT,
            Color.argb(120, 148, 163, 184),
            Color.TRANSPARENT
        )
        val stops = floatArrayOf(0f, 0.5f, 1f)
        dividerPaint.shader = LinearGradient(
            HUD_DIVIDER_X, 70f, HUD_DIVIDER_X, 314f,
            colors, stops, Shader.TileMode.CLAMP
        )
        rightDividerPaint.shader = LinearGradient(
            HUD_RIGHT_DIVIDER_X, 70f, HUD_RIGHT_DIVIDER_X, 314f,
            colors, stops, Shader.TileMode.CLAMP
        )
    }

    private fun initCelestialAndCloudBitmaps() {
        // 移至后台工作线程并发解码主要天体，体积云按需懒加载，彻底释放冷启动负荷与堆内存
        Thread {
            try {
                // 仅预加载核心天体素材
                bmpSun = SharedHmiAssetManager.getCelestialBitmap(resources, "sun")
                bmpSunRays = SharedHmiAssetManager.getCelestialBitmap(resources, "sun_rays")
                bmpSunFlare = SharedHmiAssetManager.getCelestialBitmap(resources, "sun_flare")
                if (currentConfig.timeOfDay == TimeOfDay.NIGHT) {
                    bmpMoon = SharedHmiAssetManager.getCelestialBitmap(resources, "moon")
                }
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
        snow.cachedDrawX = snow.x + sin(snow.swayPhase.toDouble()).toFloat() * snow.swayAmplitude
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

    fun updateScene(newConfig: WeatherSceneConfig, animate: Boolean = true) {
        slotRenderersA.values.forEach { it.invalidateData() }
        slotRenderersC.values.forEach { it.invalidateData() }
        if (isVisuallyEqual(newConfig, currentConfig)) {
            val needsRepaint = currentConfig.temperatureCelsius != newConfig.temperatureCelsius ||
                currentConfig.isMinuteRainActive != newConfig.isMinuteRainActive
            currentConfig = newConfig
            if (needsRepaint) {
                invalidateBoth()
                onSceneRenderUpdated?.invoke()
            }
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
        wakeUpAnimation()
        onSceneRenderUpdated?.invoke()
    }

    fun triggerInstantLightning() {
        triggerLightning(SystemClock.uptimeMillis())
        wakeUpAnimation()
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
        removeCallbacks(clockTickRunnable)
        isClockTickerRunning = false
    }

    fun resumeAnimation() {
        isPausedManually = false
        evaluateRenderState()
        invalidateBoth()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        evaluateRenderState()
        invalidateBoth()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllAnimators()
        stopAnimation()
        removeCallbacks(clockTickRunnable)
        isClockTickerRunning = false
    }

    private fun cancelAllAnimators() {
        transitionAnimator.cancel()
        slotAAnimator?.cancel()
        slotCAnimator?.cancel()
        slotCVisibilityAnimator?.cancel()
        liquidGlassAnimator?.cancel()
        callTransitionAnimator.cancel()
        lyricsModeAnimator.cancel()
        lineScrollAnimator.cancel()
        topMusicAlphaAnimator?.cancel()
        coverFadeAnimator?.cancel()
        albumCoverSlotAnimator?.cancel()
    }

    /**
     * 彻底销毁释放写实画布资源与位图引用
     */
    fun destroy() {
        stopAnimation()
        cancelAllAnimators()
        if (isGForceSubscribed) {
            isGForceSubscribed = false
            try {
                CarGForceManager.getInstance(context).unregisterListener(gForceListener)
            } catch (_: Throwable) {}
        }
        bmpSun = null
        bmpSunRays = null
        bmpSunFlare = null
        bmpMoon = null
        bmpCallIncomingHandset = null
        bmpCallIncomingArrow = null
        bmpCallIncomingRipple = null
        bmpCallOutgoingHandset = null
        bmpCallOutgoingArrow = null
        bmpCallOutgoingWave1 = null
        bmpCallOutgoingWave2 = null
        bmpCallConnectedHandset = null
        bmpCallConnectedWave1 = null
        bmpCallConnectedWave2 = null
        bmpCallConnectedWave3 = null
        bmpCallHangupHandset = null
        bmpCallHangupHalo = null
        bmpCallWeChatBack = null
        bmpCallWeChatFront = null
        bmpCallWeChatWave = null
        isCallBitmapsLoaded = false
        onSceneRenderUpdated = null
        reusableSnapshotBitmap?.recycle()
        reusableSnapshotBitmap = null
        reusableSnapshotCanvas = null
        cachedClockBitmap?.recycle()
        cachedClockBitmap = null
        cachedClockCanvas = null
        bakedOvercastBmp?.recycle(); bakedOvercastBmp = null; bakedOvercastSrc = null
        bakedCirrusBmp?.recycle(); bakedCirrusBmp = null; bakedCirrusSrc = null
        bakedCumBackBmp?.recycle(); bakedCumBackBmp = null; bakedCumBackSrc = null
        bakedCumFrontBmp?.recycle(); bakedCumFrontBmp = null; bakedCumFrontSrc = null
        bakedMistBmp?.recycle(); bakedMistBmp = null; bakedMistSrc = null
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
        invalidateBoth()
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        evaluateRenderState()
    }

    private fun evaluateRenderState() {
        if (isPausedManually) {
            if (isRenderingActive) stopAnimation()
            removeCallbacks(clockTickRunnable)
            isClockTickerRunning = false
            return
        }
        val shouldRender = isAttachedToWindow && (visibility == VISIBLE) && (windowVisibility == VISIBLE) && (alpha > 0.01f) && isShown
        if (!shouldRender) {
            stopAnimation()
            removeCallbacks(clockTickRunnable)
            isClockTickerRunning = false
            return
        }
        if (isContinuousAnimationNeeded()) {
            removeCallbacks(clockTickRunnable)
            isClockTickerRunning = false
            if (!isRenderingActive) {
                startAnimation()
            }
        } else {
            stopAnimation()
            if (!isClockTickerRunning) {
                isClockTickerRunning = true
                post(clockTickRunnable)
            }
        }
    }

    // =========================================================================
    // 车规级能效渲染状态机：
    // 稳态仅时钟走时时降入 0~1 FPS（彻底停止 Choreographer 逐帧调用，CPU < 1%）
    // 仅在有雨雪粒子、雷闪、转场动效或车辆高频动态变动时，按需激活 60 FPS 物理渲染
    // =========================================================================
    private var isClockTickerRunning = false
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || isPausedManually) {
                isClockTickerRunning = false
                return
            }
            if (isContinuousAnimationNeeded()) {
                isClockTickerRunning = false
                startAnimation()
                return
            }
            // 车规级稳态低功耗走时：无云晴天/晴夜彻底退出 Choreographer 逐帧唤醒
            val nowUnixSeconds = System.currentTimeMillis() / 1000L
            if (nowUnixSeconds != lastClockSecond) {
                lastClockSecond = nowUnixSeconds
                val prevTimeStr = cachedTimeStr
                dateReusable.time = System.currentTimeMillis()
                cachedTimeStr = timeFormat.format(dateReusable)
                cachedDateStr = dateFormat.format(dateReusable)

                val hasStars = currentConfig.starsVisible || previousConfig.starsVisible
                val timeChanged = cachedTimeStr != prevTimeStr
                if (hasStars || timeChanged || transitionProgress < 0.999f) {
                    updatePhysics(1.0f)
                    if (hasStars || transitionProgress < 0.999f) {
                        invalidateBg()
                    }
                }
                if (needsForegroundRefresh()) {
                    invalidateFg()
                }
            }
            val nowMs = System.currentTimeMillis()
            val delayToNextSecond = (1000L - (nowMs % 1000L)).coerceIn(50L, 1000L)
            postDelayed(this, delayToNextSecond)
        }
    }

    private fun computeDesiredRenderTier(): RenderTier {
        if (!isAttachedToWindow || visibility != VISIBLE || windowVisibility != VISIBLE || alpha <= 0.01f || !isShown || isPausedManually) {
            return RenderTier.IDLE_1FPS
        }
        val isParticlesActive = (currentConfig.rainParticleCount > 0) || (currentConfig.snowParticleCount > 0) || lightningActive
        val isTransitionActive = (transitionProgress < 0.999f) ||
                (lyricsModeProgress > 0.001f && lyricsModeProgress < 0.999f) ||
                (slotATransitionProgress < 0.999f) ||
                (slotCTransitionProgress < 0.999f) ||
                (slotCVisibilityProgress > 0.001f && slotCVisibilityProgress < 0.999f) ||
                (coverFadeAlpha > 0.001f && coverFadeAlpha < 0.999f) ||
                (albumCoverSlotAlpha > 0.001f && albumCoverSlotAlpha < 0.999f) ||
                (liquidGlassProgress > 0.001f && liquidGlassProgress < 0.999f) ||
                (callTransitionProgress > 0.001f && callTransitionProgress < 0.999f) ||
                (callInfo.isDisplayActive && callInfo.state != CarCallManager.CallState.IDLE)
        val isDynamicsActive = (isGForceSlotActive() && (gForceData.totalG > 0.015f || gForceData.peakG > 0.015f)) ||
                (isMotorPowerSlotActive() && (SystemClock.uptimeMillis() - lastMotorPowerUpdateMs < 900L)) ||
                (isRpmSlotActive() && (SystemClock.uptimeMillis() - lastRpmUpdateMs < 900L)) ||
                currentTurnSignalInfo.isAnyTurnActive ||
                currentLaneChangeAlert.hasAnyWarning
        val isLyricsTransitioning = lineScrollAnimator.isRunning

        if (isParticlesActive || isTransitionActive || isDynamicsActive || isLyricsTransitioning) {
            return RenderTier.HIGH_60FPS
        }

        val hasAmbientClouds = currentConfig.cloudCirrusAlpha > 0.006f ||
                currentConfig.cloudCumulusBackAlpha > 0.006f ||
                currentConfig.cloudCumulusFrontAlpha > 0.006f ||
                currentConfig.cloudMistAlpha > 0.006f ||
                currentConfig.cloudOvercastAlpha > 0.006f

        return if (hasAmbientClouds) {
            RenderTier.AMBIENT_30FPS
        } else {
            RenderTier.IDLE_1FPS
        }
    }

    fun isContinuousAnimationNeeded(): Boolean {
        return computeDesiredRenderTier() != RenderTier.IDLE_1FPS
    }

    private fun needsForegroundRefresh(): Boolean {
        val nowShiftMs = SystemClock.uptimeMillis()
        val nextShiftX = BurnInProtection.getShiftX(nowShiftMs)
        val nextShiftY = BurnInProtection.getShiftY(nowShiftMs)
        if (cachedTimeStr != lastFgTimeStr || cachedDateStr != lastFgDateStr) return true
        if (abs(nextShiftX - lastFgBurnInX) > 0.2f || abs(nextShiftY - lastFgBurnInY) > 0.2f) return true
        if (lightningActive || wasLightningFg) return true
        if (transitionProgress < 0.999f) return true
        if (lyricsModeProgress > 0.001f && lyricsModeProgress < 0.999f) return true
        if (lineScrollAnimator.isRunning) return true
        if (callInfo.isDisplayActive && callInfo.state != CarCallManager.CallState.IDLE) return true
        return (isGForceSlotActive() && (gForceData.totalG > 0.015f || gForceData.peakG > 0.015f)) ||
                (isMotorPowerSlotActive() && (nowShiftMs - lastMotorPowerUpdateMs < 900L)) ||
                (isRpmSlotActive() && (nowShiftMs - lastRpmUpdateMs < 900L)) ||
                currentTurnSignalInfo.isAnyTurnActive ||
                currentLaneChangeAlert.hasAnyWarning
    }

    fun wakeUpAnimation() {
        evaluateRenderState()
        invalidateFg()
        if (!isRenderingActive) {
            invalidateBg()
        }
    }

    private fun startAnimation() {
        removeCallbacks(clockTickRunnable)
        isClockTickerRunning = false
        isRenderingActive = true
        lastFrameTimeNanos = 0L
        lastRenderFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
        updateGForceSubscription()
    }

    private fun stopAnimation() {
        isRenderingActive = false
        lastFrameTimeNanos = 0L
        lastRenderFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        updateGForceSubscription()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRenderingActive) return

        val tier = computeDesiredRenderTier()
        if (tier == RenderTier.IDLE_1FPS) {
            stopAnimation()
            if (!isClockTickerRunning) {
                isClockTickerRunning = true
                post(clockTickRunnable)
            }
            return
        }

        // 环境飘云稳态采用 ~15 FPS 节能节拍，减少 50% GPU/RenderThread 负荷的同时保持柔顺视差漂移
        val minIntervalNs = when (tier) {
            RenderTier.HIGH_60FPS -> adaptiveHighThrottleNs
            RenderTier.AMBIENT_30FPS -> adaptiveAmbientThrottleNs.coerceAtLeast(62_000_000L)
            RenderTier.IDLE_1FPS -> 1_000_000_000L
        }

        if (lastRenderFrameNanos != 0L && (frameTimeNanos - lastRenderFrameNanos) < minIntervalNs) {
            val remainingMs = ((minIntervalNs - (frameTimeNanos - lastRenderFrameNanos)) / 1_000_000L).coerceAtLeast(1L)
            Choreographer.getInstance().postFrameCallbackDelayed(this, remainingMs)
            return
        }

        if (lastFrameTimeNanos != 0L) {
            val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
            val clampedDelta = deltaSeconds.coerceIn(0.001f, 0.08f)
            updatePhysics(clampedDelta)
        }
        lastFrameTimeNanos = frameTimeNanos
        lastRenderFrameNanos = frameTimeNanos

        invalidateBg()
        if (needsForegroundRefresh()) {
            invalidateFg()
        }
        if (minIntervalNs > 0L) {
            val delayMs = (minIntervalNs / 1_000_000L - 4L).coerceAtLeast(1L)
            Choreographer.getInstance().postFrameCallbackDelayed(this, delayMs)
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun logicalCloudWidth(bmp: Bitmap): Float {
        val h = bmp.height.coerceAtLeast(1)
        return bmp.width * (LOGICAL_HEIGHT / h)
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

        // 2. iOS 风格体积云：拟真风速驱动与防眩晕视差漂移
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

        // 车规级长周期浮点防护：按逻辑平铺宽度取模，保证 scrollX 严格处于 [0, logicalW) 区间
        bmpCirrus?.takeIf { !it.isRecycled }?.let { bmp -> val w = logicalCloudWidth(bmp); if (scrollCirrusX >= w) scrollCirrusX %= w }
        bmpCumulusBack?.takeIf { !it.isRecycled }?.let { bmp -> val w = logicalCloudWidth(bmp); if (scrollCumulusBackX >= w) scrollCumulusBackX %= w }
        bmpCumulusFront?.takeIf { !it.isRecycled }?.let { bmp -> val w = logicalCloudWidth(bmp); if (scrollCumulusFrontX >= w) scrollCumulusFrontX %= w }
        bmpMist?.takeIf { !it.isRecycled }?.let { bmp -> val w = logicalCloudWidth(bmp); if (scrollMistX >= w) scrollMistX %= w }
        bmpOvercast?.takeIf { !it.isRecycled }?.let { bmp -> val w = logicalCloudWidth(bmp); if (scrollOvercastX >= w) scrollOvercastX %= w }

        // 2.5 星空闪烁在物理步进中集中计算，避免 draw 阶段重复 sin()
        if (currentConfig.starsVisible || previousConfig.starsVisible) {
            val timeSec = now / 1000f
            for (s in starPool) {
                s.cachedTwinkle = sin((timeSec * s.twinkleSpeed + s.phase).toDouble()).toFloat() * 0.5f + 0.5f
            }
        }

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

        // 4. 雪花推进 (同步预计算横摆坐标)
        val snowCount = currentConfig.snowParticleCount
        if (snowCount > 0) {
            for (i in 0 until snowCount) {
                val snow = snowPool[i]
                snow.y += snow.speedY * dt
                snow.swayPhase += snow.swaySpeed * dt
                if (snow.y > LOGICAL_HEIGHT + 10f) {
                    resetSnowFlake(snow)
                } else {
                    snow.cachedDrawX = snow.x + sin(snow.swayPhase.toDouble()).toFloat() * snow.swayAmplitude
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
    // 核心双层分离绘制流水线 (1920 × 384 统一视口)
    // ==========================================
    private inline fun withLogicalViewport(canvas: Canvas, drawBlock: (Canvas) -> Unit) {
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
        drawBlock(canvas)
        canvas.restore()
    }

    private fun drawBackgroundLayer(canvas: Canvas) {
        val drawStartNs = System.nanoTime()
        withLogicalViewport(canvas) { c ->
            // 第 1 层：写实天空 4-Stop 瑞利散射自然渐变
            drawPhotorealisticSky(c)

            // 第 2 层：写实天体 (HDR 太阳 + 宽银幕耀斑 / 天文级真实月球 / 星空)
            drawPhotorealisticCelestial(c)

            // 第 3 层：体积云层 (1/2 分辨率预染色缓存)
            drawPhotorealisticClouds(c)

            // 第 4 层：物理天气粒子 (合批雨丝 / 飞雪 / 分形电闪)
            drawWeatherParticles(c)
        }

        // 车规级自适应帧耗监控：高负载时平滑降档帧率，保障车机主导航与语音绝对流畅
        val drawElapsedNs = System.nanoTime() - drawStartNs
        if (drawElapsedNs > 18_000_000L) {
            slowFrameStreak = (slowFrameStreak + 1).coerceAtMost(10)
            if (slowFrameStreak >= 3) {
                adaptiveAmbientThrottleNs = 45_000_000L
                adaptiveHighThrottleNs = 29_000_000L
            }
        } else if (drawElapsedNs < 12_000_000L && slowFrameStreak > 0) {
            slowFrameStreak--
            if (slowFrameStreak == 0) {
                adaptiveAmbientThrottleNs = 29_000_000L
                adaptiveHighThrottleNs = 0L
            }
        }
    }

    private fun drawForegroundLayer(canvas: Canvas) {
        withLogicalViewport(canvas) { c ->
            // 第 5~7 层（静态常亮前景 HUD / 时钟 / 五行歌词 / 顶部音乐胶囊）：应用全局同步微距像素漂移防烧屏
            val nowShiftMs = SystemClock.uptimeMillis()
            currentBurnInShiftX = BurnInProtection.getShiftX(nowShiftMs)
            currentBurnInShiftY = BurnInProtection.getShiftY(nowShiftMs)

            lastFgTimeStr = cachedTimeStr
            lastFgDateStr = cachedDateStr
            lastFgBurnInX = currentBurnInShiftX
            lastFgBurnInY = currentBurnInShiftY
            wasLightningFg = lightningActive

            c.save()
            c.translate(currentBurnInShiftX, currentBurnInShiftY)

            prepareSlotRenderContext(nowShiftMs)

            // 第 5 层 (A 区域)：左侧可插拔功能槽
            drawSlotA(c)

            // 第 6 层 (B 区域)：中央霸气时钟与五行居中歌词动态平滑切换
            drawHugeAtmosphericClock(c, 1f - lyricsModeProgress)
            drawFiveLineLyrics(c, lyricsModeProgress)

            // 第 6.5 层 (C 区域)：右侧可插拔功能槽
            drawSlotC(c)

            // 第 7 层：顶部居中高亮音乐胶囊
            drawTopMusicIsland(c)

            c.restore()

            // 第 8 层：左右 130px 物理安全黑边渐变融合遮罩 (最顶层，固定不动防露边)
            drawEdgeVignette(c)
        }
    }

    /**
     * 将写实天气与时钟绘制到指定的严格 1920×384 Canvas 上 (用于高精度快照合成或磨砂玻璃底层采样)
     */
    fun drawStrict1920(c: Canvas, backgroundOnlyForGlassSample: Boolean = false) {
        c.save()
        c.clipRect(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        drawPhotorealisticSky(c)
        drawPhotorealisticCelestial(c)
        drawPhotorealisticClouds(c)
        if (backgroundOnlyForGlassSample) {
            c.restore()
            return
        }

        // 确保时钟字符串为当前系统真实最新时间，即使在能耗暂停期间截图也能即时反映当前时刻
        val nowUnixSeconds = System.currentTimeMillis() / 1000L
        if (nowUnixSeconds != lastClockSecond) {
            lastClockSecond = nowUnixSeconds
            dateReusable.time = System.currentTimeMillis()
            cachedTimeStr = timeFormat.format(dateReusable)
            cachedDateStr = dateFormat.format(dateReusable)
        }

        drawWeatherParticles(c)

        val nowShiftMs = SystemClock.uptimeMillis()
        currentBurnInShiftX = BurnInProtection.getShiftX(nowShiftMs)
        currentBurnInShiftY = BurnInProtection.getShiftY(nowShiftMs)
        c.save()
        c.translate(currentBurnInShiftX, currentBurnInShiftY)
        prepareSlotRenderContext(nowShiftMs)
        drawSlotA(c)
        drawHugeAtmosphericClock(c, 1f - lyricsModeProgress)
        drawFiveLineLyrics(c, lyricsModeProgress)
        drawSlotC(c)
        drawTopMusicIsland(c)
        c.restore()

        drawEdgeVignette(c)

        c.restore()
    }

    /**
     * 截取纯净严格 1920×384 分辨率的屏幕快照
     * 消除宿主屏幕宽高比畸变、消除黑边错位，保证 3D 档位立方体翻转无缝咬合
     */
    fun captureStrict1920Snapshot(): Bitmap? {
        return try {
            val bmp = reusableSnapshotBitmap?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(
                LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888
            ).also {
                reusableSnapshotBitmap = it
                reusableSnapshotCanvas = Canvas(it)
            }
            val c = reusableSnapshotCanvas ?: Canvas(bmp).also { reusableSnapshotCanvas = it }
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawStrict1920(c)
            bmp
        } catch (t: Throwable) {
            AppLogger.e(TAG, "截取严格 1920x384 快照失败: ${t.message}", t)
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

        // 1. 写实星空 (物理步进已预计算 twinkle，避免 draw 阶段重复 sin() 运算)
        if (currentConfig.starsVisible || previousConfig.starsVisible) {
            val starAlphaFactor = if (currentConfig.starsVisible) transitionProgress else (1f - transitionProgress)
            for (s in starPool) {
                val alphaInt = (s.baseAlpha * s.cachedTwinkle * starAlphaFactor * 255f).toInt().coerceIn(0, 255)
                if (alphaInt < 8) continue
                starPaint.color = s.color
                starPaint.alpha = alphaInt
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
    private fun getOrBakeTintedCloud(
        src: Bitmap?,
        tint: Int,
        prevSrc: Bitmap?,
        prevBaked: Bitmap?,
        assignBaked: (Bitmap, Bitmap) -> Unit
    ): Bitmap? {
        if (src == null || src.isRecycled) return null
        if (bakedTintColor == tint && prevSrc === src && prevBaked != null && !prevBaked.isRecycled) {
            return prevBaked
        }
        return try {
            val out = if (prevBaked != null && !prevBaked.isRecycled &&
                prevBaked.width == src.width && prevBaked.height == src.height
            ) {
                prevBaked
            } else {
                prevBaked?.recycle()
                Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            }
            out.eraseColor(Color.TRANSPARENT)
            if (cachedCloudColorFilter == null || cachedCloudTintColor != tint) {
                cachedCloudTintColor = tint
                cachedCloudColorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
            }
            cloudBakePaint.colorFilter = cachedCloudColorFilter
            cloudBakeCanvas.setBitmap(out)
            cloudBakeCanvas.drawBitmap(src, 0f, 0f, cloudBakePaint)
            cloudBakeCanvas.setBitmap(null)
            assignBaked(src, out)
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawPhotorealisticClouds(canvas: Canvas) {
        val cirrusA = lerp(previousConfig.cloudCirrusAlpha, currentConfig.cloudCirrusAlpha, transitionProgress)
        val cumBackA = lerp(previousConfig.cloudCumulusBackAlpha, currentConfig.cloudCumulusBackAlpha, transitionProgress)
        val cumFrontA = lerp(previousConfig.cloudCumulusFrontAlpha, currentConfig.cloudCumulusFrontAlpha, transitionProgress)
        val mistA = lerp(previousConfig.cloudMistAlpha, currentConfig.cloudMistAlpha, transitionProgress)
        val overcastA = lerp(previousConfig.cloudOvercastAlpha, currentConfig.cloudOvercastAlpha, transitionProgress)

        // 性能关键优化：如果当前场景完全无任何云层显示（如大晴天/纯净星空），直接跳过全部云层解码与绘制
        if (cirrusA <= 0.006f && cumBackA <= 0.006f && cumFrontA <= 0.006f && mistA <= 0.006f && overcastA <= 0.006f) {
            return
        }

        var tint = lerpColor(previousConfig.cloudTintColor, currentConfig.cloudTintColor, transitionProgress)

        // 雷暴电闪瞬间激发云层内部高光散射
        val isFlashing = lightningActive && lightningIntensity > 0.1f
        if (isFlashing) {
            tint = ColorUtils.blendARGB(tint, Color.parseColor("#F5D0FE"), lightningIntensity * 0.85f)
        }

        val canUsePreBaked = !isFlashing && transitionProgress >= 0.999f
        if (canUsePreBaked) {
            if (bakedTintColor != tint) {
                // 新稳态色调：标记失效，由各活跃层懒加载单次烘焙
                bakedOvercastSrc = null
                bakedCirrusSrc = null
                bakedCumBackSrc = null
                bakedCumFrontSrc = null
                bakedMistSrc = null
                bakedTintColor = tint
            }
            cloudOvercastPaint.colorFilter = null
            cloudCirrusPaint.colorFilter = null
            cloudCumulusBackPaint.colorFilter = null
            cloudCumulusFrontPaint.colorFilter = null
            cloudMistPaint.colorFilter = null
        } else {
            if (cachedCloudColorFilter == null || cachedCloudTintColor != tint) {
                cachedCloudTintColor = tint
                cachedCloudColorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
            }
            val colorFilter = cachedCloudColorFilter
            cloudCirrusPaint.colorFilter = colorFilter
            cloudCumulusBackPaint.colorFilter = colorFilter
            cloudCumulusFrontPaint.colorFilter = colorFilter
            cloudMistPaint.colorFilter = colorFilter
            cloudOvercastPaint.colorFilter = colorFilter
        }

        if (overcastA > 0.006f) {
            val src = bmpOvercast?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "overcast")?.also { bmpOvercast = it }
            val drawBmp = if (canUsePreBaked) getOrBakeTintedCloud(src, tint, bakedOvercastSrc, bakedOvercastBmp) { s, b -> bakedOvercastSrc = s; bakedOvercastBmp = b } ?: src else src
            drawSeamlessCloudLayer(canvas, drawBmp, scrollOvercastX, overcastA, cloudOvercastPaint)
        } else if (bakedOvercastBmp != null) {
            bakedOvercastBmp?.recycle(); bakedOvercastBmp = null; bakedOvercastSrc = null
        }
        if (cirrusA > 0.006f) {
            val src = bmpCirrus?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cirrus")?.also { bmpCirrus = it }
            val drawBmp = if (canUsePreBaked) getOrBakeTintedCloud(src, tint, bakedCirrusSrc, bakedCirrusBmp) { s, b -> bakedCirrusSrc = s; bakedCirrusBmp = b } ?: src else src
            drawSeamlessCloudLayer(canvas, drawBmp, scrollCirrusX, cirrusA, cloudCirrusPaint)
        } else if (bakedCirrusBmp != null) {
            bakedCirrusBmp?.recycle(); bakedCirrusBmp = null; bakedCirrusSrc = null
        }
        if (cumBackA > 0.006f) {
            val src = bmpCumulusBack?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_back")?.also { bmpCumulusBack = it }
            val drawBmp = if (canUsePreBaked) getOrBakeTintedCloud(src, tint, bakedCumBackSrc, bakedCumBackBmp) { s, b -> bakedCumBackSrc = s; bakedCumBackBmp = b } ?: src else src
            drawSeamlessCloudLayer(canvas, drawBmp, scrollCumulusBackX, cumBackA, cloudCumulusBackPaint)
        } else if (bakedCumBackBmp != null) {
            bakedCumBackBmp?.recycle(); bakedCumBackBmp = null; bakedCumBackSrc = null
        }
        if (cumFrontA > 0.006f) {
            val src = bmpCumulusFront?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "cumulus_front")?.also { bmpCumulusFront = it }
            val drawBmp = if (canUsePreBaked) getOrBakeTintedCloud(src, tint, bakedCumFrontSrc, bakedCumFrontBmp) { s, b -> bakedCumFrontSrc = s; bakedCumFrontBmp = b } ?: src else src
            drawSeamlessCloudLayer(canvas, drawBmp, scrollCumulusFrontX, cumFrontA, cloudCumulusFrontPaint)
        } else if (bakedCumFrontBmp != null) {
            bakedCumFrontBmp?.recycle(); bakedCumFrontBmp = null; bakedCumFrontSrc = null
        }
        if (mistA > 0.006f) {
            val src = bmpMist?.takeIf { !it.isRecycled } ?: SharedHmiAssetManager.getCloudBitmap(resources, "mist")?.also { bmpMist = it }
            val drawBmp = if (canUsePreBaked) getOrBakeTintedCloud(src, tint, bakedMistSrc, bakedMistBmp) { s, b -> bakedMistSrc = s; bakedMistBmp = b } ?: src else src
            drawSeamlessCloudLayer(canvas, drawBmp, scrollMistX, mistA, cloudMistPaint)
        } else if (bakedMistBmp != null) {
            bakedMistBmp?.recycle(); bakedMistBmp = null; bakedMistSrc = null
        }
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
        val texW = logicalCloudWidth(bitmap)
        var startX = scrollX % texW
        if (startX > 0) startX -= texW

        var currX = startX
        while (currX < LOGICAL_WIDTH) {
            cloudDestRect.set(currX, 0f, currX + texW, LOGICAL_HEIGHT)
            canvas.drawBitmap(bitmap, null, cloudDestRect, paint)
            currX += texW
        }
    }

    // ----------------------------------------------------
    // 第 4 层：天气粒子 (合批零 GC 降雨 / 飞雪 / 闪电)
    // ----------------------------------------------------
    private fun drawWeatherParticles(canvas: Canvas) {
        // 1. 降雨合批绘制 (按透明度 3 桶合批 drawLines，从 160 次 DrawCall 压缩为最多 3 次)
        val rainCount = currentConfig.rainParticleCount
        if (rainCount > 0) {
            val windSlantX = (currentConfig.windSpeedKmh * 0.42f).coerceIn(-28f, 28f)
            val fgStroke = if (currentConfig.intensity == Intensity.HEAVY) 2.2f else 1.4f
            var fgBIdx = 0
            var fgMIdx = 0
            var bgIdx = 0

            for (i in 0 until rainCount) {
                val drop = rainPool[i]
                if (drop.isForeground) {
                    if (drop.alpha >= 0.55f) {
                        rainFgBrightPts[fgBIdx++] = drop.x
                        rainFgBrightPts[fgBIdx++] = drop.y
                        rainFgBrightPts[fgBIdx++] = drop.x + windSlantX
                        rainFgBrightPts[fgBIdx++] = drop.y + drop.length
                    } else {
                        rainFgMidPts[fgMIdx++] = drop.x
                        rainFgMidPts[fgMIdx++] = drop.y
                        rainFgMidPts[fgMIdx++] = drop.x + windSlantX
                        rainFgMidPts[fgMIdx++] = drop.y + drop.length
                    }
                } else {
                    rainBgPts[bgIdx++] = drop.x
                    rainBgPts[bgIdx++] = drop.y
                    rainBgPts[bgIdx++] = drop.x + windSlantX * 0.8f
                    rainBgPts[bgIdx++] = drop.y + drop.length * 0.7f
                }
            }

            if (bgIdx > 0) {
                rainBgPaint.color = Color.parseColor("#7DD3FC")
                rainBgPaint.strokeWidth = 1.0f
                rainBgPaint.alpha = 110
                canvas.drawLines(rainBgPts, 0, bgIdx, rainBgPaint)
            }
            rainFgPaint.color = Color.parseColor("#BAE6FD")
            rainFgPaint.strokeWidth = fgStroke
            if (fgMIdx > 0) {
                rainFgPaint.alpha = 135
                canvas.drawLines(rainFgMidPts, 0, fgMIdx, rainFgPaint)
            }
            if (fgBIdx > 0) {
                rainFgPaint.alpha = 205
                canvas.drawLines(rainFgBrightPts, 0, fgBIdx, rainFgPaint)
            }
        }

        // 2. 降雪 (两档透明度分桶绘制，并直接复用物理步进缓存好的 cachedDrawX)
        val snowCount = currentConfig.snowParticleCount
        if (snowCount > 0) {
            snowPaint.color = Color.WHITE
            snowPaint.alpha = 216
            for (i in 0 until snowCount) {
                val snow = snowPool[i]
                if (snow.alpha >= 0.7f) {
                    canvas.drawCircle(snow.cachedDrawX, snow.y, snow.radius, snowPaint)
                }
            }
            snowPaint.alpha = 128
            for (i in 0 until snowCount) {
                val snow = snowPool[i]
                if (snow.alpha < 0.7f) {
                    canvas.drawCircle(snow.cachedDrawX, snow.y, snow.radius, snowPaint)
                }
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
    // 第 5 层 & 第 6.5 层：A / C 可插拔分区渲染引擎 (支持天气、歌曲封面、蓄电池、空白等无缝替换)
    // ----------------------------------------------------
    private fun prepareSlotRenderContext(nowUptimeMs: Long) {
        slotRenderContext.currentConfig = currentConfig
        slotRenderContext.previousConfig = previousConfig
        slotRenderContext.transitionProgress = transitionProgress
        slotRenderContext.lyricsModeProgress = lyricsModeProgress
        slotRenderContext.timeStr = cachedTimeStr
        slotRenderContext.dateStr = cachedDateStr
        slotRenderContext.lightningActive = lightningActive
        slotRenderContext.lightningIntensity = lightningIntensity
        slotRenderContext.nowUptimeMs = nowUptimeMs
        slotRenderContext.trackTitle = slotTrackTitle.ifBlank { topMusicTitle }
        slotRenderContext.trackArtist = slotTrackArtist.ifBlank { topMusicArtist }
        slotRenderContext.isMusicPlaying = slotMusicPlaying || isTopMusicPlaying
        slotRenderContext.coverBitmap = albumCoverBitmap
        slotRenderContext.coverFadeAlpha = coverFadeAlpha
        slotRenderContext.albumCoverSlotAlpha = albumCoverSlotAlpha
        slotRenderContext.batteryVoltageV = batteryInfo.voltageV
        slotRenderContext.batterySohPercent = batteryInfo.sohPercent
        slotRenderContext.batterySocPercent = batteryInfo.socPercent
        slotRenderContext.batteryLowWarning = batteryInfo.isLowWarning
        slotRenderContext.carOutsideTempC = carOutsideTempC
        slotRenderContext.carCabinTempC = carCabinTempC
        slotRenderContext.gForceX = gForceData.gx
        slotRenderContext.gForceY = gForceData.gy
        slotRenderContext.gForcePeak = gForceData.peakG
        slotRenderContext.gForcePeakX = gForceData.peakGx
        slotRenderContext.gForcePeakY = gForceData.peakGy
        slotRenderContext.motorPowerKw = motorPowerInfo.powerKw
        slotRenderContext.motorPowerPeakOutKw = motorPowerInfo.peakOutputKw
        slotRenderContext.motorPowerPeakRegenKw = motorPowerInfo.peakRegenKw
        slotRenderContext.motorPowerHvSocPercent = motorPowerInfo.hvSocPercent
        slotRenderContext.motorPowerIsPlugCharging = motorPowerInfo.isPlugCharging
        slotRenderContext.engineRpm = rpmInfo.engineRpm
        slotRenderContext.motorRpm = rpmInfo.motorRpm
        slotRenderContext.enginePeakRpm = rpmInfo.enginePeakRpm
        slotRenderContext.motorPeakRpm = rpmInfo.motorPeakRpm
        slotRenderContext.powertrainMode = rpmInfo.powertrainMode
        slotRenderContext.currentGear = currentGear
        slotRenderContext.showSmallTimeInWeatherSlot = true
    }

    private fun drawSlotRenderer(
        canvas: Canvas,
        type: XiaoOuSlotType,
        renderers: Map<XiaoOuSlotType, XiaoOuSlotRenderer>,
        region: XiaoOuSlotRegion,
        alphaFactor: Float
    ) {
        if (type == XiaoOuSlotType.ALBUM_COVER) {
            val coverAlpha = slotRenderContext.albumCoverSlotAlpha.coerceIn(0f, 1f)
            val weatherAlpha = (1f - coverAlpha).coerceIn(0f, 1f)
            if (weatherAlpha > 0.005f) {
                val weatherRenderer = renderers[XiaoOuSlotType.WEATHER]
                weatherRenderer?.draw(canvas, region, slotRenderContext, alphaFactor * weatherAlpha)
                if (region == XiaoOuSlotRegion.LEFT_A && weatherRenderer != null) {
                    weatherHudLeftX = weatherRenderer.getLastLeftAnchorX()
                }
            }
            if (coverAlpha > 0.005f) {
                renderers[XiaoOuSlotType.ALBUM_COVER]?.draw(canvas, region, slotRenderContext, alphaFactor)
            }
        } else {
            val renderer = renderers[type]
            renderer?.draw(canvas, region, slotRenderContext, alphaFactor)
            if (type == XiaoOuSlotType.WEATHER && region == XiaoOuSlotRegion.LEFT_A && renderer != null) {
                weatherHudLeftX = renderer.getLastLeftAnchorX()
            }
        }
    }

    private fun drawSlotA(canvas: Canvas) {
        val slotAlphaFactor = (1f - callTransitionProgress).coerceIn(0f, 1f)
        if (slotAlphaFactor > 0.005f) {
            if (slotATransitionProgress < 0.999f && prevSlotAType != slotAType) {
                val outAlpha = (1f - slotATransitionProgress) * slotAlphaFactor
                if (outAlpha > 0.005f) {
                    drawSlotRenderer(canvas, prevSlotAType, slotRenderersA, XiaoOuSlotRegion.LEFT_A, outAlpha)
                }
            }
            val inAlpha = slotATransitionProgress * slotAlphaFactor
            if (inAlpha > 0.005f) {
                drawSlotRenderer(canvas, slotAType, slotRenderersA, XiaoOuSlotRegion.LEFT_A, inAlpha)
            }
        }

        // 通话状态展示 (高优先级覆盖 A 区域，当有通话时平滑淡入)
        if (callTransitionProgress > 0.005f) {
            drawCallCluster(canvas, callTransitionProgress)
        } else {
            callNamePaint.clearShadowLayer()
            callSubPaint.clearShadowLayer()
        }

        // A 区右侧雅致纵向分割光轨 (X=535，仅当 A 区非空或通话中且无太阳/月亮遮挡时绘制)
        val hasAContent = (slotAType != XiaoOuSlotType.NONE) || (callTransitionProgress > 0.005f)
        if (hasAContent && !currentConfig.celestialVisible) {
            canvas.drawLine(HUD_DIVIDER_X, 70f, HUD_DIVIDER_X, 314f, dividerPaint)
        }
    }

    private fun drawSlotC(canvas: Canvas) {
        // 核心规范：开启小欧形象时 (xiaoOuAvoidanceEnabled == true)，C 区域完全让给小欧 3D 形象，不绘制任何内容
        val visibilityAlpha = slotCVisibilityProgress.coerceIn(0f, 1f)
        if (visibilityAlpha <= 0.005f) return

        // 若 A 区当前正在显示天气（含“歌曲封面”停播自动补位天气的情况），避免 C 区同时显示天气时重复绘制小时间
        val prevShowSmallTime = slotRenderContext.showSmallTimeInWeatherSlot
        val slotAShowsWeatherNow = (slotAType == XiaoOuSlotType.WEATHER) ||
            (slotAType == XiaoOuSlotType.ALBUM_COVER && slotRenderContext.albumCoverSlotAlpha < 0.995f)
        val slotCShowsWeather = (slotCType == XiaoOuSlotType.WEATHER) || (slotCType == XiaoOuSlotType.ALBUM_COVER)
        if (slotAShowsWeatherNow && slotCShowsWeather) {
            slotRenderContext.showSmallTimeInWeatherSlot = false
        }

        if (slotCTransitionProgress < 0.999f && prevSlotCType != slotCType) {
            val outAlpha = (1f - slotCTransitionProgress) * visibilityAlpha
            if (outAlpha > 0.005f) {
                drawSlotRenderer(canvas, prevSlotCType, slotRenderersC, XiaoOuSlotRegion.RIGHT_C, outAlpha)
            }
        }
        val inAlpha = slotCTransitionProgress * visibilityAlpha
        if (inAlpha > 0.005f) {
            drawSlotRenderer(canvas, slotCType, slotRenderersC, XiaoOuSlotRegion.RIGHT_C, inAlpha)
        }

        slotRenderContext.showSmallTimeInWeatherSlot = prevShowSmallTime

        // C 区左侧对称纵向分割光轨 (X=1385，仅当 C 区可见且非空时绘制)
        if (slotCType != XiaoOuSlotType.NONE && !currentConfig.celestialVisible) {
            rightDividerPaint.alpha = (255 * visibilityAlpha).toInt()
            canvas.drawLine(HUD_RIGHT_DIVIDER_X, 70f, HUD_RIGHT_DIVIDER_X, 314f, rightDividerPaint)
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
        CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, displayName, hudCenterX, 252f,
            callNamePaint, masterAlpha, 0f, 2f, 220
        )

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
            CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, previousStatusText, hudCenterX, 292f - easeTp * 8f,
                callSubPaint, oldAlpha, 0f, 2f, 200
            )

            // 新文字由下向上浮入并淡入 (阴影同步升入淡入)
            val newAlpha = (210 * alphaProgress.coerceIn(0f, 1f) * easeTp).toInt()
            CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, statusText, hudCenterX, 292f + (1f - easeTp) * 8f,
                callSubPaint, newAlpha, 0f, 2f, 200
            )
        } else {
            val steadyAlpha = (210 * alphaProgress.coerceIn(0f, 1f)).toInt()
            CanvasRenderUtils.drawTextWithSoftShadow(
                canvas, statusText, hudCenterX, 292f,
                callSubPaint, steadyAlpha, 0f, 2f, 200
            )
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

        // 彻底根除残影 3：转场退出时附带极其自然的纵深收敛微缩放 (0.94 -> 1.0)
        val scaleAnim = lerp(0.94f, 1.0f, smoothAlpha)

        val isLiquidSteady = (liquidGlassProgress <= 0.001f || liquidGlassProgress >= 0.999f)
        val canUseClockCache = isLiquidSteady && transitionProgress >= 0.999f && !lightningActive

        if (canUseClockCache) {
            val isLiquidMode = liquidGlassProgress >= 0.999f
            val cacheInvalid = cachedClockBitmap == null || cachedClockBitmap!!.isRecycled ||
                    cachedClockKeyStr != cachedTimeStr ||
                    cachedClockKeyLiquid != isLiquidMode ||
                    cachedClockKeyTextColor != finalTextColor ||
                    cachedClockKeyGlowColor != glowColor ||
                    kotlin.math.abs(cachedClockKeyGlowRadius - targetGlowRadius) > 0.2f

            if (cacheInvalid) {
                val bmp = cachedClockBitmap?.takeIf { !it.isRecycled && it.width == CLOCK_CACHE_W && it.height == CLOCK_CACHE_H }
                    ?: Bitmap.createBitmap(CLOCK_CACHE_W, CLOCK_CACHE_H, Bitmap.Config.ARGB_8888).also {
                        cachedClockBitmap?.recycle()
                        cachedClockBitmap = it
                        cachedClockCanvas = Canvas(it)
                    }
                val cc = cachedClockCanvas ?: Canvas(bmp).also { cachedClockCanvas = it }
                bmp.eraseColor(Color.TRANSPARENT)
                cc.save()
                cc.translate(-CLOCK_CACHE_LEFT, 0f)
                drawHugeAtmosphericClockDirect(cc, 1.0f, finalTextColor, glowColor, targetGlowRadius, useBlurShadow = true)
                cc.restore()

                cachedClockKeyStr = cachedTimeStr
                cachedClockKeyLiquid = isLiquidMode
                cachedClockKeyTextColor = finalTextColor
                cachedClockKeyGlowColor = glowColor
                cachedClockKeyGlowRadius = targetGlowRadius
            }

            val bmp = cachedClockBitmap
            if (bmp != null && !bmp.isRecycled) {
                canvas.save()
                canvas.scale(scaleAnim, scaleAnim, CLOCK_CENTER_X, LOGICAL_HEIGHT / 2f)
                cachedClockPaint.alpha = textAlphaInt
                canvas.drawBitmap(bmp, CLOCK_CACHE_LEFT, 0f, cachedClockPaint)
                canvas.restore()
                return
            }
        }

        canvas.save()
        canvas.scale(scaleAnim, scaleAnim, CLOCK_CENTER_X, LOGICAL_HEIGHT / 2f)
        drawHugeAtmosphericClockDirect(canvas, smoothAlpha, finalTextColor, glowColor, targetGlowRadius, useBlurShadow = false)
        canvas.restore()
    }

    private fun drawHugeAtmosphericClockDirect(
        canvas: Canvas,
        smoothAlpha: Float,
        finalTextColor: Int,
        glowColor: Int,
        targetGlowRadius: Float,
        useBlurShadow: Boolean = false
    ) {
        // 1. 经典拟真大气荧光时钟 (液态玻璃进度 < 1.0 时渲染，支持开启/关闭丝滑交叉溶解淡入淡出)
        if (liquidGlassProgress < 0.999f) {
            val classicAlphaFactor = (1f - liquidGlassProgress) * smoothAlpha
            val classicAlphaInt = (classicAlphaFactor * 255f).toInt()
            if (classicAlphaInt > 0) {
                clockTimePaint.color = finalTextColor
                clockTimePaint.alpha = classicAlphaInt

                val glowAlphaInt = (Color.alpha(glowColor) * classicAlphaFactor).toInt().coerceIn(0, 255)
                if (useBlurShadow && glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
                    val baseGlow = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor
                    val fadedGlow = ColorUtils.setAlphaComponent(baseGlow, glowAlphaInt)
                    clockTimePaint.setShadowLayer(
                        (targetGlowRadius * 1.4f + (if (lightningActive) 20f else 0f)) * classicAlphaFactor,
                        0f,
                        4f,
                        fadedGlow
                    )
                    canvas.drawText(cachedTimeStr, CLOCK_CENTER_X, clockBaselineY, clockTimePaint)
                } else if (glowAlphaInt > 0 && targetGlowRadius > 0.5f) {
                    clockTimePaint.clearShadowLayer()
                    val baseGlow = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor
                    CanvasRenderUtils.drawTextWithHaloAndShadow(
                        canvas, cachedTimeStr, CLOCK_CENTER_X, clockBaselineY,
                        clockTimePaint, classicAlphaInt, baseGlow, glowAlphaInt, dyGlow = 4f
                    )
                } else {
                    clockTimePaint.clearShadowLayer()
                    canvas.drawText(cachedTimeStr, CLOCK_CENTER_X, clockBaselineY, clockTimePaint)
                }
            }
        } else {
            clockTimePaint.clearShadowLayer()
        }

        // 2. 核心：液态玻璃时钟
        if (liquidGlassProgress > 0.001f) {
            drawLiquidGlassClock(canvas, smoothAlpha, finalTextColor, useBlurShadow)
        }
    }

    /**
     * 绘制液态玻璃中央大时钟 (车规级 100% GPU 硬件加速重构版)：
     * 1. 采用精密双重 LinearGradient 曲率着色器与环境漫反射天光 (0ms CPU，0 软模糊)
     * 2. 亮白微光内浮雕高光 (inet 1px 1px #fff8)
     * 3. 全曲率晶体折射描边 (Fresnel Refraction Rim) 与菲涅尔微晕
     * 4. 彻底废除 CPU 软件像素盒式模糊与 clipPath 掩码离屏渲染，杜绝 95% 掉帧与 90MB 显存膨胀
     */
    private fun drawLiquidGlassClock(
        canvas: Canvas,
        smoothAlpha: Float,
        finalTextColor: Int,
        useBlurShadow: Boolean = false
    ) {
        if (liquidGlassProgress <= 0.001f || smoothAlpha <= 0.005f) return

        // 1. 更新字体矢量轮廓 (仅在走时分钟改变时重新计算一次)
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

        val glassAlpha = (smoothAlpha * liquidGlassProgress).coerceIn(0f, 1f)

        // 仅在时间文本改变或 alpha 变动显著时重新构建 Shader，复用成员数组，零 GC
        val needUpdateShaders = (cachedTimeStr != glassCachedTimeForShaders) ||
                (kotlin.math.abs(glassAlpha - lastGlassAlphaForShaders) > 0.05f) ||
                (glassSurfaceShinePaint.shader == null)
        if (needUpdateShaders) {
            glassCachedTimeForShaders = cachedTimeStr
            lastGlassAlphaForShaders = glassAlpha

            glassShineColors[0] = Color.argb((210 * glassAlpha).toInt(), 255, 255, 255)
            glassShineColors[1] = Color.argb((85 * glassAlpha).toInt(), 230, 240, 255)
            glassShineColors[2] = Color.argb((150 * glassAlpha).toInt(), 245, 250, 255)
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

        // 2. 文字本体磨砂晶体渐变填充 (GPU 直接着色，0 离屏 RenderTarget)
        val prevShader = clockTimePaint.shader
        val prevAlpha = clockTimePaint.alpha
        clockTimePaint.shader = glassSurfaceShinePaint.shader
        clockTimePaint.alpha = (255 * glassAlpha).toInt()
        canvas.drawText(cachedTimeStr, CLOCK_CENTER_X, clockBaselineY, clockTimePaint)
        clockTimePaint.shader = prevShader
        clockTimePaint.alpha = prevAlpha

        // 3. 边缘全曲率晶体折射描边 (Fresnel Refraction Rim)
        canvas.drawPath(glassTextPath, glassRefractionStrokePaint)

        // 4. 边缘菲涅尔光晕：离屏位图缓存路径使用高斯阴影，实时过渡帧退化为轻量外描边消除 GPU Scratch RenderTarget
        if (glassAlpha > 0.05f) {
            val glowColor = if (lightningActive && lightningIntensity > 0.35f) {
                Color.argb((180 * glassAlpha).toInt(), 240, 171, 252)
            } else {
                Color.argb((110 * glassAlpha).toInt(), 255, 255, 255)
            }
            if (useBlurShadow) {
                glassOuterRefractionPaint.color = Color.TRANSPARENT
                glassOuterRefractionPaint.strokeWidth = 1.4f
                glassOuterRefractionPaint.setShadowLayer(
                    14f * glassAlpha,
                    0f, 0f, glowColor
                )
                canvas.drawPath(glassTextPath, glassOuterRefractionPaint)
                glassOuterRefractionPaint.clearShadowLayer()
            } else {
                glassOuterRefractionPaint.clearShadowLayer()
                glassOuterRefractionPaint.color = ColorUtils.setAlphaComponent(glowColor, (Color.alpha(glowColor) * 0.45f).toInt())
                glassOuterRefractionPaint.strokeWidth = 3.2f
                canvas.drawPath(glassTextPath, glassOuterRefractionPaint)
            }
        }
    }

    // ----------------------------------------------------
    // 第 6 层 (B)：五行居中对称歌词引擎 (顶满屏幕边缘大字体、居中对齐、逐字速度驱动跑马灯)
    // ----------------------------------------------------
    private fun computeConfigBrightnessFactor(cfg: WeatherSceneConfig): Float {
        val topLum = ColorUtils.calculateLuminance(cfg.skyTopColor).toFloat()
        val botLum = ColorUtils.calculateLuminance(cfg.skyBottomColor).toFloat()
        val skyLum = topLum * 0.35f + botLum * 0.65f
        val cloudLum = ColorUtils.calculateLuminance(cfg.cloudTintColor).toFloat()
        val cloudCover = (
            cfg.cloudCumulusFrontAlpha * 0.50f +
            cfg.cloudCumulusBackAlpha * 0.25f +
            cfg.cloudOvercastAlpha * 0.35f +
            cfg.cloudCirrusAlpha * 0.15f
        ).coerceIn(0f, 0.85f)
        val combined = skyLum * (1f - cloudCover * 0.55f) + cloudLum * (cloudCover * 0.55f)
        return ((combined - 0.20f) / 0.40f).coerceIn(0f, 1f)
    }

    private fun computeLyricsSceneBrightFactor(): Float {
        val prevB = computeConfigBrightnessFactor(previousConfig)
        val currB = computeConfigBrightnessFactor(currentConfig)
        return lerp(prevB, currB, transitionProgress).coerceIn(0f, 1f)
    }

    private fun drawFiveLineLyrics(canvas: Canvas, alphaFactor: Float) {
        if (alphaFactor <= 0.005f || lyricLines.isEmpty() || activeLineIndex < 0) return

        val targetTextColor = currentConfig.clockTextColor
        val targetGlowColor = currentConfig.clockGlowColor

        val textColor = lerpColor(previousConfig.clockTextColor, targetTextColor, transitionProgress)
        val glowColor = lerpColor(previousConfig.clockGlowColor, targetGlowColor, transitionProgress)
        val finalTextColor = if (lightningActive && lightningIntensity > 0.35f) Color.WHITE else textColor
        val finalGlowColor = if (lightningActive) Color.parseColor("#F0ABFC") else glowColor
        val brightFactor = computeLyricsSceneBrightFactor()

        updateSafeBounds()

        val leftClip = CLOCK_CENTER_X - safeHalfWidth
        val rightClip = CLOCK_CENTER_X + safeHalfWidth
        val maxVisibleWidth = safeHalfWidth * 2f

        canvas.save()
        canvas.clipRect(leftClip, 0f, rightClip, LOGICAL_HEIGHT)

        // 自适应柔光对比度衬底：在白天多云/大雪/亮晴等高亮度云雾场景下于歌词区背部柔化高光，夜间几乎无感
        val scrimAlpha = (alphaFactor * lerp(18f, 116f, brightFactor)).toInt().coerceIn(0, 255)
        if (scrimAlpha > 2) {
            val rx = (safeHalfWidth * 1.06f).coerceAtLeast(260f)
            val ry = LOGICAL_HEIGHT * 0.52f
            val cy = LOGICAL_HEIGHT * 0.50f
            lyricsScrimMatrix.setScale(rx, ry)
            lyricsScrimMatrix.postTranslate(CLOCK_CENTER_X, cy)
            lyricsScrimShader.setLocalMatrix(lyricsScrimMatrix)
            lyricsScrimPaint.alpha = scrimAlpha
            lyricsScrimRect.set(CLOCK_CENTER_X - rx, cy - ry, CLOCK_CENTER_X + rx, cy + ry)
            canvas.drawRect(lyricsScrimRect, lyricsScrimPaint)
        }

        val maxComfortableWidth = maxVisibleWidth - 64f // 786px 触发阈值，避免边缘贴字切字
        val centerLineFloor = animatedLineIndex.toInt()
        val minSlotIndex = (centerLineFloor - 3).coerceAtLeast(0)
        val maxSlotIndex = (centerLineFloor + 3).coerceAtMost(lyricLines.size - 1)

        for (idx in minSlotIndex..maxSlotIndex) {
            val v = idx.toFloat() - animatedLineIndex
            val absV = abs(v)
            if (absV > 2.8f) continue

            // 1. 字体大小阶梯：中心 92px，二四行 36px，边缘一五行 22px
            val textSize = getSlotTextSize(absV)

            // 2. 透明度阶梯：亮天/多云下温和抬升次级行不透明度，防止与白昼积云混色消失
            val baseAlpha = getSlotAlpha(absV, brightFactor)
            val lineAlpha = (baseAlpha * alphaFactor * 255f).toInt().coerceIn(0, 255)
            if (lineAlpha <= 0) continue

            // 车规级 0 RenderTarget 与有限字号图集优化：仅使用 92f / 36f 两个稳态字号档位，平滑缩放交由 GPU 矩阵完成
            val basePaintSize = if (absV < 0.7f) 92f else 36f
            val scaleFactor = textSize / basePaintSize
            lyricDrawPaint.textSize = basePaintSize
            lyricDrawPaint.color = finalTextColor
            lyricDrawPaint.typeface = if (absV < 0.45f) typefaceMediumBold else typefaceMediumNormal

            // 4. 纵向基线垂直居中 (非线性间距：中心至二四行 80px，一五行贴近二四行 50px，复用 lyricFontMetrics 零 GC)
            val centerY = getSlotY(v)
            lyricDrawPaint.getFontMetrics(lyricFontMetrics)
            val baselineY = centerY - (lyricFontMetrics.descent + lyricFontMetrics.ascent) / 2f

            // 5. 逐字显示速度驱动跑马灯位移计算 (中心句及过渡期间保持丝滑位置连续)
            val lineText = lyricLines[idx].text
            val textWidth = lyricDrawPaint.measureText(lineText) * scaleFactor

            var drawCenterX = CLOCK_CENTER_X
            if (textWidth > maxComfortableWidth) {
                val marqueeOffset = when {
                    idx == activeLineIndex -> calculateMarqueeOffset(textWidth, maxVisibleWidth, idx, currentLyricsPositionMs)
                    idx < activeLineIndex -> -((textWidth - maxVisibleWidth) / 2f) - 48f // 已唱完，驻留在句末 (多往左走 48px 保证末字完整)
                    else -> (textWidth - maxVisibleWidth) / 2f + 24f                     // 未开唱，句首舒适对齐
                }
                drawCenterX = CLOCK_CENTER_X + marqueeOffset
            }

            val needScale = abs(scaleFactor - 1f) > 0.002f
            if (needScale) {
                canvas.save()
                canvas.scale(scaleFactor, scaleFactor, drawCenterX, centerY)
            }
            if (absV < 0.45f) {
                val glowFactor = (1f - 2.2f * absV).coerceIn(0f, 1f)
                val glowAlpha = (Color.alpha(finalGlowColor) * (lineAlpha / 255f) * glowFactor).toInt().coerceIn(0, 255)
                CanvasRenderUtils.drawTextWithHaloAndShadow(
                    canvas, lineText, drawCenterX, baselineY,
                    lyricDrawPaint, lineAlpha, finalGlowColor, glowAlpha,
                    dyGlow = 3f, backdropBoost = brightFactor
                )
            } else {
                CanvasRenderUtils.drawTextWithBrightBackdropShadow(
                    canvas, lineText, drawCenterX, baselineY,
                    lyricDrawPaint, lineAlpha, brightFactor, baseShadowAlpha = 210
                )
            }
            if (needScale) {
                canvas.restore()
            }
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
     * 中心至左侧 A 区边界净距 (425px) vs 中心至右侧 C 区 / 小欧形象净距，取最小值作为 B 区半宽
     */
    private fun updateSafeBounds() {
        val dLeft = CLOCK_CENTER_X - HUD_DIVIDER_X // 960 - 535 = 425f
        val dRight = when {
            xiaoOuAvoidanceEnabled -> XIAOOU_GESTURE_BOUNDARY_X - CLOCK_CENTER_X // 1420 - 960 = 460f
            slotCType != XiaoOuSlotType.NONE -> HUD_RIGHT_DIVIDER_X - CLOCK_CENTER_X // 1385 - 960 = 425f
            else -> SAFE_RIGHT_START_PX - CLOCK_CENTER_X // 1790 - 960 = 830f
        }
        safeHalfWidth = min(dLeft, dRight).coerceAtLeast(200f)
    }

    fun setXiaoOuAvoidance(avoid: Boolean, smooth: Boolean = true) {
        val targetCVis = if (avoid) 0f else 1f
        if (xiaoOuAvoidanceEnabled == avoid && kotlin.math.abs(slotCVisibilityProgress - targetCVis) < 0.001f) {
            return
        }
        xiaoOuAvoidanceEnabled = avoid
        updateSafeBounds()
        updateGForceSubscription()
        slotCVisibilityAnimator?.cancel()
        if (!smooth) {
            slotCVisibilityProgress = targetCVis
            invalidateFg()
            return
        }
        slotCVisibilityAnimator = ValueAnimator.ofFloat(slotCVisibilityProgress, targetCVis).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                slotCVisibilityProgress = it.animatedValue as Float
                invalidateFg()
            }
            start()
        }
        wakeUpAnimation()
    }

    // ==========================================
    // 小欧屏 A / C 区域可替换模块配置 API
    // ==========================================
    fun setSlotConfig(slotA: XiaoOuSlotType, slotC: XiaoOuSlotType, smooth: Boolean = true) {
        setSlotA(slotA, smooth)
        setSlotC(slotC, smooth)
    }

    fun setSlotA(type: XiaoOuSlotType, smooth: Boolean = true) {
        if (slotAType == type && slotATransitionProgress >= 0.999f) return
        slotAAnimator?.cancel()
        if (!smooth) {
            prevSlotAType = type
            slotAType = type
            slotATransitionProgress = 1f
            updateSafeBounds()
            updateGForceSubscription()
            invalidateFg()
            return
        }
        prevSlotAType = slotAType
        slotAType = type
        slotATransitionProgress = 0f
        updateSafeBounds()
        updateGForceSubscription()
        slotAAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                slotATransitionProgress = it.animatedValue as Float
                invalidateFg()
                onSceneRenderUpdated?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slotATransitionProgress = 1f
                    prevSlotAType = slotAType
                    updateGForceSubscription()
                    invalidateFg()
                    onSceneRenderUpdated?.invoke()
                }
            })
            start()
        }
        wakeUpAnimation()
    }

    fun setSlotC(type: XiaoOuSlotType, smooth: Boolean = true) {
        if (slotCType == type && slotCTransitionProgress >= 0.999f) return
        slotCAnimator?.cancel()
        if (!smooth) {
            prevSlotCType = type
            slotCType = type
            slotCTransitionProgress = 1f
            updateSafeBounds()
            updateGForceSubscription()
            invalidateFg()
            return
        }
        prevSlotCType = slotCType
        slotCType = type
        slotCTransitionProgress = 0f
        updateSafeBounds()
        updateGForceSubscription()
        slotCAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                slotCTransitionProgress = it.animatedValue as Float
                invalidateFg()
                onSceneRenderUpdated?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slotCTransitionProgress = 1f
                    prevSlotCType = slotCType
                    updateGForceSubscription()
                    invalidateFg()
                    onSceneRenderUpdated?.invoke()
                }
            })
            start()
        }
        wakeUpAnimation()
    }

    fun getSlotAType(): XiaoOuSlotType = slotAType
    fun getSlotCType(): XiaoOuSlotType = slotCType

    // ==========================================
    // 歌曲封面与 12V 蓄电池实时数据注入 API
    // ==========================================
    fun setSlotTrackInfo(title: String, artist: String, isPlaying: Boolean? = null) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val isPlaceholder = cleanTitle.isEmpty() ||
            MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, cleanArtist)
        slotTrackTitle = if (isPlaceholder) "" else cleanTitle
        slotTrackArtist = if (isPlaceholder) "" else cleanArtist
        if (isPlaying != null) {
            slotMusicPlaying = isPlaying && !isPlaceholder
        }
        invalidateFg()
    }

    fun setSlotMusicPlaying(playing: Boolean) {
        if (slotMusicPlaying != playing) {
            slotMusicPlaying = playing
            invalidateFg()
        }
    }

    /**
     * 控制歌曲封面槽位整体显隐（与歌词播放/暂停 5 秒回退节奏完全同步）：
     * - visible=true：播放中平滑淡入展示；
     * - visible=false：停止播放满 5 秒后平滑淡出至纯空白，淡出结束后再回收位图，杜绝中途闪黑胶占位图。
     */
    fun setAlbumCoverSlotVisible(visible: Boolean, smooth: Boolean = true) {
        val target = if (visible) 1f else 0f
        if (isAlbumCoverSlotVisible == visible && kotlin.math.abs(albumCoverSlotAlpha - target) < 0.005f) {
            return
        }
        isAlbumCoverSlotVisible = visible
        albumCoverSlotAnimator?.cancel()
        if (!smooth) {
            albumCoverSlotAlpha = target
            if (!visible) {
                coverRequestSeq++
                albumCoverUrl = null
                coverFadeAnimator?.cancel()
                albumCoverBitmap = null
                coverFadeAlpha = 0f
            }
            invalidateFg()
            return
        }
        val startAlpha = albumCoverSlotAlpha
        albumCoverSlotAnimator = ValueAnimator.ofFloat(startAlpha, target).apply {
            duration = if (visible) 380L else 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                albumCoverSlotAlpha = it.animatedValue as Float
                invalidateFg()
                onSceneRenderUpdated?.invoke()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isAlbumCoverSlotVisible && albumCoverSlotAlpha <= 0.005f) {
                        albumCoverSlotAlpha = 0f
                        coverRequestSeq++
                        albumCoverUrl = null
                        coverFadeAnimator?.cancel()
                        albumCoverBitmap = null
                        coverFadeAlpha = 0f
                        invalidateFg()
                        onSceneRenderUpdated?.invoke()
                    }
                }
            })
            start()
        }
        wakeUpAnimation()
    }

    fun setAlbumCoverUrl(url: String?, smooth: Boolean = true) {
        val normalized = url?.trim().orEmpty()
        if (normalized.isEmpty()) {
            coverRequestSeq++
            albumCoverUrl = null
            coverFadeAnimator?.cancel()
            albumCoverBitmap = null
            coverFadeAlpha = 0f
            invalidateFg()
            onSceneRenderUpdated?.invoke()
            return
        }
        if (normalized == albumCoverUrl && albumCoverBitmap != null && !albumCoverBitmap!!.isRecycled) {
            return
        }
        albumCoverUrl = normalized
        val seq = ++coverRequestSeq
        com.carlyrics.util.LyricsCacheManager.loadCoverBitmapScaled(
            context = context,
            url = normalized,
            sizePx = 320
        ) { bmp ->
            if (seq != coverRequestSeq) return@loadCoverBitmapScaled
            if (bmp == null || bmp.isRecycled) {
                albumCoverBitmap = null
                coverFadeAlpha = 0f
                invalidateFg()
                onSceneRenderUpdated?.invoke()
                return@loadCoverBitmapScaled
            }
            albumCoverBitmap = bmp
            try {
                com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onCoverLoaded(bmp)
            } catch (_: Exception) {}
            coverFadeAnimator?.cancel()
            if (!smooth) {
                coverFadeAlpha = 1f
                invalidateFg()
                onSceneRenderUpdated?.invoke()
            } else {
                coverFadeAlpha = 0f
                coverFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 280L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        coverFadeAlpha = it.animatedValue as Float
                        invalidateFg()
                        onSceneRenderUpdated?.invoke()
                    }
                    start()
                }
            }
        }
    }

    fun setBatteryInfo(info: CarGearManager.BatteryInfo) {
        if (batteryInfo == info) return
        batteryInfo = info
        invalidateFg()
    }

    fun setCarOutsideTempC(tempC: Float?) {
        if (carOutsideTempC == tempC) return
        carOutsideTempC = tempC
        invalidateFg()
    }

    fun setCarCabinTempC(tempC: Float?) {
        if (carCabinTempC == tempC) return
        carCabinTempC = tempC
        invalidateFg()
    }

    fun setMotorPowerInfo(info: CarGearManager.MotorPowerInfo) {
        if (motorPowerInfo == info) return
        motorPowerInfo = info
        lastMotorPowerUpdateMs = SystemClock.uptimeMillis()
        wakeUpAnimation()
    }

    fun setRpmInfo(info: CarGearManager.RpmInfo) {
        if (rpmInfo == info) return
        rpmInfo = info
        lastRpmUpdateMs = SystemClock.uptimeMillis()
        wakeUpAnimation()
    }

    fun setGear(gear: String) {
        if (currentGear != gear) {
            currentGear = gear
            fgView.postInvalidateOnAnimation()
        }
    }

    // ==========================================
    // 歌词数据与播放进度同步 API
    // ==========================================
    fun setLyrics(lines: List<LyricLine>) {
        if (lyricLines == lines && activeLineIndex >= 0) return
        lyricLines = lines
        activeLineIndex = -1
        activeLineMeasuredIdx = -1
        lastActiveMarqueeOffset = Float.NaN
        lineActiveStartTimeMs = SystemClock.uptimeMillis()
        if (lines.isNotEmpty()) {
            updateLyricsProgress(currentLyricsPositionMs)
        }
        invalidateFg()
    }

    fun updateLyricsProgress(positionMs: Long) {
        val effectiveMs = (positionMs + PrefsManager.lyricsOffsetMs).coerceAtLeast(0L)
        currentLyricsPositionMs = effectiveMs
        if (lyricLines.isEmpty()) return

        var targetIdx = -1
        val cur = activeLineIndex
        if (cur in lyricLines.indices && effectiveMs >= lyricLines[cur].timestamp) {
            val nextIdx = cur + 1
            targetIdx = if (nextIdx >= lyricLines.size || effectiveMs < lyricLines[nextIdx].timestamp) {
                cur
            } else if (nextIdx + 1 >= lyricLines.size || effectiveMs < lyricLines[nextIdx + 1].timestamp) {
                nextIdx
            } else {
                -1
            }
        }
        if (targetIdx == -1) {
            for (i in lyricLines.indices) {
                if (lyricLines[i].timestamp <= effectiveMs) {
                    targetIdx = i
                } else {
                    break
                }
            }
            if (targetIdx == -1) targetIdx = 0
        }

        if (targetIdx != activeLineIndex) {
            val prevIdx = activeLineIndex
            activeLineIndex = targetIdx
            activeLineMeasuredIdx = -1
            lastActiveMarqueeOffset = Float.NaN
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
            invalidateFg()
        } else if (lyricsModeProgress > 0.005f && !lineScrollAnimator.isRunning) {
            // 仅当当前中心行超长且处于跑马灯有效移动窗口内时才按需重绘前景层，短句静止期内零 GPU 纹理重绘
            updateSafeBounds()
            if (activeLineMeasuredIdx != targetIdx || activeLineMeasuredSafeHalfW != safeHalfWidth) {
                activeLineMeasuredIdx = targetIdx
                activeLineMeasuredSafeHalfW = safeHalfWidth
                lyricDrawPaint.textSize = 92f
                lyricDrawPaint.typeface = typefaceMediumBold
                activeLineMeasuredWidth = lyricDrawPaint.measureText(lyricLines[targetIdx].text)
            }
            val maxVisibleWidth = safeHalfWidth * 2f
            val maxComfortableWidth = maxVisibleWidth - 64f
            if (activeLineMeasuredWidth > maxComfortableWidth) {
                val nextOffset = calculateMarqueeOffset(activeLineMeasuredWidth, maxVisibleWidth, targetIdx, effectiveMs)
                if (lastActiveMarqueeOffset.isNaN() || abs(nextOffset - lastActiveMarqueeOffset) > 0.25f) {
                    lastActiveMarqueeOffset = nextOffset
                    invalidateFg()
                }
            }
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
            invalidateFg()
        }
    }

    /**
     * 设置并更新小欧屏最顶部居中高亮音乐播放信息 (‘音乐图标’ 歌名-歌手名)
     * - 纯原生 1920×384 黄金基准坐标系直接渲染，实车与 PAD 缩放比例 100% 绝对一致
     * - 居中对齐，超出边界自动以省略号截断
     * - 播放时淡入展示；无音乐播放或恢复时间显示时优雅淡出收敛隐藏
     * - 具备幂等性保护，播放状态下重复上报进度绝对不打断动画、杜绝频闪
     */
    fun setTopMusicTrackInfo(title: String, artist: String, isPlaying: Boolean, smooth: Boolean = true) {
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
        if (!smooth) {
            topMusicAlphaAnimator?.cancel()
            topMusicTargetAlpha = target
            topMusicAlpha = target
            invalidateFg()
            return
        }
        if (target == topMusicTargetAlpha) {
            if (isNewText && topMusicAlpha > 0f) {
                invalidateFg()
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
                invalidateFg()
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
        if (text != cachedTopMusicRawText || kotlin.math.abs(maxTextWidth - cachedTopMusicMaxWidth) > 1f) {
            cachedTopMusicRawText = text
            cachedTopMusicMaxWidth = maxTextWidth
            cachedTopMusicEllipsizedText = TextUtils.ellipsize(text, topMusicTextPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            cachedTopMusicEllipsizedWidth = topMusicTextPaint.measureText(cachedTopMusicEllipsizedText)
        }
        val textToDraw = cachedTopMusicEllipsizedText
        val textWidth = cachedTopMusicEllipsizedWidth

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

        // 4. 高亮歌名 - 歌手名居中字样 (零 RenderTarget 与零每帧 ellipsize 分配)
        val textLeft = iconLeft + iconWidth + iconSpacing
        val fm = topMusicTextPaint.fontMetrics
        val textBaseline = capsuleTop + (capsuleHeight - (fm.bottom - fm.top)) / 2f - fm.top
        CanvasRenderUtils.drawTextWithSoftShadow(
            canvas, textToDraw, textLeft, textBaseline,
            topMusicTextPaint, alphaInt, 0f, 1.5f, 220, Color.BLACK
        )
    }

    // ----------------------------------------------------
    // 第 8 层：左右 130px 物理安全黑边渐变遮罩 (顶层融合)
    // ----------------------------------------------------
    private fun drawEdgeVignette(canvas: Canvas) {
        canvas.drawRect(vignetteLeftRect, vignetteLeftPaint)
        canvas.drawRect(vignetteRightRect, vignetteRightPaint)
    }

    fun setLaneChangeAlert(alert: CarGearManager.LaneChangeAlert) {
        if (currentLaneChangeAlert != alert) {
            currentLaneChangeAlert = alert
            wakeUpAnimation()
        }
    }

    fun setTurnSignalInfo(info: CarGearManager.TurnSignalInfo) {
        if (currentTurnSignalInfo != info) {
            currentTurnSignalInfo = info
            wakeUpAnimation()
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
    // 第 10 层：转向灯三边空心渐隐矩形 (左/右/双闪)
    // 垂直高 352px, 水平延伸 352px, 三边等长, 水平朝向中心渐隐
    // ----------------------------------------------------
    private fun drawTurnSignals(canvas: Canvas) {
        if (!isTurnSignalSyncEnabled) return
        val info = currentTurnSignalInfo
        if (!info.isAnyTurnActive) return

        val now = SystemClock.uptimeMillis()
        val blinkPeriodMs = 680L
        val phase = (now % blinkPeriodMs).toFloat() / blinkPeriodMs
        val blinkAlpha = when {
            phase < 0.08f -> phase / 0.08f
            phase < 0.52f -> 1.0f
            phase < 0.60f -> 1.0f - (phase - 0.52f) / 0.08f
            else -> 0.0f
        }
        if (blinkAlpha <= 0.001f) return

        val coreAlphaInt = (blinkAlpha * 255f).toInt().coerceIn(0, 255)
        val glowAlphaInt = (blinkAlpha * 95f).toInt().coerceIn(0, 255)

        // 左转向灯：贴着左边缘 (X=130) 的三边，垂直 16..368 (高352)，水平向右延伸 352 (至 X=482)，向中心渐隐
        if (info.isLeftTurn || info.isHazard) {
            turnSignalLeftShader?.let { shader ->
                turnSignalCorePaint.shader = shader
                turnSignalGlowPaint.shader = shader
                turnSignalCorePaint.alpha = coreAlphaInt
                turnSignalGlowPaint.alpha = glowAlphaInt

                canvas.drawPath(turnSignalLeftPath, turnSignalGlowPaint)
                canvas.drawPath(turnSignalLeftPath, turnSignalCorePaint)
            }
        }

        // 右转向灯：贴着右边缘 (X=1790) 的三边，垂直 16..368 (高352)，水平向左延伸 352 (至 X=1438)，向中心渐隐
        if (info.isRightTurn || info.isHazard) {
            turnSignalRightShader?.let { shader ->
                turnSignalCorePaint.shader = shader
                turnSignalGlowPaint.shader = shader
                turnSignalCorePaint.alpha = coreAlphaInt
                turnSignalGlowPaint.alpha = glowAlphaInt

                canvas.drawPath(turnSignalRightPath, turnSignalGlowPaint)
                canvas.drawPath(turnSignalRightPath, turnSignalCorePaint)
            }
        }
    }

    // ----------------------------------------------------
    // 第 9 层：变道辅助盲区大面积半透明渐变矩形光幕预警
    // 规范：
    // 1. 取消与小欧形象的互动 (零干扰 3D 小欧)
    // 2. 左/右侧有车不满足变道条件时：半透明黄色矩形，越往中心越透明，慢速脉冲式闪烁 (1200ms 周期，余光柔和醒目)
    // 3. 打了转向灯且不满足变道条件时：稍快的红黄半透明矩形交替显示 (560ms 周期，280ms红 / 280ms黄 高危急促交替)
    // ----------------------------------------------------
    private fun drawLaneChangeAlertCurtains(canvas: Canvas) {
        if (!isLaneChangeAssistEnabled) return
        val alert = currentLaneChangeAlert
        if (!alert.isBsdOn || !alert.hasAnyWarning) return

        val now = SystemClock.uptimeMillis()

        // 1. 左侧变道警示光幕 (X: 0..482, 向中心渐隐消散；严格仅在左侧转向灯激活时升级红黄)
        if (alert.isLeftWarningActive) {
            val isLeftTurnActive = currentTurnSignalInfo.isLeftTurnActive || currentTurnSignalInfo.isLeftTurn
            val isDanger = alert.isLeftDanger || (alert.leftWarning > 0 && isLeftTurnActive)
            if (isDanger) {
                // 打了左转向灯且盲区有车：稍快的红黄半透明矩形交替显示 (560ms 周期，280ms红 / 280ms黄)
                val isRedPhase = ((now % 560L) < 280L)
                val subPhase = ((now % 280L).toFloat() / 280f)
                val pulse = kotlin.math.sin(subPhase * Math.PI.toFloat())
                val alphaInt = ((0.15f + 0.38f * pulse) * 255f).toInt().coerceIn(0, 255)

                val shader = if (isRedPhase) laneChangeLeftRedShader else laneChangeLeftYellowShader
                shader?.let {
                    laneChangeCurtainPaint.shader = it
                    laneChangeCurtainPaint.alpha = alphaInt
                    canvas.drawRect(laneChangeLeftRect, laneChangeCurtainPaint)
                }
            } else {
                // 未打转向灯但盲区有车：慢速脉冲式闪烁半透明黄色矩形 (1200ms 周期)
                val pulsePhase = (now % 1200L).toFloat() / 1200f
                val pulse = 0.5f + 0.5f * kotlin.math.sin(pulsePhase * 2f * Math.PI.toFloat())
                val alphaInt = ((0.10f + 0.35f * pulse) * 255f).toInt().coerceIn(0, 255)

                laneChangeLeftYellowShader?.let {
                    laneChangeCurtainPaint.shader = it
                    laneChangeCurtainPaint.alpha = alphaInt
                    canvas.drawRect(laneChangeLeftRect, laneChangeCurtainPaint)
                }
            }
        }

        // 2. 右侧变道警示光幕 (X: 1438..1920, 向中心渐隐消散；严格仅在右侧转向灯激活时升级红黄)
        if (alert.isRightWarningActive) {
            val isRightTurnActive = currentTurnSignalInfo.isRightTurnActive || currentTurnSignalInfo.isRightTurn
            val isDanger = alert.isRightDanger || (alert.rightWarning > 0 && isRightTurnActive)
            if (isDanger) {
                // 打了右转向灯且盲区有车：稍快的红黄半透明矩形交替显示 (560ms 周期，280ms红 / 280ms黄)
                val isRedPhase = ((now % 560L) < 280L)
                val subPhase = ((now % 280L).toFloat() / 280f)
                val pulse = kotlin.math.sin(subPhase * Math.PI.toFloat())
                val alphaInt = ((0.15f + 0.38f * pulse) * 255f).toInt().coerceIn(0, 255)

                val shader = if (isRedPhase) laneChangeRightRedShader else laneChangeRightYellowShader
                shader?.let {
                    laneChangeCurtainPaint.shader = it
                    laneChangeCurtainPaint.alpha = alphaInt
                    canvas.drawRect(laneChangeRightRect, laneChangeCurtainPaint)
                }
            } else {
                // 未打转向灯但盲区有车：慢速脉冲式闪烁半透明黄色矩形 (1200ms 周期)
                val pulsePhase = (now % 1200L).toFloat() / 1200f
                val pulse = 0.5f + 0.5f * kotlin.math.sin(pulsePhase * 2f * Math.PI.toFloat())
                val alphaInt = ((0.10f + 0.35f * pulse) * 255f).toInt().coerceIn(0, 255)

                laneChangeRightYellowShader?.let {
                    laneChangeCurtainPaint.shader = it
                    laneChangeCurtainPaint.alpha = alphaInt
                    canvas.drawRect(laneChangeRightRect, laneChangeCurtainPaint)
                }
            }
        }
    }

    @ColorInt
    private fun lerpColor(@ColorInt startColor: Int, @ColorInt endColor: Int, fraction: Float): Int {
        return ColorUtils.blendARGB(startColor, endColor, fraction.coerceIn(0f, 1f))
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }
}

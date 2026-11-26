package com.car.xiaoouscreen.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.ui.CarWeatherCanvasView
import com.car.xiaoouscreen.ui.DriveModeWipeView
import com.car.xiaoouscreen.ui.GearCubeFlipView
import com.car.xiaoouscreen.ui.CarBottomLeftGearHudView
import com.car.xiaoouscreen.ui.CarSmartSceneOverlayView
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType
import com.carlyrics.display.LyricsNormalizedContainerView
import com.carlyrics.model.LyricLine
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.skin.SkinType
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrefsManager

/**
 * 小欧屏悬浮窗 / Presentation 宿主窗口 (100% 纯原生车规版)：
 * - Mode.TOP (小欧上方): 采用系统 WindowManager.addView + TYPE_APPLICATION_OVERLAY (2038)
 * - Mode.FITNESS (小欧下方): 采用 android.app.Presentation (2037)
 * - 全量纯原生架构：档位 3D 棱柱翻转与驾驶模式光刃擦除全量采用 RenderThread 原生硬件加速，彻底废除 WebView 与 Base64 截屏
 */
class WeatherOverlayWindow(
    context: Context,
    private val targetDisplay: Display,
    private val mode: Mode
) {
    enum class Mode {
        FITNESS, // 小欧下方 (2037)
        TOP      // 小欧上方 (2038)
    }

    enum class ActivePage {
        WEATHER,
        LYRICS
    }

    private val appContext = context.applicationContext
    private var windowManager: WindowManager? = null
    private var overlayRootView: View? = null
    private var presentation: InternalPresentation? = null

    private var weatherCanvasView: CarWeatherCanvasView? = null
    private var lyricsContainerView: LyricsNormalizedContainerView? = null
    private var gearCubeView: GearCubeFlipView? = null
    private var driveModeView: DriveModeWipeView? = null
    private var bottomLeftGearHudView: CarBottomLeftGearHudView? = null
    private var smartSceneOverlayView: CarSmartSceneOverlayView? = null
    private var lastMusicTitle: String = ""
    private var lastMusicArtist: String = ""
    private var isMusicPlaying: Boolean = false
    private var isAlbumCoverSessionActive: Boolean = false

    // 画面层级优先级管理与双向覆盖挂起控制器：
    // 1. 当触发档位动画后接着触发模式动画：模式动画覆盖档位页面，模式动画完毕后直接展示档位页面，2秒后档位页面退出动画回到主页面。
    // 2. 当触发模式动画后接着触发档位动画：档位动画覆盖模式页面，档位动画完毕后直接展示模式页面，2秒后模式页面退出动画回到主页面。
    private var isGearActive = false
    private var isDriveModeActive = false
    private var currentActiveDriveMode: String? = null

    private fun updateUnderlyingRenderState() {
        // 车载主界面 (写实多图层天气时钟 / 全屏歌词) 持续保持 60FPS 实时渲染，绝不强制挂起！
        // 模式动画以原生硬件加速透明覆盖在主页面上，在出现或消失半途中，驾驶员均可实时看到主页面的物理天体动态与走字，彻底消除跳帧与割裂感。
        if (currentPage == ActivePage.WEATHER) {
            weatherCanvasView?.resumeAnimation()
        } else if (currentPage == ActivePage.LYRICS) {
            lyricsContainerView?.resume()
        }
        // 保证左下角 HUD 与智能场景置顶图层始终处于绝对最高物理层级
        bottomLeftGearHudView?.bringToFront()
        smartSceneOverlayView?.bringToFront()
    }

    private var currentPage: ActivePage = ActivePage.WEATHER
    private var closeButtonContainer: View? = null
    var isAttached = false
        private set
    var onCloseListener: (() -> Unit)? = null
    var onFocusLostListener: (() -> Unit)? = null

    private var lastReassertTime = 0L
    private var consecutiveReassertCount = 0

    private var pendingSceneConfig: WeatherSceneConfig? = null
    private var pendingCanvasLyricLines: List<LyricLine>? = null
    private var pendingCanvasLyricsActive: Boolean? = null
    private var pendingCanvasAvoidance: Boolean? = null
    private var pendingSlotA: XiaoOuSlotType? = null
    private var pendingSlotC: XiaoOuSlotType? = null
    private var pendingGear: String? = null
    private var pendingBatteryInfo: CarGearManager.BatteryInfo? = null
    private var pendingCarOutsideTempC: Float? = null
    private var pendingCarCabinTempC: Float? = null
    private var pendingMotorPowerInfo: CarGearManager.MotorPowerInfo? = null
    private var pendingRpmInfo: CarGearManager.RpmInfo? = null
    private var pendingCallInfo: CarCallManager.CallInfo? = null
    private var wasInLyricsBeforeCall: Boolean = false

    // 缓存独立全屏车载歌词与画布歌词的全部状态，确保 Presentation 重新登顶或重置时 100% 继承，绝不重置到第 0 行
    private var lastLyricsTrackTitle: String = ""
    private var lastLyricsTrackArtist: String = ""
    private var lastLyricsCoverUrl: String? = null
    private var lastLyricLines: List<LyricLine> = emptyList()
    private var lastLyricsProgressMs: Long = 0L
    private var lastCanvasLyricsProgressMs: Long = 0L

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideCloseRunnable = Runnable {
        closeButtonContainer?.visibility = View.GONE
    }

    val targetDisplayId: Int
        get() = targetDisplay.displayId

    fun isShowing(): Boolean = isAttached

    fun show(): Boolean {
        if (isAttached) return true
        return if (mode == Mode.TOP) {
            showAsOverlay()
        } else {
            showAsPresentation()
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun showAsOverlay(): Boolean {
        return try {
            // 严谨支持多屏硬件：针对小欧副屏 targetDisplay 创建专属 DisplayContext
            // 杜绝直接使用 appContext 导致悬浮窗误加至中控主屏 Display 0
            val displayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                appContext.createDisplayContext(targetDisplay)
            } else {
                appContext
            }
            windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(displayContext)
            val contentView = inflater.inflate(R.layout.presentation_weather_overlay, null)

            val root = TouchInterceptFrameLayout(displayContext).apply {
                addView(contentView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                onTouchDown = { resetCloseButtonTimer() }
            }
            overlayRootView = root
            bindViews(contentView)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val isDefaultDisplay = (targetDisplay.displayId == Display.DEFAULT_DISPLAY)
            // 方案 B（桌面悬浮条带模式）：单屏调试时窗口物理高度精确约束为 1920×384 等比条带，悬浮在最上方
            val windowHeight = if (isDefaultDisplay) {
                val dm = displayContext.resources.displayMetrics
                (dm.widthPixels * 384f / 1920f).toInt().coerceAtLeast(1)
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }

            // 单屏模式配置 FLAG_NOT_TOUCH_MODAL，小欧条带内部正常触控唤出退出按钮，条带下方全屏点击 100% 自然直通底层，既不盲点也不吞噬触控！
            val windowFlags = if (isDefaultDisplay) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                windowHeight,
                layoutType,
                windowFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.LEFT or Gravity.TOP
                x = 0
                y = 0
                title = "XiaoOuWeatherOverlayWindow"
            }

            windowManager?.addView(root, params)
            isAttached = true
            resetCloseButtonTimer()

            weatherCanvasView?.visibility = View.VISIBLE
            weatherCanvasView?.alpha = 1f
            weatherCanvasView?.resumeAnimation()
            weatherCanvasView?.invalidate()

            pendingSceneConfig?.let { weatherCanvasView?.updateScene(it) }
            pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
            pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }
            val shouldAvoid = PrefsManager.isXiaoOuAvatarEnabled()
            lyricsContainerView?.xiaoOuAvoidanceEnabled = shouldAvoid
            weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid, smooth = false)
            weatherCanvasView?.setLiquidGlassEnabled(com.carlyrics.util.PrefsManager.isLiquidGlassClockEnabled(), smooth = false)
            val slotA = pendingSlotA ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
            val slotC = pendingSlotC ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
            weatherCanvasView?.setSlotConfig(slotA, slotC, smooth = false)
            syncSlotRuntimeDataToCanvas(smooth = false)
            AppLogger.i(TAG, "OverlayWindow.show 成功 (2038) displayId=${targetDisplay.displayId}, shouldAvoid=$shouldAvoid, slotA=$slotA, slotC=$slotC")
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "OverlayWindow.show 异常: ${t.message}", t)
            isAttached = false
            overlayRootView = null
            return false
        }
    }

    private fun showAsPresentation(): Boolean {
        // 防御性多屏兼容：Android 原生强制要求 Presentation 必须依附于独立次级副屏 (displayId != 0)
        // 当在单屏平板开发调试、或实车尚未就绪物理副屏 (targetDisplay 为主屏 Display.DEFAULT_DISPLAY) 时：
        // 自动降级为使用 WindowManager (TYPE_APPLICATION_OVERLAY) 模拟展示 Mode.FITNESS (小欧下方)，避免 InvalidDisplayException 崩溃
        if (targetDisplay.displayId == Display.DEFAULT_DISPLAY) {
            AppLogger.w(TAG, "检测到当前为单屏/主屏环境 (displayId=0)，Presentation 不可用，降级以 Overlay 模拟 Mode.FITNESS (小欧下方) 展现")
            return showAsOverlay()
        }

        return try {
            val pres = InternalPresentation(appContext, targetDisplay).apply {
                setOnTouchCallback { resetCloseButtonTimer() }
                setOnCloseCallback { onCloseListener?.invoke() }
                setOnFocusLostCallback { onFocusLostListener?.invoke() }
                show()
            }
            presentation = pres
            isAttached = true
            weatherCanvasView = pres.canvasView
            lyricsContainerView = pres.lyricsView
            gearCubeView = pres.gearView
            driveModeView = pres.driveModeView
            bottomLeftGearHudView = pres.bottomLeftGearHudView
            smartSceneOverlayView = pres.smartSceneOverlayView
            closeButtonContainer = pres.closeContainer
            if (isMusicPlaying && lastMusicTitle.isNotEmpty()) {
                setTopMusicTrackInfo(lastMusicTitle, lastMusicArtist, true)
            }
            setupNativeAnimationViews()
            setupBottomLeftGearHudView()
            resetCloseButtonTimer()

            weatherCanvasView?.visibility = View.VISIBLE
            weatherCanvasView?.alpha = 1f
            weatherCanvasView?.resumeAnimation()
            weatherCanvasView?.invalidate()

            pendingSceneConfig?.let { weatherCanvasView?.updateScene(it) }
            pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
            pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }
            val shouldAvoid = PrefsManager.isXiaoOuAvatarEnabled()
            lyricsContainerView?.alpha = 0f
            lyricsContainerView?.visibility = View.GONE
            lyricsContainerView?.pause()
            lyricsContainerView?.xiaoOuAvoidanceEnabled = shouldAvoid
            weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid, smooth = false)
            weatherCanvasView?.setLiquidGlassEnabled(com.carlyrics.util.PrefsManager.isLiquidGlassClockEnabled(), smooth = false)
            val slotA = pendingSlotA ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
            val slotC = pendingSlotC ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
            weatherCanvasView?.setSlotConfig(slotA, slotC, smooth = false)
            syncSlotRuntimeDataToCanvas(smooth = false)
            AppLogger.i(TAG, "Presentation.show 成功 (2037) targetDisplay=${targetDisplay.displayId}")
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Presentation.show 异常: ${t.message}", t)
            AppLogger.w(TAG, "尝试优雅降级为 showAsOverlay 容灾展示...")
            presentation = null
            isAttached = false
            showAsOverlay()
        }
    }

    private fun bindViews(view: View) {
        weatherCanvasView = view.findViewById(R.id.weatherCanvasView)
        lyricsContainerView = view.findViewById(R.id.lyricsContainerView)
        gearCubeView = view.findViewById(R.id.gearCubeView)
        driveModeView = view.findViewById(R.id.driveModeView)
        bottomLeftGearHudView = view.findViewById(R.id.bottomLeftGearHudView)
        smartSceneOverlayView = view.findViewById(R.id.smartSceneOverlayView)
        closeButtonContainer = view.findViewById(R.id.closeButtonContainer)
        if (isMusicPlaying && lastMusicTitle.isNotEmpty()) {
            setTopMusicTrackInfo(lastMusicTitle, lastMusicArtist, true)
        }
        view.findViewById<View>(R.id.tvCloseButton).setOnClickListener {
            onCloseListener?.invoke()
        }

        setupNativeAnimationViews()
        setupBottomLeftGearHudView()

        weatherCanvasView?.alpha = 1f
        weatherCanvasView?.visibility = View.VISIBLE
        weatherCanvasView?.resumeAnimation()

        lyricsContainerView?.alpha = 0f
        lyricsContainerView?.visibility = View.GONE
        lyricsContainerView?.pause()
        val shouldAvoid = PrefsManager.isXiaoOuAvatarEnabled()
        lyricsContainerView?.xiaoOuAvoidanceEnabled = shouldAvoid
        weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid, smooth = false)
        weatherCanvasView?.setLiquidGlassEnabled(com.carlyrics.util.PrefsManager.isLiquidGlassClockEnabled(), smooth = false)
        val slotA = pendingSlotA ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
        val slotC = pendingSlotC ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
        weatherCanvasView?.setSlotConfig(slotA, slotC, smooth = false)
        syncSlotRuntimeDataToCanvas(smooth = false)

        pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
        pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }
        pendingCallInfo?.let { weatherCanvasView?.setCallInfo(it) }
    }

    private fun setupNativeAnimationViews() {
        gearCubeView?.onFlipStartedListener = {
            isGearActive = true
            updateUnderlyingRenderState()
        }
        gearCubeView?.onFlipCompleteListener = {
            isGearActive = false
            updateUnderlyingRenderState()
        }

        driveModeView?.onWipeStartedListener = {
            isDriveModeActive = true
            updateUnderlyingRenderState()
        }
        driveModeView?.onWipeCompleteListener = {
            isDriveModeActive = false
            currentActiveDriveMode = null
            updateUnderlyingRenderState()
        }
    }

    private fun setupBottomLeftGearHudView() {
        val hud = bottomLeftGearHudView ?: return
        hud.backgroundProvider = { canvas, _ ->
            drawActiveUnderlyingView(canvas, sampleRegionOnly = true)
        }
        hud.weatherLeftProvider = {
            weatherCanvasView?.weatherHudLeftX ?: com.car.xiaoouscreen.ui.MiniGearPosition.BOTTOM_LEFT.defaultLeft
        }
        weatherCanvasView?.onSceneRenderUpdated = {
            hud.invalidateBackground()
        }
        lyricsContainerView?.onBackgroundUpdated = {
            hud.invalidateBackground()
        }
        val currentGear = com.car.xiaoouscreen.car.CarGearManager.getInstance(appContext).getCurrentGear()
        hud.setGear(currentGear, animate = false)
        val initialPos = com.carlyrics.util.PrefsManager.getMiniGearPosition()
        hud.setPosition(com.car.xiaoouscreen.ui.MiniGearPosition.fromId(initialPos), animate = false)
        hud.setRealtimeColorEnabled(com.carlyrics.util.PrefsManager.isMiniGearRealtimeColorEnabled())
        hud.setHudEnabled(com.carlyrics.util.PrefsManager.isBottomLeftGearHudEnabled(), animate = false)
        hud.bringToFront()
    }

    /**
     * 动态控制左下角常驻档位 HUD 开关 (置顶微型液态玻璃指示器)
     */
    fun setBottomLeftGearHudEnabled(enabled: Boolean) {
        autoHideHandler.post {
            bottomLeftGearHudView?.setHudEnabled(enabled, animate = true)
            if (enabled) {
                bottomLeftGearHudView?.bringToFront()
            }
        }
    }

    /**
     * 动态修改微型档位指示器的停靠位置 (5 大可选坐标)
     */
    fun setMiniGearPosition(position: Int) {
        autoHideHandler.post {
            bottomLeftGearHudView?.setPosition(com.car.xiaoouscreen.ui.MiniGearPosition.fromId(position), animate = true)
            bottomLeftGearHudView?.bringToFront()
        }
    }

    /**
     * 动态设置实验室特性：微型档位实时背景自适应计算开关
     */
    fun setMiniGearRealtimeColor(enabled: Boolean) {
        autoHideHandler.post {
            bottomLeftGearHudView?.setRealtimeColorEnabled(enabled)
        }
    }

    /**
     * 静默同步左下角微型常驻档位 HUD (开机就绪、自愈重启或免扰保护期调用，仅平滑更新左下角 HUD 指示，绝不弹起 3D 大翻转)
     */
    fun syncGearSilently(gear: String) {
        pendingGear = gear
        autoHideHandler.post {
            try {
                weatherCanvasView?.setGear(gear)
                bottomLeftGearHudView?.setGear(gear, animate = false)
                bottomLeftGearHudView?.bringToFront()
                AppLogger.recordBreadcrumb("HUD_GEAR", gear)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "静默同步左下角 HUD 档位异常: ${t.message}")
            }
        }
    }

    /**
     * 触发小欧屏 3D 正方体向上翻转档位动效 (100% 纯原生)
     * - 用户规范：先模式再档位，档位退出后直接显示主界面，绝不复活展示模式
     */
    fun triggerGearFlip(gear: String) {
        pendingGear = gear
        val gearV = gearCubeView ?: return
        autoHideHandler.post {
            if (!isAttached) return@post
            try {
                weatherCanvasView?.setGear(gear)
                if (isDriveModeActive || driveModeView?.visibility == View.VISIBLE) {
                    isDriveModeActive = false
                    currentActiveDriveMode = null
                    driveModeView?.dismiss()
                }
                isGearActive = true
                gearCubeView?.bringToFront()
                // 左下角 HUD 同步滑移档位并保持置顶
                bottomLeftGearHudView?.setGear(gear, animate = true)
                bottomLeftGearHudView?.bringToFront()
                smartSceneOverlayView?.bringToFront()

                gearV.triggerGearFlip(gear) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
                AppLogger.i(TAG, "小欧屏悬浮窗已触发 3D 档位向上翻转动效: $gear 档")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "触发档位翻转异常: ${t.message}", t)
            }
        }
    }

    /**
     * 触发小欧屏车辆模式切换由左至右光刃擦除转场 (100% 纯原生)
     * - 用户规范：先档位再模式，模式退出后直接显示主界面，绝不复活展示档位
     */
    fun triggerDriveModeSwitch(mode: String) {
        val modeV = driveModeView ?: return
        autoHideHandler.post {
            if (!isAttached) return@post
            try {
                if (isGearActive || gearCubeView?.visibility == View.VISIBLE) {
                    isGearActive = false
                    gearCubeView?.dismiss()
                }
                currentActiveDriveMode = mode
                isDriveModeActive = true
                driveModeView?.bringToFront()
                bottomLeftGearHudView?.bringToFront()
                smartSceneOverlayView?.bringToFront()

                modeV.triggerDriveModeSwitch(mode) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
                AppLogger.i(TAG, "小欧屏悬浮窗已触发驾驶模式左至右擦除转场: $mode 模式")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "触发驾驶模式擦除异常: ${t.message}", t)
            }
        }
    }

    private fun drawActiveUnderlyingView(canvas: Canvas, sampleRegionOnly: Boolean = false) {
        val weather = weatherCanvasView
        val lyrics = lyricsContainerView
        val lyricsVisible = lyrics != null && lyrics.visibility == View.VISIBLE && lyrics.alpha > 0.01f
        val lyricsAlpha = if (lyricsVisible) lyrics!!.alpha.coerceIn(0f, 1f) else 0f

        // 仅当歌词层尚未完全覆盖或天气层自身可见时绘制写实天气底图
        if (lyricsAlpha < 0.99f) {
            if (weather != null && (weather.visibility == View.VISIBLE || lyricsAlpha <= 0.01f)) {
                weather.drawStrict1920(canvas, backgroundOnlyForGlassSample = sampleRegionOnly)
            } else {
                canvas.drawColor(Color.BLACK)
            }
        }

        // 若歌词图层正在展示或处于交叉淡入淡出过程中，按真实透明度实时合成，杜绝转场期间背景采样提前/延后跳变
        if (lyricsVisible && lyrics != null) {
            if (lyricsAlpha >= 0.99f) {
                lyrics.drawStrict1920(canvas)
            } else {
                val saveCount = canvas.saveLayerAlpha(0f, 0f, 1920f, 384f, (lyricsAlpha * 255f).toInt().coerceIn(0, 255))
                lyrics.drawStrict1920(canvas)
                canvas.restoreToCount(saveCount)
            }
        }
    }

    // ==================== 双页面丝滑平滑转场 ====================

    fun getCurrentPage(): ActivePage = currentPage

    /**
     * 平滑切入车载歌词页面
     */
    fun switchToLyricsPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.LYRICS && lyricsContainerView?.visibility == View.VISIBLE && lyricsContainerView?.alpha == 1f) {
            weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false)
            return
        }
        currentPage = ActivePage.LYRICS

        // 全屏歌词状态下：隐藏顶部居中常驻音乐胶囊
        weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false)

        val lyrics = lyricsContainerView ?: return
        val weather = weatherCanvasView

        lyrics.resume()
        lyrics.visibility = View.VISIBLE
        if (driveModeView?.visibility == View.VISIBLE) {
            driveModeView?.bringToFront()
        }
        if (gearCubeView?.visibility == View.VISIBLE) {
            gearCubeView?.bringToFront()
        }
        bottomLeftGearHudView?.bringToFront()
        smartSceneOverlayView?.bringToFront()

        if (smooth) {
            lyrics.animate().cancel()
            weather?.animate()?.cancel()

            lyrics.animate()
                .alpha(1f)
                .setDuration(500L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener {
                    bottomLeftGearHudView?.invalidateBackground()
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottomLeftGearHudView?.invalidateBackground()
                    }
                })
                .start()

            weather?.animate()
                ?.alpha(0f)
                ?.setDuration(500L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.LYRICS) {
                            weather.visibility = View.GONE
                            weather.pauseAnimation()
                            bottomLeftGearHudView?.invalidateBackground()
                        }
                    }
                })
                ?.start()
        } else {
            lyrics.alpha = 1f
            weather?.visibility = View.GONE
            weather?.alpha = 0f
            weather?.pauseAnimation()
            bottomLeftGearHudView?.invalidateBackground()
        }
    }

    /**
     * 平滑回退至默认时间天气页面
     */
    fun switchToWeatherPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.WEATHER && weatherCanvasView?.visibility == View.VISIBLE && weatherCanvasView?.alpha == 1f) {
            checkAndRestoreTopMusicInfo(smooth)
            return
        }
        currentPage = ActivePage.WEATHER

        val weather = weatherCanvasView ?: return
        val lyrics = lyricsContainerView

        checkAndRestoreTopMusicInfo(smooth)

        weather.resumeAnimation()
        weather.visibility = View.VISIBLE
        if (driveModeView?.visibility == View.VISIBLE) {
            driveModeView?.bringToFront()
        }
        if (gearCubeView?.visibility == View.VISIBLE) {
            gearCubeView?.bringToFront()
        }
        bottomLeftGearHudView?.bringToFront()
        smartSceneOverlayView?.bringToFront()

        if (smooth) {
            weather.animate().cancel()
            lyrics?.animate()?.cancel()

            weather.animate()
                .alpha(1f)
                .setDuration(800L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener {
                    bottomLeftGearHudView?.invalidateBackground()
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottomLeftGearHudView?.invalidateBackground()
                    }
                })
                .start()

            lyrics?.animate()
                ?.alpha(0f)
                ?.setDuration(800L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.WEATHER) {
                            lyrics.visibility = View.GONE
                            lyrics.pause()
                            bottomLeftGearHudView?.invalidateBackground()
                        }
                    }
                })
                ?.start()
        } else {
            weather.alpha = 1f
            lyrics?.visibility = View.GONE
            lyrics?.alpha = 0f
            lyrics?.pause()
            bottomLeftGearHudView?.invalidateBackground()
        }
    }

    /**
     * 平滑隐藏顶部音乐播放信息胶囊 (岛)
     */
    fun hideTopMusicTrackInfo(smooth: Boolean = true) {
        weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false)
    }

    /**
     * 评估并恢复天气主界面的顶部常驻音乐信息胶囊
     * - 仅在非全屏歌词模式且音乐正在播放时展示
     * - 如果没有音乐播放，即天气界面恢复时间显示时，必须严格隐藏！
     */
    private fun checkAndRestoreTopMusicInfo(smooth: Boolean) {
        val cleanTitle = lastMusicTitle.trim()
        val cleanArtist = lastMusicArtist.trim()

        val shouldShow = isMusicPlaying &&
            cleanTitle.isNotEmpty() &&
            !MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, cleanArtist) &&
            !PrefsManager.isStandaloneLyricsEnabled() &&
            currentPage != ActivePage.LYRICS

        if (shouldShow) {
            weatherCanvasView?.setTopMusicTrackInfo(cleanTitle, cleanArtist, isPlaying = true)
        } else {
            weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false)
        }
    }

    // ==================== 歌词数据透传 ====================

    fun setLyricsTrackInfo(title: String, artist: String) {
        lastLyricsTrackTitle = title
        lastLyricsTrackArtist = artist
        lyricsContainerView?.setTrackInfo(title, artist)
        weatherCanvasView?.setSlotTrackInfo(title, artist)
    }

    /**
     * 设置并更新小欧屏最顶部居中高亮音乐播放信息 (‘音乐图标’ 歌名-歌手名)
     * - 居中对齐，超出边界自动以省略号截断
     * - 播放时淡入展示；无音乐播放或恢复时间显示时优雅淡出收敛隐藏
     */
    fun setTopMusicTrackInfo(title: String, artist: String, isPlaying: Boolean) {
        lastMusicTitle = title
        lastMusicArtist = artist
        this.isMusicPlaying = isPlaying
        val cleanTitle = title.trim()
        val isValidTrack = cleanTitle.isNotEmpty() && !MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, artist.trim())
        if (!isPlaying && !isValidTrack) {
            // 停止播放已满 5 秒回退或播放器关闭：与歌词同步平滑淡出封面槽位至空白
            isAlbumCoverSessionActive = false
            weatherCanvasView?.setSlotTrackInfo("", "", isPlaying = false)
            weatherCanvasView?.setAlbumCoverSlotVisible(false, smooth = true)
        } else if (isPlaying && isValidTrack) {
            isAlbumCoverSessionActive = true
            weatherCanvasView?.setSlotTrackInfo(title, artist, isPlaying = true)
            weatherCanvasView?.setAlbumCoverSlotVisible(true, smooth = true)
        } else {
            // 刚暂停处于 5 秒宽限期内：保持封面可见与歌词同频定格，直到 5 秒回退触发
            if (isValidTrack) {
                weatherCanvasView?.setSlotTrackInfo(title, artist, isPlaying = false)
            } else {
                weatherCanvasView?.setSlotMusicPlaying(false)
            }
        }

        // 全屏歌词状态下 (ActivePage.LYRICS 或系统启用了全屏歌词且音乐正在播放)，不显示顶部的歌名等
        if (currentPage == ActivePage.LYRICS || PrefsManager.isStandaloneLyricsEnabled()) {
            weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false)
            return
        }

        weatherCanvasView?.setTopMusicTrackInfo(title, artist, isPlaying)
    }

    fun setLyricsCoverUrl(url: String?) {
        lastLyricsCoverUrl = url
        lyricsContainerView?.setCoverUrl(url)
        weatherCanvasView?.setAlbumCoverUrl(url, smooth = true)
        bottomLeftGearHudView?.invalidateBackground()
    }

    fun setBatteryInfo(info: CarGearManager.BatteryInfo) {
        pendingBatteryInfo = info
        autoHideHandler.post {
            weatherCanvasView?.setBatteryInfo(info)
        }
    }

    fun setCarOutsideTempC(tempC: Float?) {
        pendingCarOutsideTempC = tempC
        autoHideHandler.post {
            weatherCanvasView?.setCarOutsideTempC(tempC)
        }
    }

    fun setCarCabinTempC(tempC: Float?) {
        pendingCarCabinTempC = tempC
        autoHideHandler.post {
            weatherCanvasView?.setCarCabinTempC(tempC)
        }
    }

    fun setMotorPowerInfo(info: CarGearManager.MotorPowerInfo) {
        pendingMotorPowerInfo = info
        autoHideHandler.post {
            weatherCanvasView?.setMotorPowerInfo(info)
        }
    }

    fun setRpmInfo(info: CarGearManager.RpmInfo) {
        pendingRpmInfo = info
        autoHideHandler.post {
            weatherCanvasView?.setRpmInfo(info)
        }
    }

    fun setLaneChangeAlert(alert: CarGearManager.LaneChangeAlert) {
        autoHideHandler.post {
            weatherCanvasView?.setLaneChangeAlert(alert)
            smartSceneOverlayView?.setLaneChangeAlert(alert)
        }
    }

    fun setTurnSignalInfo(info: CarGearManager.TurnSignalInfo) {
        autoHideHandler.post {
            weatherCanvasView?.setTurnSignalInfo(info)
            smartSceneOverlayView?.setTurnSignalInfo(info)
        }
    }

    fun setTurnSignalSyncEnabled(enabled: Boolean) {
        autoHideHandler.post {
            weatherCanvasView?.setTurnSignalSyncEnabled(enabled)
            smartSceneOverlayView?.setTurnSignalSyncEnabled(enabled)
        }
    }

    fun setLaneChangeAssistEnabled(enabled: Boolean) {
        autoHideHandler.post {
            weatherCanvasView?.setLaneChangeAssistEnabled(enabled)
            smartSceneOverlayView?.setLaneChangeAssistEnabled(enabled)
        }
    }

    private fun syncSlotRuntimeDataToCanvas(smooth: Boolean) {
        val canvas = weatherCanvasView ?: return
        val gearMgr = CarGearManager.getInstance(appContext)
        canvas.setGear(pendingGear ?: gearMgr.getCurrentGear())
        val battery = pendingBatteryInfo ?: gearMgr.getCurrentBatteryInfo()
        canvas.setBatteryInfo(battery)
        canvas.setCarOutsideTempC(pendingCarOutsideTempC ?: gearMgr.getCurrentOutsideTempC())
        canvas.setCarCabinTempC(pendingCarCabinTempC ?: gearMgr.getCurrentCabinTempC())
        canvas.setMotorPowerInfo(pendingMotorPowerInfo ?: gearMgr.getCurrentMotorPowerInfo())
        canvas.setRpmInfo(pendingRpmInfo ?: gearMgr.getCurrentRpmInfo())
        canvas.setLaneChangeAlert(gearMgr.currentLaneChangeAlert)
        canvas.setTurnSignalInfo(gearMgr.currentTurnSignalInfo)
        smartSceneOverlayView?.setLaneChangeAlert(gearMgr.currentLaneChangeAlert)
        smartSceneOverlayView?.setTurnSignalInfo(gearMgr.currentTurnSignalInfo)
        val effectiveTitle = lastLyricsTrackTitle.ifBlank { lastMusicTitle }
        val effectiveArtist = lastLyricsTrackArtist.ifBlank { lastMusicArtist }
        if (effectiveTitle.isNotBlank() && (isMusicPlaying || isAlbumCoverSessionActive)) {
            canvas.setSlotTrackInfo(effectiveTitle, effectiveArtist, isPlaying = isMusicPlaying)
            canvas.setAlbumCoverSlotVisible(true, smooth = false)
            if (!lastLyricsCoverUrl.isNullOrBlank()) {
                canvas.setAlbumCoverUrl(lastLyricsCoverUrl, smooth = smooth)
            }
        } else {
            canvas.setAlbumCoverSlotVisible(false, smooth = false)
        }
    }

    fun setLyricsLines(lines: List<LyricLine>) {
        lastLyricLines = lines
        lyricsContainerView?.setLyrics(lines)
    }

    fun updateLyricsProgress(positionMs: Long) {
        lastLyricsProgressMs = positionMs
        lyricsContainerView?.updateProgress(positionMs)
    }

    fun reloadLyricsSkin(skinType: SkinType) {
        lyricsContainerView?.reloadSkin(skinType)
    }

    fun updateLyricsFontSize(sp: Float) {
        lyricsContainerView?.getSkin()?.fontSizeSp = sp
    }

    fun updateLyricsSplitRatio(ratio: Float) {
        lyricsContainerView?.getSkin()?.splitRatio = ratio
    }

    fun updateLyricsAlignment(alignment: Int) {
        lyricsContainerView?.getSkin()?.lyricsTextAlignment = alignment
    }

    fun updateLyricsMarquee(enabled: Boolean) {
        lyricsContainerView?.getSkin()?.marqueeEnabled = enabled
    }

    fun updateLyricsScale(scale: Float) {
        lyricsContainerView?.getSkin()?.scaleFactor = scale
    }

    fun updateLyricsInfoFontScale(scale: Float) {
        lyricsContainerView?.getSkin()?.infoFontScale = scale
    }

    fun updateLyricsOffsetMs(offsetMs: Long) {
        lyricsContainerView?.getSkin()?.lyricsOffsetMs = offsetMs
    }

    fun updateLyricsAvoidance(avoid: Boolean) {
        lyricsContainerView?.xiaoOuAvoidanceEnabled = avoid
    }

    fun updateAvatarAndAvoidance(enabled: Boolean) {
        autoHideHandler.post {
            updateCanvasAvoidance(enabled)
            updateLyricsAvoidance(enabled)
        }
    }

    fun updateSlotLayout(slotA: XiaoOuSlotType, slotC: XiaoOuSlotType, smooth: Boolean = true) {
        pendingSlotA = slotA
        pendingSlotC = slotC
        autoHideHandler.post {
            weatherCanvasView?.setSlotConfig(slotA, slotC, smooth = smooth)
        }
    }

    // ==================== 天气画布五行居中歌词控制 ====================

    fun setCanvasLyrics(lines: List<LyricLine>) {
        pendingCanvasLyricLines = lines
        if (lines.isEmpty()) {
            lastCanvasLyricsProgressMs = 0L
        }
        weatherCanvasView?.setLyrics(lines)
    }

    fun updateCanvasLyricsProgress(positionMs: Long) {
        lastCanvasLyricsProgressMs = positionMs
        weatherCanvasView?.updateLyricsProgress(positionMs)
    }

    fun setCanvasLyricsActive(active: Boolean, smooth: Boolean = true) {
        pendingCanvasLyricsActive = active
        weatherCanvasView?.setLyricsActive(active, smooth)
    }

    fun updateCanvasAvoidance(avoid: Boolean) {
        pendingCanvasAvoidance = avoid
        weatherCanvasView?.setXiaoOuAvoidance(avoid)
    }

    // ==================== 车载接打电话控制 (方案 A 优先响应) ====================

    fun setCallInfo(callInfo: CarCallManager.CallInfo) {
        pendingCallInfo = callInfo
        if (callInfo.isCallActive) {
            // 通话开始/来电：若当前处于歌词模式，平滑切回天气主界面展示来电
            if (currentPage == ActivePage.LYRICS) {
                wasInLyricsBeforeCall = true
                switchToWeatherPage(smooth = true)
            }
        } else if (callInfo.state == CarCallManager.CallState.IDLE) {
            // 通话挂断结束完全恢复 IDLE：若此前在看歌词且音乐仍在播放，自动切回歌词模式
            if (wasInLyricsBeforeCall) {
                val isPlaying = com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.isPlaying == true
                if (isPlaying) {
                    switchToLyricsPage(smooth = true)
                }
                wasInLyricsBeforeCall = false
            }
        }
        weatherCanvasView?.setCallInfo(callInfo)
    }

    // ==================== 天气时钟控制 ====================

    fun resetCloseButtonTimer() {
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        closeButtonContainer?.visibility = View.VISIBLE
        autoHideHandler.postDelayed(hideCloseRunnable, 5000L)
    }

    fun updateScene(scene: WeatherSceneConfig) {
        pendingSceneConfig = scene
        weatherCanvasView?.updateScene(scene)
    }

    fun triggerInstantLightning() {
        weatherCanvasView?.triggerInstantLightning()
    }

    fun setLiquidGlassEnabled(enabled: Boolean) {
        weatherCanvasView?.setLiquidGlassEnabled(enabled)
    }

    fun pause() {
        weatherCanvasView?.pauseAnimation()
        lyricsContainerView?.pause()
    }

    fun resume() {
        if (isAttached) {
            weatherCanvasView?.resumeAnimation()
            if (currentPage == ActivePage.LYRICS) {
                lyricsContainerView?.resume()
            }
        }
    }

    fun dismiss() {
        autoHideHandler.removeCallbacks(hideCloseRunnable)

        // 彻底切断底层子视图物理引擎与模糊监听闭包
        weatherCanvasView?.onSceneRenderUpdated = null
        lyricsContainerView?.onBackgroundUpdated = null
        weatherCanvasView?.pauseAnimation()
        lyricsContainerView?.pause()
        bottomLeftGearHudView?.dismiss()
        gearCubeView?.dismiss()
        driveModeView?.dismiss()

        overlayRootView?.let { root ->
            windowManager?.let { wm ->
                try {
                    wm.removeViewImmediate(root)
                } catch (_: Throwable) {
                    try {
                        wm.removeView(root)
                    } catch (_: Throwable) {}
                }
            }
            overlayRootView = null
            windowManager = null
        }

        presentation?.let { p ->
            try {
                p.dismiss()
            } catch (ignored: Throwable) {}
            presentation = null
        }

        weatherCanvasView = null
        lyricsContainerView = null
        gearCubeView = null
        driveModeView = null
        bottomLeftGearHudView = null
        smartSceneOverlayView = null
        closeButtonContainer = null

        isAttached = false
        isGearActive = false
        isDriveModeActive = false
        currentActiveDriveMode = null
        AppLogger.i(TAG, "小欧副屏宿主窗口已销毁")
    }

    /**
     * 辅助方案 B：失焦自愈与无缝重新登顶
     * 当车机原厂系统在 2037 层后发调用 MediaPresentation.show() 覆盖我方界面时，
     * 通过创建新 Presentation 并在显示就绪后立即 dismiss 旧 Presentation，
     * 视觉上 0 闪黑、0 割裂，重新将我方界面排回 2037 最顶层！
     */
    fun reassertTop(force: Boolean = false): Boolean {
        if (!isAttached) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReassertTime < 2000L) {
            AppLogger.d(TAG, "🛡️ 置顶重登节流保护 (2000ms 内已执行)，跳过重复调用")
            return false
        }
        if (!force && consecutiveReassertCount >= 6 && now - lastReassertTime < 15000L) {
            AppLogger.w(TAG, "🛡️ 连续失焦重登已达上限 (6次)，为防死循环暂停自动重登")
            return false
        }
        if (now - lastReassertTime > 15000L || force) {
            consecutiveReassertCount = 0
        }
        lastReassertTime = now
        if (!force) {
            consecutiveReassertCount++
        }

        return try {
            if (mode == Mode.FITNESS && targetDisplay.displayId != Display.DEFAULT_DISPLAY) {
                AppLogger.i(TAG, "🛡️ 开始执行 Presentation (2037) 无缝重新登顶，压制系统自带媒体界面...")
                val oldPres = presentation
                // 核心零闪烁重构：先构造新 Presentation（不立即 show()），在其首次上屏渲染前将全部状态、
                // 天气场景、实时歌词行与精确毫秒进度、页面显隐 100% 瞬间预注入完毕，再调用 show()！
                // 彻底根除“新窗口首帧先画初始化大时钟，100ms 后再跳回歌词页”的闪屏问题！
                val newPres = InternalPresentation(appContext, targetDisplay).apply {
                    setOnTouchCallback { resetCloseButtonTimer() }
                    setOnCloseCallback { onCloseListener?.invoke() }
                    setOnFocusLostCallback { onFocusLostListener?.invoke() }
                }
                presentation = newPres
                weatherCanvasView = newPres.canvasView
                lyricsContainerView = newPres.lyricsView
                gearCubeView = newPres.gearView
                driveModeView = newPres.driveModeView
                bottomLeftGearHudView = newPres.bottomLeftGearHudView
                smartSceneOverlayView = newPres.smartSceneOverlayView
                closeButtonContainer = newPres.closeContainer

                // 在注入场景前先确立页面显隐与休眠状态，避免全屏歌词页期间触发天气画布唤醒
                if (currentPage == ActivePage.LYRICS) {
                    lyricsContainerView?.visibility = View.VISIBLE
                    lyricsContainerView?.alpha = 1f
                    weatherCanvasView?.visibility = View.GONE
                    weatherCanvasView?.alpha = 0f
                    weatherCanvasView?.pauseAnimation()
                } else {
                    weatherCanvasView?.visibility = View.VISIBLE
                    weatherCanvasView?.alpha = 1f
                    lyricsContainerView?.visibility = View.GONE
                    lyricsContainerView?.alpha = 0f
                    lyricsContainerView?.pause()
                }

                val shouldAvoid = PrefsManager.isXiaoOuAvatarEnabled()
                lyricsContainerView?.xiaoOuAvoidanceEnabled = shouldAvoid
                weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid, smooth = false)
                weatherCanvasView?.setLiquidGlassEnabled(PrefsManager.isLiquidGlassClockEnabled(), smooth = false)
                val slotA = pendingSlotA ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
                val slotC = pendingSlotC ?: XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
                weatherCanvasView?.setSlotConfig(slotA, slotC, smooth = false)
                syncSlotRuntimeDataToCanvas(smooth = false)

                // 1. 无动画直接恢复天气场景与通话卡片
                pendingSceneConfig?.let { weatherCanvasView?.updateScene(it, animate = false) }
                pendingCallInfo?.let { weatherCanvasView?.setCallInfo(it) }

                // 2. 无动画恢复顶栏音乐胶囊
                if (isMusicPlaying && lastMusicTitle.isNotEmpty() && currentPage != ActivePage.LYRICS && !PrefsManager.isStandaloneLyricsEnabled()) {
                    weatherCanvasView?.setTopMusicTrackInfo(lastMusicTitle, lastMusicArtist, isPlaying = true, smooth = false)
                } else {
                    weatherCanvasView?.setTopMusicTrackInfo("", "", isPlaying = false, smooth = false)
                }

                // 3. 无动画瞬间恢复画布五行居中歌词：必须【先注入进度，再注入歌词行】，
                //    利用 prevIdx == -1 瞬间精准定位到当前播放行，0 滚屏动画、0 回退到第 0 行！
                val effectiveCanvasProgress = if (lastCanvasLyricsProgressMs > 0L) lastCanvasLyricsProgressMs else lastLyricsProgressMs
                if (effectiveCanvasProgress > 0L) {
                    weatherCanvasView?.updateLyricsProgress(effectiveCanvasProgress)
                }
                pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
                pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }

                // 4. 注入歌词数据与排版参数 (皮肤若处于隐藏休眠态将在 LyricsNormalizedContainerView 内延迟至激活时懒加载)
                lyricsContainerView?.let { lv ->
                    if (currentPage == ActivePage.LYRICS) {
                        lv.reloadSkin(PrefsManager.getSkinType())
                    }
                    lv.getSkin()?.let { skin ->
                        skin.fontSizeSp = PrefsManager.getFontSizeSp()
                        skin.splitRatio = PrefsManager.getSplitRatio()
                        skin.lyricsTextAlignment = PrefsManager.getLyricsAlignment()
                        skin.marqueeEnabled = PrefsManager.isMarqueeEnabled()
                        skin.scaleFactor = PrefsManager.getDpiScale()
                        skin.infoFontScale = PrefsManager.getInfoFontScale()
                        skin.lyricsOffsetMs = PrefsManager.lyricsOffsetMs
                    }
                    if (lastLyricsTrackTitle.isNotEmpty()) {
                        lv.setTrackInfo(lastLyricsTrackTitle, lastLyricsTrackArtist)
                    }
                    if (lastLyricsCoverUrl != null) {
                        lv.setCoverUrl(lastLyricsCoverUrl)
                    }
                    if (lastLyricLines.isNotEmpty()) {
                        lv.setLyrics(lastLyricLines)
                        if (lastLyricsProgressMs > 0L) {
                            lv.updateProgress(lastLyricsProgressMs)
                        }
                    }
                }

                // 5. 同步挡位 HUD 与页面显隐（在 show() 前就位，避免全屏歌词页闪现天气页）
                setupNativeAnimationViews()
                setupBottomLeftGearHudView()
                resetCloseButtonTimer()

                if (currentPage == ActivePage.LYRICS) {
                    lyricsContainerView?.visibility = View.VISIBLE
                    lyricsContainerView?.alpha = 1f
                    lyricsContainerView?.resume()
                    weatherCanvasView?.visibility = View.GONE
                    weatherCanvasView?.pauseAnimation()
                } else {
                    weatherCanvasView?.visibility = View.VISIBLE
                    weatherCanvasView?.alpha = 1f
                    weatherCanvasView?.resumeAnimation()
                    lyricsContainerView?.visibility = View.GONE
                    lyricsContainerView?.pause()
                }

                // 6. 全部视图状态 100% 就绪后正式上屏！首帧即与当前播放画面像素级一致
                newPres.show()

                // 7. 销毁旧 Presentation：延迟 60ms 等新首帧提交合成后立即释放旧窗口与全部 GPU 图层
                newPres.canvasView.postDelayed({
                    try {
                        oldPres?.destroy()
                    } catch (_: Throwable) {}
                }, 60L)

                AppLogger.i(TAG, "🛡️ 小欧副屏 2037 Presentation 无缝重新置顶完成！")
                true
            } else if (overlayRootView != null && windowManager != null) {
                val root = overlayRootView ?: return false
                val wm = windowManager ?: return false
                val lp = (root.layoutParams as? WindowManager.LayoutParams) ?: return false
                wm.updateViewLayout(root, lp)
                AppLogger.i(TAG, "🛡️ Overlay 2038 窗口更新布局置顶完成")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "reassertTop 异常: ${t.message}", t)
            false
        }
    }

    // 内部 Presentation 实现 (Type 2037)
    private class InternalPresentation(
        context: Context,
        display: Display
    ) : Presentation(context, display) {

        val canvasView: CarWeatherCanvasView
        val lyricsView: LyricsNormalizedContainerView
        val gearView: GearCubeFlipView
        val driveModeView: DriveModeWipeView
        val bottomLeftGearHudView: CarBottomLeftGearHudView
        val smartSceneOverlayView: CarSmartSceneOverlayView
        val closeContainer: View
        private var onTouchCallback: (() -> Unit)? = null
        private var onCloseCallback: (() -> Unit)? = null
        private var onFocusLostCallback: (() -> Unit)? = null

        fun setOnTouchCallback(cb: () -> Unit) { onTouchCallback = cb }
        fun setOnCloseCallback(cb: () -> Unit) { onCloseCallback = cb }
        fun setOnFocusLostCallback(cb: () -> Unit) { onFocusLostCallback = cb }

        init {
            window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            val contentView = LayoutInflater.from(context).inflate(R.layout.presentation_weather_overlay, null)

            val root = TouchInterceptFrameLayout(context).apply {
                addView(contentView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                onTouchDown = { onTouchCallback?.invoke() }
                onFocusLost = { onFocusLostCallback?.invoke() }
            }
            setContentView(root)

            canvasView = contentView.findViewById(R.id.weatherCanvasView)
            lyricsView = contentView.findViewById(R.id.lyricsContainerView)
            gearView = contentView.findViewById(R.id.gearCubeView)
            driveModeView = contentView.findViewById(R.id.driveModeView)
            bottomLeftGearHudView = contentView.findViewById(R.id.bottomLeftGearHudView)
            smartSceneOverlayView = contentView.findViewById(R.id.smartSceneOverlayView)
            closeContainer = contentView.findViewById(R.id.closeButtonContainer)
            contentView.findViewById<View>(R.id.tvCloseButton).setOnClickListener {
                onCloseCallback?.invoke()
            }

            canvasView.alpha = 1f
            canvasView.visibility = View.VISIBLE

            lyricsView.alpha = 0f
            lyricsView.visibility = View.GONE
            lyricsView.pause()
        }

        fun destroy() {
            onTouchCallback = null
            onCloseCallback = null
            onFocusLostCallback = null
            canvasView.onSceneRenderUpdated = null
            lyricsView.onBackgroundUpdated = null
            canvasView.pauseAnimation()
            canvasView.destroy()
            lyricsView.pause()
            lyricsView.destroy()
            gearView.dismiss()
            driveModeView.dismiss()
            bottomLeftGearHudView.dismiss()
            try {
                dismiss()
            } catch (_: Throwable) {}
        }
    }

    // 触摸拦截与失焦回弹守护容器：捕获用户触控唤出退出按钮，并监听被系统窗口遮挡时的失焦事件
    private class TouchInterceptFrameLayout(context: Context) : FrameLayout(context) {
        var onTouchDown: (() -> Unit)? = null
        var onFocusLost: (() -> Unit)? = null

        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            if (ev?.action == MotionEvent.ACTION_DOWN) {
                onTouchDown?.invoke()
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            // 车机多屏环境：副屏失去窗口焦点是主屏操作（切桌面、点应用）的正常跨屏调度，
            // 绝不能在副屏失焦时触发重登或重建 Presentation，彻底杜绝切桌面时歌词回零跳变！
        }
    }

    companion object {
        private const val TAG = "WeatherOverlayWindow"
    }
}

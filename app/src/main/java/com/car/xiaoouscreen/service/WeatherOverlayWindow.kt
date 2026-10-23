package com.car.xiaoouscreen.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.ui.CarWeatherCanvasView
import com.carlyrics.display.LyricsNormalizedContainerView
import com.carlyrics.model.LyricLine
import com.carlyrics.skin.SkinType
import java.io.ByteArrayOutputStream

/**
 * 小欧屏悬浮窗 / Presentation 宿主窗口：
 * - Mode.TOP (小欧上方): 采用系统 WindowManager.addView + TYPE_APPLICATION_OVERLAY (2038)
 * - Mode.FITNESS (小欧下方): 采用 android.app.Presentation (2037)
 * - 双页面无缝平滑转场：天气时钟页与车载歌词页深度整合，未激活页面彻底停止渲染以实现极致性能
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
    private var gearCubeWebView: WebView? = null
    private var isWebPageLoaded = false
    private var pendingGearFlip: String? = null

    private var driveModeWebView: WebView? = null
    private var isDriveModeWebPageLoaded = false
    private var pendingDriveModeSwitch: String? = null

    // 画面层级优先级管理 (由高至低)：
    // 1. 档位切换 (最高优先级，始终置于顶层)
    // 2. 模式切换 (位于档位下方、歌词/天气上方)
    // 3. 全屏歌词
    // 4. 天气页面 (基底)
    private var isGearActive = false
    private var isDriveModeActive = false
    private var currentActiveDriveMode: String? = null

    private fun updateUnderlyingRenderState() {
        val shouldPauseUnderlying = isGearActive || isDriveModeActive
        if (shouldPauseUnderlying) {
            if (currentPage == ActivePage.WEATHER) {
                weatherCanvasView?.pauseAnimation()
            } else if (currentPage == ActivePage.LYRICS) {
                lyricsContainerView?.pause()
            }
        } else {
            // 只有当档位和驾驶模式都完全退出后，才恢复底层画布动画
            if (currentPage == ActivePage.WEATHER) {
                weatherCanvasView?.resumeAnimation()
            } else if (currentPage == ActivePage.LYRICS) {
                lyricsContainerView?.resume()
            }
        }
    }

    private var closeButtonContainer: View? = null

    private var currentPage: ActivePage = ActivePage.WEATHER

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideCloseRunnable = Runnable {
        closeButtonContainer?.visibility = View.GONE
    }

    var onCloseListener: (() -> Unit)? = null
    var isAttached = false
        private set

    private var pendingSceneConfig: WeatherSceneConfig? = null
    private var pendingCanvasLyricLines: List<LyricLine>? = null
    private var pendingCanvasLyricsActive: Boolean? = null
    private var pendingCanvasAvoidance: Boolean? = null

    fun show(): Boolean {
        if (isAttached) return true

        return if (mode == Mode.TOP) {
            showAsWindowOverlay()
        } else {
            val ok = showAsPresentation()
            if (!ok) {
                Log.w(TAG, "Presentation 启动失败，降级使用 WindowOverlay 显示")
                showAsWindowOverlay()
            } else {
                true
            }
        }
    }

    private fun showAsWindowOverlay(): Boolean {
        try {
            val displayContext = if (targetDisplay.displayId != Display.DEFAULT_DISPLAY) {
                appContext.createDisplayContext(targetDisplay)
            } else {
                appContext
            }

            windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager == null) {
                Log.e(TAG, "无法获取 WindowManager")
                return false
            }

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

            val lp = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }

            windowManager?.addView(overlayRootView, lp)
            isAttached = true
            resetCloseButtonTimer()

            pendingSceneConfig?.let { weatherCanvasView?.updateScene(it) }
            Log.i(TAG, "WindowManager.addView 成功 (2038) targetDisplay=${targetDisplay.displayId}")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "WindowManager.addView 异常: ${t.message}", t)
            isAttached = false
            overlayRootView = null
            return false
        }
    }

    private fun showAsPresentation(): Boolean {
        return try {
            presentation = InternalPresentation(appContext, targetDisplay).apply {
                setOnTouchCallback { resetCloseButtonTimer() }
                setOnCloseCallback { onCloseListener?.invoke() }
                show()
            }
            isAttached = true
            weatherCanvasView = presentation?.canvasView
            lyricsContainerView = presentation?.lyricsView
            gearCubeWebView = presentation?.gearView
            gearCubeWebView?.let { initGearWebView(it) }
            driveModeWebView = presentation?.driveModeView
            driveModeWebView?.let { initDriveModeWebView(it) }
            closeButtonContainer = presentation?.closeContainer
            resetCloseButtonTimer()

            pendingSceneConfig?.let { weatherCanvasView?.updateScene(it) }
            pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
            pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }
            val shouldAvoid = (mode == Mode.FITNESS) && com.carlyrics.util.PrefsManager.isXiaoOuAvoidanceEnabled()
            weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid)
            Log.i(TAG, "Presentation.show 成功 (2037) targetDisplay=${targetDisplay.displayId}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Presentation.show 异常: ${t.message}", t)
            isAttached = false
            presentation = null
            false
        }
    }

    private fun bindViews(view: View) {
        weatherCanvasView = view.findViewById(R.id.weatherCanvasView)
        lyricsContainerView = view.findViewById(R.id.lyricsContainerView)
        gearCubeWebView = view.findViewById(R.id.gearCubeWebView)
        gearCubeWebView?.let { initGearWebView(it) }
        driveModeWebView = view.findViewById(R.id.driveModeWebView)
        driveModeWebView?.let { initDriveModeWebView(it) }
        closeButtonContainer = view.findViewById(R.id.closeButtonContainer)
        view.findViewById<View>(R.id.tvCloseButton).setOnClickListener {
            onCloseListener?.invoke()
        }

        weatherCanvasView?.alpha = 1f
        weatherCanvasView?.visibility = View.VISIBLE
        weatherCanvasView?.resumeAnimation()

        lyricsContainerView?.alpha = 0f
        lyricsContainerView?.visibility = View.GONE
        lyricsContainerView?.pause()
        val shouldAvoid = (mode == Mode.FITNESS) && com.carlyrics.util.PrefsManager.isXiaoOuAvoidanceEnabled()
        lyricsContainerView?.xiaoOuAvoidanceEnabled = shouldAvoid
        weatherCanvasView?.setXiaoOuAvoidance(shouldAvoid)

        pendingCanvasLyricLines?.let { weatherCanvasView?.setLyrics(it) }
        pendingCanvasLyricsActive?.let { weatherCanvasView?.setLyricsActive(it, smooth = false) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initGearWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onFlipStarted() {
                autoHideHandler.post {
                    Log.i(TAG, "3D档位翻转纹理解码就绪，展现 WebView 并冻结原生画布")
                    isGearActive = true
                    gearCubeWebView?.alpha = 1f
                    gearCubeWebView?.visibility = View.VISIBLE
                    updateUnderlyingRenderState()
                }
            }

            @JavascriptInterface
            fun onRollOutComplete() {
                autoHideHandler.post {
                    Log.i(TAG, "3D档位翻转回原页面完成，隐藏 WebView 图层")
                    isGearActive = false
                    gearCubeWebView?.visibility = View.GONE

                    if (isDriveModeActive) {
                        // 无论是在切档前触发的模式 (先模式后档位)，还是切档期间准备好的模式 (先档位后模式)，
                        // 档位 3D 退出翻转后展现在用户眼前的都已经是完全展现态的模式页面，
                        // 此时开始驻留 2 秒，随后自左向右光刃擦除退出到主界面！
                        Log.i(TAG, "档位 3D 退出完成，启动模式页面驻留 2 秒后光刃退出: $currentActiveDriveMode 模式")
                        driveModeWebView?.evaluateJavascript("window.resumeDriveModeDwell && window.resumeDriveModeDwell()", null)
                        updateUnderlyingRenderState()
                    } else {
                        updateUnderlyingRenderState()
                    }
                }
            }

            @JavascriptInterface
            fun onPageReady() {
                autoHideHandler.post {
                    gearCubeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
                }
            }
        }, "GearBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                isWebPageLoaded = true
                gearCubeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
                pendingGearFlip?.let { pending ->
                    pendingGearFlip = null
                    triggerGearFlip(pending)
                }
            }
        }

        webView.loadUrl("file:///android_asset/hmi/gear_cube_flip.html")
    }

    /**
     * 触发小欧屏 3D 正方体向上翻转档位动效 (优先级 1 - 最高优先级)
     * - 连续切档防黑屏：若已在显示中，直接就地平滑刷新，绝不设 alpha=0f
     * - 若当前模式切换正在显示 (先模式后档位)，冻结模式倒计时并关联模式底图，档位翻转完毕后恢复模式
     */
    fun triggerGearFlip(gear: String) {
        val web = gearCubeWebView ?: return
        if (!isWebPageLoaded) {
            Log.i(TAG, "小欧屏档位 3D 页面尚未加载完毕，排队等待加载后执行: $gear 档")
            pendingGearFlip = gear
            return
        }
        autoHideHandler.post {
            try {
                isGearActive = true
                val isAlreadyVisible = (web.visibility == View.VISIBLE && web.alpha > 0.5f)
                if (!isAlreadyVisible) {
                    web.alpha = 0f
                }
                web.visibility = View.VISIBLE
                if (driveModeWebView?.visibility == View.VISIBLE) {
                    driveModeWebView?.bringToFront()
                }
                gearCubeWebView?.bringToFront()

                // 若当前模式正在显示 (先模式后档位)，冻结模式倒计时并保持完全展现，防止在档位下方偷偷跑完退出
                if (isDriveModeActive) {
                    driveModeWebView?.evaluateJavascript("window.pauseDriveModeDwell && window.pauseDriveModeDwell()", null)
                }

                val snapshotBase64 = captureActiveViewSnapshot() ?: ""
                val modeParam = if (isDriveModeActive && !currentActiveDriveMode.isNullOrEmpty()) currentActiveDriveMode!! else ""
                web.evaluateJavascript("window.setOverlayMode(true)", null)
                web.evaluateJavascript("window.triggerCubeFlip('$gear', '$snapshotBase64', '$modeParam')", null)
                updateUnderlyingRenderState()
                Log.i(TAG, "小欧屏悬浮窗已触发 3D 档位向上翻转动效: $gear 档 (activeMode=$modeParam, isAlreadyVisible=$isAlreadyVisible)")
            } catch (t: Throwable) {
                Log.e(TAG, "触发档位翻转异常: ${t.message}", t)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initDriveModeWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onWipeStarted() {
                autoHideHandler.post {
                    Log.i(TAG, "🚗 驾驶模式擦除转场就绪，展现 WebView 并冻结原生画布")
                    isDriveModeActive = true
                    driveModeWebView?.alpha = 1f
                    driveModeWebView?.visibility = View.VISIBLE
                    updateUnderlyingRenderState()
                }
            }

            @JavascriptInterface
            fun onWipeOutComplete() {
                autoHideHandler.post {
                    Log.i(TAG, "🚗 驾驶模式擦除回退完成，隐藏 WebView 图层")
                    isDriveModeActive = false
                    currentActiveDriveMode = null
                    driveModeWebView?.visibility = View.GONE
                    updateUnderlyingRenderState()
                }
            }

            @JavascriptInterface
            fun onPageReady() {
                autoHideHandler.post {
                    isDriveModeWebPageLoaded = true
                    driveModeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
                    pendingDriveModeSwitch?.let { pending ->
                        pendingDriveModeSwitch = null
                        triggerDriveModeSwitch(pending)
                    }
                }
            }
        }, "DriveBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                isDriveModeWebPageLoaded = true
                driveModeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
                pendingDriveModeSwitch?.let { pending ->
                    pendingDriveModeSwitch = null
                    triggerDriveModeSwitch(pending)
                }
            }
        }

        webView.loadUrl("file:///android_asset/hmi/drive_mode_wipe.html")
    }

    /**
     * 触发小欧屏车辆模式切换由左至右擦除转场 (优先级 2 - 位于档位下方、歌词/天气上方)
     * - ECO / NORMAL / SPORT / RACE / CUSTOM
     * - 先档位后模式场景：模式在底层立即静默就绪为已完全展现态，档位 3D 退出时翻转露出的就是完整的模式页面，驻留 2 秒后光刃擦除退出
     * - 若已在显示中切换模式，新模式直接自左向右擦除覆盖旧模式，底层绝不闪黑
     */
    fun triggerDriveModeSwitch(mode: String) {
        if (driveModeWebView == null) return
        if (!isDriveModeWebPageLoaded) {
            Log.i(TAG, "小欧屏驾驶模式页面尚未加载完毕，排队等待加载后执行: $mode 模式")
            pendingDriveModeSwitch = mode
            return
        }
        autoHideHandler.post {
            // 如果档位当前正在显示 (优先级 1 独占顶层，先档位后模式场景)
            // 立即让下层的驾驶模式以“已经完成出现动画的模式页面”静默就绪，并在档位 3D 退出后直接呈现并驻留 2 秒后光刃退出
            if (isGearActive) {
                Log.i(TAG, "档位动效正在运行中 (优先级 1)，驾驶模式 $mode 立即在底层静默准备为完全展现态，等待档位 3D 退出后直接展现")
                prepareDriveModeUnderGear(mode)
                return@post
            }

            executeDriveModeSwitch(mode)
        }
    }

    private fun prepareDriveModeUnderGear(mode: String) {
        val web = driveModeWebView ?: return
        try {
            currentActiveDriveMode = mode
            isDriveModeActive = true
            web.alpha = 1f
            web.visibility = View.VISIBLE
            // 确保物理层级：driveModeWebView 位于档位下方
            driveModeWebView?.bringToFront()
            gearCubeWebView?.bringToFront()

            val snapshotBase64 = captureActiveViewSnapshot() ?: ""
            web.evaluateJavascript("window.setOverlayMode(true)", null)
            web.evaluateJavascript("window.prepareModeInstant && window.prepareModeInstant('$mode', '$snapshotBase64')", null)

            // 通知 gearCubeWebView 底层模式更新为 mode，确保 3D 翻转背面咬合透出真实模式
            gearCubeWebView?.evaluateJavascript("window.updateUnderlyingMode && window.updateUnderlyingMode('$mode')", null)

            updateUnderlyingRenderState()
            Log.i(TAG, "底层驾驶模式已静默完全就绪: $mode 模式，等待档位 3D 退出后直接展现")
        } catch (t: Throwable) {
            Log.e(TAG, "静默准备底层驾驶模式异常: ${t.message}", t)
        }
    }

    private fun executeDriveModeSwitch(mode: String) {
        val web = driveModeWebView ?: return
        try {
            currentActiveDriveMode = mode
            val isAlreadyVisible = (web.visibility == View.VISIBLE && web.alpha > 0.5f)
            if (!isAlreadyVisible) {
                web.alpha = 0f
            }
            web.visibility = View.VISIBLE
            val snapshotBase64 = captureActiveViewSnapshot() ?: ""
            web.evaluateJavascript("window.setOverlayMode(true)", null)
            web.evaluateJavascript("window.triggerDriveModeSwitch('$mode', '$snapshotBase64')", null)
            isDriveModeActive = true
            updateUnderlyingRenderState()
            Log.i(TAG, "小欧屏悬浮窗已触发驾驶模式左至右擦除转场: $mode 模式 (isAlreadyVisible=$isAlreadyVisible)")
        } catch (t: Throwable) {
            Log.e(TAG, "触发驾驶模式转场异常: ${t.message}", t)
        }
    }

    private fun captureActiveViewSnapshot(): String? {
        val bitmap = try {
            val bmp = Bitmap.createBitmap(1920, 384, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // 永远先绘制写实天气画布 (天体、天空、时钟)，提供 100% 饱和度的坚实底图，杜绝任何透明变黑
            weatherCanvasView?.drawStrict1920(c)
            // 若当前处于车载歌词页面，将歌词图层叠加在上
            if (currentPage == ActivePage.LYRICS) {
                lyricsContainerView?.drawStrict1920(c)
            }
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "截取屏幕快照异常: ${t.message}", t)
            null
        } ?: return null

        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "压缩屏幕快照异常: ${e.message}")
            null
        }
    }

    // ==================== 双页面丝滑平滑转场 ====================

    fun getCurrentPage(): ActivePage = currentPage

    /**
     * 平滑切入车载歌词页面
     * 过程：歌词页先可见并 500ms 淡入，天气画布淡出；动画完成后天气画布隐藏并彻底暂停 Choreographer 渲染
     */
    fun switchToLyricsPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.LYRICS && lyricsContainerView?.visibility == View.VISIBLE && lyricsContainerView?.alpha == 1f) return
        currentPage = ActivePage.LYRICS

        val lyrics = lyricsContainerView ?: return
        val weather = weatherCanvasView

        lyrics.resume()
        lyrics.visibility = View.VISIBLE
        if (driveModeWebView?.visibility == View.VISIBLE) {
            driveModeWebView?.bringToFront()
        }
        if (gearCubeWebView?.visibility == View.VISIBLE) {
            gearCubeWebView?.bringToFront()
        }

        if (smooth) {
            lyrics.animate().cancel()
            weather?.animate()?.cancel()

            lyrics.animate()
                .alpha(1f)
                .setDuration(500L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
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
                        }
                    }
                })
                ?.start()
        } else {
            lyrics.alpha = 1f
            weather?.visibility = View.GONE
            weather?.alpha = 0f
            weather?.pauseAnimation()
        }
    }

    /**
     * 平滑回退至默认时间天气页面
     * 过程：天气画布先可见并恢复 60fps 动效，800ms 缓缓淡入；歌词页缓缓淡出；动画完成后歌词页隐藏并休眠
     */
    fun switchToWeatherPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.WEATHER && weatherCanvasView?.visibility == View.VISIBLE && weatherCanvasView?.alpha == 1f) return
        currentPage = ActivePage.WEATHER

        val weather = weatherCanvasView ?: return
        val lyrics = lyricsContainerView

        weather.resumeAnimation()
        weather.visibility = View.VISIBLE
        if (driveModeWebView?.visibility == View.VISIBLE) {
            driveModeWebView?.bringToFront()
        }
        if (gearCubeWebView?.visibility == View.VISIBLE) {
            gearCubeWebView?.bringToFront()
        }

        if (smooth) {
            weather.animate().cancel()
            lyrics?.animate()?.cancel()

            weather.animate()
                .alpha(1f)
                .setDuration(800L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
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
                        }
                    }
                })
                ?.start()
        } else {
            weather.alpha = 1f
            lyrics?.visibility = View.GONE
            lyrics?.alpha = 0f
            lyrics?.pause()
        }
    }

    // ==================== 歌词数据透传 ====================

    fun setLyricsTrackInfo(title: String, artist: String) {
        lyricsContainerView?.setTrackInfo(title, artist)
    }

    fun setLyricsCoverUrl(url: String?) {
        lyricsContainerView?.setCoverUrl(url)
    }

    fun setLyricsLines(lines: List<LyricLine>) {
        lyricsContainerView?.setLyrics(lines)
    }

    fun updateLyricsProgress(positionMs: Long) {
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

    // ==================== 天气画布五行居中歌词控制 ====================

    fun setCanvasLyrics(lines: List<LyricLine>) {
        pendingCanvasLyricLines = lines
        weatherCanvasView?.setLyrics(lines)
    }

    fun updateCanvasLyricsProgress(positionMs: Long) {
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

    fun dismiss() {
        autoHideHandler.removeCallbacks(hideCloseRunnable)

        overlayRootView?.let { root ->
            windowManager?.let { wm ->
                try {
                    wm.removeView(root)
                } catch (ignored: Throwable) {}
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

        isAttached = false
        isWebPageLoaded = false
        pendingGearFlip = null
        isDriveModeWebPageLoaded = false
        pendingDriveModeSwitch = null
        isGearActive = false
        isDriveModeActive = false
        currentActiveDriveMode = null
        driveModeWebView = null
        Log.i(TAG, "小欧副屏宿主窗口已销毁")
    }

    // 内部 Presentation 实现 (Type 2037)
    private class InternalPresentation(
        context: Context,
        display: Display
    ) : Presentation(context, display) {

        var canvasView: CarWeatherCanvasView? = null
        var lyricsView: LyricsNormalizedContainerView? = null
        var gearView: WebView? = null
        var driveModeView: WebView? = null
        var closeContainer: View? = null
        private var onTouchCallback: (() -> Unit)? = null
        private var onCloseCallback: (() -> Unit)? = null

        fun setOnTouchCallback(cb: () -> Unit) { onTouchCallback = cb }
        fun setOnCloseCallback(cb: () -> Unit) { onCloseCallback = cb }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val contentView = layoutInflater.inflate(R.layout.presentation_weather_overlay, null)

            val root = TouchInterceptFrameLayout(context).apply {
                addView(contentView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                onTouchDown = { onTouchCallback?.invoke() }
            }
            setContentView(root)

            canvasView = contentView.findViewById(R.id.weatherCanvasView)
            lyricsView = contentView.findViewById(R.id.lyricsContainerView)
            gearView = contentView.findViewById(R.id.gearCubeWebView)
            driveModeView = contentView.findViewById(R.id.driveModeWebView)
            closeContainer = contentView.findViewById(R.id.closeButtonContainer)
            contentView.findViewById<View>(R.id.tvCloseButton).setOnClickListener {
                onCloseCallback?.invoke()
            }

            canvasView?.alpha = 1f
            canvasView?.visibility = View.VISIBLE
            canvasView?.resumeAnimation()

            lyricsView?.alpha = 0f
            lyricsView?.visibility = View.GONE
            lyricsView?.pause()
            lyricsView?.xiaoOuAvoidanceEnabled = com.carlyrics.util.PrefsManager.isXiaoOuAvoidanceEnabled()
        }
    }

    // 触摸拦截容器，轻触任意位置唤出关闭按钮
    private class TouchInterceptFrameLayout(context: Context) : FrameLayout(context) {
        var onTouchDown: (() -> Unit)? = null

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                onTouchDown?.invoke()
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    companion object {
        private const val TAG = "WeatherOverlayWindow"
    }
}

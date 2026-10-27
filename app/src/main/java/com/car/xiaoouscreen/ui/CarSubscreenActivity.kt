package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneMapper
import com.car.xiaoouscreen.domain.WeatherState
import com.car.xiaoouscreen.location.CarGpsManager
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.carlyrics.CarLyricsApplication
import com.carlyrics.display.LyricsNormalizedContainerView
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.util.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车载副屏全屏独立预览 Activity (100% 纯原生架构)：
 *
 * 画面层级与转场规范：
 * 1. 档位 3D 棱柱翻转 (最高优先级 1，600ms 入场 -> 2.0s 驻留 -> 600ms 翻出)
 * 2. 驾驶模式光刃擦除 (优先级 2，600ms 擦除入场 -> 2.0s 驻留 -> 600ms 擦除退场)
 * 3. 车载歌词全屏 (优先级 3，有音乐播放时丝滑展示)
 * 4. 时间天气多图层深空画布 (基底 4，无音乐或暂停 5s 后居中展示大时钟与动态天空)
 *
 * 安全规范：
 * - 首次进入软件坚决不自动弹出任何动画，静默就绪当前档位与模式；
 * - 档位/模式退场完毕后自动隐藏，露出的始终是当前处于活跃态的时间天气或歌词主页面；
 * - 彻底废除无底层画面的空白测试界面。
 */
class CarSubscreenActivity : AppCompatActivity(),
    CarGearManager.GearListener,
    CarGearManager.DriveModeListener {

    companion object {
        private const val TAG = "CarSubscreenActivity"
        const val ACTION_GEAR_SWITCH = "com.car.gear.SWITCH"
        const val ACTION_SWITCH_GEAR = "com.car.xiaoouscreen.SWITCH_GEAR"
        const val ACTION_SWITCH_DRIVE_MODE = "com.car.xiaoouscreen.SWITCH_DRIVE_MODE"
        const val EXTRA_GEAR = "gear"
        const val EXTRA_MODE = "mode"
    }

    enum class ActivePage {
        WEATHER,
        LYRICS
    }

    private lateinit var weatherCanvasView: CarWeatherCanvasView
    private lateinit var lyricsContainerView: LyricsNormalizedContainerView
    private lateinit var driveModeView: DriveModeWipeView
    private lateinit var gearCubeView: GearCubeFlipView
    private lateinit var bottomLeftGearHudView: CarBottomLeftGearHudView
    private lateinit var closeButtonContainer: View
    private lateinit var viewModel: WeatherClockViewModel

    private var currentPage: ActivePage = ActivePage.WEATHER
    private var isGearActive = false
    private var isDriveModeActive = false
    private var currentActiveDriveMode: String? = null

    // 首次启动防自动弹出保护
    private var isFirstGearEvent = true
    private var isFirstDriveModeEvent = true
    private var lastReportedGear: String? = null
    private var lastReportedDriveMode: String? = null

    // 歌词业务
    private val lyricsApi = LyricsApiManager()
    private var lastTrackTitle = ""
    private var lastTrackArtist = ""
    private var lastCoverUrl: String? = null
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var lastKnownPosition: Long = 0L
    private var lastPositionNanos: Long = 0L
    private var isMusicPlaying = false
    private var lyricsProgressJob: Job? = null
    private var lyricsFetchJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideCloseRunnable = Runnable {
        closeButtonContainer.visibility = View.GONE
    }
    private val fallbackToWeatherRunnable = Runnable {
        Log.i(TAG, "音乐暂停已达 5 秒，平滑回退至时间天气大时钟界面")
        switchToWeatherPage(smooth = true)
    }

    private var wasInLyricsBeforeCall: Boolean = false
    private val callListener = object : CarCallManager.CallListener {
        override fun onCallChanged(callInfo: CarCallManager.CallInfo) {
            runOnUiThread {
                if (callInfo.isCallActive) {
                    if (currentPage == ActivePage.LYRICS) {
                        wasInLyricsBeforeCall = true
                        switchToWeatherPage(smooth = true)
                    }
                } else if (callInfo.state == CarCallManager.CallState.IDLE) {
                    if (wasInLyricsBeforeCall) {
                        if (isMusicPlaying) {
                            switchToLyricsPage(smooth = true)
                        }
                        wasInLyricsBeforeCall = false
                    }
                }
                weatherCanvasView.setCallInfo(callInfo)
            }
        }
    }

    private val simulationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "📡 副屏预览收到外部/ADB调试广播: $action")
            when (action) {
                ACTION_GEAR_SWITCH, ACTION_SWITCH_GEAR -> {
                    val gear = intent.getStringExtra(EXTRA_GEAR) ?: "P"
                    isFirstGearEvent = false
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateGear(gear)
                }
                ACTION_SWITCH_DRIVE_MODE -> {
                    val mode = intent.getStringExtra(EXTRA_MODE) ?: "SPORT"
                    isFirstDriveModeEvent = false
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateDriveMode(mode)
                }
                WeatherOverlayService.ACTION_SIMULATE_CUSTOM -> {
                    val timeIdx = intent.getIntExtra(WeatherOverlayService.EXTRA_TIME_OF_DAY, 1)
                    val weatherIdx = intent.getIntExtra(WeatherOverlayService.EXTRA_WEATHER_STATE, 0)
                    val windSpeed = intent.getFloatExtra(WeatherOverlayService.EXTRA_WIND_SPEED, 12f)
                    val isSleet = intent.getBooleanExtra(WeatherOverlayService.EXTRA_IS_SLEET, false)
                    val timeOfDay = TimeOfDay.values().getOrElse(timeIdx) { TimeOfDay.DAY }
                    val targetWeatherState = if (isSleet) WeatherState.SLEET else WeatherState.values().getOrElse(weatherIdx) { WeatherState.CLEAR }
                    val scene = WeatherSceneMapper.mapToScene(
                        state = targetWeatherState,
                        timeOfDay = timeOfDay,
                        intensity = if (targetWeatherState == WeatherState.STORM) Intensity.HEAVY else Intensity.LIGHT,
                        windSpeed = windSpeed,
                        temperature = if (targetWeatherState == WeatherState.SNOW) -2f
                                      else if (targetWeatherState == WeatherState.SLEET) 1.5f
                                      else 22f
                    )
                    weatherCanvasView.updateScene(scene)
                }
                WeatherOverlayService.ACTION_CALL_EVENT -> {
                    val typeStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_TYPE) ?: "normal"
                    val stateStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_STATE) ?: "ringing"
                    val name = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NAME) ?: "张总"
                    val number = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NUMBER) ?: "13800009527"
                    val type = if (typeStr.equals("wechat", ignoreCase = true)) CarCallManager.CallType.WECHAT else CarCallManager.CallType.NORMAL
                    val state = when (stateStr.lowercase()) {
                        "ringing", "incoming" -> CarCallManager.CallState.RINGING
                        "dialing", "outgoing" -> CarCallManager.CallState.DIALING
                        "active", "connected" -> CarCallManager.CallState.ACTIVE
                        "hangup", "terminated" -> CarCallManager.CallState.TERMINATED
                        else -> CarCallManager.CallState.IDLE
                    }
                    CarCallManager.getInstance(this@CarSubscreenActivity).simulateCall(type, state, name, number)
                }
                WeatherOverlayService.ACTION_UPDATE_LIQUID_GLASS -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_LIQUID_GLASS_ENABLED, PrefsManager.isLiquidGlassClockEnabled())
                    Log.i(TAG, "📡 副屏预览收到液态玻璃时钟状态变更: enabled=$enabled")
                    weatherCanvasView.setLiquidGlassEnabled(enabled, smooth = true)
                }
                WeatherOverlayService.ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 副屏预览收到左下角档位HUD状态变更: enabled=$enabled")
                    bottomLeftGearHudView.setHudEnabled(enabled, animate = true)
                    if (enabled) {
                        bottomLeftGearHudView.bringToFront()
                    }
                }
                WeatherOverlayService.ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 副屏预览收到小欧形象避让状态变更: enabled=$enabled")
                    weatherCanvasView.setXiaoOuAvoidance(enabled)
                    lyricsContainerView.xiaoOuAvoidanceEnabled = enabled
                }
                WeatherOverlayService.ACTION_UPDATE_LYRICS_SETTINGS -> {
                    Log.i(TAG, "📡 副屏预览收到歌词设置/预设变更广播，立即同步运行时状态")
                    val shouldAvoid = PrefsManager.isXiaoOuAvoidanceEnabled()
                    lyricsContainerView.xiaoOuAvoidanceEnabled = shouldAvoid
                    weatherCanvasView.setXiaoOuAvoidance(shouldAvoid)
                    lyricsContainerView.reloadSkin(PrefsManager.getSkinType())
                    lyricsContainerView.getSkin()?.fontSizeSp = PrefsManager.getFontSizeSp()
                    lyricsContainerView.getSkin()?.splitRatio = PrefsManager.getSplitRatio()
                    lyricsContainerView.getSkin()?.lyricsTextAlignment = PrefsManager.getLyricsAlignment()
                    lyricsContainerView.getSkin()?.marqueeEnabled = PrefsManager.isMarqueeEnabled()
                    lyricsContainerView.getSkin()?.scaleFactor = PrefsManager.getDpiScale()
                    lyricsContainerView.getSkin()?.infoFontScale = PrefsManager.getInfoFontScale()
                    lyricsContainerView.getSkin()?.lyricsOffsetMs = PrefsManager.lyricsOffsetMs
                }
                WeatherOverlayService.ACTION_SIMULATE_MUSIC -> {
                    val playing = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MUSIC_PLAYING, true)
                    val title = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_TITLE) ?: "See You Again (feat. Charlie Puth)"
                    val artist = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_ARTIST) ?: "Wiz Khalifa / Charlie Puth"
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = 200000L))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        setContentView(R.layout.activity_car_subscreen)
        weatherCanvasView = findViewById(R.id.carWeatherCanvasView)
        lyricsContainerView = findViewById(R.id.lyricsContainerView)
        driveModeView = findViewById(R.id.driveModeView)
        gearCubeView = findViewById(R.id.gearCubeView)
        bottomLeftGearHudView = findViewById(R.id.bottomLeftGearHudView)
        closeButtonContainer = findViewById(R.id.closeButtonContainer)

        findViewById<View>(R.id.tvCloseButton).setOnClickListener {
            finish()
        }

        setupNativeAnimationViews()
        startMusicObservation()

        // 默认显示时间天气大时钟主屏
        weatherCanvasView.alpha = 1f
        weatherCanvasView.visibility = View.VISIBLE
        weatherCanvasView.resumeAnimation()
        weatherCanvasView.setLiquidGlassEnabled(PrefsManager.isLiquidGlassClockEnabled(), smooth = false)

        // 初始化左下角常驻微型档位 HUD (置顶)
        bottomLeftGearHudView.backgroundProvider = { canvas, _ ->
            weatherCanvasView.drawWeatherBackgroundOnly(canvas)
        }
        bottomLeftGearHudView.weatherLeftProvider = {
            weatherCanvasView.weatherHudLeftX
        }
        weatherCanvasView.onSceneRenderUpdated = {
            bottomLeftGearHudView.invalidateBackground()
        }
        bottomLeftGearHudView.setHudEnabled(PrefsManager.isBottomLeftGearHudEnabled(), animate = false)
        bottomLeftGearHudView.bringToFront()

        val shouldAvoid = PrefsManager.isXiaoOuAvoidanceEnabled()
        lyricsContainerView.alpha = 0f
        lyricsContainerView.visibility = View.GONE
        lyricsContainerView.pause()
        lyricsContainerView.xiaoOuAvoidanceEnabled = shouldAvoid
        weatherCanvasView.setXiaoOuAvoidance(shouldAvoid)

        // 注册切档与驾驶模式监听
        val carManager = CarGearManager.getInstance(this).apply {
            init()
            registerListener(this@CarSubscreenActivity)
            registerDriveModeListener(this@CarSubscreenActivity)
        }
        lastReportedGear = carManager.getCurrentGear()
        lastReportedDriveMode = carManager.getCurrentDriveMode()
        lastReportedGear?.let { bottomLeftGearHudView.setGear(it, animate = false) }

        // 注册原厂与模拟通话状态监听
        CarCallManager.getInstance(this).apply {
            startListening()
            addListener(callListener)
        }

        // 注册 ADB 模拟广播
        val filter = IntentFilter().apply {
            addAction(ACTION_GEAR_SWITCH)
            addAction(ACTION_SWITCH_GEAR)
            addAction(ACTION_SWITCH_DRIVE_MODE)
            addAction(WeatherOverlayService.ACTION_SIMULATE_CUSTOM)
            addAction(WeatherOverlayService.ACTION_CALL_EVENT)
            addAction(WeatherOverlayService.ACTION_UPDATE_LIQUID_GLASS)
            addAction(WeatherOverlayService.ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD)
            addAction(WeatherOverlayService.ACTION_UPDATE_XIAOOU_AVATAR)
            addAction(WeatherOverlayService.ACTION_UPDATE_LYRICS_SETTINGS)
            addAction(WeatherOverlayService.ACTION_SIMULATE_MUSIC)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simulationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(simulationReceiver, filter)
        }

        // 组装天气与 GPS 依赖
        val repository = OpenMeteoRepository(applicationContext)
        val gpsManager = CarGpsManager(applicationContext)
        viewModel = WeatherClockViewModel(repository, gpsManager)

        // 响应式收集天气场景状态并驱动 View 渲染
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneConfig.collect { scene ->
                    weatherCanvasView.updateScene(scene)
                }
            }
        }

        viewModel.start()
    }

    private fun setupNativeAnimationViews() {
        gearCubeView.onFlipStartedListener = {
            isGearActive = true
            updateUnderlyingRenderState()
        }
        gearCubeView.onFlipCompleteListener = {
            isGearActive = false
            updateUnderlyingRenderState()
        }

        driveModeView.onWipeStartedListener = {
            isDriveModeActive = true
            updateUnderlyingRenderState()
        }
        driveModeView.onWipeCompleteListener = {
            isDriveModeActive = false
            currentActiveDriveMode = null
            updateUnderlyingRenderState()
        }
    }

    private fun updateUnderlyingRenderState() {
        // 车载主界面 (写实多图层天气时钟 / 全屏歌词) 持续保持 60FPS 实时渲染，绝不强制挂起！
        // 模式动画以原生硬件加速透明覆盖在主页面上，在出现或消失半途中，驾驶员均可实时看到主页面的物理天体动态与走字，彻底消除跳帧与割裂感。
        if (currentPage == ActivePage.WEATHER) {
            weatherCanvasView.resumeAnimation()
        } else {
            lyricsContainerView.resume()
        }
        bottomLeftGearHudView.bringToFront()
    }

    // ==================== 档位与驾驶模式监听 ====================

    override fun onGearChanged(gear: String, rawValue: Int) {
        // 关键：首次进入软件仅记录初始状态，坚决不自动触发翻转动画！
        if (isFirstGearEvent) {
            isFirstGearEvent = false
            lastReportedGear = gear
            bottomLeftGearHudView.setGear(gear, animate = false)
            bottomLeftGearHudView.bringToFront()
            Log.i(TAG, "🚗 副屏初始档位静默就绪: $gear (首次进入不触发动画)")
            return
        }
        if (lastReportedGear == gear) return
        lastReportedGear = gear

        bottomLeftGearHudView.setGear(gear, animate = true)
        bottomLeftGearHudView.bringToFront()
        Log.i(TAG, "🚗 监听到真实档位变动: $gear (raw=$rawValue)，立即执行 3D 向上翻转")
        triggerGearFlip(gear)
    }

    override fun onDriveModeChanged(mode: String, rawValue: Int) {
        if (isFirstDriveModeEvent) {
            isFirstDriveModeEvent = false
            lastReportedDriveMode = mode
            Log.i(TAG, "🏎️ 副屏初始驾驶模式静默就绪: $mode (首次进入不触发动画)")
            return
        }
        if (lastReportedDriveMode == mode) return
        lastReportedDriveMode = mode

        Log.i(TAG, "🏎️ 监听到真实驾驶模式变动: $mode (raw=$rawValue)，立即执行光刃擦除")
        triggerDriveModeSwitch(mode)
    }

    fun triggerGearFlip(gear: String) {
        mainHandler.post {
            try {
                // 用户规范：档位直接打断并关闭模式页面，档位退出后直接保持主界面，绝不复活展示模式
                if (isDriveModeActive || driveModeView.visibility == View.VISIBLE) {
                    isDriveModeActive = false
                    currentActiveDriveMode = null
                    driveModeView.dismiss()
                }
                isGearActive = true
                gearCubeView.bringToFront()
                bottomLeftGearHudView.setGear(gear, animate = true)
                bottomLeftGearHudView.bringToFront()

                gearCubeView.triggerGearFlip(gear) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
            } catch (t: Throwable) {
                Log.e(TAG, "触发档位翻转异常: ${t.message}", t)
            }
        }
    }

    fun triggerDriveModeSwitch(mode: String) {
        mainHandler.post {
            try {
                // 用户规范：模式直接打断并关闭档位页面，模式退出后直接保持主界面，绝不复活展示档位
                if (isGearActive || gearCubeView.visibility == View.VISIBLE) {
                    isGearActive = false
                    gearCubeView.dismiss()
                }
                currentActiveDriveMode = mode
                isDriveModeActive = true
                driveModeView.bringToFront()

                driveModeView.triggerDriveModeSwitch(mode) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
            } catch (t: Throwable) {
                Log.e(TAG, "触发驾驶模式擦除异常: ${t.message}", t)
            }
        }
    }

    private fun drawActiveUnderlyingView(canvas: Canvas) {
        // 先绘制时间气象动态背景画布
        weatherCanvasView.drawStrict1920(canvas)
        // 若当前处于车载歌词页面，将歌词图层叠加在上
        if (currentPage == ActivePage.LYRICS) {
            lyricsContainerView.drawStrict1920(canvas)
        }
    }

    // ==================== 歌词监听与双页面丝滑切换 ====================

    private fun startMusicObservation() {
        lifecycleScope.launch {
            CarLyricsApplication.recognitionEngine.currentTrack.collectLatest { track ->
                onTrackStateChanged(track)
            }
        }
    }

    private fun onTrackStateChanged(track: CurrentTrack?) {
        if (track == null || track.title.isBlank()) {
            isMusicPlaying = false
            mainHandler.removeCallbacks(fallbackToWeatherRunnable)
            switchToWeatherPage(smooth = true)
            return
        }

        val title = track.title.trim()
        val artist = track.artist.trim()
        val playing = track.isPlaying

        isMusicPlaying = playing
        lastKnownPosition = track.position
        lastPositionNanos = System.nanoTime()

        if (title.isNotEmpty() && (title != lastTrackTitle || artist != lastTrackArtist || currentLyricLines.isEmpty())) {
            lastTrackTitle = title
            lastTrackArtist = artist
            fetchLyricsAndCover(title, artist)
        } else {
            lyricsContainerView.setTrackInfo(lastTrackTitle, lastTrackArtist)
            if (lastCoverUrl != null) lyricsContainerView.setCoverUrl(lastCoverUrl)
            if (currentLyricLines.isNotEmpty()) {
                lyricsContainerView.setLyrics(currentLyricLines)
            }
        }

        if (playing) {
            mainHandler.removeCallbacks(fallbackToWeatherRunnable)
            switchToLyricsPage(smooth = true)
            startLyricsProgressTicker()
        } else {
            mainHandler.postDelayed(fallbackToWeatherRunnable, 5000L)
        }
    }

    private fun fetchLyricsAndCover(title: String, artist: String) {
        lyricsFetchJob?.cancel()
        lyricsApi.cancelAllRequests()
        lyricsContainerView.setTrackInfo(title, artist)

        lyricsFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val coverUrl = try {
                lyricsApi.fetchCover(title, artist)
            } catch (_: Exception) { null }

            lastCoverUrl = coverUrl
            withContext(Dispatchers.Main) {
                lyricsContainerView.setCoverUrl(coverUrl)
            }

            val lyricResult = try {
                lyricsApi.fetchLyrics(title, artist)
            } catch (_: Exception) { null }

            val lines = lyricResult?.getOrNull()?.lines ?: emptyList()
            currentLyricLines = lines

            withContext(Dispatchers.Main) {
                lyricsContainerView.setLyrics(lines)
            }
        }
    }

    private fun startLyricsProgressTicker() {
        lyricsProgressJob?.cancel()
        lyricsProgressJob = lifecycleScope.launch {
            while (isActive && isMusicPlaying) {
                if (currentPage != ActivePage.LYRICS) {
                    delay(200L)
                    continue
                }
                val now = System.nanoTime()
                val deltaMs = ((now - lastPositionNanos) / 1_000_000L).coerceAtLeast(0L)
                val currentPos = lastKnownPosition + deltaMs
                lyricsContainerView.updateProgress(currentPos)
                delay(16L) // 60FPS 逐字走时
            }
        }
    }

    fun switchToLyricsPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.LYRICS && lyricsContainerView.visibility == View.VISIBLE && lyricsContainerView.alpha == 1f) return
        currentPage = ActivePage.LYRICS

        lyricsContainerView.resume()
        lyricsContainerView.visibility = View.VISIBLE
        if (driveModeView.visibility == View.VISIBLE) driveModeView.bringToFront()
        if (gearCubeView.visibility == View.VISIBLE) gearCubeView.bringToFront()

        if (smooth) {
            lyricsContainerView.animate().cancel()
            weatherCanvasView.animate().cancel()

            lyricsContainerView.animate()
                .alpha(1f)
                .setDuration(500L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
                .start()

            weatherCanvasView.animate()
                .alpha(0f)
                .setDuration(500L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.LYRICS) {
                            weatherCanvasView.visibility = View.GONE
                            weatherCanvasView.pauseAnimation()
                        }
                    }
                })
                .start()
        } else {
            lyricsContainerView.alpha = 1f
            weatherCanvasView.visibility = View.GONE
            weatherCanvasView.alpha = 0f
            weatherCanvasView.pauseAnimation()
        }
    }

    fun switchToWeatherPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.WEATHER && weatherCanvasView.visibility == View.VISIBLE && weatherCanvasView.alpha == 1f) return
        currentPage = ActivePage.WEATHER

        weatherCanvasView.resumeAnimation()
        weatherCanvasView.visibility = View.VISIBLE
        if (driveModeView.visibility == View.VISIBLE) driveModeView.bringToFront()
        if (gearCubeView.visibility == View.VISIBLE) gearCubeView.bringToFront()

        if (smooth) {
            weatherCanvasView.animate().cancel()
            lyricsContainerView.animate().cancel()

            weatherCanvasView.animate()
                .alpha(1f)
                .setDuration(800L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
                .start()

            lyricsContainerView.animate()
                .alpha(0f)
                .setDuration(800L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.WEATHER) {
                            lyricsContainerView.visibility = View.GONE
                            lyricsContainerView.pause()
                        }
                    }
                })
                .start()
        } else {
            weatherCanvasView.alpha = 1f
            lyricsContainerView.visibility = View.GONE
            lyricsContainerView.alpha = 0f
            lyricsContainerView.pause()
        }
    }

    // ==================== 触控与按键模拟 ====================

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            showCloseButtonWithTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showCloseButtonWithTimer() {
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        closeButtonContainer.visibility = View.VISIBLE
        autoHideHandler.postDelayed(hideCloseRunnable, 5000L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val targetGear = when (keyCode) {
            KeyEvent.KEYCODE_P -> "P"
            KeyEvent.KEYCODE_R -> "R"
            KeyEvent.KEYCODE_N -> "N"
            KeyEvent.KEYCODE_D -> "D"
            else -> null
        }
        if (targetGear != null) {
            isFirstGearEvent = false
            lastReportedGear = null
            triggerGearFlip(targetGear)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        weatherCanvasView.resumeAnimation()
        if (currentPage == ActivePage.LYRICS) {
            lyricsContainerView.resume()
            if (isMusicPlaying) {
                startLyricsProgressTicker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        weatherCanvasView.pauseAnimation()
        lyricsContainerView.pause()
        lyricsProgressJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(simulationReceiver)
        } catch (_: Exception) {}
        CarGearManager.getInstance(this).unregisterListener(this)
        CarGearManager.getInstance(this).unregisterDriveModeListener(this)
        CarCallManager.getInstance(this).removeListener(callListener)
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        mainHandler.removeCallbacks(fallbackToWeatherRunnable)
        bottomLeftGearHudView.backgroundProvider = null
        bottomLeftGearHudView.weatherLeftProvider = null
        weatherCanvasView.onSceneRenderUpdated = null
        lyricsProgressJob?.cancel()
        lyricsFetchJob?.cancel()
        viewModel.stop()
    }
}

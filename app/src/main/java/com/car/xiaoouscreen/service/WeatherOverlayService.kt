package com.car.xiaoouscreen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.data.QWeatherRepository
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherFusionEngine
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherSceneMapper
import com.car.xiaoouscreen.domain.WeatherState
import com.car.xiaoouscreen.location.CarGpsManager
import com.car.xiaoouscreen.ui.MainActivity
import com.carlyrics.CarLyricsApplication
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.skin.SkinType
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.PrivacyUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车载副屏投屏前台服务：
 * 1. 负责管理小欧屏 (AR_XIAOOU / 1920x384) Presentation / Overlay 唯一次级窗口生命周期
 * 2. 绑定 B561 ThreeScreen 中间件调度 (OSHAN_FITNESS 槽位)
 * 3. 驱动 Open-Meteo 真实气象轮询与昼夜监测
 * 4. 驱动车载歌词与天气时钟双态无缝切换
 * 5. 驱动实车/模拟档位 3D 正方体向上翻转动效
 */
class WeatherOverlayService : Service(), ThreeScreenClient.Callback {

    interface ServiceCallback {
        fun onOverlayStateChanged(isShowing: Boolean, mode: WeatherOverlayWindow.Mode?, displayDesc: String)
        fun onSceneChanged(scene: WeatherSceneConfig)
        fun onTrackChanged(track: CurrentTrack?)
        fun onGearChanged(gear: String)
        fun onDriveModeChanged(mode: String)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayWindow: WeatherOverlayWindow? = null
    private var threeScreenClient: ThreeScreenClient? = null
    private var fusionEngine: WeatherFusionEngine? = null
    private var fusionJob: Job? = null
    private var gpsManager: CarGpsManager? = null

    private var currentMode: WeatherOverlayWindow.Mode = WeatherOverlayWindow.Mode.TOP
    private var currentScene: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    private var isSimulating = false

    // 歌词业务与 5s 延时回退
    private val lyricsApi = LyricsApiManager()
    private var lastTrackTitle = ""
    private var lastTrackArtist = ""
    private var inFlightFetchTrackKey: String? = null
    private var lastFetchedTrackKey: String? = null
    private var lastCoverUrl: String? = null
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var lastKnownPosition: Long = 0L
    private var lastPositionNanos: Long = 0L
    private var isMusicPlaying = false
    private var isFallbackPending = false
    private val pauseDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingPausedTrack: CurrentTrack? = null

    private val pauseDebounceRunnable = Runnable {
        val pausedTrack = pendingPausedTrack ?: return@Runnable
        pendingPausedTrack = null
        if (isMusicPlaying) return@Runnable

        AppLogger.i(TAG, "⏸️ 音乐暂停已确认为真实暂停 (350ms防抖通过)，执行定格与回退大时钟倒计时 (pos=${pausedTrack.position})")

        // 1. 彻底销毁 16ms 歌词高频推演协程，杜绝暂停后时间戳继续递增推演
        lyricsProgressJob?.cancel()
        lyricsProgressJob = null

        // 2. 冻结当前时间戳与位置，将静止位置立即定格至 View
        lastKnownPosition = pausedTrack.position
        lastPositionNanos = 0L
        overlayWindow?.updateLyricsProgress(pausedTrack.position)
        overlayWindow?.updateCanvasLyricsProgress(pausedTrack.position)

        // 3. 顶栏音乐胶囊状态更新为非播放
        overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = false)

        // 4. 音乐暂停防抖 5s 延时回退
        if (isShowing()) {
            if (!isFallbackPending) {
                isFallbackPending = true
                fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                fallbackHandler.postDelayed(fallbackToWeatherRunnable, 5000L)
            }
        }
    }

    private var lyricsProgressJob: Job? = null
    private var lyricsFetchJob: Job? = null

    private val fallbackHandler = Handler(Looper.getMainLooper())
    private val fallbackToWeatherRunnable = Runnable {
        AppLogger.i(TAG, "音乐暂停已达 5 秒，平滑回退至时间天气大时钟界面")
        isFallbackPending = false
        wasMusicPlaying = false
        lyricsProgressJob?.cancel()
        lyricsProgressJob = null
        overlayWindow?.setTopMusicTrackInfo("", "", isPlaying = false)
        if (PrefsManager.isStandaloneLyricsEnabled()) {
            overlayWindow?.switchToWeatherPage(smooth = true)
        } else {
            overlayWindow?.setCanvasLyricsActive(false, smooth = true)
        }
    }

    // 辅助方案 B 与起播防抢置顶重登调度器
    private var wasMusicPlaying = false
    private val reassertTopHandler = Handler(Looper.getMainLooper())
    private val reassertTopRunnable = Runnable {
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            if (!CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
                AppLogger.i(TAG, "🛡️ [辅助方案B·防抢置顶] 执行小欧副屏无缝重登置顶")
                val ok = overlayWindow?.reassertTop() ?: false
                if (ok) {
                    if (lastTrackTitle.isNotEmpty()) {
                        overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
                        if (isMusicPlaying) {
                            overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = true)
                        }
                        if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
                        if (currentLyricLines.isNotEmpty()) {
                            overlayWindow?.setLyricsLines(currentLyricLines)
                            overlayWindow?.setCanvasLyrics(currentLyricLines)
                            overlayWindow?.updateLyricsProgress(lastKnownPosition)
                        }
                    }
                    updateLyricsRuntimeSettings()
                    evaluateLyricsStateTransition(smooth = false)
                }
            }
        }
    }

    private fun scheduleReassertTop(delayMs: Long) {
        reassertTopHandler.removeCallbacks(reassertTopRunnable)
        reassertTopHandler.postDelayed(reassertTopRunnable, delayMs)
    }

    private fun handleSubscreenFocusLost() {
        if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) return
        if (CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
            AppLogger.d(TAG, "当前处于车载通话活跃状态，允许系统浮层展示，跳过失焦重登")
            return
        }
        AppLogger.i(TAG, "🛡️ [辅助方案B] 检测到小欧副屏窗口失焦 (被系统窗口覆盖)，预约 450ms 后执行置顶重登守护...")
        scheduleReassertTop(450L)
    }

    private var isFirstGearEvent = true
    private var isFirstDriveModeEvent = true
    private var lastReportedGear: String? = null
    private var lastReportedDriveMode: String? = null
    private var serviceStartTimeMs: Long = 0L

    private val gearListener = object : CarGearManager.GearListener {
        override fun onGearChanged(gear: String, rawValue: Int) {
            val isStartupGracePeriod = (SystemClock.elapsedRealtime() - serviceStartTimeMs < 6000L)
            // 过滤首次启动/注册时及开机前 6 秒内的初始回调，仅同步状态，杜绝启动软件时自动弹出档位翻转动画！
            if (isFirstGearEvent || isStartupGracePeriod) {
                isFirstGearEvent = false
                lastReportedGear = gear
                serviceCallback?.onGearChanged(gear)
                mainHandler.post {
                    overlayWindow?.syncGearSilently(gear)
                }
                AppLogger.i(TAG, "🚗 初始档位静默就绪: $gear (免扰保护期不触发 3D 翻转动画，已静默同步至左下角微型 HUD)")
                return
            }
            if (lastReportedGear == gear) {
                // 确保左下角 HUD 状态保持与实车一致 (防止窗口重建时 HUD 滞留初始默认状态)
                mainHandler.post {
                    overlayWindow?.syncGearSilently(gear)
                }
                return
            }
            lastReportedGear = gear
            AppLogger.i(TAG, "🚗 监听到实际档位变动: $gear (raw=$rawValue)，立即驱动小欧屏悬浮窗执行 3D 翻转")
            if (!PrefsManager.getOverlayVisible()) {
                return
            }
            mainHandler.post {
                overlayWindow?.triggerGearFlip(gear)
                serviceCallback?.onGearChanged(gear)
            }
        }
    }

    private val driveModeListener = object : CarGearManager.DriveModeListener {
        override fun onDriveModeChanged(mode: String, rawValue: Int) {
            // 车规级冷启动保护期延长至 15 秒，确保整车动力域与座舱各 ECU 节点上线握手完毕，彻底杜绝点火误弹动画！
            val isStartupGracePeriod = (SystemClock.elapsedRealtime() - serviceStartTimeMs < 15000L)
            if (isFirstDriveModeEvent || isStartupGracePeriod) {
                isFirstDriveModeEvent = false
                lastReportedDriveMode = mode
                serviceCallback?.onDriveModeChanged(mode)
                AppLogger.i(TAG, "🏎️ 初始驾驶模式静默就绪: $mode (免扰保护期不触发动画)")
                return
            }
            if (lastReportedDriveMode == mode) {
                return
            }
            lastReportedDriveMode = mode
            AppLogger.i(TAG, "🏎️ 监听到实际驾驶模式变动: $mode (raw=$rawValue)，立即驱动小欧屏悬浮窗执行左至右擦除转场")
            if (!PrefsManager.getOverlayVisible()) {
                return
            }
            mainHandler.post {
                overlayWindow?.triggerDriveModeSwitch(mode)
                serviceCallback?.onDriveModeChanged(mode)
            }
        }
    }

    private var wasCallActiveBefore = false
    private val callRecoveryRunnable = Runnable {
        checkOcclusionAndRecover("电话挂断恢复")
    }

    private val callListener = object : CarCallManager.CallListener {
        override fun onCallChanged(callInfo: CarCallManager.CallInfo) {
            mainHandler.post {
                if (!PrefsManager.getOverlayVisible()) {
                    return@post
                }
                overlayWindow?.setCallInfo(callInfo)

                if (callInfo.isCallActive) {
                    wasCallActiveBefore = true
                    mainHandler.removeCallbacks(callRecoveryRunnable)
                } else if (callInfo.state == CarCallManager.CallState.TERMINATED && wasCallActiveBefore) {
                    wasCallActiveBefore = false
                    AppLogger.i(TAG, "🛡️ [电话挂断接管] 捕获通话挂断退场，延时 600ms 执行单次置顶确认")
                    mainHandler.removeCallbacks(callRecoveryRunnable)
                    mainHandler.postDelayed(callRecoveryRunnable, 600L)
                } else if (callInfo.state == CarCallManager.CallState.IDLE) {
                    // IDLE 为 2.5s 倒计时结束后的常态复位，绝对不派发二次自检队列
                    wasCallActiveBefore = false
                    mainHandler.removeCallbacks(callRecoveryRunnable)
                }
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "📡 收到外部/ADB控制广播: $action")
            when (action) {
                ACTION_CHECK_OCCLUSION -> {
                    AppLogger.i(TAG, "📡 接收到遮挡探测广播指令")
                    checkOcclusionAndRecover("广播手动触发自检")
                }
                ACTION_GEAR_SWITCH_LEGACY, ACTION_SWITCH_GEAR -> {
                    val gear = intent.getStringExtra(EXTRA_GEAR_LEGACY)
                        ?: intent.getStringExtra(EXTRA_TARGET_GEAR)
                        ?: "P"
                    Log.i(TAG, "📡 广播显式触发切档模拟: $gear")
                    // 用户显式广播或按钮切档，确保触发动画
                    isFirstGearEvent = false
                    lastReportedGear = null
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateGear(gear)
                }
                ACTION_SHOW_TOP -> showOverlay(WeatherOverlayWindow.Mode.TOP)
                ACTION_SHOW_FITNESS -> showOverlay(WeatherOverlayWindow.Mode.FITNESS)
                ACTION_HIDE -> hideOverlay()
                ACTION_TRIGGER_LIGHTNING -> overlayWindow?.triggerInstantLightning()
                ACTION_SWITCH_DRIVE_MODE -> {
                    val mode = intent.getStringExtra(EXTRA_DRIVE_MODE) ?: "SPORT"
                    Log.i(TAG, "📡 广播显式触发驾驶模式切换: $mode")
                    isFirstDriveModeEvent = false
                    lastReportedDriveMode = null
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateDriveMode(mode)
                }
                ACTION_SIMULATE_PRESET -> {
                    val preset = intent.getStringExtra(EXTRA_PRESET_KEY)
                    applySimulationPreset(preset)
                }
                ACTION_SIMULATE_CUSTOM -> {
                    val timeIdx = intent.getIntExtra(EXTRA_TIME_OF_DAY, 1)
                    val weatherIdx = intent.getIntExtra(EXTRA_WEATHER_STATE, 0)
                    val windSpeed = intent.getFloatExtra(EXTRA_WIND_SPEED, 10f)
                    val isSleet = intent.getBooleanExtra(EXTRA_IS_SLEET, false)
                    applyCustomSimulation(timeIdx, weatherIdx, windSpeed, isSleet)
                }
                ACTION_RESET_LIVE -> {
                    isSimulating = false
                    startLiveWeatherEngine(force = true)
                }
                ACTION_UPDATE_LIQUID_GLASS -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LIQUID_GLASS_ENABLED, PrefsManager.isLiquidGlassClockEnabled())
                    Log.i(TAG, "📡 接收到液态玻璃时钟状态更新广播: enabled=$enabled")
                    overlayWindow?.setLiquidGlassEnabled(enabled)
                }
                ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 接收到左下角档位HUD状态更新广播: enabled=$enabled")
                    overlayWindow?.setBottomLeftGearHudEnabled(enabled)
                }
                ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 接收到小欧形象与避让状态更新指令: enabled=$enabled")
                    overlayWindow?.updateAvatarAndAvoidance(enabled)
                }
                ACTION_CALL_EVENT -> {
                    val typeStr = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "normal"
                    val stateStr = intent.getStringExtra(EXTRA_CALL_STATE) ?: "ringing"
                    val name = intent.getStringExtra(EXTRA_CALL_NAME) ?: "张总"
                    val number = intent.getStringExtra(EXTRA_CALL_NUMBER) ?: "13800009527"
                    val type = if (typeStr.equals("wechat", ignoreCase = true)) CarCallManager.CallType.WECHAT else CarCallManager.CallType.NORMAL
                    val state = when (stateStr.lowercase()) {
                        "ringing", "incoming" -> CarCallManager.CallState.RINGING
                        "dialing", "outgoing" -> CarCallManager.CallState.DIALING
                        "active", "connected" -> CarCallManager.CallState.ACTIVE
                        "hangup", "terminated" -> CarCallManager.CallState.TERMINATED
                        else -> CarCallManager.CallState.IDLE
                    }
                    Log.i(TAG, "📡 接收到通话模拟广播: type=$type, state=$state, name=${PrivacyUtil.maskName(name)}, num=${PrivacyUtil.maskPhone(number)}")
                    CarCallManager.getInstance(this@WeatherOverlayService).simulateCall(type, state, name, number)
                }
                ACTION_SIMULATE_MUSIC -> {
                    val playing = intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, true)
                    val title = intent.getStringExtra(EXTRA_MUSIC_TITLE) ?: "See You Again (feat. Charlie Puth)"
                    val artist = intent.getStringExtra(EXTRA_MUSIC_ARTIST) ?: "Wiz Khalifa / Charlie Puth"
                    val duration = intent.getLongExtra("extra_music_duration", 0L)
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = duration))
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "🌙 车机屏幕息屏/熄火，立即同步日志并挂起小欧屏高帧率物理引擎与歌词走时")
                    com.carlyrics.util.AppLogger.flushSync()
                    overlayWindow?.pause()
                    lyricsProgressJob?.cancel()
                    lyricsProgressJob = null
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "☀️ 车机屏幕亮屏/唤醒，立即恢复小欧屏物理引擎与歌词走时")
                    overlayWindow?.resume()
                    if (isShowing()) {
                        startLyricsProgressTicker()
                        mainHandler.postDelayed({
                            checkOcclusionAndRecover("屏幕唤醒自检")
                        }, 800L)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceStartTimeMs = SystemClock.elapsedRealtime()

        // 优先从本地持久化加载上一次的实况天气，并立即代入当前系统时间重算昼夜时相，杜绝启动首帧画面跳变
        val cachedSceneJson = PrefsManager.getLastWeatherSceneJson()
        if (!cachedSceneJson.isNullOrBlank()) {
            try {
                val cached = com.google.gson.Gson().fromJson(cachedSceneJson, WeatherSceneConfig::class.java)
                if (cached != null) {
                    val nowUnix = System.currentTimeMillis() / 1000L
                    currentScene = WeatherSceneMapper.updateSceneTimeline(
                        current = cached,
                        nowUnix = nowUnix,
                        updatedMinuteRainSummary = cached.minuteRainSummary,
                        isMinuteRainActive = cached.isMinuteRainActive
                    )
                    Log.i(TAG, "📦 启动预载上次缓存天气场景(已重算当前昼夜): ${currentScene.weatherState}, 时段=${currentScene.timeOfDay}, ${currentScene.temperatureCelsius}°C")
                }
            } catch (_: Exception) {}
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("小欧屏服务运行中"))

        threeScreenClient = ThreeScreenClient(this, this).apply { bind() }
        gpsManager = CarGpsManager(this)
        val openMeteoRepo = OpenMeteoRepository(this)
        val qWeatherRepo = QWeatherRepository(this)
        fusionEngine = WeatherFusionEngine(
            context = this,
            scope = serviceScope,
            openMeteoRepo = openMeteoRepo,
            qWeatherRepo = qWeatherRepo,
            gpsManager = gpsManager!!
        )

        // 初始化欧尚 Z6 实车档位与驾驶模式总线信号监听
        CarGearManager.getInstance(this).apply {
            init()
            registerListener(gearListener)
            registerDriveModeListener(driveModeListener)
        }

        // 初始化车载通话监听
        CarCallManager.getInstance(this).apply {
            startListening()
            addListener(callListener)
        }

        // 注册外部/ADB广播控制接收器 (支持 com.car.gear.SWITCH 等指令)
        val filter = IntentFilter().apply {
            addAction(ACTION_GEAR_SWITCH_LEGACY)
            addAction(ACTION_SWITCH_GEAR)
            addAction(ACTION_SWITCH_DRIVE_MODE)
            addAction(ACTION_SHOW_TOP)
            addAction(ACTION_SHOW_FITNESS)
            addAction(ACTION_HIDE)
            addAction(ACTION_TRIGGER_LIGHTNING)
            addAction(ACTION_SIMULATE_PRESET)
            addAction(ACTION_SIMULATE_CUSTOM)
            addAction(ACTION_RESET_LIVE)
            addAction(ACTION_UPDATE_LIQUID_GLASS)
            addAction(ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD)
            addAction(ACTION_UPDATE_XIAOOU_AVATAR)
            addAction(ACTION_CALL_EVENT)
            addAction(ACTION_SIMULATE_MUSIC)
            addAction(ACTION_CHECK_OCCLUSION)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        startLiveWeatherEngine()
        startMusicObservation()

        // 注册屏幕热插拔/多屏动态监听：车机开机时物理副屏若延迟上线，自动平滑迁移至独立物理副屏
        ThirdDisplayFinder.startListening(this, object : ThirdDisplayFinder.DisplayChangeListener {
            override fun onDisplaysChanged() {
                if (isShowing()) {
                    val currentTarget = overlayWindow?.targetDisplayId ?: -1
                    val bestDisplay = ThirdDisplayFinder.find(this@WeatherOverlayService)
                    if (bestDisplay != null && bestDisplay.displayId != currentTarget && currentTarget == android.view.Display.DEFAULT_DISPLAY) {
                        Log.i(TAG, "检测到物理副屏上线 (id=${bestDisplay.displayId})，自动将投屏从小欧主屏平滑迁移至独立副屏")
                        showOverlay(currentMode)
                    }
                }
            }
        })

        notifyStateToCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            Log.i(TAG, "收到指令: $action")
            when (action) {
                ACTION_SHOW_TOP -> showOverlay(WeatherOverlayWindow.Mode.TOP)
                ACTION_SHOW_FITNESS -> showOverlay(WeatherOverlayWindow.Mode.FITNESS)
                ACTION_HIDE -> hideOverlay()
                ACTION_CYCLE_DISPLAY -> {
                    val nextDisplay = ThirdDisplayFinder.cycleDisplay(this)
                    if (isShowing()) {
                        hideOverlay(isExplicitUserClose = false)
                        nextDisplay?.let { showOverlay(currentMode) }
                    } else {
                        notifyStateToCallback()
                    }
                }
                ACTION_TRIGGER_LIGHTNING -> {
                    overlayWindow?.triggerInstantLightning()
                }
                ACTION_GEAR_SWITCH_LEGACY, ACTION_SWITCH_GEAR -> {
                    val targetGear = intent.getStringExtra(EXTRA_TARGET_GEAR)
                        ?: intent.getStringExtra(EXTRA_GEAR_LEGACY)
                        ?: "P"
                    Log.i(TAG, "收到档位模拟切换指令: $targetGear")
                    isFirstGearEvent = false
                    lastReportedGear = null
                    CarGearManager.getInstance(this).simulateGear(targetGear)
                }
                ACTION_SWITCH_DRIVE_MODE -> {
                    val targetMode = intent.getStringExtra(EXTRA_DRIVE_MODE) ?: "SPORT"
                    Log.i(TAG, "收到驾驶模式模拟切换指令: $targetMode")
                    isFirstDriveModeEvent = false
                    lastReportedDriveMode = null
                    CarGearManager.getInstance(this).simulateDriveMode(targetMode)
                }
                ACTION_SIMULATE_PRESET -> {
                    val preset = intent.getStringExtra(EXTRA_PRESET_KEY)
                    applySimulationPreset(preset)
                }
                ACTION_SIMULATE_CUSTOM -> {
                    val timeIdx = intent.getIntExtra(EXTRA_TIME_OF_DAY, 1)
                    val weatherIdx = intent.getIntExtra(EXTRA_WEATHER_STATE, 0)
                    val windSpeed = intent.getFloatExtra(EXTRA_WIND_SPEED, 10f)
                    val isSleet = intent.getBooleanExtra(EXTRA_IS_SLEET, false)
                    applyCustomSimulation(timeIdx, weatherIdx, windSpeed, isSleet)
                }
                ACTION_RESET_LIVE -> {
                    isSimulating = false
                    startLiveWeatherEngine(force = true)
                }
                ACTION_UPDATE_LIQUID_GLASS -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LIQUID_GLASS_ENABLED, PrefsManager.isLiquidGlassClockEnabled())
                    Log.i(TAG, "📡 接收到液态玻璃时钟状态更新广播: enabled=$enabled")
                    overlayWindow?.setLiquidGlassEnabled(enabled)
                }
                ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 接收到左下角档位HUD状态更新广播: enabled=$enabled")
                    overlayWindow?.setBottomLeftGearHudEnabled(enabled)
                }
                ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 接收到小欧形象与避让状态更新指令: enabled=$enabled")
                    overlayWindow?.updateAvatarAndAvoidance(enabled)
                }
                ACTION_CALL_EVENT -> {
                    val typeStr = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "normal"
                    val stateStr = intent.getStringExtra(EXTRA_CALL_STATE) ?: "ringing"
                    val name = intent.getStringExtra(EXTRA_CALL_NAME) ?: "张总"
                    val number = intent.getStringExtra(EXTRA_CALL_NUMBER) ?: "13800009527"
                    val type = if (typeStr.equals("wechat", ignoreCase = true)) CarCallManager.CallType.WECHAT else CarCallManager.CallType.NORMAL
                    val state = when (stateStr.lowercase()) {
                        "ringing", "incoming" -> CarCallManager.CallState.RINGING
                        "dialing", "outgoing" -> CarCallManager.CallState.DIALING
                        "active", "connected" -> CarCallManager.CallState.ACTIVE
                        "hangup", "terminated" -> CarCallManager.CallState.TERMINATED
                        else -> CarCallManager.CallState.IDLE
                    }
                    Log.i(TAG, "📡 接收到通话模拟广播: type=$type, state=$state, name=${PrivacyUtil.maskName(name)}, num=${PrivacyUtil.maskPhone(number)}")
                    CarCallManager.getInstance(this@WeatherOverlayService).simulateCall(type, state, name, number)
                }
                ACTION_UPDATE_LYRICS_SETTINGS -> {
                    updateLyricsRuntimeSettings()
                }
                ACTION_SIMULATE_MUSIC -> {
                    val playing = intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, true)
                    val title = intent.getStringExtra(EXTRA_MUSIC_TITLE) ?: "See You Again (feat. Charlie Puth)"
                    val artist = intent.getStringExtra(EXTRA_MUSIC_ARTIST) ?: "Wiz Khalifa / Charlie Puth"
                    val duration = intent.getLongExtra("extra_music_duration", 0L)
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = duration))
                }
                ACTION_CHECK_LYRICS_STATE -> {
                    evaluateLyricsStateTransition()
                }
                ACTION_CHECK_OCCLUSION -> {
                    checkOcclusionAndRecover("onStartCommand触发自检")
                }
                else -> {}
            }
        }

        // 若服务在未带具体 Action 下启动（如系统开机、任务管理自愈、后台服务重启），检查投屏状态自愈恢复
        if (intent == null || intent.action == null) {
            if (PrefsManager.getOverlayVisible() && !isShowing()) {
                val showAvatar = PrefsManager.isXiaoOuAvatarEnabled()
                val targetMode = if (showAvatar) WeatherOverlayWindow.Mode.FITNESS else WeatherOverlayWindow.Mode.TOP
                Log.i(TAG, "服务自愈/被动拉起，检测到原先已开启投屏，自动恢复显示: mode=$targetMode")
                showOverlay(targetMode)
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: 监听到前台任务被移除，确保投屏前台服务自愈运行")
        if (PrefsManager.getOverlayVisible()) {
            try {
                val showAvatar = PrefsManager.isXiaoOuAvatarEnabled()
                val restartIntent = Intent(applicationContext, WeatherOverlayService::class.java).apply {
                    action = if (showAvatar) ACTION_SHOW_FITNESS else ACTION_SHOW_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved 自愈拉起异常: ${e.message}")
            }
        }
    }

    private fun showOverlay(mode: WeatherOverlayWindow.Mode) {
        currentMode = mode
        val targetDisplay = ThirdDisplayFinder.find(this)
        if (targetDisplay == null) {
            Log.w(TAG, "未发现可用屏幕")
            return
        }

        // 核心规范：严禁向 threeScreenClient 申请 requestShowFitness()！
        // 逆向分析表明调用 requestShowFitness 会向系统发出 notifyHideView("VOICE_TTS") 导致小欧 3D 机器人被隐藏销毁。
        // 直接使用 Presentation (2037) 或 Overlay (2038) 挂载，小欧 3D 形象天然共存。

        overlayWindow?.dismiss()
        overlayWindow = WeatherOverlayWindow(this, targetDisplay, mode).apply {
            onCloseListener = {
                hideOverlay(isExplicitUserClose = true)
            }
            onFocusLostListener = {
                handleSubscreenFocusLost()
            }
            show()
            updateScene(currentScene)
        }
        PrefsManager.setOverlayVisible(true)

        // 立即向新挂载的悬浮窗同步已知音轨元数据与歌词
        if (lastTrackTitle.isNotEmpty()) {
            overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
            if (isMusicPlaying) {
                overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = true)
            }
            if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
            if (currentLyricLines.isNotEmpty()) {
                overlayWindow?.setLyricsLines(currentLyricLines)
                overlayWindow?.setCanvasLyrics(currentLyricLines)
            }
        }
        updateLyricsRuntimeSettings()
        // 挂载后立即同步一次当前已知实车档位至微型 HUD，消灭重建延迟
        val currentGear = CarGearManager.getInstance(this).getCurrentGear()
        overlayWindow?.syncGearSilently(currentGear)

        evaluateLyricsStateTransition(smooth = false)
        notifyStateToCallback()
    }

    fun hideOverlay(isExplicitUserClose: Boolean = true) {
        mainHandler.removeCallbacks(callRecoveryRunnable)
        wasCallActiveBefore = false
        pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
        pendingPausedTrack = null
        reassertTopHandler.removeCallbacks(reassertTopRunnable)
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
        isFallbackPending = false
        lyricsProgressJob?.cancel()
        lyricsProgressJob = null
        overlayWindow?.dismiss()
        overlayWindow = null
        // 彻底切断 requestHideFitness 调用，我方独立管理窗口生命周期，杜绝与系统三屏中间件冲突
        if (isExplicitUserClose) {
            PrefsManager.setOverlayVisible(false)
        }
        notifyStateToCallback()
    }

    fun isShowing(): Boolean = overlayWindow?.isAttached == true

    // ==========================================
    // 音乐识别与车载歌词双态平滑切换
    // ==========================================
    private fun startMusicObservation() {
        serviceScope.launch {
            CarLyricsApplication.recognitionEngine.currentTrack.collectLatest { track ->
                onTrackStateChanged(track)
            }
        }
    }

    private fun onTrackStateChanged(track: CurrentTrack?) {
        serviceCallback?.onTrackChanged(track)

        val title = track?.title?.trim() ?: ""
        val artist = track?.artist?.trim() ?: ""

        val isTrackChanged = (title != lastTrackTitle || artist != lastTrackArtist)

        // 核心优化：只要曲名有效，无论当前是否处于播放中（例如蓝牙刚连上、暂停或起播状态延迟），
        // 立即异步预取歌词与封面缓存，彻底消灭用户刚切歌/蓝牙起播时等歌词的真空期！
        if (title.isNotEmpty() && !MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            val trackKey = "$title|$artist"
            if (isTrackChanged) {
                lastTrackTitle = title
                lastTrackArtist = artist
                lastFetchedTrackKey = null
                currentLyricLines = emptyList()
                lastCoverUrl = null
                // 核心修复：切歌瞬间立即清空旧歌词并同步新歌信息，彻底杜绝上一首歌词残留
                mainHandler.post {
                    overlayWindow?.setLyricsTrackInfo(title, artist)
                    overlayWindow?.setLyricsLines(emptyList())
                    overlayWindow?.setCanvasLyrics(emptyList())
                    overlayWindow?.setLyricsCoverUrl("")
                    if (isMusicPlaying || track?.isPlaying == true) {
                        overlayWindow?.setTopMusicTrackInfo(title, artist, isPlaying = true)
                    }
                }
                fetchLyricsAndCover(title, artist, track)
            } else if (lastFetchedTrackKey != trackKey && inFlightFetchTrackKey != trackKey) {
                fetchLyricsAndCover(title, artist, track)
            }
        }

        if (track == null || track.title.isBlank() || MusicRecognitionEngine.isInvalidPlaceholderTrack(track.title, track.artist)) {
            isMusicPlaying = false
            wasMusicPlaying = false
            isFallbackPending = false
            fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
            lyricsProgressJob?.cancel()
            lyricsProgressJob = null
            overlayWindow?.setTopMusicTrackInfo("", "", isPlaying = false)
            if (PrefsManager.isStandaloneLyricsEnabled()) {
                overlayWindow?.switchToWeatherPage(smooth = true)
            } else {
                overlayWindow?.setCanvasLyricsActive(false, smooth = true)
            }
            return
        }

        if (!track.isPlaying) {
            // 若当前处于播放中，启动 350ms 暂停防抖过滤，杜绝车机蓝牙多通道伴生回调的瞬态闪烁震荡
            if (isMusicPlaying) {
                isMusicPlaying = false
                pendingPausedTrack = track
                pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
                pauseDebounceHandler.postDelayed(pauseDebounceRunnable, 350L)
            } else if (pendingPausedTrack != null) {
                pendingPausedTrack = track
            } else {
                // 已经完成暂停防抖确认，仅更新最新静止位置，不重启任何定时器
                lastKnownPosition = track.position
                overlayWindow?.updateLyricsProgress(track.position)
                overlayWindow?.updateCanvasLyricsProgress(track.position)
            }
            return
        }

        // 此时处于 PLAYING 状态
        val isFirstPlayInSession = !wasMusicPlaying && pendingPausedTrack == null

        // 立即取消暂停防抖，平稳恢复播放
        pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
        pendingPausedTrack = null

        isMusicPlaying = true
        wasMusicPlaying = true
        if (isFallbackPending) {
            fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
            isFallbackPending = false
            AppLogger.d(TAG, "▶️ 音乐在 5s 内恢复播放，取消大时钟回退倒计时")
        }

        // 核心防抢置顶与无感过渡优化：
        // 1. 日常切歌 (isTrackChanged == true) 时，小欧副屏窗口一直稳固挂载在顶层，绝不调用 reassertTop 重建窗口！
        // 2. 暂停恢复播放 (无论暂停多久) 时，小欧副屏窗口始终处于最顶层，直接平滑淡入歌词页，绝不调用 checkOcclusionAndRecover
        //    触发伪遮挡误判，彻底杜绝 Presentation 销毁重建与闪烁！
        // 3. 原厂媒体界面抢占副屏由 ITSmiddleService 的 onShowRequest 事件驱动精准捕获，无需在起播时主动轮询。

        lastKnownPosition = track.position
        lastPositionNanos = System.nanoTime()

        if (title.isNotEmpty()) {
            overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
            overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = true)
            if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
            if (currentLyricLines.isNotEmpty()) {
                overlayWindow?.setLyricsLines(currentLyricLines)
                overlayWindow?.setCanvasLyrics(currentLyricLines)
            }
        }

        if (isShowing()) {
            if (isFirstPlayInSession || isTrackChanged) {
                if (PrefsManager.isStandaloneLyricsEnabled()) {
                    overlayWindow?.switchToLyricsPage(smooth = true)
                } else {
                    overlayWindow?.switchToWeatherPage(smooth = false)
                    if (currentLyricLines.isNotEmpty()) {
                        overlayWindow?.setCanvasLyricsActive(true, smooth = true)
                    }
                }
            }
            startLyricsProgressTicker()
        }
    }

    private fun fetchLyricsAndCover(title: String, artist: String, track: CurrentTrack? = null) {
        val trackKey = "$title|$artist"
        if (inFlightFetchTrackKey == trackKey) {
            Log.d(TAG, "歌词与封面请求已在进行中，跳过重复拉取: $trackKey")
            return
        }
        if (lastFetchedTrackKey == trackKey && currentLyricLines.isNotEmpty()) {
            Log.d(TAG, "当前曲目歌词已就绪，无需重复拉取: $trackKey")
            return
        }

        lyricsFetchJob?.cancel()
        lyricsApi.cancelAllRequests()
        inFlightFetchTrackKey = trackKey
        overlayWindow?.setLyricsTrackInfo(title, artist)

        lyricsFetchJob = serviceScope.launch(Dispatchers.IO) {
            try {
                coroutineContext.ensureActive()
                val currentTrack = track ?: CarLyricsApplication.recognitionEngine.currentTrack.value

                // 1. 获取歌词内容：优先使用原车酷狗/U盘离线自带歌词，若无则调用维护的在线接口与本地缓存
                val isNativeLyricsAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeLyrics.isNullOrEmpty()

                val (lines, fetchedCoverUrl) = if (isNativeLyricsAvailable) {
                    val rawLines = currentTrack!!.nativeLyrics!!
                    Log.i(TAG, "优先采用原车/U盘原生歌词: $trackKey (行数: ${rawLines.size})")
                    Pair(rawLines, null)
                } else {
                    Log.d(TAG, "原车未附带歌词，回退至在线歌词接口匹配: $trackKey")
                    val lyricResult = try {
                        lyricsApi.fetchLyrics(title, artist)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) { null }

                    coroutineContext.ensureActive()
                    if (inFlightFetchTrackKey != trackKey) {
                        Log.d(TAG, "已切换新歌曲，丢弃过时歌词结果: $trackKey (当前正在获取: $inFlightFetchTrackKey)")
                        return@launch
                    }

                    val data = lyricResult?.getOrNull()
                    val rawLines = data?.lines?.filter { it.text.isNotBlank() } ?: emptyList()
                    val parsedLines = if (rawLines.isNotEmpty()) {
                        rawLines
                    } else {
                        val highlightLine = if (artist.isNotBlank() &&
                            !artist.equals("未知歌手", ignoreCase = true) &&
                            !artist.equals("Unknown", ignoreCase = true)
                        ) {
                            if (title.contains(artist)) title else "$title - $artist"
                        } else if (title.isNotBlank()) {
                            title
                        } else {
                            "未知曲目"
                        }
                        listOf(
                            LyricLine(timestamp = 0L, text = highlightLine),
                            LyricLine(timestamp = 86_400_000L, text = "暂无未匹配到歌词")
                        )
                    }
                    Pair(parsedLines, data?.coverUrl)
                }

                currentLyricLines = lines
                lastFetchedTrackKey = trackKey

                // 2. 封面处理：优先使用原车/U盘本地提取封面，次优复用歌词返回的 coverUrl，若无则在线补拉封面
                coroutineContext.ensureActive()
                val isNativeCoverAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeCoverUrl.isNullOrBlank()

                val coverUrl = if (isNativeCoverAvailable) {
                    Log.i(TAG, "优先采用原车/U盘原生封面: $trackKey (${currentTrack!!.nativeCoverUrl})")
                    currentTrack.nativeCoverUrl
                } else {
                    fetchedCoverUrl ?: try {
                        lyricsApi.fetchCover(title, artist)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) { null }
                }

                coroutineContext.ensureActive()
                if (inFlightFetchTrackKey != trackKey) {
                    Log.d(TAG, "已切换新歌曲，丢弃过时封面结果: $trackKey (当前正在获取: $inFlightFetchTrackKey)")
                    return@launch
                }
                lastCoverUrl = coverUrl

                withContext(Dispatchers.Main) {
                    coroutineContext.ensureActive()
                    if (inFlightFetchTrackKey != trackKey && inFlightFetchTrackKey != null) {
                        return@withContext
                    }
                    if (coverUrl != null) {
                        overlayWindow?.setLyricsCoverUrl(coverUrl)
                    }
                    overlayWindow?.setLyricsLines(lines)
                    overlayWindow?.setCanvasLyrics(lines)
                    if (isMusicPlaying) {
                        overlayWindow?.setTopMusicTrackInfo(title, artist, isPlaying = true)
                    }
                    if (!PrefsManager.isStandaloneLyricsEnabled() && isMusicPlaying && lines.isNotEmpty() && isShowing()) {
                        overlayWindow?.setCanvasLyricsActive(true, smooth = true)
                    }
                }
            } finally {
                if (inFlightFetchTrackKey == trackKey) {
                    inFlightFetchTrackKey = null
                }
            }
        }
    }

    /**
     * 16ms (60fps) 高帧率歌词走时推演，保证逐字动效卡拉OK与跑马灯极致丝滑推进
     */
    private fun startLyricsProgressTicker() {
        if (lyricsProgressJob?.isActive == true) {
            // 走时协程已在平稳推进中，仅依赖 lastKnownPosition / lastPositionNanos 基准计算，杜绝频繁重建造成的微顿挫
            return
        }
        lyricsProgressJob = serviceScope.launch {
            while (isActive && isMusicPlaying) {
                if (!isShowing()) {
                    delay(300L)
                    continue
                }
                val now = System.nanoTime()
                val elapsedMs = (now - lastPositionNanos) / 1_000_000L
                val currentPos = (lastKnownPosition + elapsedMs).coerceAtLeast(0L)

                val isStandaloneLyricsActive = PrefsManager.isStandaloneLyricsEnabled() &&
                    overlayWindow?.getCurrentPage() == WeatherOverlayWindow.ActivePage.LYRICS

                if (isStandaloneLyricsActive) {
                    overlayWindow?.updateLyricsProgress(currentPos)
                    delay(16L)
                } else if (!PrefsManager.isStandaloneLyricsEnabled()) {
                    overlayWindow?.updateCanvasLyricsProgress(currentPos)
                    delay(16L)
                } else {
                    // 当歌词页面不可见时（如处于大时钟页），降频至 200ms 休眠，削减主线程无谓走时推演开销
                    delay(200L)
                }
            }
        }
    }

    /**
     * 评估当前是否应处于歌词页或天气页
     */
    fun evaluateLyricsStateTransition(smooth: Boolean = true) {
        val standaloneEnabled = PrefsManager.isStandaloneLyricsEnabled()
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
        isFallbackPending = false

        val track = CarLyricsApplication.recognitionEngine.currentTrack.value

        if (standaloneEnabled) {
            overlayWindow?.setCanvasLyricsActive(false, smooth = false)
            if (track != null && track.isPlaying && track.title.isNotBlank()) {
                val title = track.title.trim()
                val artist = track.artist.trim()
                lastKnownPosition = track.position
                lastPositionNanos = System.nanoTime()

                val trackKey = "$title|$artist"
                val isNewTrack = (title != lastTrackTitle || artist != lastTrackArtist)
                if (isNewTrack) {
                    lastTrackTitle = title
                    lastTrackArtist = artist
                    lastFetchedTrackKey = null
                    currentLyricLines = emptyList()
                    fetchLyricsAndCover(title, artist, track)
                } else if (lastFetchedTrackKey != trackKey && inFlightFetchTrackKey != trackKey) {
                    fetchLyricsAndCover(title, artist, track)
                } else {
                    overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
                    if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
                    if (currentLyricLines.isNotEmpty()) overlayWindow?.setLyricsLines(currentLyricLines)
                }

                overlayWindow?.switchToLyricsPage(smooth)
                startLyricsProgressTicker()
            } else {
                overlayWindow?.switchToWeatherPage(smooth)
            }
        } else {
            // 独立歌词关闭时：留在天气界面，使用画布五行居中歌词
            overlayWindow?.switchToWeatherPage(smooth = false)
            if (track != null && track.isPlaying && track.title.isNotBlank()) {
                val title = track.title.trim()
                val artist = track.artist.trim()
                overlayWindow?.setTopMusicTrackInfo(title, artist, isPlaying = true)
                lastKnownPosition = track.position
                lastPositionNanos = System.nanoTime()

                val trackKey = "$title|$artist"
                val isNewTrack = (title != lastTrackTitle || artist != lastTrackArtist)
                if (isNewTrack) {
                    lastTrackTitle = title
                    lastTrackArtist = artist
                    lastFetchedTrackKey = null
                    currentLyricLines = emptyList()
                    fetchLyricsAndCover(title, artist, track)
                } else if (lastFetchedTrackKey != trackKey && inFlightFetchTrackKey != trackKey) {
                    fetchLyricsAndCover(title, artist, track)
                } else {
                    if (currentLyricLines.isNotEmpty()) {
                        overlayWindow?.setCanvasLyrics(currentLyricLines)
                    }
                }

                if (currentLyricLines.isNotEmpty()) {
                    overlayWindow?.setCanvasLyricsActive(true, smooth)
                }
                startLyricsProgressTicker()
            } else {
                overlayWindow?.setTopMusicTrackInfo("", "", isPlaying = false)
                overlayWindow?.setCanvasLyricsActive(false, smooth)
            }
        }
    }

    /**
     * 同步运行时二级设置至当前悬浮窗
     */
    fun updateLyricsRuntimeSettings() {
        val shouldAvoid = PrefsManager.isXiaoOuAvatarEnabled()
        val targetSkin = PrefsManager.getSkinType()
        overlayWindow?.let { w ->
            w.updateLyricsAvoidance(shouldAvoid)
            w.updateCanvasAvoidance(shouldAvoid)
            w.reloadLyricsSkin(targetSkin)
            w.updateLyricsFontSize(PrefsManager.getFontSizeSp())
            w.updateLyricsSplitRatio(PrefsManager.getSplitRatio())
            w.updateLyricsAlignment(PrefsManager.getLyricsAlignment())
            w.updateLyricsMarquee(PrefsManager.isMarqueeEnabled())
            w.updateLyricsScale(PrefsManager.getDpiScale())
            w.updateLyricsInfoFontScale(PrefsManager.getInfoFontScale())
            w.updateLyricsOffsetMs(PrefsManager.lyricsOffsetMs)
        }
        // 同时发送全局广播，让处于副屏预览的 CarSubscreenActivity 也同步更新！
        sendBroadcast(Intent(ACTION_UPDATE_LYRICS_SETTINGS).apply {
            setPackage(packageName)
        })
    }

    // ==========================================
    // 气象驱动引擎 (WeatherFusionEngine 双引擎融合)
    // ==========================================
    private fun startLiveWeatherEngine(force: Boolean = false) {
        fusionEngine?.start()
        if (force) {
            gpsManager?.let { gps ->
                fusionEngine?.refreshWeather(gps.currentLatitude, gps.currentLongitude, force = true)
            }
        }
        fusionJob?.cancel()
        fusionJob = serviceScope.launch {
            fusionEngine?.sceneConfig?.collectLatest { newScene ->
                if (!isSimulating) {
                    applyNewScene(newScene)
                }
            }
        }
    }

    // ==========================================
    // 效果模拟实验室 API
    // ==========================================
    private fun applySimulationPreset(preset: String?) {
        isSimulating = true
        val config = when (preset) {
            "CLEAR_DAY" -> WeatherSceneMapper.mapToScene(0, TimeOfDay.DAY, 10f, 24f)
            "CLEAR_DAWN" -> WeatherSceneMapper.mapToScene(0, TimeOfDay.DAWN, 8f, 16f)
            "CLOUDY_DUSK" -> WeatherSceneMapper.mapToScene(2, TimeOfDay.DUSK, 12f, 20f)
            "CLEAR_NIGHT" -> WeatherSceneMapper.mapToScene(0, TimeOfDay.NIGHT, 6f, 15f)
            "RAIN_HEAVY" -> WeatherSceneMapper.mapToScene(65, TimeOfDay.DAY, 26f, 18f)
            "STORM_NIGHT" -> WeatherSceneMapper.mapToScene(95, TimeOfDay.NIGHT, 32f, 19f)
            "SNOW_DAY" -> WeatherSceneMapper.mapToScene(75, TimeOfDay.DAY, 14f, -2f)
            "OVERCAST_DAY" -> WeatherSceneMapper.mapToScene(3, TimeOfDay.DAY, 10f, 17f)
            "HOT_DAY" -> WeatherSceneMapper.mapToScene(0, TimeOfDay.DAY, 6f, 31f)
            "SCORCHING_HEAT" -> WeatherSceneMapper.mapToScene(0, TimeOfDay.DAY, 4f, 36f)
            else -> WeatherSceneMapper.mapToScene(0, TimeOfDay.DAY, 10f, 22f)
        }
        applyNewScene(config)
    }

    private fun applyCustomSimulation(timeIdx: Int, weatherIdx: Int, windSpeed: Float, isSleet: Boolean = false) {
        isSimulating = true
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
        applyNewScene(scene)
    }

    private fun applyNewScene(scene: WeatherSceneConfig) {
        currentScene = scene
        try {
            val json = com.google.gson.Gson().toJson(scene)
            PrefsManager.setLastWeatherSceneJson(json)
        } catch (_: Exception) {}
        overlayWindow?.updateScene(scene)
        notifyStateToCallback()
    }

    private fun notifyStateToCallback() {
        val showing = isShowing()
        val desc = ThirdDisplayFinder.describe(this)
        serviceCallback?.onOverlayStateChanged(showing, if (showing) currentMode else null, desc)
        serviceCallback?.onSceneChanged(currentScene)
        serviceCallback?.onTrackChanged(CarLyricsApplication.recognitionEngine.currentTrack.value)
    }

    // ==========================================
    // ThreeScreenClient 回调与方案 1 遮挡精准自检测
    // ==========================================
    override fun onBound(ok: Boolean) {
        AppLogger.i(TAG, "三屏调度 NotifyService 绑定状态: $ok")
        if (ok && isShowing()) {
            mainHandler.postDelayed({
                checkOcclusionAndRecover("三屏服务连接就绪初检")
            }, 600L)
        }
    }

    override fun onShowRequest(type: String) {
        AppLogger.i(TAG, "三屏调度收到 onShowRequest: type=$type")
        if (type == ThreeScreenClient.SOURCE_FITNESS) {
            if (!PrefsManager.getOverlayVisible()) {
                Log.d(TAG, "三屏调度收到 onShowRequest，但用户已关闭投屏，忽略唤醒")
                return
            }
            showOverlay(currentMode)
            return
        }

        // 方案 1 核心自检测：判断自身是否被原厂副屏媒体(蓝牙音乐/酷狗歌词)覆盖
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            if (threeScreenClient?.isMediaView(type) == true) {
                // 车载电话活跃状态严格避让，绝不在通话中触发抢屏
                if (CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
                    AppLogger.i(TAG, "🛡️ [遮挡自检] 监听到原厂媒体($type)弹出，但当前处于通话活跃状态，保持避让")
                    return
                }

                AppLogger.w(TAG, "🚨 [遮挡自检·精确捕获] 确凿监听到原厂媒体($type)弹出抢占副屏！调度无缝置顶接管...")
                // 延时 350ms 避开原厂 Presentation.show() 内部初始化与入场动画，然后精准登顶
                scheduleReassertTop(350L)
            } else if (type.contains(ThreeScreenClient.VIEW_NAVIGATION, ignoreCase = true) ||
                type.contains(ThreeScreenClient.VIEW_APA, ignoreCase = true)) {
                AppLogger.i(TAG, "🛡️ [高优先级让路] 监听到系统高优先业务($type)，我方保持礼貌让路，不触发重登")
            }
        }
    }

    override fun onHideRequest(type: String) {
        AppLogger.d(TAG, "三屏调度收到 onHideRequest: type=$type")
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            if (threeScreenClient?.isMediaView(type) == true) {
                AppLogger.i(TAG, "🛡️ [遮挡自检] 原厂媒体($type)退场，执行状态复核")
                mainHandler.postDelayed({
                    checkOcclusionAndRecover("原厂媒体退场复核")
                }, 300L)
            }
        }
    }

    /**
     * 方案 1：通过车机三屏调度中间件 (ITSmiddleService AIDL) 主动探测自身是否被原厂副屏遮挡
     * 若检测到当前三屏前台视图为原厂媒体 (MEDIA)，且自身处于开启状态，则精准触发登顶接管
     */
    fun checkOcclusionAndRecover(reason: String): Boolean {
        if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) {
            return false
        }
        if (CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
            AppLogger.d(TAG, "🛡️ [遮挡自检·$reason] 当前车载电话处于活跃状态，保持避让不探测")
            return false
        }

        val client = threeScreenClient ?: return false
        val currentView = client.getCurrentView()
        AppLogger.d(TAG, "🔍 [遮挡自检·$reason] 车机三屏当前前台视图: $currentView")

        if (client.isMediaView(currentView)) {
            AppLogger.w(TAG, "🚨 [遮挡自检·$reason] 主动探测确凿证实小欧屏被原厂媒体($currentView)遮挡！立即执行无缝登顶接管！")
            val success = overlayWindow?.reassertTop() ?: false
            if (success) {
                updateLyricsRuntimeSettings()
                if (lastTrackTitle.isNotEmpty()) {
                    overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
                    if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
                    if (currentLyricLines.isNotEmpty()) {
                        overlayWindow?.setLyricsLines(currentLyricLines)
                        overlayWindow?.setCanvasLyrics(currentLyricLines)
                        if (lastKnownPosition > 0L) {
                            overlayWindow?.updateLyricsProgress(lastKnownPosition)
                            overlayWindow?.updateCanvasLyricsProgress(lastKnownPosition)
                        }
                    }
                    if (isMusicPlaying) {
                        overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = true)
                    }
                }
            }
            return success
        } else {
            AppLogger.d(TAG, "✅ [遮挡自检·$reason] 小欧屏未被媒体遮挡 (前台: $currentView)")
            return false
        }
    }

    // ==========================================
    // 前台通知管理
    // ==========================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小欧屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持小欧屏天气与车载歌词常驻渲染"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("小欧屏")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("小欧屏")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "注销 commandReceiver 异常", e)
        }
        CarGearManager.getInstance(this).unregisterListener(gearListener)
        CarGearManager.getInstance(this).unregisterDriveModeListener(driveModeListener)
        CarGearManager.getInstance(this).release()
        CarCallManager.getInstance(this).removeListener(callListener)
        CarCallManager.getInstance(this).stopListening()
        mainHandler.removeCallbacks(callRecoveryRunnable)
        wasCallActiveBefore = false
        pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
        pendingPausedTrack = null
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
        isFallbackPending = false
        reassertTopHandler.removeCallbacks(reassertTopRunnable)
        lyricsProgressJob?.cancel()
        lyricsProgressJob = null
        lyricsFetchJob?.cancel()
        fusionJob?.cancel()
        fusionEngine?.stop()
        hideOverlay(isExplicitUserClose = false)
        ThreeScreenClient.let {}
        ThirdDisplayFinder.stopListening(this)
        threeScreenClient?.unbind()
        gpsManager?.stopLocationUpdates()
        serviceScope.cancel()
        instance = null
    }

    companion object {
        private const val TAG = "WeatherOverlayService"
        private const val CHANNEL_ID = "weather_overlay_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_GEAR_SWITCH_LEGACY = "com.car.gear.SWITCH"
        const val EXTRA_GEAR_LEGACY = "gear"

        const val ACTION_SHOW_TOP = "com.car.xiaoouscreen.SHOW_TOP"
        const val ACTION_SHOW_FITNESS = "com.car.xiaoouscreen.SHOW_FITNESS"
        const val ACTION_HIDE = "com.car.xiaoouscreen.HIDE"
        const val ACTION_CYCLE_DISPLAY = "com.car.xiaoouscreen.CYCLE_DISPLAY"
        const val ACTION_TRIGGER_LIGHTNING = "com.car.xiaoouscreen.TRIGGER_LIGHTNING"
        const val ACTION_SWITCH_GEAR = "com.car.xiaoouscreen.SWITCH_GEAR"
        const val ACTION_SWITCH_DRIVE_MODE = "com.car.xiaoouscreen.SWITCH_DRIVE_MODE"
        const val ACTION_SIMULATE_PRESET = "com.car.xiaoouscreen.SIMULATE_PRESET"
        const val ACTION_SIMULATE_CUSTOM = "com.car.xiaoouscreen.SIMULATE_CUSTOM"
        const val ACTION_RESET_LIVE = "com.car.xiaoouscreen.RESET_LIVE"
        const val ACTION_UPDATE_LYRICS_SETTINGS = "com.car.xiaoouscreen.UPDATE_LYRICS_SETTINGS"
        const val ACTION_CHECK_LYRICS_STATE = "com.car.xiaoouscreen.CHECK_LYRICS_STATE"
        const val ACTION_UPDATE_LIQUID_GLASS = "com.car.xiaoouscreen.UPDATE_LIQUID_GLASS"
        const val ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD = "com.car.xiaoouscreen.UPDATE_BOTTOM_LEFT_GEAR_HUD"
        const val ACTION_UPDATE_XIAOOU_AVATAR = "com.car.xiaoouscreen.UPDATE_XIAOOU_AVATAR"
        const val ACTION_CALL_EVENT = "com.car.xiaoouscreen.CALL_EVENT"
        const val ACTION_SIMULATE_MUSIC = "com.car.xiaoouscreen.SIMULATE_MUSIC"
        const val ACTION_CHECK_OCCLUSION = "com.car.xiaoouscreen.CHECK_OCCLUSION"

        const val EXTRA_MUSIC_PLAYING = "extra_music_playing"
        const val EXTRA_MUSIC_TITLE = "extra_music_title"
        const val EXTRA_MUSIC_ARTIST = "extra_music_artist"

        const val EXTRA_TARGET_GEAR = "extra_target_gear"
        const val EXTRA_DRIVE_MODE = "mode"
        const val EXTRA_PRESET_KEY = "extra_preset"
        const val EXTRA_TIME_OF_DAY = "extra_time_of_day"
        const val EXTRA_WEATHER_STATE = "extra_weather_state"
        const val EXTRA_WIND_SPEED = "extra_wind_speed"
        const val EXTRA_IS_SLEET = "extra_is_sleet"
        const val EXTRA_LIQUID_GLASS_ENABLED = "extra_liquid_glass_enabled"
        const val EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED = "extra_bottom_left_gear_hud_enabled"
        const val EXTRA_XIAOOU_AVATAR_ENABLED = "extra_xiaoou_avatar_enabled"
        const val EXTRA_CALL_TYPE = "type"
        const val EXTRA_CALL_STATE = "state"
        const val EXTRA_CALL_NAME = "name"
        const val EXTRA_CALL_NUMBER = "number"

        var instance: WeatherOverlayService? = null
            private set

        // 车规优化：采用弱引用守护，杜绝 UI 宿主 Activity 销毁时的整树内存泄漏
        private var callbackRef: java.lang.ref.WeakReference<ServiceCallback>? = null
        var serviceCallback: ServiceCallback?
            get() = callbackRef?.get()
            set(value) {
                callbackRef = if (value != null) java.lang.ref.WeakReference(value) else null
            }

        fun simulateCall(context: Context, type: String, state: String, name: String, number: String) {
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_CALL_EVENT
                putExtra(EXTRA_CALL_TYPE, type)
                putExtra(EXTRA_CALL_STATE, state)
                putExtra(EXTRA_CALL_NAME, name)
                putExtra(EXTRA_CALL_NUMBER, number)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun switchGear(context: Context, gear: String) {
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_SWITCH_GEAR
                putExtra(EXTRA_TARGET_GEAR, gear)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun switchDriveMode(context: Context, mode: String) {
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_SWITCH_DRIVE_MODE
                putExtra(EXTRA_DRIVE_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun simulate(
            context: Context,
            timeOfDay: TimeOfDay,
            weatherState: WeatherState,
            isSleet: Boolean = false,
            windSpeed: Float = 12f
        ) {
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_SIMULATE_CUSTOM
                putExtra(EXTRA_TIME_OF_DAY, timeOfDay.ordinal)
                putExtra(EXTRA_WEATHER_STATE, weatherState.ordinal)
                putExtra(EXTRA_IS_SLEET, isSleet)
                putExtra(EXTRA_WIND_SPEED, windSpeed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun send(context: Context, action: String) {
            val i = Intent(context, WeatherOverlayService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun updateLiquidGlassState(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.setLiquidGlassEnabled(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_LIQUID_GLASS
                putExtra(EXTRA_LIQUID_GLASS_ENABLED, enabled)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动服务指令失败: ${e.message}")
            }
            // 同时发送全局广播，通知 CarSubscreenActivity (副屏预览页面)
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_LIQUID_GLASS).apply {
                    putExtra(EXTRA_LIQUID_GLASS_ENABLED, enabled)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "广播发送失败: ${e.message}")
            }
        }

        fun updateBottomLeftGearHudState(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.setBottomLeftGearHudEnabled(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD
                putExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, enabled)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动服务指令失败: ${e.message}")
            }
            // 同时发送全局广播，通知 CarSubscreenActivity (副屏预览页面)
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD).apply {
                    putExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, enabled)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "广播发送失败: ${e.message}")
            }
        }

        fun updateXiaoOuAvatarState(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.updateAvatarAndAvoidance(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_XIAOOU_AVATAR
                putExtra(EXTRA_XIAOOU_AVATAR_ENABLED, enabled)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动服务指令失败: ${e.message}")
            }
            // 同时发送全局广播，通知 CarSubscreenActivity (副屏预览页面)
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_XIAOOU_AVATAR).apply {
                    putExtra(EXTRA_XIAOOU_AVATAR_ENABLED, enabled)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "广播发送失败: ${e.message}")
            }
        }
    }
}

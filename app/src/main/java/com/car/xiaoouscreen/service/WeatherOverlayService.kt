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
import android.util.Log
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
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
import com.carlyrics.skin.SkinType
import com.carlyrics.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private var repository: OpenMeteoRepository? = null
    private var gpsManager: CarGpsManager? = null

    private var currentMode: WeatherOverlayWindow.Mode = WeatherOverlayWindow.Mode.TOP
    private var currentScene: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    private var isSimulating = false

    private var weatherPollJob: Job? = null
    private var timeTickerJob: Job? = null

    // 歌词业务与 5s 延时回退
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

    private val fallbackHandler = Handler(Looper.getMainLooper())
    private val fallbackToWeatherRunnable = Runnable {
        Log.i(TAG, "音乐暂停已达 5 秒，平滑回退至时间天气大时钟界面")
        if (PrefsManager.isStandaloneLyricsEnabled()) {
            overlayWindow?.switchToWeatherPage(smooth = true)
        } else {
            overlayWindow?.setCanvasLyricsActive(false, smooth = true)
        }
    }

    private val gearListener = object : CarGearManager.GearListener {
        override fun onGearChanged(gear: String, rawValue: Int) {
            Log.i(TAG, "🚗 监听到档位变动: $gear (raw=$rawValue)，立即驱动小欧屏悬浮窗执行 3D 翻转")
            mainHandler.post {
                if (overlayWindow == null) {
                    showOverlay(WeatherOverlayWindow.Mode.TOP)
                }
                overlayWindow?.triggerGearFlip(gear)
                serviceCallback?.onGearChanged(gear)
            }
        }
    }

    private val driveModeListener = object : CarGearManager.DriveModeListener {
        override fun onDriveModeChanged(mode: String, rawValue: Int) {
            Log.i(TAG, "🏎️ 监听到驾驶模式变动: $mode (raw=$rawValue)，立即驱动小欧屏悬浮窗执行左至右擦除转场")
            mainHandler.post {
                if (overlayWindow == null) {
                    showOverlay(WeatherOverlayWindow.Mode.TOP)
                }
                overlayWindow?.triggerDriveModeSwitch(mode)
                serviceCallback?.onDriveModeChanged(mode)
            }
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "📡 收到外部/ADB控制广播: $action")
            when (action) {
                ACTION_GEAR_SWITCH_LEGACY, ACTION_SWITCH_GEAR -> {
                    val gear = intent.getStringExtra(EXTRA_GEAR_LEGACY)
                        ?: intent.getStringExtra(EXTRA_TARGET_GEAR)
                        ?: "P"
                    Log.i(TAG, "📡 广播触发切档: $gear")
                    if (overlayWindow == null) {
                        showOverlay(WeatherOverlayWindow.Mode.TOP)
                    }
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateGear(gear)
                }
                ACTION_SHOW_TOP -> showOverlay(WeatherOverlayWindow.Mode.TOP)
                ACTION_SHOW_FITNESS -> showOverlay(WeatherOverlayWindow.Mode.FITNESS)
                ACTION_HIDE -> hideOverlay()
                ACTION_TRIGGER_LIGHTNING -> overlayWindow?.triggerInstantLightning()
                ACTION_SWITCH_DRIVE_MODE -> {
                    val mode = intent.getStringExtra(EXTRA_DRIVE_MODE) ?: "SPORT"
                    Log.i(TAG, "📡 广播触发驾驶模式切换: $mode")
                    if (overlayWindow == null) {
                        showOverlay(WeatherOverlayWindow.Mode.TOP)
                    }
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateDriveMode(mode)
                }
                ACTION_SIMULATE_PRESET -> {
                    val preset = intent.getStringExtra(EXTRA_PRESET_KEY)
                    applySimulationPreset(preset)
                }
                ACTION_RESET_LIVE -> {
                    isSimulating = false
                    startLiveWeatherEngine()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("小欧屏服务运行中"))

        threeScreenClient = ThreeScreenClient(this, this).apply { bind() }
        repository = OpenMeteoRepository(this)
        gpsManager = CarGpsManager(this)

        // 初始化欧尚 Z6 实车档位与驾驶模式总线信号监听
        CarGearManager.getInstance(this).apply {
            init()
            registerListener(gearListener)
            registerDriveModeListener(driveModeListener)
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
            addAction(ACTION_RESET_LIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        startLiveWeatherEngine()
        startMusicObservation()
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
                        hideOverlay()
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
                    if (overlayWindow == null) {
                        showOverlay(WeatherOverlayWindow.Mode.TOP)
                    }
                    CarGearManager.getInstance(this).simulateGear(targetGear)
                }
                ACTION_SWITCH_DRIVE_MODE -> {
                    val targetMode = intent.getStringExtra(EXTRA_DRIVE_MODE) ?: "SPORT"
                    Log.i(TAG, "收到驾驶模式模拟切换指令: $targetMode")
                    if (overlayWindow == null) {
                        showOverlay(WeatherOverlayWindow.Mode.TOP)
                    }
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
                    startLiveWeatherEngine()
                }
                ACTION_UPDATE_LYRICS_SETTINGS -> {
                    updateLyricsRuntimeSettings()
                }
                ACTION_CHECK_LYRICS_STATE -> {
                    evaluateLyricsStateTransition()
                }
                else -> {}
            }
        }
        return START_STICKY
    }

    private fun showOverlay(mode: WeatherOverlayWindow.Mode) {
        currentMode = mode
        val targetDisplay = ThirdDisplayFinder.find(this)
        if (targetDisplay == null) {
            Log.w(TAG, "未发现可用屏幕")
            return
        }

        threeScreenClient?.requestShowFitness()

        overlayWindow?.dismiss()
        overlayWindow = WeatherOverlayWindow(this, targetDisplay, mode).apply {
            onCloseListener = {
                hideOverlay()
            }
            show()
            updateScene(currentScene)
        }

        // 立即向新挂载的悬浮窗同步已知音轨元数据与歌词
        if (lastTrackTitle.isNotEmpty()) {
            overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
            if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
            if (currentLyricLines.isNotEmpty()) {
                overlayWindow?.setLyricsLines(currentLyricLines)
                overlayWindow?.setCanvasLyrics(currentLyricLines)
            }
        }
        updateLyricsRuntimeSettings()

        evaluateLyricsStateTransition(smooth = false)
        notifyStateToCallback()
    }

    fun hideOverlay() {
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
        lyricsProgressJob?.cancel()
        overlayWindow?.dismiss()
        overlayWindow = null
        threeScreenClient?.requestHideFitness()
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

        if (track == null || !track.isPlaying) {
            isMusicPlaying = false
            // 音乐暂停或停止 -> 启动 5s 延时回退
            if (isShowing()) {
                fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                fallbackHandler.postDelayed(fallbackToWeatherRunnable, 5000L)
            }
            return
        }

        // 此时处于 PLAYING 状态
        isMusicPlaying = true
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)

        val title = track.title.trim()
        val artist = track.artist.trim()
        lastKnownPosition = track.position
        lastPositionNanos = System.nanoTime()

        // 若曲目发生了变化或此前未拉取到歌词，拉取新歌词与封面
        if (title.isNotEmpty() && (title != lastTrackTitle || artist != lastTrackArtist || currentLyricLines.isEmpty())) {
            lastTrackTitle = title
            lastTrackArtist = artist
            fetchLyricsAndCover(title, artist)
        } else {
            // 同一首歌曲更新进度与元信息
            overlayWindow?.setLyricsTrackInfo(lastTrackTitle, lastTrackArtist)
            if (lastCoverUrl != null) overlayWindow?.setLyricsCoverUrl(lastCoverUrl)
            if (currentLyricLines.isNotEmpty()) {
                overlayWindow?.setLyricsLines(currentLyricLines)
                overlayWindow?.setCanvasLyrics(currentLyricLines)
            }
        }

        if (isShowing()) {
            if (PrefsManager.isStandaloneLyricsEnabled()) {
                overlayWindow?.switchToLyricsPage(smooth = true)
            } else {
                overlayWindow?.switchToWeatherPage(smooth = false)
                if (currentLyricLines.isNotEmpty()) {
                    overlayWindow?.setCanvasLyricsActive(true, smooth = true)
                }
            }
            startLyricsProgressTicker()
        }
    }

    private fun fetchLyricsAndCover(title: String, artist: String) {
        lyricsFetchJob?.cancel()
        overlayWindow?.setLyricsTrackInfo(title, artist)

        lyricsFetchJob = serviceScope.launch(Dispatchers.IO) {
            // 1. 获取封面图
            val coverUrl = try {
                lyricsApi.fetchCover(title, artist)
            } catch (_: Exception) { null }

            lastCoverUrl = coverUrl
            withContext(Dispatchers.Main) {
                overlayWindow?.setLyricsCoverUrl(coverUrl)
            }

            // 2. 获取歌词内容 (内置偏好源、JS 歌词源瀑布流调度与本地缓存)
            val lyricResult = try {
                lyricsApi.fetchLyrics(title, artist)
            } catch (_: Exception) { null }

            val lines = lyricResult?.getOrNull()?.lines ?: emptyList()
            currentLyricLines = lines

            withContext(Dispatchers.Main) {
                overlayWindow?.setLyricsLines(lines)
                overlayWindow?.setCanvasLyrics(lines)
                if (!PrefsManager.isStandaloneLyricsEnabled() && isMusicPlaying && lines.isNotEmpty() && isShowing()) {
                    overlayWindow?.setCanvasLyricsActive(true, smooth = true)
                }
            }
        }
    }

    /**
     * 16ms (60fps) 高帧率歌词走时推演，保证逐字动效卡拉OK与跑马灯极致丝滑推进
     */
    private fun startLyricsProgressTicker() {
        lyricsProgressJob?.cancel()
        lyricsProgressJob = serviceScope.launch {
            while (isActive && isMusicPlaying) {
                val now = System.nanoTime()
                val elapsedMs = (now - lastPositionNanos) / 1_000_000L
                val currentPos = (lastKnownPosition + elapsedMs).coerceAtLeast(0L)

                if (PrefsManager.isStandaloneLyricsEnabled()) {
                    if (overlayWindow?.getCurrentPage() == WeatherOverlayWindow.ActivePage.LYRICS) {
                        overlayWindow?.updateLyricsProgress(currentPos)
                    }
                } else {
                    overlayWindow?.updateCanvasLyricsProgress(currentPos)
                }
                delay(16L)
            }
        }
    }

    /**
     * 评估当前是否应处于歌词页或天气页
     */
    fun evaluateLyricsStateTransition(smooth: Boolean = true) {
        val standaloneEnabled = PrefsManager.isStandaloneLyricsEnabled()
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)

        val track = CarLyricsApplication.recognitionEngine.currentTrack.value

        if (standaloneEnabled) {
            overlayWindow?.setCanvasLyricsActive(false, smooth = false)
            if (track != null && track.isPlaying && track.title.isNotBlank()) {
                val title = track.title.trim()
                val artist = track.artist.trim()
                lastKnownPosition = track.position
                lastPositionNanos = System.nanoTime()

                if (title != lastTrackTitle || artist != lastTrackArtist || currentLyricLines.isEmpty()) {
                    lastTrackTitle = title
                    lastTrackArtist = artist
                    fetchLyricsAndCover(title, artist)
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
                lastKnownPosition = track.position
                lastPositionNanos = System.nanoTime()

                if (title != lastTrackTitle || artist != lastTrackArtist || currentLyricLines.isEmpty()) {
                    lastTrackTitle = title
                    lastTrackArtist = artist
                    fetchLyricsAndCover(title, artist)
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
                overlayWindow?.setCanvasLyricsActive(false, smooth)
            }
        }
    }

    /**
     * 同步运行时二级设置至当前悬浮窗
     */
    fun updateLyricsRuntimeSettings() {
        val w = overlayWindow ?: return
        val shouldAvoid = (currentMode == WeatherOverlayWindow.Mode.FITNESS) && PrefsManager.isXiaoOuAvoidanceEnabled()
        w.updateLyricsAvoidance(shouldAvoid)
        w.updateCanvasAvoidance(shouldAvoid)
        w.reloadLyricsSkin(PrefsManager.getSkinType())
        w.updateLyricsFontSize(PrefsManager.getFontSizeSp())
        w.updateLyricsSplitRatio(PrefsManager.getSplitRatio())
        w.updateLyricsAlignment(PrefsManager.getLyricsAlignment())
        w.updateLyricsMarquee(PrefsManager.isMarqueeEnabled())
        w.updateLyricsScale(PrefsManager.getDpiScale())
        w.updateLyricsInfoFontScale(PrefsManager.getInfoFontScale())
        w.updateLyricsOffsetMs(PrefsManager.lyricsOffsetMs)
    }

    // ==========================================
    // 气象驱动引擎 (真实 GPS + 本地走时)
    // ==========================================
    private fun startLiveWeatherEngine() {
        gpsManager?.startLocationUpdates { lat, lon ->
            if (!isSimulating) {
                fetchNetworkWeather(lat, lon)
            }
        }

        // 15分钟轮询
        weatherPollJob?.cancel()
        weatherPollJob = serviceScope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                if (!isSimulating) {
                    val lat = gpsManager?.currentLatitude ?: 31.2304
                    val lon = gpsManager?.currentLongitude ?: 121.4737
                    fetchNetworkWeather(lat, lon)
                }
            }
        }

        // 1分钟本地昼夜监测
        timeTickerJob?.cancel()
        timeTickerJob = serviceScope.launch {
            while (isActive) {
                delay(60 * 1000L)
                if (!isSimulating) {
                    val nowUnix = System.currentTimeMillis() / 1000L
                    val mapped = WeatherSceneMapper.mapToScene(
                        wmoCode = 0,
                        nowUnix = nowUnix,
                        sunriseUnix = currentScene.sunriseUnix,
                        sunsetUnix = currentScene.sunsetUnix,
                        windSpeed = currentScene.windSpeedKmh,
                        temperature = currentScene.temperatureCelsius,
                        windDirection = currentScene.windDirectionDegree
                    )
                    if (mapped.timeOfDay != currentScene.timeOfDay) {
                        applyNewScene(mapped)
                    }
                }
            }
        }
    }

    private fun fetchNetworkWeather(lat: Double, lon: Double) {
        serviceScope.launch {
            repository?.getWeatherScene(lat, lon)?.onSuccess { newScene ->
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
    // ThreeScreenClient 回调
    // ==========================================
    override fun onBound(ok: Boolean) {
        Log.i(TAG, "三屏调度绑定状态: $ok")
    }

    override fun onShowRequest(type: String) {
        if (type == ThreeScreenClient.SOURCE_FITNESS) {
            showOverlay(currentMode)
        }
    }

    override fun onHideRequest(type: String) {
        if (type == ThreeScreenClient.SOURCE_FITNESS) {
            hideOverlay()
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
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentIntent(pi)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("小欧屏")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
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
        fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
        lyricsProgressJob?.cancel()
        lyricsFetchJob?.cancel()
        hideOverlay()
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

        const val EXTRA_TARGET_GEAR = "extra_target_gear"
        const val EXTRA_DRIVE_MODE = "mode"
        const val EXTRA_PRESET_KEY = "extra_preset"
        const val EXTRA_TIME_OF_DAY = "extra_time_of_day"
        const val EXTRA_WEATHER_STATE = "extra_weather_state"
        const val EXTRA_WIND_SPEED = "extra_wind_speed"
        const val EXTRA_IS_SLEET = "extra_is_sleet"

        var instance: WeatherOverlayService? = null
            private set
        var serviceCallback: ServiceCallback? = null

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
    }
}

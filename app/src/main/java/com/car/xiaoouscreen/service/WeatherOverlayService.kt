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
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.AmbientLightManager
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
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType
import com.carlyrics.CarLyricsApplication
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.model.NotificationLyric
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

    // 歌词业务与状态机管理
    enum class LyricsMatchStatus {
        IDLE,
        WAITING_DURATION,
        FETCHING_PRIMARY,
        MATCHED,
        RETRYABLE_PENDING,
        PERMANENT_NOT_FOUND
    }

    private val lyricsApi = LyricsApiManager()
    private var lastTrackTitle = ""
    private var lastTrackArtist = ""
    private var inFlightFetchTrackKey: String? = null
    private var lastFetchedTrackKey: String? = null
    private var lastCoverUrl: String? = null
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var lastKnownPosition: Long = 0L
    private var lastPositionNanos: Long = 0L
    private var currentTrackSource: com.carlyrics.model.MusicSource = com.carlyrics.model.MusicSource.UNKNOWN
    private var isMusicPlaying = false
    private var isFallbackPending = false
    private val pauseDebounceHandler = Handler(Looper.getMainLooper())
    private var pendingPausedTrack: CurrentTrack? = null

    /**
     * Bug1修复：蓝牙静音/暂停振荡抑制。
     * 车机静音按钮会让 AVRCP 与 MediaSession 形成 play/pause 高频交替振荡（约每 1s 一次），
     * 此时 pauseDebounceRunnable 确认了真实暂停后，设置一个短暂抑制窗口。
     * 在窗口内收到的 BT isPlaying=true 且进度未显著前进时，视为振荡噪音，拒绝恢复走时协程。
     */
    private var btPauseConfirmedAtMs: Long = 0L
    private var btPauseConfirmedPos: Long = 0L
    private val BT_OSCILLATION_SUPPRESS_MS = 1200L   // 确认暂停后 1.2s 抑制窗口
    private val BT_OSCILLATION_POS_THRESHOLD_MS = 1500L  // 进度前进超过此值才认定为真实恢复

    // 智能退避与弱网自愈调度状态
    private var currentMatchStatus = LyricsMatchStatus.IDLE
    private var durationWaitJob: Job? = null
    private var retryJob: Job? = null
    private var pendingOfflineTrack: CurrentTrack? = null
    private var primaryRetryCount = 0
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val pauseDebounceRunnable = Runnable {
        val pausedTrack = pendingPausedTrack ?: return@Runnable
        pendingPausedTrack = null
        if (isMusicPlaying) return@Runnable

        AppLogger.i(TAG, "⏸️ 音乐暂停已确认为真实暂停 (350ms防抖通过)，执行定格与 2s 回退大时钟倒计时 (pos=${pausedTrack.position})")

        // Bug1修复：记录确认暂停的时刻与位置，用于后续 BT 振荡抑制判断
        btPauseConfirmedAtMs = android.os.SystemClock.elapsedRealtime()
        btPauseConfirmedPos = pausedTrack.position

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

        // 4. 音乐暂停防抖(350ms)后延时 1650ms 回退（自停播起累计 2 秒精确回退时钟，绝不触发无谓的窗口重建）
        if (isShowing()) {
            if (!isFallbackPending) {
                isFallbackPending = true
                fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                fallbackHandler.postDelayed(fallbackToWeatherRunnable, 1650L)
            }
        }
    }

    private var lyricsProgressJob: Job? = null
    private var lyricsFetchJob: Job? = null
    private var nativeCoverFallbackJob: Job? = null

    private fun getEstimatedCurrentTrackPositionMs(): Long {
        if (!isMusicPlaying || lastPositionNanos <= 0L) {
            return lastKnownPosition.coerceAtLeast(0L)
        }
        val elapsedMs = ((System.nanoTime() - lastPositionNanos) / 1_000_000L).coerceAtLeast(0L)
        // 蓝牙连接下（非0基准位置），若底层超过 2.2s 未收到新进度推动，封顶推演防止静音暂停后歌词一路飘走
        val effectiveElapsedMs = if (currentTrackSource == com.carlyrics.model.MusicSource.BLUETOOTH && lastKnownPosition > 0L) {
            elapsedMs.coerceAtMost(2200L)
        } else {
            elapsedMs
        }
        return (lastKnownPosition + effectiveElapsedMs).coerceAtLeast(0L)
    }

    private val fallbackHandler = Handler(Looper.getMainLooper())
    private val fallbackToWeatherRunnable = Runnable {
        AppLogger.i(TAG, "音乐暂停已达 2 秒，平滑回退至时间天气大时钟界面")
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
    private var pendingReassertForce: Boolean = false
    private var lastTrackReassertKey: String? = null
    private var lastTrackReassertTimeMs: Long = 0L
    private val reassertTopHandler = Handler(Looper.getMainLooper())
    private val reassertTopRunnable = Runnable {
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            if (!CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
                // 车规最高安全准则：若当前副屏正显示云眼后视镜，绝对不可打断或遮挡！
                val client = threeScreenClient
                val currentView = if (client != null && client.isBound) client.getCurrentView() else null
                if (client != null && client.isBound) {
                    if (client.isCloudEyeView(currentView)) {
                        AppLogger.w(TAG, "🛡️ [安全避让] 置顶重登执行前检测到云眼后视镜正处于前台($currentView)，立即取消置顶，确保后视安全！")
                        isCloudEyeActive = true
                        pendingReassertForce = false
                        return@Runnable
                    }
                    val isRecentCloudEyeExit = SystemClock.elapsedRealtime() - lastCloudEyeCloseTimeMs < 3500L
                    if (!client.isMediaView(currentView) && !isRecentCloudEyeExit) {
                        // 车机三屏前台并非原厂媒体弹窗（如 NEBULAR_COMPASS），无需反复销毁重建 Presentation 加载天气/时钟资源
                        pendingReassertForce = false
                        lastObservedThreeScreenView = currentView
                        AppLogger.d(TAG, "🛡️ [防抢置顶] 三屏当前前台($currentView)未出现原厂媒体遮挡，跳过无谓窗口重建")
                        return@Runnable
                    }
                }
                val force = pendingReassertForce
                pendingReassertForce = false
                AppLogger.i(TAG, "🛡️ [辅助方案B·防抢置顶] 执行小欧副屏无缝重登置顶 (force=$force)")
                // 重登前先将精确实时毫秒进度同步到 overlayWindow 缓存，确保新 Presentation 在 show() 前即定位到精准行，0 帧闪回
                val exactPos = getEstimatedCurrentTrackPositionMs()
                if (exactPos > 0L) {
                    overlayWindow?.updateLyricsProgress(exactPos)
                    overlayWindow?.updateCanvasLyricsProgress(exactPos)
                }
                val ok = overlayWindow?.reassertTop(force = force) ?: false
                if (ok) {
                    lastTrackReassertTimeMs = SystemClock.elapsedRealtime()
                    // 同步记录当前三屏前台视图，避免巡检狗把已接管的同一媒体视图当成新遮挡而死循环重登
                    if (client != null && client.isBound) {
                        lastObservedThreeScreenView = client.getCurrentView()
                    }
                }
            } else {
                pendingReassertForce = false
            }
        } else {
            pendingReassertForce = false
        }
    }

    private fun scheduleReassertTop(delayMs: Long, force: Boolean = false) {
        if (force) pendingReassertForce = true
        reassertTopHandler.removeCallbacks(reassertTopRunnable)
        reassertTopHandler.postDelayed(reassertTopRunnable, delayMs)
    }

    private fun handleSubscreenFocusLost() {
        if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) return
        if (CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
            AppLogger.d(TAG, "当前处于车载通话活跃状态，允许系统浮层展示，跳过失焦重登")
            return
        }
        if (SystemClock.elapsedRealtime() - lastTrackReassertTimeMs < 2000L) {
            AppLogger.d(TAG, "🛡️ [辅助方案B] 距上次置顶重登 <2s，跳过窗口切换伴随的瞬态失焦回调")
            return
        }
        AppLogger.i(TAG, "🛡️ [辅助方案B] 检测到小欧副屏窗口失焦 (被系统窗口覆盖)，预约 450ms 后执行置顶重登守护...")
        scheduleReassertTop(450L, force = false)
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
            val previousGear = lastReportedGear
            if (previousGear == gear) {
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
                // 从倒车 R 挡切回前进/驻车挡时，倒车影像退场，调度 600ms 复核置顶接管
                if (previousGear == "R" && gear != "R") {
                    mainHandler.postDelayed({
                        checkOcclusionAndRecover("倒车影像退场复核")
                    }, 600L)
                }
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

    // 实车车外温度融合状态：保留纯在线气象场景，支持开启/关闭开关 0 延迟瞬切，0 网络损耗
    private var latestOnlineScene: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    @Volatile
    private var latestCarOutsideTempC: Float? = null

    private val outsideTempListener = object : CarGearManager.OutsideTempListener {
        override fun onOutsideTempChanged(tempCelsius: Float) {
            latestCarOutsideTempC = tempCelsius
            mainHandler.post {
                overlayWindow?.setCarOutsideTempC(tempCelsius)
                if (!isSimulating && PrefsManager.isUseCarOutsideTempEnabled()) {
                    refreshEffectiveSceneDisplay(saveOnlineCache = false)
                }
            }
        }
    }

    private val cabinTempListener = object : CarGearManager.CabinTempListener {
        override fun onCabinTempChanged(tempCelsius: Float) {
            mainHandler.post {
                overlayWindow?.setCarCabinTempC(tempCelsius)
            }
        }
    }

    private var batteryPollJob: Job? = null
    private var motorPowerPollJob: Job? = null
    private val batteryInfoListener = object : CarGearManager.BatteryInfoListener {
        override fun onBatteryInfoChanged(info: CarGearManager.BatteryInfo) {
            mainHandler.post {
                overlayWindow?.setBatteryInfo(info)
            }
        }
    }

    private val motorPowerListener = object : CarGearManager.MotorPowerListener {
        override fun onMotorPowerChanged(info: CarGearManager.MotorPowerInfo) {
            mainHandler.post {
                overlayWindow?.setMotorPowerInfo(info)
            }
        }
    }

    private val rpmListener = object : CarGearManager.RpmListener {
        override fun onRpmChanged(info: CarGearManager.RpmInfo) {
            mainHandler.post {
                overlayWindow?.setRpmInfo(info)
            }
        }
    }

    private val laneChangeAlertListener = object : CarGearManager.LaneChangeAlertListener {
        override fun onLaneChangeAlertChanged(alert: CarGearManager.LaneChangeAlert) {
            mainHandler.post {
                overlayWindow?.setLaneChangeAlert(alert)
            }
        }
    }

    private val turnSignalListener = object : CarGearManager.TurnSignalListener {
        override fun onTurnSignalChanged(info: CarGearManager.TurnSignalInfo) {
            mainHandler.post {
                overlayWindow?.setTurnSignalInfo(info)
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
                ACTION_SIMULATE_CLOUDEYE_EXIT -> {
                    AppLogger.i(TAG, "📡 接收到模拟云眼退出广播指令")
                    isCloudEyeActive = false
                    lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                    checkOcclusionAndRecover("ADB模拟云眼退出")
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
                    CarGearManager.getInstance(this@WeatherOverlayService).refreshOutsideTempNow()
                    startLiveWeatherEngine(force = true)
                }
                ACTION_UPDATE_CAR_OUTSIDE_TEMP -> {
                    val enabled = intent.getBooleanExtra(EXTRA_USE_CAR_OUTSIDE_TEMP, PrefsManager.isUseCarOutsideTempEnabled())
                    AppLogger.i(TAG, "📡 接收到实车车外温度开关更新: enabled=$enabled, currentCarTemp=$latestCarOutsideTempC")
                    if (enabled) {
                        CarGearManager.getInstance(this@WeatherOverlayService).refreshOutsideTempNow()
                    }
                    if (!isSimulating) {
                        refreshEffectiveSceneDisplay(saveOnlineCache = false)
                    }
                    Unit
                }
                ACTION_UPDATE_LIQUID_GLASS -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LIQUID_GLASS_ENABLED, PrefsManager.isLiquidGlassClockEnabled())
                    Log.i(TAG, "📡 接收到液态玻璃时钟状态更新广播: enabled=$enabled")
                    overlayWindow?.setLiquidGlassEnabled(enabled)
                }
                ACTION_UPDATE_TURN_SIGNAL_SYNC -> {
                    val enabled = intent.getBooleanExtra(EXTRA_TURN_SIGNAL_SYNC_ENABLED, PrefsManager.isTurnSignalSyncEnabled())
                    Log.i(TAG, "📡 接收到转向灯三边律动光效状态更新广播: enabled=$enabled")
                    overlayWindow?.setTurnSignalSyncEnabled(enabled)
                }
                ACTION_UPDATE_LANE_CHANGE_ASSIST -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LANE_CHANGE_ASSIST_ENABLED, PrefsManager.isLaneChangeAssistEnabled())
                    Log.i(TAG, "📡 接收到变道辅助盲区光幕状态更新广播: enabled=$enabled")
                    overlayWindow?.setLaneChangeAssistEnabled(enabled)
                }
                ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 接收到左下角档位HUD状态更新广播: enabled=$enabled")
                    overlayWindow?.setBottomLeftGearHudEnabled(enabled)
                }
                ACTION_UPDATE_MINI_GEAR_POSITION -> {
                    val position = intent.getIntExtra(EXTRA_MINI_GEAR_POSITION, PrefsManager.getMiniGearPosition())
                    Log.i(TAG, "📡 接收到微型档位位置更新指令: position=$position")
                    overlayWindow?.setMiniGearPosition(position)
                }
                ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_MINI_GEAR_REALTIME_COLOR, PrefsManager.isMiniGearRealtimeColorEnabled())
                    Log.i(TAG, "📡 接收到微型档位实时背景自适应指令: enabled=$enabled")
                    overlayWindow?.setMiniGearRealtimeColor(enabled)
                }
                ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 接收到小欧形象与避让状态更新指令: enabled=$enabled")
                    overlayWindow?.updateAvatarAndAvoidance(enabled)
                    updateMotorPowerPollingState()
                }
                ACTION_UPDATE_SLOT_LAYOUT -> {
                    val slotAId = intent.getStringExtra(EXTRA_SLOT_A) ?: PrefsManager.getXiaoOuSlotA()
                    val slotCId = intent.getStringExtra(EXTRA_SLOT_C) ?: PrefsManager.getXiaoOuSlotC()
                    val slotA = XiaoOuSlotType.fromId(slotAId, XiaoOuSlotType.WEATHER)
                    val slotC = XiaoOuSlotType.fromId(slotCId, XiaoOuSlotType.ALBUM_COVER)
                    Log.i(TAG, "📡 接收到小欧屏 A/C 分区布局更新指令: slotA=$slotA, slotC=$slotC")
                    PrefsManager.setXiaoOuSlotA(slotA.id)
                    PrefsManager.setXiaoOuSlotC(slotC.id)
                    overlayWindow?.updateSlotLayout(slotA, slotC, smooth = true)
                    updateMotorPowerPollingState()
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
                    val cover = intent.getStringExtra(EXTRA_MUSIC_COVER)
                    val pkg = intent.getStringExtra("extra_music_package") ?: ""
                    val mediaId = intent.getStringExtra("extra_music_media_id") ?: ""
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = duration, nativeCoverUrl = cover, packageName = pkg, mediaId = mediaId))
                }
                ACTION_SIMULATE_BATTERY -> {
                    val voltage = if (intent.hasExtra(EXTRA_BATTERY_VOLTAGE)) {
                        intent.getFloatExtra(EXTRA_BATTERY_VOLTAGE, -1f).takeIf { it > 0f }
                    } else null
                    val soh = if (intent.hasExtra(EXTRA_BATTERY_SOH)) {
                        intent.getIntExtra(EXTRA_BATTERY_SOH, -1).takeIf { it in 1..100 }
                    } else null
                    val soc = if (intent.hasExtra(EXTRA_BATTERY_SOC)) {
                        intent.getIntExtra(EXTRA_BATTERY_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val low = intent.getBooleanExtra(EXTRA_BATTERY_LOW, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateBattery(voltage, soh, soc, low)
                }
                ACTION_SIMULATE_TEMPS -> {
                    val outT = if (intent.hasExtra(EXTRA_OUTSIDE_TEMP)) {
                        intent.getFloatExtra(EXTRA_OUTSIDE_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val cabT = if (intent.hasExtra(EXTRA_CABIN_TEMP)) {
                        intent.getFloatExtra(EXTRA_CABIN_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateTemps(outT, cabT)
                }
                ACTION_SIMULATE_GFORCE -> {
                    val gx = intent.getFloatExtra(EXTRA_GFORCE_X, 0f)
                    val gy = intent.getFloatExtra(EXTRA_GFORCE_Y, 0f)
                    val holdMs = intent.getLongExtra(EXTRA_GFORCE_HOLD_MS, 15_000L)
                    com.car.xiaoouscreen.car.CarGForceManager.getInstance(this@WeatherOverlayService).simulateGForce(gx, gy, holdMs)
                }
                ACTION_SIMULATE_MOTOR_POWER -> {
                    val kw = if (intent.hasExtra(EXTRA_MOTOR_POWER_KW)) {
                        intent.getFloatExtra(EXTRA_MOTOR_POWER_KW, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val hvSoc = if (intent.hasExtra(EXTRA_MOTOR_HV_SOC)) {
                        intent.getIntExtra(EXTRA_MOTOR_HV_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val plugCharging = intent.getBooleanExtra(EXTRA_MOTOR_PLUG_CHARGING, false)
                    val resetPeaks = intent.getBooleanExtra(EXTRA_MOTOR_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateMotorPower(kw, hvSoc, plugCharging, resetPeaks)
                }
                ACTION_SIMULATE_RPM -> {
                    val eng = if (intent.hasExtra(EXTRA_ENGINE_RPM)) {
                        intent.getIntExtra(EXTRA_ENGINE_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val mot = if (intent.hasExtra(EXTRA_MOTOR_RPM)) {
                        intent.getIntExtra(EXTRA_MOTOR_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val resetPeaks = intent.getBooleanExtra(EXTRA_RPM_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateRpm(eng, mot, resetPeaks)
                }
                ACTION_SIMULATE_LANE_CHANGE -> {
                    val left = intent.getIntExtra(EXTRA_LANE_LEFT_WARNING, 0)
                    val right = intent.getIntExtra(EXTRA_LANE_RIGHT_WARNING, 0)
                    val bsdOn = intent.getBooleanExtra(EXTRA_LANE_BSD_ON, true)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateLaneChangeAlert(left, right, bsdOn)
                }
                ACTION_SIMULATE_TURN_SIGNAL -> {
                    val left = intent.getBooleanExtra(EXTRA_TURN_LEFT, false)
                    val right = intent.getBooleanExtra(EXTRA_TURN_RIGHT, false)
                    val hazard = intent.getBooleanExtra(EXTRA_TURN_HAZARD, false)
                    val avmDir = intent.getIntExtra(EXTRA_TURN_AVM, 0)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateTurnSignal(left, right, hazard, avmDir)
                }
                ACTION_SIMULATE_PREEMPT -> {
                    val reason = intent.getStringExtra("reason") ?: "ADB模拟原车抢占"
                    val delay = intent.getLongExtra("delay", 5000L)
                    AppLogger.i(TAG, "📡 接收到模拟原车功能抢占指令: reason=$reason, delay=${delay}ms")
                    AmbientLightManager.preempt(reason, autoReclaimDelayMs = delay)
                }
                ACTION_RECLAIM_AMBIENT_LIGHT -> {
                    val origin = intent.getStringExtra("origin") ?: "ADB指令重新接管"
                    AppLogger.i(TAG, "📡 接收到重新接管氛围灯指令: origin=$origin")
                    AmbientLightManager.reclaimAndSync(origin)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "🌙 车机屏幕息屏/熄火，立即同步日志并挂起小欧屏高帧率物理引擎与歌词走时")
                    com.carlyrics.util.AppLogger.flushSync()
                    overlayWindow?.pause()
                    lyricsProgressJob?.cancel()
                    lyricsProgressJob = null
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
                "android.intent.action.ACC_ON", "com.geely.intent.action.ACC_ON",
                Intent.ACTION_POWER_CONNECTED -> {
                    AppLogger.i(TAG, "☀️ 车机屏幕亮屏/上车点火唤醒 ($action)，立即恢复物理引擎并检查天气实况刷新")
                    overlayWindow?.resume()
                    if (!isSimulating) {
                        CarGearManager.getInstance(this@WeatherOverlayService).refreshOutsideTempNow()
                        fusionEngine?.onDeviceWakeUp(action)
                    }
                    CarGearManager.getInstance(this@WeatherOverlayService).refreshBatteryInfoNow()
                    CarGearManager.getInstance(this@WeatherOverlayService).refreshCabinTempNow()
                    if (isMotorPowerSlotConfigured()) {
                        CarGearManager.getInstance(this@WeatherOverlayService).refreshMotorPowerNow()
                    }
                    if (isRpmSlotConfigured()) {
                        CarGearManager.getInstance(this@WeatherOverlayService).refreshRpmNow()
                    }
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

    // ==========================================
    // 原厂副屏媒体 (蓝牙音乐/酷狗/自带播放器) 争抢多维感知与自愈哨兵
    // ==========================================
    private var nativeMediaObserver: ContentObserver? = null
    private var nativeMediaReceiver: BroadcastReceiver? = null
    private var occlusionWatchdogJob: Job? = null
    private var lastObservedThreeScreenView: String? = null
    private var lastA2dpPlayingState: Int = -1
    private var lastMediaSentinelTriggerMs: Long = 0L

    // ==========================================
    // 云眼后视镜与方控按键车载特征事件感知
    // ==========================================
    @Volatile private var isCloudEyeActive = false
    private var lastCloudEyeCloseTimeMs = 0L
    private var carFeatureEventReceiver: BroadcastReceiver? = null

    private fun setupCarFeatureEventReceiver() {
        if (carFeatureEventReceiver != null) return
        carFeatureEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) return
                if (CarCallManager.getInstance(this@WeatherOverlayService).getCurrentCallInfo().isCallActive) return

                when (action) {
                    "com.coagent.intent.action.KEY_CHANGED" -> {
                        val keyCode = intent.getStringExtra("Key_code") ?: return
                        val keyState = intent.getStringExtra("Key_state") ?: ""
                        AppLogger.d(TAG, "📡 [方控广播] 监听到方控按键: code=$keyCode, state=$keyState")

                        val isCloudEyeKey = (keyCode.equals("AVM", ignoreCase = true) && keyState.equals("LONG_EVENT", ignoreCase = true)) ||
                            keyCode.equals("CLOUDEYE_R", ignoreCase = true) ||
                            keyCode.equals("CLOUDEYE", ignoreCase = true)

                        if (isCloudEyeKey) {
                            val client = threeScreenClient
                            val currentView = client?.getCurrentView()
                            val isCurrentlyShowing = isCloudEyeActive || client?.isCloudEyeView(currentView) == true

                            if (isCurrentlyShowing) {
                                // 当前处于云眼显示中，本次方控按键为【关闭云眼】
                                AppLogger.i(TAG, "🛡️ [方控感知] 捕获到方控按键 ($keyCode/$keyState) 关闭云眼后视镜，立即调度置顶接管！")
                                isCloudEyeActive = false
                                lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                                scheduleReassertTop(350L, force = true)
                                mainHandler.postDelayed({
                                    checkOcclusionAndRecover("方控关闭云眼后延时复核")
                                }, 850L)
                            } else {
                                // 当前未显示云眼，本次方控按键为【开启云眼】
                                AppLogger.i(TAG, "🛡️ [方控感知] 捕获到方控按键 ($keyCode/$keyState) 开启云眼后视镜，主动安全避让")
                                isCloudEyeActive = true
                                reassertTopHandler.removeCallbacks(reassertTopRunnable)
                            }
                        }
                    }
                    "android.intent.action.CLOUDEYE" -> {
                        val client = threeScreenClient
                        val currentView = client?.getCurrentView()
                        AppLogger.i(TAG, "📡 [云眼事件广播] 收到 $action, currentView=$currentView, isCloudEyeActive=$isCloudEyeActive")
                        if (client?.isCloudEyeView(currentView) == true) {
                            isCloudEyeActive = true
                            reassertTopHandler.removeCallbacks(reassertTopRunnable)
                        }
                    }
                    "chinatsp.action.virtual.tts" -> {
                        val extra = intent.getStringExtra("EXTRA_CLOUD_INTO_BY_WHEEL")
                        if (extra != null) {
                            AppLogger.i(TAG, "📡 [云眼TTS广播] 收到 EXTRA_CLOUD_INTO_BY_WHEEL, 云眼启动中")
                            isCloudEyeActive = true
                            reassertTopHandler.removeCallbacks(reassertTopRunnable)
                        }
                    }
                    "ACTION_FOR_FINISH_CLOUDEYE_ACTIVITY", "ACTION_FOR_FINISH_ACTIVITY" -> {
                        AppLogger.i(TAG, "📡 [云眼关闭广播] 收到 $action, 准备接管小欧副屏")
                        isCloudEyeActive = false
                        lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                        scheduleReassertTop(300L, force = true)
                        mainHandler.postDelayed({
                            checkOcclusionAndRecover("云眼关闭广播延时复核")
                        }, 800L)
                    }
                    ACTION_SIMULATE_CLOUDEYE_EXIT -> {
                        AppLogger.i(TAG, "📡 [ADB模拟] 收到模拟云眼退出广播，执行置顶接管")
                        isCloudEyeActive = false
                        lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                        checkOcclusionAndRecover("ADB模拟云眼退出")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.coagent.intent.action.KEY_CHANGED")
            addAction("android.intent.action.CLOUDEYE")
            addAction("chinatsp.action.virtual.tts")
            addAction("ACTION_FOR_FINISH_CLOUDEYE_ACTIVITY")
            addAction("ACTION_FOR_FINISH_ACTIVITY")
            addAction(ACTION_SIMULATE_CLOUDEYE_EXIT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(carFeatureEventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(carFeatureEventReceiver, filter)
            }
            AppLogger.i(TAG, "🛡️ 车载特征事件与方控按键监听注册成功")
        } catch (e: Exception) {
            Log.w(TAG, "注册车载特征事件监听异常: ${e.message}")
        }
    }

    private fun teardownCarFeatureEventReceiver() {
        carFeatureEventReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            carFeatureEventReceiver = null
        }
    }

    private fun setupNativeMediaSentinel() {
        if (nativeMediaObserver == null) {
            nativeMediaObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
                        if (CarCallManager.getInstance(this@WeatherOverlayService).getCurrentCallInfo().isCallActive) return
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastMediaSentinelTriggerMs < 2500L) return
                        lastMediaSentinelTriggerMs = now
                        AppLogger.i(TAG, "🛡️ [设置哨兵] 捕获到原厂副屏媒体设置变动 ($uri)，调度 450ms 置顶接管")
                        scheduleReassertTop(450L, force = true)
                    }
                }
            }
            try {
                val cr = contentResolver
                listOf("cb_thirdscreen_lrc_show", "kgcc_thirdscreen_lrc_show", "showLrc").forEach { key ->
                    Settings.System.getUriFor(key)?.let { uri ->
                        cr.registerContentObserver(uri, false, nativeMediaObserver!!)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "注册原厂副屏媒体设置监听异常: ${e.message}")
            }
        }

        if (nativeMediaReceiver == null) {
            nativeMediaReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) return
                    if (CarCallManager.getInstance(this@WeatherOverlayService).getCurrentCallInfo().isCallActive) return

                    // 蓝牙 A2DP 播放状态广播：严格边沿过滤，仅当由非播放跃迁为 STATE_PLAYING (10) 时才触发守护，
                    // 彻底拦截 state=11 (NOT_PLAYING) 或重复心跳引发的无谓重登
                    if (action == "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED") {
                        val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                        val prevState = lastA2dpPlayingState
                        lastA2dpPlayingState = state
                        if (state != 10 || prevState == 10) {
                            return
                        }
                    }

                    // 冷却去重：避免与 onTrackStateChanged 的切歌/起播重登叠加造成连闪
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMediaSentinelTriggerMs < 3000L || now - lastTrackReassertTimeMs < 2500L) {
                        return
                    }
                    lastMediaSentinelTriggerMs = now
                    AppLogger.d(TAG, "🛡️ [媒体广播哨兵] 监听到车机原厂媒体起播/切源广播: $action，预约 500ms 置顶守护")
                    scheduleReassertTop(500L, force = false)
                }
            }
            try {
                val filter = IntentFilter().apply {
                    addAction("com.chinatsp.media.playstate")
                    addAction("com.chinatsp.media.trackinfo")
                    addAction("com.chinatsp.music.service.status")
                    addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED")
                    // 严禁监听 android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT！
                    // 实车日志证实该广播每秒都会发送一次播放进度心跳，监听它会导致每秒无限销毁重建 Presentation！
                    // 真正的蓝牙切歌/起播由 MusicRecognitionEngine -> onTrackStateChanged 精准去重后调度。
                    addAction("com.kugou.ultimatetv.action.MEDIA_INFO")
                }
                registerReceiver(nativeMediaReceiver, filter)
            } catch (e: Exception) {
                Log.w(TAG, "注册原厂媒体广播监听异常: ${e.message}")
            }
        }
    }

    private fun teardownNativeMediaSentinel() {
        nativeMediaObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            nativeMediaObserver = null
        }
        nativeMediaReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            nativeMediaReceiver = null
        }
    }

    private fun startOcclusionWatchdog() {
        occlusionWatchdogJob?.cancel()
        occlusionWatchdogJob = serviceScope.launch {
            while (isActive) {
                // 动态巡检频度：当云眼后视镜正处于前台时，提升为 500ms 高频巡检，实现毫秒级捕获退出边沿；
                // 常态下保持 2500ms 轻量巡检，兼顾极低 CPU 占用与车机续航
                val pollInterval = if (isCloudEyeActive) 500L else 2500L
                delay(pollInterval)
                if (!isShowing() || currentMode != WeatherOverlayWindow.Mode.FITNESS) {
                    continue
                }
                if (CarCallManager.getInstance(this@WeatherOverlayService).getCurrentCallInfo().isCallActive) {
                    continue
                }
                val client = threeScreenClient
                if (client != null && client.isBound) {
                    val currentView = client.getCurrentView()
                    val prevView = lastObservedThreeScreenView
                    val isCurrentCloudEye = client.isCloudEyeView(currentView)
                    val wasCloudEye = isCloudEyeActive || client.isCloudEyeView(prevView)

                    // 1. 云眼后视镜状态维护与下降沿检测
                    if (isCurrentCloudEye) {
                        isCloudEyeActive = true
                        lastObservedThreeScreenView = currentView
                        continue
                    } else if (wasCloudEye) {
                        // 云眼后视镜退场下降沿捕获！(包含用户屏幕点击关闭、方控关闭、超时自动退出、语音关闭等所有场景)
                        isCloudEyeActive = false
                        lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                        lastObservedThreeScreenView = currentView
                        AppLogger.w(TAG, "🚨 [巡检心跳·边沿捕获] 探测到云眼后视镜退场 (前台跃迁: $prevView -> $currentView)，立即执行小欧屏登顶接管！")
                        checkOcclusionAndRecover("巡检心跳捕获云眼退场")
                        // 600ms 后进行二次兜底复核 (防止系统 NotifyService 紧接着弹出 VOICE_TTS / NEBULAR_COMPASS 再次压制)
                        mainHandler.postDelayed({
                            checkOcclusionAndRecover("云眼退场后二次复核")
                        }, 600L)
                        continue
                    }

                    // 2. 原厂媒体视图上升沿检测 (媒体打断接管)
                    // 核心边沿触发 (Edge-Triggering) 防死循环机制：
                    // 我方在 FITNESS 模式下重登 2037 Presentation 不会改变三屏中间件记录的 currentView，
                    // 原车酷狗开启后 getCurrentView() 会持续返回 "MEDIA_MUSIC"。
                    // 只有当三屏视图【首次跃迁进入媒体视图】或【在不同媒体视图之间切换】时，说明原厂刚刚弹出了新媒体窗，
                    // 才执行唯一一次置顶接管！接管后记录 lastObservedThreeScreenView，绝不再每 3 秒死循环重登！
                    if (client.isMediaView(currentView)) {
                        if (currentView != prevView) {
                            lastObservedThreeScreenView = currentView
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTrackReassertTimeMs < 2000L) {
                                AppLogger.d(TAG, "🛡️ [巡检心跳] 三屏跃迁至媒体($currentView)，但 2s 内刚完成置顶重登，跳过重复接管")
                            } else {
                                AppLogger.w(TAG, "🚨 [巡检心跳·边沿捕获] 探测到副屏由($prevView)新跃迁至原厂媒体($currentView)，执行单次登顶接管！")
                                checkOcclusionAndRecover("巡检心跳边沿捕获媒体切换")
                            }
                        }
                    } else if (client.isOccludingView(prevView) && !client.isOccludingView(currentView)) {
                        // 3. 其他系统高优先独占业务 (APA 泊车/休息模式等) 退场下降沿检测
                        lastObservedThreeScreenView = currentView
                        AppLogger.i(TAG, "🛡️ [巡检心跳·边沿捕获] 探测到系统独占高优业务退场 ($prevView -> $currentView)，执行登顶接管")
                        checkOcclusionAndRecover("系统高优先独占业务退场")
                    } else {
                        // 离开媒体视图（如切回指南针/语音）时刷新状态，以便后续再次进入媒体视图时可重新捕获上升沿
                        lastObservedThreeScreenView = currentView
                    }
                }
            }
        }
    }

    private fun stopOcclusionWatchdog() {
        occlusionWatchdogJob?.cancel()
        occlusionWatchdogJob = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceStartTimeMs = SystemClock.elapsedRealtime()

        // 优先从本地持久化加载上一次的实况天气，并立即代入当前系统时间重算昼夜时相，杜绝启动首帧画面跳变
        val cachedSceneJson = PrefsManager.getLastWeatherSceneJson()
        if (!cachedSceneJson.isNullOrBlank()) {
            try {
                val cached = com.carlyrics.util.SharedNetwork.gson.fromJson(cachedSceneJson, WeatherSceneConfig::class.java)
                if (cached != null) {
                    val nowUnix = System.currentTimeMillis() / 1000L
                    currentScene = WeatherSceneMapper.updateSceneTimeline(
                        current = cached,
                        nowUnix = nowUnix,
                        updatedMinuteRainSummary = cached.minuteRainSummary,
                        isMinuteRainActive = cached.isMinuteRainActive
                    )
                    latestOnlineScene = currentScene
                    Log.i(TAG, "📦 启动预载上次缓存天气场景(已重算当前昼夜): ${currentScene.weatherState}, 时段=${currentScene.timeOfDay}, ${currentScene.temperatureCelsius}°C")
                }
            } catch (_: Exception) {}
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("小欧屏服务运行中"))

        threeScreenClient = ThreeScreenClient(this, this).apply { bind() }
        gpsManager = CarGpsManager.getInstance(this)
        val openMeteoRepo = OpenMeteoRepository(this)
        val qWeatherRepo = QWeatherRepository(this)
        fusionEngine = WeatherFusionEngine(
            context = this,
            scope = serviceScope,
            openMeteoRepo = openMeteoRepo,
            qWeatherRepo = qWeatherRepo,
            gpsManager = gpsManager!!
        )

        // 初始化欧尚 Z6 实车档位、驾驶模式、车外环境温度与 12V 蓄电池总线信号监听
        CarGearManager.getInstance(this).apply {
            init()
            registerListener(gearListener)
            registerDriveModeListener(driveModeListener)
            registerOutsideTempListener(outsideTempListener)
            registerCabinTempListener(cabinTempListener)
            registerBatteryInfoListener(batteryInfoListener)
            registerMotorPowerListener(motorPowerListener)
            registerRpmListener(rpmListener)
            registerLaneChangeAlertListener(laneChangeAlertListener)
            registerTurnSignalListener(turnSignalListener)
            latestCarOutsideTempC = getCurrentOutsideTempC()
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
            addAction(ACTION_UPDATE_TURN_SIGNAL_SYNC)
            addAction(ACTION_UPDATE_LANE_CHANGE_ASSIST)
            addAction(ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD)
            addAction(ACTION_UPDATE_MINI_GEAR_POSITION)
            addAction(ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR)
            addAction(ACTION_UPDATE_XIAOOU_AVATAR)
            addAction(ACTION_UPDATE_SLOT_LAYOUT)
            addAction(ACTION_UPDATE_CAR_OUTSIDE_TEMP)
            addAction(ACTION_CALL_EVENT)
            addAction(ACTION_SIMULATE_MUSIC)
            addAction(ACTION_SIMULATE_BATTERY)
            addAction(ACTION_SIMULATE_TEMPS)
            addAction(ACTION_SIMULATE_GFORCE)
            addAction(ACTION_SIMULATE_MOTOR_POWER)
            addAction(ACTION_SIMULATE_RPM)
            addAction(ACTION_SIMULATE_LANE_CHANGE)
            addAction(ACTION_SIMULATE_TURN_SIGNAL)
            addAction(ACTION_CHECK_OCCLUSION)
            addAction(ACTION_SIMULATE_CLOUDEYE_EXIT)
            addAction(ACTION_SIMULATE_PREEMPT)
            addAction(ACTION_RECLAIM_AMBIENT_LIGHT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.ACC_ON")
            addAction("com.geely.intent.action.ACC_ON")
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        startLiveWeatherEngine()
        startMusicObservation()
        registerNetworkCallback()

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

        // 初始化原厂副屏媒体与车载特征/方控感知哨兵
        setupNativeMediaSentinel()
        setupCarFeatureEventReceiver()
        if (PrefsManager.getOverlayVisible()) {
            startOcclusionWatchdog()
        }

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
                    CarGearManager.getInstance(this@WeatherOverlayService).refreshOutsideTempNow()
                    startLiveWeatherEngine(force = true)
                }
                ACTION_UPDATE_CAR_OUTSIDE_TEMP -> {
                    val enabled = intent.getBooleanExtra(EXTRA_USE_CAR_OUTSIDE_TEMP, PrefsManager.isUseCarOutsideTempEnabled())
                    AppLogger.i(TAG, "📡 接收到实车车外温度开关更新: enabled=$enabled, currentCarTemp=$latestCarOutsideTempC")
                    if (enabled) {
                        CarGearManager.getInstance(this@WeatherOverlayService).refreshOutsideTempNow()
                    }
                    if (!isSimulating) {
                        refreshEffectiveSceneDisplay(saveOnlineCache = false)
                    }
                    Unit
                }
                ACTION_UPDATE_LIQUID_GLASS -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LIQUID_GLASS_ENABLED, PrefsManager.isLiquidGlassClockEnabled())
                    Log.i(TAG, "📡 接收到液态玻璃时钟状态更新广播: enabled=$enabled")
                    overlayWindow?.setLiquidGlassEnabled(enabled)
                }
                ACTION_UPDATE_TURN_SIGNAL_SYNC -> {
                    val enabled = intent.getBooleanExtra(EXTRA_TURN_SIGNAL_SYNC_ENABLED, PrefsManager.isTurnSignalSyncEnabled())
                    Log.i(TAG, "📡 接收到转向灯三边律动光效状态更新广播: enabled=$enabled")
                    overlayWindow?.setTurnSignalSyncEnabled(enabled)
                }
                ACTION_UPDATE_LANE_CHANGE_ASSIST -> {
                    val enabled = intent.getBooleanExtra(EXTRA_LANE_CHANGE_ASSIST_ENABLED, PrefsManager.isLaneChangeAssistEnabled())
                    Log.i(TAG, "📡 接收到变道辅助盲区光幕状态更新广播: enabled=$enabled")
                    overlayWindow?.setLaneChangeAssistEnabled(enabled)
                }
                ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 接收到左下角档位HUD状态更新广播: enabled=$enabled")
                    overlayWindow?.setBottomLeftGearHudEnabled(enabled)
                }
                ACTION_UPDATE_MINI_GEAR_POSITION -> {
                    val position = intent.getIntExtra(EXTRA_MINI_GEAR_POSITION, PrefsManager.getMiniGearPosition())
                    Log.i(TAG, "📡 接收到微型档位位置更新指令: position=$position")
                    overlayWindow?.setMiniGearPosition(position)
                }
                ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_MINI_GEAR_REALTIME_COLOR, PrefsManager.isMiniGearRealtimeColorEnabled())
                    Log.i(TAG, "📡 接收到微型档位实时背景自适应指令: enabled=$enabled")
                    overlayWindow?.setMiniGearRealtimeColor(enabled)
                }
                ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 接收到小欧形象与避让状态更新指令: enabled=$enabled")
                    overlayWindow?.updateAvatarAndAvoidance(enabled)
                    updateMotorPowerPollingState()
                }
                ACTION_UPDATE_SLOT_LAYOUT -> {
                    val slotAId = intent.getStringExtra(EXTRA_SLOT_A) ?: PrefsManager.getXiaoOuSlotA()
                    val slotCId = intent.getStringExtra(EXTRA_SLOT_C) ?: PrefsManager.getXiaoOuSlotC()
                    val slotA = XiaoOuSlotType.fromId(slotAId, XiaoOuSlotType.WEATHER)
                    val slotC = XiaoOuSlotType.fromId(slotCId, XiaoOuSlotType.ALBUM_COVER)
                    Log.i(TAG, "📡 接收到小欧屏 A/C 分区布局更新指令: slotA=$slotA, slotC=$slotC")
                    PrefsManager.setXiaoOuSlotA(slotA.id)
                    PrefsManager.setXiaoOuSlotC(slotC.id)
                    overlayWindow?.updateSlotLayout(slotA, slotC, smooth = true)
                    updateMotorPowerPollingState()
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
                    if (intent.hasExtra("extra_standalone_lyrics_enabled")) {
                        val enabled = intent.getBooleanExtra("extra_standalone_lyrics_enabled", false)
                        PrefsManager.setStandaloneLyricsEnabled(enabled)
                        evaluateLyricsStateTransition()
                    }
                    updateLyricsRuntimeSettings()
                }
                ACTION_SIMULATE_MUSIC -> {
                    val playing = intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, true)
                    val title = intent.getStringExtra(EXTRA_MUSIC_TITLE) ?: "See You Again (feat. Charlie Puth)"
                    val artist = intent.getStringExtra(EXTRA_MUSIC_ARTIST) ?: "Wiz Khalifa / Charlie Puth"
                    val duration = intent.getLongExtra("extra_music_duration", 0L)
                    val cover = intent.getStringExtra(EXTRA_MUSIC_COVER)
                    val pkg = intent.getStringExtra("extra_music_package") ?: ""
                    val mediaId = intent.getStringExtra("extra_music_media_id") ?: ""
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = duration, nativeCoverUrl = cover, packageName = pkg, mediaId = mediaId))
                }
                ACTION_SIMULATE_BATTERY -> {
                    val voltage = if (intent.hasExtra(EXTRA_BATTERY_VOLTAGE)) {
                        intent.getFloatExtra(EXTRA_BATTERY_VOLTAGE, -1f).takeIf { it > 0f }
                    } else null
                    val soh = if (intent.hasExtra(EXTRA_BATTERY_SOH)) {
                        intent.getIntExtra(EXTRA_BATTERY_SOH, -1).takeIf { it in 1..100 }
                    } else null
                    val soc = if (intent.hasExtra(EXTRA_BATTERY_SOC)) {
                        intent.getIntExtra(EXTRA_BATTERY_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val low = intent.getBooleanExtra(EXTRA_BATTERY_LOW, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateBattery(voltage, soh, soc, low)
                }
                ACTION_SIMULATE_TEMPS -> {
                    val outT = if (intent.hasExtra(EXTRA_OUTSIDE_TEMP)) {
                        intent.getFloatExtra(EXTRA_OUTSIDE_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val cabT = if (intent.hasExtra(EXTRA_CABIN_TEMP)) {
                        intent.getFloatExtra(EXTRA_CABIN_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateTemps(outT, cabT)
                }
                ACTION_SIMULATE_GFORCE -> {
                    val gx = intent.getFloatExtra(EXTRA_GFORCE_X, 0f)
                    val gy = intent.getFloatExtra(EXTRA_GFORCE_Y, 0f)
                    val holdMs = intent.getLongExtra(EXTRA_GFORCE_HOLD_MS, 15_000L)
                    com.car.xiaoouscreen.car.CarGForceManager.getInstance(this@WeatherOverlayService).simulateGForce(gx, gy, holdMs)
                }
                ACTION_SIMULATE_MOTOR_POWER -> {
                    val kw = if (intent.hasExtra(EXTRA_MOTOR_POWER_KW)) {
                        intent.getFloatExtra(EXTRA_MOTOR_POWER_KW, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val hvSoc = if (intent.hasExtra(EXTRA_MOTOR_HV_SOC)) {
                        intent.getIntExtra(EXTRA_MOTOR_HV_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val plugCharging = intent.getBooleanExtra(EXTRA_MOTOR_PLUG_CHARGING, false)
                    val resetPeaks = intent.getBooleanExtra(EXTRA_MOTOR_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateMotorPower(kw, hvSoc, plugCharging, resetPeaks)
                }
                ACTION_SIMULATE_RPM -> {
                    val eng = if (intent.hasExtra(EXTRA_ENGINE_RPM)) {
                        intent.getIntExtra(EXTRA_ENGINE_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val mot = if (intent.hasExtra(EXTRA_MOTOR_RPM)) {
                        intent.getIntExtra(EXTRA_MOTOR_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val resetPeaks = intent.getBooleanExtra(EXTRA_RPM_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateRpm(eng, mot, resetPeaks)
                }
                ACTION_SIMULATE_LANE_CHANGE -> {
                    val left = intent.getIntExtra(EXTRA_LANE_LEFT_WARNING, 0)
                    val right = intent.getIntExtra(EXTRA_LANE_RIGHT_WARNING, 0)
                    val bsdOn = intent.getBooleanExtra(EXTRA_LANE_BSD_ON, true)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateLaneChangeAlert(left, right, bsdOn)
                }
                ACTION_SIMULATE_TURN_SIGNAL -> {
                    val left = intent.getBooleanExtra(EXTRA_TURN_LEFT, false)
                    val right = intent.getBooleanExtra(EXTRA_TURN_RIGHT, false)
                    val hazard = intent.getBooleanExtra(EXTRA_TURN_HAZARD, false)
                    val avmDir = intent.getIntExtra(EXTRA_TURN_AVM, 0)
                    CarGearManager.getInstance(this@WeatherOverlayService).simulateTurnSignal(left, right, hazard, avmDir)
                }
                ACTION_SIMULATE_PREEMPT -> {
                    val reason = intent.getStringExtra("reason") ?: "ADB模拟原车抢占"
                    val delay = intent.getLongExtra("delay", 5000L)
                    AppLogger.i(TAG, "📡 接收到模拟原车功能抢占指令: reason=$reason, delay=${delay}ms")
                    AmbientLightManager.preempt(reason, autoReclaimDelayMs = delay)
                }
                ACTION_RECLAIM_AMBIENT_LIGHT -> {
                    val origin = intent.getStringExtra("origin") ?: "ADB指令重新接管"
                    AppLogger.i(TAG, "📡 接收到重新接管氛围灯指令: origin=$origin")
                    AmbientLightManager.reclaimAndSync(origin)
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
                AppLogger.e(TAG, "onTaskRemoved 自愈拉起异常: ${e.message}", e)
            }
        }
    }

    private fun showOverlay(mode: WeatherOverlayWindow.Mode) {
        val prevMode = currentMode
        val targetDisplay = ThirdDisplayFinder.find(this)
        if (targetDisplay == null) {
            Log.w(TAG, "未发现可用屏幕")
            return
        }

        val existingWindow = overlayWindow
        if (existingWindow != null && existingWindow.isShowing() &&
            existingWindow.targetDisplayId == targetDisplay.displayId &&
            prevMode == mode
        ) {
            // 防止开机 LOCKED_BOOT_COMPLETED 与 BOOT_COMPLETED 连发导致副屏窗口销毁重建闪屏及重复天气请求
            AppLogger.d(TAG, "副屏窗口已在目标屏幕 (display=${targetDisplay.displayId}, mode=$mode) 正常运行中，跳过重复创建")
            PrefsManager.setOverlayVisible(true)
            return
        }

        currentMode = mode
        lastObservedThreeScreenView = null
        if (!isSimulating) {
            fusionEngine?.onDeviceWakeUp("投屏挂载")
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
        // 挂载后立即同步一次当前已知实车档位、12V 蓄电池与座舱内外温度状态，消灭重建延迟
        val gearMgr = CarGearManager.getInstance(this)
        overlayWindow?.syncGearSilently(gearMgr.getCurrentGear())
        overlayWindow?.setBatteryInfo(gearMgr.getCurrentBatteryInfo())
        overlayWindow?.setCarOutsideTempC(gearMgr.getCurrentOutsideTempC())
        overlayWindow?.setCarCabinTempC(gearMgr.getCurrentCabinTempC())
        overlayWindow?.setMotorPowerInfo(gearMgr.getCurrentMotorPowerInfo())
        overlayWindow?.setRpmInfo(gearMgr.getCurrentRpmInfo())
        gearMgr.refreshBatteryInfoNow()
        gearMgr.refreshCabinTempNow()
        if (isMotorPowerSlotConfigured()) {
            gearMgr.refreshMotorPowerNow()
        }
        if (isRpmSlotConfigured()) {
            gearMgr.refreshRpmNow()
        }
        startBatteryPollingLoop()
        updateMotorPowerPollingState()

        evaluateLyricsStateTransition(smooth = false)
        if (mode == WeatherOverlayWindow.Mode.FITNESS) {
            startOcclusionWatchdog()
        } else {
            stopOcclusionWatchdog()
        }
        notifyStateToCallback()
    }

    fun hideOverlay(isExplicitUserClose: Boolean = true) {
        stopOcclusionWatchdog()
        stopBatteryPollingLoop()
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

    private fun startBatteryPollingLoop() {
        batteryPollJob?.cancel()
        batteryPollJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                if (!isShowing()) continue
                val mgr = CarGearManager.getInstance(this@WeatherOverlayService)
                if (mgr.isConnectedToCar()) {
                    mgr.refreshBatteryInfoNow()
                    mgr.refreshCabinTempNow()
                }
            }
        }
    }

    private fun stopBatteryPollingLoop() {
        batteryPollJob?.cancel()
        batteryPollJob = null
        motorPowerPollJob?.cancel()
        motorPowerPollJob = null
    }

    private fun isMotorPowerSlotConfigured(): Boolean {
        val slotA = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
        if (slotA == XiaoOuSlotType.MOTOR_POWER) return true
        if (!PrefsManager.isXiaoOuAvatarEnabled()) {
            val slotC = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
            if (slotC == XiaoOuSlotType.MOTOR_POWER) return true
        }
        return false
    }

    private fun isRpmSlotConfigured(): Boolean {
        val slotA = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER)
        if (slotA == XiaoOuSlotType.RPM) return true
        if (!PrefsManager.isXiaoOuAvatarEnabled()) {
            val slotC = XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER)
            if (slotC == XiaoOuSlotType.RPM) return true
        }
        return false
    }

    private fun updateMotorPowerPollingState() {
        val shouldPoll = isShowing() && (isMotorPowerSlotConfigured() || isRpmSlotConfigured())
        if (!shouldPoll) {
            motorPowerPollJob?.cancel()
            motorPowerPollJob = null
            return
        }
        if (motorPowerPollJob?.isActive == true) return
        // 对齐原厂 B561_NewEnergy / B561_Dashboard：仅在展示电机功率或实时转速模块且连上实车时 1s 轻量补读一次
        motorPowerPollJob = serviceScope.launch {
            while (isActive) {
                val mgr = CarGearManager.getInstance(this@WeatherOverlayService)
                if (isShowing() && mgr.isConnectedToCar()) {
                    if (isMotorPowerSlotConfigured()) mgr.refreshMotorPowerNow()
                    if (isRpmSlotConfigured()) mgr.refreshRpmNow()
                }
                delay(1_000L)
            }
        }
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
        serviceScope.launch {
            CarLyricsApplication.recognitionEngine.liveNotificationLyric.collectLatest { notifLyric ->
                onNotificationLyricChanged(notifLyric)
            }
        }
    }

    private fun onNotificationLyricChanged(notifLyric: NotificationLyric?) {
        if (notifLyric == null || notifLyric.text.isBlank()) return
        if (!isMusicPlaying) return

        // 核心兜底策略：如果当前尚未匹配到全量在线词轨，立即使用通知栏单行歌词进行平滑渲染
        if (currentMatchStatus != LyricsMatchStatus.MATCHED) {
            val singleLine = listOf(
                LyricLine(timestamp = 0L, text = notifLyric.text),
                LyricLine(timestamp = 86_400_000L, text = "")
            )
            currentLyricLines = singleLine
            mainHandler.post {
                overlayWindow?.setLyricsLines(singleLine)
                overlayWindow?.setCanvasLyrics(singleLine)
            }
        }
    }

    private fun onTrackStateChanged(track: CurrentTrack?) {
        serviceCallback?.onTrackChanged(track)
        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onTrackChanged(track)

        val title = track?.title?.trim() ?: ""
        val artist = track?.artist?.trim() ?: ""

        val isTrackChanged = (title != lastTrackTitle || artist != lastTrackArtist)

        // 核心优化：只要曲名有效，无论当前是否处于播放中，立即异步预取歌词与封面缓存
        if (title.isNotEmpty() && !MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            val trackKey = "$title|$artist"
            if (isTrackChanged) {
                lastTrackTitle = title
                lastTrackArtist = artist
                lastFetchedTrackKey = null
                currentLyricLines = emptyList()
                lastCoverUrl = null
                currentMatchStatus = LyricsMatchStatus.IDLE
                primaryRetryCount = 0
                pendingOfflineTrack = null
                durationWaitJob?.cancel()
                lyricsFetchJob?.cancel()
                nativeCoverFallbackJob?.cancel()
                retryJob?.cancel()
                // Bug2修复：切歌时立即重置 inFlightFetchTrackKey，
                // 防止已取消的旧歌抓取协程在 cancel 传播前绕过 trackKey 守卫，将旧歌词写入新歌词位置
                inFlightFetchTrackKey = null

                // Bug1修复：切歌时同步清除 BT 振荡抑制状态，避免新歌被旧抑制窗口误拦截
                btPauseConfirmedAtMs = 0L
                btPauseConfirmedPos = 0L

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

                if (!track?.nativeLyrics.isNullOrEmpty()) {
                    currentLyricLines = track!!.nativeLyrics!!
                    currentMatchStatus = LyricsMatchStatus.MATCHED
                    lastFetchedTrackKey = trackKey
                    val nativeCover = track.nativeCoverUrl?.takeIf { it.isNotBlank() }
                    if (nativeCover != null) {
                        applyOverlayCoverUrl(nativeCover)
                    }
                    mainHandler.post {
                        overlayWindow?.setLyricsLines(track.nativeLyrics!!)
                        overlayWindow?.setCanvasLyrics(track.nativeLyrics!!)
                    }
                    if (nativeCover == null) {
                        // 原车自带歌词但暂缺原生封面时，异步补拉在线封面（避免因跳过 fetchLyricsAndCover 导致无封面）
                        fetchNativeCoverFallbackOnly(title, artist, track.packageName)
                    }
                } else if ((track?.duration ?: 0L) > 15000L) {
                    // 时长已就绪，立即发起单次精准请求
                    fetchLyricsAndCover(title, artist, track, isRetry = false)
                } else {
                    // 时长未就绪 (切歌缓冲中 / duration <= 15s)，开启 800ms 沉淀等待窗口
                    currentMatchStatus = LyricsMatchStatus.WAITING_DURATION
                    durationWaitJob = serviceScope.launch(Dispatchers.Main) {
                        delay(800L)
                        if (currentMatchStatus == LyricsMatchStatus.WAITING_DURATION) {
                            AppLogger.d(TAG, "800ms 时长沉淀超时，以现有元数据发起单次获取: $title - $artist")
                            val latestTrack = CarLyricsApplication.recognitionEngine.currentTrack.value ?: track
                            fetchLyricsAndCover(title, artist, latestTrack, isRetry = false)
                        }
                    }
                }

                // 核心防抢置顶（仅首次起播时弱校验置顶；已在播状态下连续切歌依赖三屏遮挡事件实时接管，杜绝每切一首歌反复销毁重建 Presentation）
                if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
                    val isContinuousSwitch = wasMusicPlaying && track != null
                    if (!isContinuousSwitch) {
                        lastTrackReassertKey = trackKey
                        val delayMs = if (track?.isPlaying == true) 500L else 700L
                        scheduleReassertTop(delayMs, force = false)
                    }
                }
            } else {
                // 酷狗切歌歌词延迟/刷新同步：同曲目下若迟到的 nativeLyrics 与当前不同（如刚切歌时返回了旧歌缓存，400ms后才就绪新词），立即覆盖更新
                val delayedNativeLyrics = track?.nativeLyrics
                if (!delayedNativeLyrics.isNullOrEmpty() && delayedNativeLyrics != currentLyricLines) {
                    durationWaitJob?.cancel()
                    lyricsFetchJob?.cancel()
                    nativeCoverFallbackJob?.cancel()
                    retryJob?.cancel()
                    inFlightFetchTrackKey = null
                    currentLyricLines = delayedNativeLyrics
                    currentMatchStatus = LyricsMatchStatus.MATCHED
                    lastFetchedTrackKey = trackKey
                    AppLogger.i(TAG, "🎶 [同曲原生词刷新] 检测到延迟/更新的原生歌词 (${delayedNativeLyrics.size}行)，立即覆盖同步: $title")
                    try {
                        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onLyricsLoaded(delayedNativeLyrics)
                    } catch (_: Exception) {}
                    mainHandler.post {
                        overlayWindow?.setLyricsLines(delayedNativeLyrics)
                        overlayWindow?.setCanvasLyrics(delayedNativeLyrics)
                        if (!PrefsManager.isStandaloneLyricsEnabled() && isMusicPlaying && isShowing()) {
                            overlayWindow?.setCanvasLyricsActive(true, smooth = true)
                        }
                    }
                }
                // 同曲目下，若原车/U盘原生封面比歌词/切歌通知晚到达，立即补刷上屏
                val delayedNativeCover = track?.nativeCoverUrl?.takeIf { it.isNotBlank() }
                if (delayedNativeCover != null && lastCoverUrl.isNullOrBlank()) {
                    applyOverlayCoverUrl(delayedNativeCover)
                }
                if (currentMatchStatus == LyricsMatchStatus.WAITING_DURATION && (track?.duration ?: 0L) > 15000L) {
                    // 800ms 沉淀期内捕获到真实时长！立即发起精准请求
                    durationWaitJob?.cancel()
                    durationWaitJob = null
                    AppLogger.i(TAG, "⚡️ [时长对齐] 沉淀期内成功捕获真实时长 (${track?.duration}ms)，立即发起精准请求: $title")
                    fetchLyricsAndCover(title, artist, track, isRetry = false)
                } else if (lastFetchedTrackKey == trackKey && currentLyricLines.isEmpty() && inFlightFetchTrackKey != trackKey) {
                    // Bug2修复：同一首歌 key 命中但歌词为空（快速切换 A→B→A 导致旧 fetch 被取消、歌词已清空）
                    // 此时 lastFetchedTrackKey 仍指向本曲 key，不会进入正常的 !=trackKey 重拉分支。
                    // 检测到歌词为空则强制清除缓存 key 并重新拉取，避免停留在无词状态。
                    AppLogger.i(TAG, "🔄 [Bug2修复] 同曲目歌词已被切歌清空但 key 命中，强制重新拉取歌词: $trackKey")
                    lastFetchedTrackKey = null
                    currentMatchStatus = LyricsMatchStatus.IDLE
                    fetchLyricsAndCover(title, artist, track, isRetry = false)
                }
            }
        }

        currentTrackSource = track?.source ?: com.carlyrics.model.MusicSource.UNKNOWN

        if (track == null || track.title.isBlank() || MusicRecognitionEngine.isInvalidPlaceholderTrack(track.title, track.artist)) {
            isMusicPlaying = false
            lyricsProgressJob?.cancel()
            lyricsProgressJob = null
            lastPositionNanos = 0L
            pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
            pendingPausedTrack = null

            // 与暂停相同：若此前正处于播放/歌词态（如第三方切歌瞬间短暂下发 STOPPED/NONE 导致 track=null），
            // 给予 2 秒宽限延时后再回退大时钟，杜绝切歌瞬间闪回大时钟并重复加载天气/时间资源
            if (isShowing() && (wasMusicPlaying || isFallbackPending)) {
                overlayWindow?.setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = false)
                if (!isFallbackPending) {
                    isFallbackPending = true
                    fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                    fallbackHandler.postDelayed(fallbackToWeatherRunnable, 2000L)
                    AppLogger.d(TAG, "⏳ 音源短暂空窗/停播，开启 2s 宽限延时后再决定是否回退大时钟")
                }
            } else {
                wasMusicPlaying = false
                isFallbackPending = false
                fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                overlayWindow?.setTopMusicTrackInfo("", "", isPlaying = false)
                if (PrefsManager.isStandaloneLyricsEnabled()) {
                    overlayWindow?.switchToWeatherPage(smooth = true)
                } else {
                    overlayWindow?.setCanvasLyricsActive(false, smooth = true)
                }
            }
            return
        }

        if (!track.isPlaying) {
            // 核心修复：0ms 立即销毁推演协程并定格静止，杜绝暂停后歌词继续向前滚动！
            isMusicPlaying = false
            lyricsProgressJob?.cancel()
            lyricsProgressJob = null
            lastKnownPosition = track.position
            lastPositionNanos = 0L
            overlayWindow?.updateLyricsProgress(track.position)
            overlayWindow?.updateCanvasLyricsProgress(track.position)

            if (isTrackChanged || pendingPausedTrack == null) {
                // 切歌到尚未起播的新歌或首次收到暂停：重新计算完整 2 秒缓冲宽限（350ms防抖 + 1650ms回退）
                if (isTrackChanged && isFallbackPending) {
                    fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
                    isFallbackPending = false
                }
                pendingPausedTrack = track
                pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
                pauseDebounceHandler.postDelayed(pauseDebounceRunnable, 350L)
            } else {
                pendingPausedTrack = track
            }
            return
        }

        // 此时处于 PLAYING 状态
        val isFirstPlayInSession = !wasMusicPlaying && pendingPausedTrack == null
        val wasResumedFromPause = !isMusicPlaying && wasMusicPlaying

        // Bug1修复：蓝牙振荡抑制
        // 若刚刚通过防抖确认了真实暂停，且本次 isPlaying=true 信号来自蓝牙，
        // 且进度相比确认暂停时的位置未显著前进（说明仍是 AVRCP/MediaSession 振荡噪音），
        // 拒绝恢复走时，避免歌词在静音后继续向前跳动。
        if (track.source == com.carlyrics.model.MusicSource.BLUETOOTH && btPauseConfirmedAtMs > 0L) {
            val suppressWindowExpired = android.os.SystemClock.elapsedRealtime() - btPauseConfirmedAtMs > BT_OSCILLATION_SUPPRESS_MS
            val posSignificantlyAdvanced = track.position - btPauseConfirmedPos > BT_OSCILLATION_POS_THRESHOLD_MS
            if (!suppressWindowExpired && !posSignificantlyAdvanced) {
                AppLogger.d(TAG, "🔇 [BT振荡抑制] 确认暂停后 ${android.os.SystemClock.elapsedRealtime() - btPauseConfirmedAtMs}ms 内收到 isPlaying=true (pos=${track.position}, pausedPos=$btPauseConfirmedPos)，视为静音振荡噪音，忽略本次恢复")
                return
            } else if (suppressWindowExpired || posSignificantlyAdvanced) {
                // 超出抑制窗口或进度显著前进，认定为真实用户恢复操作
                AppLogger.d(TAG, "▶️ [BT振荡抑制] 检测到真实恢复: windowExpired=$suppressWindowExpired, posAdv=${track.position - btPauseConfirmedPos}ms，清除振荡抑制状态")
                btPauseConfirmedAtMs = 0L
                btPauseConfirmedPos = 0L
            }
        }

        // 立即取消暂停防抖，平稳恢复播放
        pauseDebounceHandler.removeCallbacks(pauseDebounceRunnable)
        pendingPausedTrack = null

        isMusicPlaying = true
        wasMusicPlaying = true
        if (isFallbackPending) {
            fallbackHandler.removeCallbacks(fallbackToWeatherRunnable)
            isFallbackPending = false
            AppLogger.d(TAG, "▶️ 音乐在 2s 内恢复播放，取消大时钟回退倒计时")
        }

        // 核心防抢置顶与无感过渡优化：
        // 1. 首次起播 (isFirstPlayInSession) 或暂停恢复 (wasResumedFromPause) 时，
        //    车机原厂系统 (B561_Dashboard) 必在 200~400ms 内调用 MediaPresentation.show() 压制第三方副屏。
        // 2. 合并去重：若同一首歌在 3000ms 内已经完成过重登（常见于播放器起播时 playing -> buffering(false) -> playing 瞬态抖动），
        //    直接抑制重复重登，确保每次切歌/起播最多只执行 1 次精准置顶！
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            if (isFirstPlayInSession || wasResumedFromPause) {
                val trackKey = "$title|$artist"
                val now = SystemClock.elapsedRealtime()
                val recentlyReasserted = (now - lastTrackReassertTimeMs < 5000L)
                if (recentlyReasserted && (lastTrackReassertKey == trackKey || !isFirstPlayInSession)) {
                    AppLogger.d(TAG, "🛡️ [防抢置顶] 曲目($title) 5s 内已完成置顶或处于连续播放恢复抖动，抑制重复窗口重建")
                } else {
                    lastTrackReassertKey = trackKey
                    AppLogger.i(TAG, "🛡️ [防抢置顶] 检测到音乐起播/恢复播放 (isFirstPlay=$isFirstPlayInSession, wasResumed=$wasResumedFromPause)，预约 550ms 后置顶重登")
                    scheduleReassertTop(550L, force = false)
                }
            }
        }

        val shouldResetAnchor = isTrackChanged || wasResumedFromPause || isFirstPlayInSession ||
            (track.position != lastKnownPosition) || lastPositionNanos <= 0L
        if (shouldResetAnchor) {
            lastKnownPosition = track.position
            lastPositionNanos = System.nanoTime()
        }

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
            // 无论首曲是否先以 isPlaying=false 预载再于 350ms 内转正，只要处于真实播放态，幂等确保歌词视图就绪
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

    private fun schedulePrimaryRetry(track: CurrentTrack) {
        retryJob?.cancel()
        if (primaryRetryCount >= 2) return
        val nextRetryIndex = primaryRetryCount + 1
        val targetPosMs = if (nextRetryIndex == 1) 3000L else 8000L

        retryJob = serviceScope.launch(Dispatchers.Main) {
            val nowPos = track.position.coerceAtLeast(0L)
            val waitMs = (targetPosMs - nowPos).coerceIn(800L, 8000L)
            delay(waitMs)

            val latest = CarLyricsApplication.recognitionEngine.currentTrack.value ?: return@launch
            if (latest.title != lastTrackTitle || latest.artist != lastTrackArtist) return@launch
            if (!isMusicPlaying) return@launch
            if (currentMatchStatus == LyricsMatchStatus.MATCHED) return@launch

            val dur = latest.duration
            if (dur > 0L && (latest.position.toFloat() / dur.toFloat()) > 0.35f) {
                AppLogger.d(TAG, "播放进度已超过 35%，退出第一顺位退避重试黄金期")
                return@launch
            }

            if (!LyricsApiManager.isNetworkAvailable(this@WeatherOverlayService)) {
                AppLogger.d(TAG, "退避重试时处于断网态，挂起并等待网络恢复: ${latest.title}")
                pendingOfflineTrack = latest
                return@launch
            }

            primaryRetryCount++
            AppLogger.i(TAG, "🚀 [第一顺位源专属退避重试] 发起第 $primaryRetryCount 次重试: ${latest.title} (进度=${latest.position}ms)")
            fetchLyricsAndCover(latest.title, latest.artist, latest, isRetry = true)
        }
    }

    /**
     * 统一同步有效封面 URL 到悬浮窗与律动取色引擎
     */
    private fun applyOverlayCoverUrl(coverUrl: String) {
        lastCoverUrl = coverUrl
        mainHandler.post {
            overlayWindow?.setLyricsCoverUrl(coverUrl)
        }
        com.carlyrics.util.LyricsCacheManager.loadCoverBitmapScaled(this@WeatherOverlayService, coverUrl, 320) { bmp: android.graphics.Bitmap? ->
            if (bmp != null) {
                try {
                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onCoverLoaded(bmp)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 当原车音乐已透传完整歌词但缺失原生封面时，异步在线补拉封面
     */
    private fun fetchNativeCoverFallbackOnly(title: String, artist: String, packageName: String?) {
        nativeCoverFallbackJob?.cancel()
        nativeCoverFallbackJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (!LyricsApiManager.isNetworkAvailable(this@WeatherOverlayService)) return@launch
                val coverUrl = lyricsApi.fetchCover(title, artist, packageName)
                coroutineContext.ensureActive()
                if (!coverUrl.isNullOrBlank() && title == lastTrackTitle && artist == lastTrackArtist && lastCoverUrl.isNullOrBlank()) {
                    applyOverlayCoverUrl(coverUrl)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {}
        }
    }

    private fun fetchLyricsAndCover(title: String, artist: String, track: CurrentTrack? = null, isRetry: Boolean = false) {
        val trackKey = "$title|$artist"
        if (inFlightFetchTrackKey == trackKey) {
            Log.d(TAG, "歌词与封面请求已在进行中，跳过重复拉取: $trackKey")
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

                // 弱网与离线检查：若彻底断网且无本地原生歌词，立即挂起并退出，零无意义网络请求
                val hasNet = LyricsApiManager.isNetworkAvailable(this@WeatherOverlayService)
                val isNativeLyricsAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeLyrics.isNullOrEmpty()

                if (!hasNet && !isNativeLyricsAvailable) {
                    AppLogger.i(TAG, "🌐 [离线保护] 当前处于断网态，挂起当前曲目等待网络恢复: $trackKey")
                    pendingOfflineTrack = currentTrack
                    currentMatchStatus = LyricsMatchStatus.RETRYABLE_PENDING
                    return@launch
                }

                // 1. 获取歌词内容：优先使用原车酷狗/U盘离线自带歌词，次优请求第一顺位源 (primaryOnly)，重试耗尽后才走次选瀑布流
                var fetchedCoverUrl: String? = null
                val lines = if (isNativeLyricsAvailable) {
                    currentMatchStatus = LyricsMatchStatus.MATCHED
                    currentTrack!!.nativeLyrics!!
                } else {
                    currentMatchStatus = LyricsMatchStatus.FETCHING_PRIMARY
                    val primaryResult = try {
                        lyricsApi.fetchLyrics(
                            title = title,
                            artist = artist,
                            duration = currentTrack?.duration ?: 0L,
                            mediaId = currentTrack?.mediaId,
                            packageName = currentTrack?.packageName ?: "",
                            primaryOnly = true
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Result.failure(e)
                    }

                    coroutineContext.ensureActive()
                    if (inFlightFetchTrackKey != trackKey) return@launch

                    val primaryData = primaryResult.getOrNull()
                    val rawLines = primaryData?.lines?.filter { it.text.isNotBlank() } ?: emptyList()

                    if (rawLines.isNotEmpty() && lyricsApi.isSubstantiveLyrics(rawLines)) {
                        currentMatchStatus = LyricsMatchStatus.MATCHED
                        pendingOfflineTrack = null
                        fetchedCoverUrl = primaryData?.coverUrl
                        rawLines
                    } else {
                        val err = primaryResult.exceptionOrNull()
                        val isNetErr = LyricsApiManager.isNetworkError(err)
                        if (isNetErr && !LyricsApiManager.isNetworkAvailable(this@WeatherOverlayService)) {
                            pendingOfflineTrack = currentTrack
                        }

                        if (primaryRetryCount < 2) {
                            currentMatchStatus = LyricsMatchStatus.RETRYABLE_PENDING
                            withContext(Dispatchers.Main) {
                                currentTrack?.let { schedulePrimaryRetry(it) }
                            }
                            emptyList()
                        } else {
                            AppLogger.i(TAG, "⚠️ 第一顺位源 3 次重试均无词，放行次选瀑布流与官方级联兜底: $trackKey")
                            val fallbackResult = try {
                                lyricsApi.fetchLyricsFallback(
                                    title = title,
                                    artist = artist,
                                    duration = currentTrack?.duration ?: 0L,
                                    mediaId = currentTrack?.mediaId,
                                    packageName = currentTrack?.packageName ?: ""
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                Result.failure(e)
                            }

                            coroutineContext.ensureActive()
                            if (inFlightFetchTrackKey != trackKey) return@launch

                            val fallbackData = fallbackResult.getOrNull()
                            val fallbackLines = fallbackData?.lines?.filter { it.text.isNotBlank() } ?: emptyList()

                            if (fallbackLines.isNotEmpty() && lyricsApi.isSubstantiveLyrics(fallbackLines)) {
                                currentMatchStatus = LyricsMatchStatus.MATCHED
                                pendingOfflineTrack = null
                                fetchedCoverUrl = fallbackData?.coverUrl
                                fallbackLines
                            } else {
                                currentMatchStatus = LyricsMatchStatus.PERMANENT_NOT_FOUND
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
                        }
                    }
                }

                if (lines.isNotEmpty()) {
                    currentLyricLines = lines
                    if (currentMatchStatus == LyricsMatchStatus.MATCHED || currentMatchStatus == LyricsMatchStatus.PERMANENT_NOT_FOUND) {
                        lastFetchedTrackKey = trackKey
                    }
                    try {
                        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onLyricsLoaded(lines)
                    } catch (_: Exception) {}
                }

                // 2. 封面处理：优先使用原车/U盘本地提取封面，次优复用歌词返回的 coverUrl，若无则在线补拉封面
                coroutineContext.ensureActive()
                val isNativeCoverAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeCoverUrl.isNullOrBlank()

                val coverUrl = if (isNativeCoverAvailable) {
                    AppLogger.i(TAG, "优先采用原车/U盘原生封面: $trackKey (${currentTrack!!.nativeCoverUrl})")
                    currentTrack.nativeCoverUrl
                } else {
                    fetchedCoverUrl ?: try {
                        lyricsApi.fetchCover(title, artist, currentTrack?.packageName)
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
                        com.carlyrics.util.LyricsCacheManager.loadCoverBitmapScaled(this@WeatherOverlayService, coverUrl, 320) { bmp: android.graphics.Bitmap? ->
                            if (bmp != null) {
                                try {
                                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onCoverLoaded(bmp)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    overlayWindow?.setLyricsLines(lines)
                    overlayWindow?.setCanvasLyrics(lines)
                    if (isMusicPlaying) {
                        overlayWindow?.setTopMusicTrackInfo(title, artist, isPlaying = true)
                    } else {
                        overlayWindow?.updateLyricsProgress(lastKnownPosition)
                        overlayWindow?.updateCanvasLyricsProgress(lastKnownPosition)
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
                val currentPos = getEstimatedCurrentTrackPositionMs()
                try {
                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onPositionUpdate(currentPos)
                } catch (_: Exception) {}

                if (!isShowing()) {
                    delay(300L)
                    continue
                }

                val isStandaloneLyricsActive = PrefsManager.isStandaloneLyricsEnabled() &&
                    overlayWindow?.getCurrentPage() == WeatherOverlayWindow.ActivePage.LYRICS

                if (isStandaloneLyricsActive) {
                    overlayWindow?.updateLyricsProgress(currentPos)
                    delay(if (currentLyricLines.isEmpty()) 250L else 16L)
                } else if (!PrefsManager.isStandaloneLyricsEnabled()) {
                    overlayWindow?.updateCanvasLyricsProgress(currentPos)
                    delay(if (currentLyricLines.isEmpty()) 250L else 33L)
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
                    // 防止引擎尚未拉取成功时的默认占位场景 (DEFAULT) 冲刷覆盖开机预载的本地实况缓存
                    if (newScene == WeatherSceneConfig.DEFAULT && currentScene != WeatherSceneConfig.DEFAULT) {
                        return@collectLatest
                    }
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

    private fun resolveEffectiveScene(base: WeatherSceneConfig): WeatherSceneConfig {
        val carTemp = latestCarOutsideTempC
        return if (!isSimulating && PrefsManager.isUseCarOutsideTempEnabled() && carTemp != null) {
            base.copy(temperatureCelsius = carTemp)
        } else {
            base
        }
    }

    private fun refreshEffectiveSceneDisplay(saveOnlineCache: Boolean) {
        val effective = resolveEffectiveScene(latestOnlineScene)
        currentScene = effective
        if (saveOnlineCache) {
            try {
                val json = com.carlyrics.util.SharedNetwork.gson.toJson(latestOnlineScene)
                PrefsManager.setLastWeatherSceneJson(json)
            } catch (_: Exception) {}
        }
        overlayWindow?.updateScene(effective)
        notifyStateToCallback()
    }

    private fun applyNewScene(scene: WeatherSceneConfig) {
        AmbientLightManager.setTimeOfDay(scene.timeOfDay)
        if (!isSimulating) {
            latestOnlineScene = scene
            refreshEffectiveSceneDisplay(saveOnlineCache = true)
        } else {
            currentScene = scene
            overlayWindow?.updateScene(scene)
            notifyStateToCallback()
        }
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

        // 方案 1 核心自检测：判断自身是否被原厂副屏媒体(蓝牙音乐/酷狗歌词)或系统高优先界面覆盖
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            val client = threeScreenClient
            if (client?.isCloudEyeView(type) == true) {
                AppLogger.i(TAG, "🛡️ [三屏调度] 监听到云眼后视镜($type)启动，小欧屏主动礼让")
                isCloudEyeActive = true
                reassertTopHandler.removeCallbacks(reassertTopRunnable)
                return
            }

            if (client?.isMediaView(type) == true) {
                // 车载电话活跃状态严格避让，绝不在通话中触发抢屏
                if (CarCallManager.getInstance(this).getCurrentCallInfo().isCallActive) {
                    AppLogger.i(TAG, "🛡️ [遮挡自检] 监听到原厂媒体($type)弹出，但当前处于通话活跃状态，保持避让")
                    return
                }

                AppLogger.w(TAG, "🚨 [遮挡自检·精确捕获] 确凿监听到原厂媒体($type)弹出抢占副屏！调度无缝置顶接管...")
                // 延时 350ms 避开原厂 Presentation.show() 内部初始化与入场动画，然后精准登顶
                scheduleReassertTop(350L, force = true)
            } else if (type.contains(ThreeScreenClient.VIEW_NAVIGATION, ignoreCase = true) ||
                type.contains(ThreeScreenClient.VIEW_APA, ignoreCase = true) ||
                type.contains(ThreeScreenClient.VIEW_CIRCUIT, ignoreCase = true) ||
                type.contains(ThreeScreenClient.VIEW_REST, ignoreCase = true)) {
                AppLogger.i(TAG, "🛡️ [高优先级让路] 监听到系统高优先业务($type)，我方保持礼貌让路，不触发重登")
            }
        }
    }

    override fun onHideRequest(type: String) {
        AppLogger.d(TAG, "三屏调度收到 onHideRequest: type=$type")
        if (isShowing() && currentMode == WeatherOverlayWindow.Mode.FITNESS) {
            val client = threeScreenClient
            if (client?.isCloudEyeView(type) == true) {
                AppLogger.i(TAG, "🚨 [三屏调度] 监听到云眼后视镜($type)退场，立即调度置顶接管！")
                isCloudEyeActive = false
                lastCloudEyeCloseTimeMs = SystemClock.elapsedRealtime()
                scheduleReassertTop(350L, force = true)
                mainHandler.postDelayed({
                    checkOcclusionAndRecover("云眼退场调度复核")
                }, 800L)
                return
            }

            if (client?.isMediaView(type) == true) {
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

        // 车规最高行车安全级：若当前正在显示云眼后视镜，绝对不可争抢遮挡！
        if (client.isCloudEyeView(currentView)) {
            AppLogger.i(TAG, "🛡️ [安全避让·$reason] 当前副屏正处于云眼后视镜前台($currentView)，严禁遮挡行车安全视像，保持避让！")
            isCloudEyeActive = true
            return false
        }

        // 倒车档位安全避让：若当前处于 R 挡，保持避让
        if (lastReportedGear == "R" || CarGearManager.getInstance(this).getCurrentGear() == "R") {
            AppLogger.d(TAG, "🛡️ [安全避让·$reason] 当前处于倒车 R 挡，避让倒车影像")
            return false
        }

        val isMedia = client.isMediaView(currentView)
        val isCloudEyeRecovery = reason.contains("云眼") || reason.contains("后视镜") || reason.contains("CLOUD_EYE")
        val isWheelRecovery = reason.contains("方控") || reason.contains("按键")
        val isGenericRecovery = reason.contains("退场") || reason.contains("恢复") || reason.contains("接管") ||
            reason.contains("电话挂断") || reason.contains("手动") || reason.contains("唤醒") ||
            reason.contains("ADB") || reason.contains("广播")

        val needRecover = isMedia || isCloudEyeRecovery || isWheelRecovery || isGenericRecovery

        if (needRecover) {
            AppLogger.w(TAG, "🚨 [遮挡自检·$reason] 判定需要执行小欧屏登顶接管 (前台视图: $currentView, isMedia: $isMedia)！")
            // 重登前先同步精确实时毫秒进度，确保新 Presentation 上屏首帧即与当前行 100% 对齐
            val exactPos = getEstimatedCurrentTrackPositionMs()
            if (exactPos > 0L) {
                overlayWindow?.updateLyricsProgress(exactPos)
                overlayWindow?.updateCanvasLyricsProgress(exactPos)
            }
            val success = overlayWindow?.reassertTop(force = true) ?: false
            if (success) {
                lastObservedThreeScreenView = currentView
                lastTrackReassertTimeMs = SystemClock.elapsedRealtime()
            }
            return success
        } else {
            lastObservedThreeScreenView = currentView
            AppLogger.d(TAG, "✅ [遮挡自检·$reason] 小欧屏未被遮挡无需接管 (前台: $currentView)")
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
        CarGearManager.getInstance(this).unregisterOutsideTempListener(outsideTempListener)
        CarGearManager.getInstance(this).unregisterCabinTempListener(cabinTempListener)
        CarGearManager.getInstance(this).unregisterBatteryInfoListener(batteryInfoListener)
        CarGearManager.getInstance(this).unregisterMotorPowerListener(motorPowerListener)
        CarGearManager.getInstance(this).unregisterRpmListener(rpmListener)
        CarGearManager.getInstance(this).unregisterLaneChangeAlertListener(laneChangeAlertListener)
        CarGearManager.getInstance(this).unregisterTurnSignalListener(turnSignalListener)
        stopBatteryPollingLoop()
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
        durationWaitJob?.cancel()
        retryJob?.cancel()
        unregisterNetworkCallback()
        fusionJob?.cancel()
        fusionEngine?.stop()
        teardownNativeMediaSentinel()
        teardownCarFeatureEventReceiver()
        stopOcclusionWatchdog()
        hideOverlay(isExplicitUserClose = false)
        ThreeScreenClient.let {}
        ThirdDisplayFinder.stopListening(this)
        threeScreenClient?.unbind()
        gpsManager?.stopLocationUpdates()
        serviceScope.cancel()
        instance = null
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post {
                        val pending = pendingOfflineTrack
                        val latest = CarLyricsApplication.recognitionEngine.currentTrack.value
                        if (pending != null && latest != null &&
                            pending.title == latest.title && pending.artist == latest.artist &&
                            isMusicPlaying && currentMatchStatus != LyricsMatchStatus.MATCHED
                        ) {
                            val dur = latest.duration
                            val progressRatio = if (dur > 0L) latest.position.toFloat() / dur.toFloat() else 0f
                            if (progressRatio < 0.50f) {
                                AppLogger.i(TAG, "🌐 [网络恢复自愈] 出地库/网络已就绪，立即唤醒歌词匹配: ${latest.title}")
                                pendingOfflineTrack = null
                                fetchLyricsAndCover(latest.title, latest.artist, latest, isRetry = true)
                            } else {
                                AppLogger.d(TAG, "🌐 [网络恢复自愈] 当前曲目进度已超过 50% ($progressRatio)，放弃补拉以节省带宽: ${latest.title}")
                                pendingOfflineTrack = null
                            }
                        }
                    }
                }
            }
            networkCallback = callback
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
        } catch (e: Exception) {
            AppLogger.d(TAG, "注册 NetworkCallback 异常: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
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
        const val ACTION_UPDATE_TURN_SIGNAL_SYNC = "com.car.xiaoouscreen.UPDATE_TURN_SIGNAL_SYNC"
        const val EXTRA_TURN_SIGNAL_SYNC_ENABLED = "extra_turn_signal_sync_enabled"
        const val ACTION_UPDATE_LANE_CHANGE_ASSIST = "com.car.xiaoouscreen.UPDATE_LANE_CHANGE_ASSIST"
        const val EXTRA_LANE_CHANGE_ASSIST_ENABLED = "extra_lane_change_assist_enabled"
        const val ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD = "com.car.xiaoouscreen.UPDATE_BOTTOM_LEFT_GEAR_HUD"
        const val ACTION_UPDATE_MINI_GEAR_POSITION = "com.car.xiaoouscreen.UPDATE_MINI_GEAR_POSITION"
        const val ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR = "com.car.xiaoouscreen.UPDATE_MINI_GEAR_REALTIME_COLOR"
        const val ACTION_UPDATE_XIAOOU_AVATAR = "com.car.xiaoouscreen.UPDATE_XIAOOU_AVATAR"
        const val ACTION_UPDATE_SLOT_LAYOUT = "com.car.xiaoouscreen.UPDATE_SLOT_LAYOUT"
        const val ACTION_UPDATE_CAR_OUTSIDE_TEMP = "com.car.xiaoouscreen.UPDATE_CAR_OUTSIDE_TEMP"
        const val ACTION_CALL_EVENT = "com.car.xiaoouscreen.CALL_EVENT"
        const val ACTION_SIMULATE_MUSIC = "com.car.xiaoouscreen.SIMULATE_MUSIC"
        const val ACTION_SIMULATE_BATTERY = "com.car.xiaoouscreen.SIMULATE_BATTERY"
        const val ACTION_SIMULATE_TEMPS = "com.car.xiaoouscreen.SIMULATE_TEMPS"
        const val ACTION_SIMULATE_GFORCE = "com.car.xiaoouscreen.SIMULATE_GFORCE"
        const val ACTION_SIMULATE_MOTOR_POWER = "com.car.xiaoouscreen.SIMULATE_MOTOR_POWER"
        const val ACTION_SIMULATE_RPM = "com.car.xiaoouscreen.SIMULATE_RPM"
        const val ACTION_SIMULATE_LANE_CHANGE = "com.car.xiaoouscreen.SIMULATE_LANE_CHANGE"
        const val ACTION_SIMULATE_TURN_SIGNAL = "com.car.xiaoouscreen.SIMULATE_TURN_SIGNAL"
        const val ACTION_CHECK_OCCLUSION = "com.car.xiaoouscreen.CHECK_OCCLUSION"
        const val ACTION_SIMULATE_CLOUDEYE_EXIT = "com.car.xiaoouscreen.SIMULATE_CLOUDEYE_EXIT"
        const val ACTION_SIMULATE_PREEMPT = "com.car.xiaoouscreen.action.SIMULATE_PREEMPT"
        const val ACTION_RECLAIM_AMBIENT_LIGHT = "com.car.xiaoouscreen.action.RECLAIM_AMBIENT_LIGHT"

        const val EXTRA_MUSIC_PLAYING = "extra_music_playing"
        const val EXTRA_MUSIC_TITLE = "extra_music_title"
        const val EXTRA_MUSIC_ARTIST = "extra_music_artist"
        const val EXTRA_MUSIC_COVER = "extra_music_cover"

        const val EXTRA_BATTERY_VOLTAGE = "extra_battery_voltage"
        const val EXTRA_BATTERY_SOH = "extra_battery_soh"
        const val EXTRA_BATTERY_SOC = "extra_battery_soc"
        const val EXTRA_BATTERY_LOW = "extra_battery_low"
        const val EXTRA_OUTSIDE_TEMP = "extra_outside_temp"
        const val EXTRA_CABIN_TEMP = "extra_cabin_temp"
        const val EXTRA_GFORCE_X = "extra_gx"
        const val EXTRA_GFORCE_Y = "extra_gy"
        const val EXTRA_GFORCE_HOLD_MS = "extra_hold_ms"
        const val EXTRA_MOTOR_POWER_KW = "extra_power_kw"
        const val EXTRA_MOTOR_HV_SOC = "extra_hv_soc"
        const val EXTRA_MOTOR_PLUG_CHARGING = "extra_plug_charging"
        const val EXTRA_MOTOR_RESET_PEAKS = "extra_reset_peaks"
        const val EXTRA_ENGINE_RPM = "extra_engine_rpm"
        const val EXTRA_MOTOR_RPM = "extra_motor_rpm"
        const val EXTRA_RPM_RESET_PEAKS = "extra_rpm_reset_peaks"
        const val EXTRA_LANE_LEFT_WARNING = "left"
        const val EXTRA_LANE_RIGHT_WARNING = "right"
        const val EXTRA_LANE_BSD_ON = "bsd_on"
        const val EXTRA_TURN_LEFT = "left"
        const val EXTRA_TURN_RIGHT = "right"
        const val EXTRA_TURN_HAZARD = "hazard"
        const val EXTRA_TURN_AVM = "avm_dir"

        const val EXTRA_TARGET_GEAR = "extra_target_gear"
        const val EXTRA_DRIVE_MODE = "mode"
        const val EXTRA_PRESET_KEY = "extra_preset"
        const val EXTRA_TIME_OF_DAY = "extra_time_of_day"
        const val EXTRA_WEATHER_STATE = "extra_weather_state"
        const val EXTRA_WIND_SPEED = "extra_wind_speed"
        const val EXTRA_IS_SLEET = "extra_is_sleet"
        const val EXTRA_LIQUID_GLASS_ENABLED = "extra_liquid_glass_enabled"
        const val EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED = "extra_bottom_left_gear_hud_enabled"
        const val EXTRA_MINI_GEAR_POSITION = "extra_mini_gear_position"
        const val EXTRA_MINI_GEAR_REALTIME_COLOR = "extra_mini_gear_realtime_color"
        const val EXTRA_XIAOOU_AVATAR_ENABLED = "extra_xiaoou_avatar_enabled"
        const val EXTRA_SLOT_A = "extra_slot_a"
        const val EXTRA_SLOT_C = "extra_slot_c"
        const val EXTRA_USE_CAR_OUTSIDE_TEMP = "extra_use_car_outside_temp"
        const val EXTRA_CALL_TYPE = "type"
        const val EXTRA_CALL_STATE = "state"
        const val EXTRA_CALL_NAME = "name"
        const val EXTRA_CALL_NUMBER = "number"

        var instance: WeatherOverlayService? = null
            private set

        fun pauseOverlay() {
            instance?.overlayWindow?.pause()
        }

        fun resumeOverlay() {
            instance?.overlayWindow?.resume()
        }

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

        fun simulateCloudEyeExit(context: Context) {
            send(context, ACTION_SIMULATE_CLOUDEYE_EXIT)
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

        fun updateTurnSignalSyncState(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.setTurnSignalSyncEnabled(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_TURN_SIGNAL_SYNC
                putExtra(EXTRA_TURN_SIGNAL_SYNC_ENABLED, enabled)
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
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_TURN_SIGNAL_SYNC).apply {
                    putExtra(EXTRA_TURN_SIGNAL_SYNC_ENABLED, enabled)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "广播发送失败: ${e.message}")
            }
        }

        fun updateLaneChangeAssistState(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.setLaneChangeAssistEnabled(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_LANE_CHANGE_ASSIST
                putExtra(EXTRA_LANE_CHANGE_ASSIST_ENABLED, enabled)
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
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_LANE_CHANGE_ASSIST).apply {
                    putExtra(EXTRA_LANE_CHANGE_ASSIST_ENABLED, enabled)
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

        fun updateMiniGearPosition(context: Context, position: Int) {
            instance?.overlayWindow?.setMiniGearPosition(position)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_MINI_GEAR_POSITION
                putExtra(EXTRA_MINI_GEAR_POSITION, position)
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
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_MINI_GEAR_POSITION).apply {
                    putExtra(EXTRA_MINI_GEAR_POSITION, position)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "广播发送失败: ${e.message}")
            }
        }

        fun updateMiniGearRealtimeColor(context: Context, enabled: Boolean) {
            instance?.overlayWindow?.setMiniGearRealtimeColor(enabled)
            val i = Intent(context, WeatherOverlayService::class.java).apply {
                action = ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR
                putExtra(EXTRA_MINI_GEAR_REALTIME_COLOR, enabled)
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
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR).apply {
                    putExtra(EXTRA_MINI_GEAR_REALTIME_COLOR, enabled)
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

        fun updateCarOutsideTempState(context: Context, enabled: Boolean) {
            instance?.let { svc ->
                svc.mainHandler.post {
                    if (enabled) {
                        CarGearManager.getInstance(context).refreshOutsideTempNow()
                    }
                    if (!svc.isSimulating) {
                        svc.refreshEffectiveSceneDisplay(saveOnlineCache = false)
                    }
                }
            }
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_CAR_OUTSIDE_TEMP).apply {
                    putExtra(EXTRA_USE_CAR_OUTSIDE_TEMP, enabled)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "车外温度开关广播发送失败: ${e.message}")
            }
        }

        fun updateSlotLayout(context: Context, slotAId: String, slotCId: String) {
            val slotA = XiaoOuSlotType.fromId(slotAId, XiaoOuSlotType.WEATHER)
            val slotC = XiaoOuSlotType.fromId(slotCId, XiaoOuSlotType.ALBUM_COVER)
            instance?.let { svc ->
                svc.overlayWindow?.updateSlotLayout(slotA, slotC, smooth = true)
                svc.mainHandler.post { svc.updateMotorPowerPollingState() }
            }
            // 通知独立预览 Activity (CarSubscreenActivity)；若服务已运行则已在上方直连更新，不重复拉起 IPC 节省车机资源
            try {
                context.sendBroadcast(Intent(ACTION_UPDATE_SLOT_LAYOUT).apply {
                    putExtra(EXTRA_SLOT_A, slotA.id)
                    putExtra(EXTRA_SLOT_C, slotC.id)
                    setPackage(context.packageName)
                })
            } catch (e: Exception) {
                Log.w(TAG, "分区布局更新广播发送失败: ${e.message}")
            }
        }
    }
}

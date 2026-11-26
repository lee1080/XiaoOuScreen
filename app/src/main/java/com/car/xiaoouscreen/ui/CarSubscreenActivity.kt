package com.car.xiaoouscreen.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.AmbientLightManager
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.domain.Intensity
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneMapper
import com.car.xiaoouscreen.domain.WeatherState
import com.car.xiaoouscreen.location.CarGpsManager
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType
import com.carlyrics.CarLyricsApplication
import com.carlyrics.display.LyricsNormalizedContainerView
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.model.NotificationLyric
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private lateinit var smartSceneOverlayView: CarSmartSceneOverlayView
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
    private var lastCoverUrl: String? = null
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var lastKnownPosition: Long = 0L
    private var lastPositionNanos: Long = 0L
    private var currentTrackSource: com.carlyrics.model.MusicSource = com.carlyrics.model.MusicSource.UNKNOWN
    private var isMusicPlaying = false
    private var lyricsProgressJob: Job? = null
    private var lyricsFetchJob: Job? = null

    // 智能退避与弱网自愈调度状态
    private var currentMatchStatus = LyricsMatchStatus.IDLE
    private var durationWaitJob: Job? = null
    private var retryJob: Job? = null
    private var pendingOfflineTrack: CurrentTrack? = null
    private var primaryRetryCount = 0
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideCloseRunnable = Runnable {
        closeButtonContainer.visibility = View.GONE
    }
    private val fallbackToWeatherRunnable = Runnable {
        Log.i(TAG, "音乐暂停已达 2 秒，平滑回退至时间天气大时钟界面")
        setTopMusicTrackInfo("", "", isPlaying = false)
        switchToWeatherPage(smooth = true)
    }

    private var wasInLyricsBeforeCall: Boolean = false
    private var isResumedState = false

    private fun bringActivityToFront() {
        try {
            val intent = Intent(this, CarSubscreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            Log.i(TAG, "🛡️ [Activity登顶接管] 执行 bringActivityToFront 压制原厂媒体界面")
        } catch (e: Exception) {
            Log.w(TAG, "bringActivityToFront failed: ${e.message}")
        }
    }

    private val batteryInfoListener = object : CarGearManager.BatteryInfoListener {
        override fun onBatteryInfoChanged(info: CarGearManager.BatteryInfo) {
            runOnUiThread {
                weatherCanvasView.setBatteryInfo(info)
            }
        }
    }

    private val outsideTempListener = object : CarGearManager.OutsideTempListener {
        override fun onOutsideTempChanged(tempCelsius: Float) {
            runOnUiThread {
                weatherCanvasView.setCarOutsideTempC(tempCelsius)
            }
        }
    }

    private val cabinTempListener = object : CarGearManager.CabinTempListener {
        override fun onCabinTempChanged(tempCelsius: Float) {
            runOnUiThread {
                weatherCanvasView.setCarCabinTempC(tempCelsius)
            }
        }
    }

    private val motorPowerListener = object : CarGearManager.MotorPowerListener {
        override fun onMotorPowerChanged(info: CarGearManager.MotorPowerInfo) {
            runOnUiThread {
                weatherCanvasView.setMotorPowerInfo(info)
            }
        }
    }

    private val rpmListener = object : CarGearManager.RpmListener {
        override fun onRpmChanged(info: CarGearManager.RpmInfo) {
            runOnUiThread {
                weatherCanvasView.setRpmInfo(info)
            }
        }
    }

    private val laneChangeAlertListener = object : CarGearManager.LaneChangeAlertListener {
        override fun onLaneChangeAlertChanged(alert: CarGearManager.LaneChangeAlert) {
            runOnUiThread {
                weatherCanvasView.setLaneChangeAlert(alert)
                smartSceneOverlayView.setLaneChangeAlert(alert)
            }
        }
    }

    private val turnSignalListener = object : CarGearManager.TurnSignalListener {
        override fun onTurnSignalChanged(info: CarGearManager.TurnSignalInfo) {
            runOnUiThread {
                weatherCanvasView.setTurnSignalInfo(info)
                smartSceneOverlayView.setTurnSignalInfo(info)
            }
        }
    }

    private val callListener = object : CarCallManager.CallListener {
        override fun onCallChanged(callInfo: CarCallManager.CallInfo) {
            runOnUiThread {
                if (callInfo.isCallActive) {
                    if (currentPage == ActivePage.LYRICS) {
                        wasInLyricsBeforeCall = true
                        switchToWeatherPage(smooth = true)
                    }
                } else if (callInfo.state == CarCallManager.CallState.TERMINATED || callInfo.state == CarCallManager.CallState.IDLE) {
                    if (wasInLyricsBeforeCall) {
                        if (isMusicPlaying) {
                            switchToLyricsPage(smooth = true)
                        }
                        wasInLyricsBeforeCall = false
                    }
                    // 挂断电话且音乐正在恢复播放，若 Activity 被挤到后台则主动登顶接管
                    if (isMusicPlaying && !isResumedState) {
                        mainHandler.postDelayed({
                            if (!isResumedState && isMusicPlaying) {
                                bringActivityToFront()
                            }
                        }, 800L)
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
                    AmbientLightManager.setTimeOfDay(scene.timeOfDay)
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
                WeatherOverlayService.ACTION_UPDATE_TURN_SIGNAL_SYNC -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_SIGNAL_SYNC_ENABLED, PrefsManager.isTurnSignalSyncEnabled())
                    Log.i(TAG, "📡 副屏预览收到转向灯状态变更: enabled=$enabled")
                    weatherCanvasView.setTurnSignalSyncEnabled(enabled)
                    smartSceneOverlayView.setTurnSignalSyncEnabled(enabled)
                }
                WeatherOverlayService.ACTION_UPDATE_LANE_CHANGE_ASSIST -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_LANE_CHANGE_ASSIST_ENABLED, PrefsManager.isLaneChangeAssistEnabled())
                    Log.i(TAG, "📡 副屏预览收到变道辅助盲区光幕状态变更: enabled=$enabled")
                    weatherCanvasView.setLaneChangeAssistEnabled(enabled)
                    smartSceneOverlayView.setLaneChangeAssistEnabled(enabled)
                }
                WeatherOverlayService.ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_BOTTOM_LEFT_GEAR_HUD_ENABLED, PrefsManager.isBottomLeftGearHudEnabled())
                    Log.i(TAG, "📡 副屏预览收到左下角档位HUD状态变更: enabled=$enabled")
                    bottomLeftGearHudView.setHudEnabled(enabled, animate = true)
                    if (enabled) {
                        bottomLeftGearHudView.bringToFront()
                    }
                }
                WeatherOverlayService.ACTION_UPDATE_MINI_GEAR_POSITION -> {
                    val position = intent.getIntExtra(WeatherOverlayService.EXTRA_MINI_GEAR_POSITION, PrefsManager.getMiniGearPosition())
                    Log.i(TAG, "📡 副屏预览收到微型档位位置更新: position=$position")
                    bottomLeftGearHudView.setPosition(MiniGearPosition.fromId(position), animate = true)
                    bottomLeftGearHudView.bringToFront()
                }
                WeatherOverlayService.ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MINI_GEAR_REALTIME_COLOR, PrefsManager.isMiniGearRealtimeColorEnabled())
                    Log.i(TAG, "📡 副屏预览收到微型档位实时背景自适应更新: enabled=$enabled")
                    bottomLeftGearHudView.setRealtimeColorEnabled(enabled)
                }
                WeatherOverlayService.ACTION_UPDATE_XIAOOU_AVATAR -> {
                    val enabled = intent.getBooleanExtra(WeatherOverlayService.EXTRA_XIAOOU_AVATAR_ENABLED, PrefsManager.isXiaoOuAvatarEnabled())
                    Log.i(TAG, "📡 副屏预览收到小欧形象避让状态变更: enabled=$enabled")
                    weatherCanvasView.setXiaoOuAvoidance(enabled)
                    lyricsContainerView.xiaoOuAvoidanceEnabled = enabled
                }
                WeatherOverlayService.ACTION_UPDATE_SLOT_LAYOUT -> {
                    val slotAId = intent.getStringExtra(WeatherOverlayService.EXTRA_SLOT_A) ?: PrefsManager.getXiaoOuSlotA()
                    val slotCId = intent.getStringExtra(WeatherOverlayService.EXTRA_SLOT_C) ?: PrefsManager.getXiaoOuSlotC()
                    val slotA = XiaoOuSlotType.fromId(slotAId, XiaoOuSlotType.WEATHER)
                    val slotC = XiaoOuSlotType.fromId(slotCId, XiaoOuSlotType.ALBUM_COVER)
                    Log.i(TAG, "📡 副屏预览收到小欧屏 A/C 分区布局变更: slotA=$slotA, slotC=$slotC")
                    PrefsManager.setXiaoOuSlotA(slotA.id)
                    PrefsManager.setXiaoOuSlotC(slotC.id)
                    weatherCanvasView.setSlotConfig(slotA, slotC, smooth = true)
                }
                WeatherOverlayService.ACTION_UPDATE_LYRICS_SETTINGS -> {
                    Log.i(TAG, "📡 副屏预览收到歌词设置/预设变更广播，立即同步运行时状态")
                    if (intent.hasExtra("extra_standalone_lyrics_enabled")) {
                        PrefsManager.setStandaloneLyricsEnabled(intent.getBooleanExtra("extra_standalone_lyrics_enabled", false))
                    }
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

                    if (PrefsManager.isStandaloneLyricsEnabled()) {
                        if (isMusicPlaying) {
                            switchToLyricsPage(smooth = true)
                        }
                    } else if (currentPage == ActivePage.LYRICS) {
                        switchToWeatherPage(smooth = true)
                    }
                }
                WeatherOverlayService.ACTION_SIMULATE_MUSIC -> {
                    val playing = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MUSIC_PLAYING, true)
                    val title = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_TITLE) ?: "See You Again (feat. Charlie Puth)"
                    val artist = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_ARTIST) ?: "Wiz Khalifa / Charlie Puth"
                    val duration = intent.getLongExtra("extra_music_duration", 0L)
                    val cover = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_COVER)
                    val pkg = intent.getStringExtra("extra_music_package") ?: ""
                    val mediaId = intent.getStringExtra("extra_music_media_id") ?: ""
                    onTrackStateChanged(CurrentTrack(title = title, artist = artist, isPlaying = playing, position = 15000L, duration = duration, nativeCoverUrl = cover, packageName = pkg, mediaId = mediaId))
                }
                WeatherOverlayService.ACTION_SIMULATE_BATTERY -> {
                    val voltage = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_VOLTAGE)) {
                        intent.getFloatExtra(WeatherOverlayService.EXTRA_BATTERY_VOLTAGE, -1f).takeIf { it > 0f }
                    } else null
                    val soh = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_SOH)) {
                        intent.getIntExtra(WeatherOverlayService.EXTRA_BATTERY_SOH, -1).takeIf { it in 1..100 }
                    } else null
                    val soc = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_SOC)) {
                        intent.getIntExtra(WeatherOverlayService.EXTRA_BATTERY_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val low = intent.getBooleanExtra(WeatherOverlayService.EXTRA_BATTERY_LOW, false)
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateBattery(voltage, soh, soc, low)
                }
                WeatherOverlayService.ACTION_SIMULATE_TEMPS -> {
                    val outT = if (intent.hasExtra(WeatherOverlayService.EXTRA_OUTSIDE_TEMP)) {
                        intent.getFloatExtra(WeatherOverlayService.EXTRA_OUTSIDE_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val cabT = if (intent.hasExtra(WeatherOverlayService.EXTRA_CABIN_TEMP)) {
                        intent.getFloatExtra(WeatherOverlayService.EXTRA_CABIN_TEMP, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateTemps(outT, cabT)
                }
                WeatherOverlayService.ACTION_SIMULATE_GFORCE -> {
                    val gx = intent.getFloatExtra(WeatherOverlayService.EXTRA_GFORCE_X, 0f)
                    val gy = intent.getFloatExtra(WeatherOverlayService.EXTRA_GFORCE_Y, 0f)
                    val holdMs = intent.getLongExtra(WeatherOverlayService.EXTRA_GFORCE_HOLD_MS, 15_000L)
                    com.car.xiaoouscreen.car.CarGForceManager.getInstance(this@CarSubscreenActivity).simulateGForce(gx, gy, holdMs)
                }
                WeatherOverlayService.ACTION_SIMULATE_MOTOR_POWER -> {
                    val kw = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_POWER_KW)) {
                        intent.getFloatExtra(WeatherOverlayService.EXTRA_MOTOR_POWER_KW, Float.NaN).takeIf { !it.isNaN() }
                    } else null
                    val hvSoc = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_HV_SOC)) {
                        intent.getIntExtra(WeatherOverlayService.EXTRA_MOTOR_HV_SOC, -1).takeIf { it in 0..100 }
                    } else null
                    val plugCharging = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MOTOR_PLUG_CHARGING, false)
                    val resetPeaks = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MOTOR_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateMotorPower(kw, hvSoc, plugCharging, resetPeaks)
                }
                WeatherOverlayService.ACTION_SIMULATE_RPM -> {
                    val eng = if (intent.hasExtra(WeatherOverlayService.EXTRA_ENGINE_RPM)) {
                        intent.getIntExtra(WeatherOverlayService.EXTRA_ENGINE_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val mot = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_RPM)) {
                        intent.getIntExtra(WeatherOverlayService.EXTRA_MOTOR_RPM, -1).takeIf { it >= 0 }
                    } else null
                    val resetPeaks = intent.getBooleanExtra(WeatherOverlayService.EXTRA_RPM_RESET_PEAKS, false)
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateRpm(eng, mot, resetPeaks)
                }
                WeatherOverlayService.ACTION_SIMULATE_LANE_CHANGE -> {
                    val left = intent.getIntExtra(WeatherOverlayService.EXTRA_LANE_LEFT_WARNING, 0)
                    val right = intent.getIntExtra(WeatherOverlayService.EXTRA_LANE_RIGHT_WARNING, 0)
                    val bsdOn = intent.getBooleanExtra(WeatherOverlayService.EXTRA_LANE_BSD_ON, true)
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateLaneChangeAlert(left, right, bsdOn)
                }
                WeatherOverlayService.ACTION_SIMULATE_TURN_SIGNAL -> {
                    val left = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_LEFT, false)
                    val right = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_RIGHT, false)
                    val hazard = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_HAZARD, false)
                    val avmDir = intent.getIntExtra(WeatherOverlayService.EXTRA_TURN_AVM, 0)
                    CarGearManager.getInstance(this@CarSubscreenActivity).simulateTurnSignal(left, right, hazard, avmDir)
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
        smartSceneOverlayView = findViewById(R.id.smartSceneOverlayView)
        closeButtonContainer = findViewById(R.id.closeButtonContainer)

        findViewById<View>(R.id.tvCloseButton).setOnClickListener {
            finish()
        }

        setupNativeAnimationViews()
        startMusicObservation()

        smartSceneOverlayView.setTurnSignalSyncEnabled(PrefsManager.isTurnSignalSyncEnabled())
        smartSceneOverlayView.setLaneChangeAssistEnabled(PrefsManager.isLaneChangeAssistEnabled())

        // 默认显示时间天气大时钟主屏
        weatherCanvasView.alpha = 1f
        weatherCanvasView.visibility = View.VISIBLE
        weatherCanvasView.resumeAnimation()
        weatherCanvasView.setLiquidGlassEnabled(PrefsManager.isLiquidGlassClockEnabled(), smooth = false)

        // 初始化左下角常驻微型档位 HUD (置顶)
        bottomLeftGearHudView.backgroundProvider = { canvas, _ ->
            drawActiveUnderlyingView(canvas, sampleRegionOnly = true)
        }
        bottomLeftGearHudView.weatherLeftProvider = {
            weatherCanvasView.weatherHudLeftX
        }
        weatherCanvasView.onSceneRenderUpdated = {
            bottomLeftGearHudView.invalidateBackground()
        }
        lyricsContainerView.onBackgroundUpdated = {
            bottomLeftGearHudView.invalidateBackground()
        }
        val initialPos = PrefsManager.getMiniGearPosition()
        bottomLeftGearHudView.setPosition(MiniGearPosition.fromId(initialPos), animate = false)
        bottomLeftGearHudView.setRealtimeColorEnabled(PrefsManager.isMiniGearRealtimeColorEnabled())
        bottomLeftGearHudView.setHudEnabled(PrefsManager.isBottomLeftGearHudEnabled(), animate = false)
        bottomLeftGearHudView.bringToFront()

        val shouldAvoid = PrefsManager.isXiaoOuAvoidanceEnabled()
        lyricsContainerView.alpha = 0f
        lyricsContainerView.visibility = View.GONE
        lyricsContainerView.pause()
        lyricsContainerView.xiaoOuAvoidanceEnabled = shouldAvoid
        weatherCanvasView.setXiaoOuAvoidance(shouldAvoid, smooth = false)
        weatherCanvasView.setSlotConfig(
            XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotA(), XiaoOuSlotType.WEATHER),
            XiaoOuSlotType.fromId(PrefsManager.getXiaoOuSlotC(), XiaoOuSlotType.ALBUM_COVER),
            smooth = false
        )

        // 注册切档、驾驶模式与 12V 蓄电池总线监听
        val carManager = CarGearManager.getInstance(this).apply {
            init()
            registerListener(this@CarSubscreenActivity)
            registerDriveModeListener(this@CarSubscreenActivity)
            registerBatteryInfoListener(batteryInfoListener)
            registerOutsideTempListener(outsideTempListener)
            registerCabinTempListener(cabinTempListener)
            registerMotorPowerListener(motorPowerListener)
            registerRpmListener(rpmListener)
            registerLaneChangeAlertListener(laneChangeAlertListener)
            registerTurnSignalListener(turnSignalListener)
            refreshCabinTempNow()
            refreshBatteryInfoNow()
        }
        weatherCanvasView.setBatteryInfo(carManager.getCurrentBatteryInfo())
        weatherCanvasView.setCarOutsideTempC(carManager.getCurrentOutsideTempC())
        weatherCanvasView.setCarCabinTempC(carManager.getCurrentCabinTempC())
        weatherCanvasView.setMotorPowerInfo(carManager.getCurrentMotorPowerInfo())
        weatherCanvasView.setRpmInfo(carManager.getCurrentRpmInfo())
        weatherCanvasView.setLaneChangeAlert(carManager.currentLaneChangeAlert)
        weatherCanvasView.setTurnSignalInfo(carManager.currentTurnSignalInfo)
        smartSceneOverlayView.setLaneChangeAlert(carManager.currentLaneChangeAlert)
        smartSceneOverlayView.setTurnSignalInfo(carManager.currentTurnSignalInfo)
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
            addAction(WeatherOverlayService.ACTION_UPDATE_TURN_SIGNAL_SYNC)
            addAction(WeatherOverlayService.ACTION_UPDATE_LANE_CHANGE_ASSIST)
            addAction(WeatherOverlayService.ACTION_UPDATE_BOTTOM_LEFT_GEAR_HUD)
            addAction(WeatherOverlayService.ACTION_UPDATE_MINI_GEAR_POSITION)
            addAction(WeatherOverlayService.ACTION_UPDATE_MINI_GEAR_REALTIME_COLOR)
            addAction(WeatherOverlayService.ACTION_UPDATE_XIAOOU_AVATAR)
            addAction(WeatherOverlayService.ACTION_UPDATE_SLOT_LAYOUT)
            addAction(WeatherOverlayService.ACTION_UPDATE_LYRICS_SETTINGS)
            addAction(WeatherOverlayService.ACTION_SIMULATE_MUSIC)
            addAction(WeatherOverlayService.ACTION_SIMULATE_BATTERY)
            addAction(WeatherOverlayService.ACTION_SIMULATE_TEMPS)
            addAction(WeatherOverlayService.ACTION_SIMULATE_GFORCE)
            addAction(WeatherOverlayService.ACTION_SIMULATE_MOTOR_POWER)
            addAction(WeatherOverlayService.ACTION_SIMULATE_RPM)
            addAction(WeatherOverlayService.ACTION_SIMULATE_LANE_CHANGE)
            addAction(WeatherOverlayService.ACTION_SIMULATE_TURN_SIGNAL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simulationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(simulationReceiver, filter)
        }

        // 组装天气与 GPS 依赖 (车规级 ViewModelProvider 纳管)
        viewModel = androidx.lifecycle.ViewModelProvider(this, object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val repository = OpenMeteoRepository(applicationContext)
                val gpsManager = CarGpsManager.getInstance(applicationContext)
                @Suppress("UNCHECKED_CAST")
                return WeatherClockViewModel(application, repository, gpsManager) as T
            }
        })[WeatherClockViewModel::class.java]

        // 响应式收集天气场景状态并驱动 View 渲染
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneConfig.collect { scene ->
                    weatherCanvasView.updateScene(scene)
                    AmbientLightManager.setTimeOfDay(scene.timeOfDay)
                }
            }
        }

        viewModel.start()
        registerNetworkCallback()
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
        smartSceneOverlayView.bringToFront()
    }

    // ==================== 档位与驾驶模式监听 ====================

    override fun onGearChanged(gear: String, rawValue: Int) {
        // 关键：首次进入软件仅记录初始状态，坚决不自动触发翻转动画！
        if (isFirstGearEvent) {
            isFirstGearEvent = false
            lastReportedGear = gear
            weatherCanvasView.setGear(gear)
            bottomLeftGearHudView.setGear(gear, animate = false)
            bottomLeftGearHudView.bringToFront()
            smartSceneOverlayView.bringToFront()
            Log.i(TAG, "🚗 副屏初始档位静默就绪: $gear (首次进入不触发动画)")
            return
        }
        if (lastReportedGear == gear) return
        lastReportedGear = gear

        weatherCanvasView.setGear(gear)
        bottomLeftGearHudView.setGear(gear, animate = true)
        bottomLeftGearHudView.bringToFront()
        smartSceneOverlayView.bringToFront()
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
                smartSceneOverlayView.bringToFront()

                gearCubeView.triggerGearFlip(gear) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
            } catch (t: Throwable) {
                AppLogger.e(TAG, "触发档位翻转异常: ${t.message}", t)
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
                bottomLeftGearHudView.bringToFront()
                smartSceneOverlayView.bringToFront()

                driveModeView.triggerDriveModeSwitch(mode) { canvas ->
                    drawActiveUnderlyingView(canvas)
                }
                updateUnderlyingRenderState()
            } catch (t: Throwable) {
                AppLogger.e(TAG, "触发驾驶模式擦除异常: ${t.message}", t)
            }
        }
    }

    private fun drawActiveUnderlyingView(canvas: Canvas, sampleRegionOnly: Boolean = false) {
        val lyricsVisible = lyricsContainerView.visibility == View.VISIBLE && lyricsContainerView.alpha > 0.01f
        val lyricsAlpha = if (lyricsVisible) lyricsContainerView.alpha.coerceIn(0f, 1f) else 0f

        if (lyricsAlpha < 0.99f) {
            if (weatherCanvasView.visibility == View.VISIBLE || lyricsAlpha <= 0.01f) {
                weatherCanvasView.drawStrict1920(canvas, backgroundOnlyForGlassSample = sampleRegionOnly)
            } else {
                canvas.drawColor(Color.BLACK)
            }
        }
        if (lyricsVisible) {
            if (lyricsAlpha >= 0.99f) {
                lyricsContainerView.drawStrict1920(canvas)
            } else {
                val saveCount = canvas.saveLayerAlpha(0f, 0f, 1920f, 384f, (lyricsAlpha * 255f).toInt().coerceIn(0, 255))
                lyricsContainerView.drawStrict1920(canvas)
                canvas.restoreToCount(saveCount)
            }
        }
    }

    // ==================== 歌词监听与双页面丝滑切换 ====================

    private fun startMusicObservation() {
        lifecycleScope.launch {
            CarLyricsApplication.recognitionEngine.currentTrack.collectLatest { track ->
                onTrackStateChanged(track)
            }
        }
        lifecycleScope.launch {
            CarLyricsApplication.recognitionEngine.liveNotificationLyric.collectLatest { notifLyric ->
                onNotificationLyricChanged(notifLyric)
            }
        }
    }

    private fun onNotificationLyricChanged(notifLyric: NotificationLyric?) {
        if (notifLyric == null || notifLyric.text.isBlank()) return
        if (!isMusicPlaying) return

        // 核心兜底策略：如果当前尚未匹配到全量完整歌词 (例如处于 WAITING_DURATION、RETRYABLE_PENDING、或弱网断网中)，
        // 立即使用通知栏实时动态嗅探出的单行歌词进行平滑渲染，消灭等待期与弱网空白！
        if (currentMatchStatus != LyricsMatchStatus.MATCHED) {
            val singleLine = listOf(
                LyricLine(timestamp = 0L, text = notifLyric.text),
                LyricLine(timestamp = 86_400_000L, text = "")
            )
            currentLyricLines = singleLine
            lyricsContainerView.setLyrics(singleLine)
        }
    }

    private fun updateLyricsUiAfterMatched(lines: List<LyricLine>, title: String, artist: String, playing: Boolean) {
        try {
            com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onLyricsLoaded(lines)
        } catch (_: Exception) {}
        lyricsContainerView.setLyrics(lines)
        if (playing) {
            setTopMusicTrackInfo(title, artist, isPlaying = true)
            if (PrefsManager.isStandaloneLyricsEnabled()) {
                switchToLyricsPage(smooth = true)
            }
        }
    }

    private fun onTrackStateChanged(track: CurrentTrack?) {
        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onTrackChanged(track)
        if (track == null || track.title.isBlank() || MusicRecognitionEngine.isInvalidPlaceholderTrack(track.title, track.artist)) {
            val wasActiveBeforeNull = isMusicPlaying || currentPage == ActivePage.LYRICS
            isMusicPlaying = false
            lyricsProgressJob?.cancel()
            lyricsProgressJob = null
            lastPositionNanos = 0L

            if (wasActiveBeforeNull) {
                setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = false)
                mainHandler.removeCallbacks(fallbackToWeatherRunnable)
                mainHandler.postDelayed(fallbackToWeatherRunnable, 2000L)
            } else {
                currentMatchStatus = LyricsMatchStatus.IDLE
                pendingOfflineTrack = null
                durationWaitJob?.cancel()
                lyricsFetchJob?.cancel()
                retryJob?.cancel()
                mainHandler.removeCallbacks(fallbackToWeatherRunnable)
                setTopMusicTrackInfo("", "", isPlaying = false)
                switchToWeatherPage(smooth = true)
            }
            return
        }

        val title = track.title.trim()
        val artist = track.artist.trim()
        val playing = track.isPlaying

        val wasPlayingBefore = isMusicPlaying
        isMusicPlaying = playing
        val isTrackChanged = (title != lastTrackTitle || artist != lastTrackArtist)
        val shouldResetAnchor = isTrackChanged || (!wasPlayingBefore && playing) ||
            (track.position != lastKnownPosition) || lastPositionNanos <= 0L
        currentTrackSource = track.source
        if (shouldResetAnchor) {
            lastKnownPosition = track.position
            lastPositionNanos = System.nanoTime()
        }

        if (isTrackChanged) {
            lastTrackTitle = title
            lastTrackArtist = artist
            lastCoverUrl = null
            currentMatchStatus = LyricsMatchStatus.IDLE
            primaryRetryCount = 0
            pendingOfflineTrack = null
            durationWaitJob?.cancel()
            lyricsFetchJob?.cancel()
            retryJob?.cancel()
            currentLyricLines = emptyList()
            weatherCanvasView.setSlotTrackInfo(title, artist, isPlaying = playing)
            weatherCanvasView.setAlbumCoverUrl("", smooth = false)
            lyricsContainerView.setTrackInfo(title, artist)
            lyricsContainerView.setCoverUrl("")

            if (!track.nativeLyrics.isNullOrEmpty()) {
                // 优先采用原车/U盘原生歌词，同时同步原生封面或在线补拉封面
                val nativeLines = track.nativeLyrics
                currentLyricLines = nativeLines
                currentMatchStatus = LyricsMatchStatus.MATCHED
                val nativeCover = track.nativeCoverUrl?.takeIf { it.isNotBlank() }
                if (nativeCover != null) {
                    applySubscreenCoverUrl(nativeCover, smooth = true)
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            if (!LyricsApiManager.isNetworkAvailable(this@CarSubscreenActivity)) return@launch
                            val fallbackCover = lyricsApi.fetchCover(title, artist, track.packageName)
                            if (!fallbackCover.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    if (title == lastTrackTitle && artist == lastTrackArtist && lastCoverUrl.isNullOrBlank()) {
                                        applySubscreenCoverUrl(fallbackCover, smooth = true)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                updateLyricsUiAfterMatched(nativeLines, title, artist, playing)
            } else if (track.duration > 15000L) {
                // 时长已就绪，立即发起单次精准请求
                fetchLyricsAndCover(title, artist, track, isRetry = false)
            } else {
                // 时长未就绪 (切歌缓冲中 / duration <= 15s)，开启 800ms 沉淀等待窗口
                currentMatchStatus = LyricsMatchStatus.WAITING_DURATION
                durationWaitJob = lifecycleScope.launch(Dispatchers.Main) {
                    delay(800L)
                    if (currentMatchStatus == LyricsMatchStatus.WAITING_DURATION) {
                        AppLogger.d(TAG, "800ms 时长沉淀超时，以现有元数据发起单次获取: $title - $artist")
                        val latestTrack = CarLyricsApplication.recognitionEngine.currentTrack.value ?: track
                        fetchLyricsAndCover(title, artist, latestTrack, isRetry = false)
                    }
                }
            }
        } else {
            val delayedNativeLyrics = track.nativeLyrics
            if (!delayedNativeLyrics.isNullOrEmpty() && delayedNativeLyrics != currentLyricLines) {
                durationWaitJob?.cancel()
                lyricsFetchJob?.cancel()
                retryJob?.cancel()
                currentLyricLines = delayedNativeLyrics
                currentMatchStatus = LyricsMatchStatus.MATCHED
                AppLogger.i(TAG, "🎶 [同曲原生词刷新] 检测到延迟/更新的原生歌词 (${delayedNativeLyrics.size}行)，立即覆盖同步: $title")
                updateLyricsUiAfterMatched(delayedNativeLyrics, title, artist, playing)
            }
            val delayedNativeCover = track.nativeCoverUrl?.takeIf { it.isNotBlank() }
            if (delayedNativeCover != null && lastCoverUrl.isNullOrBlank()) {
                applySubscreenCoverUrl(delayedNativeCover, smooth = true)
            }
            if (currentMatchStatus == LyricsMatchStatus.WAITING_DURATION && track.duration > 15000L) {
                // 800ms 沉淀窗口期内收到了有效时长！立即取消定时器，带上精准时长发起唯一的 1 次请求！
                durationWaitJob?.cancel()
                durationWaitJob = null
                AppLogger.i(TAG, "⚡️ [时长对齐] 沉淀期内成功捕获真实时长 (${track.duration}ms)，立即发起精准请求: $title")
                fetchLyricsAndCover(title, artist, track, isRetry = false)
            } else if (currentMatchStatus == LyricsMatchStatus.MATCHED) {
                lyricsContainerView.setTrackInfo(lastTrackTitle, lastTrackArtist)
                weatherCanvasView.setSlotTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = playing)
                if (lastCoverUrl != null) {
                    lyricsContainerView.setCoverUrl(lastCoverUrl)
                    weatherCanvasView.setAlbumCoverUrl(lastCoverUrl, smooth = false)
                }
                if (currentLyricLines.isNotEmpty()) {
                    lyricsContainerView.setLyrics(currentLyricLines)
                }
            }
        }

        if (playing) {
            mainHandler.removeCallbacks(fallbackToWeatherRunnable)
            if (!wasPlayingBefore && !isResumedState) {
                // 音乐恢复播放但界面不在前台，登顶接管
                bringActivityToFront()
            }
            val hasRealLyrics = currentMatchStatus == LyricsMatchStatus.MATCHED ||
                (currentLyricLines.isNotEmpty() && currentLyricLines.none { it.text == "暂无未匹配到歌词" })
            if (PrefsManager.isShowDefaultClockWhenNoLyrics() && currentMatchStatus == LyricsMatchStatus.PERMANENT_NOT_FOUND && !hasRealLyrics) {
                switchToWeatherPage(smooth = false)
            } else if (PrefsManager.isStandaloneLyricsEnabled()) {
                switchToLyricsPage(smooth = true)
            } else {
                switchToWeatherPage(smooth = false)
            }
            setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = true)
            startLyricsProgressTicker()
        } else {
            // 立即停止走时协程并定格静止
            lyricsProgressJob?.cancel()
            lyricsProgressJob = null
            lyricsContainerView.updateProgress(track.position)
            setTopMusicTrackInfo(lastTrackTitle, lastTrackArtist, isPlaying = false)
            mainHandler.removeCallbacks(fallbackToWeatherRunnable)
            mainHandler.postDelayed(fallbackToWeatherRunnable, 2000L)
        }
    }

    private fun schedulePrimaryRetry(track: CurrentTrack) {
        retryJob?.cancel()
        if (primaryRetryCount >= 2) return
        val nextRetryIndex = primaryRetryCount + 1
        val targetPosMs = if (nextRetryIndex == 1) 3000L else 8000L

        retryJob = lifecycleScope.launch(Dispatchers.Main) {
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

            if (!LyricsApiManager.isNetworkAvailable(this@CarSubscreenActivity)) {
                AppLogger.d(TAG, "退避重试时处于断网态，挂起并等待网络恢复: ${latest.title}")
                pendingOfflineTrack = latest
                return@launch
            }

            primaryRetryCount++
            AppLogger.i(TAG, "🚀 [第一顺位源专属退避重试] 发起第 $primaryRetryCount 次重试: ${latest.title} (进度=${latest.position}ms)")
            fetchLyricsAndCover(latest.title, latest.artist, latest, isRetry = true)
        }
    }

    private fun applySubscreenCoverUrl(coverUrl: String, smooth: Boolean = true) {
        lastCoverUrl = coverUrl
        lyricsContainerView.setCoverUrl(coverUrl)
        weatherCanvasView.setAlbumCoverUrl(coverUrl, smooth = smooth)
        com.carlyrics.util.LyricsCacheManager.loadCoverBitmapScaled(this@CarSubscreenActivity, coverUrl, 320) { bmp: android.graphics.Bitmap? ->
            if (bmp != null) {
                lyricsContainerView.setCoverBitmap(bmp)
                try {
                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onCoverLoaded(bmp)
                } catch (_: Exception) {}
            }
        }
    }

    private fun fetchLyricsAndCover(title: String, artist: String, track: CurrentTrack? = null, isRetry: Boolean = false) {
        lyricsFetchJob?.cancel()
        lyricsApi.cancelAllRequests()
        lyricsContainerView.setTrackInfo(title, artist)
        if (isMusicPlaying) {
            setTopMusicTrackInfo(title, artist, isPlaying = true)
        }

        lyricsFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                coroutineContext.ensureActive()
                val currentTrack = track ?: CarLyricsApplication.recognitionEngine.currentTrack.value

                // 弱网与离线检查：若彻底断网且无本地原生歌词，立即挂起并退出，零无意义网络请求
                val hasNet = LyricsApiManager.isNetworkAvailable(this@CarSubscreenActivity)
                val isNativeLyricsAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeLyrics.isNullOrEmpty()

                if (!hasNet && !isNativeLyricsAvailable) {
                    AppLogger.i(TAG, "🌐 [离线保护] 当前处于断网态，挂起当前曲目等待网络恢复: $title - $artist")
                    pendingOfflineTrack = currentTrack
                    currentMatchStatus = LyricsMatchStatus.RETRYABLE_PENDING
                    return@launch
                }

                // 1. 封面处理：优先使用原车/U盘本地提取封面，若无则轻量级补拉在线封面
                val isNativeCoverAvailable = currentTrack != null &&
                        currentTrack.title.equals(title, ignoreCase = true) &&
                        !currentTrack.nativeCoverUrl.isNullOrBlank()

                val coverUrl = if (isNativeCoverAvailable) {
                    currentTrack!!.nativeCoverUrl
                } else {
                    try {
                        lyricsApi.fetchCover(title, artist, currentTrack?.packageName)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) { null }
                }

                coroutineContext.ensureActive()
                if (title != lastTrackTitle || artist != lastTrackArtist) return@launch
                lastCoverUrl = coverUrl
                withContext(Dispatchers.Main) {
                    if (title == lastTrackTitle && artist == lastTrackArtist && coverUrl != null) {
                        lyricsContainerView.setCoverUrl(coverUrl)
                        weatherCanvasView.setAlbumCoverUrl(coverUrl, smooth = true)
                        com.carlyrics.util.LyricsCacheManager.loadCoverBitmapScaled(this@CarSubscreenActivity, coverUrl, 320) { bmp: android.graphics.Bitmap? ->
                            if (bmp != null) {
                                lyricsContainerView.setCoverBitmap(bmp)
                                try {
                                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onCoverLoaded(bmp)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                coroutineContext.ensureActive()
                // 2. 歌词处理：优先使用原车酷狗/U盘离线自带歌词，次优请求第一顺位源 (primaryOnly)，重试耗尽后才走次选瀑布流
                val lines = if (isNativeLyricsAvailable) {
                    currentMatchStatus = LyricsMatchStatus.MATCHED
                    currentTrack!!.nativeLyrics!!
                } else {
                    currentMatchStatus = LyricsMatchStatus.FETCHING_PRIMARY
                    // 阶段 1：第一顺位源专属精准请求 (防错版截胡)
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
                    if (title != lastTrackTitle || artist != lastTrackArtist) return@launch
                    val rawLines = primaryResult.getOrNull()?.lines?.filter { it.text.isNotBlank() } ?: emptyList()

                    if (rawLines.isNotEmpty() && lyricsApi.isSubstantiveLyrics(rawLines)) {
                        currentMatchStatus = LyricsMatchStatus.MATCHED
                        pendingOfflineTrack = null
                        rawLines
                    } else {
                        // 第一顺位源未命中有效歌词
                        val err = primaryResult.exceptionOrNull()
                        val isNetErr = LyricsApiManager.isNetworkError(err)
                        if (isNetErr && !LyricsApiManager.isNetworkAvailable(this@CarSubscreenActivity)) {
                            pendingOfflineTrack = currentTrack
                        }

                        if (primaryRetryCount < 2) {
                            // 尚有重试配额，保持 RETRYABLE_PENDING 状态并安排退避重试
                            currentMatchStatus = LyricsMatchStatus.RETRYABLE_PENDING
                            withContext(Dispatchers.Main) {
                                currentTrack?.let { schedulePrimaryRetry(it) }
                            }
                            emptyList()
                        } else {
                            // 第一顺位源 3 次机会已用尽，此时放行【次选瀑布流兜底】
                            AppLogger.i(TAG, "⚠️ 第一顺位源 3 次重试均无词，放行次选瀑布流与官方级联兜底: $title - $artist")
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
                            if (title != lastTrackTitle || artist != lastTrackArtist) return@launch
                            val fallbackLines = fallbackResult.getOrNull()?.lines?.filter { it.text.isNotBlank() } ?: emptyList()
                            if (fallbackLines.isNotEmpty() && lyricsApi.isSubstantiveLyrics(fallbackLines)) {
                                currentMatchStatus = LyricsMatchStatus.MATCHED
                                pendingOfflineTrack = null
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
                    try {
                        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onLyricsLoaded(lines)
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    if (title == lastTrackTitle && artist == lastTrackArtist) {
                        if (lines.isNotEmpty()) {
                            lyricsContainerView.setLyrics(lines)
                        }
                        if (isMusicPlaying) {
                            setTopMusicTrackInfo(title, artist, isPlaying = true)
                        }
                        val isLyricsMatched = lines.isNotEmpty() && lyricsApi.isSubstantiveLyrics(lines)
                        if (!isLyricsMatched && PrefsManager.isShowDefaultClockWhenNoLyrics() && currentMatchStatus == LyricsMatchStatus.PERMANENT_NOT_FOUND) {
                            switchToWeatherPage(smooth = true)
                        } else if (PrefsManager.isStandaloneLyricsEnabled() && isMusicPlaying && isLyricsMatched) {
                            switchToLyricsPage(smooth = true)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // 协程已及时取消，安全退出
            }
        }
    }

    private fun startLyricsProgressTicker() {
        lyricsProgressJob?.cancel()
        lyricsProgressJob = lifecycleScope.launch {
            while (isActive && isMusicPlaying) {
                val now = System.nanoTime()
                val rawDeltaMs = ((now - lastPositionNanos) / 1_000_000L).coerceAtLeast(0L)
                val deltaMs = if (currentTrackSource == com.carlyrics.model.MusicSource.BLUETOOTH && lastKnownPosition > 0L) {
                    rawDeltaMs.coerceAtMost(2200L)
                } else {
                    rawDeltaMs
                }
                val currentPos = lastKnownPosition + deltaMs

                if (currentPage != ActivePage.LYRICS) {
                    // 当处于天气/时钟表盘页时，挂起 60FPS 歌词重绘，仅以 500ms 极低频同步时钟锚点，维持 1.0 FPS 极致省电
                    try {
                        com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onPositionUpdate(currentPos)
                    } catch (_: Exception) {}
                    delay(500L)
                    continue
                }

                try {
                    com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onPositionUpdate(currentPos)
                } catch (_: Exception) {}
                lyricsContainerView.updateProgress(currentPos)
                delay(16L) // 60FPS 逐字走时
            }
        }
    }

    fun switchToLyricsPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.LYRICS && lyricsContainerView.visibility == View.VISIBLE && lyricsContainerView.alpha == 1f) {
            weatherCanvasView.setTopMusicTrackInfo("", "", isPlaying = false)
            return
        }
        currentPage = ActivePage.LYRICS

        // 全屏歌词状态下：隐藏顶部居中常驻音乐胶囊
        weatherCanvasView.setTopMusicTrackInfo("", "", isPlaying = false)

        lyricsContainerView.resume()
        lyricsContainerView.visibility = View.VISIBLE
        if (driveModeView.visibility == View.VISIBLE) driveModeView.bringToFront()
        if (gearCubeView.visibility == View.VISIBLE) gearCubeView.bringToFront()
        bottomLeftGearHudView.bringToFront()
        smartSceneOverlayView.bringToFront()

        if (smooth) {
            lyricsContainerView.animate().cancel()
            weatherCanvasView.animate().cancel()

            lyricsContainerView.animate()
                .alpha(1f)
                .setDuration(500L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener {
                    bottomLeftGearHudView.invalidateBackground()
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottomLeftGearHudView.invalidateBackground()
                    }
                })
                .start()

            weatherCanvasView.animate()
                ?.alpha(0f)
                ?.setDuration(500L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.LYRICS) {
                            weatherCanvasView.visibility = View.GONE
                            weatherCanvasView.pauseAnimation()
                            bottomLeftGearHudView.invalidateBackground()
                        }
                    }
                })
                ?.start()
        } else {
            lyricsContainerView.alpha = 1f
            weatherCanvasView.visibility = View.GONE
            weatherCanvasView.alpha = 0f
            weatherCanvasView.pauseAnimation()
            bottomLeftGearHudView.invalidateBackground()
        }
    }

    fun switchToWeatherPage(smooth: Boolean = true) {
        if (currentPage == ActivePage.WEATHER && weatherCanvasView.visibility == View.VISIBLE && weatherCanvasView.alpha == 1f) {
            checkAndRestoreTopMusicInfo(smooth)
            return
        }
        currentPage = ActivePage.WEATHER

        checkAndRestoreTopMusicInfo(smooth)

        weatherCanvasView.resumeAnimation()
        weatherCanvasView.visibility = View.VISIBLE
        if (driveModeView.visibility == View.VISIBLE) driveModeView.bringToFront()
        if (gearCubeView.visibility == View.VISIBLE) gearCubeView.bringToFront()
        bottomLeftGearHudView.bringToFront()
        smartSceneOverlayView.bringToFront()

        if (smooth) {
            weatherCanvasView.animate().cancel()
            lyricsContainerView.animate().cancel()

            weatherCanvasView.animate()
                .alpha(1f)
                .setDuration(800L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener {
                    bottomLeftGearHudView.invalidateBackground()
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottomLeftGearHudView.invalidateBackground()
                    }
                })
                .start()

            lyricsContainerView.animate()
                ?.alpha(0f)
                ?.setDuration(800L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentPage == ActivePage.WEATHER) {
                            lyricsContainerView.visibility = View.GONE
                            lyricsContainerView.pause()
                            bottomLeftGearHudView.invalidateBackground()
                        }
                    }
                })
                ?.start()
        } else {
            weatherCanvasView.alpha = 1f
            lyricsContainerView.visibility = View.GONE
            lyricsContainerView.alpha = 0f
            lyricsContainerView.pause()
            bottomLeftGearHudView.invalidateBackground()
        }
    }

    /**
     * 平滑隐藏顶部音乐播放信息胶囊 (岛)
     */
    fun hideTopMusicTrackInfo(smooth: Boolean = true) {
        weatherCanvasView.setTopMusicTrackInfo("", "", isPlaying = false)
    }

    /**
     * 评估并恢复天气主界面的顶部常驻音乐信息胶囊
     * - 仅在非全屏歌词模式且音乐正在播放时展示
     * - 如果没有音乐播放，恢复时间显示时必须隐藏这个岛！
     */
    private fun checkAndRestoreTopMusicInfo(smooth: Boolean) {
        val cleanTitle = lastTrackTitle.trim()
        val cleanArtist = lastTrackArtist.trim()
        val shouldShow = isMusicPlaying &&
            cleanTitle.isNotEmpty() &&
            !MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, cleanArtist) &&
            !PrefsManager.isStandaloneLyricsEnabled() &&
            currentPage != ActivePage.LYRICS

        if (shouldShow) {
            weatherCanvasView.setTopMusicTrackInfo(cleanTitle, cleanArtist, isPlaying = true)
        } else {
            weatherCanvasView.setTopMusicTrackInfo("", "", isPlaying = false)
        }
    }

    /**
     * 设置并更新小欧屏最顶部居中高亮音乐播放信息 (‘音乐图标’ 歌名-歌手名)
     * - 纯原生 1920×384 黄金比例直接渲染，实车与 PAD 缩放比例 100% 绝对一致
     * - 居中对齐，超出边界自动以省略号截断
     * - 播放时淡入展示；无音乐播放或恢复时间显示时优雅淡出收敛隐藏
     */
    fun setTopMusicTrackInfo(title: String, artist: String, isPlaying: Boolean) {
        lastTrackTitle = title
        lastTrackArtist = artist
        this.isMusicPlaying = isPlaying

        val cleanTitle = title.trim()
        val isValidTrack = cleanTitle.isNotEmpty() && !MusicRecognitionEngine.isInvalidPlaceholderTrack(cleanTitle, artist.trim())
        if (!isPlaying && !isValidTrack) {
            weatherCanvasView.setSlotTrackInfo("", "", isPlaying = false)
            weatherCanvasView.setAlbumCoverSlotVisible(false, smooth = true)
        } else if (isPlaying && isValidTrack) {
            weatherCanvasView.setSlotTrackInfo(title, artist, isPlaying = true)
            weatherCanvasView.setAlbumCoverSlotVisible(true, smooth = true)
        } else if (isValidTrack) {
            weatherCanvasView.setSlotTrackInfo(title, artist, isPlaying = false)
        } else {
            weatherCanvasView.setSlotMusicPlaying(false)
        }

        // 全屏歌词状态下 (ActivePage.LYRICS 或系统启用了全屏歌词且音乐正在播放)，不显示顶部的歌名等
        if (currentPage == ActivePage.LYRICS || PrefsManager.isStandaloneLyricsEnabled()) {
            weatherCanvasView.setTopMusicTrackInfo("", "", isPlaying = false)
            return
        }

        weatherCanvasView.setTopMusicTrackInfo(title, artist, isPlaying)
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
        isResumedState = true
        hideSystemUi()
        // 手机/单屏环境互斥：副屏独立全屏运行期间暂停后台浮窗绘制，杜绝双重 Canvas 争抢 GPU
        WeatherOverlayService.pauseOverlay()
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
        isResumedState = false
        weatherCanvasView.pauseAnimation()
        lyricsContainerView.pause()
        lyricsProgressJob?.cancel()
        WeatherOverlayService.resumeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(simulationReceiver)
        } catch (_: Exception) {}
        CarGearManager.getInstance(this).unregisterListener(this)
        CarGearManager.getInstance(this).unregisterDriveModeListener(this)
        CarGearManager.getInstance(this).unregisterBatteryInfoListener(batteryInfoListener)
        CarGearManager.getInstance(this).unregisterOutsideTempListener(outsideTempListener)
        CarGearManager.getInstance(this).unregisterCabinTempListener(cabinTempListener)
        CarGearManager.getInstance(this).unregisterMotorPowerListener(motorPowerListener)
        CarGearManager.getInstance(this).unregisterRpmListener(rpmListener)
        CarGearManager.getInstance(this).unregisterLaneChangeAlertListener(laneChangeAlertListener)
        CarGearManager.getInstance(this).unregisterTurnSignalListener(turnSignalListener)
        CarCallManager.getInstance(this).removeListener(callListener)
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        mainHandler.removeCallbacks(fallbackToWeatherRunnable)
        bottomLeftGearHudView.backgroundProvider = null
        bottomLeftGearHudView.weatherLeftProvider = null
        weatherCanvasView.onSceneRenderUpdated = null
        lyricsContainerView.onBackgroundUpdated = null
        lyricsProgressJob?.cancel()
        lyricsFetchJob?.cancel()
        durationWaitJob?.cancel()
        retryJob?.cancel()
        unregisterNetworkCallback()
        viewModel.stop()
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
}

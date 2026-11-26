package com.car.xiaoouscreen.car

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrefsManager
import com.car.xiaoouscreen.domain.TimeOfDay
import com.chinatsp.smartlights.aidl.ILightingClient
import com.chinatsp.smartlights.aidl.ILightsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 原厂座舱氛围灯双通道直控调度管理器
 *
 * 核心架构：
 * 通道 1：SmartLightsServer AIDL 服务调用 (原车 UI 协议层同步与实时双向监听)
 * 通道 2：CarGearManager -> CarCabinManager CAN 总线直接接管 (0x2141f3d5 / 0x2140f3e4 / 0x21400a25 硬件物理写入与读取)
 *
 * 核心特性：
 * 1. 【原车高优先级功能抢占与智能接管 (Smart Auto-Reclaim)】：
 *    - 深度适配小欧同学语音交互 (com.chinatsp.intent.action_VOICE_AWARED / DISAPPEAR)：唤醒时主动挂起避让原车蓝色语音流光，退出后 1.3 秒无缝重新接管；
 *    - 深度适配空调温度/风速调节 (0x2540f3b6 / 0x25400503)：温控操作时避让 5.5 秒展示冷暖流光，结束后满血恢复小欧屏音乐主色与律动；
 *    - 深度适配蓝牙电话通话中 (CarCallManager)：来电振铃与通话避让原车绿色警示，挂断后 1 秒重新接管；
 *    - 深度适配驾驶模式切换 (0x2140f030)：赛道/运动/ECO 模式变动时避让 5.5 秒后自动重新接管；
 *    - 原车单色重置看门狗 (Color Reclaim Watchdog)：当原车触发 recoveryLightingSetting 还原默认色时，延时 800ms 自动纠偏覆写封面色。
 * 2. 【原车亮度智能读取与实时监听】：
 *    - 自动通过 SmartLightsServer AIDL (type=101/0x65) 监听车机中控大屏当前设置与用户拖动调节；
 *    - 硬件层支持直接从 CarCabinManager CAN 寄存器读取初始硬件档位。
 * 3. 【多模式基准亮度与等比动态缩放】：
 *    - 支持在小欧屏设置 10%~100% 自定义基准。
 * 4. 【昼夜时段模式与 50% 等比衰减保护】：
 *    - 白天时段（DAWN / DAY）：实发 = round(律动等级 * 基准%)；
 *    - 傍晚/夜间时段（DUSK / NIGHT）：实发 = round(律动等级 * 基准% * 50%)；
 *    - 音乐暂停恢复常亮：白天恢复基准亮度，夜间自动按 50% 保护常亮。
 */
object AmbientLightManager {

    private const val TAG = "AmbientLightManager"
    private const val SERVER_PACKAGE = "com.chinatsp.smartlights"
    private const val SERVER_CLASS = "com.chinatsp.smartlights.SmartLightsServer"

    // 氛围灯控制 SwitchType 常量 (依据 SmartLightsServer 反编译协议)
    const val SWITCH_TYPE_ATMOSPHERE_LAMP = 14 // 0x0E: 氛围灯物理总开关
    const val SWITCH_TYPE_COLOR_RANDOM = 20    // 0x14: 原车随机颜色呼吸开关
    const val SWITCH_TYPE_MUSIC_RHYTHM = 22    // 0x16: 音乐律动功能开关

    // 氛围灯 ProgressType 常量 (依据 ILightingUIChangeListener 反编译协议)
    const val PROGRESS_TYPE_LUMINANCE = 0x65     // 101: 原车保存的用户选择亮度 (1..10)
    const val PROGRESS_TYPE_COLOR_SELECTOR = 0x66 // 102: 原车保存的用户选择基色 (0..63)
    const val PROGRESS_TYPE_LIGHT_LANE_TURN = 0x67 // 103: 转向/流向联动状态
    const val PROGRESS_TYPE_BREATHING_CYCLE = 0x68 // 104: 呼吸周期

    private var appContext: Context? = null
    private var lightsService: ILightsService? = null

    // 原车功能抢占状态 (小欧语音交互、空调温度调节、蓝牙电话、驾驶模式等车机原生高优事件)
    private val _isPreempted = MutableStateFlow(false)
    val isPreempted: StateFlow<Boolean> = _isPreempted.asStateFlow()
    var currentPreemptionReason: String? = null
        private set
    private var preemptTimestampMs: Long = 0L
    private var autoReclaimRunnable: Runnable? = null
    private var colorReclaimWatchdog: Runnable? = null
    private var voiceAssistantReceiver: BroadcastReceiver? = null

    // 原车中控当前上报的氛围灯亮度 (1..10 档，默认 8 档即 80%)
    private val _nativeCarBrightness = MutableStateFlow(8)
    val nativeCarBrightness: StateFlow<Int> = _nativeCarBrightness.asStateFlow()

    // 氛围灯基准亮度 (10..100%，默认 80%)
    private val _baseBrightnessPercent = MutableStateFlow(80)
    val baseBrightnessPercent: StateFlow<Int> = _baseBrightnessPercent.asStateFlow()
    val customBrightnessPercent: StateFlow<Int> = _baseBrightnessPercent.asStateFlow()

    // 兼容保留字段 (已废弃跟随原车模式，统一由基准亮度接管)
    private val _followCarBrightness = MutableStateFlow(false)
    val followCarBrightness: StateFlow<Boolean> = _followCarBrightness.asStateFlow()

    // 当前换算后的基准亮度比例 (10%..100%，白天以此为准，夜间以此打 5 折)
    private val _effectiveBaseBrightnessPercent = MutableStateFlow(80)
    val effectiveBaseBrightnessPercent: StateFlow<Int> = _effectiveBaseBrightnessPercent.asStateFlow()

    // 最终实发生效倍率 (例如：白天 80% 则为 0.8f；夜间 80% * 0.5 则为 0.4f)
    private val _effectiveScale = MutableStateFlow(0.8f)
    val effectiveScale: StateFlow<Float> = _effectiveScale.asStateFlow()

    // 昼夜模式状态 (傍晚 DUSK / 夜晚 NIGHT 为夜间模式；清晨 DAWN / 白天 DAY 为白天模式)
    var isNightMode: Boolean = false
        private set

    // 模拟夜间模式覆盖 (用于测试台手动测试白天/夜间，null 为跟随真实时段)
    private val _simulatedNightMode = MutableStateFlow<Boolean?>(null)
    val simulatedNightMode: StateFlow<Boolean?> = _simulatedNightMode.asStateFlow()

    val effectiveNightMode: Boolean
        get() = _simulatedNightMode.value ?: isNightMode

    // 连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // 原始律动请求等级 (0..10) 与 最终下发实车等级 (0..10)
    private val _rawLuminance = MutableStateFlow(0)
    val rawLuminance: StateFlow<Int> = _rawLuminance.asStateFlow()

    private val _currentLuminance = MutableStateFlow(0)
    val currentLuminance: StateFlow<Int> = _currentLuminance.asStateFlow()

    private val _currentColorIndex = MutableStateFlow(0)
    val currentColorIndex: StateFlow<Int> = _currentColorIndex.asStateFlow()

    @Volatile private var isMusicRhythmActive: Boolean = false
    @Volatile private var isStandbyRestore: Boolean = true

    private val deathRecipient = IBinder.DeathRecipient {
        AppLogger.w(TAG, "SmartLightsServer 远端进程挂起/死亡，重置连接状态")
        _isConnected.value = false
        lightsService = null
        tryRebind()
    }

    private val lightingClient = object : ILightingClient.Stub() {
        override fun onProgressChanged(type: Int, progress: Int) {
            when (type) {
                PROGRESS_TYPE_LUMINANCE -> {
                    val safeB = progress.coerceIn(1, 10)
                    if (_nativeCarBrightness.value != safeB) {
                        _nativeCarBrightness.value = safeB
                        AppLogger.i(TAG, "💡 [AIDL监听] 原车中控当前氛围灯亮度: $safeB 档 (${safeB * 10}%)")
                    }
                }
                PROGRESS_TYPE_COLOR_SELECTOR -> {
                    val safeC = progress.coerceIn(1, 64)
                    AppLogger.throttledD(TAG, "💡 [AIDL监听] 原车基准色号: $safeC (小欧屏设定色=${_currentColorIndex.value}, preempted=${_isPreempted.value})", 15_000L)
                    onNativeColorChanged(safeC)
                }
                else -> {
                    // 高频内部通道 (如 type=103 等) 默认静默或长周期聚合，避免车机日志刷屏触发 GC
                    AppLogger.throttledD(TAG, "原车灯光进度反馈: type=$type, progress=$progress", 60_000L)
                }
            }
        }

        override fun onSwitchChanged(type: Int, isOpen: Boolean) {
            AppLogger.throttledD(TAG, "原车灯光开关反馈: type=$type, isOpen=$isOpen", 15_000L)
            // 仅当音乐律动真实开启且小欧屏未处于抢占避让中时，自动保护拉起总开关
            val syncEnabled = try { PrefsManager.isAmbientLightSyncEnabled() } catch (_: Exception) { false }
            if (type == SWITCH_TYPE_ATMOSPHERE_LAMP && !isOpen && !_isPreempted.value && syncEnabled && isMusicRhythmActive) {
                mainHandler.postDelayed({
                    if (!_isPreempted.value && isMusicRhythmActive) {
                        try {
                            lightsService?.setControlSwitch(SWITCH_TYPE_ATMOSPHERE_LAMP, true)
                        } catch (_: Exception) {}
                    }
                }, 800L)
            }
        }
    }

    private fun onNativeColorChanged(safeC: Int) {
        val currentTarget = _currentColorIndex.value
        // 若当前未被原车高优事件抢占，但原车自身重置了基色（如 recoveryLightingSetting 触发将颜色还原为原车单色）：
        if (currentTarget > 0 && safeC != currentTarget && !_isPreempted.value) {
            colorReclaimWatchdog?.let { mainHandler.removeCallbacks(it) }
            colorReclaimWatchdog = Runnable {
                if (!_isPreempted.value && _currentColorIndex.value == currentTarget) {
                    AppLogger.i(TAG, "🐕 [色彩看门狗] 检测到原车重置单色 ($safeC)，自动恢复小欧屏封面主色 ($currentTarget)")
                    setColorSelector(currentTarget)
                }
            }.also { r -> mainHandler.postDelayed(r, 800L) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            AppLogger.i(TAG, "成功绑定原车 SmartLightsServer 服务")
            val service = ILightsService.Stub.asInterface(binder)
            lightsService = service
            try {
                binder?.linkToDeath(deathRecipient, 0)
                service.addLightingClient(lightingClient)
                // 主动触发原车刷新状态，SmartLightsServer 会立即向 lightingClient 回调当前亮度与颜色！
                service.refreshUi()
                // 仅当律动已在播放中且开启同步时才接管开启开关，严禁开机未放歌时提前抢占原车灯光！
                val syncEnabled = try { PrefsManager.isAmbientLightSyncEnabled() } catch (_: Exception) { false }
                if (syncEnabled && isMusicRhythmActive) {
                    service.setControlSwitch(SWITCH_TYPE_ATMOSPHERE_LAMP, true)
                    service.setControlSwitch(SWITCH_TYPE_MUSIC_RHYTHM, true)
                }
            } catch (e: RemoteException) {
                AppLogger.w(TAG, "注册死亡回调或 LightingClient 异常: ${e.message}")
            }
            _isConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.w(TAG, "SmartLightsServer 服务意外断开，准备重连")
            _isConnected.value = false
            lightsService = null
            tryRebind()
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        // 初始化读取持久化设置
        _baseBrightnessPercent.value = PrefsManager.getAmbientLightCustomBrightness()

        // 尝试直接从 CAN 硬件读取原车当前物理亮度
        val hwBrightness = CarGearManager.getInstance(context).getAmbientLightBrightness()
        if (hwBrightness in 1..10) {
            _nativeCarBrightness.value = hwBrightness
            AppLogger.i(TAG, "从 CAN 硬件读取到初始氛围灯亮度: $hwBrightness 档 (${hwBrightness * 10}%)")
        }

        recomputeScale()
        registerVoiceReceiver(context)
        registerCallListener(context)
        bind()
    }

    private fun registerVoiceReceiver(context: Context) {
        if (voiceAssistantReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                AppLogger.d(TAG, "🎙️ 收到语音事件广播: $action")
                when (action) {
                    "com.chinatsp.intent.action_VOICE_AWARED",
                    "com.chinatsp.ifly.SHOW_ASSISTANT",
                    "com.chinatsp.ifly.voiceadapter" -> {
                        // 语音交互唤醒：挂起小欧屏控制，但设置 6000ms 刷新看门狗，防止系统未发送 DISAPPEAR 导致永久死锁
                        preempt("小欧语音交互唤醒", autoReclaimDelayMs = 6000L)
                    }
                    "com.chinatsp.intent.action_VOICE_DISAPPEAR" -> {
                        // 原车 SmartLightsServer 在语音退出时，延迟 1000ms 执行 recoveryLightingSetting 重置氛围灯
                        // 小欧屏在 1300ms 准时接管，重新覆盖原车的重置
                        mainHandler.postDelayed({
                            reclaim("小欧语音交互退出")
                        }, 1300L)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.chinatsp.intent.action_VOICE_AWARED")
            addAction("com.chinatsp.ifly.SHOW_ASSISTANT")
            addAction("com.chinatsp.ifly.voiceadapter")
            addAction("com.chinatsp.intent.action_VOICE_DISAPPEAR")
        }
        try {
            context.registerReceiver(receiver, filter)
            voiceAssistantReceiver = receiver
            AppLogger.i(TAG, "🎙️ 成功注册欧尚小欧语音助手状态监听广播")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册小欧语音广播异常: ${t.message}")
        }
    }

    private fun registerCallListener(context: Context) {
        try {
            CarCallManager.getInstance(context).addListener(object : CarCallManager.CallListener {
                override fun onCallChanged(callInfo: CarCallManager.CallInfo) {
                    if (callInfo.isCallActive) {
                        preempt("蓝牙电话通话中")
                    } else if (callInfo.state == CarCallManager.CallState.IDLE || callInfo.state == CarCallManager.CallState.TERMINATED) {
                        if (_isPreempted.value && currentPreemptionReason == "蓝牙电话通话中") {
                            mainHandler.postDelayed({
                                reclaim("蓝牙电话通话结束")
                            }, 1000L)
                        }
                    }
                }
            })
            AppLogger.i(TAG, "📞 成功挂载 CarCallManager 电话状态监听")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "挂载 CarCallManager 异常: ${t.message}")
        }
    }

    fun bind() {
        val ctx = appContext ?: return
        if (_isConnected.value && lightsService != null) return

        try {
            val intent = Intent().apply {
                component = ComponentName(SERVER_PACKAGE, SERVER_CLASS)
            }
            val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            AppLogger.i(TAG, "bindService(SmartLightsServer) 结果: $bound")
        } catch (e: Exception) {
            AppLogger.e(TAG, "绑定 SmartLightsServer 失败: ${e.message}", e)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rebindRunnable: Runnable? = null

    fun unbind() {
        val ctx = appContext ?: return
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = null
        autoReclaimRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReclaimRunnable = null
        colorReclaimWatchdog?.let { mainHandler.removeCallbacks(it) }
        colorReclaimWatchdog = null
        voiceAssistantReceiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (_: Exception) {}
            voiceAssistantReceiver = null
        }
        try {
            lightsService?.asBinder()?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Exception) {}
        try {
            lightsService?.removeLightingClient(lightingClient)
            ctx.unbindService(connection)
        } catch (e: Exception) {
            AppLogger.w(TAG, "解绑 SmartLightsServer 异常: ${e.message}")
        } finally {
            _isConnected.value = false
            lightsService = null
        }
    }

    private fun tryRebind() {
        if (appContext == null) return
        rebindRunnable?.let { mainHandler.removeCallbacks(it) }
        rebindRunnable = Runnable {
            if (!_isConnected.value) {
                bind()
            }
        }.also { r -> mainHandler.postDelayed(r, 3000L) }
    }

    /**
     * 原车功能抢占避让：挂起小欧屏向总线与 AIDL 的高频写入，将灯光控制权无缝让给原车交互 (如语音蓝色动效、空调红蓝流光)
     */
    @Synchronized
    fun preempt(reason: String, autoReclaimDelayMs: Long = 0L) {
        val isSameReason = _isPreempted.value && currentPreemptionReason == reason
        _isPreempted.value = true
        currentPreemptionReason = reason
        preemptTimestampMs = SystemClock.uptimeMillis()
        autoReclaimRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReclaimRunnable = null

        // 核心安全看门狗：如果未显式指定延时(0L)，绝不永久死锁！默认设定 8000ms 兜底超时
        val effectiveDelay = if (autoReclaimDelayMs > 0L) autoReclaimDelayMs else 8000L
        if (!isSameReason) {
            AppLogger.i(TAG, "🛡️ [原车抢占避让] 氛围灯已被原车功能抢占: $reason, 挂起向总线与AIDL写入 (自动恢复延时=${effectiveDelay}ms)")
        }

        autoReclaimRunnable = Runnable {
            reclaim("自动延时恢复($reason)")
        }.also { r -> mainHandler.postDelayed(r, effectiveDelay) }
    }

    /**
     * 原车功能抢占解除：原车交互结束，小欧屏重新接管氛围灯
     */
    @Synchronized
    fun reclaim(reason: String) {
        AppLogger.i(TAG, "🔓 [原车抢占解除] 原车功能交互已结束: $reason (原原因: $currentPreemptionReason)，开始重新接管氛围灯")
        _isPreempted.value = false
        currentPreemptionReason = null
        preemptTimestampMs = 0L
        autoReclaimRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReclaimRunnable = null
        reclaimAndSync(reason)
    }

    /**
     * 满血重新同步并完全接管原车氛围灯
     */
    @Synchronized
    fun reclaimAndSync(origin: String) {
        _isPreempted.value = false
        currentPreemptionReason = null
        preemptTimestampMs = 0L
        autoReclaimRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReclaimRunnable = null

        val ctx = appContext ?: return
        try {
            val syncEnabled = try { PrefsManager.isAmbientLightSyncEnabled() } catch (_: Exception) { false }
            val rawColor = _currentColorIndex.value
            // 未开启同步或从未加载过任何歌曲封面色且当前不在律动状态：坚决保持原车氛围灯原生设置，严禁强行写入色号 1！
            if (!syncEnabled || (!isMusicRhythmActive && rawColor !in 1..64)) {
                AppLogger.i(TAG, "⏭️ [接管跳过] 保持原车原生氛围灯设置 (syncEnabled=$syncEnabled, rhythmActive=$isMusicRhythmActive, color=$rawColor)")
                return
            }

            val isCoverColorMode = try { PrefsManager.isAmbientLightColorSyncEnabled() } catch (_: Exception) { true }
            // 1. 确保物理总开关开启；律动开关按真实播放状态恢复（暂停期间不再强开律动）
            lightsService?.setControlSwitch(SWITCH_TYPE_ATMOSPHERE_LAMP, true)
            lightsService?.setControlSwitch(SWITCH_TYPE_MUSIC_RHYTHM, isMusicRhythmActive)
            CarGearManager.getInstance(ctx).setAmbientLightSwitch(true)

            // 2. 仅在根据封面颜色变换模式且已有有效提取色号 (1..64) 时，关停原车随机抢占并写入基色；
            // 若为使用系统设置颜色模式，坚决不触碰原车颜色开关与色号，允许用户自由使用原车设置（单色或随机）
            if (isCoverColorMode && rawColor in 1..64) {
                lightsService?.setControlSwitch(SWITCH_TYPE_COLOR_RANDOM, false)
                lightsService?.setMusicColorRandomMode(false)
                lightsService?.setUserSelectColor(rawColor)
                lightsService?.setColorSelector(rawColor, false)
                CarGearManager.getInstance(ctx).setAmbientLightColor(rawColor)
            }

            // 3. 重新同步亮度
            reapplyCurrentBrightness()
            AppLogger.i(TAG, "🚀 [接管恢复完成] 成功重新接管原车氛围灯 (触发源=$origin, 模式=${if (isCoverColorMode) "封面色彩" else "系统色彩"}, 亮度=${_currentLuminance.value}级)")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "reclaimAndSync 执行异常: ${t.message}", t)
        }
    }

    /**
     * 原车空调温度调节触发 (原车流光展示约 5 秒，5 秒后触发 recoveryLightingSetting)
     */
    fun onAirConditionerChanged() {
        preempt("空调温度调节", autoReclaimDelayMs = 5500L)
    }

    /**
     * 原车驾驶模式变动触发 (赛道/运动/ECO/NORMAL，原车流光展示 5 秒)
     */
    fun onDriveModeChanged() {
        preempt("驾驶模式切换", autoReclaimDelayMs = 5500L)
    }

    /**
     * 设置当前时段 (傍晚 DUSK / 夜间 NIGHT 自动触发 50% 衰减保护；清晨 DAWN / 白天 DAY 恢复 100%)
     */
    fun setTimeOfDay(timeOfDay: TimeOfDay) {
        val night = (timeOfDay == TimeOfDay.DUSK || timeOfDay == TimeOfDay.NIGHT)
        if (isNightMode != night) {
            isNightMode = night
            AppLogger.i(TAG, "🌙 氛围灯昼夜时段模式切换: $timeOfDay (夜间模式=$night)")
            recomputeScale()
            reapplyCurrentBrightness()
        }
    }

    /**
     * 设置氛围灯基准亮度 (10%..100%)
     * 无论是否处于音乐律动中，滑动即时物理接管实车氛围灯亮度！
     */
    fun setBaseBrightnessPercent(percent: Int) {
        val safe = percent.coerceIn(10, 100)
        _baseBrightnessPercent.value = safe
        _effectiveBaseBrightnessPercent.value = safe
        PrefsManager.setAmbientLightCustomBrightness(safe)
        recomputeScale()

        val baseLevel = (safe / 10.0f).roundToInt().coerceIn(1, 10)
        val effectiveLevel = if (effectiveNightMode) max(1, (baseLevel * 0.5f).roundToInt()) else baseLevel

        val ctx = appContext
        // 关键物理接管：立即同步下发到原车 AIDL 与 CAN 硬件总线
        try {
            lightsService?.setUserSelectBrightness(effectiveLevel)
            lightsService?.setLuminance(effectiveLevel)
        } catch (_: Exception) {}

        if (ctx != null) {
            CarGearManager.getInstance(ctx).setAmbientLightBrightness(effectiveLevel)
        }

        reapplyCurrentBrightness()
        AppLogger.i(TAG, "💡 设置氛围灯基准亮度即时接管生效: $safe% (实发=$effectiveLevel 档, 夜间=$effectiveNightMode)")
    }

    fun setCustomBrightnessPercent(percent: Int) = setBaseBrightnessPercent(percent)

    /**
     * 兼容保留接口 (已统一采用基准亮度)
     */
    fun setFollowCarBrightness(follow: Boolean) {
        AppLogger.d(TAG, "setFollowCarBrightness (已统一由基准亮度接管): $follow")
    }

    /**
     * 设置模拟昼夜模式 (测试台专用，null 为跟随真实时段)
     */
    fun setSimulatedNightMode(simulatedNight: Boolean?) {
        _simulatedNightMode.value = simulatedNight
        recomputeScale()
        reapplyCurrentBrightness()
        AppLogger.i(TAG, "🧪 模拟昼夜模式切换: $simulatedNight (有效夜间状态=$effectiveNightMode)")
    }

    /**
     * 重新计算最终有效实发比例
     */
    private fun recomputeScale() {
        val basePercent = _baseBrightnessPercent.value.coerceIn(10, 100)
        _effectiveBaseBrightnessPercent.value = basePercent

        val nightFactor = if (effectiveNightMode) 0.5f else 1.0f
        val scale = (basePercent / 100.0f) * nightFactor
        _effectiveScale.value = scale
        AppLogger.d(TAG, "💡 氛围灯基准缩放更新: 基准=${basePercent}%, 夜间模式=${effectiveNightMode}, 最终Scale=$scale")
    }

    private fun reapplyCurrentBrightness() {
        val current = _rawLuminance.value
        if (isStandbyRestore || current <= 0) {
            restoreDefaultLight()
        } else {
            setLuminance(current)
        }
    }

    /**
     * 开启/关闭音乐律动模式开关 (双通道同步接管)
     * 注意：停止律动时绝不关闭氛围灯总电源，氛围灯必须始终保持在开启位置！
     */
    fun setMusicRhythmEnabled(enabled: Boolean) {
        isMusicRhythmActive = enabled
        if (_isPreempted.value) {
            // 通话/语音/空调抢占期间坚决静默避让，禁止穿透写入总线，等待抢占解除后统一恢复
            return
        }
        val ctx = appContext
        try {
            lightsService?.setControlSwitch(SWITCH_TYPE_ATMOSPHERE_LAMP, true)
            lightsService?.setControlSwitch(SWITCH_TYPE_MUSIC_RHYTHM, enabled)
            AppLogger.i(TAG, "setMusicRhythmEnabled (AIDL): $enabled")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setMusicRhythmEnabled AIDL 失败: ${e.message}")
        }
        // CAN 通道确保氛围灯总开关始终保持开启(2=ON)，绝不关闭总开关
        if (ctx != null) {
            CarGearManager.getInstance(ctx).setAmbientLightSwitch(true)
        }
    }

    /**
     * 设置实时律动亮度挡位 (0..10 级)
     * -1 恢复默认常亮
     * 自动线性动态映射至用户基准亮度 (10%~100%) 与夜间模式 50% 保护上限:
     * rawLevel 10 达到用户设定的最高上限 maxAllowedLevel，rawLevel 1 达到波谷 1 级。
     */
    fun setLuminance(level: Int) {
        isStandbyRestore = (level == -1)
        val rawLevel = if (level == -1) {
            (_effectiveBaseBrightnessPercent.value / 10.0f).roundToInt().coerceIn(1, 10)
        } else {
            level.coerceIn(0, 10)
        }
        _rawLuminance.value = rawLevel

        val maxAllowedLevel = if (effectiveNightMode) {
            max(1, (_effectiveBaseBrightnessPercent.value / 20.0f).roundToInt())
        } else {
            max(1, (_effectiveBaseBrightnessPercent.value / 10.0f).roundToInt())
        }

        val scaledLevel = if (level == -1) {
            // 待机常亮状态: 恢复基准设定亮度
            maxAllowedLevel
        } else if (rawLevel > 0) {
            // 律动状态: 依据 rawLevel (1..10) 严格线性映射到 [1..maxAllowedLevel] 动态范围
            if (maxAllowedLevel <= 1) {
                1
            } else {
                (1 + ((rawLevel - 1) / 9.0f) * (maxAllowedLevel - 1)).roundToInt().coerceIn(1, maxAllowedLevel)
            }
        } else {
            0
        }

        if (_isPreempted.value) {
            // 同步更新预期亮度状态，确保日志与抢占恢复后状态一致，但不下发硬件
            _currentLuminance.value = scaledLevel
            // 🛡️ 原车高优交互中（小欧语音蓝色流光、空调温控流光、电话绿色闪烁等），小欧屏全面静默避让
            // 但如果被抢占超过 8 秒且当前无活跃通话，触发死锁超时看门狗强制打破死锁
            val elapsed = SystemClock.uptimeMillis() - preemptTimestampMs
            val isCallActive = try {
                appContext?.let { CarCallManager.getInstance(it).getCurrentCallInfo().isCallActive } == true
            } catch (_: Exception) { false }

            if (elapsed > 8000L && !isCallActive) {
                AppLogger.w(TAG, "🛡️ 抢占超时看门狗触发 (已持续 ${elapsed}ms 未恢复, 原原因: $currentPreemptionReason)，强制打破死锁重新接管")
                reclaim("抢占超时看门狗强制恢复")
            } else {
                return
            }
        }

        val ctx = appContext

        // 通道 1: AIDL 律动亮度 (原厂酷狗同款官方接口，直通 SmartLightsServer)
        var aidlSuccess = false
        try {
            if (lightsService != null) {
                lightsService?.setLuminance(scaledLevel)
                if (level == -1 && scaledLevel > 0) {
                    lightsService?.setUserSelectBrightness(scaledLevel)
                }
                aidlSuccess = true
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "AIDL setLuminance 异常: ${e.message}")
        }

        // 通道 2: CAN 硬件直控
        // 注意：律动期间严禁高频写入 0x2140f3e5，防止污染用户持久化菜单
        if (ctx != null) {
            val canBrightness = if (scaledLevel <= 0) 1 else scaledLevel
            if (level == -1) {
                // 恢复待机常亮状态: 全寄存器同步复位
                CarGearManager.getInstance(ctx).setAmbientLightBrightness(canBrightness)
            } else if (!aidlSuccess) {
                // AIDL 未连通时的律动 CAN 保底：仅写入律动物理亮度，不污染用户持久化菜单
                CarGearManager.getInstance(ctx).setAmbientLightRhythmBrightness(canBrightness)
            }
        }

        _currentLuminance.value = scaledLevel
    }

    /**
     * 设置当前律动使用的基础色号 (0..63)
     * 彻底解决原车“随机颜色”抢占：先主动关闭原车随机颜色循环，确保封面提取色/选中色立即生效！
     */
    fun setColorSelector(colorIndex: Int) {
        val safeIdx = colorIndex.coerceIn(1, 64)
        _currentColorIndex.value = safeIdx

        if (_isPreempted.value) {
            // 抢占期间暂缓向总线与 AIDL 下发，等待抢占解除后统一下发
            return
        }

        val ctx = appContext

        // 通道 1: AIDL (先关停原车随机颜色抢占，再下发主色)
        try {
            lightsService?.setControlSwitch(SWITCH_TYPE_COLOR_RANDOM, false)
            lightsService?.setMusicColorRandomMode(false)
            lightsService?.setUserSelectColor(safeIdx)
            lightsService?.setColorSelector(safeIdx, false)
            AppLogger.i(TAG, "AIDL 下发基色成功: $safeIdx (已关闭原车随机抢占)")
        } catch (e: Exception) {
            AppLogger.d(TAG, "AIDL setColorSelector 异常: ${e.message}")
        }

        // 通道 2: CAN 硬件直控 (0x2140f3e4 + 0x2141f3d5 area=0)
        if (ctx != null) {
            CarGearManager.getInstance(ctx).setAmbientLightColor(safeIdx)
        }

        _currentColorIndex.value = safeIdx
    }

    /**
     * 开启/关闭原车随机色彩渐变呼吸模式
     */
    fun setMusicColorRandomMode(enable: Boolean) {
        try {
            lightsService?.setControlSwitch(SWITCH_TYPE_COLOR_RANDOM, enable)
            lightsService?.setMusicColorRandomMode(enable)
            AppLogger.i(TAG, "setMusicColorRandomMode: $enable (同步设置 SWITCH_TYPE_COLOR_RANDOM=$enable)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setMusicColorRandomMode 失败: ${e.message}")
        }
    }

    /**
     * 静态设置用户主灯光亮度 (1..10)
     */
    fun setUserSelectBrightness(brightness: Int) {
        val safeB = brightness.coerceIn(1, 10)
        setCustomBrightnessPercent(safeB * 10)
        val scaledB = if (effectiveNightMode) max(1, (safeB * 0.5f).toInt()) else safeB
        try {
            lightsService?.setUserSelectBrightness(scaledB)
        } catch (e: Exception) {
            AppLogger.d(TAG, "setUserSelectBrightness 失败: ${e.message}")
        }
        appContext?.let { CarGearManager.getInstance(it).setAmbientLightBrightness(scaledB) }
    }

    /**
     * 静态设置用户主灯光颜色 (0..63)
     */
    fun setUserSelectColor(colorIndex: Int) {
        val safeC = colorIndex.coerceIn(1, 64)
        try {
            lightsService?.setControlSwitch(SWITCH_TYPE_COLOR_RANDOM, false)
            lightsService?.setMusicColorRandomMode(false)
            lightsService?.setUserSelectColor(safeC)
        } catch (e: Exception) {
            AppLogger.d(TAG, "setUserSelectColor 失败: ${e.message}")
        }
        appContext?.let { CarGearManager.getInstance(it).setAmbientLightColor(safeC) }
    }

    /**
     * 安全停止律动并恢复原车默认常亮灯光 (夜间 50% 保护)
     */
    fun restoreDefaultLight() {
        isMusicRhythmActive = false
        setLuminance(-1)
        val baseP = _effectiveBaseBrightnessPercent.value
        val actualL = _currentLuminance.value
        AppLogger.i(TAG, "恢复氛围灯默认常亮状态 (基准=$baseP%, 夜间=$effectiveNightMode, 实发=$actualL 级)")
    }
}

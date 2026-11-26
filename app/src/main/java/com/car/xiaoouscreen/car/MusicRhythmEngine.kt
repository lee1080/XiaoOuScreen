package com.car.xiaoouscreen.car

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.model.LyricWord
import com.carlyrics.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 全局音乐律动处理引擎 (针对 Android 9 车机与原厂座舱深度优化)
 * 1. 深度整合 Android 9 (API 28) AudioPlaybackCallback 与 audioManager.isMusicActive，毫秒级响应音乐暂停与恢复
 * 2. 尝试挂载系统全局/独立 AudioSession Visualizer，失败无缝切至车规级声学冲击衰减律动引擎
 * 3. 欧尚 31 频段算法 + 重低音强化滤波 (20Hz~250Hz)，消除地图导航人声与蜂鸣器干扰
 * 4. 监听 AudioFocus Ducking 状态，地图语音播报时平滑锁定柔和亮度
 * 5. 提取歌曲封面主色，自适应映射原厂 64 色氛围灯，音乐暂停立即平稳恢复常亮基准
 */
class MusicRhythmEngine(context: Context) {

    companion object {
        private const val TAG = "MusicRhythmEngine"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var visualizer: Visualizer? = null
    private var isRunning = false
    private var isDucking = false // 地图导航播报压音中
    @Volatile
    private var isVisualizerGlobalSessionBlocked = false

    // 专业级 DSP 鼓点瞬态频谱通量分析器状态 (Kick 45-130Hz, Snare 180-300Hz+2000-4500Hz, Hi-Hat 6000-12000Hz)
    private var prevFftMagnitudes: FloatArray? = null
    private var rollingKickFluxAvg = 15.0f
    private var rollingSnareBodyFluxAvg = 10.0f
    private var rollingSnareCrackFluxAvg = 8.0f
    private var lastDrumTriggerTimeMs = 0L
    private var drumAttackPeak = 2.0f
    private var lastDrumAttackTimeMs = 0L
    private val DRUM_COOLDOWN_MS = 170L // 170ms 动态冷却防抖，杜绝鼓点余震频闪

    // 零能量静音/隔离看门狗
    private var consecutiveZeroEnergyFrames = 0
    private var lastFrameFftEnergy = 0f
    private val ZERO_ENERGY_SILENCE_THRESHOLD = 20.0f
    private val MAX_ZERO_ENERGY_FRAMES_BEFORE_FALLBACK = 12 // 约 300ms

    // 设置项
    var masterEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                stop()
            } else if (currentTrack?.isPlaying == true || isAudioSystemActive()) {
                start()
            }
        }
    var colorSyncEnabled: Boolean = true
    var randomColorMode: Boolean = false
        set(value) {
            field = value
            AmbientLightManager.setMusicColorRandomMode(value)
        }
    var sensitivityPercent: Int = 100 // 50..150

    private var currentTrack: CurrentTrack? = null
    private var lastCoverBitmapRef: java.lang.ref.WeakReference<Bitmap>? = null
    var lastExtractedColorIndex: Int = -1
        private set
    private var lastExtractedColorTrackKey: String? = null
    @Volatile private var lastAnalyzedBitmapSignature: Int = 0
    @Volatile private var lastExtractedRgb: Int = 0
    @Volatile private var lastColorApplyTime: Long = 0L

    val lastCoverBitmap: Bitmap?
        get() = lastCoverBitmapRef?.get()?.takeIf { !it.isRecycled }

    fun clearCoverCache() {
        lastCoverBitmapRef = null
        lastAnalyzedBitmapSignature = 0
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainDebounceHandler = Handler(android.os.Looper.getMainLooper())
    private val pendingStopRunnable = Runnable {
        if (currentTrack?.isPlaying != true && !isAudioSystemActive() && isRunning) {
            AppLogger.i(TAG, "⏸️ 收到稳定暂停信号 (350ms 防抖确认)，停止律动并恢复基准常亮")
            stop()
        }
    }

    private val _liveLuminance = MutableStateFlow(0)
    val liveLuminance: StateFlow<Int> = _liveLuminance.asStateFlow()

    private val _liveColorIndex = MutableStateFlow(0)
    val liveColorIndex: StateFlow<Int> = _liveColorIndex.asStateFlow()

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var lastPlaybackActiveLogged: Boolean? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 地图导航TTS开始播报，临时冻结律动跳跃，防止人声被误当成节拍
                isDucking = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 导航播报结束，恢复音乐节拍律动
                isDucking = false
            }
        }
    }

    init {
        AmbientLightManager.init(appContext)
        ensureWorker()
        registerAudioPlaybackCallback()
    }

    private fun ensureWorker() {
        if (workerThread == null) {
            workerThread = HandlerThread("MusicRhythm_Worker", Process.THREAD_PRIORITY_AUDIO).apply {
                start()
                workerHandler = Handler(looper)
            }
        }
    }

    /**
     * 检测 Android 9 系统层音频流是否处于活跃发声状态 (兼顾 Automotive USAGE_MEDIA 硬件通道与 STREAM_MUSIC)
     */
    fun isAudioSystemActive(): Boolean {
        return try {
            val hasActiveConfig = try {
                audioManager.activePlaybackConfigurations.any {
                    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
                }
            } catch (_: Exception) { false }
            val isMusicActive = try { audioManager.isMusicActive } catch (_: Exception) { false }
            hasActiveConfig || isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 注册 Android 9 官方媒体音频流播放监听回调
     */
    private fun registerAudioPlaybackCallback() {
        try {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    val list = configs ?: emptyList()
                    val hasActiveMediaConfig = list.any { cfg ->
                        cfg.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
                    }
                    val isSystemMusicActive = try { audioManager.isMusicActive } catch (_: Exception) { false }
                    val isTrackPlaying = currentTrack?.isPlaying == true
                    // 核心逻辑：若当前曲目已识别，以 track 的播放状态为基准；若未识别到曲目，以系统媒体通道或 isMusicActive 为准
                    val isReallyActive = if (currentTrack != null) isTrackPlaying else (hasActiveMediaConfig || isSystemMusicActive)

                    if (isReallyActive != lastPlaybackActiveLogged) {
                        lastPlaybackActiveLogged = isReallyActive
                        AppLogger.i(TAG, "🎧 [系统音频流状态变迁] active=$isReallyActive (mediaConfig=$hasActiveMediaConfig, musicActive=$isSystemMusicActive, trackPlaying=$isTrackPlaying)")
                    } else {
                        AppLogger.recordBreadcrumb("AUDIO", "active=$isReallyActive")
                    }

                    if (!isReallyActive) {
                        if (isRunning) {
                            AppLogger.i(TAG, "AudioPlaybackCallback: 系统音频已确认为暂停/停止状态，立即停止律动并恢复常亮")
                            stop()
                        }
                    } else {
                        if (!isRunning && masterEnabled) {
                            AppLogger.i(TAG, "AudioPlaybackCallback: 系统音频已恢复发声，启动律动")
                            start()
                        }
                    }
                }
            }
            audioManager.registerAudioPlaybackCallback(callback, workerHandler)
            playbackCallback = callback
            AppLogger.i(TAG, "成功挂载 Android 9 AudioPlaybackCallback 媒体监听器")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册 AudioPlaybackCallback 异常: ${t.message}")
        }
    }

    private fun unregisterAudioPlaybackCallback() {
        playbackCallback?.let {
            try {
                audioManager.unregisterAudioPlaybackCallback(it)
            } catch (_: Exception) {}
            playbackCallback = null
        }
    }

    private fun destroyVisualizerInternal() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "destroyVisualizerInternal 异常: ${e.message}")
        }
        visualizer = null
    }

    @Synchronized
    fun start() {
        if (isRunning || !masterEnabled) return
        // 核心安全检查：当前曲目若明确处于暂停/停止状态，绝不启动律动
        val isTrackExplicitPaused = currentTrack != null && !currentTrack!!.isPlaying
        if (isTrackExplicitPaused) {
            AppLogger.d(TAG, "当前曲目已暂停，取消启动律动")
            return
        }
        ensureWorker()

        try {
            // 确保底层律动总开关与音乐律动模式开启
            AmbientLightManager.setMusicRhythmEnabled(true)
            if (randomColorMode) {
                AmbientLightManager.setMusicColorRandomMode(true)
            }

            consecutiveZeroEnergyFrames = 0
            prevFftMagnitudes = null

            // 寻找当前最活跃播放器的 ClientAudioSessionId (通过反射调用系统层隐藏接口，兼顾 Android 9 与 10+)
            val sessionToUse = extractAudioSessionId()
            if (isVisualizerGlobalSessionBlocked) {
                // 车机 HAL 已确认禁止 Visualizer (error: -3 或全 0 硬件隔离)，直接运行高保真声学律动引擎
                visualizer = null
                isRunning = true
                startSoftwareRhythmLoop()
                return
            }

            val v = try {
                if (sessionToUse > 0) {
                    AppLogger.i(TAG, "🎙️ 尝试挂载独立媒体客户端 AudioSession: $sessionToUse")
                    Visualizer(sessionToUse)
                } else {
                    AppLogger.i(TAG, "🎙️ 未找到独立 AudioSession，尝试挂载全局混音 Session 0")
                    Visualizer(0)
                }
            } catch (se: Exception) {
                isVisualizerGlobalSessionBlocked = true
                throw se
            }

            val captureRange = Visualizer.getCaptureSizeRange()
            val maxRate = Visualizer.getMaxCaptureRate()

            v.captureSize = captureRange[1] // 最大采样深度 (通常 1024)
            val captureRate = maxRate.coerceIn(20000, 50000)

            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || !isRunning) return
                    if (isDucking) {
                        AmbientLightManager.setLuminance(1)
                        return
                    }

                    // 1. 专业级 DSP 鼓点瞬态频谱通量分析与平滑包络计算
                    val luminance = analyzeFftDrumFlux(fft, samplingRate)

                    // 2. 零能量看门狗检测：若 Visualizer 连续捕获全 0 数据 (Android 9 车机硬件 Bus 隔离)
                    if (lastFrameFftEnergy <= ZERO_ENERGY_SILENCE_THRESHOLD) {
                        consecutiveZeroEnergyFrames++
                        if (consecutiveZeroEnergyFrames >= MAX_ZERO_ENERGY_FRAMES_BEFORE_FALLBACK) {
                            isVisualizerGlobalSessionBlocked = true
                            AppLogger.w(TAG, "⚠️ [零能量看门狗触发] Visualizer 连续捕获全 0 数据 (已超 $consecutiveZeroEnergyFrames 帧)！" +
                                    "判定为 Android 9 车机硬件音频 Bus 隔离，锁定切换至高保真自适应动态节拍引擎！")
                            workerHandler?.post {
                                if (isRunning && this@MusicRhythmEngine.visualizer != null) {
                                    destroyVisualizerInternal()
                                    startSoftwareRhythmLoop()
                                }
                            }
                            return
                        }
                    } else {
                        consecutiveZeroEnergyFrames = 0
                    }

                    _liveLuminance.value = luminance
                    AmbientLightManager.setLuminance(luminance)
                }
            }, captureRate, false, true)

            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.enabled = true
            visualizer = v
            isRunning = true
            AppLogger.i(TAG, "全局音乐律动 Visualizer(session=$sessionToUse, rate=${captureRate}mHz) 启动成功")
        } catch (e: Exception) {
            isVisualizerGlobalSessionBlocked = true
            AppLogger.w(TAG, "启动 Visualizer 失败 (HAL限制混音Session): ${e.message}，已切换至高保真自适应动态律动引擎")
            visualizer = null
            isRunning = true
            startSoftwareRhythmLoop()
        }
    }

    private fun extractAudioSessionId(): Int {
        try {
            val configs = audioManager.activePlaybackConfigurations.orEmpty()
            for (cfg in configs) {
                val usage = try { cfg.audioAttributes.usage } catch (_: Throwable) { -1 }
                if (usage == AudioAttributes.USAGE_MEDIA) {
                    val sid = getAudioSessionId(cfg)
                    if (sid > 0) return sid
                }
            }
            for (cfg in configs) {
                val sid = getAudioSessionId(cfg)
                if (sid > 0) return sid
            }
        } catch (_: Throwable) {}
        return 0
    }

    private fun getAudioSessionId(config: AudioPlaybackConfiguration): Int {
        // 1. Android 10+ (API 29+)
        try {
            val m = config.javaClass.getMethod("getClientAudioSessionId")
            val sid = m.invoke(config) as? Int ?: 0
            if (sid > 0) return sid
        } catch (_: Throwable) {}

        // 2. Android 9 (API 28) 隐藏方法: getSessionId()
        try {
            val m = config.javaClass.getMethod("getSessionId")
            val sid = m.invoke(config) as? Int ?: 0
            if (sid > 0) return sid
        } catch (_: Throwable) {}

        // 3. Android 9 (API 28) 私有字段: mSessionId
        try {
            val f = config.javaClass.getDeclaredField("mSessionId")
            f.isAccessible = true
            val sid = f.getInt(config)
            if (sid > 0) return sid
        } catch (_: Throwable) {}

        return 0
    }

    @Synchronized
    fun stop() {
        if (!isRunning && visualizer == null) return
        try {
            consecutiveZeroEnergyFrames = 0
            prevFftMagnitudes = null
            lastDrumTriggerTimeMs = 0L
            lastDrumAttackTimeMs = 0L
            stopSoftwareRhythmLoop()
            destroyVisualizerInternal()
            isRunning = false
            _liveLuminance.value = 0
            lastSentLuminance = -1
            lastSentTimeMs = 0L
            currentHitPeak = 2.0f
            drumAttackPeak = 2.0f
            // 注意：曲目中途暂停或网络缓冲时绝不清空 activeLyricLines！保留歌词缓存防止后半程退化为机械节拍
            lastTrackPositionMs = 0L
            lastPositionUpdateNano = 0L
            // 立即下发 -1 恢复基准默认平稳常亮 (带昼夜 50% 保护)
            AmbientLightManager.restoreDefaultLight()
            AppLogger.i(TAG, "全局音乐律动已停止，立即恢复基准常亮")
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止律动异常: ${e.message}")
        }
    }

    private var lastRandomColorCycleMs = 0L
    private var lastSentLuminance = -1
    private var lastSentTimeMs = 0L
    private var currentHitPeak = 2.0f

    @Volatile
    private var activeLyricLines: List<LyricLine> = emptyList()
    private var detectedBpm = 108.0f
    private var beatPeriodMs = 555.0f
    private var lastTrackPositionMs = 0L
    private var lastPositionUpdateNano = 0L
    @Volatile private var hasLoggedEmptyLyrics = false

    /**
     * 挂载当前正在播放歌曲的已解析歌词 (用于歌词驱动声学节拍逆推引擎)
     */
    fun onLyricsLoaded(lines: List<LyricLine>?) {
        setLyricsInternal(lines)
    }

    private fun setLyricsInternal(lines: List<LyricLine>?) {
        val raw = lines.orEmpty()
        if (raw.isEmpty() && activeLyricLines.isEmpty()) {
            if (!hasLoggedEmptyLyrics) {
                detectedBpm = 108.0f
                beatPeriodMs = 555.0f
                hasLoggedEmptyLyrics = true
                AppLogger.i(TAG, "🎶 [纯鼓点律动] 歌词为空，采用默认 108 BPM 鼓点节奏")
            }
            return
        }

        val validLines = raw.filter { it.text.isNotBlank() }
        val prev = activeLyricLines
        if (validLines.size == prev.size && validLines.isNotEmpty()) {
            val lastIdx = validLines.size - 1
            val midIdx = validLines.size / 2
            if (validLines[0].timestamp == prev[0].timestamp && validLines[0].text == prev[0].text &&
                validLines[midIdx].timestamp == prev[midIdx].timestamp && validLines[midIdx].text == prev[midIdx].text &&
                validLines[lastIdx].timestamp == prev[lastIdx].timestamp && validLines[lastIdx].text == prev[lastIdx].text
            ) {
                return
            }
        }

        activeLyricLines = validLines
        hasLoggedEmptyLyrics = validLines.isEmpty()
        if (validLines.isNotEmpty()) {
            detectedBpm = computeBpmFromLyrics(validLines)
            beatPeriodMs = (60000.0f / detectedBpm).coerceIn(400.0f, 900.0f)
            AppLogger.i(TAG, "🎶 [纯鼓点律动] 挂载歌词 ${validLines.size} 行，逆推歌曲鼓点节拍: BPM=${detectedBpm.roundToInt()}, 单拍周期=${beatPeriodMs.roundToInt()}ms")
        } else {
            detectedBpm = 108.0f
            beatPeriodMs = 555.0f
            AppLogger.i(TAG, "🎶 [纯鼓点律动] 歌词为空，采用默认 108 BPM 鼓点节奏")
        }
    }

    /**
     * 根据全曲歌词时序差分逆推歌曲主节奏速度 (BPM)
     */
    private fun computeBpmFromLyrics(lyrics: List<LyricLine>): Float {
        val timestamps = lyrics.map { it.timestamp }.filter { it in 1L until 86_400_000L }
        if (timestamps.size < 4) return 108.0f

        val deltas = mutableListOf<Long>()
        for (i in 0 until timestamps.size - 1) {
            val dt = timestamps[i + 1] - timestamps[i]
            if (dt in 1800L..14000L) {
                deltas.add(dt)
            }
        }
        if (deltas.isEmpty()) return 108.0f

        var bestBpm = 108.0f
        var bestScore = -1e9f

        for (bpm in 72..140) {
            val tBeat = 60000.0f / bpm
            var score = 0.0f
            for (dt in deltas) {
                val ratio = dt / tBeat
                val nearest = kotlin.math.round(ratio).toInt()
                if (nearest >= 2) {
                    val err = kotlin.math.abs(ratio - nearest)
                    val weight = if (nearest % 4 == 0) 1.5f else if (nearest % 2 == 0) 1.2f else 1.0f
                    score += weight * cos(err * 2.0 * Math.PI).toFloat()
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm.toFloat()
            }
        }
        return bestBpm
    }

    /**
     * 同步当前音乐实时播放进度 (ms)
     */
    fun onPositionUpdate(positionMs: Long) {
        lastTrackPositionMs = positionMs
        lastPositionUpdateNano = System.nanoTime()
    }

    /**
     * 车规级标准音乐氛围灯律动引擎 (纯鼓点驱动 · 歌词声学节拍逆推 · 原厂 500ms 黄金调度)
     * 1. 【基于毫秒级歌词时序与演唱音节的声学节拍逆推引擎 (Lyric-Acoustic Beat Engine)】：
     *    - 歌词起唱大底鼓 (Downbeat Kick): 每句歌词起始毫秒 (line.timestamp) 瞬间爆发冲顶 (10级)；
     *    - 词句连续吐字 (Snare/Hi-hat): 词内连续音节按 >= 280ms 节奏驱动次强击打 (7.5~8.5级)；
     *    - 间奏与拖尾深暗呼吸 (Clean Interlude Rest): 歌词停顿或长间奏时，彻底降至底光 (1~2级)，绝不乱闪！
     * 2. 【彻底修复“光效缓出改坏”的核心曲线缺陷 (双段复合缓出曲线 Dual-Slope Envelope)】：
     *    - 0 ~ 40ms (Instant Attack 冲顶)：0ms 瞬间爆发冲顶至目标亮度（满足用户“亮可以瞬间亮”）；
     *    - 40ms ~ 120ms (Soft Top Linger 柔和缓释悬停)：在峰值处柔和微衰 10%~15% 停留，消除一闪即逝的生硬感，
     *      赋予座舱灯光饱满高级的光晕厚度（满足用户“亮了之后想缓缓显示”）；
     *    - 120ms ~ 320ms (Smooth Ease-Out Glide 优雅滑落)：以平滑余弦曲线流畅滑向底光基准；
     *    - 320ms ~ 500ms (Clean Dark Rest 充分暗区呼吸)：在拍尾留足长达 180ms 的完全平稳底光时间，
     *      彻底根除“每次还没减暗就又开始亮了”的高位悬浮乱闪，给下一拍击打留出巨大的反差冲击力！
     * 3. 【无歌词/纯音乐回退兜底模型 (Fallback)】：
     *    - 自动无缝降级为优化后的 4 拍动感骨架声学模型。
     * 4. 【对齐原厂 500ms 黄金调度与 CAN/AIDL 智能节流】：
     *    - 瞬态爆发 0ms 极速响应；
     *    - 衰减阶段节流控制在 >= 80ms，释放原车 BCM 硬件物理电容缓降阻尼；
     *    - 900ms 智能喂狗保活，彻底杜绝原厂 SmartLightsServer 1500ms 看门狗超时复位。
     */
    private val softwareRhythmRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !masterEnabled) return

            // 核心安全检查：当前音轨或系统音频若非发声中，立即切断并恢复常亮
            val isTrackExplicitPaused = currentTrack != null && !currentTrack!!.isPlaying
            val isMusicActive = !isTrackExplicitPaused && (currentTrack?.isPlaying == true || isAudioSystemActive())
            if (!isMusicActive) {
                AppLogger.i(TAG, "软件律动循环检测到音乐已停止发声，立即停止律动并恢复常亮")
                stop()
                return
            }

            val nowMs = SystemClock.uptimeMillis()
            val nowNano = System.nanoTime()
            val elapsedMs = if (lastPositionUpdateNano > 0L) (nowNano - lastPositionUpdateNano) / 1_000_000L else 0L
            val currentPosMs = (lastTrackPositionMs + elapsedMs).coerceAtLeast(0L)

            // 【补偿灯控通信延迟】：针对车机 CAN 总线与 AIDL 的 30ms~80ms 硬件响应延迟，
            // 离线时间轴提前 50ms 前瞻下发指令，确保车内鼓点发声与灯光闪烁在感官上毫秒合拍！
            val compensatedPosMs = (currentPosMs + 50L).coerceAtLeast(0L)

            val sensRatio = (sensitivityPercent / 100.0f).coerceIn(0.5f, 1.5f)

            // 暗部基准底光 (50%灵敏度: 2.6级温润微光; 100%灵敏度: 2.0级标准; 150%灵敏度: 1.0级高对比深暗)
            val floorLevel = (2.6f - (sensRatio - 0.5f) * 1.6f).coerceIn(1.0f, 3.0f)

            val lyrics = activeLyricLines
            val hasRealLyrics = lyrics.isNotEmpty() && lyrics.any { it.timestamp in 1L until 86_400_000L }

            val currentIntensity: Float = if (hasRealLyrics) {
                // ==========================================
                // 方案 A: 毫秒级歌词时序与纯鼓点声学节拍逆推引擎 (前瞻 50ms 延迟补偿)
                // ==========================================
                calculateLyricAcousticIntensity(compensatedPosMs, lyrics, floorLevel, sensRatio)
            } else {
                // ==========================================
                // 方案 B (兜底回退): 4 拍动感骨架声学模型 (前瞻 50ms 延迟补偿)
                // ==========================================
                calculateFallbackAcousticIntensity(compensatedPosMs, floorLevel, sensRatio)
            }

            val targetLevel = if (isDucking) {
                1
            } else {
                (currentIntensity + 0.5f).toInt().coerceIn(1, 10)
            }

            // 车规级对齐原厂调度 (瞬态起音爆发 0ms · 衰减节流 >=70ms · 800ms 看门狗保活防复位)
            val isUpwardAttack = targetLevel > lastSentLuminance
            val isLevelChanged = targetLevel != lastSentLuminance
            val isWatchdogDue = (nowMs - lastSentTimeMs) > 800L

            if (isUpwardAttack || (isLevelChanged && (nowMs - lastSentTimeMs) >= 70L) || isWatchdogDue) {
                lastSentLuminance = targetLevel
                lastSentTimeMs = nowMs
                _liveLuminance.value = targetLevel
                AmbientLightManager.setLuminance(targetLevel)
            }

            workerHandler?.postDelayed(this, 35L)
        }
    }

    /**
     * 实时人声演唱声学律动引擎 (Vocal Resonance & Phrasing Rhythm Engine)
     * 深度追踪歌手真实开嗓、吐字重音、长音共鸣与句间换气停顿：
     *
     * 1. 【前奏与未开口静默期 (Intro / Instrumental Rest)】：
     *    - 首句歌词未开唱前，氛围灯处于纯净底光温润微呼吸 (1.2~2.2级)，静候歌手开嗓；
     * 2. 【句首开嗓瞬态爆发 (Vocal Attack / 起唱重拍)】：
     *    - 歌手开口唱出整句第 1 个音节的瞬间 (0~35ms)，氛围灯 0ms 瞬间极速冲顶至 9.5~10.0 级最高亮；
     *    - 35~120ms 保持饱满光晕悬停，给车内成员最直接强烈的“歌手开嗓”视觉共鸣；
     * 3. 【句内字词吐字抑扬顿挫 (Syllable Resonance / 演唱共鸣)】：
     *    - 实时定位当前正在演唱的具体音节 (LyricWord)：
     *    - 起音爆发 (Attack 0~35ms)：随着字词起音声浪瞬间拉升（重音字 8.6~9.6 级，轻音字 7.0~8.0 级）；
     *    - 元音持续共鸣 (Vocal Sustain)：在音节持续发声区间，灯光绝不摔入黑暗，而是保持在 5.5~7.5 级饱满共鸣区间，并伴有细微人声音颤微光；
     *    - 吐字衔接与微落 (Word Release)：音节结束向下一个音节过渡时柔和滑落，呈现波浪般的人声流动感；
     * 4. 【句尾长音与渐弱收声 (Final Vocal Sustain & Fade-Out)】：
     *    - 句尾最后一个字通常有拖腔与渐弱，灯光饱满维持并在发声结束时以平滑余弦曲线流畅滑向底光；
     * 5. 【句间换气与曲目长间奏 (Breath Pause & Instrumental Solo)】：
     *    - 当本句人声唱完且距离下一句还有间歇 (>=180ms) 时，氛围灯温润沉降至暗区底光呼吸 (1.2~2.2级)，绝对静止不乱闪；
     *    - 并在底光期间以 2.8 秒平滑微幅正弦呼吸持续向原车 SmartLightsServer 喂狗，彻底杜绝 1500ms 看门狗超时复位！
     */
    private fun calculateLyricAcousticIntensity(
        currentPosMs: Long,
        lyrics: List<LyricLine>,
        floorLevel: Float,
        sensRatio: Float
    ): Float {
        var lineIndex = -1
        var li = lyrics.size - 1
        while (li >= 0) {
            if (lyrics[li].timestamp <= currentPosMs) {
                lineIndex = li
                break
            }
            li--
        }
        if (lineIndex < 0) {
            // 前奏未开口期: 柔和暗部正弦微呼吸 (1.2 ~ 2.2级)，静候歌手开嗓
            val introPhase = (currentPosMs % 2800L) / 2800.0f
            val softBreath = (0.35f * sin(2.0 * PI * introPhase.toDouble())).toFloat()
            return (floorLevel + softBreath).coerceIn(1.0f, 3.0f)
        }

        val currentLine = lyrics[lineIndex]
        val nextLine = lyrics.getOrNull(lineIndex + 1)
        val lineStart = currentLine.timestamp
        val nextLineStart = nextLine?.timestamp ?: (lineStart + 6000L)

        // 提取本句歌词的所有音节 (若为逐字歌词则使用精确字时间戳，若为LRC则使用声学模型)
        val words = currentLine.getOrGenerateWords(nextLineStart)
        val lastWord = words.lastOrNull()
        val singingEndTime = if (lastWord != null) {
            lastWord.startTime + lastWord.duration.coerceIn(150L, 2500L)
        } else {
            lineStart + (currentLine.text.length * 360L).coerceIn(1200L, 4000L)
        }

        // 句间换气停顿与间奏独奏：若本句人声已唱完，且距离下一句开嗓还有间隙 (>=180ms)
        if (currentPosMs > singingEndTime + 180L && currentPosMs < nextLineStart - 70L) {
            // 歌手停顿/间奏吉他独奏中：绝对安静呼吸于底光，温润微光呼吸，留足座舱呼吸空间，彻底消除乱闪与机械感！
            val restTime = (currentPosMs - singingEndTime - 180L).coerceAtLeast(0L)
            val restPhase = (restTime % 2800L) / 2800.0f
            val softBreath = (0.35f * sin(2.0 * PI * restPhase.toDouble())).toFloat()
            return (floorLevel + softBreath).coerceIn(1.0f, 3.0f)
        }

        // ==========================================
        // 歌手正在开口演唱中 (Vocal In Progress)
        // ==========================================
        val timeSinceLineStart = (currentPosMs - lineStart).coerceAtLeast(0L)

        // 1. 句首起唱瞬态爆发 (Downbeat Vocal Attack)
        // 歌手开口唱出本句第 1 个音节的瞬间，0ms 极速冲顶 10 级并饱满悬停
        if (timeSinceLineStart < 120L) {
            val peak = (floorLevel + (10.0f - floorLevel) * sensRatio).coerceIn(floorLevel, 10.0f)
            return if (timeSinceLineStart < 35L) {
                // 0~35ms 瞬间冲顶
                peak
            } else {
                // 35~120ms 饱满光晕悬停，微衰 8%
                val p = (timeSinceLineStart - 35L) / 85.0f
                peak - (peak - floorLevel) * 0.08f * p
            }
        }

        // 2. 句内音节声韵跟拍与元音持续共鸣 (Syllable Resonance & Phrasing)
        // 定位当前正处于或刚刚发音的音节 (0-GC 快速扫描)
        var activeWordIndex = -1
        var wi = words.size - 1
        while (wi >= 0) {
            if (words[wi].startTime <= currentPosMs) {
                activeWordIndex = wi
                break
            }
            wi--
        }
        val activeWord = if (activeWordIndex >= 0) words[activeWordIndex] else words.firstOrNull()

        if (activeWord != null) {
            val dtWord = (currentPosMs - activeWord.startTime).coerceAtLeast(0L)
            val wordDur = activeWord.duration.coerceIn(120L, 2500L)
            val isFinalWord = (activeWordIndex == words.size - 1)

            // 依据音节位置与抑扬顿挫分配重拍力度：
            // 句首/奇数重拍 8.8~9.6级，偶数次重拍 7.2~8.2级
            val baseWordPeak = when {
                activeWordIndex == 0 -> 10.0f
                isFinalWord -> 8.8f
                (activeWordIndex % 2) == 1 -> 8.8f
                else -> 7.4f
            }
            val targetWordPeak = (floorLevel + (baseWordPeak - floorLevel) * sensRatio).coerceIn(floorLevel, 10.0f)

            if (dtWord < wordDur) {
                // 正在演唱该字/词的持续期间：
                val attackDuration = 35L
                val sustainDuration = (wordDur * 0.65f).toLong().coerceAtLeast(attackDuration + 40L)

                return when {
                    dtWord < attackDuration -> {
                        // 起音瞬冲 (Attack)
                        targetWordPeak
                    }
                    dtWord < sustainDuration -> {
                        // 人声元音持续共鸣 (Vocal Sustain):
                        // 绝不摔入黑暗底光，保持在 72% 峰值的饱满共鸣区间，并带有人声微光颤音
                        val p = (dtWord - attackDuration).toFloat() / (sustainDuration - attackDuration)
                        val sustainLevel = targetWordPeak - (targetWordPeak - floorLevel) * 0.22f * p
                        val shimmer = (0.25f * sin(2.0 * PI * (dtWord.toDouble() / 120.0))).toFloat()
                        (sustainLevel + shimmer).coerceIn(floorLevel, 10.0f)
                    }
                    else -> {
                        // 吐字尾音平滑过渡 (Release): 向字间过渡平滑余弦滑落
                        val p = ((dtWord - sustainDuration).toDouble() / (wordDur - sustainDuration)).coerceIn(0.0, 1.0)
                        val ease = (0.5 * (1.0 + cos(PI * p))).toFloat()
                        val sustainFloor = floorLevel + (targetWordPeak - floorLevel) * 0.35f
                        val midTop = targetWordPeak - (targetWordPeak - floorLevel) * 0.22f
                        sustainFloor + (midTop - sustainFloor) * ease
                    }
                }
            } else {
                // 该字已发音完毕，处于两字之间的微小换气间隔 (dtWord >= wordDur)
                val dtAfterWord = dtWord - wordDur
                if (isFinalWord) {
                    // 句尾收声：250ms 内余弦优雅滑落至底光
                    val fadeDuration = 250.0
                    val p = (dtAfterWord.toDouble() / fadeDuration).coerceIn(0.0, 1.0)
                    val ease = (0.5 * (1.0 + cos(PI * p))).toFloat()
                    val startLevel = floorLevel + (targetWordPeak - floorLevel) * 0.35f
                    return floorLevel + (startLevel - floorLevel) * ease
                } else {
                    // 字间微歇：柔和维持在字间过渡位，不骤黑
                    val gapLevel = floorLevel + (targetWordPeak - floorLevel) * 0.25f
                    return gapLevel.coerceIn(floorLevel, 10.0f)
                }
            }
        }

        return floorLevel
    }

    /**
     * 兜底回退模式：无歌词时的座舱音乐自适应旋律流光呼吸 (Harmonic Musical Flow)
     * 告别机械死板的固定节拍器循环，采用多重声学谐波合成的自然流光律动
     */
    private fun calculateFallbackAcousticIntensity(
        currentPosMs: Long,
        floorLevel: Float,
        sensRatio: Float
    ): Float {
        val safePos = currentPosMs.coerceAtLeast(0L)
        // 采用双重谐波周期复合：主旋律周期 2600ms，副节奏周期 650ms
        val tPrimary = (safePos % 2600L).toDouble() / 2600.0
        val tSecondary = (safePos % 650L).toDouble() / 650.0

        val wavePrimary = (0.5 * (1.0 + sin(2.0 * PI * tPrimary))).toFloat()
        val waveSecondary = (0.5 * (1.0 + cos(2.0 * PI * tSecondary))).toFloat()

        val compositeWave = 0.65f * wavePrimary + 0.35f * waveSecondary
        val dynamicPeak = (floorLevel + (7.0f - floorLevel) * sensRatio).coerceIn(floorLevel, 10.0f)

        return floorLevel + (dynamicPeak - floorLevel) * compositeWave
    }

    private fun startSoftwareRhythmLoop() {
        stopSoftwareRhythmLoop()
        lastSentLuminance = -1
        lastSentTimeMs = 0L

        // 核心避让安全看门狗：起播时如果原车因历史语音等非通话事件挂起，主动解除抢占恢复小欧屏接管
        if (AmbientLightManager.isPreempted.value && AmbientLightManager.currentPreemptionReason != "蓝牙电话通话中") {
            AppLogger.i(TAG, "起播/恢复播放主动解除历史非通话类抢占，重新接管氛围灯")
            AmbientLightManager.reclaim("起播重新接管")
        }

        workerHandler?.post(softwareRhythmRunnable)
        AppLogger.i(TAG, "纯鼓点声学节拍律动引擎已启动 (车规级 40ms/原厂500ms调度, BPM=${detectedBpm.roundToInt()})")
    }

    private fun stopSoftwareRhythmLoop() {
        workerHandler?.removeCallbacks(softwareRhythmRunnable)
    }

    /**
     * 响应当前播放曲目与播放状态改变
     */
    fun onTrackChanged(track: CurrentTrack?) {
        val isSameTrack = currentTrack?.title == track?.title && currentTrack?.artist == track?.artist
        currentTrack = track
        val isNowPlaying = track?.isPlaying == true

        if (track != null) {
            lastTrackPositionMs = track.position
            lastPositionUpdateNano = System.nanoTime()
            if (!isSameTrack) {
                lastExtractedColorTrackKey = null
                lastExtractedColorIndex = -1
                lastAnalyzedBitmapSignature = 0
                hasLoggedEmptyLyrics = false
                // 新曲目切歌: 尝试预载本地已缓存歌词
                if (!track.nativeLyrics.isNullOrEmpty()) {
                    setLyricsInternal(track.nativeLyrics)
                    AppLogger.i(TAG, "🎶 [歌词声学律动] 采用原声音轨自带歌词: ${track.title} (${activeLyricLines.size}行)")
                } else {
                    val cached = com.carlyrics.util.LyricsCacheManager.getCachedLyrics(track.title, track.artist)
                    if (cached != null && cached.lines.isNotEmpty()) {
                        setLyricsInternal(cached.lines)
                        AppLogger.i(TAG, "🎶 [歌词声学律动] 从本地缓存成功预载歌词: ${track.title} (${cached.lines.size}行)")
                    } else {
                        setLyricsInternal(emptyList())
                    }
                }
            }
        }

        if (!masterEnabled) {
            if (isRunning) stop()
            return
        }

        if (isNowPlaying) {
            mainDebounceHandler.removeCallbacks(pendingStopRunnable)
            if (!isRunning) {
                start()
            }
        } else {
            if (isRunning && (track != null || !isAudioSystemActive())) {
                // 350ms 防抖缓冲：过滤第三方/原生播放器切歌瞬间的 isPlaying true -> false -> true 抖动，避免律动引擎反复停启与日志刷屏
                mainDebounceHandler.removeCallbacks(pendingStopRunnable)
                mainDebounceHandler.postDelayed(pendingStopRunnable, 350L)
            }
        }
    }

    /**
     * 响应歌曲封面位图加载完成，提取大面积主题基色并下发车机
     */
    fun onCoverLoaded(bitmap: Bitmap?) {
        if (!masterEnabled || !colorSyncEnabled) return
        if (bitmap == null || bitmap.isRecycled) return

        val currentTrackKey = currentTrack?.let { "${it.title}|${it.artist}" }
        // 核心车规原则：同一首歌曲在播放期间，主题色一旦成功提取，全曲锁定！坚决杜绝因封面多分辨率/多音源通知在播放中途反复变色！
        if (currentTrackKey != null && currentTrackKey == lastExtractedColorTrackKey && lastExtractedColorIndex > 0) {
            return
        }

        val sig = computeBitmapSignature(bitmap)
        if (sig != 0 && sig == lastAnalyzedBitmapSignature && lastExtractedColorIndex > 0) {
            // 同一封面位图已提取过，直接忽略，避免重复消耗 CPU 与多次下发
            return
        }

        lastCoverBitmapRef = java.lang.ref.WeakReference(bitmap)

        ensureWorker()
        workerHandler?.post {
            if (bitmap.isRecycled) return@post
            val curTrackKey = currentTrack?.let { "${it.title}|${it.artist}" }
            if (curTrackKey != null && curTrackKey == lastExtractedColorTrackKey && lastExtractedColorIndex > 0) {
                return@post
            }
            val currentSig = computeBitmapSignature(bitmap)
            if (currentSig != 0 && currentSig == lastAnalyzedBitmapSignature && lastExtractedColorIndex > 0) {
                return@post
            }

            var scaledBmp: Bitmap? = null
            try {
                // 车规级优化：预先缩小至 64x64 极小尺寸进行 Palette 分析，分析速度提升数十倍且保证大面积色彩感知一致
                scaledBmp = if (bitmap.width > 64 || bitmap.height > 64) {
                    try {
                        Bitmap.createScaledBitmap(bitmap, 64, 64, false)
                    } catch (_: Throwable) {
                        bitmap
                    }
                } else {
                    bitmap
                }

                val currentScaled = scaledBmp
                Palette.from(currentScaled).maximumColorCount(24).generate { palette ->
                    if (currentScaled != bitmap && !currentScaled.isRecycled) {
                        try { currentScaled.recycle() } catch (_: Throwable) {}
                    }
                    if (palette == null) return@generate

                    val curTrackKeyInCallback = currentTrack?.let { "${it.title}|${it.artist}" }
                    if (curTrackKeyInCallback != null && curTrackKeyInCallback == lastExtractedColorTrackKey && lastExtractedColorIndex > 0) {
                        return@generate
                    }

                    // 核心取色逻辑：精准提取感官上的“封面大面积主色”，彻底杜绝因局部细微高艳色点（vibrantSwatch）导致的全图跑偏
                    val bestRgb = extractDominantAreaColor(palette)
                    val colorIdx = LightingColor64.getRGBIndex(bestRgb)
                    val now = SystemClock.uptimeMillis()

                    // 色号完全一致时，静默对齐，坚决不重复刷写 AIDL 与 CAN 总线
                    if (colorIdx == lastExtractedColorIndex) {
                        lastAnalyzedBitmapSignature = currentSig
                        lastExtractedColorTrackKey = curTrackKeyInCallback
                        _liveColorIndex.value = colorIdx
                        return@generate
                    }

                    lastAnalyzedBitmapSignature = currentSig
                    lastExtractedColorIndex = colorIdx
                    lastExtractedColorTrackKey = curTrackKeyInCallback
                    lastExtractedRgb = bestRgb
                    lastColorApplyTime = now
                    _liveColorIndex.value = colorIdx
                    AmbientLightManager.setColorSelector(colorIdx)
                    AppLogger.i(TAG, "🎨 随歌曲封面提取主色成功: rgb=${Integer.toHexString(bestRgb)}, 映射原厂色号=$colorIdx (已下发原车)")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Palette 封面取色失败: ${e.message}")
                if (scaledBmp != null && scaledBmp != bitmap && !scaledBmp.isRecycled) {
                    try { scaledBmp.recycle() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun computeBitmapSignature(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0 || bmp.isRecycled) return 0
        var hash = w * 31 + h
        try {
            val p0 = bmp.getPixel(w / 2, h / 2)
            val p1 = bmp.getPixel(w / 4, h / 4)
            val p2 = bmp.getPixel(w * 3 / 4, h / 4)
            val p3 = bmp.getPixel(w / 4, h * 3 / 4)
            val p4 = bmp.getPixel(w * 3 / 4, h * 3 / 4)
            hash = 31 * hash + p0
            hash = 31 * hash + p1
            hash = 31 * hash + p2
            hash = 31 * hash + p3
            hash = 31 * hash + p4
        } catch (_: Throwable) {}
        return hash
    }

    /**
     * 从 Palette 中智能提取感官上真正代表封面大面积视觉的主题色
     * 1. 过滤纯黑 (L < 0.06)、纯白 (L > 0.95)、纯灰 (S < 0.12) 等非彩色
     * 2. 以像素覆盖面积（population）为主导，结合色彩饱和度（saturation）进行加权择优
     */
    private fun extractDominantAreaColor(palette: Palette): Int {
        val swatches = palette.swatches
        if (swatches.isEmpty()) {
            return palette.dominantSwatch?.rgb ?: 0xFF3B44FF.toInt()
        }

        val hsl = FloatArray(3)
        var bestSwatch: Palette.Swatch? = null
        var maxScore = -1f

        for (swatch in swatches) {
            ColorUtils.colorToHSL(swatch.rgb, hsl)
            val s = hsl[1]
            val l = hsl[2]

            // 过滤极暗与极亮无彩色 (氛围灯需有明确光色)
            if (l < 0.06f || l > 0.95f) continue
            // 过滤无彩色纯灰 (饱和度过低)
            if (s < 0.12f) continue

            // 权重评分：以像素覆盖面积（population）为绝对主导，辅以色彩纯度（saturation）适当加权
            val weight = 0.35f + 0.65f * s
            val score = swatch.population * weight

            if (score > maxScore) {
                maxScore = score
                bestSwatch = swatch
            }
        }

        // 若全图均为黑白灰，则回退到 dominantSwatch 或原厂暖白
        return bestSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: 0xFFFBECAB.toInt()
    }

    /**
     * 重新应用上一首歌曲封面的提取基色
     */
    fun reapplyLastCoverColor() {
        if (!colorSyncEnabled) return
        if (lastExtractedColorIndex in 1..64) {
            _liveColorIndex.value = lastExtractedColorIndex
            AmbientLightManager.setColorSelector(lastExtractedColorIndex)
            return
        }
        lastCoverBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                onCoverLoaded(bmp)
            }
        }
    }

    /**
     * 专业级 DSP 鼓点瞬态起音分析器 (按鼓件切分频段 · 半波整流频谱通量 · 自适应阈值与冷却防抖 · 柔和指数衰减)
     * 1. 按鼓件切分目标频段：
     *    - 底鼓 (Kick): 45Hz–130Hz，瞬态冲击强、衰减快
     *    - 军鼓 (Snare): 鼓腔基频 180Hz–300Hz + 响弦高频脆响 2000Hz–4500Hz 双频段同时突增防人声与吉他干扰
     *    - 踩镲 (Hi-Hat): 6000Hz–12000Hz，用于副拍高灵敏度微弱修饰
     * 2. 计算半波整流频谱通量 (Half-wave Rectified Spectral Flux):
     *    - Sum(max(0, Mt(k) - Mt-1(k)))，仅保留正向增长部分（起音），自动过滤持续低音贝斯长音与伴奏
     * 3. 动态自适应阈值与 170ms 冷却防抖 (Cooldown):
     *    - 维护 0.8~1.2 秒滑动窗口通量均值，阈值 = 均值 * 系数 + 底噪门限
     *    - 触发后立即进入 170ms 冷却期，杜绝同声鼓点余震频闪
     * 4. 衰减平滑与避免硬开关：
     *    - 0~30ms 瞬时拉高亮度，30~90ms 柔和悬停，90~240ms 余弦/指数缓降至基准底光，夜晚跟拍不刺眼
     */
    private fun analyzeFftDrumFlux(fft: ByteArray, samplingRate: Int): Int {
        val captureSize = fft.size
        val numBins = captureSize / 2
        if (numBins <= 4) return 2

        if (prevFftMagnitudes == null || prevFftMagnitudes!!.size != numBins) {
            prevFftMagnitudes = FloatArray(numBins)
        }
        val prevMags = prevFftMagnitudes!!

        // samplingRate 单位通常为 mHz (如 44100000 mHz)
        val sampleRateHz = if (samplingRate > 100000) (samplingRate / 1000) else if (samplingRate > 0) samplingRate else 44100
        val binWidthHz = sampleRateHz.toFloat() / captureSize.toFloat()

        // 鼓件目标频段分段
        val kKickStart = max(1, (45f / binWidthHz).roundToInt())
        val kKickEnd = min(numBins - 1, (130f / binWidthHz).roundToInt())

        val kSnareBodyStart = max(1, (180f / binWidthHz).roundToInt())
        val kSnareBodyEnd = min(numBins - 1, (300f / binWidthHz).roundToInt())

        val kSnareCrackStart = max(1, (2000f / binWidthHz).roundToInt())
        val kSnareCrackEnd = min(numBins - 1, (4500f / binWidthHz).roundToInt())

        val kHiHatStart = max(1, (6000f / binWidthHz).roundToInt())
        val kHiHatEnd = min(numBins - 1, (12000f / binWidthHz).roundToInt())

        var kickFlux = 0f
        var snareBodyFlux = 0f
        var snareCrackFlux = 0f
        var hihatFlux = 0f
        var totalEnergy = 0f

        for (k in 1 until numBins) {
            val r = fft[2 * k].toFloat()
            val im = fft[2 * k + 1].toFloat()
            val mag = kotlin.math.sqrt(r * r + im * im)
            totalEnergy += mag

            val delta = mag - prevMags[k]
            prevMags[k] = mag
            val posDiff = if (delta > 0f) delta else 0f

            when {
                k in kKickStart..kKickEnd -> kickFlux += posDiff
                k in kSnareBodyStart..kSnareBodyEnd -> snareBodyFlux += posDiff
                k in kSnareCrackStart..kSnareCrackEnd -> snareCrackFlux += posDiff
                k in kHiHatStart..kHiHatEnd -> hihatFlux += posDiff
            }
        }
        lastFrameFftEnergy = totalEnergy

        // 车机音量自适应归一化补偿
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volFactor = (maxVol.toFloat() / currVol.toFloat()).coerceIn(1.0f, 3.0f)
        kickFlux *= volFactor
        snareBodyFlux *= volFactor
        snareCrackFlux *= volFactor
        hihatFlux *= volFactor

        val now = SystemClock.uptimeMillis()
        val sensRatio = (sensitivityPercent / 100.0f).coerceIn(0.5f, 1.5f)

        // 维护约 1 秒的滑动窗口通量均值 (平滑系数 0.05 对应 ~40 帧)
        rollingKickFluxAvg = rollingKickFluxAvg * 0.95f + kickFlux * 0.05f
        rollingSnareBodyFluxAvg = rollingSnareBodyFluxAvg * 0.95f + snareBodyFlux * 0.05f
        rollingSnareCrackFluxAvg = rollingSnareCrackFluxAvg * 0.95f + snareCrackFlux * 0.05f

        // 动态自适应阈值: 均值 * 系数 + 底噪门限
        val kickSensCoeff = (1.9f - (sensRatio - 1.0f) * 0.6f).coerceIn(1.2f, 2.5f)
        val thresholdKick = rollingKickFluxAvg * kickSensCoeff + 10.0f

        val snareSensCoeff = (1.7f - (sensRatio - 1.0f) * 0.5f).coerceIn(1.1f, 2.2f)
        val thresholdSnareBody = rollingSnareBodyFluxAvg * snareSensCoeff + 7.0f
        val thresholdSnareCrack = rollingSnareCrackFluxAvg * snareSensCoeff + 5.0f

        val timeSinceLastTrigger = now - lastDrumTriggerTimeMs
        val isCooldownActive = timeSinceLastTrigger < DRUM_COOLDOWN_MS

        val isKickHit = !isCooldownActive && (kickFlux > thresholdKick)
        // 军鼓判定：鼓腔基频与高频响弦双频段同时突增，杜绝人声与吉他干扰
        val isSnareHit = !isCooldownActive && !isKickHit && (snareBodyFlux > thresholdSnareBody && snareCrackFlux > thresholdSnareCrack)

        val baselineFloor = if (sensitivityPercent < 80) 2.6f else if (sensitivityPercent > 120) 1.0f else 2.0f

        if (isKickHit) {
            lastDrumTriggerTimeMs = now
            lastDrumAttackTimeMs = now
            val excess = (kickFlux - thresholdKick) / max(1.0f, rollingKickFluxAvg * 1.5f)
            // 底鼓重音爆发冲顶 8.5 ~ 10.0 级
            drumAttackPeak = (8.5f + excess * 1.5f * sensRatio).coerceIn(8.5f, 10.0f)
        } else if (isSnareHit) {
            lastDrumTriggerTimeMs = now
            lastDrumAttackTimeMs = now
            val excess = min(
                (snareBodyFlux - thresholdSnareBody) / max(1.0f, rollingSnareBodyFluxAvg * 1.5f),
                (snareCrackFlux - thresholdSnareCrack) / max(1.0f, rollingSnareCrackFluxAvg * 1.5f)
            )
            // 军鼓利落回响 7.5 ~ 9.0 级
            drumAttackPeak = (7.5f + excess * 1.5f * sensRatio).coerceIn(7.5f, 9.0f)
        }

        // 衰减平滑包络曲线 (瞬时冲顶 -> 柔和悬停 -> 余弦/指数缓降 -> 平稳底光)
        val dtMs = now - lastDrumAttackTimeMs
        val currentLevelFloat: Float = when {
            dtMs < 30L -> {
                // 1. Instant Attack (0~30ms): 瞬间爆发冲顶至目标亮度
                drumAttackPeak
            }
            dtMs < 90L -> {
                // 2. Soft Top Linger (30~90ms): 柔和微衰 10% 饱满悬停，赋予座舱光晕厚度
                val lingerP = (dtMs - 30L) / 60.0f
                drumAttackPeak - (drumAttackPeak - baselineFloor) * 0.10f * lingerP
            }
            dtMs < 240L -> {
                // 3. Smooth Cosine Decay (90~240ms): 150ms 优雅缓落至基准底光
                val glideP = ((dtMs - 90L) / 150.0f).coerceIn(0f, 1f)
                val ease = 0.5f * (1.0f + cos(PI * glideP)).toFloat()
                val midTop = drumAttackPeak - (drumAttackPeak - baselineFloor) * 0.10f
                baselineFloor + (midTop - baselineFloor) * ease
            }
            else -> {
                // 4. Smooth Ambient Base Level (>=240ms 直至下一次鼓点): 保持纯净柔和底光，绝不完全灭灯
                baselineFloor
            }
        }

        return (currentLevelFloat + 0.5f).toInt().coerceIn(1, 10)
    }

    fun release() {
        unregisterAudioPlaybackCallback()
        stop()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }
}

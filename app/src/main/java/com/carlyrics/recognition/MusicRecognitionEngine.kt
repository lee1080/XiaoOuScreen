package com.carlyrics.recognition

import android.app.Notification
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.MusicSource
import com.carlyrics.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class MusicRecognitionEngine(private val context: Context) {

    companion object {
        private const val TAG = "MusicRecognition"
        private const val POLL_INTERVAL_MS = 2000L

        // Android 原生 / 车载 A2DP Sink 与 AVRCP Controller 广播 Action
        private const val ACTION_AVRCP_TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
        private const val ACTION_AVRCP_CONNECTION_STATE_CHANGED = "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_A2DP_SINK_PLAYING_STATE_CHANGED = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        private const val ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_A2DP_PLAYING_STATE_CHANGED = "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
        private const val ACTION_A2DP_CONNECTION_STATE_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"

        // AVRCP Controller 广播 Extras
        private const val EXTRA_AVRCP_METADATA = "android.bluetooth.avrcp-controller.profile.extra.METADATA"
        private const val EXTRA_AVRCP_PLAYBACK = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"
        private const val EXTRA_AVRCP_SONGLEN = "android.bluetooth.avrcp-controller.profile.extra.SONGLEN"

        // 蓝牙状态码 (Android BluetoothProfile)
        private const val BT_STATE_PLAYING = 10
        private const val BT_STATE_NOT_PLAYING = 11
        private const val BT_PROFILE_STATE_CONNECTED = 2
        private const val BT_PROFILE_STATE_DISCONNECTED = 0

        /**
         * 校验曲名/歌手是否属于前台保活、系统通知等无效占位符
         */
        fun isInvalidPlaceholderTrack(title: String?, artist: String?): Boolean {
            val t = title?.trim() ?: ""
            val a = artist?.trim() ?: ""
            if (t.isEmpty() || t == "0" || t.all { it.isDigit() }) return true

            val invalidKeywords = listOf(
                "正在运行", "点击进入", "点按即可", "点按了解", "了解详情",
                "已打开", "后台运行", "运行中", "service 正在运行", "服务正在运行",
                "mobilelog", "mtklogger", "手势识别", "未在播放", "暂无播放",
                "无播放内容", "未知歌曲", "喜马拉雅", "收音机", "网络电台", "fm", "am", "车机电台"
            )
            val tLower = t.lowercase()
            val aLower = a.lowercase()
            for (kw in invalidKeywords) {
                if (tLower.contains(kw) || aLower.contains(kw)) {
                    return true
                }
            }
            return false
        }

        fun isBlacklistedPackage(packageName: String): Boolean {
            val lower = packageName.lowercase()
            return lower == "android" ||
                lower == "com.android.systemui" ||
                lower == "com.android.welcome" ||
                lower.startsWith("com.mediatek.") ||
                lower.startsWith("com.car.xiaoouscreen") ||
                lower.startsWith("com.changan.oushang") ||
                lower == "com.chinatsp.automate" ||
                lower == "com.chinatsp.etc" ||
                lower == "com.chinatsp.gesturerecognition" ||
                lower == "com.chinatsp.onecall" ||
                lower == "com.mojoxing.light" ||
                lower == "com.oushang.radio" ||
                lower.startsWith("com.oushang.radio") ||
                lower.contains("ximalaya")
        }
    }

    private val _currentTrack = MutableStateFlow<CurrentTrack?>(null)
    val currentTrack: StateFlow<CurrentTrack?> = _currentTrack

    /** 原车酷狗音乐专属 AIDL 客户端 (优先读取原车/U盘歌词与封面，无权限门槛) */
    val kuGouClient = KuGouNativeClient(context, object : KuGouNativeClient.KuGouListener {
        override fun onTrackChanged(track: CurrentTrack) {
            updateCurrentTrack(track, "KuGouNative")
        }

        override fun onProgressChanged(positionMs: Long, durationMs: Long) {
            val current = _currentTrack.value
            if (current != null && current.source == MusicSource.KUGOU_NATIVE) {
                val dur = if (durationMs > 0) durationMs else current.duration
                val pos = positionMs.coerceAtLeast(0L)
                if (current.position != pos || current.duration != dur) {
                    _currentTrack.value = current.copy(position = pos, duration = dur, isPlaying = true)
                }
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            val current = _currentTrack.value
            if (current != null && current.source == MusicSource.KUGOU_NATIVE) {
                if (current.isPlaying != isPlaying) {
                    updateCurrentTrack(current.copy(isPlaying = isPlaying), "KuGouNativeStateChanged")
                }
            }
        }
    })

    // 动态进度推导与切歌脏数据防护状态
    @Volatile private var trackedTitle: String = ""
    @Volatile private var trackedArtist: String = ""
    @Volatile private var lastSamplePos: Long = -1L
    @Volatile private var lastSampleTime: Long = 0L
    @Volatile private var lastPosAdvanceTime: Long = 0L
    @Volatile private var lastTrackEndPos: Long = 0L
    @Volatile private var trackSwitchTime: Long = 0L
    @Volatile private var isDynamicPlayingConfirmed: Boolean? = null

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 改为 ConcurrentHashMap 解决多线程并发读写问题 */
    private val activeControllers = ConcurrentHashMap<String, MediaController>()
    /** 每个 controller 持有独立 callback 实例，onMetadataChanged 中直接引用对应 controller */
    private val activeCallbacks = ConcurrentHashMap<String, MediaController.Callback>()

    /** 记录最近一次识别到的蓝牙音轨信息，供切歌/断连或轮询兜底复用 */
    @Volatile
    private var lastBluetoothTrack: CurrentTrack? = null

    /** 记录蓝牙 A2DP 音频流是否处于真实发声推流状态 (STATE_PLAYING = 10) */
    @Volatile
    private var isBtA2dpStreaming: Boolean = false

    /** 车载 Android 9 A2DP Sink 官方媒体浏览器连接对象 */
    private var a2dpMediaBrowser: MediaBrowser? = null

    @Volatile
    private var isBtReceiverRegistered: Boolean = false

    private var pollingHandler: Handler? = null
    private val pollingRunnable = Runnable { scanAllSessions() }

    /** SecurityException 标志：MediaSession 注册失败时设为 true，供外部查询 */
    @Volatile
    var hasMediaSessionError: Boolean = false
        private set

    /** 记录最近一次识别来源，供 UI 展示调试信息 */
    @Volatile
    var lastDetectionSource: String = "none"
        private set

    /** 记录最后一次 forceRefresh 的结果信息 */
    @Volatile
    var lastRefreshInfo: String = ""
        private set

    /**
     * 多音源集中焦点仲裁调度器：
     * 1. 严格过滤无效占位通知 (正在运行、点击进入、MTKLogger、0 等)
     * 2. 仲裁规则：
     *    - 正在发声且推进的音源 (AVRCP / MediaSession) 为第一优先级
     *    - 通知源 (NOTIFICATION) 仅在当前无有效/播放音源时作为最低兜底，绝不能覆盖正在发声或具备有效元数据的音源
     */
    @Synchronized
    private fun updateCurrentTrack(candidate: CurrentTrack?, reason: String) {
        if (candidate == null) {
            _currentTrack.value = null
            return
        }

        // 1. 拦截无效文本与前台保活通知
        if (isInvalidPlaceholderTrack(candidate.title, candidate.artist)) {
            AppLogger.d(TAG, "[$reason] 拦截占位/无效音轨: title=${candidate.title}, artist=${candidate.artist}, pkg=${candidate.packageName}")
            return
        }

        val current = _currentTrack.value
        if (current != null) {
            // 2. 通知源降权：NOTIFICATION 绝对无法抢占正在播放或具备有效时长/进度的音源
            if (candidate.source == MusicSource.NOTIFICATION) {
                if (current.isPlaying || current.position > 0 || current.duration > 0 || current.source != MusicSource.NOTIFICATION) {
                    AppLogger.d(TAG, "[$reason] 通知源拦截: 维持活跃主音源 (${current.source}, title=${current.title})")
                    return
                }
            }

            // 3. 正在实际发声且进度的音源防抢占：如果当前已有音源在播放中，候选音源来自另一个未在播放的通道，则保持当前在播音源
            if (current.isPlaying && !candidate.isPlaying && candidate.packageName != current.packageName) {
                AppLogger.d(TAG, "[$reason] 候选音源未在播放，保持正在发声的音源: ${current.title} (${current.packageName})")
                return
            }

            // 4. 同一音源通道的超短瞬态假暂停防抖过滤：
            // 如果当前在播音源与候选音源属于同一曲目且来自蓝牙通道 (BLUETOOTH)，
            // 且上一次物理递增推进发生在 2.0 秒内，若候选音源突然上报 isPlaying=false 且 position 未改变，
            // 极大概率是双通道时序差造成的虚假震荡，忽略本次假暂停！
            if (current.isPlaying && !candidate.isPlaying &&
                current.title == candidate.title &&
                current.artist == candidate.artist &&
                current.source == MusicSource.BLUETOOTH &&
                candidate.source == MusicSource.BLUETOOTH
            ) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPosAdvanceTime < 2000L) {
                    AppLogger.d(TAG, "[$reason] 蓝牙瞬态时序防抖: 距上次物理递增仅 ${now - lastPosAdvanceTime}ms，过滤虚假暂停状态")
                    return
                }
            }

            // 5. 重复微小抖动过滤：同一音轨相同播放状态下，若进度微小抖动(<500ms)，避免高频轰炸 StateFlow 与日志
            if (current.title == candidate.title &&
                current.artist == candidate.artist &&
                current.isPlaying == candidate.isPlaying &&
                current.duration == candidate.duration &&
                current.packageName == candidate.packageName &&
                current.source == candidate.source &&
                kotlin.math.abs(current.position - candidate.position) < 500L
            ) {
                return
            }
        }

        _currentTrack.value = candidate
        if (candidate.source == MusicSource.BLUETOOTH) {
            lastBluetoothTrack = candidate
        }
        lastDetectionSource = if (candidate.isPlaying) "$reason-Active" else "$reason-Paused"
        AppLogger.d(TAG, "[$reason] 音源仲裁生效: title=${candidate.title}, artist=${candidate.artist}, isPlaying=${candidate.isPlaying}, pos=${candidate.position}, dur=${candidate.duration}, src=${candidate.source}")
    }

    /**
     * 动态播放状态推导与切歌脏数据防护：
     * 1. 切歌瞬时防护：在新歌开始的前 2.5 秒内，如果收到接近上一首歌结束位置的残留脏进度，强制丢弃并归零，杜绝歌词进度条闪回。
     * 2. 动态播放推导：对于同一首歌，若检测到 pos 稳定正向递增 (增量在 250ms ~ 4000ms)，即使底层报 isPlaying=false 也强制修正为 true；
     *    反之若同一首歌 pos 停滞超过 2.8 秒，修正为 isPlaying=false。
     */
    private fun resolveDynamicStateAndPos(
        title: String,
        artist: String,
        rawPos: Long,
        rawIsPlaying: Boolean,
        source: MusicSource
    ): Pair<Long, Boolean> {
        val now = android.os.SystemClock.elapsedRealtime()
        var pos = if (rawPos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && rawPos >= 0L) rawPos else 0L

        // 1. 切歌检测与脏进度瞬移过滤
        val isNewTrack = (title != trackedTitle || artist != trackedArtist)
        if (isNewTrack) {
            if (trackedTitle.isNotBlank()) {
                lastTrackEndPos = lastSamplePos.coerceAtLeast(0L)
            }
            trackSwitchTime = now
            trackedTitle = title
            trackedArtist = artist
            lastSamplePos = pos
            lastSampleTime = now
            lastPosAdvanceTime = now
            isDynamicPlayingConfirmed = null
        }

        // 新歌开始的前 2500ms 内，如果收到接近上一首歌结尾的残留脏进度 (例如 > 5000ms 且偏差在 6000ms 范围内)，强制丢弃并置零
        if (now - trackSwitchTime <= 2500L && lastTrackEndPos > 5000L) {
            if (kotlin.math.abs(pos - lastTrackEndPos) < 6000L || pos > lastTrackEndPos - 2000L) {
                AppLogger.d(TAG, "切歌脏进度拦截: 丢弃残留进度 $pos (上一首结束于 $lastTrackEndPos)")
                pos = 0L
            }
        }

        // 2. 基于同一首歌进度递增/停滞的动态状态判定
        val timeDelta = now - lastSampleTime
        val posDelta = pos - lastSamplePos

        if (!isNewTrack && pos > 0L) {
            // 采样间隔在 400ms ~ 3500ms 之间
            if (timeDelta in 400..3500) {
                if (posDelta in 250..4000) {
                    // 进度正在以真实物理速率稳定递增！确认正在播放
                    isDynamicPlayingConfirmed = true
                    lastPosAdvanceTime = now
                    lastSamplePos = pos
                    lastSampleTime = now
                } else if (posDelta == 0L) {
                    // 进度完全未推进
                    if (now - lastPosAdvanceTime >= 2000L || !rawIsPlaying) {
                        // 停滞时间超过 2 秒，或者底层明确指示暂停，确认暂停
                        isDynamicPlayingConfirmed = false
                    }
                } else if (posDelta > 0) {
                    lastSamplePos = pos
                    lastSampleTime = now
                }
            } else if (timeDelta >= 3500) {
                lastSamplePos = pos
                lastSampleTime = now
            }
        } else if (isNewTrack) {
            lastSamplePos = pos
            lastSampleTime = now
            lastPosAdvanceTime = now
        }

        // 3. 动态状态最终仲裁 (严格遵循真实物理状态)：
        val finalPlaying = if (!rawIsPlaying) {
            // 底层指示未播放 (PAUSED / NOT_PLAYING / rawIsPlaying == false)
            if (isDynamicPlayingConfirmed == true && (posDelta in 250..4000 || now - lastPosAdvanceTime < 2000L)) {
                // 若进度正在以物理速率狂飙递增，或距离上次物理递增还在 2 秒稳定窗口期内，保持播放
                true
            } else {
                // 停滞超过 2 秒且底层指示非播放，坚决确认暂停！
                isDynamicPlayingConfirmed = false
                false
            }
        } else {
            // 底层指示正在播放 (rawIsPlaying == true)
            if (isDynamicPlayingConfirmed == false) {
                // 虽报播放，但采样发现进度长期停滞不动
                // 此时检测音频流是否真的在响
                val isAudioReallyActive = isBtA2dpStreaming || try { audioManager.isMusicActive } catch (_: Exception) { false }
                isAudioReallyActive
            } else {
                true
            }
        }

        return Pair(pos, finalPlaying)
    }

    // ==========================================
    // 蓝牙广播接收器：直接捕获 AVRCP Controller / A2DP Sink 信号
    // ==========================================
    private val btMusicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val action = intent.action ?: return
            handleBluetoothBroadcast(action, intent)
        }
    }

    private fun handleBluetoothBroadcast(action: String, intent: Intent) {
        try {
            when (action) {
                ACTION_AVRCP_TRACK_EVENT -> {
                    handleAvrcpTrackEvent(intent)
                }
                ACTION_A2DP_SINK_PLAYING_STATE_CHANGED,
                ACTION_A2DP_PLAYING_STATE_CHANGED -> {
                    handleA2dpPlayingStateChanged(intent)
                }
                ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED,
                ACTION_A2DP_CONNECTION_STATE_CHANGED,
                ACTION_AVRCP_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    handleBtConnectionStateChanged(action, intent)
                }
                "com.android.music.metachanged",
                "com.android.music.playstatechanged",
                "com.android.music.playbackcomplete",
                "com.chinatsp.music.action.METADATA_CHANGED",
                "com.chinatsp.music.action.PLAY_STATE_CHANGED",
                "com.chinatsp.music.metachanged",
                "com.chinatsp.music.playstatechanged" -> {
                    handleGeneralMusicBroadcast(intent)
                }
                "com.media.radio",
                "com.media.tuner",
                "com.chinatsp.music.remote.local.radio",
                "com.chinatsp.music.remote.ol.radio" -> {
                    AppLogger.d(TAG, "捕获欧尚收音机/喜马拉雅电台激活广播: $action，排除电台并回退默认天气时钟")
                    updateCurrentTrack(null, "RadioActivated")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleBluetoothBroadcast error: ${e.message}", e)
        }
    }

    /**
     * 处理 AVRCP Controller 核心 Track 事件 (包含 MediaMetadata 和 PlaybackState)
     */
    private fun handleAvrcpTrackEvent(intent: Intent) {
        try {
            val meta = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_AVRCP_METADATA, MediaMetadata::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<MediaMetadata>(EXTRA_AVRCP_METADATA)
            }

            val pbState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_AVRCP_PLAYBACK, PlaybackState::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<PlaybackState>(EXTRA_AVRCP_PLAYBACK)
            }
            val songLen = intent.getLongExtra(EXTRA_AVRCP_SONGLEN, 0L)

            var title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("track")
            var artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: intent.getStringExtra("artist") ?: ""
            var album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: intent.getStringExtra("album") ?: ""
            var duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration <= 0L && songLen > 0L) {
                duration = songLen
            }
            if (duration <= 0L) {
                duration = intent.getLongExtra("duration", 0L)
            }

            // 如果本事件只携带了播放状态变更(pbState)，未重新下发 metadata，则复用上一次记录的歌曲信息
            if (title.isNullOrBlank() && lastBluetoothTrack != null) {
                title = lastBluetoothTrack?.title
                if (artist.isBlank()) artist = lastBluetoothTrack?.artist ?: ""
                if (album.isBlank()) album = lastBluetoothTrack?.album ?: ""
                if (duration <= 0L) duration = lastBluetoothTrack?.duration ?: 0L
            }

            if (title.isNullOrBlank()) {
                AppLogger.d(TAG, "AVRCP TRACK_EVENT: title is empty, state=${pbState?.state}")
                return
            }

            val stateCode = pbState?.state ?: -1
            val rawPos = pbState?.position ?: intent.getLongExtra("position", PlaybackState.PLAYBACK_POSITION_UNKNOWN)
            val current = _currentTrack.value
            val initialPos = if (rawPos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && rawPos > 0L) {
                rawPos
            } else if (current != null && current.title == title && current.position > 0L) {
                current.position
            } else {
                0L
            }

            // 更新蓝牙 A2DP 音频流活跃状态
            val isExplicitPaused = (stateCode == PlaybackState.STATE_PAUSED || stateCode == 3)
            if (isExplicitPaused) {
                isBtA2dpStreaming = false
            } else if (stateCode == PlaybackState.STATE_PLAYING || stateCode == 2) {
                isBtA2dpStreaming = true
            }

            // NforeBluetooth 底层实现中，mPlayStatus==2 为 PLAYING，3 为 PAUSED
            val rawIsPlaying = if (isExplicitPaused) {
                false
            } else {
                isBtA2dpStreaming || stateCode == PlaybackState.STATE_PLAYING || stateCode == 2
            }

            // 基于进度递增/停滞的动态状态判定与切歌脏进度拦截
            val (resolvedPos, resolvedPlaying) = resolveDynamicStateAndPos(
                title = title,
                artist = artist,
                rawPos = initialPos,
                rawIsPlaying = rawIsPlaying,
                source = MusicSource.BLUETOOTH
            )

            if (!resolvedPlaying) {
                isBtA2dpStreaming = false
            }

            val track = CurrentTrack(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                position = resolvedPos,
                source = MusicSource.BLUETOOTH,
                packageName = "com.android.bluetooth",
                isPlaying = resolvedPlaying
            )
            updateCurrentTrack(track, "Bluetooth-AVRCP")
            AppLogger.d(TAG, "AVRCP track resolved: title=$title, artist=$artist, isPlaying=$resolvedPlaying (raw=$rawIsPlaying), pos=$resolvedPos, dur=$duration")
        } catch (e: Exception) {
            Log.w(TAG, "handleAvrcpTrackEvent failed: ${e.message}", e)
        }
    }

    /**
     * 处理 A2DP Sink 音频流播放状态变化 (STATE_PLAYING = 10, STATE_NOT_PLAYING = 11)
     */
    private fun handleA2dpPlayingStateChanged(intent: Intent) {
        try {
            val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
            val isPlaying = (state == BT_STATE_PLAYING)
            isBtA2dpStreaming = isPlaying
            AppLogger.d(TAG, "A2DP playing state changed: state=$state, isPlaying=$isPlaying")

            val current = _currentTrack.value
            if (current != null && (current.source == MusicSource.BLUETOOTH || current.packageName.contains("bluetooth"))) {
                _currentTrack.value = current.copy(isPlaying = isPlaying)
                lastDetectionSource = if (isPlaying) "Bluetooth-A2DP-Active" else "Bluetooth-A2DP-Paused"
            } else if (isPlaying && lastBluetoothTrack != null) {
                _currentTrack.value = lastBluetoothTrack?.copy(isPlaying = true)
                lastDetectionSource = "Bluetooth-A2DP-Resumed"
            }

            if (isPlaying) {
                forceRefresh()
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleA2dpPlayingStateChanged error: ${e.message}", e)
        }
    }

    /**
     * 处理蓝牙设备断开
     */
    private fun handleBtConnectionStateChanged(action: String, intent: Intent) {
        try {
            val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
            val isDisconnected = (state == BT_PROFILE_STATE_DISCONNECTED) || (action == BluetoothDevice.ACTION_ACL_DISCONNECTED)
            if (isDisconnected) {
                isBtA2dpStreaming = false
                AppLogger.d(TAG, "Bluetooth disconnected: action=$action, state=$state")
                val current = _currentTrack.value
                if (current != null && (current.source == MusicSource.BLUETOOTH || current.packageName.contains("bluetooth"))) {
                    _currentTrack.value = null
                    lastBluetoothTrack = null
                    lastDetectionSource = "Bluetooth-Disconnected"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleBtConnectionStateChanged error: ${e.message}", e)
        }
    }

    /**
     * 处理系统通用或长安车机定制音乐广播兜底 (com.android.music / com.chinatsp.music)
     */
    private fun handleGeneralMusicBroadcast(intent: Intent) {
        try {
            val extras = intent.extras
            val title = intent.getStringExtra("track")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("song")
                ?: extras?.getCharSequence("track")?.toString()
                ?: extras?.getCharSequence("title")?.toString()
                ?: return

            val artist = intent.getStringExtra("artist")
                ?: extras?.getCharSequence("artist")?.toString()
                ?: ""
            val album = intent.getStringExtra("album")
                ?: extras?.getCharSequence("album")?.toString()
                ?: ""

            val durObj = extras?.get("duration")
            val duration = when (durObj) {
                is Number -> durObj.toLong()
                is String -> durObj.toLongOrNull() ?: 0L
                else -> 0L
            }

            val posObj = extras?.get("position")
            val position = when (posObj) {
                is Number -> posObj.toLong()
                is String -> posObj.toLongOrNull() ?: 0L
                else -> 0L
            }

            val playingObj = extras?.get("playing")
            val isPlayingIntent = when (playingObj) {
                is Boolean -> playingObj
                is Number -> playingObj.toInt() == 1
                is String -> playingObj.equals("true", ignoreCase = true) || playingObj == "1"
                else -> false
            }
            val isPlaying = isPlayingIntent

            val pkg = intent.getStringExtra("package") ?: "system.media.broadcast"
            if (isBlacklistedPackage(pkg) || isInvalidPlaceholderTrack(title, artist)) {
                return
            }

            val (resolvedPos, resolvedPlaying) = resolveDynamicStateAndPos(
                title = title,
                artist = artist,
                rawPos = position.coerceAtLeast(0L),
                rawIsPlaying = isPlaying,
                source = MusicSource.BROADCAST
            )

            val track = CurrentTrack(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                position = resolvedPos,
                source = MusicSource.BROADCAST,
                packageName = pkg,
                isPlaying = resolvedPlaying
            )

            updateCurrentTrack(track, "Broadcast($pkg)")
        } catch (e: Exception) {
            Log.w(TAG, "handleGeneralMusicBroadcast error: ${e.message}", e)
        }
    }

    private fun registerBluetoothReceiver() {
        if (isBtReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                // 原生 / 车载 A2DP Sink 与 AVRCP 广播
                addAction(ACTION_AVRCP_TRACK_EVENT)
                addAction(ACTION_AVRCP_CONNECTION_STATE_CHANGED)
                addAction(ACTION_A2DP_SINK_PLAYING_STATE_CHANGED)
                addAction(ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED)
                addAction(ACTION_A2DP_PLAYING_STATE_CHANGED)
                addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)

                // 通用与车机定制音乐广播
                addAction("com.android.music.metachanged")
                addAction("com.android.music.playstatechanged")
                addAction("com.android.music.playbackcomplete")
                addAction("com.chinatsp.music.action.METADATA_CHANGED")
                addAction("com.chinatsp.music.action.PLAY_STATE_CHANGED")
                addAction("com.chinatsp.music.metachanged")
                addAction("com.chinatsp.music.playstatechanged")

                // 欧尚收音机与喜马拉雅电台广播 (捕获后主动回退默认天气时钟)
                addAction("com.media.radio")
                addAction("com.media.tuner")
                addAction("com.chinatsp.music.remote.local.radio")
                addAction("com.chinatsp.music.remote.ol.radio")
            }
            ContextCompat.registerReceiver(
                context,
                btMusicReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isBtReceiverRegistered = true
            AppLogger.d(TAG, "Bluetooth music broadcast receiver registered OK")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Bluetooth receiver: ${e.message}", e)
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (!isBtReceiverRegistered) return
        try {
            context.unregisterReceiver(btMusicReceiver)
            isBtReceiverRegistered = false
            AppLogger.d(TAG, "Bluetooth music broadcast receiver unregistered")
        } catch (_: Exception) {}
    }

    /**
     * 连接车载 A2dpMediaBrowserService 以唤醒并直接绑定 com.android.bluetooth MediaSession
     */
    private fun connectA2dpMediaBrowser() {
        try {
            if (a2dpMediaBrowser != null && a2dpMediaBrowser?.isConnected == true) return
            val component = ComponentName(
                "com.android.bluetooth",
                "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"
            )
            a2dpMediaBrowser = MediaBrowser(
                context,
                component,
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            val token = a2dpMediaBrowser?.sessionToken ?: return
                            AppLogger.d(TAG, "A2dpMediaBrowser connected OK! token=$token")
                            val controller = MediaController(context, token)
                            val callback = createMediaCallback(controller)
                            controller.registerCallback(callback)
                            activeControllers[controller.packageName] = controller
                            activeCallbacks[controller.packageName] = callback
                            controller.metadata?.let { md ->
                                onMetadataFrom(controller, md)
                            }
                        } catch (e: Exception) {
                            AppLogger.d(TAG, "A2dpMediaBrowser onConnected controller error: ${e.message}")
                        }
                    }

                    override fun onConnectionSuspended() {
                        AppLogger.d(TAG, "A2dpMediaBrowser connection suspended")
                    }

                    override fun onConnectionFailed() {
                        AppLogger.d(TAG, "A2dpMediaBrowser connection failed (service may not be enabled on this device)")
                    }
                },
                null
            )
            a2dpMediaBrowser?.connect()
            AppLogger.d(TAG, "A2dpMediaBrowser connect() initiated")
        } catch (e: Exception) {
            AppLogger.d(TAG, "connectA2dpMediaBrowser error: ${e.message}")
        }
    }

    private fun disconnectA2dpMediaBrowser() {
        try {
            a2dpMediaBrowser?.disconnect()
            a2dpMediaBrowser = null
        } catch (_: Exception) {}
    }

    // ==========================================
    // MediaSession 监听与解析逻辑
    // ==========================================

    private val sessionCallback = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            controllers ?: return
            AppLogger.d(TAG, "onActiveSessionsChanged: ${controllers.size} sessions")
            try {
                val currentPkgs = controllers.mapNotNull { c ->
                    try { c.packageName } catch (_: Exception) { null }
                }.toSet()

                // 清理已退出的 session
                activeControllers.keys.toList().forEach { pkg ->
                    if (pkg !in currentPkgs) {
                        activeControllers[pkg]?.let { controller ->
                            activeCallbacks[pkg]?.let { cb ->
                                try { controller.unregisterCallback(cb) } catch (_: Exception) {}
                            }
                        }
                        activeControllers.remove(pkg)
                        activeCallbacks.remove(pkg)
                    }
                }

                // 注册新 session
                controllers.forEach { controller ->
                    val pkg = try { controller.packageName } catch (_: Exception) { return@forEach }
                    if (!activeControllers.containsKey(pkg)) {
                        AppLogger.d(TAG, "New media session: $pkg")
                        val callback = createMediaCallback(controller)
                        try { controller.registerCallback(callback) } catch (_: Exception) {}
                        activeControllers[pkg] = controller
                        activeCallbacks[pkg] = callback
                    }
                }

                extractBestTrack(controllers)
            } catch (e: Exception) {
                Log.w(TAG, "onActiveSessionsChanged error: ${e.message}", e)
            }
        }
    }

    private fun extractBestTrack(controllers: List<MediaController>) {
        val isMusicActive = try { audioManager.isMusicActive } catch (_: Exception) { false }

        if (controllers.isEmpty()) {
            if (!isMusicActive) {
                _currentTrack.value = null
            } else if (isBtA2dpStreaming && lastBluetoothTrack != null) {
                // 无 MediaSession 但底层蓝牙音频流真实推流中，且有历史记录的蓝牙音轨，保持播放
                _currentTrack.value = lastBluetoothTrack?.copy(isPlaying = true)
                lastDetectionSource = "Bluetooth-AudioManagerFallback"
            }
            return
        }

        // 综合评分选出最佳 Controller
        val best = controllers.maxByOrNull { c -> scoreController(c) } ?: controllers.first()
        val bestState = best.playbackState
        val pkg = try { best.packageName } catch (_: Exception) { "unknown" }
        val isBt = pkg.contains("bluetooth")
        val isBtExplicitPaused = isBt && (bestState?.state == PlaybackState.STATE_PAUSED || bestState?.state == 3)

        val isBestPlaying = if (isBt) {
            !isBtExplicitPaused && (isBtA2dpStreaming || bestState?.state == PlaybackState.STATE_PLAYING || bestState?.state == 2)
        } else {
            bestState != null && (bestState.state == PlaybackState.STATE_PLAYING || bestState.state == PlaybackState.STATE_BUFFERING)
        }

        val isBestPaused = if (isBt) {
            isBtExplicitPaused || (!isBtA2dpStreaming && (bestState?.state == PlaybackState.STATE_PAUSED || bestState?.state == 3))
        } else {
            bestState?.state == PlaybackState.STATE_PAUSED
        }

        if (isBestPlaying || isBestPaused) {
            best.metadata?.let { metadata ->
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                if (!title.isNullOrBlank()) {
                    val appName = getAppDisplayName(pkg)
                    val track = buildTrack(best, metadata, bestState)
                    updateCurrentTrack(track, "MediaSession($appName)")
                    return
                }
            }
        }

        // 若选出的控制器无 metadata 但当前音源仍在播放，保持当前状态避免抖动清空
        val current = _currentTrack.value
        if (current != null && current.isPlaying) {
            return
        }

        // 蓝牙音轨兜底：仅当蓝牙音频流确实在推流发声时才兜底恢复
        if (isBtA2dpStreaming && lastBluetoothTrack != null) {
            updateCurrentTrack(lastBluetoothTrack?.copy(isPlaying = true), "Bluetooth-Broadcast")
            return
        }

        _currentTrack.value = null
    }

    private fun buildTrack(
        controller: MediaController,
        metadata: MediaMetadata,
        state: PlaybackState?
    ): CurrentTrack {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val rawPos = state?.position?.takeIf { it != PlaybackState.PLAYBACK_POSITION_UNKNOWN } ?: 0L
        val current = _currentTrack.value
        val position = if (rawPos > 0L) {
            rawPos
        } else if (current != null && current.title == title && current.position > 0L) {
            current.position
        } else {
            rawPos.coerceAtLeast(0L)
        }

        val pkg = try { controller.packageName } catch (_: Exception) { "" }
        val isBt = pkg.contains("bluetooth")
        val isBtExplicitPaused = isBt && (state?.state == PlaybackState.STATE_PAUSED || state?.state == 3)
        val isPlaying = if (isBt) {
            !isBtExplicitPaused && (isBtA2dpStreaming || state?.state == PlaybackState.STATE_PLAYING || state?.state == 2)
        } else {
            state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        }
        val source = if (isBt) MusicSource.BLUETOOTH else MusicSource.MEDIA_SESSION

        val (resolvedPos, resolvedPlaying) = resolveDynamicStateAndPos(
            title = title,
            artist = artist,
            rawPos = position,
            rawIsPlaying = isPlaying,
            source = source
        )

        val track = CurrentTrack(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            position = resolvedPos,
            source = source,
            packageName = pkg,
            isPlaying = resolvedPlaying,
            mediaId = mediaId
        )
        if (isBt) {
            lastBluetoothTrack = track
        }
        return track
    }

    /**
     * metadata 更新回调
     */
    private fun onMetadataFrom(controller: MediaController, metadata: MediaMetadata) {
        try {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return

            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val pkg = try { controller.packageName } catch (_: Exception) { "unknown" }
            val state = controller.playbackState
            val isBt = pkg.contains("bluetooth")
            val isBtExplicitPaused = isBt && (state?.state == PlaybackState.STATE_PAUSED || state?.state == 3)

            val isPlaying = if (isBt) {
                !isBtExplicitPaused && (isBtA2dpStreaming || state?.state == PlaybackState.STATE_PLAYING || state?.state == 2)
            } else {
                (state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING)
            }

            val rawPos = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
            val current = _currentTrack.value
            val pos = if (rawPos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && rawPos > 0L) {
                rawPos
            } else if (current != null && current.title == title && current.position > 0L) {
                current.position
            } else {
                0L
            }

            val source = if (isBt) MusicSource.BLUETOOTH else MusicSource.MEDIA_SESSION
            val (resolvedPos, resolvedPlaying) = resolveDynamicStateAndPos(
                title = title,
                artist = artist,
                rawPos = pos,
                rawIsPlaying = isPlaying,
                source = source
            )

            val track = CurrentTrack(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                position = resolvedPos,
                source = source,
                packageName = pkg,
                isPlaying = resolvedPlaying,
                mediaId = mediaId
            )
            val appName = getAppDisplayName(pkg)
            updateCurrentTrack(track, "MediaSession($appName)")
        } catch (e: Exception) {
            Log.w(TAG, "onMetadataFrom error: ${e.message}", e)
        }
    }

    private fun createMediaCallback(controller: MediaController): MediaController.Callback {
        return object : MediaController.Callback() {
            override fun onSessionDestroyed() {
                try {
                    val pkg = try { controller.packageName } catch (_: Exception) { "" }
                    if (pkg.isNotBlank() && _currentTrack.value?.packageName == pkg) {
                        _currentTrack.value = null
                    }
                } catch (_: Exception) {}
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                try {
                    metadata ?: return
                    val pkg = try { controller.packageName } catch (_: Exception) { "" }
                    val current = _currentTrack.value

                    // 多播放器防抢占锁：如果当前已有正在播放的音源，且来自于另一个包名
                    if (current != null && current.isPlaying && current.packageName != pkg) {
                        val currentCtrl = activeControllers[current.packageName]
                        val currentScore = if (currentCtrl != null) scoreController(currentCtrl) else 0L
                        val incomingScore = scoreController(controller)
                        if (incomingScore <= currentScore) {
                            return
                        }
                    }

                    onMetadataFrom(controller, metadata)
                } catch (e: Exception) {
                    Log.w(TAG, "onMetadataChanged error: ${e.message}", e)
                }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                try {
                    state ?: return
                    val stateCode = state.state
                    val pkg = try { controller.packageName } catch (_: Exception) { "" }
                    val isBt = pkg.contains("bluetooth")
                    val current = _currentTrack.value

                    val isBtExplicitPaused = isBt && (stateCode == PlaybackState.STATE_PAUSED || stateCode == 3)
                    if (isBtExplicitPaused) {
                        isBtA2dpStreaming = false
                    } else if (isBt && (stateCode == PlaybackState.STATE_PLAYING || stateCode == 2)) {
                        isBtA2dpStreaming = true
                    }

                    // 如果明确处于 STOPPED / NONE / ERROR 状态
                    if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE || stateCode == PlaybackState.STATE_ERROR) {
                        // 蓝牙特性：切歌时可能短暂下发 NONE/STOPPED，但若底层蓝牙音频流仍在发声推流，暂不彻底清空
                        if (isBt && isBtA2dpStreaming) {
                            return
                        }
                        if (current != null && current.packageName == pkg) {
                            val otherPlaying = activeControllers.values.firstOrNull { c ->
                                c.packageName != pkg && (c.playbackState?.state == PlaybackState.STATE_PLAYING || c.playbackState?.state == PlaybackState.STATE_BUFFERING)
                            }
                            if (otherPlaying != null) {
                                extractBestTrack(activeControllers.values.toList())
                            } else {
                                _currentTrack.value = null
                            }
                        }
                        return
                    }

                    val isNowPlaying = if (isBt) {
                        if (isBtExplicitPaused) {
                            false
                        } else if (stateCode == PlaybackState.STATE_PLAYING || stateCode == 2 || isBtA2dpStreaming) {
                            true
                        } else if (current != null && current.source == MusicSource.BLUETOOTH && current.isPlaying) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            (now - lastPosAdvanceTime < 2500L)
                        } else {
                            false
                        }
                    } else {
                        (stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING)
                    }

                    // 多播放器防抢占锁：当已有音源正在播放且来自另一个包名
                    if (current != null && current.isPlaying && current.packageName != pkg) {
                        if (!isNowPlaying) return
                        val currentCtrl = activeControllers[current.packageName]
                        val currentScore = if (currentCtrl != null) scoreController(currentCtrl) else 0L
                        val incomingScore = scoreController(controller)
                        if (incomingScore <= currentScore) {
                            return
                        }
                    }

                    val rawPosition = state.position
                    val validPosition = if (rawPosition != PlaybackState.PLAYBACK_POSITION_UNKNOWN) rawPosition else 0L

                    if (current != null && current.packageName == pkg) {
                        val targetPos = if (validPosition > 0L) {
                            validPosition
                        } else if (current.position > 2000L) {
                            current.position
                        } else {
                            validPosition
                        }
                        val (resolvedPos, resolvedPlaying) = resolveDynamicStateAndPos(
                            title = current.title,
                            artist = current.artist,
                            rawPos = targetPos,
                            rawIsPlaying = isNowPlaying,
                            source = current.source
                        )
                        if (!resolvedPlaying && isBt) {
                            isBtA2dpStreaming = false
                        }
                        val updated = current.copy(
                            position = resolvedPos,
                            isPlaying = resolvedPlaying
                        )
                        updateCurrentTrack(updated, "PlaybackState(${getAppDisplayName(pkg)})")
                    } else if (isNowPlaying) {
                        controller.metadata?.let { md ->
                            onMetadataFrom(controller, md)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onPlaybackStateChanged error: ${e.message}", e)
                }
            }
        }
    }

    fun onNotificationRemoved(pkg: String) {
        val current = _currentTrack.value
        if (current != null && current.packageName == pkg && current.source == MusicSource.NOTIFICATION) {
            _currentTrack.value = null
        }
    }

    fun processNotification(sbn: StatusBarNotification) {
        try {
            processNotificationInternal(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "processNotification error: ${e.message}", e)
        }
    }

    private fun processNotificationInternal(sbn: StatusBarNotification) {
        // 核心仲裁法则：如果当前已有真实在播或具备有效时长/进度的音源 (MediaSession 或 Bluetooth)，通知绝对禁止抢占！
        val current = _currentTrack.value
        if (current != null && (current.isPlaying || current.position > 0 || current.duration > 0) &&
            (current.source == MusicSource.MEDIA_SESSION || current.source == MusicSource.BLUETOOTH)) {
            return
        }

        if (!isMusicNotification(sbn)) return

        val extras = sbn.notification.extras ?: return
        val title = findNotificationTitle(sbn) ?: return
        val artist = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getString(Notification.EXTRA_SUB_TEXT)
            ?: extras.getString(Notification.EXTRA_SUMMARY_TEXT)
            ?: ""

        // 拦截无效文本与前台保活通知
        if (isInvalidPlaceholderTrack(title, artist)) {
            AppLogger.d(TAG, "Notification from ${sbn.packageName} rejected as placeholder: title=$title, artist=$artist")
            return
        }

        val track = CurrentTrack(
            title = title,
            artist = artist,
            source = MusicSource.NOTIFICATION,
            packageName = sbn.packageName,
            isPlaying = true
        )
        val appName = getAppDisplayName(sbn.packageName)
        updateCurrentTrack(track, "Notification($appName)")
    }

    private fun findNotificationTitle(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras ?: return null
        extras.getString(Notification.EXTRA_TITLE)?.let { if (it.isNotBlank()) return it }
        sbn.notification.tickerText?.toString()?.let { if (it.isNotBlank()) return it }
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { if (it.isNotBlank()) return it }
        for (key in extras.keySet()) {
            if (key.lowercase().contains("title") || key.lowercase().contains("track")) {
                extras.getString(key)?.let { if (it.isNotBlank()) return it }
            }
        }
        return null
    }

    private fun isMusicNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        if (isBlacklistedPackage(pkg)) {
            return false
        }

        val n = sbn.notification
        val extras = n.extras ?: return false

        // 必须具备媒体通知特征 (MediaSession Token 或 MediaStyle 或 CATEGORY_TRANSPORT)
        val hasMediaToken = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val isTransport = n.category == Notification.CATEGORY_TRANSPORT
        val hasMediaPlayback = extras.getBoolean("android.media.playback", false)
        val hasAlbumArt = extras.containsKey("android.media.metadata.ALBUM_ART_URI")

        val isMediaNotification = hasMediaToken || isTransport || hasMediaPlayback || hasAlbumArt
        if (!isMediaNotification) {
            return false
        }

        // 知名音乐 App 白名单校验 (如果具有媒体特征)
        val isKnownMusicApp = (
            pkg.contains("netease") || pkg.contains("cloudmusic") ||
            pkg.contains("qqmusic") || pkg.contains("tencent") ||
            pkg.contains("kugou") || pkg.contains("kuwo") ||
            pkg.contains("spotify") || pkg.contains("apple.android.music") ||
            pkg.contains("luna.music") || pkg.contains("qishui") ||
            pkg.contains("yinyue") || pkg == "com.chinatsp.music"
        )
        return isKnownMusicApp || hasMediaToken
    }

    private fun scanAllSessions() {
        try {
            val controllers = mediaSessionManager.getActiveSessions(
                ComponentName(context, NotificationListenerService::class.java)
            )
            if (controllers.isNotEmpty()) {
                extractBestTrack(controllers)
                if (_currentTrack.value == null) {
                    lastRefreshInfo = "轮询: ${controllers.size} 个session 但无有效 metadata"
                } else {
                    lastRefreshInfo = ""
                }
            } else {
                val musicActive = audioManager.isMusicActive
                if (musicActive) {
                    // 若 AudioManager 活跃且蓝牙 A2DP 确实在推流发声，且有已缓存的蓝牙音轨，主动恢复播放
                    if (_currentTrack.value == null && isBtA2dpStreaming && lastBluetoothTrack != null) {
                        _currentTrack.value = lastBluetoothTrack?.copy(isPlaying = true)
                        lastDetectionSource = "Bluetooth-AudioManagerFallback"
                    }
                    AppLogger.d(TAG, "AudioManager.isMusicActive=true, but no active MediaSessions — 可能是蓝牙播放或系统播放器")
                    lastRefreshInfo = "轮询: AudioManager 检测到音乐播放中"
                } else {
                    lastRefreshInfo = "轮询: 无活跃 MediaSession, AudioManager 也未检测到音乐播放"
                }
            }
        } catch (e: SecurityException) {
            AppLogger.d(TAG, "scanAllSessions SecurityException (notification listener not enabled?)")
            hasMediaSessionError = true
            val musicActive = audioManager.isMusicActive
            if (musicActive) {
                if (_currentTrack.value == null && isBtA2dpStreaming && lastBluetoothTrack != null) {
                    _currentTrack.value = lastBluetoothTrack?.copy(isPlaying = true)
                    lastDetectionSource = "Bluetooth-AudioManagerFallback"
                }
                lastRefreshInfo = "轮询 SecurityException, 但 AudioManager 检测到音乐播放中"
            } else {
                lastRefreshInfo = "轮询 SecurityException: 通知监听权限可能未开启"
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "scanAllSessions failed: ${e.message}")
            lastRefreshInfo = "轮询异常: ${e.message}"
        }
        // 动态自适应轮询：播放中保持 2s 灵敏追踪，空闲静默期平缓退避至 10s，大幅降低车机 Binder IPC 开销与 CPU 唤醒
        val isPlaying = _currentTrack.value?.isPlaying == true || isBtA2dpStreaming
        val nextInterval = if (isPlaying) POLL_INTERVAL_MS else (POLL_INTERVAL_MS * 5)
        pollingHandler?.postDelayed(pollingRunnable, nextInterval)
    }

    fun startMediaSessionListening() {
        if (pollingHandler != null) {
            AppLogger.d(TAG, "MediaSession listening 处于活跃中，执行即时刷新")
            forceRefresh()
            return
        }
        AppLogger.d(TAG, "=== startMediaSessionListening ===")
        val comp = ComponentName(context, NotificationListenerService::class.java)

        // 0. 启动原车酷狗音乐专属 AIDL 专线监听 (直出歌词与封面，支持U盘/离线)
        kuGouClient.startListening()

        // 1. 注册蓝牙专用广播接收器 (AVRCP / A2DP Sink)
        registerBluetoothReceiver()

        // 2. 尝试唤醒并连接 Android Automotive A2DP MediaBrowser
        connectA2dpMediaBrowser()

        pollingHandler = Handler(Looper.getMainLooper())

        // 3. 注册系统 MediaSession 回调
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionCallback, comp)
            AppLogger.d(TAG, "MediaSession listener registered OK")
            hasMediaSessionError = false
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSession listener registration FAILED (SecurityException). App will rely on polling + Bluetooth broadcasts + notifications.", e)
            hasMediaSessionError = true
            lastRefreshInfo = "MediaSession listener 注册失败(SecurityException)，依赖广播+通知"
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession listener registration FAILED (${e.javaClass.simpleName}). App will rely on polling + Bluetooth broadcasts + notifications.", e)
            hasMediaSessionError = true
            lastRefreshInfo = "MediaSession listener 注册失败(${e.javaClass.simpleName})，依赖广播+通知"
        }

        // 启动时不阻塞主线程，延时 300ms 异步初次扫描
        pollingHandler?.postDelayed({
            forceRefresh()
        }, 300L)
        pollingHandler?.postDelayed({
            if (activeControllers.isEmpty()) {
                forceRefresh()
            }
        }, 800L)
        // 之后进入 2s 轮询
        pollingHandler?.postDelayed(pollingRunnable, 2500L)
        AppLogger.d(TAG, "Polling started, interval=${POLL_INTERVAL_MS}ms")
    }

    fun stopMediaSessionListening() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionCallback)
        } catch (_: Exception) {}

        kuGouClient.stopListening()
        unregisterBluetoothReceiver()
        disconnectA2dpMediaBrowser()

        activeControllers.forEach { (pkg, controller) ->
            activeCallbacks[pkg]?.let { cb ->
                try { controller.unregisterCallback(cb) } catch (_: Exception) {}
            }
        }
        activeControllers.clear()
        activeCallbacks.clear()
        pollingHandler?.removeCallbacksAndMessages(null)
        pollingHandler = null
        hasMediaSessionError = false
    }

    fun refreshPosition() {
        try {
            val current = _currentTrack.value ?: return
            if (!current.isPlaying) return

            // 0. 原车酷狗专线：直接从硬件 IPC 获取高保真物理进度
            if (current.source == MusicSource.KUGOU_NATIVE) {
                val nativePos = kuGouClient.getPosition()
                if (nativePos > 0L && nativePos != current.position) {
                    _currentTrack.value = current.copy(position = nativePos)
                }
                return
            }

            // 1. 优先从当前主 Controller 读取进度
            val activeController = activeControllers[current.packageName]
            val activeState = activeController?.playbackState
            val activePos = activeState?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN

            if (activePos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && activePos > 0L) {
                if (activePos != current.position) {
                    _currentTrack.value = current.copy(position = activePos)
                }
                return
            }

            // 2. 如果主 Controller 未提供有效进度 (>0)，寻找其它正在播放且有有效进度的 Controller (借用真实进度)
            for ((pkg, controller) in activeControllers) {
                if (pkg == current.packageName) continue
                val state = controller.playbackState ?: continue
                val isBt = pkg.contains("bluetooth")
                val isBtExplicitPaused = isBt && (state.state == PlaybackState.STATE_PAUSED || state.state == 3)
                val isPlaying = if (isBt) {
                    !isBtExplicitPaused && (isBtA2dpStreaming || state.state == PlaybackState.STATE_PLAYING || state.state == 2)
                } else {
                    state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
                }
                if (!isPlaying) continue

                val pos = state.position
                if (pos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && pos > 0L) {
                    val meta = controller.metadata
                    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    if (title.isNullOrBlank() || title == current.title || current.title.contains(title) || title.contains(current.title)) {
                        _currentTrack.value = current.copy(position = pos)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshPosition error: ${e.message}", e)
        }
    }

    fun getNextTrackInQueue(): Pair<String, String>? {
        try {
            for (controller in activeControllers.values) {
                try {
                    val state = controller.playbackState ?: continue
                    if (state.state != PlaybackState.STATE_PLAYING) continue

                    val currentTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
                    val queue = controller.queue ?: continue
                    if (queue.isEmpty()) continue

                    for (i in queue.indices) {
                        val item = queue[i]
                        val itemTitle = item.description?.title?.toString() ?: continue
                        if (itemTitle == currentTitle && i + 1 < queue.size) {
                            val next = queue[i + 1]
                            val nextTitle = next.description?.title?.toString() ?: continue
                            val nextArtist = next.description?.subtitle?.toString() ?: ""
                            return Pair(nextTitle, nextArtist)
                        }
                    }
                } catch (_: Exception) { continue }
            }
        } catch (_: Exception) {}
        return null
    }

    private var lastForceRefreshTime = 0L

    fun forceRefresh() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForceRefreshTime < 600L) {
            return
        }
        lastForceRefreshTime = now
        // 尝试刷新原车酷狗状态
        kuGouClient.refreshTrackAsync("forceRefresh")
        try {
            val comp = ComponentName(context, NotificationListenerService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(comp)
            val isMusicActive = try { audioManager.isMusicActive } catch (_: Exception) { false }

            if (controllers.isNullOrEmpty()) {
                if (isMusicActive && isBtA2dpStreaming && lastBluetoothTrack != null) {
                    _currentTrack.value = lastBluetoothTrack?.copy(isPlaying = true)
                    lastDetectionSource = "Bluetooth-AudioManagerFallback"
                    lastRefreshInfo = "forceRefresh: 无MediaSession, 恢复蓝牙音轨"
                } else {
                    AppLogger.d(TAG, "forceRefresh: no active sessions")
                    lastRefreshInfo = "forceRefresh: 无活跃 MediaSession"
                }
                return
            }
            AppLogger.d(TAG, "forceRefresh: ${controllers.size} sessions, pkgs=${controllers.mapNotNull { c -> try { c.packageName } catch (_: Exception) { null } }}")

            // 注册缺失的 session
            controllers.forEach { controller ->
                val pkg = try { controller.packageName } catch (_: Exception) { return@forEach }
                if (!activeControllers.containsKey(pkg)) {
                    AppLogger.d(TAG, "forceRefresh: registering new controller for $pkg")
                    val callback = createMediaCallback(controller)
                    try { controller.registerCallback(callback) } catch (_: Exception) {}
                    activeControllers[pkg] = controller
                    activeCallbacks[pkg] = callback
                }
            }

            // 提取最佳 track
            extractBestTrack(controllers)
            if (_currentTrack.value != null) {
                lastRefreshInfo = ""
            } else {
                lastRefreshInfo = "forceRefresh: ${controllers.size} 个session 但无有效 metadata"
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "forceRefresh SecurityException — 通知监听权限可能未开启", e)
            hasMediaSessionError = true
            lastRefreshInfo = "forceRefresh SecurityException: 通知监听权限可能未开启"
        } catch (e: Exception) {
            Log.w(TAG, "forceRefresh failed: ${e.javaClass.simpleName} ${e.message}", e)
            lastRefreshInfo = "forceRefresh异常: ${e.message}"
        }
    }

    fun getAppDisplayName(packageName: String): String {
        if (packageName.isBlank() || packageName == "unknown") return "未知播放器"
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            val lower = packageName.lowercase()
            when {
                lower.contains("zlink") -> "Zlink (CarPlay)"
                lower.contains("phonemirror") || lower.contains("autokit") -> "AutoKit (CarPlay)"
                lower.contains("speedplay") -> "SpeedPlay (CarPlay)"
                lower.contains("carlife") -> "CarLife"
                lower.contains("gearhead") -> "Android Auto"
                lower.contains("bluetooth") -> "蓝牙音乐"
                lower.contains("cloudmusic") || lower.contains("netease") -> "网易云音乐"
                lower.contains("qqmusic") || lower.contains("tencent") -> "QQ音乐"
                lower.contains("kugou") -> "酷狗音乐"
                lower.contains("kuwo") -> "酷我音乐"
                lower.contains("luna.music") || lower.contains("qishui") -> "汽水音乐"
                lower.contains("spotify") -> "Spotify"
                lower.contains("apple.android.music") -> "Apple Music"
                else -> packageName
            }
        }
    }

    private fun scoreController(controller: MediaController): Long {
        var score = 0L
        val state = try { controller.playbackState } catch (_: Exception) { null }
        val metadata = try { controller.metadata } catch (_: Exception) { null }
        val pkg = try { controller.packageName } catch (_: Exception) { "" }
        val isBt = pkg.contains("bluetooth")
        val stateCode = state?.state ?: PlaybackState.STATE_NONE
        val isBtExplicitPaused = isBt && (stateCode == PlaybackState.STATE_PAUSED || stateCode == 3)

        // 1. 播放状态得分：正在播放 (STATE_PLAYING) 必须具有绝对压倒性优势 (+50000分)，彻底杜绝暂停音源压过播放音源
        val isBtPlaying = isBt && !isBtExplicitPaused && (isBtA2dpStreaming || stateCode == PlaybackState.STATE_PLAYING || stateCode == 2 || isDynamicPlayingConfirmed == true)
        if (isBtPlaying) {
            score += 50_000L
        } else if (isBtExplicitPaused) {
            score += 3_000L
        } else {
            when (stateCode) {
                PlaybackState.STATE_PLAYING -> score += 50_000L
                PlaybackState.STATE_BUFFERING -> score += 25_000L
                PlaybackState.STATE_PAUSED -> score += 3_000L
                else -> {}
            }
        }

        // 2. 元数据完整度得分
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        if (!title.isNullOrBlank()) score += 5_000L
        if (!artist.isNullOrBlank()) score += 2_000L
        if (duration > 0L) score += 3_000L

        // 3. 关键进度得分
        val pos = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        if (pos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && pos > 0L) {
            score += 15_000L
        } else if (isBt && isBtPlaying && !title.isNullOrBlank()) {
            // 蓝牙 AVRCP 特性：歌曲刚开始或部分手机协议下 pos 恒为 0，只要音频活跃且有歌名，赋予同等进度信任分
            score += 12_000L
        }

        // 4. 当前会话粘性得分 (Session Stickiness)：仅当该会话仍在播放时有效
        val current = _currentTrack.value
        if (current != null && current.packageName == pkg && current.isPlaying) {
            score += 8_000L
        }

        // 5. 蓝牙活跃发声强优先权加成：车机底层正在输出蓝牙 A2DP 音频流或进度稳定推进时，加成保证压过已暂停的本地播放器
        if (isBt && (isBtA2dpStreaming || isDynamicPlayingConfirmed == true) && isBtPlaying && !title.isNullOrBlank()) {
            score += 10_000L
        }

        // 6. 常见音乐 App / 互联协议包名优先级加成
        score += musicAppPriority(pkg) * 10L

        // 7. 状态更新时间戳微调
        val updateTime = state?.lastPositionUpdateTime ?: 0L
        score += (updateTime % 100L)

        return score
    }

    private fun musicAppPriority(packageName: String): Int {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("netease") || pkg.contains("cloudmusic") -> 100
            pkg.contains("qqmusic") || pkg.contains("tencent") -> 99
            pkg.contains("luna") || pkg.contains("qishui") -> 98
            pkg.contains("kugou") -> 98
            pkg.contains("kuwo") -> 97
            pkg.contains("bluetooth") -> 96
            pkg.contains("spotify") -> 95
            pkg.contains("apple") || pkg.contains("apple.android.music") -> 95
            pkg.contains("zlink") || pkg.contains("phonemirror") || pkg.contains("autokit") ||
            pkg.contains("speedplay") || pkg.contains("gearhead") || pkg.contains("carlife") -> 90
            pkg.contains("music") || pkg.contains("yinyue") || pkg.contains("audio") -> 80
            pkg.contains("media") -> 50
            else -> 10
        }
    }
}

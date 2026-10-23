package com.carlyrics.recognition

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
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
    }

    private val _currentTrack = MutableStateFlow<CurrentTrack?>(null)
    val currentTrack: StateFlow<CurrentTrack?> = _currentTrack

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 改为 ConcurrentHashMap 解决多线程并发读写问题 */
    private val activeControllers = ConcurrentHashMap<String, MediaController>()
    /** 每个 controller 持有独立 callback 实例，onMetadataChanged 中直接引用对应 controller */
    private val activeCallbacks = ConcurrentHashMap<String, MediaController.Callback>()

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
        if (controllers.isEmpty()) {
            val isMusicActive = try { audioManager.isMusicActive } catch (_: Exception) { false }
            if (!isMusicActive) {
                _currentTrack.value = null
            }
            return
        }

        // 综合评分选出最佳 Controller（优先选出处于播放状态且具备真实推进进度 position > 0 的音源）
        val best = controllers.maxByOrNull { c -> scoreController(c) } ?: controllers.first()
        val bestState = best.playbackState
        val isBestPlaying = bestState != null && (bestState.state == PlaybackState.STATE_PLAYING || bestState.state == PlaybackState.STATE_BUFFERING)

        if (isBestPlaying || bestState?.state == PlaybackState.STATE_PAUSED) {
            best.metadata?.let { metadata ->
                val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                if (!title.isNullOrBlank()) {
                    val pkg = try { best.packageName } catch (_: Exception) { "unknown" }
                    val appName = getAppDisplayName(pkg)
                    _currentTrack.value = buildTrack(best, metadata, bestState)
                    lastDetectionSource = if (isBestPlaying) "MediaSession-Active($appName)" else "MediaSession-Paused($appName)"
                    return
                }
            }
        }

        // 若选出的控制器无 metadata 但当前音源仍在播放，保持当前状态避免抖动清空
        val current = _currentTrack.value
        if (current != null && current.isPlaying) {
            return
        }

        _currentTrack.value = null
    }

    private fun buildTrack(
        controller: MediaController,
        metadata: android.media.MediaMetadata,
        state: PlaybackState?
    ): CurrentTrack {
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val rawPos = state?.position?.takeIf { it != PlaybackState.PLAYBACK_POSITION_UNKNOWN } ?: 0L
        val current = _currentTrack.value
        val position = if (rawPos > 0L) {
            rawPos
        } else if (current != null && current.title == title && current.position > 0L) {
            current.position
        } else {
            rawPos.coerceAtLeast(0L)
        }
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        return CurrentTrack(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            position = position,
            source = MusicSource.MEDIA_SESSION,
            packageName = controller.packageName,
            isPlaying = isPlaying,
            mediaId = mediaId
        )
    }

    /**
     * metadata 更新回调。
     * 切歌时 metadata 先变但播放状态可能暂为 SKIPPING_TO_NEXT/BUFFERING，
     * 必须无条件更新 track，否则封面/歌词会停留在上一曲。
     */
    private fun onMetadataFrom(controller: MediaController, metadata: android.media.MediaMetadata) {
        try {
            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return

            val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
            val mediaId = metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
            val pkg = try { controller.packageName } catch (_: Exception) { "unknown" }
            val state = controller.playbackState
            val isPlaying = (state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING)

            val rawPos = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
            val current = _currentTrack.value
            val pos = if (rawPos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && rawPos > 0L) {
                rawPos
            } else if (current != null && current.title == title && current.position > 0L) {
                current.position
            } else {
                0L
            }

            val track = CurrentTrack(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                position = pos,
                source = MusicSource.MEDIA_SESSION,
                packageName = pkg,
                isPlaying = isPlaying,
                mediaId = mediaId
            )
            _currentTrack.value = track
            val appName = getAppDisplayName(pkg)
            lastDetectionSource = if (isPlaying) "MediaSession-Active($appName)" else "MediaSession-Paused($appName)"
        } catch (e: Exception) {
            Log.w(TAG, "onMetadataFrom error: ${e.message}", e)
        }
    }

    /**
     * 修复点：每个 controller 持有独立 callback，直接持有 controller 引用
     * 消除旧代码中 `it.metadata == metadata` 对象相等匹配的不可靠性，并增加多 App 冲突防护锁
     */
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

            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                try {
                    metadata ?: return
                    val pkg = try { controller.packageName } catch (_: Exception) { "" }
                    val current = _currentTrack.value

                    // 多播放器防抢占锁：如果当前已有正在播放的音源，且来自于另一个包名
                    if (current != null && current.isPlaying && current.packageName != pkg) {
                        val currentCtrl = activeControllers[current.packageName]
                        val currentScore = if (currentCtrl != null) scoreController(currentCtrl) else 0L
                        val incomingScore = scoreController(controller)
                        // 只有新传入的 controller 评分严格高于当前活跃 controller 时才允许抢占
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
                    val current = _currentTrack.value

                    // 如果明确处于 STOPPED / NONE / ERROR 状态
                    if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE || stateCode == PlaybackState.STATE_ERROR) {
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

                    val isNowPlaying = (stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING)

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
                        // 同一应用状态/进度更新：若当前已有推进进度(>2s)而本次突发收到 0，保持现有进度，避免来回拉扯
                        val targetPos = if (validPosition > 0L) {
                            validPosition
                        } else if (current.position > 2000L) {
                            current.position
                        } else {
                            validPosition
                        }
                        _currentTrack.value = current.copy(
                            position = targetPos,
                            isPlaying = isNowPlaying
                        )
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
        if (_currentTrack.value?.packageName == pkg) {
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
        // 如果当前已有正在播放的 MediaSession 实例，通知不应抢占并重置进度
        val current = _currentTrack.value
        if (current != null && current.isPlaying && current.source == MusicSource.MEDIA_SESSION) {
            return
        }

        val isMusic = isMusicNotification(sbn)
        AppLogger.d(TAG, "Notification from ${sbn.packageName}: category=${sbn.notification.category}, isMusic=$isMusic")
        if (!isMusic) return
        val extras = sbn.notification.extras
        val title = findNotificationTitle(sbn)
        if (title == null) {
            AppLogger.d(TAG, "Notification from ${sbn.packageName}: no title found, keys=${extras.keySet()}")
            return
        }
        val artist = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getString(Notification.EXTRA_SUB_TEXT)
            ?: extras.getString(Notification.EXTRA_SUMMARY_TEXT)
            ?: ""
        val track = CurrentTrack(
            title = title,
            artist = artist,
            source = MusicSource.NOTIFICATION,
            packageName = sbn.packageName
        )
        _currentTrack.value = track
        val appName = getAppDisplayName(sbn.packageName)
        lastDetectionSource = "Notification($appName)"
        AppLogger.d(TAG, "Track from notification: $track")
    }

    private fun findNotificationTitle(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
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
        val n = sbn.notification
        val category = n.category
        if (category == Notification.CATEGORY_TRANSPORT || category == Notification.CATEGORY_PROGRESS) {
            return true
        }
        val extras = n.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        val pb = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val pbMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1)
        if (pb >= 0 && pbMax > 0) return true
        // Android 9 兼容：QQ音乐等可能不走标准 category，检查是否有媒体风格的 notification style
        if (n.extras.getBoolean("android.media.playback")) return true
        // 检查是否有专辑封面等媒体特征
        if (extras.containsKey("android.media.metadata.ALBUM_ART_URI")) return true
        // 宽泛匹配：已知音乐/车机媒体 app 包名 + 有 title (含长安欧尚 Z6 原生酷狗 com.chinatsp.music, 喜马拉雅 com.oushang.radio, AAOS com.android.car.media)
        val pkg = sbn.packageName.lowercase()
        if ((pkg.contains("qqmusic") || pkg.contains("music") || pkg.contains("netease") ||
             pkg.contains("kugou") || pkg.contains("kuwo") || pkg.contains("spotify") ||
             pkg.contains("yinyue") || pkg.contains("tencent") || pkg.contains("chinatsp") ||
             pkg.contains("oushang") || pkg.contains("media") || pkg.contains("radio"))
            && findNotificationTitle(sbn) != null) {
            return true
        }
        return false
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
                // 没有 MediaSession，检查 AudioManager 是否检测到音频播放
                val musicActive = audioManager.isMusicActive
                if (musicActive) {
                    AppLogger.d(TAG, "AudioManager.isMusicActive=true, but no active MediaSessions — 可能是蓝牙播放或系统播放器")
                    lastRefreshInfo = "轮询: AudioManager 检测到音乐播放中, 但无 MediaSession metadata (可能为蓝牙A2DP或系统播放器)"
                } else {
                    lastRefreshInfo = "轮询: 无活跃 MediaSession, AudioManager 也未检测到音乐播放"
                }
            }
        } catch (e: SecurityException) {
            AppLogger.d(TAG, "scanAllSessions SecurityException (notification listener not enabled?)")
            hasMediaSessionError = true
            // 即使 getActiveSessions 失败，仍检查 AudioManager
            val musicActive = audioManager.isMusicActive
            if (musicActive) {
                lastRefreshInfo = "轮询 SecurityException, 但 AudioManager 检测到音乐播放中"
            } else {
                lastRefreshInfo = "轮询 SecurityException: 通知监听权限可能未开启"
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "scanAllSessions failed: ${e.message}")
            lastRefreshInfo = "轮询异常: ${e.message}"
        }
        pollingHandler?.postDelayed(pollingRunnable, POLL_INTERVAL_MS)
    }

    fun startMediaSessionListening() {
        AppLogger.d(TAG, "=== startMediaSessionListening ===")
        val comp = ComponentName(context, NotificationListenerService::class.java)

        // 先创建 Handler，确保无论 MediaSession 注册是否成功，轮询都能启动
        pollingHandler = Handler(Looper.getMainLooper())

        // 尝试注册 session 变化监听（Android 9 可能因 SecurityException 失败）
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionCallback, comp)
            AppLogger.d(TAG, "MediaSession listener registered OK")
            hasMediaSessionError = false
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSession listener registration FAILED (SecurityException). App will rely on polling + notifications.", e)
            hasMediaSessionError = true
            lastRefreshInfo = "MediaSession listener 注册失败(SecurityException)，依赖轮询+通知"
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession listener registration FAILED (${e.javaClass.simpleName}). App will rely on polling + notifications.", e)
            hasMediaSessionError = true
            lastRefreshInfo = "MediaSession listener 注册失败(${e.javaClass.simpleName})，依赖轮询+通知"
        }

        // 无论注册是否成功，都启动多时间节点兜底扫描 + 轮询
        forceRefresh()
        pollingHandler?.postDelayed({ forceRefresh() }, 300L)
        pollingHandler?.postDelayed({ forceRefresh() }, 800L)
        pollingHandler?.postDelayed({ forceRefresh() }, 1500L)
        // 之后进入 2s 轮询
        pollingHandler?.postDelayed(pollingRunnable, 2500L)
        AppLogger.d(TAG, "Polling started, interval=${POLL_INTERVAL_MS}ms")
    }

    fun stopMediaSessionListening() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionCallback)
        } catch (_: Exception) {}
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
                val isPlaying = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
                if (!isPlaying) continue

                val pos = state.position
                if (pos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && pos > 0L) {
                    val meta = controller.metadata
                    val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
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

    /**
     * 获取播放队列中下一首歌曲的信息（用于预加载）。
     * 遍历 activeControllers，取第一个正在播放的 session 的 queue。
     * @return Pair(title, artist) 或 null
     */
    fun getNextTrackInQueue(): Pair<String, String>? {
        try {
            for (controller in activeControllers.values) {
                try {
                    val state = controller.playbackState ?: continue
                    if (state.state != PlaybackState.STATE_PLAYING) continue

                    val currentTitle = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: continue

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

    /**
     * 手动/主动触发全量扫描，用于启动时兜底和用户手动刷新。
     * 直接调用 getActiveSessions() 获取当前活跃会话列表，无需等待 onActiveSessionsChanged 回调。
     */
    fun forceRefresh() {
        try {
            val comp = ComponentName(context, NotificationListenerService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(comp)
            if (controllers.isNullOrEmpty()) {
                AppLogger.d(TAG, "forceRefresh: no active sessions")
                lastRefreshInfo = "forceRefresh: 无活跃 MediaSession"
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

    /**
     * 将包名转换为用户易读的应用/播放器名称（含系统与互联协议映射）
     */
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
                lower.contains("bluetooth") -> "蓝牙音频"
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

    /**
     * 控制器综合评分函数：
     * 用于在存在多个 MediaSession（如手机 CarPlay 与车机原生媒体广播同时存在）时，
     * 准确评判谁是真实有效的音频音源，彻底根除两者进度 0 与正常进度的相互拉扯。
     */
    private fun scoreController(controller: MediaController): Long {
        var score = 0L
        val state = try { controller.playbackState } catch (_: Exception) { null }
        val metadata = try { controller.metadata } catch (_: Exception) { null }
        val pkg = try { controller.packageName } catch (_: Exception) { "" }

        // 1. 播放状态得分
        when (state?.state) {
            PlaybackState.STATE_PLAYING -> score += 10_000L
            PlaybackState.STATE_BUFFERING -> score += 5_000L
            PlaybackState.STATE_PAUSED -> score += 1_000L
            else -> {}
        }

        // 2. 元数据完整度得分
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        if (!title.isNullOrBlank()) score += 5_000L
        if (!artist.isNullOrBlank()) score += 2_000L
        if (duration > 0L) score += 3_000L

        // 3. 关键进度得分：具有有效推进的播放进度 (> 0) 是核心鉴别标志
        val pos = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        if (pos != PlaybackState.PLAYBACK_POSITION_UNKNOWN && pos > 0L) {
            score += 15_000L // 极其关键！有正常进度的控制器显著优先于进度恒为 0 的虚假/中转广播
        }

        // 4. 当前会话粘性得分 (Session Stickiness)
        val current = _currentTrack.value
        if (current != null && current.packageName == pkg && current.isPlaying) {
            score += 8_000L // 防抖动防抢占加成：避免在两个评分相近的 Controller 之间频繁切换
        }

        // 5. 常见音乐 App / 互联协议包名优先级加成
        score += musicAppPriority(pkg) * 10L

        // 6. 状态更新时间戳微调 (作为同分平局打破标志，不超过 100 分)
        val updateTime = state?.lastPositionUpdateTime ?: 0L
        score += (updateTime % 100L)

        return score
    }

    private fun musicAppPriority(packageName: String): Int {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("netease") || pkg.contains("cloudmusic") -> 100
            pkg.contains("qqmusic") || pkg.contains("tencent") -> 99
            pkg.contains("kugou") -> 98
            pkg.contains("kuwo") -> 97
            pkg.contains("spotify") -> 95
            pkg.contains("apple") || pkg.contains("apple.android.music") -> 95
            // CarPlay / Android Auto / 手机互联投屏协议应用
            pkg.contains("zlink") || pkg.contains("phonemirror") || pkg.contains("autokit") ||
            pkg.contains("speedplay") || pkg.contains("gearhead") || pkg.contains("carlife") -> 90
            pkg.contains("bluetooth") -> 85
            pkg.contains("music") || pkg.contains("yinyue") || pkg.contains("audio") -> 80
            pkg.contains("media") -> 50
            else -> 10
        }
    }
}

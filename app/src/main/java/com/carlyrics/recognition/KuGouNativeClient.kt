package com.carlyrics.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import com.chinatsp.music.lyrc.Lrc
import com.carlyrics.model.CurrentTrack
import com.carlyrics.model.LyricLine
import com.carlyrics.model.MusicSource
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 欧尚 B561 原厂酷狗音乐 (com.chinatsp.music) 专用 AIDL 原生客户端
 * 直连 KuGouMusicInterfaceService，实现零权限、高保真、毫秒级媒体感知与原生歌词/封面直出
 */
class KuGouNativeClient(
    private val context: Context,
    private val listener: KuGouListener
) {

    interface KuGouListener {
        fun onTrackChanged(track: CurrentTrack)
        fun onProgressChanged(positionMs: Long, durationMs: Long)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    companion object {
        private const val TAG = "KuGouNativeClient"
        const val KUGOU_PACKAGE = "com.chinatsp.music"
        private const val KUGOU_SERVICE_CLASS = "com.chinatsp.music.service.KuGouMusicInterfaceService"
        private const val DESCRIPTOR_MEDIA_CONTROL = "com.chinatsp.music.MediaControlInterface"
        private const val DESCRIPTOR_MEDIA_CHANGE_LISTENER = "com.chinatsp.music.IMediaChangeListener"

        // MediaControlInterface 事务码 (严格对应车机原厂 MediaControlInterface$Stub)
        private const val TRANSACTION_setUserPause = 0x1
        private const val TRANSACTION_playOrPause = 0x2
        private const val TRANSACTION_play = 0x3
        private const val TRANSACTION_pause = 0x4
        private const val TRANSACTION_stop = 0x5
        private const val TRANSACTION_next = 0x6
        private const val TRANSACTION_pre = 0x7
        private const val TRANSACTION_setLoopMode = 0x8
        private const val TRANSACTION_isPlaying = 0x9
        private const val TRANSACTION_playById = 0xa
        private const val TRANSACTION_seekTo = 0xb
        private const val TRANSACTION_getPlayPath = 0xc
        private const val TRANSACTION_getMediaTitle = 0xd
        private const val TRANSACTION_getMediaArtist = 0xe
        private const val TRANSACTION_getMediaAlbum = 0xf
        private const val TRANSACTION_getDuration = 0x10
        private const val TRANSACTION_getPosition = 0x11
        private const val TRANSACTION_getMediaType = 0x12
        private const val TRANSACTION_addMediaChangeListener = 0x1a
        private const val TRANSACTION_removeMediaChangeListener = 0x1b
        private const val TRANSACTION_hasLyrics = 0x1e
        private const val TRANSACTION_getLrcData = 0x22

        // IMediaChangeListener 事务码
        private const val TRANSACTION_onStatusChange = 1
        private const val TRANSACTION_onInfoChange = 2
        private const val TRANSACTION_onMediaListChange = 3
        private const val TRANSACTION_onProgressChange = 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // 单线程串行 IPC 调度器：彻底消除 onInfoChange / onStatusChange / verifyPlayback 并发读写造成的时序倒挂与旧态覆盖
    private val ipcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KuGouIpcThread").apply { isDaemon = true }
    }
    private val bgScope = CoroutineScope(ipcExecutor.asCoroutineDispatcher() + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(TAG, "KuGouNativeClient 后台协程异常: ${e.message}", e)
    })

    // 状态序列号与上一首原生歌词指纹，用于识别切歌瞬间酷狗内部 LRC 缓存尚未刷新的脏歌词
    private val trackRefreshSeq = AtomicLong(0L)
    private var currentSessionTrackKey: String = ""
    private var lastValidCurrentLrcSignature: String = ""
    private var previousTrackLrcSignature: String = ""
    private val staleLrcRetryRunnable1 = Runnable { refreshTrackAsync("staleLrcRetry-350ms") }
    private val staleLrcRetryRunnable2 = Runnable { refreshTrackAsync("staleLrcRetry-850ms") }

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    var isBound = false
        private set

    @Volatile
    private var isRegisteredListener = false

    private val reconnectRunnable = Runnable { connectService() }

    private val deathRecipient = IBinder.DeathRecipient {
        AppLogger.w(TAG, "酷狗音乐服务已终止 (Binder Died)，注销状态、通知暂停并准备重连...")
        remoteBinder = null
        isBound = false
        isRegisteredListener = false
        mainHandler.post { listener.onPlaybackStateChanged(false) }
        mainHandler.postDelayed(reconnectRunnable, 3000L)
    }

    /**
     * 原厂 IMediaChangeListener 的服务端回调 Binder Stub
     */
    private val changeListenerStub = object : Binder() {
        init {
            attachInterface(null, DESCRIPTOR_MEDIA_CHANGE_LISTENER)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                TRANSACTION_onStatusChange -> {
                    data.enforceInterface(DESCRIPTOR_MEDIA_CHANGE_LISTENER)
                    handleStatusChange()
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_onInfoChange -> {
                    data.enforceInterface(DESCRIPTOR_MEDIA_CHANGE_LISTENER)
                    handleInfoChange()
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_onMediaListChange -> {
                    data.enforceInterface(DESCRIPTOR_MEDIA_CHANGE_LISTENER)
                    handleInfoChange()
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_onProgressChange -> {
                    data.enforceInterface(DESCRIPTOR_MEDIA_CHANGE_LISTENER)
                    val cur = data.readLong()
                    val total = data.readLong()
                    handleProgressChange(cur, total)
                    reply?.writeNoException()
                    true
                }
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_MEDIA_CHANGE_LISTENER)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            AppLogger.d(TAG, "KuGouMusicInterfaceService 连接成功: $name")
            remoteBinder = binder
            isBound = true
            try {
                binder?.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                AppLogger.w(TAG, "linkToDeath failed: ${e.message}")
            }
            registerMediaChangeListenerInternal()
            // 连接建立时立即在后台线程刷新一次完整曲目状态
            refreshTrackAsync("onServiceConnected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.w(TAG, "KuGouMusicInterfaceService 连接断开: $name")
            try {
                remoteBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
            remoteBinder = null
            isBound = false
            isRegisteredListener = false
            mainHandler.post { listener.onPlaybackStateChanged(false) }
            mainHandler.postDelayed(reconnectRunnable, 2000L)
        }
    }

    fun startListening() {
        AppLogger.d(TAG, "启动原车酷狗 AIDL 监听客户端")
        connectService()
    }

    fun stopListening() {
        AppLogger.d(TAG, "停止原车酷狗 AIDL 监听客户端")
        mainHandler.removeCallbacks(reconnectRunnable)
        cancelStaleLrcRetries()
        unregisterMediaChangeListenerInternal()
        if (isBound) {
            try {
                remoteBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                AppLogger.w(TAG, "unbindService exception: ${e.message}")
            }
            isBound = false
            remoteBinder = null
        }
    }

    private fun isKuGouInstalled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(KUGOU_PACKAGE, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(KUGOU_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun connectService() {
        if (isBound && remoteBinder?.isBinderAlive == true) return
        if (!isKuGouInstalled()) {
            AppLogger.d(TAG, "宿主未安装原车酷狗($KUGOU_PACKAGE)，跳过常驻绑定轮询")
            return
        }
        try {
            val intent = Intent().apply {
                component = ComponentName(KUGOU_PACKAGE, KUGOU_SERVICE_CLASS)
            }
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            AppLogger.d(TAG, "发起绑定原车酷狗服务结果: $ok")
            if (!ok) {
                mainHandler.postDelayed(reconnectRunnable, 10000L)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "绑定原车酷狗服务异常: ${e.message}", e)
            mainHandler.postDelayed(reconnectRunnable, 10000L)
        }
    }

    private fun registerMediaChangeListenerInternal() {
        val binder = remoteBinder ?: return
        if (isRegisteredListener) return
        bgScope.launch {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
                data.writeStrongBinder(changeListenerStub)
                val ok = binder.transact(TRANSACTION_addMediaChangeListener, data, reply, 0)
                if (ok) {
                    reply.readException()
                    isRegisteredListener = true
                    AppLogger.d(TAG, "注册原车酷狗 IMediaChangeListener 成功")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "注册原车酷狗 IMediaChangeListener 失败: ${e.message}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun unregisterMediaChangeListenerInternal() {
        val binder = remoteBinder ?: return
        if (!isRegisteredListener) return
        bgScope.launch {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
                data.writeStrongBinder(changeListenerStub)
                binder.transact(TRANSACTION_removeMediaChangeListener, data, reply, 0)
                reply.readException()
                isRegisteredListener = false
                AppLogger.d(TAG, "注销原车酷狗 IMediaChangeListener")
            } catch (e: Exception) {
                AppLogger.w(TAG, "注销原车酷狗 IMediaChangeListener 异常: ${e.message}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun cancelStaleLrcRetries() {
        mainHandler.removeCallbacks(staleLrcRetryRunnable1)
        mainHandler.removeCallbacks(staleLrcRetryRunnable2)
    }

    private fun scheduleStaleLrcRetries() {
        cancelStaleLrcRetries()
        mainHandler.postDelayed(staleLrcRetryRunnable1, 350L)
        mainHandler.postDelayed(staleLrcRetryRunnable2, 850L)
    }

    private fun computeLrcSignature(lines: List<LyricLine>?): String {
        if (lines.isNullOrEmpty()) return ""
        val first = lines.first()
        val mid = lines[lines.size / 2]
        val last = lines.last()
        return "${lines.size}|${first.timestamp}:${first.text}|${mid.timestamp}:${mid.text}|${last.timestamp}:${last.text}"
    }

    private fun handleStatusChange() {
        val seq = trackRefreshSeq.incrementAndGet()
        bgScope.launch {
            val playing = isPlayingInternal()
            AppLogger.d(TAG, "酷狗回调 onStatusChange: isPlaying=$playing")
            if (seq == trackRefreshSeq.get()) {
                mainHandler.post {
                    listener.onPlaybackStateChanged(playing)
                }
            }
            refreshTrackSync("onStatusChange", seq)
        }
    }

    private fun handleInfoChange() {
        AppLogger.d(TAG, "酷狗回调 onInfoChange (切歌/元数据变动)")
        refreshTrackAsync("onInfoChange")
    }

    private fun handleProgressChange(cur: Long, total: Long) {
        mainHandler.post {
            listener.onProgressChanged(cur, total)
        }
    }

    /**
     * 异步提取完整歌曲元数据、原生歌词与原生封面（严格在单线程 IPC 调度器中串行执行，杜绝并发倒挂）
     */
    fun refreshTrackAsync(trigger: String) {
        val binder = remoteBinder
        if (binder == null || !binder.isBinderAlive) {
            return
        }
        val seq = trackRefreshSeq.incrementAndGet()
        bgScope.launch {
            refreshTrackSync(trigger, seq)
        }
    }

    private fun refreshTrackSync(trigger: String, seq: Long) {
        if (seq != trackRefreshSeq.get()) {
            // 已有更新的刷新任务排队，跳过过期中间帧（避免旧帧覆盖新状态）
            return
        }
        try {
            val title = getMediaTitleInternal().trim()
            val artist = getMediaArtistInternal().trim()
            if (title.isBlank()) {
                return
            }
            val trackKey = "$title|$artist"
            val isTrackSwitch = trackKey != currentSessionTrackKey
            if (isTrackSwitch) {
                if (lastValidCurrentLrcSignature.isNotBlank()) {
                    previousTrackLrcSignature = lastValidCurrentLrcSignature
                }
                currentSessionTrackKey = trackKey
                lastValidCurrentLrcSignature = ""
                cancelStaleLrcRetries()
            }

            val duration = getDurationInternal()
            val position = getPositionInternal()
            val mediaType = getMediaTypeInternal()

            // 1. 原生歌词获取 (支持在线与 U 盘音乐直读)
            val hasLrc = hasLyricsInternal()
            val rawLines: List<LyricLine>? = if (hasLrc) {
                val rawLrcList = getLrcDataInternal()
                if (!rawLrcList.isNullOrEmpty()) {
                    rawLrcList.filter { !it.text.isNullOrBlank() }.map {
                        LyricLine(timestamp = it.time, text = it.text?.trim() ?: "")
                    }
                } else null
            } else null

            // 防御原车酷狗切歌异步竞争：刚切到新歌时 getLrcData() 可能仍返回上一首歌的旧歌词缓存
            val sig = computeLrcSignature(rawLines)
            val nativeLines: List<LyricLine>? = if (
                sig.isNotBlank() &&
                previousTrackLrcSignature.isNotBlank() &&
                sig == previousTrackLrcSignature
            ) {
                AppLogger.w(TAG, "[$trigger] ⚠️ 检测到原车酷狗切歌歌词缓存尚未刷新 ($title 仍返回上一首的 ${rawLines?.size ?: 0} 行旧歌词)，丢弃旧词并预约延迟重读")
                mainHandler.post { scheduleStaleLrcRetries() }
                null
            } else {
                if (sig.isNotBlank()) {
                    lastValidCurrentLrcSignature = sig
                    cancelStaleLrcRetries()
                }
                rawLines
            }

            // 2. 原生封面提取与本地持久缓存 (支持 U 盘 MP3/FLAC ID3 APIC 直读；命中磁盘缓存时跳过跨进程 512x512 Binder 位图拷贝与 JPEG 压缩)
            val nativeCoverUrl: String? = LyricsCacheManager.getCachedNativeCoverUri(title, artist)
                ?: getMediaAlbumInternal(512, 512)?.let { albumBitmap ->
                    LyricsCacheManager.saveNativeCover(title, artist, albumBitmap)
                }

            // 3. 在耗时 IPC 提取结束后重新复核一次最新的在播状态与曲名，彻底杜绝旧状态覆写新播放态
            val latestTitle = getMediaTitleInternal().trim()
            if (latestTitle.isNotBlank() && latestTitle != title) {
                AppLogger.d(TAG, "[$trigger] 提取期间检测到已再次切歌 ($title -> $latestTitle)，放弃过期快照")
                return
            }
            val latestIsPlaying = isPlayingInternal()

            val track = CurrentTrack(
                title = title,
                artist = artist,
                album = mediaType ?: "",
                position = position,
                duration = duration,
                source = MusicSource.KUGOU_NATIVE,
                packageName = KUGOU_PACKAGE,
                isPlaying = latestIsPlaying,
                nativeLyrics = nativeLines,
                nativeCoverUrl = nativeCoverUrl
            )

            AppLogger.d(TAG, "[$trigger] 原车酷狗提取成功: $title - $artist, hasLrc=${!nativeLines.isNullOrEmpty()}(${nativeLines?.size ?: 0}行), hasCover=${nativeCoverUrl != null}, isPlaying=$latestIsPlaying")

            mainHandler.post {
                listener.onTrackChanged(track)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "[$trigger] 提取原车酷狗音轨信息失败: ${e.message}")
        }
    }

    // ==========================================
    // IPC 底层事务调用方法
    // ==========================================

    fun isPlaying(): Boolean = isPlayingInternal()
    fun getPosition(): Long = getPositionInternal()
    fun getDuration(): Long = getDurationInternal()

    /**
     * 双向异步校验酷狗真实在播状态、切歌或迟到歌词，兼顾停播收敛与换播放器首曲/复播自愈
     */
    fun verifyPlaybackAsync(
        expectedPlaying: Boolean = true,
        expectedTitle: String = "",
        needsLyrics: Boolean = false
    ) {
        val binder = remoteBinder
        if (binder == null || !binder.isBinderAlive) {
            if (expectedPlaying) {
                mainHandler.post { listener.onPlaybackStateChanged(false) }
            }
            return
        }
        bgScope.launch {
            val actualPlaying = isPlayingInternal()
            val actualTitle = getMediaTitleInternal().trim()
            if (expectedPlaying && !actualPlaying) {
                mainHandler.post { listener.onPlaybackStateChanged(false) }
            } else if (!expectedPlaying && actualPlaying && actualTitle.isNotBlank()) {
                AppLogger.i(TAG, "🔄 [酷狗自愈校验] 轮询检测到酷狗已实际在播 ($actualTitle)，主动唤醒提取完整音轨与歌词")
                refreshTrackSync("pollStateRecover", trackRefreshSeq.incrementAndGet())
            } else if (actualPlaying && actualTitle.isNotBlank() &&
                ((expectedTitle.isNotBlank() && actualTitle != expectedTitle) || needsLyrics)
            ) {
                refreshTrackSync("pollTrackOrLrcRecover", trackRefreshSeq.incrementAndGet())
            }
        }
    }

    private fun isPlayingInternal(): Boolean {
        val binder = remoteBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_isPlaying, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readInt() != 0
            } else false
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getMediaTitleInternal(): String {
        val binder = remoteBinder ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getMediaTitle, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readString() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getMediaArtistInternal(): String {
        val binder = remoteBinder ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getMediaArtist, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readString() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getDurationInternal(): Long {
        val binder = remoteBinder ?: return 0L
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getDuration, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readLong()
            } else 0L
        } catch (_: Exception) {
            0L
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getPositionInternal(): Long {
        val binder = remoteBinder ?: return 0L
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getPosition, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readLong()
            } else 0L
        } catch (_: Exception) {
            0L
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getMediaTypeInternal(): String? {
        val binder = remoteBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getMediaType, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readString()
            } else null
        } catch (_: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun hasLyricsInternal(): Boolean {
        val binder = remoteBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_hasLyrics, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readInt() != 0
            } else false
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getLrcDataInternal(): List<Lrc>? {
        val binder = remoteBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(TRANSACTION_getLrcData, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.createTypedArrayList(Lrc.CREATOR)
            } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "getLrcData 异常: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getMediaAlbumInternal(width: Int, height: Int): Bitmap? {
        val binder = remoteBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            data.writeInt(width)
            data.writeInt(height)
            val ok = binder.transact(TRANSACTION_getMediaAlbum, data, reply, 0)
            if (ok) {
                reply.readException()
                if (reply.readInt() != 0) {
                    Bitmap.CREATOR.createFromParcel(reply)
                } else null
            } else null
        } catch (e: Exception) {
            AppLogger.w(TAG, "getMediaAlbum 异常: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ==========================================
    // 播放控制透传
    // ==========================================

    fun playOrPause(): Boolean = sendBooleanCommand(TRANSACTION_playOrPause)
    fun play(): Boolean = sendBooleanCommand(TRANSACTION_play)
    fun pause(): Boolean = sendBooleanCommand(TRANSACTION_pause)
    fun next(): Boolean = sendBooleanCommand(TRANSACTION_next)
    fun pre(): Boolean = sendBooleanCommand(TRANSACTION_pre)

    fun seekTo(positionMs: Long): Boolean {
        val binder = remoteBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            data.writeLong(positionMs)
            val ok = binder.transact(TRANSACTION_seekTo, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readInt() != 0
            } else false
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun sendBooleanCommand(code: Int): Boolean {
        val binder = remoteBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_MEDIA_CONTROL)
            val ok = binder.transact(code, data, reply, 0)
            if (ok) {
                reply.readException()
                reply.readInt() != 0
            } else false
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

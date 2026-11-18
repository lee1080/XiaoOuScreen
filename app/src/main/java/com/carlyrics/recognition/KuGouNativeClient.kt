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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        AppLogger.e(TAG, "KuGouNativeClient 后台协程异常: ${e.message}", e)
    })

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    var isBound = false
        private set

    @Volatile
    private var isRegisteredListener = false

    private val reconnectRunnable = Runnable { connectService() }

    private val deathRecipient = IBinder.DeathRecipient {
        AppLogger.w(TAG, "酷狗音乐服务已终止 (Binder Died)，注销状态并准备重连...")
        remoteBinder = null
        isBound = false
        isRegisteredListener = false
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
            remoteBinder = null
            isBound = false
            isRegisteredListener = false
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
        unregisterMediaChangeListenerInternal()
        if (isBound) {
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

    private fun handleStatusChange() {
        bgScope.launch {
            val playing = isPlayingInternal()
            AppLogger.d(TAG, "酷狗回调 onStatusChange: isPlaying=$playing")
            mainHandler.post {
                listener.onPlaybackStateChanged(playing)
            }
            refreshTrackAsync("onStatusChange")
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
     * 异步提取完整歌曲元数据、原生歌词与原生封面
     */
    fun refreshTrackAsync(trigger: String) {
        val binder = remoteBinder
        if (binder == null || !binder.isBinderAlive) {
            return
        }
        bgScope.launch {
            try {
                val title = getMediaTitleInternal()
                val artist = getMediaArtistInternal()
                if (title.isBlank()) {
                    return@launch
                }
                val isPlaying = isPlayingInternal()
                val duration = getDurationInternal()
                val position = getPositionInternal()
                val mediaType = getMediaTypeInternal()

                // 1. 原生歌词获取 (支持在线与 U 盘音乐直读)
                val hasLrc = hasLyricsInternal()
                val nativeLines: List<LyricLine>? = if (hasLrc) {
                    val rawLrcList = getLrcDataInternal()
                    if (!rawLrcList.isNullOrEmpty()) {
                        rawLrcList.filter { !it.text.isNullOrBlank() }.map {
                            LyricLine(timestamp = it.time, text = it.text?.trim() ?: "")
                        }
                    } else null
                } else null

                // 2. 原生封面提取与本地持久缓存 (支持 U 盘 MP3/FLAC ID3 APIC 直读)
                val albumBitmap = getMediaAlbumInternal(512, 512)
                val nativeCoverUrl: String? = if (albumBitmap != null) {
                    LyricsCacheManager.saveNativeCover(title, artist, albumBitmap)
                } else null

                val track = CurrentTrack(
                    title = title,
                    artist = artist,
                    album = mediaType ?: "",
                    position = position,
                    duration = duration,
                    source = MusicSource.KUGOU_NATIVE,
                    packageName = KUGOU_PACKAGE,
                    isPlaying = isPlaying,
                    nativeLyrics = nativeLines,
                    nativeCoverUrl = nativeCoverUrl
                )

                AppLogger.d(TAG, "[$trigger] 原车酷狗提取成功: $title - $artist, hasLrc=${!nativeLines.isNullOrEmpty()}(${nativeLines?.size ?: 0}行), hasCover=${nativeCoverUrl != null}, isPlaying=$isPlaying")

                mainHandler.post {
                    listener.onTrackChanged(track)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "[$trigger] 提取原车酷狗音轨信息失败: ${e.message}")
            }
        }
    }

    // ==========================================
    // IPC 底层事务调用方法
    // ==========================================

    fun isPlaying(): Boolean = isPlayingInternal()
    fun getPosition(): Long = getPositionInternal()
    fun getDuration(): Long = getDurationInternal()

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

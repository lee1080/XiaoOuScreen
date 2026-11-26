package com.car.xiaoouscreen

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import com.carlyrics.CarLyricsApplication
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.lyrics.LyricsSourceRunner
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.recognition.NotificationListenerService
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.PresetManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XiaoOuApplication : Application() {

    companion object {
        const val OVERLAY_CHANNEL_ID = "weather_overlay_channel"
        lateinit var instance: XiaoOuApplication
            private set
        lateinit var rhythmEngine: com.car.xiaoouscreen.car.MusicRhythmEngine
            private set
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("XiaoOuApp", "全局协程未捕获异常: ${throwable.message}", throwable)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. 初始化基础轻量配置与异步日志（同步触发和风天气旧版 CE->DE 无损迁移与本地备份自愈）
        PrefsManager.init(this)
        try {
            com.car.xiaoouscreen.data.QWeatherConfigManager(this)
        } catch (_: Exception) {}
        AppLogger.init(this)

        // 0.1 初始化车规级全局未捕获异常处理器
        com.carlyrics.util.CrashHandler.init(this)

        // 0.2 配置 Glide 全局显存池上限 (车规级节流：限制 12MB BitmapPool 与 12MB LruCache，优先 RGB_565 解码)
        try {
            if (!com.bumptech.glide.Glide.isInitialized()) {
                val glideBuilder = com.bumptech.glide.GlideBuilder()
                    .setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(12L * 1024 * 1024))
                    .setBitmapPool(com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(12L * 1024 * 1024))
                    .setDefaultRequestOptions(
                        com.bumptech.glide.request.RequestOptions().format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    )
                com.bumptech.glide.Glide.init(this, glideBuilder)
                AppLogger.i("XiaoOuApp", "已定制注入 Glide 车规级低内存策略 (RGB_565 + 12MB Pool)")
            }
        } catch (e: Exception) {
            AppLogger.w("XiaoOuApp", "配置 Glide 内存上限异常: ${e.message}")
        }

        // 2. 初始化全局音乐识别引擎并注入单例
        val engine = MusicRecognitionEngine(this)
        CarLyricsApplication.instance = this
        CarLyricsApplication.recognitionEngine = engine

        // 2.1 初始化音乐氛围灯律动引擎
        rhythmEngine = com.car.xiaoouscreen.car.MusicRhythmEngine(this).apply {
            masterEnabled = PrefsManager.isAmbientLightSyncEnabled()
            colorSyncEnabled = PrefsManager.isAmbientLightColorSyncEnabled()
            randomColorMode = PrefsManager.isAmbientLightRandomColorEnabled()
            sensitivityPercent = PrefsManager.getAmbientLightSensitivity()
        }

        createNotificationChannels()

        // 3. 启动媒体与通知监听桥接
        engine.startMediaSessionListening()
        NotificationListenerService.onNotificationPosted = { sbn ->
            engine.processNotification(sbn)
        }

        // 3.1 监听音轨变化联动氛围灯 (曲目与封面防抖去重，避免 500ms 进度推进引发主线程重复加载与算力浪费)
        appScope.launch {
            var lastLyricsTrackKey: String? = null
            var lastLyricsSize = -1
            var lastCoverTrackKey: String? = null
            var lastCoverUrl: String? = null
            engine.currentTrack.collect { track ->
                rhythmEngine.onTrackChanged(track)
                val trackKey = if (track != null) "${track.title}|${track.artist}" else null
                val nativeLyrics = track?.nativeLyrics
                if (nativeLyrics != null && nativeLyrics.isNotEmpty()) {
                    if (trackKey != lastLyricsTrackKey || nativeLyrics.size != lastLyricsSize) {
                        lastLyricsTrackKey = trackKey
                        lastLyricsSize = nativeLyrics.size
                        rhythmEngine.onLyricsLoaded(nativeLyrics)
                    }
                } else if (trackKey != lastLyricsTrackKey) {
                    lastLyricsTrackKey = trackKey
                    lastLyricsSize = 0
                }
                val coverUrl = track?.nativeCoverUrl
                if (!coverUrl.isNullOrBlank()) {
                    if (trackKey != lastCoverTrackKey || coverUrl != lastCoverUrl) {
                        lastCoverTrackKey = trackKey
                        lastCoverUrl = coverUrl
                        LyricsCacheManager.loadCoverBitmapScaled(this@XiaoOuApplication, coverUrl, 320) { bmp ->
                            rhythmEngine.onCoverLoaded(bmp)
                        }
                    }
                }
            }
        }

        // 4. 重型 I/O、歌词源同步、通知白名单授权全量放入 IO 线程，彻底解除主线程冷启卡顿
        appScope.launch {
            try {
                PresetManager.init(this@XiaoOuApplication)
                LyricsCacheManager.init(this@XiaoOuApplication)
                LyricsSourceManager.init(this@XiaoOuApplication)
                com.car.xiaoouscreen.util.NotificationPermissionHelper.tryAutoGrantNotificationListener(this@XiaoOuApplication)
            } catch (e: Exception) {
                AppLogger.w("XiaoOuApp", "后台异步初始化部分组件异常: ${e.message}")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.d("XiaoOuApp", "系统触发 onTrimMemory: level=$level")
        com.car.xiaoouscreen.util.SharedHmiAssetManager.trimMemory(level)
        // 注意常量值大小关系：TRIM_MEMORY_RUNNING_CRITICAL(15) < TRIM_MEMORY_UI_HIDDEN(20) < TRIM_MEMORY_MODERATE(60)
        // 仅在真正处于前台运行时紧急内存告急(RUNNING_CRITICAL=15)或后台中度/完全紧张(>=MODERATE=60)时清空全量缓存，避免 UI_HIDDEN(20) 误杀导致冷热图重载卡顿
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            com.car.xiaoouscreen.util.SharedHmiAssetManager.clearAll()
            try {
                com.bumptech.glide.Glide.get(this).clearMemory()
            } catch (_: Exception) {}
            try {
                rhythmEngine.clearCoverCache()
                com.carlyrics.recognition.NotificationLyricsSniffer.clearPackageContextCache()
                LyricsSourceRunner.cancelAll()
                LyricsSourceRunner.clearScriptCache()
                com.carlyrics.skin.KaraokeHelper.clearWordSpanCache()
                com.carlyrics.lyrics.LyricsApiManager.clearPreloadCache()
                com.carlyrics.util.LyricsCacheManager.clearMemoryCache()
            } catch (_: Exception) {}
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            try {
                com.bumptech.glide.Glide.get(this).trimMemory(level)
            } catch (_: Exception) {}
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLogger.w("XiaoOuApp", "系统触发 onLowMemory，紧急释放全部共享位图、歌词请求与图片缓存")
        com.car.xiaoouscreen.util.SharedHmiAssetManager.clearAll()
        try {
            com.bumptech.glide.Glide.get(this).clearMemory()
        } catch (_: Exception) {}
        try {
            rhythmEngine.clearCoverCache()
            com.carlyrics.recognition.NotificationLyricsSniffer.clearPackageContextCache()
            LyricsSourceRunner.cancelAll()
            LyricsSourceRunner.clearScriptCache()
            com.carlyrics.skin.KaraokeHelper.clearWordSpanCache()
            com.carlyrics.lyrics.LyricsApiManager.clearPreloadCache()
            com.carlyrics.util.LyricsCacheManager.clearMemoryCache()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val overlayChannel = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "小欧屏悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持小欧屏天气与车载歌词后台运行"
                setShowBadge(false)
            }
            manager.createNotificationChannel(overlayChannel)
        }
    }
}

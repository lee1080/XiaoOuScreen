package com.car.xiaoouscreen

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.carlyrics.CarLyricsApplication
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.recognition.NotificationListenerService
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.PresetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XiaoOuApplication : Application() {

    companion object {
        const val OVERLAY_CHANNEL_ID = "weather_overlay_channel"
        lateinit var instance: XiaoOuApplication
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. 初始化车规级全局未捕获异常处理器
        com.carlyrics.util.CrashHandler.init(this)

        // 1. 初始化基础轻量配置与异步日志
        PrefsManager.init(this)
        AppLogger.init(this)

        // 2. 初始化全局音乐识别引擎并注入单例
        val engine = MusicRecognitionEngine(this)
        CarLyricsApplication.instance = this
        CarLyricsApplication.recognitionEngine = engine

        createNotificationChannels()

        // 3. 启动媒体与通知监听桥接
        engine.startMediaSessionListening()
        NotificationListenerService.onNotificationPosted = { sbn ->
            engine.processNotification(sbn)
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

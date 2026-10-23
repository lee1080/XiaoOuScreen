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

class XiaoOuApplication : Application() {

    companion object {
        const val OVERLAY_CHANNEL_ID = "weather_overlay_channel"
        lateinit var instance: XiaoOuApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 初始化歌词模块相关的持久化与管理器
        PrefsManager.init(this)
        PresetManager.init(this)
        LyricsCacheManager.init(this)
        LyricsSourceManager.init(this)
        AppLogger.init(this)

        // 2. 初始化全局音乐识别引擎并注入兼容单例
        val engine = MusicRecognitionEngine(this)
        CarLyricsApplication.instance = this
        CarLyricsApplication.recognitionEngine = engine

        // 3. 尝试静默向系统设置写入通知监听白名单 (适用于车机系统/平台签名环境)
        com.car.xiaoouscreen.util.NotificationPermissionHelper.tryAutoGrantNotificationListener(this)

        // 4. 启动媒体监听并桥接通知监听
        engine.startMediaSessionListening()
        NotificationListenerService.onNotificationPosted = { sbn ->
            engine.processNotification(sbn)
        }

        createNotificationChannels()
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

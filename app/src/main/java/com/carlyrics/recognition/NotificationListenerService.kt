package com.carlyrics.recognition

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.carlyrics.util.AppLogger

/**
 * 通知监听服务 —— 多渠道音乐识别的渠道之二
 * 用户需要在系统设置中手动授权「通知使用权」
 */
class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"

        fun isIgnoredPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
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

        var instance: NotificationListenerService? = null
            private set
        var onNotificationPosted: ((StatusBarNotification) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.d(TAG, "NotificationListenerService onCreate")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.d(TAG, "onListenerConnected — 通知监听服务已连接")
        try {
            // 1. 服务就绪时，立即重新激活 MediaSession 监听
            com.carlyrics.CarLyricsApplication.recognitionEngine.startMediaSessionListening()

            // 2. 遍历现有常驻通知，立刻识别当前正在播放的音乐
            val active = activeNotifications
            AppLogger.d(TAG, "onListenerConnected: ${active?.size ?: 0} active notifications")
            active?.forEach { sbn ->
                if (isIgnoredPackage(sbn.packageName)) return@forEach
                AppLogger.d(TAG, "  existing: pkg=${sbn.packageName} category=${sbn.notification.category}")
                com.carlyrics.CarLyricsApplication.recognitionEngine.processNotification(sbn)
            }

            // 3. 强制全局刷新一次
            com.carlyrics.CarLyricsApplication.recognitionEngine.forceRefresh()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "onListenerDisconnected — 通知监听服务已断开！请检查权限设置")
        try {
            requestRebind(ComponentName(this, NotificationListenerService::class.java))
            AppLogger.d(TAG, "requestRebind() called")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind() failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val pkg = sbn.packageName
        if (isIgnoredPackage(pkg)) {
            return
        }
        // 隐私合规保护：不记录任意三方通知的敏感标题内容，仅记录包名与通知分类
        AppLogger.d(TAG, "onNotificationPosted: pkg=$pkg, category=${sbn.notification.category}")
        val callback = onNotificationPosted
        if (callback != null) {
            callback.invoke(sbn)
        } else {
            try {
                com.carlyrics.CarLyricsApplication.recognitionEngine.processNotification(sbn)
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        try {
            com.carlyrics.CarLyricsApplication.recognitionEngine.onNotificationRemoved(sbn.packageName)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        // 关键防护：绝不置空 onNotificationPosted，确保服务在系统弱内存回收重启后仍能稳定向引擎分发通知
        AppLogger.d(TAG, "NotificationListenerService onDestroy")
    }
}

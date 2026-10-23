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
        AppLogger.d(TAG, "onNotificationPosted: pkg=${sbn.packageName}, category=${sbn.notification.category}, title=${sbn.notification.extras.getString(android.app.Notification.EXTRA_TITLE)}")
        onNotificationPosted?.invoke(sbn)
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
        onNotificationPosted = null
        AppLogger.d(TAG, "NotificationListenerService onDestroy")
    }
}

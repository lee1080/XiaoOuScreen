package com.car.xiaoouscreen.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import com.car.xiaoouscreen.R
import com.carlyrics.recognition.NotificationListenerService

object NotificationPermissionHelper {
    private const val TAG = "NotificationHelper"

    /**
     * 检查当前应用是否已被授予通知监听使用权
     */
    fun isNotificationListenerGranted(context: Context): Boolean {
        val pkg = context.packageName
        val isPackageEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(pkg)
        if (isPackageEnabled) return true

        // 针对部分定制车机系统 NotificationManagerCompat 兼容性问题，直接比对 Secure Settings
        val flat = try {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        } catch (_: Exception) { null } ?: ""
        return flat.contains(pkg)
    }

    /**
     * 针对车机系统应用 / 具备平台签名的环境，尝试静默将自身服务追加写入 enabled_notification_listeners 白名单
     */
    fun tryAutoGrantNotificationListener(context: Context): Boolean {
        try {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            val myComponent = "${context.packageName}/${NotificationListenerService::class.java.name}"
            if (!flat.contains(myComponent)) {
                val newFlat = if (flat.isBlank()) myComponent else "$flat:$myComponent"
                val ok = Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", newFlat)
                if (ok) {
                    Log.i(TAG, "成功静默向 enabled_notification_listeners 追加写入: $myComponent")
                    return true
                }
            } else {
                return true
            }
        } catch (t: Throwable) {
            Log.d(TAG, "静默写入 enabled_notification_listeners 受限 (需系统设置授权): ${t.message}")
        }
        return false
    }

    /**
     * 弹窗引导用户开启通知使用权
     */
    fun promptNotificationPermission(activity: Activity, onDismiss: (() -> Unit)? = null) {
        if (isNotificationListenerGranted(activity)) {
            onDismiss?.invoke()
            return
        }

        AlertDialog.Builder(activity, R.style.IosAlertDialogTheme)
            .setTitle("需要通知使用权")
            .setMessage("车载歌词与小欧屏需要读取车机音乐通知与播放进度，以实现无缝自动切歌与状态同步。\n\n请在接下来的系统设置中开启「小欧屏」的通知使用权。\n\n(若车机无设置入口，可通过ADB执行:\nadb shell settings put secure enabled_notification_listeners ${activity.packageName}/${NotificationListenerService::class.java.name})")
            .setPositiveButton("前往开启") { _, _ ->
                openNotificationSettings(activity)
                onDismiss?.invoke()
            }
            .setNegativeButton("稍后再说") { _, _ ->
                onDismiss?.invoke()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 跳转至系统通知监听设置页 (带健全兜底)
     */
    fun openNotificationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "无法直接打开通知设置，请手动进入系统设置授权", Toast.LENGTH_LONG).show()
            }
        }
    }
}

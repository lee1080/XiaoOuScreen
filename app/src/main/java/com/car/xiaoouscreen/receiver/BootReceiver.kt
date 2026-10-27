package com.car.xiaoouscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrefsManager

/**
 * 车载点火与系统开机自启广播接收器
 * 参照 CarLyricsApp 经过实车验证的成熟开机启动逻辑：
 * 1. 声明 android:directBootAware="true" 并设置最高广播优先级 2147483647
 * 2. 覆盖主流车机系统（长安/吉利/通用/HTC等）的点火与通电广播动作
 * 3. 校验用户是否在服务设置中开启了「开机启动」，若开启则秒级拉起小欧屏投屏服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        private fun startOverlayServiceSafely(context: Context) {
            try {
                val showAvatar = PrefsManager.isXiaoOuAvatarEnabled()
                val targetAction = if (showAvatar) {
                    WeatherOverlayService.ACTION_SHOW_FITNESS
                } else {
                    WeatherOverlayService.ACTION_SHOW_TOP
                }
                val intent = Intent(context, WeatherOverlayService::class.java).apply {
                    action = targetAction
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AppLogger.d(TAG, "已成功拉起 WeatherOverlayService 投屏服务 (action=$targetAction, showAvatar=$showAvatar)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "拉起 WeatherOverlayService 失败: ${e.message}", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        AppLogger.d(TAG, "收到系统启动/点火上电广播 action: $action")

        try {
            // 初始化持久化管理器 (兼容 Direct Boot 设备保护存储)
            PrefsManager.init(context)

            val autoStartEnabled = PrefsManager.isAutoStartBoot()
            val overlayWasVisible = PrefsManager.getOverlayVisible()

            if (!autoStartEnabled && !overlayWasVisible) {
                AppLogger.d(TAG, "用户未开启开机启动且悬浮窗未处于显示状态，跳过自启")
                return
            }

            AppLogger.d(TAG, "满足开机启动条件 (autoStart=$autoStartEnabled, overlayVisible=$overlayWasVisible)，执行服务启动")
            startOverlayServiceSafely(context)
        } catch (e: Exception) {
            AppLogger.e(TAG, "BootReceiver 处理异常: ${e.message}", e)
        }
    }
}

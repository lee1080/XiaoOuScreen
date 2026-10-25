package com.car.xiaoouscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.service.WeatherOverlayService

/**
 * 接收来自外部 / ADB 命令行广播的指令接收器
 * 无论服务是否处于前台，均能捕获广播并调度悬浮窗与档位
 */
class XiaoOuCommandReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "XiaoOuCommandReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "📡 静态广播接收器收到指令: $action")

        // 若前台投屏服务已运行，内部动态接收器已精准处理，避免重复执行
        if (WeatherOverlayService.instance != null) {
            Log.d(TAG, "WeatherOverlayService 服务运行中，交由内部动态接收器处理")
            return
        }

        when (action) {
            WeatherOverlayService.ACTION_GEAR_SWITCH_LEGACY, WeatherOverlayService.ACTION_SWITCH_GEAR -> {
                val gear = intent.getStringExtra(WeatherOverlayService.EXTRA_GEAR_LEGACY)
                    ?: intent.getStringExtra(WeatherOverlayService.EXTRA_TARGET_GEAR)
                    ?: "P"
                Log.i(TAG, "🏎️ 静态广播触发切档: $gear")
                // 1. 同步更新 CarGearManager 状态
                CarGearManager.getInstance(context).simulateGear(gear)
                // 2. 调度前台投屏服务
                WeatherOverlayService.switchGear(context, gear)
            }
            WeatherOverlayService.ACTION_SHOW_TOP -> {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_SHOW_TOP)
            }
            WeatherOverlayService.ACTION_SHOW_FITNESS -> {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_SHOW_FITNESS)
            }
            WeatherOverlayService.ACTION_HIDE -> {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_HIDE)
            }
            WeatherOverlayService.ACTION_TRIGGER_LIGHTNING -> {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_TRIGGER_LIGHTNING)
            }
            WeatherOverlayService.ACTION_SIMULATE_PRESET -> {
                val preset = intent.getStringExtra(WeatherOverlayService.EXTRA_PRESET_KEY)
                val i = Intent(context, WeatherOverlayService::class.java).apply {
                    this.action = WeatherOverlayService.ACTION_SIMULATE_PRESET
                    putExtra(WeatherOverlayService.EXTRA_PRESET_KEY, preset)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
            WeatherOverlayService.ACTION_RESET_LIVE -> {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_RESET_LIVE)
            }
            WeatherOverlayService.ACTION_CALL_EVENT -> {
                val typeStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_TYPE) ?: "normal"
                val stateStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_STATE) ?: "ringing"
                val name = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NAME) ?: "张总"
                val number = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NUMBER) ?: "13800009527"
                val type = if (typeStr.equals("wechat", ignoreCase = true)) CarCallManager.CallType.WECHAT else CarCallManager.CallType.NORMAL
                val state = when (stateStr.lowercase()) {
                    "ringing", "incoming" -> CarCallManager.CallState.RINGING
                    "dialing", "outgoing" -> CarCallManager.CallState.DIALING
                    "active", "connected" -> CarCallManager.CallState.ACTIVE
                    "hangup", "terminated" -> CarCallManager.CallState.TERMINATED
                    else -> CarCallManager.CallState.IDLE
                }
                CarCallManager.getInstance(context).simulateCall(type, state, name, number)
            }
        }
    }
}

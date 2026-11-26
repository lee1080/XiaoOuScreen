package com.car.xiaoouscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.carlyrics.util.PrivacyUtil

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
                val rawGear = intent.getStringExtra(WeatherOverlayService.EXTRA_GEAR_LEGACY)
                    ?: intent.getStringExtra(WeatherOverlayService.EXTRA_TARGET_GEAR)
                    ?: "P"
                val gear = rawGear.uppercase().trim()
                if (gear != "P" && gear != "R" && gear != "N" && gear != "D") {
                    Log.w(TAG, "忽略非有效档位指令: $rawGear")
                    return
                }
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
            WeatherOverlayService.ACTION_SIMULATE_CLOUDEYE_EXIT -> {
                Log.i(TAG, "📡 静态广播接收器触发模拟云眼退出接管")
                WeatherOverlayService.simulateCloudEyeExit(context)
            }
            WeatherOverlayService.ACTION_CALL_EVENT -> {
                val typeStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_TYPE) ?: "normal"
                val stateStr = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_STATE) ?: "ringing"
                val name = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NAME) ?: "张总"
                val number = intent.getStringExtra(WeatherOverlayService.EXTRA_CALL_NUMBER) ?: "13800009527"
                Log.d(TAG, "接收到通话事件广播: type=$typeStr, state=$stateStr, name=${PrivacyUtil.maskName(name)}, num=${PrivacyUtil.maskPhone(number)}")
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
            WeatherOverlayService.ACTION_SIMULATE_BATTERY -> {
                val voltage = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_VOLTAGE)) {
                    intent.getFloatExtra(WeatherOverlayService.EXTRA_BATTERY_VOLTAGE, -1f).takeIf { it > 0f }
                } else null
                val soh = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_SOH)) {
                    intent.getIntExtra(WeatherOverlayService.EXTRA_BATTERY_SOH, -1).takeIf { it in 1..100 }
                } else null
                val soc = if (intent.hasExtra(WeatherOverlayService.EXTRA_BATTERY_SOC)) {
                    intent.getIntExtra(WeatherOverlayService.EXTRA_BATTERY_SOC, -1).takeIf { it in 0..100 }
                } else null
                val low = intent.getBooleanExtra(WeatherOverlayService.EXTRA_BATTERY_LOW, false)
                CarGearManager.getInstance(context).simulateBattery(voltage, soh, soc, low)
            }
            WeatherOverlayService.ACTION_SIMULATE_TEMPS -> {
                val outT = if (intent.hasExtra(WeatherOverlayService.EXTRA_OUTSIDE_TEMP)) {
                    intent.getFloatExtra(WeatherOverlayService.EXTRA_OUTSIDE_TEMP, Float.NaN).takeIf { !it.isNaN() }
                } else null
                val cabT = if (intent.hasExtra(WeatherOverlayService.EXTRA_CABIN_TEMP)) {
                    intent.getFloatExtra(WeatherOverlayService.EXTRA_CABIN_TEMP, Float.NaN).takeIf { !it.isNaN() }
                } else null
                CarGearManager.getInstance(context).simulateTemps(outT, cabT)
            }
            WeatherOverlayService.ACTION_SIMULATE_GFORCE -> {
                val gx = intent.getFloatExtra(WeatherOverlayService.EXTRA_GFORCE_X, 0f)
                val gy = intent.getFloatExtra(WeatherOverlayService.EXTRA_GFORCE_Y, 0f)
                val holdMs = intent.getLongExtra(WeatherOverlayService.EXTRA_GFORCE_HOLD_MS, 15_000L)
                com.car.xiaoouscreen.car.CarGForceManager.getInstance(context).simulateGForce(gx, gy, holdMs)
            }
            WeatherOverlayService.ACTION_SIMULATE_MOTOR_POWER -> {
                val kw = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_POWER_KW)) {
                    intent.getFloatExtra(WeatherOverlayService.EXTRA_MOTOR_POWER_KW, Float.NaN).takeIf { !it.isNaN() }
                } else null
                val hvSoc = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_HV_SOC)) {
                    intent.getIntExtra(WeatherOverlayService.EXTRA_MOTOR_HV_SOC, -1).takeIf { it in 0..100 }
                } else null
                val plugCharging = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MOTOR_PLUG_CHARGING, false)
                val resetPeaks = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MOTOR_RESET_PEAKS, false)
                CarGearManager.getInstance(context).simulateMotorPower(kw, hvSoc, plugCharging, resetPeaks)
            }
            WeatherOverlayService.ACTION_SIMULATE_RPM -> {
                val eng = if (intent.hasExtra(WeatherOverlayService.EXTRA_ENGINE_RPM)) {
                    intent.getIntExtra(WeatherOverlayService.EXTRA_ENGINE_RPM, -1).takeIf { it >= 0 }
                } else null
                val mot = if (intent.hasExtra(WeatherOverlayService.EXTRA_MOTOR_RPM)) {
                    intent.getIntExtra(WeatherOverlayService.EXTRA_MOTOR_RPM, -1).takeIf { it >= 0 }
                } else null
                val resetPeaks = intent.getBooleanExtra(WeatherOverlayService.EXTRA_RPM_RESET_PEAKS, false)
                val modeStr = intent.getStringExtra("mode")?.lowercase()
                val mode = when (modeStr) {
                    "ev" -> CarGearManager.PowertrainMode.EV
                    "hev" -> CarGearManager.PowertrainMode.HEV
                    "ice" -> CarGearManager.PowertrainMode.PURE_ICE
                    else -> null
                }
                CarGearManager.getInstance(context).simulateRpm(eng, mot, resetPeaks, mode)
            }
            WeatherOverlayService.ACTION_SIMULATE_LANE_CHANGE -> {
                val left = intent.getIntExtra(WeatherOverlayService.EXTRA_LANE_LEFT_WARNING, 0)
                val right = intent.getIntExtra(WeatherOverlayService.EXTRA_LANE_RIGHT_WARNING, 0)
                val bsdOn = intent.getBooleanExtra(WeatherOverlayService.EXTRA_LANE_BSD_ON, true)
                CarGearManager.getInstance(context).simulateLaneChangeAlert(left, right, bsdOn)
            }
            WeatherOverlayService.ACTION_SIMULATE_TURN_SIGNAL -> {
                val left = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_LEFT, false)
                val right = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_RIGHT, false)
                val hazard = intent.getBooleanExtra(WeatherOverlayService.EXTRA_TURN_HAZARD, false)
                val avmDir = intent.getIntExtra(WeatherOverlayService.EXTRA_TURN_AVM, 0)
                CarGearManager.getInstance(context).simulateTurnSignal(left, right, hazard, avmDir)
            }
            WeatherOverlayService.ACTION_SIMULATE_MUSIC -> {
                val playing = intent.getBooleanExtra(WeatherOverlayService.EXTRA_MUSIC_PLAYING, true)
                val title = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_TITLE) ?: "测试曲目"
                val artist = intent.getStringExtra(WeatherOverlayService.EXTRA_MUSIC_ARTIST) ?: "测试歌手"
                val track = com.carlyrics.model.CurrentTrack(
                    title = title,
                    artist = artist,
                    isPlaying = playing,
                    position = 15000L,
                    duration = 200000L
                )
                com.car.xiaoouscreen.XiaoOuApplication.rhythmEngine.onTrackChanged(track)
            }
            WeatherOverlayService.ACTION_SIMULATE_PREEMPT -> {
                val reason = intent.getStringExtra("reason") ?: "ADB静态广播模拟抢占"
                val delay = intent.getLongExtra("delay", 5000L)
                Log.i(TAG, "📡 静态广播接收器触发模拟原车抢占: reason=$reason, delay=${delay}ms")
                com.car.xiaoouscreen.car.AmbientLightManager.preempt(reason, autoReclaimDelayMs = delay)
            }
            WeatherOverlayService.ACTION_RECLAIM_AMBIENT_LIGHT -> {
                val origin = intent.getStringExtra("origin") ?: "ADB静态广播重新接管"
                Log.i(TAG, "📡 静态广播接收器触发重新接管: origin=$origin")
                com.car.xiaoouscreen.car.AmbientLightManager.reclaimAndSync(origin)
            }
        }
    }
}

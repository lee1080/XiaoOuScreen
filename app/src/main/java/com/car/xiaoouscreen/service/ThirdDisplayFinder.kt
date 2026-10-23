package com.car.xiaoouscreen.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * 车载屏幕智能检测与路由：
 * 1. 优先按系统特征名称匹配 AR_XIAOOU (欧尚小欧屏系统标识)
 * 2. 优先匹配物理高度为 384 的超宽长条副屏 (如 1920x384)
 * 3. 匹配 displayId = 2 (欧尚 B561 默认第三屏)
 * 4. 若在开发平板或单屏环境上，自动降级为默认主屏幕 (Display 0)，确保在平板上也能 100% 调试投屏
 */
object ThirdDisplayFinder {
    private const val TAG = "ThirdDisplayFinder"
    const val Z6_PREFERRED_DISPLAY_ID = 2

    private var manualDisplayId: Int? = null

    fun setManualDisplayId(id: Int?) {
        manualDisplayId = id
    }

    fun getManualDisplayId(): Int? = manualDisplayId

    /**
     * 循环切换检测到的可用屏幕 (用于实车调试与单屏平板测试)
     */
    fun cycleDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val displays = dm.displays ?: return null
        if (displays.isEmpty()) return null

        val current = find(context)
        val currentId = current?.displayId ?: 0

        var nextIdx = 0
        for (i in displays.indices) {
            if (displays[i].displayId == currentId) {
                nextIdx = (i + 1) % displays.size
                break
            }
        }
        manualDisplayId = displays[nextIdx].displayId
        return displays[nextIdx]
    }

    fun find(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val displays = dm.displays ?: return null
        if (displays.isEmpty()) return null

        // 1. 若手动指定了 DisplayId，优先返回
        if (manualDisplayId != null) {
            for (d in displays) {
                if (d.displayId == manualDisplayId) return d
            }
        }

        // 2. 优先按名称匹配 AR_XIAOOU
        for (d in displays) {
            val name = d.name
            if (name != null && (name.contains("AR_XIAOOU", ignoreCase = true) ||
                        name.contains("XIAOOU", ignoreCase = true))) {
                Log.i(TAG, "命中名称匹配: id=${d.displayId}, name=$name")
                return d
            }
        }

        // 3. 匹配物理高度 384 或 1920x384 比例副屏
        for (d in displays) {
            if (d.displayId != Display.DEFAULT_DISPLAY) {
                val (w, h) = sizeOf(d)
                if (h == 384 || (w == 1920 && h <= 480)) {
                    Log.i(TAG, "命中分辨率匹配: id=${d.displayId}, size=${w}x$h")
                    return d
                }
            }
        }

        // 4. 匹配系统默认 displayId = 2 (欧尚三屏硬件偏移，排除第三方虚拟屏幕如 HiSight 投屏)
        for (d in displays) {
            val isVirtual = (d.flags and Display.FLAG_PRESENTATION) != 0 && d.name?.contains("HiSight", ignoreCase = true) == true
            if (d.displayId == Z6_PREFERRED_DISPLAY_ID && !isVirtual) {
                Log.i(TAG, "命中 displayId=2 硬件屏匹配")
                return d
            }
        }

        // 5. 开发调试兜底：若非实车专属副屏，默认降级返回本地主屏幕 (Display.DEFAULT_DISPLAY)，确保在平板本地直接可见调试
        for (d in displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) {
                return d
            }
        }
        return displays.firstOrNull()
    }

    fun describe(context: Context): String {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return "无 DisplayManager"
        val displays = dm.displays ?: return "无屏幕"
        val active = find(context)

        val sb = StringBuilder()
        sb.append("探测到 ").append(displays.size).append(" 块屏幕:\n")
        for (d in displays) {
            val (w, h) = sizeOf(d)
            val isCurrent = (active != null && d.displayId == active.displayId)
            sb.append(if (isCurrent) " -> " else "    ")
            sb.append("[").append(d.displayId).append("] ")
            sb.append(d.name).append(" (").append(w).append("x").append(h).append(")")
            if (d.displayId == Display.DEFAULT_DISPLAY) sb.append(" [主屏]")
            if (isCurrent) sb.append(" [已选为目标副屏]")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun sizeOf(d: Display): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        d.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}

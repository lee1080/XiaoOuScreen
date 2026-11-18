package com.car.xiaoouscreen.ui

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * 车载常亮副屏防烧屏 / 抗残影微距像素漂移引擎 (Burn-in Protection · Zero-GC)
 *
 * 设计要点：
 * 1. 基于全局单调时钟 (SystemClock.uptimeMillis) 驱动互质周期的平滑李萨如 (Lissajous) 轨迹，
 *    使所有常亮前景图层（左侧天气 HUD、中央大时钟、五行歌词、顶部音乐胶囊、左下角常驻挡位 HUD、
 *    全屏歌词页及 3D 翻屏快照）在任意时刻严格同步偏移，杜绝组件间相对错位与截屏跳变。
 * 2. 偏移范围严守 1920×384 逻辑画布物理安全边界：
 *    - X 轴最大振幅 ±4.0px（左侧安全边界 138px - 4px = 134px > 130px 物理盲区）
 *    - Y 轴最大振幅 ±3.0px（顶部音乐胶囊 6px - 3px = 3px >= 0；左下角挡位 HUD 374px + 3px = 377px <= 384px）
 * 3. 互质长周期（X=180s, Y=290s，周期比 18:29），均匀遍历 8×6 像素区域；最大速率仅 ~0.14 px/s
 *    （约 7 秒移动 1 像素），驾驶位肉眼完全无感且无突兀跳动，零对象分配、零额外线程开销。
 */
object BurnInProtection {

    const val MAX_SHIFT_X_PX = 4.0f
    const val MAX_SHIFT_Y_PX = 3.0f

    /** 静态视图（如未切挡时的常驻挡位 HUD、暂停时的全屏歌词）低频微移刷新节拍（8 秒约 1px） */
    const val STATIC_REFRESH_INTERVAL_MS = 8000L

    /** 全屏歌词页四边防露黑缝等比微小过扫描比例（384 * 1.016 ≈ 390.14px，上下各多出 >3px 冗余且严格保持正圆） */
    const val FULLSCREEN_OVERSCAN_SCALE = 1.016f

    private const val OMEGA_X = (2.0 * PI) / 180_000.0 // 3 分钟一周期
    private const val OMEGA_Y = (2.0 * PI) / 290_000.0 // 4 分 50 秒一周期（与 X 周期互质）

    fun getShiftX(nowUptimeMs: Long = SystemClock.uptimeMillis()): Float {
        return (sin(nowUptimeMs * OMEGA_X) * MAX_SHIFT_X_PX).toFloat()
    }

    fun getShiftY(nowUptimeMs: Long = SystemClock.uptimeMillis()): Float {
        return (sin(nowUptimeMs * OMEGA_Y) * MAX_SHIFT_Y_PX).toFloat()
    }
}

package com.car.xiaoouscreen.car

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * 原厂欧尚座舱氛围灯 64 色物理映射表与色彩转换工具
 * 严格对齐长安 B561 原厂 SmartLights (SelectColorArc) 硬件 64 色色谱表
 *
 * 色号范围说明：
 * - 车机 AIDL (setUserSelectColor) 与 CAN 总线 (0x2141f3d5 area=0): 1 ~ 64 (1-based 色号)
 * - 原车色盘 UI 控件 (SelectColorArc): 0 ~ 63 (0-based 数组索引，index = id - 1)
 */
object LightingColor64 {

    /**
     * 原厂 SelectColorArc.mColors 64 色物理色谱数组 (0..63)
     */
    val REAL_CAR_COLORS: IntArray = intArrayOf(
        0xFFFBECAB.toInt(), 0xFFFFFFCC.toInt(), 0xFFFFF0F0.toInt(), 0xFFFFF0F0.toInt(),
        0xFFBECCFF.toInt(), 0xFFCCCCFF.toInt(), 0xFFDEB9F9.toInt(), 0xFFBD9BF9.toInt(),
        0xFF8F75ED.toInt(), 0xFFD787FF.toInt(), 0xFFD962FF.toInt(), 0xFFFFB7B7.toInt(),
        0xFFFF73D4.toInt(), 0xFFFF79A6.toInt(), 0xFFFF5BBD.toInt(), 0xFFFE488D.toInt(),
        0xFFFF4545.toInt(), 0xFFFF7171.toInt(), 0xFFFE4B42.toInt(), 0xFFFF0D01.toInt(),
        0xFFFF0E04.toInt(), 0xFFFF0D19.toInt(), 0xFFFF2018.toInt(), 0xFFFE3E34.toInt(),
        0xFFFF1174.toInt(), 0xFFFE1A9C.toInt(), 0xFFFF18FE.toInt(), 0xFFFF43FF.toInt(),
        0xFFF250FE.toInt(), 0xFFCC00FF.toInt(), 0xFFB603FD.toInt(), 0xFF7676F8.toInt(),
        0xFF8554FE.toInt(), 0xFF3B44FF.toInt(), 0xFF8170FE.toInt(), 0xFF7070FE.toInt(),
        0xFF299FFF.toInt(), 0xFF9DD3FF.toInt(), 0xFFB8FEF9.toInt(), 0xFF7DFDE2.toInt(),
        0xFF02ECFE.toInt(), 0xFF00FFE6.toInt(), 0xFF19FF9C.toInt(), 0xFF01FF7C.toInt(),
        0xFF47FF6C.toInt(), 0xFF56FF01.toInt(), 0xFF00FF14.toInt(), 0xFF00FF21.toInt(),
        0xFF3CFF21.toInt(), 0xFF8BFE21.toInt(), 0xFFB0FF21.toInt(), 0xFFFEBF0C.toInt(),
        0xFFFE9B24.toInt(), 0xFFFE8C02.toInt(), 0xFFFF630D.toInt(), 0xFFFF8745.toInt(),
        0xFFFF420F.toInt(), 0xFFFF5D31.toInt(), 0xFFFF8463.toInt(), 0xFFFF8F71.toInt(),
        0xFFFFC761.toInt(), 0xFFFFCC71.toInt(), 0xFFFFD589.toInt(), 0xFFFFDA97.toInt()
    )

    // 向后兼容旧名称别名
    val LIGHTING_RGB: IntArray = REAL_CAR_COLORS

    // 预计算 64 色色相 (0..360) 与饱和度 (0..1)
    private val CAR_HUE = FloatArray(64)
    private val CAR_SAT = FloatArray(64)

    init {
        val hsv = FloatArray(3)
        for (i in 0 until 64) {
            Color.colorToHSV(REAL_CAR_COLORS[i], hsv)
            CAR_HUE[i] = hsv[0]
            CAR_SAT[i] = hsv[1]
        }
    }

    /**
     * 将任意 RGB 颜色根据色相（Hue）环形距离与饱和度匹配到原厂 1~64 挡硬件色号
     * @return 1..64 车机原生色彩编号
     */
    fun getRGBIndex(@ColorInt rgb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        val targetHue = hsv[0]
        val targetSat = hsv[1]

        // 极低饱和度（黑白灰无彩色），回退到原厂极光暖白（CarID 1: #FBECAB）
        if (targetSat < 0.12f) {
            return 1
        }

        var bestIdx = 0
        var minDiff = Float.MAX_VALUE

        for (i in 0 until 64) {
            // 当目标色饱和度充足时，排除前 4 个接近浅白/淡色的色号（0..3）
            if (targetSat >= 0.20f && i in 0..3) {
                continue
            }

            val ch = CAR_HUE[i]
            val cs = CAR_SAT[i]

            // 计算环形色相最短距离 (0..180)
            var dh = kotlin.math.abs(targetHue - ch)
            if (dh > 180f) {
                dh = 360f - dh
            }

            // 加权距离：色相占 80% 决策权重，饱和度差占 20%
            val diff = dh + kotlin.math.abs(targetSat - cs) * 25f
            if (diff < minDiff) {
                minDiff = diff
                bestIdx = i
            }
        }

        // 返回 1-based 车机硬件色号 (1..64)
        return bestIdx + 1
    }

    /**
     * 获取色号对应的 RGB 颜色（用于小欧屏 UI 预览，与实车 100% 一致）
     * 支持 1..64（车机原生色号）与 0..63（0基索引）安全输入
     */
    @ColorInt
    fun getColorByIndex(idOrIndex: Int): Int {
        val safeIndex = if (idOrIndex in 1..64) {
            idOrIndex - 1
        } else {
            idOrIndex.coerceIn(0, 63)
        }
        return REAL_CAR_COLORS[safeIndex]
    }
}

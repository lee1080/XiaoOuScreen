package com.carlyrics.util

import android.content.Context
import android.content.res.Configuration

/**
 * 跨设备 Density 归一化工具类
 *
 * 核心原理：
 * 强制将 Context 的 density 锁定为标准基准 160 DPI (density = 1.0f)，
 * 使得 1920x384 黄金分辨率容器内部的所有 View 测量排版 (dp/sp) 与像素 1:1 严格对齐。
 * 随后结合外层矩阵等比硬件缩放 (Scale Matrix)，实现任何屏幕 (车机屏、Pad、手机) 零溢出、零变形、无需手动校准。
 */
object DensityNormalization {
    fun getNormalizedContext(base: Context): Context {
        val config = Configuration(base.resources.configuration).apply {
            densityDpi = 160 // MDPI 基准: 1dp = 1px, 1sp = 1px
        }
        return base.createConfigurationContext(config)
    }
}

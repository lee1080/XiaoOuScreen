package com.carlyrics.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.LayoutInflater

/**
 * 跨设备 Density 归一化工具类 (车规级 100% 绝对物理隔离)
 *
 * 核心原理：
 * 强制将 Context 的 density 锁定为标准基准 160 DPI (density = 1.0f, scaledDensity = 1.0f)，
 * 使得 1920x384 黄金分辨率容器内部的所有 View 测量排版 (dp/sp) 与像素 1:1 严格对齐。
 * 彻底切断 DisplayContext / PresentationContext / ActivityContext / AppCompatContext
 * 因宿主屏幕物理 Density (如平板 2.25、车机 1.5、手机 3.0 等) 渗透导致二次放大、排版溢出与错位。
 */
object DensityNormalization {

    fun getNormalizedContext(base: Context): Context {
        if (base is NormalizedContextWrapper) return base

        val baseRes = base.resources
        val config = Configuration(baseRes.configuration).apply {
            densityDpi = 160
            fontScale = 1.0f
        }

        val configContext = try {
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }

        return NormalizedContextWrapper(configContext)
    }

    private class NormalizedContextWrapper(base: Context) : ContextWrapper(base) {
        private var inflater: LayoutInflater? = null

        private val normalizedResources: Resources by lazy {
            val originalRes = base.resources
            val metrics = DisplayMetrics().apply {
                originalRes.displayMetrics?.let { setTo(it) }
                densityDpi = 160
                density = 1.0f
                scaledDensity = 1.0f
                widthPixels = 1920
                heightPixels = 384
                xdpi = 160f
                ydpi = 160f
            }
            val config = Configuration(originalRes.configuration).apply {
                densityDpi = 160
                fontScale = 1.0f
            }

            @Suppress("DEPRECATION")
            object : Resources(originalRes.assets, metrics, config) {
                override fun getDisplayMetrics(): DisplayMetrics = metrics
                override fun getConfiguration(): Configuration = config
            }
        }

        override fun getResources(): Resources = normalizedResources

        override fun getSystemService(name: String): Any? {
            if (Context.LAYOUT_INFLATER_SERVICE == name) {
                if (inflater == null) {
                    val baseInflater = LayoutInflater.from(baseContext)
                    inflater = baseInflater.cloneInContext(this)
                }
                return inflater
            }
            return super.getSystemService(name)
        }
    }
}

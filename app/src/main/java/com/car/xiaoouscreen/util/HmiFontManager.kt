package com.car.xiaoouscreen.util

import android.content.Context
import android.graphics.Typeface
import android.util.Log

/**
 * 车规级离线字体管理与单例缓存
 * 优先加载 assets/fonts/orbitron_bold.ttf，若异常则平滑降级至系统原生加粗无衬线字体。
 */
object HmiFontManager {

    private const val TAG = "HmiFontManager"
    private var orbitronTypeface: Typeface? = null
    private var isInitialized = false

    fun getOrbitronBold(context: Context): Typeface {
        orbitronTypeface?.let { return it }

        if (!isInitialized) {
            isInitialized = true
            try {
                orbitronTypeface = Typeface.createFromAsset(context.assets, "fonts/orbitron_bold.ttf")
                Log.i(TAG, "车载离线 Orbitron 科技字体加载成功")
            } catch (t: Throwable) {
                Log.w(TAG, "加载 assets Orbitron 字体失败，降级至原生加粗字体: ${t.message}")
                orbitronTypeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
        }
        return orbitronTypeface ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun getSystemMediumBold(): Typeface {
        return Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun getSystemMedium(): Typeface {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

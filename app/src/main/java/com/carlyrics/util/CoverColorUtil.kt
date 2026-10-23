package com.carlyrics.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.palette.graphics.Palette

/**
 * 从封面图提取配色，生成渐变背景
 */
object CoverColorUtil {

    data class CoverColors(
        val vibrant: Int = Color.parseColor("#FFB3BDCD"),
        val muted: Int = Color.parseColor("#FFE8EEF5"),
        val dark: Int = Color.parseColor("#FFC9D2E0")
    )

    private val defaultColors = CoverColors()

    /**
     * 从 Bitmap 提取颜色（在 Dispatchers.Default 子线程执行，防止卡顿 UI 主线程）
     */
    suspend fun extractColors(bitmap: Bitmap): CoverColors = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()

        val vibrant = palette?.vibrantSwatch?.rgb
        val muted = palette?.mutedSwatch?.rgb
        val darkVibrant = palette?.darkVibrantSwatch?.rgb
        val darkMuted = palette?.darkMutedSwatch?.rgb

        CoverColors(
            vibrant = vibrant ?: defaultColors.vibrant,
            muted = muted ?: defaultColors.muted,
            dark = darkVibrant ?: darkMuted ?: defaultColors.dark
        )
    }

    /**
     * 创建垂直渐变 Drawable
     */
    fun createVerticalGradient(colors: CoverColors): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.dark, colors.muted, colors.vibrant)
        ).apply {
            this.gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    /**
     * 创建对角渐变 Drawable
     */
    fun createDiagonalGradient(colors: CoverColors): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(colors.dark, colors.muted, colors.vibrant)
        ).apply {
            this.gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    /**
     * 从颜色获取互补的文字颜色 (亮or暗)
     */
    fun textColorForBackground(bgColor: Int): Int {
        val luminance = (0.299 * Color.red(bgColor) + 0.587 * Color.green(bgColor) + 0.114 * Color.blue(bgColor)) / 255.0
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }

    /**
     * 使颜色变暗
     */
    fun darken(color: Int, factor: Float = 0.5f): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    fun getDefaultColors(): CoverColors = defaultColors
}

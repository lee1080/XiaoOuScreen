package com.carlyrics.skin

import android.content.Context
import android.view.Gravity

data class SkinDefaultParams(
    val splitRatio: Float,
    val marqueeEnabled: Boolean,
    val lyricsAlignment: Int,
    val displayScale: Float,
    val infoFontScale: Float
)

enum class SkinType(val label: String, val defaultParams: SkinDefaultParams) {
    DEFAULT("默认", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.START or Gravity.CENTER_VERTICAL, // 8388611
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    )),
    GLASSMORPHISM("毛玻璃质感", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.START or Gravity.CENTER_VERTICAL, // 8388611
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    )),
    MINIMAL_NEON("极简霓虹", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.START or Gravity.CENTER_VERTICAL, // 8388611
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    )),
    VIBRANT_WAVE("动感波浪", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.CENTER, // 17 (动感波浪居中)
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    )),
    VINYL_PLAYER("唱片风格", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.START or Gravity.CENTER_VERTICAL, // 8388611
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    )),
    IMMERSIVE("沉浸式", SkinDefaultParams(
        splitRatio = 0.25f,
        marqueeEnabled = true,
        lyricsAlignment = Gravity.START or Gravity.CENTER_VERTICAL, // 8388611
        displayScale = 0.85f,
        infoFontScale = 1.2272727f
    ));
}

object SkinFactory {
    fun create(type: SkinType, context: Context): LyricsSkin = when (type) {
        SkinType.DEFAULT -> com.carlyrics.display.LyricsDisplayView(context) as LyricsSkin
        SkinType.GLASSMORPHISM -> SkinGlassmorphism(context)
        SkinType.MINIMAL_NEON -> SkinMinimalNeon(context)
        SkinType.VIBRANT_WAVE -> SkinVibrantWave(context)
        SkinType.VINYL_PLAYER -> SkinVinylPlayer(context)
        SkinType.IMMERSIVE -> SkinImmersive(context)
    }
}

package com.car.xiaoouscreen.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Shared zero-allocation Canvas rendering helpers.
 * Replaces expensive per-frame Skia BlurMaskFilter (Paint.setShadowLayer) offscreen GPU
 * Scratch RenderTarget passes with dual-pass offset shadow/glow rendering.
 */
object CanvasRenderUtils {

    /**
     * Draws text with a crisp, zero-RenderTarget soft shadow (and optional colored ambient glow halo).
     * Mutates only the caller's Paint color/alpha/shader temporarily and restores them before returning.
     */
    fun drawTextWithSoftShadow(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        targetAlpha: Int,
        shadowDx: Float,
        shadowDy: Float,
        baseShadowAlpha: Int,
        @ColorInt shadowColor: Int = Color.BLACK
    ) {
        val clampedAlpha = targetAlpha.coerceIn(0, 255)
        if (clampedAlpha <= 0 || text.isEmpty()) return

        val origColor = paint.color
        val origShader = paint.shader
        val shadowAlpha = ((baseShadowAlpha * (clampedAlpha / 255f)) * 0.58f).toInt().coerceIn(0, 255)

        if (shadowAlpha > 2) {
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(shadowColor, shadowAlpha)
            canvas.drawText(text, x + shadowDx, y + shadowDy, paint)
        }

        paint.shader = origShader
        paint.color = origColor
        paint.alpha = clampedAlpha
        canvas.drawText(text, x, y, paint)
        paint.alpha = clampedAlpha
    }

    /**
     * Draws text with a high-contrast multi-pass soft dark shadow (and zero GPU Scratch RenderTarget)
     * for bright daylight/cloudy backgrounds where single-pass offset shadows wash out.
     */
    fun drawTextWithBrightBackdropShadow(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        targetAlpha: Int,
        brightFactor: Float,
        baseShadowAlpha: Int = 200
    ) {
        val clampedAlpha = targetAlpha.coerceIn(0, 255)
        if (clampedAlpha <= 0 || text.isEmpty()) return

        val origColor = paint.color
        val origShader = paint.shader
        val bf = brightFactor.coerceIn(0f, 1f)
        val alphaScale = clampedAlpha / 255f

        paint.shader = null
        // Broad dark ambient cradle behind bright-background white text
        val farShadowA = (baseShadowAlpha * alphaScale * (0.45f + 0.40f * bf)).toInt().coerceIn(0, 255)
        if (farShadowA > 2) {
            paint.color = Color.argb(farShadowA, 4, 12, 24)
            canvas.drawText(text, x, y + 2.6f, paint)
        }
        if (bf > 0.15f) {
            val haloDarkA = (baseShadowAlpha * alphaScale * 0.42f * bf).toInt().coerceIn(0, 255)
            if (haloDarkA > 2) {
                paint.color = Color.argb(haloDarkA, 4, 12, 24)
                canvas.drawText(text, x, y + 1.0f, paint)
            }
        }

        paint.shader = origShader
        paint.color = origColor
        paint.alpha = clampedAlpha
        canvas.drawText(text, x, y, paint)
        paint.alpha = clampedAlpha
    }

    /**
     * Configures paint alpha cleanly without invoking GPU BlurMaskFilter (clearShadowLayer).
     * Used when the caller prefers direct two-pass drawing or halo pass around text.
     */
    fun drawTextWithHaloAndShadow(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        targetAlpha: Int,
        @ColorInt glowColor: Int,
        glowAlpha: Int,
        dyGlow: Float = 3f,
        backdropBoost: Float = 0f
    ) {
        val clampedAlpha = targetAlpha.coerceIn(0, 255)
        if (clampedAlpha <= 0 || text.isEmpty()) return

        val origColor = paint.color
        val origShader = paint.shader
        val boost = backdropBoost.coerceIn(0f, 1f)
        val dropShadowAlpha = (clampedAlpha * (0.42f + 0.34f * boost)).toInt().coerceIn(0, 255)

        if (boost > 0.15f) {
            val wideDarkAlpha = (clampedAlpha * 0.34f * boost).toInt().coerceIn(0, 255)
            if (wideDarkAlpha > 2) {
                paint.shader = null
                paint.color = Color.argb(wideDarkAlpha, 4, 12, 24)
                canvas.drawText(text, x, y + 4.2f, paint)
            }
        }
        if (dropShadowAlpha > 2) {
            paint.shader = null
            paint.color = Color.argb(dropShadowAlpha, 0, 0, 0)
            canvas.drawText(text, x, y + 2.5f, paint)
        }
        val haloA = (glowAlpha * (0.36f + 0.18f * boost)).toInt().coerceIn(0, 255)
        if (haloA > 3) {
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(glowColor, haloA)
            canvas.drawText(text, x, y + dyGlow, paint)
        }

        paint.shader = origShader
        paint.color = origColor
        paint.alpha = clampedAlpha
        canvas.drawText(text, x, y, paint)
    }
}

package com.carlyrics.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrefsManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 仪表盘有效内容区域渲染 SurfaceView (BorderSurfaceView)
 * 
 * 读取 display_area_* 参数构造裁剪 Rect，通过 OpenGL viewport/scissor 裁剪管线应用到输出。
 */
class BorderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "BorderSurfaceView"
    }

    private var cropLeft: Int = 0
    private var cropTop: Int = 0
    private var cropRight: Int = 0
    private var cropBottom: Int = 0

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var animTime: Float = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        reloadCropFromPrefs()
    }

    fun reloadCropFromPrefs() {
        cropLeft = PrefsManager.getDisplayAreaLeft(0)
        cropTop = PrefsManager.getDisplayAreaTop(0)
        cropRight = PrefsManager.getDisplayAreaRight(surfaceWidth.takeIf { it > 0 } ?: 1920)
        cropBottom = PrefsManager.getDisplayAreaBottom(surfaceHeight.takeIf { it > 0 } ?: 1080)
        requestRender()
    }

    fun updateCropArea(left: Int, top: Int, right: Int, bottom: Int) {
        cropLeft = left
        cropTop = top
        cropRight = right
        cropBottom = bottom
        requestRender()
    }

    fun getCropRect(): Rect {
        return Rect(cropLeft, cropTop, cropRight, cropBottom)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        // 如果 Prefs 里的 right/bottom 尚未设定，按默认的全屏边界初始化
        val savedRight = PrefsManager.getDisplayAreaRight(0)
        val savedBottom = PrefsManager.getDisplayAreaBottom(0)

        if (savedRight <= 0 || savedRight > surfaceWidth) cropRight = surfaceWidth
        if (savedBottom <= 0 || savedBottom > surfaceHeight) cropBottom = surfaceHeight

        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        animTime += 0.02f

        // 1. 清空背景为深暗底色（表示非有效显示区域/黑边区）
        GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        // 计算 OpenGL Scissor/Viewport 坐标系 (OpenGL 坐标原点在左下角)
        val validLeft = cropLeft.coerceIn(0, surfaceWidth)
        val validTop = cropTop.coerceIn(0, surfaceHeight)
        val validRight = cropRight.coerceIn(validLeft + 10, surfaceWidth)
        val validBottom = cropBottom.coerceIn(validTop + 10, surfaceHeight)

        val cropW = validRight - validLeft
        val cropH = validBottom - validTop
        val glY = surfaceHeight - validBottom // OpenGL Y 坐标转换

        // 2. 开启 OpenGL Scissor / Viewport 裁剪管线
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(validLeft, glY, cropW, cropH)
        GLES20.glViewport(validLeft, glY, cropW, cropH)

        // 3. 在有效区域内渲染实时预览画面（高质感渐变与动态效果）
        val r = (Math.sin(animTime.toDouble()) * 0.15 + 0.15).toFloat()
        val g = (Math.cos(animTime.toDouble() * 0.7) * 0.15 + 0.2).toFloat()
        val b = (Math.sin(animTime.toDouble() * 1.3) * 0.2 + 0.35).toFloat()

        GLES20.glClearColor(r, g, b, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 禁用 Scissor 恢复默认状态
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}

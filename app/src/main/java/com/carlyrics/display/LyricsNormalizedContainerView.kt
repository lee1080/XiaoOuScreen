package com.carlyrics.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.carlyrics.model.LyricLine
import com.carlyrics.skin.LyricsSkin
import com.carlyrics.skin.SkinFactory
import com.carlyrics.skin.SkinType
import com.carlyrics.util.PrefsManager

/**
 * 跨设备 Density 归一化自适应歌词容器
 *
 * 核心机制：
 * 1. 内部子视图永远以 1920x384 黄金基准分辨率进行测量与排版 (MeasureSpec.EXACTLY)
 * 2. 依据宿主物理屏幕的实际宽高 (W, H)，采用等比自适应矩阵缩放 (Scale Matrix) 居中呈现
 * 3. 彻底免除手动输入宽高像素以及物理仪表盘校准步骤
 * 4. 提供 pause / resume 生命周期调度，非展示态下彻底停止内部动画与跑马灯，保障车机零额外开销高效运行
 */
class LyricsNormalizedContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val LOGICAL_WIDTH = 1920f
        const val LOGICAL_HEIGHT = 384f
        const val SAFE_MARGIN_PX = 130f
        const val SAFE_RIGHT_START_PX = 1790f // 1920 - 130
        private const val LOGICAL_ASPECT = LOGICAL_WIDTH / LOGICAL_HEIGHT
    }

    private val normalizedContext = com.carlyrics.util.DensityNormalization.getNormalizedContext(context)
    private val contentFrame: FrameLayout
    private var currentSkin: LyricsSkin? = null

    // 左右两侧 130px 物理安全暗角渐变 (与主页时钟及 3D 档位舞台 100% 绝对一致)
    private val vignetteLeftPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, SAFE_MARGIN_PX, 0f,
            Color.BLACK, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }
    private val vignetteRightPaint = Paint().apply {
        shader = LinearGradient(
            SAFE_RIGHT_START_PX, 0f, LOGICAL_WIDTH, 0f,
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )
    }
    private val vignetteLeftRect = RectF(0f, 0f, SAFE_MARGIN_PX, LOGICAL_HEIGHT)
    private val vignetteRightRect = RectF(SAFE_RIGHT_START_PX, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)

    private var calculatedScale = 1.0f
    private var calculatedOffsetX = 0f
    private var calculatedOffsetY = 0f

    private var isPaused = false

    var xiaoOuAvoidanceEnabled: Boolean = false
        set(value) {
            field = value
            currentSkin?.xiaoOuAvoidanceEnabled = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false

        contentFrame = FrameLayout(normalizedContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        addView(contentFrame, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 加载当前皮肤
        reloadSkin(PrefsManager.getSkinType())
    }

    /**
     * 重新加载或切换皮肤
     */
    fun reloadSkin(skinType: SkinType) {
        val oldSkin = currentSkin
        val newSkin = SkinFactory.create(skinType, normalizedContext)

        // 迁移现有参数
        newSkin.fontSizeSp = PrefsManager.getFontSizeSp()
        newSkin.splitRatio = PrefsManager.getSplitRatio()
        newSkin.lyricsTextAlignment = PrefsManager.getLyricsAlignment()
        newSkin.marqueeEnabled = PrefsManager.isMarqueeEnabled()
        newSkin.scaleFactor = PrefsManager.getDpiScale()
        newSkin.infoFontScale = PrefsManager.getInfoFontScale()
        newSkin.lyricsOffsetMs = PrefsManager.lyricsOffsetMs
        newSkin.xiaoOuAvoidanceEnabled = xiaoOuAvoidanceEnabled

        contentFrame.removeAllViews()
        contentFrame.addView(
            newSkin as View,
            LayoutParams(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt())
        )
        currentSkin = newSkin
    }

    fun getSkin(): LyricsSkin? = currentSkin

    fun setTrackInfo(title: String, artist: String) {
        currentSkin?.setTrackInfo(title, artist)
    }

    fun setCoverUrl(url: String?) {
        currentSkin?.setCoverUrl(url)
    }

    fun setLyrics(lines: List<LyricLine>) {
        currentSkin?.setLyrics(lines)
    }

    fun updateProgress(positionMs: Long) {
        if (!isPaused) {
            currentSkin?.updateProgress(positionMs)
        }
    }

    /**
     * 深度休眠：暂停跑马灯、清空不必要的动画刷新，节省车机资源
     */
    fun pause() {
        if (isPaused) return
        isPaused = true
        (currentSkin as? View)?.visibility = View.INVISIBLE
    }

    /**
     * 唤醒激活：恢复跑马灯与动画渲染
     */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        (currentSkin as? View)?.visibility = View.VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)

        if (parentW > 0 && parentH > 0) {
            val currentAspect = parentW.toFloat() / parentH.toFloat()

            if (currentAspect > LOGICAL_ASPECT) {
                calculatedScale = parentH.toFloat() / LOGICAL_HEIGHT
                calculatedOffsetX = (parentW.toFloat() - LOGICAL_WIDTH * calculatedScale) / 2f
                calculatedOffsetY = 0f
            } else {
                calculatedScale = parentW.toFloat() / LOGICAL_WIDTH
                calculatedOffsetX = 0f
                calculatedOffsetY = (parentH.toFloat() - LOGICAL_HEIGHT * calculatedScale) / 2f
            }

            // 内部 contentFrame 永远以基准 1920x384 进行测量排版
            val childWSpec = MeasureSpec.makeMeasureSpec(LOGICAL_WIDTH.toInt(), MeasureSpec.EXACTLY)
            val childHSpec = MeasureSpec.makeMeasureSpec(LOGICAL_HEIGHT.toInt(), MeasureSpec.EXACTLY)
            contentFrame.measure(childWSpec, childHSpec)

            // 应用硬件加速缩放变换
            contentFrame.pivotX = 0f
            contentFrame.pivotY = 0f
            contentFrame.scaleX = calculatedScale
            contentFrame.scaleY = calculatedScale

            setMeasuredDimension(parentW, parentH)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val l = calculatedOffsetX.toInt()
        val t = calculatedOffsetY.toInt()
        // 布局在计算出的偏移位置上，结合 scale 居中缩放
        contentFrame.layout(l, t, (l + LOGICAL_WIDTH).toInt(), (t + LOGICAL_HEIGHT).toInt())
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 绘制左右两侧 130px 物理安全暗角渐变，确保歌词视图与主屏时钟无缝一致
        canvas.save()
        canvas.translate(calculatedOffsetX, calculatedOffsetY)
        canvas.scale(calculatedScale, calculatedScale)
        canvas.drawRect(vignetteLeftRect, vignetteLeftPaint)
        canvas.drawRect(vignetteRightRect, vignetteRightPaint)
        canvas.restore()
    }

    /**
     * 将歌词内容绘制到指定的严格 1920×384 Canvas 上
     */
    fun drawStrict1920(canvas: Canvas) {
        contentFrame.draw(canvas)
    }

    /**
     * 截取纯净严格 1920×384 歌词快照
     */
    fun captureStrict1920Snapshot(): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            drawStrict1920(c)
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}

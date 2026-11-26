package com.carlyrics.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.ui.BurnInProtection
import com.carlyrics.model.LyricLine
import com.carlyrics.skin.LyricsSkin
import com.carlyrics.skin.SkinFactory
import com.carlyrics.skin.SkinType
import com.carlyrics.util.PrefsManager

/**
 * 跨设备 Density 归一化自适应歌词容器 (100% 纯原生车规版)
 *
 * 核心机制：
 * 1. 内部子视图永远以 1920x384 黄金基准分辨率进行测量与排版 (MeasureSpec.EXACTLY)
 * 2. 依据宿主物理屏幕/外层容器的实际宽高 (W, H)，采用等比自适应矩阵缩放 (Scale Matrix) 居中呈现
 * 3. 左右两侧 130px 物理安全暗角纯黑线性渐变，全局设计铁律
 * 4. 纯净生产视图架构：悬浮窗/投屏绝不绘制虚拟指示器形象，形象指示器专供设置预览使用
 * 5. 自动保留并迁移音轨标题、歌手、封面、歌词及播放进度，换肤与参数刷新零跳变
 * 6. 提供 pause / resume 生命周期调度，非展示态下彻底停止内部动画与跑马灯
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
    var currentSkinType: SkinType? = null
        private set

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

    // 小欧 3D 虚拟形象指示器 (懒加载：仅当设置预览显式开启 showAvatarIndicator 时才创建 View 与解码 PNG，避免生产悬浮窗常驻解码半身图)
    private var xiaoOuIndicatorCreated = false
    private val xiaoOuIndicatorView: FrameLayout by lazy(LazyThreadSafetyMode.NONE) {
        xiaoOuIndicatorCreated = true
        FrameLayout(normalizedContext).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false

            val avatarImg = ImageView(normalizedContext).apply {
                setImageResource(R.drawable.xiaoou_3d_avatar_half)
                scaleType = ImageView.ScaleType.FIT_END
            }

            val avatarLp = LayoutParams(358, 384).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = 136
            }
            addView(avatarImg, avatarLp)
        }
    }

    private var calculatedScale = 1.0f
    private var calculatedOffsetX = 0f
    private var calculatedOffsetY = 0f

    private var isPaused = false
    var onBackgroundUpdated: (() -> Unit)? = null
    private val bgSyncPulseRunnable = Runnable { onBackgroundUpdated?.invoke() }

    private fun scheduleBackgroundSyncPulses(hasNetworkUrl: Boolean) {
        onBackgroundUpdated?.invoke()
        removeCallbacks(bgSyncPulseRunnable)
        postDelayed(bgSyncPulseRunnable, 120L)
        if (hasNetworkUrl) {
            postDelayed({ onBackgroundUpdated?.invoke() }, 420L)
        }
    }

    // 防烧屏微距像素漂移低频心跳（生产常亮投屏模式生效，即使暂停歌曲也能持续缓慢防残影）
    private var isBurnInTickerRunning = false
    private val burnInShiftRunnable = object : Runnable {
        override fun run() {
            if (showAvatarIndicator || isPaused || !isAttachedToWindow || visibility != VISIBLE) {
                isBurnInTickerRunning = false
                return
            }
            invalidate()
            postDelayed(this, BurnInProtection.STATIC_REFRESH_INTERVAL_MS)
        }
    }

    private fun updateBurnInTicker() {
        val shouldRun = !showAvatarIndicator && !isPaused && isAttachedToWindow && visibility == VISIBLE
        if (shouldRun && !isBurnInTickerRunning) {
            isBurnInTickerRunning = true
            removeCallbacks(burnInShiftRunnable)
            postDelayed(burnInShiftRunnable, BurnInProtection.STATIC_REFRESH_INTERVAL_MS)
        } else if (!shouldRun && isBurnInTickerRunning) {
            isBurnInTickerRunning = false
            removeCallbacks(burnInShiftRunnable)
        }
    }

    // 状态数据持久留存，换肤与运行时刷新平滑迁移
    private var savedTitle: String = ""
    private var savedArtist: String = ""
    private var savedCoverUrl: String? = null
    private var savedCoverBitmap: Bitmap? = null
    private var savedLyricLines: List<LyricLine> = emptyList()
    private var savedProgressMs: Long = 0L

    /**
     * 是否允许在容器内展示小欧示意图 (默认 false，生产悬浮窗绝对不画，仅设置预览开启)
     */
    var showAvatarIndicator: Boolean = false
        set(value) {
            field = value
            if (value) ensureSkinCreated()
            updateAvatarVisibility()
            updateBurnInTicker()
        }

    var xiaoOuAvoidanceEnabled: Boolean = false
        set(value) {
            field = value
            currentSkin?.xiaoOuAvoidanceEnabled = value
            updateAvatarVisibility()
        }

    private fun updateAvatarVisibility() {
        if (xiaoOuAvoidanceEnabled && showAvatarIndicator) {
            val av = xiaoOuIndicatorView
            if (av.parent == null) {
                contentFrame.addView(
                    av,
                    LayoutParams(LyricsSkin.XIAOOU_AVATAR_WIDTH_PX, LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.END
                    }
                )
            }
            av.visibility = View.VISIBLE
        } else if (xiaoOuIndicatorCreated) {
            xiaoOuIndicatorView.visibility = View.GONE
        }
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
        addView(contentFrame, LayoutParams(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt()))

        xiaoOuAvoidanceEnabled = PrefsManager.isXiaoOuAvoidanceEnabled()

        // 记录默认皮肤类型；若容器当前不可见或处于暂停态，延迟到激活展现时再实例化视图，实现 0 视图冗余与 0 后台空转
        currentSkinType = PrefsManager.getSkinType()
        if (visibility == View.VISIBLE && !isPaused) {
            reloadSkin(currentSkinType!!, force = true)
        }
    }

    fun ensureSkinCreated() {
        val targetType = currentSkinType ?: PrefsManager.getSkinType()
        if (currentSkin == null || currentSkinType != targetType) {
            reloadSkin(targetType, force = true)
        }
    }

    /**
     * 重新加载或切换皮肤，100% 迁移所有现有参数与音轨数据
     */
    fun reloadSkin(skinType: SkinType, force: Boolean = false) {
        if (!force && currentSkin != null && currentSkinType == skinType) {
            return
        }
        currentSkinType = skinType
        // 若容器正处于隐藏或休眠状态（且非预览界面强制加载），仅记录皮肤类型与数据，避免在独立全屏歌词关闭时实例化上百个 View 与动画器
        if (!force && (isPaused || visibility != View.VISIBLE) && !showAvatarIndicator && currentSkin == null) {
            return
        }
        val oldSkin = currentSkin
        try { oldSkin?.onDestroy() } catch (_: Exception) {}

        val newSkin = SkinFactory.create(skinType, normalizedContext)

        // 迁移现有样式排版参数
        newSkin.fontSizeSp = PrefsManager.getFontSizeSp()
        newSkin.splitRatio = PrefsManager.getSplitRatio()
        newSkin.lyricsTextAlignment = PrefsManager.getLyricsAlignment()
        newSkin.marqueeEnabled = PrefsManager.isMarqueeEnabled()
        newSkin.scaleFactor = PrefsManager.getDpiScale()
        newSkin.infoFontScale = PrefsManager.getInfoFontScale()
        newSkin.lyricsOffsetMs = PrefsManager.lyricsOffsetMs
        newSkin.xiaoOuAvoidanceEnabled = xiaoOuAvoidanceEnabled

        // 迁移音轨歌曲与播放状态数据
        if (savedTitle.isNotEmpty() || savedArtist.isNotEmpty()) {
            newSkin.setTrackInfo(savedTitle, savedArtist)
        }
        if (savedCoverBitmap != null) {
            newSkin.setCoverBitmap(savedCoverBitmap)
        } else if (savedCoverUrl != null) {
            newSkin.setCoverUrl(savedCoverUrl)
        }
        if (savedLyricLines.isNotEmpty()) {
            newSkin.setLyrics(savedLyricLines)
        }
        if (savedProgressMs > 0L) {
            newSkin.updateProgress(savedProgressMs)
        }

        contentFrame.removeAllViews()
        contentFrame.addView(
            newSkin as View,
            LayoutParams(LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt())
        )

        if (xiaoOuIndicatorCreated || (xiaoOuAvoidanceEnabled && showAvatarIndicator)) {
            val av = xiaoOuIndicatorView
            if (av.parent != null) {
                (av.parent as? android.view.ViewGroup)?.removeView(av)
            }
            contentFrame.addView(
                av,
                LayoutParams(LyricsSkin.XIAOOU_AVATAR_WIDTH_PX, LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                }
            )
            updateAvatarVisibility()
        }

        currentSkin = newSkin
        if (isPaused || visibility != View.VISIBLE) {
            newSkin.onPauseAnimations()
        }
        scheduleBackgroundSyncPulses(hasNetworkUrl = !savedCoverUrl.isNullOrBlank())
    }

    fun getSkin(): LyricsSkin? = currentSkin

    fun setTrackInfo(title: String, artist: String) {
        savedTitle = title
        savedArtist = artist
        currentSkin?.setTrackInfo(title, artist)
    }

    fun setCoverUrl(url: String?) {
        savedCoverUrl = url
        currentSkin?.setCoverUrl(url)
        if (currentSkin != null && !isPaused && visibility == View.VISIBLE) {
            scheduleBackgroundSyncPulses(hasNetworkUrl = !url.isNullOrBlank())
        }
    }

    fun setCoverBitmap(bitmap: Bitmap?) {
        savedCoverBitmap = bitmap
        currentSkin?.setCoverBitmap(bitmap)
        if (currentSkin != null && !isPaused && visibility == View.VISIBLE) {
            scheduleBackgroundSyncPulses(hasNetworkUrl = false)
        }
    }

    fun setLyrics(lines: List<LyricLine>) {
        savedLyricLines = lines
        currentSkin?.setLyrics(lines)
    }

    fun updateProgress(positionMs: Long) {
        savedProgressMs = positionMs
        if (!isPaused && visibility == View.VISIBLE) {
            currentSkin?.updateProgress(positionMs)
        }
    }

    /**
     * 深度休眠：暂停跑马灯、停止皮肤动效，节省车机资源
     */
    fun pause() {
        if (isPaused) return
        isPaused = true
        updateBurnInTicker()
        currentSkin?.onPauseAnimations()
        (currentSkin as? View)?.visibility = View.INVISIBLE
    }

    /**
     * 彻底销毁释放子视图与皮肤动画
     */
    fun destroy() {
        pause()
        removeCallbacks(burnInShiftRunnable)
        currentSkin?.onDestroy()
        removeAllViews()
        currentSkin = null
    }

    /**
     * 唤醒激活：按需懒加载皮肤视图，并恢复跑马灯与动画渲染
     */
    fun resume() {
        if (!isPaused && currentSkin != null) {
            currentSkin?.onResumeAnimations()
            return
        }
        isPaused = false
        ensureSkinCreated()
        (currentSkin as? View)?.visibility = View.VISIBLE
        currentSkin?.onResumeAnimations()
        updateBurnInTicker()
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

            // 内部 contentFrame 永远以黄金基准 1920x384 进行测量排版
            val childWSpec = MeasureSpec.makeMeasureSpec(LOGICAL_WIDTH.toInt(), MeasureSpec.EXACTLY)
            val childHSpec = MeasureSpec.makeMeasureSpec(LOGICAL_HEIGHT.toInt(), MeasureSpec.EXACTLY)
            contentFrame.measure(childWSpec, childHSpec)

            setMeasuredDimension(parentW, parentH)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // contentFrame 永远固定在 (0, 0, 1920, 384)，自适应缩放与居中完全由 dispatchDraw 矩阵在 GPU Canvas 层无损接管
        contentFrame.layout(0, 0, LOGICAL_WIDTH.toInt(), LOGICAL_HEIGHT.toInt())
    }

    override fun dispatchDraw(canvas: Canvas) {
        val count = canvas.save()
        canvas.translate(calculatedOffsetX, calculatedOffsetY)
        canvas.scale(calculatedScale, calculatedScale)
        canvas.clipRect(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        if (!showAvatarIndicator) {
            // 生产常亮投屏：应用全局同步防烧屏微距漂移 + 1.6% 等比过扫描防止四边露黑缝
            val nowShiftMs = SystemClock.uptimeMillis()
            val shiftX = BurnInProtection.getShiftX(nowShiftMs)
            val shiftY = BurnInProtection.getShiftY(nowShiftMs)
            canvas.save()
            canvas.translate(shiftX, shiftY)
            canvas.scale(
                BurnInProtection.FULLSCREEN_OVERSCAN_SCALE,
                BurnInProtection.FULLSCREEN_OVERSCAN_SCALE,
                LOGICAL_WIDTH / 2f,
                LOGICAL_HEIGHT / 2f
            )
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
        // 绘制左右两侧 130px 物理安全暗角渐变，确保歌词视图与主屏时钟无缝一致
        canvas.drawRect(vignetteLeftRect, vignetteLeftPaint)
        canvas.drawRect(vignetteRightRect, vignetteRightPaint)
        canvas.restoreToCount(count)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (calculatedScale <= 0f) return super.dispatchTouchEvent(ev)
        val transformed = android.view.MotionEvent.obtain(ev)
        transformed.offsetLocation(-calculatedOffsetX, -calculatedOffsetY)
        transformed.setLocation(transformed.x / calculatedScale, transformed.y / calculatedScale)
        val handled = super.dispatchTouchEvent(transformed)
        transformed.recycle()
        return handled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBurnInTicker()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateBurnInTicker()
    }

    override fun onDetachedFromWindow() {
        isBurnInTickerRunning = false
        removeCallbacks(burnInShiftRunnable)
        removeCallbacks(bgSyncPulseRunnable)
        onBackgroundUpdated = null
        pause()
        super.onDetachedFromWindow()
    }

    /**
     * 将歌词内容绘制到指定的严格 1920×384 Canvas 上
     */
    fun drawStrict1920(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, 0f, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        // 还原容器纯黑底色，确保半透明渐变/封面皮肤与实屏像素一致
        canvas.drawColor(Color.BLACK)
        if (!showAvatarIndicator) {
            val nowShiftMs = SystemClock.uptimeMillis()
            val shiftX = BurnInProtection.getShiftX(nowShiftMs)
            val shiftY = BurnInProtection.getShiftY(nowShiftMs)
            canvas.translate(shiftX, shiftY)
            canvas.scale(
                BurnInProtection.FULLSCREEN_OVERSCAN_SCALE,
                BurnInProtection.FULLSCREEN_OVERSCAN_SCALE,
                LOGICAL_WIDTH / 2f,
                LOGICAL_HEIGHT / 2f
            )
            contentFrame.draw(canvas)
        } else {
            contentFrame.draw(canvas)
        }
        canvas.restore()
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

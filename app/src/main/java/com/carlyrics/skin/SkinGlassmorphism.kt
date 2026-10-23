package com.carlyrics.skin

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.carlyrics.model.LyricLine
import com.carlyrics.util.LyricsCacheManager

/**
 * Skin 1 - 毛玻璃质感 (Glassmorphism)
 * HTML参考: design1_glassmorphism.html — 1:1还原
 *
 * 还原点:
 * - 封面图片高斯模糊(scale缩小8x模拟模糊) + brightness(0.5) + saturate(1.2) + pulse缩放动画
 * - 容器: 圆角24dp, rgba(255,255,255,0.05)半透明底, 1px白色半透明边框
 * - 布局: 左侧封面(140x140圆角20dp) + 标题(26sp/800/textShadow) + 歌手(16sp/600/rgba白70%)
 * - 渐变分割线: transparent→白20%→transparent
 * - 歌词: 当前行34sp/800, LinearGradient #fff→#dcdcdc做textShader, shadow 0 0 20px rgba白40%
 * - 非当前行: 20sp/400, rgba白40%, scale(0.95)
 * - 歌词容器渐变遮罩边缘淡出
 * - 关闭按钮: 左上角圆形半透明
 */
class SkinGlassmorphism(context: Context) : LyricsSkin(context) {

    private lateinit var bgImageView: ImageView
    private var pulseAnimator: ValueAnimator? = null
    private val container: FrameLayout
    private val coverImage: ImageView
    private val titleText: TextView
    private val artistText: TextView
    private val divider: View
    private val lyricsScrollView: ScrollView
    private val lyricsContainer: LinearLayout
    private val noLyricsText: GradientTextView
    // 左面板引用（用于水平偏移调整宽度）+ 缩放基准尺寸
    private val songInfoPanel: LinearLayout
    private val coverFrame: FrameLayout
    private val contentRowLayout: LinearLayout
    // 两栏架构容器
    private lateinit var leftPanel: FrameLayout
    private lateinit var lyricsArea: FrameLayout
    private val baseCoverSizeDp = 240
    private val baseCoverCornerDp = 16
    private val baseTitleSp = 18f
    private val baseArtistSp = 14f
    private val basePanelPaddingDp = 12
    private val baseTextInfoPaddingLeftDp = 12
    private val baseArtistTopMarginDp = 6

    private var lyLineViews: List<GradientTextView> = emptyList()
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1

    companion object {
        private const val CURRENT_SCALE = 1.7f
        private val TITLE_COLOR = Color.WHITE
        private val ARTIST_COLOR = Color.parseColor("#B3FFFFFF")
        private val LYRIC_NORMAL_COLOR = Color.parseColor("#66FFFFFF")
        private val LYRIC_GRADIENT_START = Color.parseColor("#FFFFFF")
        private val LYRIC_GRADIENT_END = Color.parseColor("#DCDCDC")
        private val CURRENT_GLOW = Color.parseColor("#66FFFFFF")
    }

    init {
        bgImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.bg_glass)
        }

        container = FrameLayout(context).apply {
            val gd = GradientDrawable().apply {
                setColor(Color.parseColor("#B31A1A1A"))
            }
            background = gd
        }

        coverImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        }
        coverFrame = FrameLayout(context).apply {
            addView(coverImage, FrameLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)))
            val gd = GradientDrawable().apply { cornerRadius = dp(baseCoverCornerDp).toFloat() }
            background = gd
            clipToOutline = true
        }

        titleText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TITLE_COLOR)
            setShadowLayer(dp(6).toFloat(), 0f, dp(4).toFloat(), Color.parseColor("#4D000000"))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            letterSpacing = -0.01f
            includeFontPadding = false
        }
        artistText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, 600)
            setTextColor(ARTIST_COLOR)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            letterSpacing = 0.02f
            includeFontPadding = false
        }

        divider = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#33FFFFFF"), Color.TRANSPARENT))
        }

        lyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            isFillViewport = true
        }
        lyricsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setPadding(0, dp(60), 0, dp(60))
            clipChildren = false; clipToPadding = false
        }
        noLyricsText = GradientTextView(context).apply {
            text = "等待播放音乐..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setShadowLayer(dp(12).toFloat(), 0f, 0f, CURRENT_GLOW)
            gradientStart = LYRIC_GRADIENT_START
            gradientEnd = LYRIC_GRADIENT_END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }


        // 组装 —— 左面板：封面在左，歌名/歌手在右（水平并排）
        songInfoPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(basePanelPaddingDp), dp(basePanelPaddingDp), dp(basePanelPaddingDp), dp(basePanelPaddingDp))
        }
        // 左侧封面（圆形裁剪）
        songInfoPanel.addView(coverFrame, LinearLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        // 右侧：歌名 + 歌手（垂直排列）
        val textInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(baseTextInfoPaddingLeftDp), 0, 0, 0)
        }
        textInfo.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        textInfo.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(baseArtistTopMarginDp)
        })
        songInfoPanel.addView(textInfo, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val lyricsFrame = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
        }
        lyricsContainer.addView(noLyricsText)
        lyricsScrollView.addView(lyricsContainer)
        lyricsFrame.addView(lyricsScrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        contentRowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // 左面板容器（定宽定高，封面绝对定锚）
        leftPanel = FrameLayout(context)
        leftPanel.addView(songInfoPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        contentRowLayout.addView(leftPanel, LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.MATCH_PARENT))
        contentRowLayout.addView(divider, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))

        // 歌词区容器（clipChildren=true 锁定避让边界，内部元素允许展开）
        lyricsArea = FrameLayout(context).apply {
            clipChildren = true; clipToPadding = true
        }
        lyricsArea.addView(lyricsFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        contentRowLayout.addView(lyricsArea, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        container.addView(contentRowLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        addView(bgImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
        refreshCoverLayout()
        refreshInfoFontSize()
        startPulseAnimation()
    }

    override fun setTrackInfo(title: String, artist: String) {
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(title) ?: run { titleText.text = title }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
    }

    override fun setCoverUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            coverImage.setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        } else {
            LyricsCacheManager.loadCoverInto(context, url, coverImage)
        }
    }

    override fun setLyrics(lines: List<LyricLine>) {
        if (lines == lyricLines && lines.isNotEmpty() && lyLineViews.isNotEmpty()) return
        lyricLines = lines; lyricsContainer.removeAllViews()
        if (lines.isEmpty()) {
            lyricsContainer.setPadding(0, 0, 0, 0)
            lyricsContainer.gravity = Gravity.CENTER
            lyricsContainer.addView(noLyricsText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
            lyLineViews = emptyList()
            return
        }
        lyricsContainer.setPadding(0, dp(60), 0, dp(60))
        lyricsContainer.gravity = Gravity.NO_GRAVITY
        val align = lyricsTextAlignment
        lyLineViews = lines.map {
            val wrapper = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = GradientTextView(context).apply {
                text = it.text; setTextColor(LYRIC_NORMAL_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp); typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.01f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                gravity = align; maxLines = if (marqueeEnabled) 1 else Integer.MAX_VALUE; ellipsize = null
                layoutParams = FrameLayout.LayoutParams(
                    if (marqueeEnabled) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (marqueeEnabled) Gravity.START or Gravity.TOP
                    else lyricsTextAlignment or Gravity.TOP
                )
            }
            wrapper.addView(tv)
            lyricsContainer.addView(wrapper)
            tv
        }
        currentIndex = -1
    }

    override fun updateProgress(positionMs: Long): Int {
        if (lyricLines.isEmpty()) return -1
        var target = 0
        val effectiveMs = positionMs + lyricsOffsetMs
        lastProgressMs = effectiveMs
        for (i in lyricLines.indices) {
            if (lyricLines[i].timestamp <= effectiveMs) target = i else break
        }
        if (target == currentIndex) {
            if (currentIndex in lyLineViews.indices) {
                val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
                KaraokeHelper.applyKaraoke(
                    textView = lyLineViews[currentIndex],
                    line = lyricLines[currentIndex],
                    nextLineTimestamp = nextTs,
                    currentPositionMs = effectiveMs,
                    style = KaraokeHelper.GLASSMORPHISM_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return target
        }

        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            KaraokeHelper.resetKaraoke(this, lyricLines[currentIndex].text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp); setTextColor(LYRIC_NORMAL_COLOR)
            gradientStart = null; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f
            clearTextShader(); setShadowLayer(0f, 0f, 0f, 0)
        }
        currentIndex = target
        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.015f
            setTextColor(Color.WHITE)
            setShadowLayer(0f, 0f, 0f, 0)
            clearTextShader()
            val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
            KaraokeHelper.applyKaraoke(
                textView = this,
                line = lyricLines[currentIndex],
                nextLineTimestamp = nextTs,
                currentPositionMs = effectiveMs,
                style = KaraokeHelper.GLASSMORPHISM_STYLE,
                marqueeEnabled = marqueeEnabled
            )
            scrollToCenter(currentIndex)
        }
        return currentIndex
    }

    override fun setPlaceholderText(text: String) {
        noLyricsText.text = text
    }

    override fun onFontSizeChanged(sp: Float) {
        noLyricsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * CURRENT_SCALE)
        lyLineViews.forEachIndexed { i, tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) sp * CURRENT_SCALE else sp)
        }
    }

    override fun onSplitRatioChanged() {
        super.onSplitRatioChanged()
        updatePanelLayouts()
        // 面板比例变化后立即基于新视口宽度重新驱动卡拉OK与跑马灯
        cancelMarquee()
        if (currentIndex in lyricLines.indices) {
            updateProgress(lastProgressMs)
        }
    }

    override fun onLyricsTextAlignmentChanged() {
        lyricsContainer.gravity = lyricsTextAlignment
        lyLineViews.forEach {
            it.gravity = lyricsTextAlignment
            (it.layoutParams as FrameLayout.LayoutParams).gravity = lyricsTextAlignment or Gravity.TOP
        }
        lyricsContainer.requestLayout()
    }

    override fun onMarqueeEnabledChanged() {
        val ml = if (marqueeEnabled) 1 else Integer.MAX_VALUE
        val lpw = if (marqueeEnabled) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        val grav = if (marqueeEnabled) Gravity.START or Gravity.TOP else lyricsTextAlignment or Gravity.TOP
        lyLineViews.forEach {
            it.setHorizontallyScrolling(marqueeEnabled)
            it.maxLines = ml
            it.layoutParams.width = lpw
            if (!marqueeEnabled) {
                it.translationX = 0f
            }
            (it.layoutParams as FrameLayout.LayoutParams).gravity = grav
        }
        lyricsContainer.requestLayout()
        if (currentIndex in lyricLines.indices) {
            updateProgress(lastProgressMs)
        }
    }

    override fun getContentRow(): View? = contentRowLayout
    override fun getLeftPanel(): View? = leftPanel
    override fun getLyricsArea(): View? = lyricsArea

    private fun refreshInfoFontSize() {
        val effectiveScale = scaleFactor * infoFontScale * BASE_INFO_FONT_SCALE
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSp * effectiveScale)
        artistText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseArtistSp * effectiveScale)
    }

    private fun refreshCoverLayout() {
        val scale = scaleFactor * BASE_DISPLAY_SCALE
        val coverSz = dp((baseCoverSizeDp * scale).toInt())
        (coverImage.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = coverSz
            height = coverSz
        }
        (coverFrame.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = coverSz
            height = coverSz
        }
        (coverFrame.background as? GradientDrawable)?.cornerRadius = dp((baseCoverCornerDp * scale).toInt()).toFloat()
        coverFrame.requestLayout()

        // 面板内边距
        songInfoPanel.setPadding(
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt())
        )
        // 文字信息区 padding
        (songInfoPanel.getChildAt(1) as? ViewGroup)?.setPadding(
            dp((baseTextInfoPaddingLeftDp * scale).toInt()), 0, 0, 0
        )
        // artist topMargin
        val artistLp = artistText.layoutParams as? LinearLayout.LayoutParams
        artistLp?.topMargin = dp((baseArtistTopMarginDp * scale).toInt())
        songInfoPanel.requestLayout()
    }

    override fun onScaleChanged(scale: Float) {
        refreshCoverLayout()
        refreshInfoFontSize()
    }

    override fun onInfoFontScaleChanged(scale: Float) {
        refreshInfoFontSize()
    }

    override fun onDestroy() { pulseAnimator?.cancel() }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.02f).apply {
            duration = 15000; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { val s = it.animatedValue as Float; bgImageView.scaleX = s; bgImageView.scaleY = s }
            start()
        }
    }

    private fun scrollToCenter(index: Int) {
        val view = lyLineViews.getOrNull(index) ?: return
        view.post {
            val wrapper = view.parent as View
            val sy = wrapper.top - lyricsScrollView.height / 2 + wrapper.height / 2
            lyricsScrollView.smoothScrollTo(0, sy.coerceAtLeast(0))
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshCoverLayout()
    }

    // ---- 内部类: 渐变文字TextView (零 GC 绘制) ----
    class GradientTextView(context: Context) : TextView(context) {
        var gradientStart: Int? = null
        var gradientEnd: Int? = null
        private var cachedShader: Shader? = null
        private var lastMeasuredW = 0f

        fun clearTextShader() {
            gradientStart = null
            gradientEnd = null
            cachedShader = null
            paint.shader = null
        }

        override fun onDraw(canvas: Canvas) {
            val gs = gradientStart
            val ge = gradientEnd
            if (gs != null && ge != null) {
                val currentW = paint.measureText(text.toString())
                if (cachedShader == null || currentW != lastMeasuredW) {
                    lastMeasuredW = currentW
                    if (currentW > 0f) {
                        cachedShader = LinearGradient(
                            0f, 0f, currentW, 0f,
                            intArrayOf(gs, ge), null, Shader.TileMode.CLAMP
                        )
                    }
                }
                paint.shader = cachedShader
            } else if (tag == null) {
                paint.shader = null
            }
            super.onDraw(canvas)
        }
    }
}

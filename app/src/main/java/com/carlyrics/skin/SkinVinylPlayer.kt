package com.carlyrics.skin

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.carlyrics.model.LyricLine
import com.carlyrics.util.LyricsCacheManager

/**
 * 唱片风格 (Sleeve Vinyl Style Skins)
 *
 * 设计说明：
 * 1. 抽取式黑胶唱片：
 *    - 顶层 (左): 正方形专辑封面矩形框 coverImage (加载歌曲封面/12dp圆角)
 *    - 底层 (右): 旋转的黑胶唱片 vinylDisc (抽出 55% 露出弧线，持续 360 度旋转)
 *    - 取消了原唱片机的唱针结构，造型经典优雅高级
 * 2. 律动频谱 (visualizerBars):
 *    - 7 根彩色律动柱采用 GPU 硬件缩放 scaleY (280ms~550ms 错落周期)，响应极速流畅，彻底解决卡顿与闪烁 Bug
 * 3. 超长歌名: 配合 BouncingTitleView 自适应往复流畅滚动，绝不截断或重叠
 */
class SkinVinylPlayer(context: Context) : LyricsSkin(context) {

    private val vinylDisc: VinylDiscView
    private val coverImage: ImageView
    private var spinAnimator: ValueAnimator? = null
    private val nowPlayingLabel: TextView
    private val titleText: TextView
    private val artistText: TextView
    private val visualizerBars: List<View>
    private val lyricsScrollView: ScrollView
    private val lyricsContainer: LinearLayout
    private val noLyricsText: GradientTV
    private val fadeMaskTop: View
    private val fadeMaskBottom: View

    // 架构容器
    private lateinit var vinylPanelView: FrameLayout
    private lateinit var leftPanel: FrameLayout
    private lateinit var lyricsArea: FrameLayout
    private lateinit var leftContainerView: LinearLayout
    private lateinit var contentRowLayout: LinearLayout

    private val baseVinylSizeDp = 130
    private val baseNowPlayingSp = 10f
    private val baseTitleSp = 20f
    private val baseArtistSp = 14f
    private val baseInfoColPaddingDp = intArrayOf(8, 12, 8, 12)
    private val baseInfoColTransX = 0f
    private val baseTitleTopMarginDp = 4
    private val baseArtistTopMarginDp = 2
    private val baseVizTopPaddingDp = 10
    private val baseBarWidthDp = 3
    private val baseBarSpacingDp = 3

    private var lyLineViews: List<GradientTV> = emptyList()
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1

    companion object {
        private const val CURRENT_SCALE = 1.76f
        private val GRADIENT_START = Color.parseColor("#E94560")
        private val GRADIENT_END = Color.parseColor("#4FACFE")
        private val BG_COLORS = intArrayOf(
            Color.parseColor("#16213E"), Color.parseColor("#0F3460"), Color.parseColor("#1A1A2E")
        )
    }

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, BG_COLORS)

        // 1. 黑胶唱片 (底层)
        vinylDisc = VinylDiscView(context)

        // 2. 专辑封面矩形框 (顶层)
        coverImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
        }

        // 信息
        nowPlayingLabel = createTV(10f, Typeface.DEFAULT_BOLD, Color.parseColor("#E94560")).apply {
            text = "♫ NOW PLAYING"; letterSpacing = 0.12f; gravity = Gravity.START
        }
        titleText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            this.typeface = Typeface.SERIF
            gravity = Gravity.START
            letterSpacing = -0.01f; includeFontPadding = false
        }
        artistText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT
            setTextColor(Color.parseColor("#80FFFFFF"))
            gravity = Gravity.START
            letterSpacing = 0.02f; includeFontPadding = false
        }

        // 彩色律动频谱柱
        visualizerBars = (0..6).map {
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#E94560"), Color.parseColor("#4FACFE"))
                )
            }
        }

        // 歌词
        lyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            isFillViewport = true
        }
        lyricsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setPadding(0, dp(50), 0, dp(50))
            clipChildren = false; clipToPadding = false
        }
        noLyricsText = GradientTV(context).apply {
            text = "等待播放音乐..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 1.35f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.parseColor("#E94560"))
            gradientStart = Color.parseColor("#FFFFFF")
            gradientEnd = Color.parseColor("#E94560")
        }

        fadeMaskTop = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E616213E"), Color.TRANSPARENT)
            )
        }
        fadeMaskBottom = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#E61A1A2E"))
            )
        }

        // 分隔线
        val sepLine = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#E94560"), Color.TRANSPARENT)
            )
        }

        // ---- 组装 ----
        contentRowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val root = contentRowLayout

        // 唱片与封面组合框 (Sleeve Vinyl Frame)
        vinylPanelView = FrameLayout(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipChildren = false; clipToPadding = false
        }
        // 底层：抽出旋转的黑胶唱片 (露出 55% 盘面)
        vinylPanelView.addView(vinylDisc, FrameLayout.LayoutParams(dp(baseVinylSizeDp), dp(baseVinylSizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            leftMargin = dp(48)
        })
        // 顶层：正方形专辑封面 (盖在左侧)
        vinylPanelView.addView(coverImage, FrameLayout.LayoutParams(dp(baseVinylSizeDp), dp(baseVinylSizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            leftMargin = 0
        })

        // 信息 + 频谱
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(baseInfoColPaddingDp[0]), dp(baseInfoColPaddingDp[1]),
                dp(baseInfoColPaddingDp[2]), dp(baseInfoColPaddingDp[3]))
            clipChildren = true; clipToPadding = true
        }
        infoCol.addView(nowPlayingLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        infoCol.addView(titleText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(baseTitleTopMarginDp) })
        infoCol.addView(artistText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(baseArtistTopMarginDp) })

        val vizBarHeight = dp(22)
        val vizRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(0, dp(baseVizTopPaddingDp), 0, 0)
        }
        visualizerBars.forEachIndexed { i, bar ->
            bar.pivotY = vizBarHeight.toFloat() // 底部缩放轴心
            vizRow.addView(bar, LinearLayout.LayoutParams(dp(baseBarWidthDp), vizBarHeight).apply {
                setMargins(0, 0, dp(baseBarSpacingDp), 0)
            })
        }
        val vizContainer = FrameLayout(context).apply {
            addView(vizRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            })
        }
        infoCol.addView(vizContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(26)
        ))

        // 左面板容器
        leftContainerView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = true; clipToPadding = true
            setPadding(dp(12), 0, dp(12), 0)
        }
        leftContainerView.addView(vinylPanelView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        })
        leftContainerView.addView(infoCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = dp(12)
        })

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        leftPanel = FrameLayout(context)
        leftPanel.addView(leftContainerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(leftPanel, LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.MATCH_PARENT))

        // 分隔线
        root.addView(sepLine, LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        })

        // 歌词区
        val lyricsFrame = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
        }
        lyricsContainer.addView(noLyricsText)
        lyricsScrollView.addView(lyricsContainer)
        lyricsFrame.addView(lyricsScrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        lyricsFrame.addView(fadeMaskTop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(25)
        ).apply { gravity = Gravity.TOP })
        lyricsFrame.addView(fadeMaskBottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(25)
        ).apply { gravity = Gravity.BOTTOM })

        lyricsArea = FrameLayout(context).apply {
            clipChildren = true; clipToPadding = true
        }
        lyricsArea.addView(lyricsFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(lyricsArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
        refreshInfoFontSize()
        startAllAnimations()
    }

    override fun setTrackInfo(title: String, artist: String) {
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(title) ?: run { titleText.text = title }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
    }

    override fun setCoverUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            coverImage.setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
            vinylDisc.setCoverBitmap(null)
        } else {
            LyricsCacheManager.loadCoverInto(context, url, coverImage)
            LyricsCacheManager.loadCoverBitmap(context, url) { bitmap ->
                bitmap?.let { vinylDisc.setCoverBitmap(it) }
            }
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
        lyricsContainer.setPadding(0, dp(50), 0, dp(50))
        lyricsContainer.gravity = Gravity.NO_GRAVITY
        lyLineViews = lines.map {
            val wrapper = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = GradientTV(context).apply {
                text = it.text; setTextColor(Color.parseColor("#FFFFFF"))
                alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                letterSpacing = 0.01f
                setPadding(dp(2), dp(8), dp(12), dp(8))
                maxLines = if (marqueeEnabled) 1 else Integer.MAX_VALUE; ellipsize = null
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
                    style = KaraokeHelper.VINYL_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return target
        }

        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            KaraokeHelper.resetKaraoke(this, lyricLines[currentIndex].text)
            alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            setTextColor(Color.parseColor("#FFFFFF")); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f
            gradientStart = null; clearTextShader()
        }
        currentIndex = target
        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = -0.015f
            clearTextShader()
            val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
            KaraokeHelper.applyKaraoke(
                textView = this,
                line = lyricLines[currentIndex],
                nextLineTimestamp = nextTs,
                currentPositionMs = effectiveMs,
                style = KaraokeHelper.VINYL_STYLE,
                marqueeEnabled = marqueeEnabled
            )
            scrollToCenter(currentIndex)
        }
        return currentIndex
    }

    override fun setPlaceholderText(text: String) { noLyricsText.text = text }

    override fun onFontSizeChanged(sp: Float) {
        lyLineViews.forEachIndexed { i, tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) sp * CURRENT_SCALE else sp)
        }
    }

    override fun onSplitRatioChanged() {
        super.onSplitRatioChanged()
        updatePanelLayouts()
        // 面板比例变化后基于新视口重新驱动卡拉OK与跑马灯
        cancelMarquee()
        if (currentIndex in lyricLines.indices) {
            updateProgress(lastProgressMs)
        }
    }

    override fun onLyricsTextAlignmentChanged() {
        lyricsContainer.gravity = lyricsTextAlignment
        val align = lyricsTextAlignment
        lyLineViews.forEach { tv ->
            val lp = tv.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (marqueeEnabled) Gravity.START or Gravity.TOP else align or Gravity.TOP
            tv.layoutParams = lp
            tv.gravity = align
        }
    }

    override fun onMarqueeEnabledChanged() {
        val ml = if (marqueeEnabled) 1 else Integer.MAX_VALUE
        val lpw = if (marqueeEnabled) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        lyLineViews.forEach {
            it.setHorizontallyScrolling(marqueeEnabled)
            it.maxLines = ml
            it.layoutParams.width = lpw
            if (!marqueeEnabled) {
                it.translationX = 0f
            }
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
        nowPlayingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseNowPlayingSp * effectiveScale)
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSp * effectiveScale)
        artistText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseArtistSp * effectiveScale)
    }

    override fun onScaleChanged(scale: Float) {
        val vinylSz = dp((baseVinylSizeDp * scale).toInt()).coerceAtMost(360)
        (vinylDisc.layoutParams as FrameLayout.LayoutParams).apply {
            width = vinylSz
            height = vinylSz
            leftMargin = dp((48 * scale).toInt())
        }
        (coverImage.layoutParams as FrameLayout.LayoutParams).apply {
            width = vinylSz
            height = vinylSz
        }

        refreshInfoFontSize()

        // infoCol (index 1)
        val infoCol = leftContainerView.getChildAt(1) as? ViewGroup ?: return
        infoCol.setPadding(
            dp((baseInfoColPaddingDp[0] * scale).toInt()),
            dp((baseInfoColPaddingDp[1] * scale).toInt()),
            dp((baseInfoColPaddingDp[2] * scale).toInt()),
            dp((baseInfoColPaddingDp[3] * scale).toInt())
        )
        infoCol.translationX = baseInfoColTransX * scale
        val titleLp = titleText.layoutParams as? LinearLayout.LayoutParams
        titleLp?.topMargin = dp((baseTitleTopMarginDp * scale).toInt())
        val artistLp = artistText.layoutParams as? LinearLayout.LayoutParams
        artistLp?.topMargin = dp((baseArtistTopMarginDp * scale).toInt())
        val vizRow = infoCol.getChildAt(3) as? ViewGroup
        vizRow?.setPadding(0, dp((baseVizTopPaddingDp * scale).toInt()), 0, 0)
        val vpLp = leftContainerView.getChildAt(0).layoutParams as? LinearLayout.LayoutParams
        vpLp?.width = LinearLayout.LayoutParams.WRAP_CONTENT
        leftContainerView.requestLayout()
    }

    override fun onDestroy() { spinAnimator?.cancel() }

    override fun onInfoFontScaleChanged(scale: Float) {
        refreshInfoFontSize()
    }

    private fun startAllAnimations() {
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 20000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { anim -> vinylDisc.rotation = anim.animatedValue as Float }
            start()
        }

        // 彩色频谱律动跳动动画 (GPU scaleY 硬件加速，零 requestLayout 开销，响应极速流畅)
        val durations = longArrayOf(420L, 280L, 550L, 340L, 480L, 310L, 390L)
        val minScales = floatArrayOf(0.18f, 0.35f, 0.12f, 0.40f, 0.22f, 0.30f, 0.15f)
        visualizerBars.forEachIndexed { i, bar ->
            ValueAnimator.ofFloat(minScales[i % minScales.size], 1.0f).apply {
                duration = durations[i % durations.size]
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    bar.scaleY = anim.animatedValue as Float
                }
                start()
            }
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

    private fun dp(dpVal: Int): Int {
        return (dpVal * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun createTV(sizeSp: Float, tf: Typeface, color: Int): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = tf
            setTextColor(color)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    /** 绘制硬核黑胶盘面 (SweepGradient 光泽沟槽 + 同心圆纹理 + 封面标圈) */
    private class VinylDiscView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var coverBitmap: Bitmap? = null
        private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()

        private val sweepColors = intArrayOf(
            Color.parseColor("#1C1C22"), Color.parseColor("#383842"),
            Color.parseColor("#1C1C22"), Color.parseColor("#383842"), Color.parseColor("#1C1C22")
        )
        private val colorBg = Color.parseColor("#111115")
        private val colorRing = Color.parseColor("#25FFFFFF")
        private val colorLabel = Color.parseColor("#E94560")
        private val colorSpindle = Color.parseColor("#0B0B0E")
        private val colorDot = Color.parseColor("#80FFFFFF")

        private val dstRectF = RectF()
        private val srcRect = Rect()
        private var sweepShader: SweepGradient? = null
        private var lastShaderWidth = 0

        fun setCoverBitmap(bitmap: Bitmap?) {
            coverBitmap = bitmap
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && w != lastShaderWidth) {
                lastShaderWidth = w
                sweepShader = SweepGradient(w / 2f, h / 2f, sweepColors, null)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = Math.min(cx, cy)
            if (radius <= 0) return

            // 1. 黑胶盘面外圈底色
            paint.style = Paint.Style.FILL
            paint.color = colorBg
            canvas.drawCircle(cx, cy, radius, paint)

            // 2. 唱片沟槽 (使用缓存的 SweepGradient 零对象分配)
            var shader = sweepShader
            if (shader == null && cx > 0) {
                shader = SweepGradient(cx, cy, sweepColors, null)
                sweepShader = shader
            }
            paint.shader = shader
            canvas.drawCircle(cx, cy, radius * 0.95f, paint)
            paint.shader = null

            // 3. 盘面同心圆纹理环
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = colorRing
            val ringStep = radius * 0.08f
            var r = radius * 0.45f
            while (r < radius * 0.90f) {
                canvas.drawCircle(cx, cy, r, paint)
                r += ringStep
            }

            // 4. 中心圆标 (专辑封面/默认标)
            val labelRadius = radius * 0.38f
            paint.style = Paint.Style.FILL
            paint.color = colorLabel
            canvas.drawCircle(cx, cy, labelRadius, paint)

            val bm = coverBitmap
            if (bm != null && !bm.isRecycled) {
                clipPath.reset()
                clipPath.addCircle(cx, cy, labelRadius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
                srcRect.set(0, 0, bm.width, bm.height)
                dstRectF.set(cx - labelRadius, cy - labelRadius, cx + labelRadius, cy + labelRadius)
                canvas.drawBitmap(bm, srcRect, dstRectF, coverPaint)
                canvas.restore()
            }

            // 5. 中心轴孔
            paint.style = Paint.Style.FILL
            paint.color = colorSpindle
            canvas.drawCircle(cx, cy, radius * 0.08f, paint)

            // 轴孔小白点
            paint.color = colorDot
            canvas.drawCircle(cx, cy, radius * 0.03f, paint)
        }
    }

    private class GradientTV(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
        var gradientStart: Int? = null
        var gradientEnd: Int? = null
        private var cachedShader: android.graphics.Shader? = null
        private var lastShaderWidth = 0

        fun clearTextShader() {
            gradientStart = null
            gradientEnd = null
            cachedShader = null
            paint.shader = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val gs = gradientStart
            val ge = gradientEnd
            if (gs != null && ge != null && width > 0) {
                if (cachedShader == null || width != lastShaderWidth) {
                    lastShaderWidth = width
                    cachedShader = android.graphics.LinearGradient(
                        0f, 0f, width.toFloat(), 0f,
                        gs, ge, android.graphics.Shader.TileMode.CLAMP
                    )
                }
                paint.shader = cachedShader
            } else if (tag == null) {
                paint.shader = null
            }
            super.onDraw(canvas)
        }
    }
}

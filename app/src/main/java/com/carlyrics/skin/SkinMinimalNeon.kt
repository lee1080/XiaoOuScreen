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
import com.carlyrics.model.LyricLine
import com.carlyrics.util.LyricsCacheManager

/**
 * Skin 2 - 极简科技风 (Minimal Neon)
 * HTML参考: design2_minimal_neon.html — 1:1还原
 *
 * 还原点:
 * - 背景: linear-gradient(135deg, #16181D→#0D0F12), 容器边框#2A2D35, 内阴影inset
 * - 左侧封面区: cover-bg层(封面opacity 0.3 + 渐变mask从左淡出) + 圆形旋转唱片(cover-disc)
 * - 唱片: border-radius 50%, 4px #111边框, shadow多层, 中心::after小圆点, spin 8s
 * - 歌曲标题: 22sp/700白色/letterSpacing 0.5; 歌手: 14sp/300/#8892B0/uppercase/letterSpacing 2px
 * - 歌词: 居中, 当前行36sp/700/#00F0FF/textShadow glow; 非当前行16sp/#4A5568/opacity 0.6
 * - 无底部进度条 (按反馈移除)
 * - 关闭按钮: 右上角
 */
class SkinMinimalNeon(context: Context) : LyricsSkin(context) {

    private val coverBgOverlay: ImageView
    private val coverBgMask: View
    private val coverDisc: ImageView
    private val discCenterDot: View
    private var spinAnimator: ValueAnimator? = null
    private val titleText: TextView
    private val artistText: TextView
    private val lyricsContainer: LinearLayout
    private val noLyricsText: TextView
    // 左面板引用（两栏架构）
    private val coverSectionView: FrameLayout
    private val leftPanel: FrameLayout
    private val lyricsArea: FrameLayout
    private val discFrame: FrameLayout
    private val contentRowLayout: LinearLayout
    private val baseLeftWeight: Float = 1f
    private val baseDiscSizeDp = 140
    private val baseDiscDotSizeDp = 18
    private val baseTitleSp = 22f
    private val baseArtistSp = 14f
    private val baseLeftPaddingDp = intArrayOf(30, 20, 20, 20)
    private val baseInfoTopPaddingDp = 12

    private val lyricsScrollView: ScrollView
    private var lyLineViews: List<TextView> = emptyList()
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1

    companion object {
        private const val NEON_COLOR = "#00F0FF"
        private const val NEON_GLOW = "#6600F0FF"
        private const val DIM_COLOR = "#4A5568"
        private const val CURRENT_SCALE = 2.18f
    }

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#16181D"), Color.parseColor("#0D0F12")))

        // 左侧封面背景层 (opacity 0.3 + 渐变mask从左淡出)
        coverBgOverlay = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.3f
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        }
        coverBgMask = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#FF16181D"), Color.TRANSPARENT))
            alpha = 0.7f
        }

        // 圆形旋转唱片
        coverDisc = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        }
        discCenterDot = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111111")); shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#2A2D35"))
            }
        }

        titleText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            letterSpacing = -0.01f; includeFontPadding = false
        }
        artistText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = com.car.xiaoouscreen.util.HmiFontManager.getWeightedTypeface(300, fallbackBold = false)
            setTextColor(Color.parseColor("#8892B0")); letterSpacing = 0.08f; includeFontPadding = false
        }

        lyricsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, dp(50), 0, dp(50))
            clipChildren = false; clipToPadding = false
        }
        noLyricsText = createTV(fontSizeSp, Typeface.DEFAULT_BOLD, Color.parseColor(NEON_COLOR)).apply {
            text = "等待播放音乐..."
            letterSpacing = -0.015f
            setShadowLayer(dp(15).toFloat(), 0f, 0f, Color.parseColor(NEON_GLOW))
            gravity = Gravity.CENTER
        }

        // 组装
        contentRowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val root = contentRowLayout

        // 左侧封面区
        coverSectionView = FrameLayout(context)
        coverSectionView.addView(coverBgOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        coverSectionView.addView(coverBgMask, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 唱片Frame (直接居中放置在 FrameLayout 正中心)
        val discSize = dp(baseDiscSizeDp)
        discFrame = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        discFrame.addView(coverDisc, FrameLayout.LayoutParams(discSize, discSize).apply {
            gravity = Gravity.CENTER
        })
        discFrame.addView(discCenterDot, FrameLayout.LayoutParams(dp(baseDiscDotSizeDp), dp(baseDiscDotSizeDp)).apply {
            gravity = Gravity.CENTER
        })
        // 绝对置于 leftPanel 几何正中心
        coverSectionView.addView(discFrame, FrameLayout.LayoutParams(discSize, discSize).apply {
            gravity = Gravity.CENTER
        })

        // 底部由下至上的深色渐变矩形 (遮罩保护歌名和歌手名文本清晰度)
        val bottomGradientOverlay = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.parseColor("#F20A0C10"),
                    Color.parseColor("#990A0C10"),
                    Color.TRANSPARENT
                )
            )
        }
        coverSectionView.addView(bottomGradientOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(95)
        ).apply {
            gravity = Gravity.BOTTOM
        })

        // 歌名 + 歌手 (放置在底部，歌手左对齐歌名)
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(16), 0, dp(16), dp(10))
            clipChildren = true; clipToPadding = true
        }
        infoCol.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START
        })
        infoCol.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            gravity = Gravity.START
        })

        coverSectionView.addView(infoCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        // 左面板容器
        leftPanel = FrameLayout(context)
        leftPanel.addView(coverSectionView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(leftPanel, LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.MATCH_PARENT))

        // 歌词区 - 居中
        lyricsContainer.addView(noLyricsText)
        lyricsScrollView = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            isFillViewport = true
            addView(lyricsContainer)
        }
        lyricsArea = FrameLayout(context).apply {
            clipChildren = true; clipToPadding = true
        }
        lyricsArea.addView(lyricsScrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(lyricsArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // 根Frame
        val frame = FrameLayout(context).apply {
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // 内阴影模拟: inset 0 1px 0 rgba白5% — 顶部一条亮线
        val innerShadow = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#0DFFFFFF"), Color.TRANSPARENT))
        }
        frame.addView(innerShadow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(2)).apply { gravity = Gravity.TOP })

        // 外层容器边框
        val wrapped = FrameLayout(context).apply {
            val gd = GradientDrawable().apply {
                setStroke(dp(1), Color.parseColor("#2A2D35"))
                cornerRadius = dp(16).toFloat()
            }
            background = gd; clipToOutline = true
            addView(frame, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(wrapped, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
        refreshInfoFontSize()
    }

    override fun setTrackInfo(title: String, artist: String) {
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(title) ?: run { titleText.text = title }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
    }

    override fun setCoverUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            coverDisc.setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
            coverBgOverlay.setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        } else {
            LyricsCacheManager.loadCoverBitmap(context, url) { bitmap ->
                bitmap?.let { coverDisc.setImageBitmap(it) }
            }
            LyricsCacheManager.loadCoverInto(context, url, coverBgOverlay)
        }
        if (spinAnimator == null || !spinAnimator!!.isRunning) {
            spinAnimator?.cancel()
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 16000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { coverDisc.rotation = it.animatedValue as Float }
                start()
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
        lyricsContainer.gravity = Gravity.CENTER_HORIZONTAL
        lyLineViews = lines.map {
            val wrapper = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = TextView(context).apply {
                text = it.text; setTextColor(Color.parseColor(DIM_COLOR)); alpha = 0.6f
                gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp / CURRENT_SCALE)
                letterSpacing = 0.01f
                minHeight = dp(30); setPadding(dp(8), dp(3), dp(8), dp(3))
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
                    style = KaraokeHelper.MINIMAL_NEON_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return target
        }

        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            KaraokeHelper.resetKaraoke(this, lyricLines[currentIndex].text)
            alpha = 0.6f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp / CURRENT_SCALE)
            setTextColor(Color.parseColor(DIM_COLOR)); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f; setShadowLayer(0f, 0f, 0f, 0)
        }
        currentIndex = target
        if (currentIndex in lyLineViews.indices) lyLineViews[currentIndex].apply {
            alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(NEON_COLOR))
            letterSpacing = -0.015f
            setShadowLayer(dp(15).toFloat(), 0f, 0f, Color.parseColor(NEON_GLOW))
            val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
            KaraokeHelper.applyKaraoke(
                textView = this,
                line = lyricLines[currentIndex],
                nextLineTimestamp = nextTs,
                currentPositionMs = effectiveMs,
                style = KaraokeHelper.MINIMAL_NEON_STYLE,
                marqueeEnabled = marqueeEnabled
            )
        }
        if (lyLineViews.isNotEmpty()) scrollToCenter(currentIndex)
        return currentIndex
    }

    override fun setPlaceholderText(text: String) { noLyricsText.text = text }

    override fun onFontSizeChanged(sp: Float) {
        noLyricsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        lyLineViews.forEachIndexed { i, tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) sp else sp / CURRENT_SCALE)
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

    override fun onScaleChanged(scale: Float) {
        // 唱片尺寸
        val discSz = dp((baseDiscSizeDp * scale).toInt())
        (coverDisc.layoutParams as FrameLayout.LayoutParams).apply { width = discSz; height = discSz }
        (discFrame.layoutParams as FrameLayout.LayoutParams).apply { width = discSz; height = discSz }
        val dotSz = dp((baseDiscDotSizeDp * scale).toInt())
        (discCenterDot.layoutParams as FrameLayout.LayoutParams).apply { width = dotSz; height = dotSz }
        discFrame.requestLayout()

        refreshInfoFontSize()

        // 左面板 padding
        val leftContent = discFrame.parent as? ViewGroup
        leftContent?.setPadding(
            dp((baseLeftPaddingDp[0] * scale).toInt()),
            dp((baseLeftPaddingDp[1] * scale).toInt()),
            dp((baseLeftPaddingDp[2] * scale).toInt()),
            dp((baseLeftPaddingDp[3] * scale).toInt())
        )
        // infoCol topPadding
        val infoCol = leftContent?.getChildAt(1) as? ViewGroup
        infoCol?.setPadding(0, dp((baseInfoTopPaddingDp * scale).toInt()), 0, 0)
    }

    override fun onInfoFontScaleChanged(scale: Float) {
        refreshInfoFontSize()
    }

    override fun onPauseAnimations() { spinAnimator?.pause() }

    override fun onResumeAnimations() { spinAnimator?.resume() }

    override fun onDestroy() {
        super.onDestroy()
        spinAnimator?.cancel()
        spinAnimator = null
    }

    private fun scrollToCenter(index: Int) {
        val view = lyLineViews.getOrNull(index) ?: return
        view.post {
            val wrapper = view.parent as View
            val sy = wrapper.top - lyricsScrollView.height / 2 + wrapper.height / 2
            lyricsScrollView.smoothScrollTo(0, sy.coerceAtLeast(0))
        }
    }

    private fun createTV(sp: Float, tf: Typeface, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = tf; setTextColor(color)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

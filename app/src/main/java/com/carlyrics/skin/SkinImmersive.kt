package com.carlyrics.skin

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.carlyrics.model.LyricLine
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager

/**
 * Skin 5 - 全屏沉浸式 (Immersive)
 * HTML参考: design5_immersive.html — 1:1还原
 *
 * 还原点:
 * - 背景: 纯暗色渐变叠加层(不使用封面模糊): linear-gradient(90deg, rgba黑80%→transparent30%→transparent60%→rgba黑50%)
 * - 无时间显示(1:42 / 3:56已移除)
 * - 左侧面板: 封面150x150圆角18dp + 标题24sp/800 textShadow + 歌手15sp/300 rgba白70%
 * - 进度条: track 4px rgba白15%, fill渐变#FF758C→#FF7EB3带box-shadow发光, 白色圆点thumb10px
 * - 歌词5行层级: far(16sp rgba白20%)→near(19sp rgba白45%)→current(34sp/800)→near→far
 * - 当前歌词: textShader LinearGradient(90deg,#FF758C→#FF7EB3→#FFB199), 左侧4px装饰竖线
 * - 歌词容器渐变遮罩边缘淡出
 * - 关闭按钮 + 右下角图标(lyric-icon, fullscreen-icon)
 */
class SkinImmersive(context: Context) : LyricsSkin(context) {

    // bgCover removed — background now uses pure dark gradient style from HTML
    private val gradientOverlay: View
    private val coverImage: ImageView
    private val titleText: TextView
    private val artistText: TextView
    // progressTrack/progressFill/progressThumb removed — progress bar removed
    // timeCurrent/timeTotal removed — HTML time display (1:42 / 3:56) removed per feedback

    private val lyricsScrollView: ScrollView
    private val lyricsContainer: LinearLayout
    private val noLyricsText: GradientTV
    private val fadeMaskTop: View
    private val fadeMaskBottom: View
    // 左面板引用（两栏架构）
    private lateinit var leftPanelView: LinearLayout
    private lateinit var leftPanel: FrameLayout
    private lateinit var lyricsArea: FrameLayout
    private lateinit var coverFrame: FrameLayout
    private lateinit var contentRowLayout: LinearLayout
    private val baseLeftWidthPercent: Float = 0.35f
    private val baseCoverSizeDp = 240
    private val baseCoverCornerDp = 16
    private val baseTitleSp = 24f
    private val baseArtistSp = 15f
    private val basePanelPaddingDp = 12
    private val baseTextInfoPaddingLeftDp = 12
    private val baseArtistTopMarginDp = 6

    private var lyLineViews: List<GradientTV> = emptyList()
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var totalDuration: Long = 0L

    companion object {
        private const val TAG = "SkinImmersive"
        private const val CURRENT_SCALE = 2.125f
        private val GRAD_COLORS = intArrayOf(
            Color.parseColor("#FF758C"), Color.parseColor("#FF7EB3"), Color.parseColor("#FFB199"))
    }

    init {
        // 根背景完全不透明，防止透明区域露出屏幕后面内容
        setBackgroundColor(Color.parseColor("#FF000000"))

        // 渐变叠加层 90deg: 半透明深蓝→微透明→透明→半透明深蓝
        gradientOverlay = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.parseColor("#4D0D47A1"), Color.parseColor("#330D47A1"),
                Color.parseColor("#1A0D47A1"), Color.parseColor("#330D47A1"),
                Color.parseColor("#4D0D47A1")))
        }

        // 封面120x120圆角16dp（参照 SkinGlassmorphism）
        coverImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        }

        titleText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setShadowLayer(dp(6).toFloat(), 0f, dp(3).toFloat(), Color.parseColor("#80000000"))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            letterSpacing = -0.01f; includeFontPadding = false
        }
        artistText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, 300)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.parseColor("#66000000"))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            letterSpacing = 0.02f; includeFontPadding = false
        }

        // progressTrack/progressFill/progressThumb removed — progress bar removed
        // timeCurrent / timeTotal removed — per feedback, no time labels (1:42 / 3:56)

        // 歌词
        lyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            isFillViewport = true
        }
        lyricsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setPadding(dp(20), dp(50), dp(20), dp(50))
            clipChildren = false; clipToPadding = false
        }
        noLyricsText = GradientTV(context).apply {
            text = "等待播放音乐..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 1.4f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.parseColor("#FF758C"))
            gradientStart = Color.parseColor("#FFFFFF")
            gradientEnd = Color.parseColor("#FF7EB3")
        }

        fadeMaskTop = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FF000000"), Color.TRANSPARENT))
        }
        fadeMaskBottom = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#FF000000")))
        }

        // ---- 组装 ----
        contentRowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = null  // 透明，让底层渐变穿透
        }
        val content = contentRowLayout

        // 左侧面板
        leftPanelView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            clipChildren = true; clipToPadding = true
        }
        coverFrame = FrameLayout(context).apply {
            addView(coverImage, FrameLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)))
            val gd = GradientDrawable().apply { cornerRadius = dp(baseCoverCornerDp).toFloat() }
            background = gd; clipToOutline = true
        }
        leftPanelView.addView(coverFrame, LinearLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        // 右侧：歌名 + 歌手（垂直排列）
        val textInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(baseTextInfoPaddingLeftDp), 0, 0, 0)
            clipChildren = true; clipToPadding = true
        }
        textInfo.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        textInfo.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(baseArtistTopMarginDp)
        })
        leftPanelView.addView(textInfo, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        // 左面板容器（居中包裹）
        leftPanel = FrameLayout(context)
        leftPanel.addView(leftPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        content.addView(leftPanel, LinearLayout.LayoutParams(
            leftW, LinearLayout.LayoutParams.MATCH_PARENT))

        // 歌词区
        val lyricsFrame = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
        }
        lyricsContainer.addView(noLyricsText)
        lyricsScrollView.addView(lyricsContainer)
        lyricsFrame.addView(lyricsScrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lyricsFrame.addView(fadeMaskTop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(30)).apply { gravity = Gravity.TOP })
        lyricsFrame.addView(fadeMaskBottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(30)).apply { gravity = Gravity.BOTTOM })

        lyricsArea = FrameLayout(context).apply {
            clipChildren = true; clipToPadding = true
        }
        lyricsArea.addView(lyricsFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        content.addView(lyricsArea, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // 根布局 — 纯暗色渐变背景，不使用封面图
        val frame = FrameLayout(context)
        frame.addView(gradientOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
        refreshInfoFontSize()
    }

    override fun setTrackInfo(title: String, artist: String) {
        AppLogger.d(TAG, "setTrackInfo: title=\"$title\", artist=\"$artist\", titleText.visibility=${titleText.visibility}, artistText.visibility=${artistText.visibility}")
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(title) ?: run { titleText.text = title }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
        AppLogger.d(TAG, "setTrackInfo done: titleText.text=\"${titleText.text}\", artistText.text=\"${artistText.text}\", titleText.width=${titleText.width}, titleText.height=${titleText.height}")
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
        lyricsContainer.setPadding(dp(20), dp(50), dp(20), dp(50))
        lyricsContainer.gravity = Gravity.NO_GRAVITY
        lyLineViews = lines.map { line ->
            val wrapper = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = GradientTV(context).apply {
                text = line.text; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                setTextColor(Color.parseColor("#33FFFFFF")); alpha = 0.2f
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.01f
                setPadding(dp(16), dp(8), dp(16), dp(8))
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
        totalDuration = lines.lastOrNull()?.timestamp?.plus(5000) ?: 0L
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
                    style = KaraokeHelper.IMMERSIVE_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return target
        }

        // 还原所有行到各级别
        for (i in lyLineViews.indices) {
            val dist = Math.abs(i - target)
            when {
                dist == 0 -> {
                    lyLineViews[i].apply {
                        alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
                        typeface = Typeface.DEFAULT_BOLD; letterSpacing = -0.015f
                        clearTextShader()
                        setPadding(dp(24), dp(8), dp(16), dp(8))
                        accentLineColor = Color.parseColor("#FF758C")
                        setShadowLayer(2f, 0f, 0f, Color.parseColor("#FF799D"))
                        background = null
                        val nextTs = lyricLines.getOrNull(i + 1)?.timestamp
                        KaraokeHelper.applyKaraoke(
                            textView = this,
                            line = lyricLines[i],
                            nextLineTimestamp = nextTs,
                            currentPositionMs = effectiveMs,
                            style = KaraokeHelper.IMMERSIVE_STYLE,
                            marqueeEnabled = marqueeEnabled
                        )
                        invalidate()
                    }
                }
                dist == 1 -> lyLineViews[i].apply {
                    KaraokeHelper.resetKaraoke(this, lyricLines[i].text)
                    alpha = 0.45f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 1.19f)
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#73FFFFFF"))
                    gradientStart = null; clearTextShader(); accentLineColor = null
                    letterSpacing = 0.01f
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    background = null
                }
                dist == 2 -> lyLineViews[i].apply {
                    KaraokeHelper.resetKaraoke(this, lyricLines[i].text)
                    alpha = 0.20f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#33FFFFFF"))
                    gradientStart = null; clearTextShader(); accentLineColor = null
                    letterSpacing = 0.01f
                    setPadding(dp(16), dp(5), dp(16), dp(5))
                    background = null
                    setShadowLayer(0f, 0f, 0f, 0)
                }
                else -> lyLineViews[i].apply {
                    KaraokeHelper.resetKaraoke(this, lyricLines[i].text)
                    alpha = 0.20f; setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 0.875f)
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#33FFFFFF"))
                    gradientStart = null; clearTextShader(); accentLineColor = null
                    letterSpacing = 0.01f
                    setPadding(dp(16), dp(4), dp(16), dp(4))
                    background = null
                    setShadowLayer(0f, 0f, 0f, 0)
                }
            }
        }
        currentIndex = target
        if (currentIndex in lyLineViews.indices) scrollToCenter(currentIndex)
        return currentIndex
    }

    override fun setPlaceholderText(text: String) { noLyricsText.text = text }

    override fun onFontSizeChanged(sp: Float) {
        lyLineViews.forEachIndexed { i, tv ->
            val dist = Math.abs(i - currentIndex)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, when {
                dist == 0 -> sp * CURRENT_SCALE; dist == 1 -> sp * 1.19f; dist == 2 -> sp; else -> sp * 0.875f
            })
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
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSp * effectiveScale)
        artistText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseArtistSp * effectiveScale)
    }

    override fun onScaleChanged(scale: Float) {
        val coverSz = dp((baseCoverSizeDp * scale).toInt())
        (coverImage.layoutParams as FrameLayout.LayoutParams).apply { width = coverSz; height = coverSz }
        (coverFrame.layoutParams as LinearLayout.LayoutParams).apply { width = coverSz; height = coverSz }
        (coverFrame.background as? GradientDrawable)?.cornerRadius = dp((baseCoverCornerDp * scale).toInt()).toFloat()
        coverFrame.requestLayout()

        refreshInfoFontSize()

        leftPanelView.setPadding(
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt())
        )
        (leftPanelView.getChildAt(1) as? ViewGroup)?.setPadding(
            dp((baseTextInfoPaddingLeftDp * scale).toInt()), 0, 0, 0
        )
        val artistLp = artistText.layoutParams as? LinearLayout.LayoutParams
        artistLp?.topMargin = dp((baseArtistTopMarginDp * scale).toInt())
    }

    override fun onInfoFontScaleChanged(scale: Float) {
        refreshInfoFontSize()
    }

    private fun scrollToCenter(index: Int) {
        val view = lyLineViews.getOrNull(index) ?: return
        view.post {
            val wrapper = view.parent as View
            val sy = wrapper.top - lyricsScrollView.height / 2 + wrapper.height / 2
            lyricsScrollView.smoothScrollTo(0, sy.coerceAtLeast(0))
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000; return "${s / 60}:${String.format("%02d", s % 60)}"
    }

    private fun createTV(sp: Float, tf: Typeface, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = tf; setTextColor(color)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ============= 内部类 =============

    /** 渐变文字 + 左侧装饰竖线 (零 GC 优化) */
    class GradientTV(context: Context) : TextView(context) {
        var gradientStart: Int? = null
        var gradientEnd: Int? = null
        var accentLineColor: Int? = null

        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pinkColor = Color.parseColor("#FF7EB3")
        private var accentShader: LinearGradient? = null
        private var textShader: LinearGradient? = null
        private var lastHeight = 0
        private var lastMeasuredW = 0f

        fun clearTextShader() {
            gradientStart = null
            gradientEnd = null
            textShader = null
            paint.shader = null
        }

        override fun onDraw(canvas: Canvas) {
            val alc = accentLineColor
            if (alc != null && height > 0) {
                if (accentShader == null || height != lastHeight) {
                    lastHeight = height
                    accentShader = LinearGradient(
                        0f, 0f, 0f, height.toFloat(),
                        intArrayOf(alc, pinkColor), null, Shader.TileMode.CLAMP
                    )
                }
                accentPaint.shader = accentShader
                val lineX = dp2(4f)
                canvas.drawRoundRect(
                    lineX, height * 0.15f, lineX + dp2(4f), height * 0.85f,
                    dp2(4f), dp2(4f), accentPaint
                )
            }

            val gs = gradientStart
            val ge = gradientEnd
            if (gs != null && ge != null) {
                val currentW = paint.measureText(text.toString())
                if (textShader == null || currentW != lastMeasuredW) {
                    lastMeasuredW = currentW
                    if (currentW > 0f) {
                        textShader = LinearGradient(
                            0f, 0f, currentW, 0f,
                            intArrayOf(gs, pinkColor, ge), null, Shader.TileMode.CLAMP
                        )
                    }
                }
                paint.shader = textShader
            } else if (tag == null) {
                paint.shader = null
            }
            super.onDraw(canvas)
        }

        private fun dp2(v: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            resources.displayMetrics
        )
    }

    /* BlurredBgView removed — background now uses pure dark gradient overlay without album art blur */
}

package com.carlyrics.display

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import com.car.xiaoouscreen.R
import com.carlyrics.model.LyricLine
import com.carlyrics.skin.LyricsSkin
import com.carlyrics.util.CoverColorUtil
import com.carlyrics.util.LyricsCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsDisplayView(context: Context) : LyricsSkin(context) {

    companion object {
        private const val CURRENT_SCALE = 1.35f
        private const val NORMAL_ALPHA = 0.85f
        const val DEFAULT_FONT_SIZE_SP = 38f
        const val MIN_FONT_SIZE_SP = 26f
        const val MAX_FONT_SIZE_SP = 50f
    }

    // ==================== 可调参数 ====================

    /** 歌词字号 (sp) */
    override var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
        set(value) {
            field = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            refreshFontSize()
        }

    // ==================== 视图 ====================

    private val coverImage: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val titleText: TextView = com.carlyrics.display.BouncingTitleView(context).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), Color.parseColor("#CC000000"))
        letterSpacing = -0.01f
        includeFontPadding = false
    }

    val artistText: TextView = com.carlyrics.display.BouncingTitleView(context).apply {
        setTextColor(Color.parseColor("#E6FFFFFF"))
        gravity = Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), Color.parseColor("#99000000"))
        letterSpacing = 0.02f
        includeFontPadding = false
    }

    private val lyricsScrollView: ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val lyricsContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(0, dp(80), 0, dp(80))
        clipChildren = false; clipToPadding = false
    }

    private val noLyricsText: TextView = TextView(context).apply {
        text = "等待播放音乐..."
        setTextColor(Color.parseColor("#FFFF4081"))
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = -0.015f
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
        setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.parseColor("#80FF4081"))
    }

    private var currentCornerRadiusDp: Float = 16f

    private val coverFrame: FrameLayout = FrameLayout(context).apply {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(currentCornerRadiusDp.toInt()).toFloat())
            }
        }
    }

    // 信息浮层覆盖在封面上，配置高对比防吞字底纹渐变蒙层
    private val coverInfoOverlay: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.START
        clipChildren = true
        clipToPadding = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#00000000"),
                Color.parseColor("#33000000"),
                Color.parseColor("#80000000"),
                Color.parseColor("#E6000000")
            )
        )
        setPadding(dp(16), dp(24), dp(16), dp(16))
    }

    // 外层内容布局 (左面板 + 歌词区)
    private val contentLayout: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    // 左面板容器（封面+信息居中）
    private val leftPanel: FrameLayout = FrameLayout(context)
    // 歌词区容器
    private val lyricsArea: FrameLayout = FrameLayout(context)

    // 全屏浅色系高级流光灰白渐变背景 (拉强对比度与高级感)
    private val darkBackground: View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FFBFC9D8"),
                Color.parseColor("#FFEBF0F6"),
                Color.parseColor("#FFAAB6C6")
            )
        )
    }

    // ==================== 状态 ====================

    private var lyLineViews: List<TextView> = emptyList()
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex: Int = -1
    private val uiScope = CoroutineScope(Dispatchers.Main)

    // raw list for updating (no weight)
    private var currentRows: MutableList<TextView> = mutableListOf()

    init {
        // 全屏暗色底
        addView(darkBackground, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 封面区
        coverFrame.addView(coverImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        coverFrame.background = CoverColorUtil.createDiagonalGradient(CoverColorUtil.getDefaultColors())
        coverImage.setImageResource(R.drawable.ic_default_cover)

        // 封面信息浮层
        coverInfoOverlay.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        coverInfoOverlay.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })
        coverFrame.addView(coverInfoOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 左面板：封面居中
        leftPanel.addView(coverFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 歌词区
        lyricsScrollView.isFillViewport = true
        noLyricsText.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER }
        lyricsScrollView.addView(lyricsContainer)
        lyricsContainer.addView(noLyricsText)
        lyricsArea.addView(lyricsScrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))


        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        // 组装 contentLayout，用定宽控制 leftPanel，用 weight 控制 lyricsArea
        contentLayout.addView(leftPanel, LinearLayout.LayoutParams(
            leftW, LinearLayout.LayoutParams.MATCH_PARENT
        ))
        lyricsArea.clipChildren = true
        lyricsArea.clipToPadding = true
        contentLayout.addView(lyricsArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
        ))

        addView(contentLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
    }

    // ==================== 公开 API ====================

    override fun setLyrics(lines: List<LyricLine>) {
        if (lines == lyricLines && lines.isNotEmpty() && lyLineViews.isNotEmpty()) return
        lyricLines = lines
        lyricsContainer.removeAllViews()

        if (lines.isEmpty()) {
            lyricsContainer.setPadding(0, 0, 0, 0)
            lyricsContainer.gravity = Gravity.CENTER
            lyricsContainer.addView(noLyricsText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
            lyLineViews = emptyList()
            return
        }
        lyricsContainer.setPadding(0, dp(80), 0, dp(80))
        lyricsContainer.gravity = lyricsTextAlignment

        currentRows = lines.map { line ->
            val wrapper = FrameLayout(context).apply {
                clipChildren = false; clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = TextView(context).apply {
                text = line.text
                setTextColor(Color.parseColor("#FF111111"))
                alpha = NORMAL_ALPHA
                gravity = Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                letterSpacing = 0.01f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setShadowLayer(2f, 0f, 1f, Color.parseColor("#15000000"))
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
        }.toMutableList()

        lyLineViews = currentRows
        currentIndex = -1
    }

    override fun updateProgress(positionMs: Long): Int {
        if (lyricLines.isEmpty()) return -1

        val effectiveMs = positionMs + lyricsOffsetMs
        lastProgressMs = effectiveMs

        var targetIndex = 0
        for (i in lyricLines.indices) {
            if (lyricLines[i].timestamp <= effectiveMs) {
                targetIndex = i
            } else {
                break
            }
        }

        if (targetIndex == currentIndex) {
            if (currentIndex in lyLineViews.indices) {
                val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
                com.carlyrics.skin.KaraokeHelper.applyKaraoke(
                    textView = lyLineViews[currentIndex],
                    line = lyricLines[currentIndex],
                    nextLineTimestamp = nextTs,
                    currentPositionMs = effectiveMs,
                    style = com.carlyrics.skin.KaraokeHelper.DEFAULT_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return targetIndex
        }

        val effSize = fontSizeSp

        // 还原上一个
        if (currentIndex in lyLineViews.indices) {
            lyLineViews[currentIndex].apply {
                com.carlyrics.skin.KaraokeHelper.resetKaraoke(this, lyricLines[currentIndex].text)
                alpha = NORMAL_ALPHA
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                setTextColor(Color.parseColor("#FF111111"))
                letterSpacing = 0.01f
                background = null
                setShadowLayer(0f, 0f, 0f, 0)
            }
        }

        currentIndex = targetIndex
        if (currentIndex in lyLineViews.indices) {
            lyLineViews[currentIndex].apply {
                alpha = 1f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
                setTextColor(Color.parseColor("#FFFF4081"))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = -0.015f
                setShadowLayer(2f, 0f, 0f, Color.parseColor("#FF799D"))
            }
            val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
            com.carlyrics.skin.KaraokeHelper.applyKaraoke(
                textView = lyLineViews[currentIndex],
                line = lyricLines[currentIndex],
                nextLineTimestamp = nextTs,
                currentPositionMs = effectiveMs,
                style = com.carlyrics.skin.KaraokeHelper.DEFAULT_STYLE,
                marqueeEnabled = marqueeEnabled
            )
            scrollToCenter(currentIndex)
        }

        return currentIndex
    }

    override fun setCoverUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            coverImage.setImageResource(R.drawable.ic_default_cover)
            coverFrame.background = CoverColorUtil.createDiagonalGradient(CoverColorUtil.getDefaultColors())
            return
        }

        LyricsCacheManager.loadCoverBitmap(context, url) { bitmap ->
            if (bitmap != null) {
                val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
                coverImage.setImageDrawable(drawable)

                uiScope.launch {
                    val colors = withContext(Dispatchers.IO) {
                        CoverColorUtil.extractColors(bitmap)
                    }
                    coverFrame.background = CoverColorUtil.createDiagonalGradient(colors)
                }
            } else {
                coverImage.setImageDrawable(null)
            }
        }
    }

    private var baseTitleSp = 22f
    private var baseArtistSp = 14f
    private var currentDisplayScale: Float = 1.0f
    private var currentInfoScale: Float = 1.0f

    override fun setTrackInfo(title: String, artist: String) {
        val titleStr = title.ifEmpty { "未知曲目" }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(titleStr) ?: run { titleText.text = titleStr }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
    }

    /** 动态设置无歌词时的占位文字 */
    override fun setPlaceholderText(text: String) {
        noLyricsText.text = text
    }

    // ==================== 内部 ====================

    private fun refreshFontSize() {
        noLyricsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
        lyLineViews.forEach { tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        }
        // 重新高亮当前行
        if (currentIndex in lyLineViews.indices) {
            lyLineViews[currentIndex].setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
        }
    }

    // ==================== 两栏架构方法 ====================

    override fun getContentRow(): View? = contentLayout
    override fun getLeftPanel(): View? = leftPanel
    override fun getLyricsArea(): View? = lyricsArea

    override fun onScaleChanged(scale: Float) {
        currentDisplayScale = scale
        // 封面尺寸缩放
        val coverSize = dp((180 * scale).toInt())
        coverFrame.layoutParams = (coverFrame.layoutParams as LayoutParams).apply {
            width = coverSize
            height = coverSize
            gravity = Gravity.CENTER
        }
        currentCornerRadiusDp = (16f * (scale / BASE_DISPLAY_SCALE)).coerceIn(8f, 24f)
        coverFrame.invalidateOutline()
        coverFrame.requestLayout()

        refreshInfoFontSize()

        // 左面板内边距
        leftPanel.setPadding(
            dp((24 * scale).toInt()), dp((20 * scale).toInt()),
            dp((24 * scale).toInt()), dp((20 * scale).toInt())
        )
        // 封面信息浮层边距
        coverInfoOverlay.setPadding(
            dp((16 * scale).toInt()), dp((24 * scale).toInt()),
            dp((16 * scale).toInt()), dp((16 * scale).toInt())
        )
    }

    override fun onInfoFontScaleChanged(scale: Float) {
        currentInfoScale = scale
        refreshInfoFontSize()
    }

    private fun refreshInfoFontSize() {
        val effectiveScale = scaleFactor * BASE_DISPLAY_SCALE * infoFontScale
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSp * effectiveScale)
        artistText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseArtistSp * effectiveScale)
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

    private fun scrollToCenter(index: Int) {
        if (lyLineViews.isEmpty() || index !in lyLineViews.indices) return
        val targetView = lyLineViews[index]
        targetView.post {
            // targetView 是 wrapper FrameLayout 内的 TextView，需用 wrapper.top 获取在 lyricsContainer 中的位置
            val wrapper = targetView.parent as View
            val scrollY = wrapper.top - lyricsScrollView.height / 2 + wrapper.height / 2
            lyricsScrollView.smoothScrollTo(0, scrollY.coerceAtLeast(0))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

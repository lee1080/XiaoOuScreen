package com.carlyrics.skin

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.carlyrics.model.LyricLine
import com.carlyrics.util.LyricsCacheManager

/**
 * Skin 3 - 动感波浪 (Vibrant Wave)
 * HTML参考: design3_vibrant_wave.html — 1:1还原
 *
 * 还原点:
 * - 背景: linear-gradient(45deg, #FF6B6B→#FECA57→#48DBFB→#FF9FF3), bg-size 400%, animation gradientBG 15s
 * - 叠加层: rgba(0,0,0,0.35) + backdrop-filter blur(15px) 模拟
 * - 左侧: 封面(150x150圆角20dp, shadow) + 标题(900白/textShadow) + 歌手(500/14sp/opacity0.9/letterSpacing1px)
 * - 右侧: 单行大字当前歌词 46sp/900白/textShadow, ::before引号装饰
 * - 关闭按钮: 右上角圆角12px半透明白色背景
 */
class SkinVibrantWave(context: Context) : LyricsSkin(context) {

    private val coverImage: ImageView
    private val titleText: TextView
    private val artistText: TextView
    private val currentLyricText: TextView
    private val quoteView: TextView
    private val noLyricsText: TextView
    private val darkOverlay: View
    private var bgAnimator: ValueAnimator? = null
    // 左面板引用（两栏架构）
    private lateinit var leftPanelView: LinearLayout
    private lateinit var leftPanel: FrameLayout
    private lateinit var lyricsArea: FrameLayout
    private lateinit var coverFrame: FrameLayout
    private lateinit var lyricTextWrapper: RelativeLayout
    private lateinit var contentRowLayout: LinearLayout
    private val baseLeftWeight: Float = 1.2f
    private val baseCoverSizeDp = 150
    private val baseTitleSp = 18f
    private val baseArtistSp = 14f
    private val basePanelPaddingDp = 16
    private val baseInfoTopMarginDp = 12
    private val baseArtistTopMarginDp = 8

    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1

    companion object {
        private const val CURRENT_SCALE = 2.0f
        private val GRADIENT_COLORS = intArrayOf(
            Color.parseColor("#FF6B6B"), Color.parseColor("#FECA57"),
            Color.parseColor("#48DBFB"), Color.parseColor("#FF9FF3"), Color.parseColor("#FF6B6B"))
    }

    init {
        background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = GRADIENT_COLORS
        }
        startBgAnimation()

        darkOverlay = View(context).apply { setBackgroundColor(Color.parseColor("#B3000000")) }

        coverImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        }

        titleText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, 900)
            setTextColor(Color.WHITE)
            setShadowLayer(dp(8).toFloat(), 0f, dp(4).toFloat(), Color.parseColor("#99000000"))
            gravity = Gravity.CENTER
            letterSpacing = -0.01f; includeFontPadding = false
        }
        artistText = com.carlyrics.display.BouncingTitleView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, 500)
            setTextColor(Color.parseColor("#E6FFFFFF"))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f; includeFontPadding = false
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.parseColor("#66000000"))
        }

        // 当前歌词大字 单行展示/粗体/白/立体阴影
        currentLyricText = object : TextView(context) {
            private val shadow1Radius = dp(16).toFloat()
            private val shadow1Dx = dp(6).toFloat()
            private val shadow1Dy = dp(10).toFloat()
            private val shadow1Color = Color.parseColor("#AA000000")
            private val shadow2Radius = dp(6).toFloat()
            private val shadow2Dx = dp(3).toFloat()
            private val shadow2Dy = dp(5).toFloat()
            private val shadow2Color = Color.parseColor("#CC000000")

            init {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * CURRENT_SCALE)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                letterSpacing = -0.015f
            }

            override fun onDraw(canvas: Canvas) {
                // 如果设置了 tag (即正处于卡拉OK或Spannable渲染模式)，完全委托 super.onDraw 进行专业硬件加速着色与排版
                if (tag != null) {
                    super.onDraw(canvas)
                    return
                }
                val p = paint
                val origColor = p.color
                val origText = text
                if (origText.isNullOrEmpty()) return
                val textW = p.measureText(origText.toString())
                val origX = if (marqueeEnabled) 0f else when (lyricsTextAlignment) {
                    Gravity.START, Gravity.LEFT -> 0f
                    Gravity.END, Gravity.RIGHT -> (width - textW).coerceAtLeast(0f)
                    else -> (width - textW) / 2f
                }
                val fm = p.fontMetrics
                val origY = height / 2f - (fm.ascent + fm.descent) / 2f

                // Layer 1: 远层大阴影
                p.setShadowLayer(shadow1Radius, shadow1Dx, shadow1Dy, shadow1Color)
                p.color = Color.WHITE
                canvas.drawText(origText.toString(), origX, origY, p)

                // Layer 2: 近层小阴影
                p.setShadowLayer(shadow2Radius, shadow2Dx, shadow2Dy, shadow2Color)
                canvas.drawText(origText.toString(), origX, origY, p)

                // Layer 3: 主体文字
                p.clearShadowLayer()
                canvas.drawText(origText.toString(), origX, origY, p)

                p.color = origColor
            }
        }.apply {
            gravity = Gravity.CENTER; maxLines = if (marqueeEnabled) 1 else Integer.MAX_VALUE; ellipsize = null
        }
        // 引号装饰 " — ::before伪元素模拟
        quoteView = TextView(context).apply {
            text = "\u201C"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
            setTextColor(Color.parseColor("#4DFFFFFF"))
            typeface = Typeface.SERIF
        }

        noLyricsText = createTV(fontSizeSp * 1.5f, Typeface.DEFAULT_BOLD, Color.WHITE).apply {
            text = "等待播放音乐..."
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            setShadowLayer(dp(15).toFloat(), 0f, 0f, Color.parseColor("#40000000"))
        }

        // 组装
        contentRowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val root = contentRowLayout

        // 左侧: 封面 + 信息（垂直排列，居中）
        leftPanelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(basePanelPaddingDp), dp(basePanelPaddingDp), dp(basePanelPaddingDp), dp(basePanelPaddingDp))
        }
        coverFrame = FrameLayout(context).apply {
            addView(coverImage, FrameLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)))
        }
        leftPanelView.addView(coverFrame, LinearLayout.LayoutParams(dp(baseCoverSizeDp), dp(baseCoverSizeDp)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(baseInfoTopMarginDp), dp(12), 0)
            clipChildren = true; clipToPadding = true
        }
        infoCol.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        infoCol.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(baseArtistTopMarginDp); gravity = Gravity.CENTER_HORIZONTAL
        })
        leftPanelView.addView(infoCol, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        // 左面板容器
        leftPanel = FrameLayout(context)
        leftPanel.addView(leftPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(leftPanel, LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.MATCH_PARENT))

        // 右侧: 大字歌词 + 引号
        val lyricPanel = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
        }
        lyricPanel.addView(noLyricsText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        currentLyricText.id = View.generateViewId()
        lyricTextWrapper = RelativeLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        lyricTextWrapper.addView(currentLyricText, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.CENTER_IN_PARENT) })
        lyricTextWrapper.addView(quoteView, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_TOP, currentLyricText.id)
            addRule(RelativeLayout.ALIGN_START, currentLyricText.id)
            leftMargin = dp(-40)
            topMargin = dp(-20)
        })
        lyricPanel.addView(lyricTextWrapper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        // 歌词区容器
        lyricsArea = FrameLayout(context).apply {
            clipChildren = true; clipToPadding = true
        }
        lyricsArea.addView(lyricPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(lyricsArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val frame = FrameLayout(context)
        frame.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // darkOverlay要在content之上、背景之下 — 即介于两者之间
        // 这里我们放在root之后add
        frame.addView(darkOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 但overlay应该在content之上(z-index)，然而root的children比darkOverlay先add，所以darkOverlay会覆盖root
        // 我们需要调整: darkOverlay先add，root后add
        frame.removeAllViews()
        frame.addView(darkOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        currentLyricText.visibility = View.GONE
        applyXiaoOuAvoidance(xiaoOuAvoidanceEnabled)
        refreshInfoFontSize()
    }

    override fun setTrackInfo(title: String, artist: String) {
        (titleText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(title) ?: run { titleText.text = title }
        val artistStr = artist.ifEmpty { "未知歌手" }
        (artistText as? com.carlyrics.display.BouncingTitleView)?.updateTitleText(artistStr) ?: run { artistText.text = artistStr }
    }

    override fun setCoverUrl(url: String?) {
        if (url.isNullOrEmpty()) coverImage.setImageResource(com.car.xiaoouscreen.R.drawable.ic_default_cover)
        else LyricsCacheManager.loadCoverInto(context, url, coverImage)
    }

    override fun setLyrics(lines: List<LyricLine>) {
        if (lines == lyricLines && lines.isNotEmpty()) return
        lyricLines = lines; currentIndex = -1
        if (lines.isEmpty()) {
            noLyricsText.visibility = View.VISIBLE
            currentLyricText.visibility = View.GONE; quoteView.visibility = View.GONE
        } else {
            noLyricsText.visibility = View.GONE
            currentLyricText.visibility = View.VISIBLE; quoteView.visibility = View.VISIBLE
        }
        // 同步跑马灯对齐（跑马灯开启时强制左对齐，避免开头被裁）
        onMarqueeEnabledChanged()
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
            if (currentIndex in lyricLines.indices) {
                val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
                KaraokeHelper.applyKaraoke(
                    textView = currentLyricText,
                    line = lyricLines[currentIndex],
                    nextLineTimestamp = nextTs,
                    currentPositionMs = effectiveMs,
                    style = KaraokeHelper.VIBRANT_WAVE_STYLE,
                    marqueeEnabled = marqueeEnabled
                )
            }
            return target
        }
        currentIndex = target
        if (currentIndex in lyricLines.indices) {
            KaraokeHelper.resetKaraoke(currentLyricText, lyricLines[currentIndex].text, marqueeEnabled)
            val nextTs = lyricLines.getOrNull(currentIndex + 1)?.timestamp
            KaraokeHelper.applyKaraoke(
                textView = currentLyricText,
                line = lyricLines[currentIndex],
                nextLineTimestamp = nextTs,
                currentPositionMs = effectiveMs,
                style = KaraokeHelper.VIBRANT_WAVE_STYLE,
                marqueeEnabled = marqueeEnabled
            )
        }
        return currentIndex
    }

    override fun setPlaceholderText(text: String) { noLyricsText.text = text }

    override fun onFontSizeChanged(sp: Float) {
        currentLyricText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * CURRENT_SCALE)
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
        if (!marqueeEnabled) {
            currentLyricText.gravity = lyricsTextAlignment
            currentLyricText.invalidate()
        }
    }

    override fun onMarqueeEnabledChanged() {
        currentLyricText.setHorizontallyScrolling(marqueeEnabled)
        currentLyricText.maxLines = if (marqueeEnabled) 1 else Integer.MAX_VALUE
        currentLyricText.ellipsize = null
        if (!marqueeEnabled) {
            currentLyricText.translationX = 0f
        }
        val lp = currentLyricText.layoutParams as RelativeLayout.LayoutParams
        lp.width = if (marqueeEnabled) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        currentLyricText.requestLayout()
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
        coverFrame.requestLayout()

        refreshInfoFontSize()

        leftPanelView.setPadding(
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt()),
            dp((basePanelPaddingDp * scale).toInt())
        )
        val infoCol = leftPanelView.getChildAt(1) as? ViewGroup
        infoCol?.setPadding(0, dp((baseInfoTopMarginDp * scale).toInt()), 0, 0)
        val artistLp = artistText.layoutParams as? LinearLayout.LayoutParams
        artistLp?.topMargin = dp((baseArtistTopMarginDp * scale).toInt())
    }

    override fun onInfoFontScaleChanged(scale: Float) {
        refreshInfoFontSize()
    }

    override fun onDestroy() { bgAnimator?.cancel() }

    /** 模拟 CSS animation: gradientBG 30s — REVERSE模式无缝循环 (0→1→0) */
    private fun startBgAnimation() {
        val gd = background as GradientDrawable
        bgAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val frac = it.animatedFraction
                val colors = IntArray(4) { i ->
                    interpolateColor(GRADIENT_COLORS[i], GRADIENT_COLORS[i+1], frac)
                }
                gd.colors = colors
            }
            start()
        }
    }

    private fun interpolateColor(c1: Int, c2: Int, f: Float): Int {
        val r = (Color.red(c1)*(1-f) + Color.red(c2)*f).toInt()
        val g = (Color.green(c1)*(1-f) + Color.green(c2)*f).toInt()
        val b = (Color.blue(c1)*(1-f) + Color.blue(c2)*f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun createTV(sp: Float, tf: Typeface, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = tf; setTextColor(color)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

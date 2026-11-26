package com.carlyrics.skin

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UpdateAppearance
import android.widget.TextView
import com.carlyrics.model.LyricLine
import com.carlyrics.model.LyricWord

/**
 * 卡拉OK效果配置与渲染器。
 *
 * 核心设计哲学（契合车载场景）：
 * 1. 更透明、更克制：未唱到的字词保持优雅半透明（alpha 0.35~0.50），不刺眼、不干扰驾驶视线；
 * 2. 动感律动：当前唱到的字词轻微放大（1.15x）并加粗发光，营造呼吸感与精准跟唱节拍；
 * 3. 充盈饱满：已唱过的字词恢复正常大小（1.0x），保持清晰主题高亮；
 * 4. 极致性能：基于 Word 索引状态缓存防抖，帧率 60fps 零 GC 压力。
 */
data class KaraokeStyle(
    val sungColor: Int,
    val activeColor: Int,
    val upcomingColor: Int,
    val activeScale: Float = 1.0f,
    val activeShadowColor: Int = 0,
    val activeShadowRadius: Float = 0f
)

object KaraokeHelper {

    private class KaraokeTag(
        val lineTimestamp: Long,
        var wipeSpan: KaraokeLineWipeSpan?,
        var lastWipeX: Float = -1f,
        var activeWordIndex: Int = 0,
        var rawOverflow: Float = 0f,
        var isOverflow: Boolean = false,
        var textStartX: Float = 0f,
        var textEndX: Float = 0f
    )

    private data class WordSpanInfo(
        val word: LyricWord,
        val start: Int,
        val end: Int
    )

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val ir = 1f - r
        val a = (Color.alpha(from) * ir + Color.alpha(to) * r).toInt()
        val red = (Color.red(from) * ir + Color.red(to) * r).toInt()
        val g = (Color.green(from) * ir + Color.green(to) * r).toInt()
        val b = (Color.blue(from) * ir + Color.blue(to) * r).toInt()
        return Color.argb(a, red, g, b)
    }

    // 默认皮肤样式 (DEFAULT / 氛围联动画卷 —— 背景自带 50~70% 暗调叠层，关闭 GPU BlurMaskFilter 消除离屏 RenderTarget)
    val DEFAULT_STYLE = KaraokeStyle(
        sungColor = Color.WHITE,                        // 纯白高亮 (100% 纯白)
        activeColor = Color.WHITE,                      // 纯白跟唱高亮光束
        upcomingColor = Color.parseColor("#66FFFFFF"),  // 40% 优雅半透明白
        activeScale = 1.0f,
        activeShadowColor = 0,
        activeShadowRadius = 0f
    )

    // 毛玻璃皮肤样式 (GLASSMORPHISM)
    val GLASSMORPHISM_STYLE = KaraokeStyle(
        sungColor = Color.WHITE,                        // 纯白高亮 (按照图二字体更白的来，100% 纯白无衰减)
        activeColor = Color.WHITE,                      // 纯白跟唱高亮光束
        upcomingColor = Color.parseColor("#66FFFFFF"),  // 40% 磨砂半透明白，与未唱行严格保持统一
        activeScale = 1.0f,
        activeShadowColor = 0,                          // 无阴影突变，确保擦除中与显示完毕后字体纯白与透明度严格一致
        activeShadowRadius = 0f
    )

    // 极简霓虹皮肤样式 (MINIMAL_NEON)
    val MINIMAL_NEON_STYLE = KaraokeStyle(
        sungColor = Color.parseColor("#00FFCC"),        // 电光青绿
        activeColor = Color.parseColor("#80FFFF"),      // 闪耀青白
        upcomingColor = Color.parseColor("#3300FFCC"),  // 低透明暗青
        activeScale = 1.0f,
        activeShadowColor = Color.parseColor("#8000FFCC"),
        activeShadowRadius = 15f
    )

    // 动感声浪皮肤样式 (VIBRANT_WAVE)
    val VIBRANT_WAVE_STYLE = KaraokeStyle(
        sungColor = Color.parseColor("#00E5FF"),        // 电波青蓝
        activeColor = Color.parseColor("#FFFF00"),      // 动感亮黄
        upcomingColor = Color.parseColor("#55FFFFFF"),  // 半透明白
        activeScale = 1.0f,
        activeShadowColor = Color.parseColor("#8000E5FF"),
        activeShadowRadius = 10f
    )

    // 复古黑胶唱片皮肤样式 (VINYL_PLAYER)
    val VINYL_STYLE = KaraokeStyle(
        sungColor = Color.parseColor("#FFB74D"),        // 暖金琥珀
        activeColor = Color.parseColor("#FFE082"),      // 明亮纯金
        upcomingColor = Color.parseColor("#55FFFFFF"),  // 半透明暖白
        activeScale = 1.0f,
        activeShadowColor = Color.parseColor("#80FFB74D"),
        activeShadowRadius = 8f
    )

    // 沉浸多级渐隐皮肤样式 (IMMERSIVE)
    val IMMERSIVE_STYLE = KaraokeStyle(
        sungColor = Color.parseColor("#FF758C"),        // 珊瑚粉红
        activeColor = Color.parseColor("#FF9EAF"),      // 柔亮珊瑚红
        upcomingColor = Color.parseColor("#55FFFFFF"),  // 半透明白
        activeScale = 1.0f,
        activeShadowColor = Color.parseColor("#80FF799D"),
        activeShadowRadius = 8f
    )

    /**
     * KTV 经典从左至右逐字平滑渐变擦除着色器 (基于 LinearGradient + Matrix 本地坐标变换，实现 60FPS 零 GC)
     */
    class KaraokeLineWipeSpan(
        var startX: Float,
        var endX: Float,
        var wipeX: Float,
        var sungColor: Int,
        var activeColor: Int,
        var upcomingColor: Int
    ) : CharacterStyle(), UpdateAppearance {
        private val localMatrix = android.graphics.Matrix()
        private var baseShader: LinearGradient? = null
        private var lastSung = 0
        private var lastActive = 0
        private var lastUpcoming = 0
        private val featherPx = 8.0f

        override fun updateDrawState(tp: TextPaint) {
            val span = (endX - startX).coerceAtLeast(1f)
            val r = ((wipeX - startX) / span).coerceIn(0f, 1f)

            if (r <= 0f) {
                tp.shader = null
                tp.color = upcomingColor
                return
            }
            if (r >= 1f) {
                tp.shader = null
                tp.color = sungColor
                return
            }

            // 关键：重置 Paint 基础颜色与 Alpha 为完全不透明纯白，避免 TextView 默认残存的半透明底色衰减 LinearGradient
            tp.color = Color.WHITE
            tp.alpha = 255

            // 车规级零 GC 关键：构建基准羽化 Shader [sungColor -> activeColor -> upcomingColor]
            // TileMode.CLAMP 使得 x < 0 自动取 sungColor，x > 2*feather 自动取 upcomingColor
            if (baseShader == null || sungColor != lastSung || activeColor != lastActive || upcomingColor != lastUpcoming) {
                val colors = intArrayOf(sungColor, activeColor, upcomingColor)
                val positions = floatArrayOf(0f, 0.5f, 1f)
                baseShader = LinearGradient(
                    0f, 0f, featherPx * 2f, 0f,
                    colors,
                    positions,
                    Shader.TileMode.CLAMP
                )
                lastSung = sungColor
                lastActive = activeColor
                lastUpcoming = upcomingColor
            }

            // 纯矩阵零 GC 变换：将羽化中心 (featherPx) 精准对齐到当前瞬时 wipeX 坐标，彻底杜绝每帧 new LinearGradient
            localMatrix.reset()
            localMatrix.setTranslate(wipeX - featherPx, 0f)
            baseShader!!.setLocalMatrix(localMatrix)

            tp.shader = baseShader
        }
    }

    // 字块与歌词分词高效 LRU 缓存，杜绝 60FPS 逐帧重复拆分与分配
    private val wordSpanCache = object : LinkedHashMap<String, List<WordSpanInfo>>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<WordSpanInfo>>?): Boolean {
            return size > 48
        }
    }

    /**
     * 清理卡拉OK字块缓存 (TrimMemory 阶段调用)
     */
    fun clearWordSpanCache() {
        synchronized(wordSpanCache) {
            wordSpanCache.clear()
        }
    }

    private fun getOrBuildWordSpans(line: LyricLine, text: String, nextLineTimestamp: Long?): List<WordSpanInfo> {
        val cacheKey = "${line.timestamp}_${text.hashCode()}_${nextLineTimestamp ?: 0L}"
        synchronized(wordSpanCache) {
            wordSpanCache[cacheKey]?.let { return it }
        }

        val rawWords = line.getOrGenerateWords(nextLineTimestamp)
        if (rawWords.isEmpty()) return emptyList()

        val words = ArrayList<LyricWord>(rawWords.size * 2)
        for (w in rawWords) {
            val len = w.text.length
            if (len > 1 && !w.text.trim().contains(' ')) {
                val charDur = (w.duration / len).coerceAtLeast(15L)
                for (ci in 0 until len) {
                    words.add(
                        LyricWord(
                            text = w.text[ci].toString(),
                            startTime = w.startTime + ci * charDur,
                            duration = charDur
                        )
                    )
                }
            } else {
                words.add(w)
            }
        }

        val wordSpans = ArrayList<WordSpanInfo>(words.size)
        var curIdx = 0
        for (w in words) {
            val idx = text.indexOf(w.text, curIdx)
            if (idx != -1) {
                val end = idx + w.text.length
                wordSpans.add(WordSpanInfo(w, idx, end))
                curIdx = end
            } else {
                val start = curIdx.coerceAtMost(text.length)
                val end = (curIdx + w.text.length).coerceAtMost(text.length)
                if (start < end) {
                    wordSpans.add(WordSpanInfo(w, start, end))
                    curIdx = end
                }
            }
        }

        synchronized(wordSpanCache) {
            wordSpanCache[cacheKey] = wordSpans
        }
        return wordSpans
    }

    fun applyKaraoke(
        textView: TextView,
        line: LyricLine,
        nextLineTimestamp: Long?,
        currentPositionMs: Long,
        style: KaraokeStyle,
        marqueeEnabled: Boolean = true
    ) {
        val text = line.text
        if (text.isBlank()) return

        val wordSpans = getOrBuildWordSpans(line, text, nextLineTimestamp)
        if (wordSpans.isEmpty()) return

        val firstStart = wordSpans.first().word.startTime
        val lastWordStart = wordSpans.last().word.startTime
        val lastEnd = wordSpans.last().let { it.word.startTime + it.word.duration }

        val existingTag = textView.tag as? KaraokeTag
        val isSameLine = existingTag != null && existingTag.lineTimestamp == line.timestamp

        val rawOverflow: Float
        val isOverflow: Boolean
        val textStartX: Float
        val textEndX: Float

        if (isSameLine && existingTag != null && existingTag.textEndX > 0f) {
            rawOverflow = existingTag.rawOverflow
            isOverflow = existingTag.isOverflow
            textStartX = existingTag.textStartX
            textEndX = existingTag.textEndX
        } else {
            // 1. 换行/首次进入时，执行一次视口与排版边界精确测算 (基于小欧 3D 形象避让与 splitRatio)
            var lyricsSkin: LyricsSkin? = null
            var pRunner: android.view.ViewParent? = textView.parent
            while (pRunner != null) {
                if (pRunner is LyricsSkin) {
                    lyricsSkin = pRunner
                    break
                }
                pRunner = pRunner.parent
            }
            val density = textView.context.resources.displayMetrics.density
            val areaView = lyricsSkin?.getLyricsArea()
            val viewportW = lyricsSkin?.getAccurateLyricsViewportWidth()
                ?: (areaView?.width?.toFloat() ?: (textView.parent as? android.view.View)?.width?.toFloat() ?: 1000f)
            val itemPadding = (textView.totalPaddingLeft + textView.totalPaddingRight).toFloat()
            val safeContentW = (viewportW - itemPadding).coerceAtLeast(50f)

            // 以 Paint 全量精确测算真实文字物理渲染宽度 (包含字距 letterSpacing 与字号)
            val measuredTextWidth = textView.paint.measureText(text)
            rawOverflow = measuredTextWidth - safeContentW

            // 临界保护安全阈值 (约 8dp / 12px)：
            val thresholdPx = density * 8f
            isOverflow = rawOverflow > -thresholdPx

            // 锁定歌词视口裁切层级
            if (areaView is android.view.ViewGroup && !areaView.clipChildren) {
                areaView.clipChildren = true
                areaView.clipToPadding = true
            }
            var cRunner: android.view.ViewParent? = textView.parent
            while (cRunner != null && cRunner !== areaView && cRunner is android.view.ViewGroup) {
                if (cRunner.clipChildren) cRunner.clipChildren = false
                if (cRunner.clipToPadding) cRunner.clipToPadding = false
                cRunner = cRunner.parent
            }

            // 2. 排版模式与控件宽度自适应调整 (彻底根除末字吞字截断)
            if (marqueeEnabled) {
                textView.setHorizontallyScrolling(true)
                if (textView.maxLines != 1) textView.maxLines = 1
                textView.ellipsize = null
                if (isOverflow) {
                    val requiredWidth = (measuredTextWidth + itemPadding + density * 120f).toInt()
                    val lp = textView.layoutParams
                    if (lp != null && lp.width < requiredWidth) {
                        lp.width = requiredWidth
                        textView.requestLayout()
                    }
                    if (textView.gravity != (android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)) {
                        textView.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    }
                } else {
                    if (textView.translationX != 0f) textView.translationX = 0f
                    val lp = textView.layoutParams
                    val defaultW = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    if (lp != null && lp.width != defaultW) {
                        lp.width = defaultW
                        textView.requestLayout()
                    }
                    val align = lyricsSkin?.lyricsTextAlignment ?: android.view.Gravity.START
                    val targetGrav = align or android.view.Gravity.CENTER_VERTICAL
                    if (textView.gravity != targetGrav) {
                        textView.gravity = targetGrav
                    }
                }
            } else {
                textView.setHorizontallyScrolling(false)
                if (textView.maxLines != Integer.MAX_VALUE) textView.maxLines = Integer.MAX_VALUE
                if (textView.translationX != 0f) textView.translationX = 0f
                val lp = textView.layoutParams
                if (lp != null && lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    textView.requestLayout()
                }
                val align = lyricsSkin?.lyricsTextAlignment ?: android.view.Gravity.START
                val targetGrav = align or android.view.Gravity.CENTER_VERTICAL
                if (textView.gravity != targetGrav) {
                    textView.gravity = targetGrav
                }
            }

            // 获取排版精确坐标
            val initialLayout = textView.layout
            val layoutW = if (initialLayout != null && initialLayout.lineCount > 0) {
                initialLayout.getLineRight(0) - initialLayout.getLineLeft(0)
            } else 0f
            val totalTextWidth = maxOf(measuredTextWidth, layoutW).coerceAtLeast(1f)

            textStartX = if (initialLayout != null && initialLayout.lineCount > 0) {
                initialLayout.getPrimaryHorizontal(0)
            } else 0f
            textEndX = textStartX + totalTextWidth
        }

        // 当前 TextView 的排版布局对象 (供多行判定与逐字坐标提取复用)
        val layout = textView.layout

        // 状态 1：整行尚未唱到，全行展示优雅半透明底色
        if (currentPositionMs < firstStart) {
            updateSynchronizedMarquee(
                textView = textView,
                currentPositionMs = currentPositionMs,
                firstStart = firstStart,
                lastWordStart = lastWordStart,
                lastEnd = lastEnd,
                nextLineTimestamp = nextLineTimestamp,
                rawOverflow = rawOverflow,
                isOverflow = isOverflow,
                marqueeEnabled = marqueeEnabled
            )
            val oldTag = textView.tag as? KaraokeTag
            if (oldTag != null && oldTag.lineTimestamp == line.timestamp && oldTag.activeWordIndex == -1) {
                return
            }
            textView.paint.shader = null
            val ssb = SpannableStringBuilder(text)
            ssb.setSpan(ForegroundColorSpan(style.upcomingColor), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            textView.text = ssb
            textView.setShadowLayer(0f, 0f, 0f, 0)
            textView.tag = KaraokeTag(line.timestamp, null, textStartX, -1, rawOverflow, isOverflow, textStartX, textEndX)
            return
        }

        // 状态 2：整行已全部唱完，全行常驻高饱和已唱颜色与加粗
        if (currentPositionMs >= lastEnd) {
            updateSynchronizedMarquee(
                textView = textView,
                currentPositionMs = currentPositionMs,
                firstStart = firstStart,
                lastWordStart = lastWordStart,
                lastEnd = lastEnd,
                nextLineTimestamp = nextLineTimestamp,
                rawOverflow = rawOverflow,
                isOverflow = isOverflow,
                marqueeEnabled = marqueeEnabled
            )
            val oldTag = textView.tag as? KaraokeTag
            if (oldTag != null && oldTag.lineTimestamp == line.timestamp && oldTag.activeWordIndex == 9999) {
                return
            }
            textView.paint.shader = null
            val ssb = SpannableStringBuilder(text)
            ssb.setSpan(ForegroundColorSpan(style.sungColor), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            textView.text = ssb
            if (style.activeShadowRadius > 0f) {
                textView.setShadowLayer(style.activeShadowRadius, 0f, 0f, style.activeShadowColor)
            } else {
                textView.setShadowLayer(0f, 0f, 0f, 0)
            }
            textView.tag = KaraokeTag(line.timestamp, null, textEndX, 9999, rawOverflow, isOverflow, textStartX, textEndX)
            return
        }

        // 状态 3：正在演唱中，计算当前毫秒在整行文字上的精确水平擦除坐标 currentWipeX
        var foundWipeX: Float? = null
        var activeWordIndex = 0
        for (i in wordSpans.indices) {
            val spanInfo = wordSpans[i]
            val w = spanInfo.word
            val wEnd = w.startTime + w.duration
            if (currentPositionMs in w.startTime until wEnd) {
                activeWordIndex = i
                val dur = w.duration.coerceAtLeast(1L)
                val p = ((currentPositionMs - w.startTime).toFloat() / dur.toFloat()).coerceIn(0f, 1f)

                val charStartX: Float
                val charEndX: Float
                val canUseLayout = layout != null && layout.lineCount > 0 && spanInfo.end <= layout.getLineEnd(0)
                if (canUseLayout) {
                    charStartX = layout!!.getPrimaryHorizontal(spanInfo.start)
                    charEndX = layout.getPrimaryHorizontal(spanInfo.end)
                } else {
                    val sPx = textView.paint.measureText(text, 0, spanInfo.start)
                    val ePx = textView.paint.measureText(text, 0, spanInfo.end)
                    charStartX = textStartX + sPx
                    charEndX = textStartX + ePx
                }
                foundWipeX = charStartX + (charEndX - charStartX) * p
                break
            }
        }

        val currentWipeX = if (foundWipeX != null) {
            foundWipeX
        } else {
            val pastSpanIndex = wordSpans.indexOfLast { (it.word.startTime + it.word.duration) <= currentPositionMs }
            if (pastSpanIndex != -1) {
                val pastSpan = wordSpans[pastSpanIndex]
                val pastEndMs = pastSpan.word.startTime + pastSpan.word.duration
                val pastEndX = if (layout != null && layout.lineCount > 0 && pastSpan.end <= layout.getLineEnd(0)) {
                    layout.getPrimaryHorizontal(pastSpan.end)
                } else {
                    textStartX + textView.paint.measureText(text, 0, pastSpan.end)
                }
                val nextSpan = wordSpans.getOrNull(pastSpanIndex + 1)
                if (nextSpan != null && nextSpan.word.startTime > pastEndMs) {
                    // 平滑线性过渡词间空隙（如空格或节奏停顿），消除词间停滞与跳变
                    val gapDur = (nextSpan.word.startTime - pastEndMs).toFloat()
                    val gapProgress = ((currentPositionMs - pastEndMs).toFloat() / gapDur).coerceIn(0f, 1f)
                    val nextStartX = if (layout != null && layout.lineCount > 0 && nextSpan.start <= layout.getLineEnd(0)) {
                        layout.getPrimaryHorizontal(nextSpan.start)
                    } else {
                        textStartX + textView.paint.measureText(text, 0, nextSpan.start)
                    }
                    pastEndX + (nextStartX - pastEndX) * gapProgress
                } else {
                    pastEndX
                }
            } else {
                textStartX
            }
        }

        // 演唱中跑马灯同步：以恒定线速度匀速向左平移超长歌词
        updateSynchronizedMarquee(
            textView = textView,
            currentPositionMs = currentPositionMs,
            firstStart = firstStart,
            lastWordStart = lastWordStart,
            lastEnd = lastEnd,
            nextLineTimestamp = nextLineTimestamp,
            rawOverflow = rawOverflow,
            isOverflow = isOverflow,
            marqueeEnabled = marqueeEnabled
        )

        // 多行换行模式 (当跑马灯关闭时，超长文字自动折行)：
        // 采用精准的逐字/逐词 Spannable 变色擦除，完美支持折行后的优雅跟唱！
        if (layout != null && layout.lineCount > 1) {
            val oldTag = textView.tag as? KaraokeTag
            if (oldTag != null && oldTag.lineTimestamp == line.timestamp && oldTag.activeWordIndex == activeWordIndex && oldTag.wipeSpan == null) {
                return
            }
            textView.paint.shader = null
            val ssb = SpannableStringBuilder(text)
            for (i in wordSpans.indices) {
                val spanInfo = wordSpans[i]
                val w = spanInfo.word
                val wEnd = w.startTime + w.duration
                when {
                    wEnd <= currentPositionMs -> {
                        ssb.setSpan(ForegroundColorSpan(style.sungColor), spanInfo.start, spanInfo.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(StyleSpan(Typeface.BOLD), spanInfo.start, spanInfo.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    currentPositionMs in w.startTime until wEnd -> {
                        ssb.setSpan(ForegroundColorSpan(style.activeColor), spanInfo.start, spanInfo.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(StyleSpan(Typeface.BOLD), spanInfo.start, spanInfo.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    else -> {
                        ssb.setSpan(ForegroundColorSpan(style.upcomingColor), spanInfo.start, spanInfo.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            textView.text = ssb
            if (style.activeShadowRadius > 0f) {
                if (textView.shadowRadius != style.activeShadowRadius || textView.shadowColor != style.activeShadowColor) {
                    textView.setShadowLayer(style.activeShadowRadius, 0f, 0f, style.activeShadowColor)
                }
            } else if (textView.shadowRadius > 0f) {
                textView.setShadowLayer(0f, 0f, 0f, 0)
            }
            textView.tag = KaraokeTag(line.timestamp, null, 0f, activeWordIndex, rawOverflow, isOverflow, textStartX, textEndX)
            return
        }

        // 高性能零 GC / 零重排更新模式：若已挂载 Span，仅更新坐标并触发局部重绘
        val oldTag = textView.tag as? KaraokeTag
        if (oldTag != null && oldTag.lineTimestamp == line.timestamp && oldTag.wipeSpan != null) {
            if (Math.abs(currentWipeX - oldTag.lastWipeX) < 0.1f) {
                return
            }
            oldTag.wipeSpan?.apply {
                this.startX = textStartX
                this.endX = textEndX
                this.wipeX = currentWipeX
                this.sungColor = style.sungColor
                this.activeColor = style.activeColor
                this.upcomingColor = style.upcomingColor
            }
            oldTag.lastWipeX = currentWipeX
            oldTag.activeWordIndex = activeWordIndex
            textView.invalidate()
            return
        }

        // 首次进入当前行演唱阶段，构造整行擦除 Span（字号恒定 1.0x，排版绝对零跳动）
        val span = KaraokeLineWipeSpan(
            startX = textStartX,
            endX = textEndX,
            wipeX = currentWipeX,
            sungColor = style.sungColor,
            activeColor = style.activeColor,
            upcomingColor = style.upcomingColor
        )
        val ssb = SpannableStringBuilder(text)
        ssb.setSpan(span, 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        textView.text = ssb
        if (style.activeShadowRadius > 0f) {
            if (textView.shadowRadius != style.activeShadowRadius || textView.shadowColor != style.activeShadowColor) {
                textView.setShadowLayer(style.activeShadowRadius, 0f, 0f, style.activeShadowColor)
            }
        } else if (textView.shadowRadius > 0f) {
            textView.setShadowLayer(0f, 0f, 0f, 0)
        }
        textView.tag = KaraokeTag(line.timestamp, span, currentWipeX, activeWordIndex, rawOverflow, isOverflow, textStartX, textEndX)
    }

    /**
     * 基于精准起止时间与恒定速度计算的超长歌词匀速跑马灯
     * 解决逐字单字时长不一导致往左移动时速率一快一慢、忽停忽冲的眩晕问题，
     * 严格以恒定线速度 v = overflow / scrollDuration 匀速平移，
     * 并在唱完这一句最后一个字之前完整呈现整句内容。
     */
    private fun updateSynchronizedMarquee(
        textView: TextView,
        currentPositionMs: Long,
        firstStart: Long,
        lastWordStart: Long,
        lastEnd: Long,
        nextLineTimestamp: Long?,
        rawOverflow: Float,
        isOverflow: Boolean,
        marqueeEnabled: Boolean
    ) {
        if (!marqueeEnabled || !isOverflow) {
            if (textView.translationX != 0f) {
                textView.translationX = 0f
            }
            return
        }

        val density = textView.context.resources.displayMetrics.density
        // 终点安全缓冲（32dp / 约 48px）：
        // 确保跑马灯向左滚到底后，最后一个字及其字间距 100% 呈现在歌词视口内，绝不贴边或被截断！
        val endBufferPx = density * 32f
        val overflow = rawOverflow.coerceAtLeast(0f) + endBufferPx

        // === 严格恒定线速度匀速跑马灯 ===
        val rawDuration = lastEnd - firstStart
        val lineDuration = if (rawDuration > 400L) {
            rawDuration
        } else {
            if (nextLineTimestamp != null && nextLineTimestamp > firstStart) {
                (nextLineTimestamp - firstStart).coerceIn(1200L, 10000L)
            } else {
                3000L
            }
        }

        // 起步保持微小缓冲（约 8% 总时长，150~350ms）：开唱时先在原位静止片刻，便于目光锚定首字
        val startHold = (lineDuration * 0.08f).toLong().coerceIn(150L, 350L)
        val scrollStart = firstStart + startHold

        // 核心约束：在唱完这一句最后一个字前（即唱到最后一个字起点 lastWordStart 或唱完前）就要显示完整句内容
        val targetEnd = if (lastWordStart > scrollStart + 300L) {
            minOf(lastWordStart, lastEnd - 300L)
        } else {
            (lastEnd - 300L).coerceAtLeast(scrollStart + 300L)
        }
        val scrollEnd = targetEnd.coerceAtLeast(scrollStart + 300L)
        val scrollDuration = (scrollEnd - scrollStart).coerceAtLeast(300L)

        // 恒定线速度匀速向左推进 v = overflow / scrollDuration，严禁任何因逐字节奏快慢引起的忽冲忽停
        val targetTranslationX = when {
            currentPositionMs <= scrollStart -> 0f
            currentPositionMs >= scrollEnd -> -overflow
            else -> {
                val linearProgress = (currentPositionMs - scrollStart).toFloat() / scrollDuration.toFloat()
                -overflow * linearProgress
            }
        }

        if (Math.abs(textView.translationX - targetTranslationX) > 0.05f) {
            textView.translationX = targetTranslationX
        }
    }

    /**
     * 重置某 TextView 的卡拉OK状态，还原为普通文本
     */
    fun resetKaraoke(textView: TextView, normalText: String, marqueeEnabled: Boolean = true) {
        textView.tag = null
        textView.paint.shader = null
        textView.translationX = 0f
        textView.text = normalText
        textView.setHorizontallyScrolling(marqueeEnabled)
        val targetW = if (marqueeEnabled) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val lp = textView.layoutParams
        if (lp != null && lp.width != targetW) {
            lp.width = targetW
            textView.requestLayout()
        }
    }
}

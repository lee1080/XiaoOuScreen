package com.carlyrics.skin

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.carlyrics.display.LyricsDisplayView
import com.carlyrics.model.LyricLine

/**
 * 歌词皮肤抽象基类。所有皮肤共享相同 API，可热切换。
 *
 * 统一两栏架构：
 * - 左面板：固定占比容器，内含封面+歌名+歌手，内容居中
 * - 歌词区：歌词展示区域，支持左对齐/居中/右对齐
 * - splitRatio：左面板占总宽比例 (0.2~0.8)，替代旧 horizontalOffset
 * - marqueeEnabled：歌词超宽时自适应滚动（跑马灯）
 */
abstract class LyricsSkin(context: Context) : FrameLayout(context) {

    companion object {
        const val MIN_FONT_SIZE_SP = LyricsDisplayView.MIN_FONT_SIZE_SP
        const val MAX_FONT_SIZE_SP = LyricsDisplayView.MAX_FONT_SIZE_SP
        const val DEFAULT_FONT_SIZE_SP = LyricsDisplayView.DEFAULT_FONT_SIZE_SP
        const val DEFAULT_SPLIT_RATIO = 0.35f
        const val MIN_SPLIT_RATIO = 0.2f
        const val MAX_SPLIT_RATIO = 0.8f

        // 物理基线倍率 (Base Constant Multipliers)
        const val BASE_DISPLAY_SCALE = 1.10f
        const val BASE_INFO_FONT_SCALE = 1.85f

        // 小欧3D形象在小欧屏右侧占用的物理避让宽度 (原 600px，边界往右扩展 100px 后为 500px，歌词视口拓展至 X: 1420)
        const val XIAOOU_AVATAR_WIDTH_PX = 500
    }

    // ==================== 可调参数 ====================

    /** 歌词字号 (sp) */
    open var fontSizeSp: Float = DEFAULT_FONT_SIZE_SP
        set(value) {
            field = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            onFontSizeChanged(field)
        }

    /** 左右分割比例 (0.2~0.8)，代表左面板占总宽度的比例 */
    open var splitRatio: Float = DEFAULT_SPLIT_RATIO
        set(value) {
            field = value.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
            onSplitRatioChanged()
        }

    /** 歌词对齐方式 (Gravity.START / Gravity.CENTER / Gravity.END) */
    open var lyricsTextAlignment: Int = android.view.Gravity.START
        set(value) {
            field = value
            onLyricsTextAlignmentChanged()
        }

    /** 是否开启歌词自适应滚动（跑马灯） */
    open var marqueeEnabled: Boolean = true
        set(value) {
            field = value
            if (!field) {
                cancelMarquee()
            }
            onMarqueeEnabledChanged()
        }

    /** 旧版兼容：水平偏移比例 (-0.3~0.3) 自动转换为 splitRatio */
    open var horizontalOffset: Float = 0f
        set(value) {
            val clamped = value.coerceIn(-0.3f, 0.3f)
            splitRatio = (DEFAULT_SPLIT_RATIO - clamped * 0.5f).coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        }

    /** 画面缩放比例 (0.5~2.0)，驱动左面板（封面/歌名/歌手）真实尺寸缩放。 */
    open var scaleFactor: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            onScaleChanged(field * BASE_DISPLAY_SCALE)
        }

    /** 信息区字体独立缩放比例 (0.5~3.0)，仅影响歌名/歌手/标签等文字，不改变封面/唱片区大小。 */
    open var infoFontScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 3.0f)
            onInfoFontScaleChanged(field * BASE_INFO_FONT_SCALE)
        }

    /** 歌词时间偏移 (ms)。正值=歌词延后，负值=歌词提前。 */
    open var lyricsOffsetMs: Long = 0L

    /** 最近一次播放进度时间戳 (ms)，便于参数变化时立即刷新当前行 */
    open var lastProgressMs: Long = 0L

    /**
     * 是否开启小欧 3D 形象避让。
     * - 投屏在小欧下方时 (Mode.FITNESS / 2037): 为 true，右侧自动预留 500px 物理安全避让区，歌词视口为 1420px (往右扩展 100px)
     * - 投屏在小欧上方时 (Mode.TOP / 2038): 为 false，使用小欧屏物理全屏 1920px 边界
     */
    open var xiaoOuAvoidanceEnabled: Boolean = false
        set(value) {
            field = value
            applyXiaoOuAvoidance(value)
        }

    /**
     * 计算并获取当前歌词区域的高精度净视口宽度 (px)
     * - 左面板宽度完全由 splitRatio 决定: totalW * splitRatio，开关避让绝对固定不变！
     * - 当开启小欧避让 (xiaoOuAvoidanceEnabled) 时：歌词区右边界扣减 500px 形象避让区
     * - 当未开启避让时：歌词区右边界延展至屏幕最右端
     */
    open fun getAccurateLyricsViewportWidth(): Float {
        val totalW = if (width > 0) width.toFloat() else 1920f
        val leftW = totalW * splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val avatarW = if (xiaoOuAvoidanceEnabled) {
            val aW = if (totalW < 1000f) totalW * (XIAOOU_AVATAR_WIDTH_PX.toFloat() / 1920f) else XIAOOU_AVATAR_WIDTH_PX.toFloat()
            aW
        } else 0f
        val areaW = totalW - leftW - avatarW
        return areaW.coerceAtLeast(100f)
    }

    /**
     * 获取歌词文字可用的实际净宽度 (扣除 TextView 自身的水平 Padding)
     */
    open fun getLyricsContentSafeWidth(textView: TextView? = null): Float {
        val viewportW = getAccurateLyricsViewportWidth()
        val tvPadding = if (textView != null) (textView.totalPaddingLeft + textView.totalPaddingRight).toFloat() else 0f
        return (viewportW - tvPadding).coerceAtLeast(50f)
    }

    /** 跑马灯动画引用，用于取消上一个动画 */
    protected var marqueeAnimator: ValueAnimator? = null

    // ---- 子类必须实现 ----

    abstract fun setLyrics(lines: List<LyricLine>)
    abstract fun updateProgress(positionMs: Long): Int
    abstract fun setCoverUrl(url: String?)
    abstract fun setTrackInfo(title: String, artist: String)

    // ---- 子类可选覆写 (回调) ----

    open fun onFontSizeChanged(sp: Float) {}
    open fun onSplitRatioChanged() {}
    open fun onLyricsTextAlignmentChanged() {}
    open fun onMarqueeEnabledChanged() {}
    open fun onScaleChanged(scale: Float) {}
    open fun onInfoFontScaleChanged(scale: Float) {}

    /** 获取承载主要水平内容 (左侧面板 + 歌词区) 的 ContentRow 布局 */
    open fun getContentRow(): View? = null

    /** 获取左面板 Container (用于截图/悬浮窗独立显示) */
    open fun getLeftPanel(): View? = null

    /** 获取歌词 Container */
    open fun getLyricsArea(): View? = null

    /**
     * 更新左右面板的排版与几何尺寸：
     * 1. contentRowLayout 自身永远占满 100% 物理宽度 (rightMargin 恒为 0)
     * 2. 左面板 (封面+歌名+歌手) 采用绝对定宽 (width = totalW * splitRatio, weight = 0f)，
     *    无论开启还是关闭小欧形象避让，绝对不发生任何位移或缩放！固定歌名和歌词模块位置！
     * 3. 歌词区填满剩余空间 (width = 0, weight = 1f)，由自身的 rightMargin 承担小欧 3D 形象避让。
     *    开启避让时右边距为 500px (视口截断至 X=1420)，关闭时为 0px (视口延展至全屏 X=1920)。
     */
    open fun updatePanelLayouts() {
        val row = getContentRow()
        val left = getLeftPanel()
        val lyrics = getLyricsArea()

        // 1. 确保 ContentRow 自身永远无外边距
        if (row != null) {
            val rowLp = row.layoutParams as? MarginLayoutParams
            if (rowLp != null && rowLp.rightMargin != 0) {
                rowLp.rightMargin = 0
                row.layoutParams = rowLp
            }
        }

        val totalW = if (width > 0) width else 1920
        val sp = splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        val leftW = (totalW * sp).toInt()

        // 2. 左面板定宽定锚
        if (left != null) {
            val leftLp = left.layoutParams as? LinearLayout.LayoutParams
            if (leftLp != null) {
                var changed = false
                if (leftLp.width != leftW) {
                    leftLp.width = leftW
                    changed = true
                }
                if (leftLp.weight != 0f) {
                    leftLp.weight = 0f
                    changed = true
                }
                if (changed) {
                    left.layoutParams = leftLp
                    left.requestLayout()
                }
            }
        }

        // 3. 歌词区填满剩余空间并由 rightMargin 避让
        if (lyrics != null) {
            val lyricsLp = lyrics.layoutParams as? LinearLayout.LayoutParams
            if (lyricsLp != null) {
                val targetMargin = if (xiaoOuAvoidanceEnabled) {
                    if (totalW < 1000) (totalW * (XIAOOU_AVATAR_WIDTH_PX.toFloat() / 1920f)).toInt() else XIAOOU_AVATAR_WIDTH_PX
                } else 0
                var changed = false
                if (lyricsLp.width != 0) {
                    lyricsLp.width = 0
                    changed = true
                }
                if (lyricsLp.weight != 1f) {
                    lyricsLp.weight = 1f
                    changed = true
                }
                if (lyricsLp.rightMargin != targetMargin) {
                    lyricsLp.rightMargin = targetMargin
                    changed = true
                }
                if (changed) {
                    lyrics.layoutParams = lyricsLp
                    lyrics.requestLayout()
                }
            }
        }
    }

    /** 应用小欧 3D 避让安全右边距 (500px 或 0px) */
    open fun applyXiaoOuAvoidance(avoid: Boolean) {
        updatePanelLayouts()
        onSplitRatioChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            updatePanelLayouts()
        }
    }

    /** 销毁资源/取消动画 */
    open fun onDestroy() {
        cancelMarquee()
    }

    /** 设置占位提示文本 */
    open fun setPlaceholderText(text: String) {}

    // ---- 内部 Helper: 跑马灯滚动 ----

    protected fun applyMarquee(targetView: TextView, lyricsAreaWidth: Int, currentTimestamp: Long, nextTimestamp: Long) {
        // 卡拉OK系统由 KaraokeHelper 全权驱动高精度平移与边界排版，避免双动画竞争覆盖
        cancelMarquee(targetView)
        if (!marqueeEnabled) {
            targetView.translationX = 0f
            targetView.setHorizontallyScrolling(false)
            targetView.maxLines = Integer.MAX_VALUE
            if (targetView.layoutParams != null && targetView.layoutParams.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                targetView.layoutParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                targetView.requestLayout()
            }
            targetView.gravity = lyricsTextAlignment or android.view.Gravity.CENTER_VERTICAL
        }
    }

    protected fun applyMarquee(targetView: TextView, currentTimestamp: Long, nextTimestamp: Long) {
        applyMarquee(targetView, 0, currentTimestamp, nextTimestamp)
    }

    protected fun cancelMarquee(exceptView: TextView? = null) {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
    }
}

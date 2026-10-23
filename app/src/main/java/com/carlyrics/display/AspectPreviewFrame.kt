package com.carlyrics.display

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * 保持真实悬浮窗/仪表盘有效区域等比例的预览外壳容器
 * - 外壳固定高度 (如 340dp)，背景为暗灰色 0xFF2C2C2E (作为上下/左右比例留白填充)
 * - 内部持有真正的 skinView，按悬浮窗实际分辨率/裁剪边界 (targetW x targetH) 渲染布局，
 *   并通过整体 Scale 矩阵缩放居中展示，做到 100% 所见即所得（包含字体大小、元素比例、排版布局完全一模一样）
 */
class AspectPreviewFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var targetW: Int = 1920
    private var targetH: Int = 384
    val innerFrame: FrameLayout

    private val xiaoOuIndicatorView = FrameLayout(context).apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false

        val avatarImg = android.widget.ImageView(context).apply {
            setImageResource(com.car.xiaoouscreen.R.drawable.xiaoou_3d_avatar_half)
            scaleType = android.widget.ImageView.ScaleType.FIT_END
        }

        // 实车物理规格 (1920x384 黄金基准):
        // 小欧避让窗口宽 500px，贴副屏右侧 (X: 1420..1920)，歌词视口拓展 100px (0..1420)
        // 内部布局右侧 paddingEnd = 136px 避让副屏物理黑边，3D 形象贴底 (Y: 0..384, X: 1426..1784)
        val avatarLp = LayoutParams(358, 384).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            rightMargin = 136
        }
        addView(avatarImg, avatarLp)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false

        innerFrame = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
        }
        addView(innerFrame, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setSkinView(skinView: View) {
        innerFrame.removeAllViews()
        innerFrame.addView(skinView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val indicatorLp = LayoutParams(com.carlyrics.skin.LyricsSkin.XIAOOU_AVATAR_WIDTH_PX, LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.END
        }
        innerFrame.addView(xiaoOuIndicatorView, indicatorLp)
    }

    fun setXiaoOuIndicatorVisible(visible: Boolean) {
        xiaoOuIndicatorView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun updateTargetResolution(width: Int, height: Int) {
        val w = if (width > 0) width else 1920
        val h = if (height > 0) height else 384
        if (targetW != w || targetH != h) {
            targetW = w
            targetH = h
            requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)

        if (parentW > 0 && parentH > 0 && targetW > 0 && targetH > 0) {
            val aspect = targetW.toFloat() / targetH.toFloat()
            var calcW = parentW
            var calcH = (parentW / aspect).toInt()

            if (calcH > parentH) {
                calcH = parentH
                calcW = (parentH * aspect).toInt()
            }

            // 按照悬浮窗实际的真实物理尺寸 targetW x targetH 进行完全测算与排版布局
            val childWSpec = MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY)
            val childHSpec = MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
            innerFrame.measure(childWSpec, childHSpec)

            // 计算整体渲染缩放比例，应用硬件加速变换
            val scale = calcW.toFloat() / targetW.toFloat()
            innerFrame.pivotX = 0f
            innerFrame.pivotY = 0f
            innerFrame.scaleX = scale
            innerFrame.scaleY = scale

            setMeasuredDimension(parentW, parentH)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val parentW = width
        val parentH = height

        if (parentW > 0 && parentH > 0 && targetW > 0 && targetH > 0) {
            val aspect = targetW.toFloat() / targetH.toFloat()
            var calcW = parentW
            var calcH = (parentW / aspect).toInt()

            if (calcH > parentH) {
                calcH = parentH
                calcW = (parentH * aspect).toInt()
            }

            val l = (parentW - calcW) / 2
            val t = (parentH - calcH) / 2

            // 给 innerFrame 真实目标分辨率空间 targetW x targetH
            // 结合 pivotX=0, pivotY=0 和 scaleX=scale, scaleY=scale，缩放后的视觉区域恰好落在 (l, t, l + calcW, t + calcH) 居中呈现
            innerFrame.layout(l, t, l + targetW, t + targetH)
        }
    }
}

package com.carlyrics.display

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * 歌名与演唱者超长往复三段式平滑 View (BouncingTitleView)
 *
 * 物理运动规范 (严格遵循用户步骤):
 * 1. [起点 0f 坐标] 静态停顿 1.5 秒
 * 2. 往左移动: 从 0f 缓慢加速 -> 匀速 -> 缓慢减速 到 -maxScrollXPx 右侧内容展现完整停稳
 * 3. [-maxScrollXPx 坐标] 停顿 1.5 秒
 * 4. 往右移动: 从 -maxScrollXPx 缓慢加速 -> 匀速 -> 缓慢减速 回到 0f 左侧内容展现完整停稳
 * 5. [起点 0f 坐标] 静态停顿 1.5 秒后重复步骤 2，无限循环往复！
 */
class BouncingTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var bounceAnimator: ValueAnimator? = null
    private var isBouncing = false
    private var lastText: String = ""

    init {
        setSingleLine(true)
        maxLines = 1
        ellipsize = null // 禁用系统 ... 截断
    }

    fun updateTitleText(newText: String) {
        if (lastText != newText) {
            lastText = newText
            text = newText
            stopBounce()
            requestLayout()
            post { checkAndStartBounce() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val textStr = text?.toString() ?: ""
        if (textStr.isNotEmpty()) {
            val textWidth = paint.measureText(textStr)
            val paddingTotal = paddingLeft + paddingRight
            val density = resources.displayMetrics.density
            val neededWidth = (textWidth + paddingTotal + (density * 32f)).toInt()

            if (neededWidth > measuredWidth) {
                setMeasuredDimension(neededWidth, measuredHeight)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { checkAndStartBounce() }
    }

    fun checkAndStartBounce() {
        stopBounce()

        val parentView = parent as? ViewGroup ?: return
        parentView.clipChildren = true
        parentView.clipToPadding = true

        val parentWidth = parentView.width
        if (parentWidth <= 0) return

        val availableW = (parentWidth - parentView.paddingLeft - parentView.paddingRight).toFloat()
        if (availableW <= 0) return

        val totalContentW = measuredWidth.toFloat()

        if (totalContentW <= availableW) {
            translationX = 0f
            alpha = 1f
            return
        }

        val maxScrollXPx = totalContentW - availableW
        val density = resources.displayMetrics.density
        // 移动时长 (按 8dp/s 舒适平滑速率计算单向时长)
        val moveDurationMs = ((maxScrollXPx / (density * 8f)) * 1000).toLong().coerceIn(3500L, 25000L)

        isBouncing = true
        startAccelerateDeceleratePingPong(maxScrollXPx, moveDurationMs)
    }

    private fun startAccelerateDeceleratePingPong(maxScrollXPx: Float, moveDurationMs: Long) {
        if (!isBouncing || !isAttachedToWindow) return

        translationX = 0f
        alpha = 1f

        // ValueAnimator 往复倒放
        val anim = ValueAnimator.ofFloat(0f, -maxScrollXPx).apply {
            duration = moveDurationMs
            startDelay = 1500L // 步骤 1: 在起点 0f 停顿 1.5 秒
            // 步骤 2 & 4: 缓慢加速 -> 匀速 -> 缓慢减速
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE // 往复

            addUpdateListener { animator ->
                if (isBouncing) {
                    translationX = animator.animatedValue as Float
                }
            }
        }

        // 两端 1.5 秒精确停顿控制
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                if (!isBouncing || !isAttachedToWindow) return
                // 每次到了端点 (无论是右侧展示完整还是回到左侧展示完整)，暂停动画 1.5 秒
                anim.pause()
                postDelayed({
                    if (isBouncing && isAttachedToWindow && anim.isPaused) {
                        anim.resume()
                    }
                }, 1500L)
            }
        })

        bounceAnimator = anim
        anim.start()
    }

    fun stopBounce() {
        isBouncing = false
        bounceAnimator?.cancel()
        bounceAnimator = null
        translationX = 0f
        alpha = 1f
    }

    override fun onDetachedFromWindow() {
        stopBounce()
        super.onDetachedFromWindow()
    }
}

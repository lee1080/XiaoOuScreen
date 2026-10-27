package com.car.xiaoouscreen.ui.settings.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 车规级浅色拟物风修饰符套件 (Light Neumorphism Modifiers - 100% 硬件加速版)
 * 采用原生 GPU 硬件加速的 DrawScope 绘制图层，彻底废除 BlurMaskFilter CPU 软件光栅化，稳锁 60FPS。
 */

/**
 * 1. 拟物悬浮大外壳 / 侧边栏 (neuFloatingShell)
 * 双向外投影（右下暗影 #A6B4C4 偏移 +4dp，左上高光 #FFFFFF 偏移 -3dp）+ 1dp 半透明白边
 */
fun Modifier.neuFloatingShell(
    cornerRadius: Dp = 28.dp,
    borderWidth: Dp = 1.dp
): Modifier = this
    .drawBehind {
        val cornerPx = cornerRadius.toPx()
        // 1. 左上高光 (#FFFFFF)
        drawRoundRect(
            color = NeuColors.ShadowLight.copy(alpha = 0.65f),
            topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
            size = Size(size.width + 2.dp.toPx(), size.height + 2.dp.toPx()),
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )
        // 2. 右下暗影 (#A6B4C4)
        drawRoundRect(
            color = NeuColors.ShadowDarkFloating.copy(alpha = 0.28f),
            topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )
    }
    .background(
        brush = NeuColors.ShellGradient,
        shape = RoundedCornerShape(cornerRadius)
    )
    .border(
        width = borderWidth,
        color = NeuColors.BorderShell,
        shape = RoundedCornerShape(cornerRadius)
    )

/**
 * 2. 拟物凸起卡片 (neuCard)
 * 右下暗影 #C7D1DC 偏移 +3dp，左上高光 #FFFFFF 偏移 -2dp
 */
fun Modifier.neuCard(
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = NeuColors.BorderCard
): Modifier = this
    .drawBehind {
        val cornerPx = cornerRadius.toPx()
        // 左上高光
        drawRoundRect(
            color = NeuColors.ShadowLight.copy(alpha = 0.70f),
            topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
            size = Size(size.width + 1.dp.toPx(), size.height + 1.dp.toPx()),
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )
        // 右下暗影
        drawRoundRect(
            color = NeuColors.ShadowDark.copy(alpha = 0.22f),
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )
    }
    .background(
        brush = NeuColors.CardGradient,
        shape = RoundedCornerShape(cornerRadius)
    )
    .border(
        width = borderWidth,
        color = borderColor,
        shape = RoundedCornerShape(cornerRadius)
    )

/**
 * 3. 拟物凹陷槽 (neuInset)
 * 用于文本输入框底槽、滑轨底槽、三段式分段底槽、菜单选中态等
 * 纯硬件加速描边：左上内暗影 #C4CED9，右下内高光 #FFFFFF
 */
fun Modifier.neuInset(
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 1.dp,
    backgroundColor: Color = NeuColors.InsetBg
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .drawWithContent {
        drawContent()
        val cornerPx = cornerRadius.toPx()
        // 左上微暗描边
        drawRoundRect(
            color = NeuColors.InsetDarkShadow.copy(alpha = 0.40f),
            topLeft = Offset(0f, 0f),
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = 1.5.dp.toPx())
        )
        // 右下微高光描边
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
            size = Size(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = 1.dp.toPx())
        )
    }
    .border(
        width = borderWidth,
        color = NeuColors.BorderInset,
        shape = RoundedCornerShape(cornerRadius)
    )

/**
 * 4. 拟物按钮修饰符 (neuButton)
 * 未按下时外凸，按下或激活时切换为 neuInset 内凹阴影
 */
fun Modifier.neuButton(
    isPressed: Boolean = false,
    isPrimary: Boolean = false,
    cornerRadius: Dp = 12.dp
): Modifier = if (isPressed) {
    this.neuInset(cornerRadius = cornerRadius)
} else if (isPrimary) {
    this
        .drawBehind {
            val cornerPx = cornerRadius.toPx()
            drawRoundRect(
                color = NeuColors.AccentBlue.copy(alpha = 0.35f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        }
        .background(
            brush = NeuColors.PrimaryBlueGradient,
            shape = RoundedCornerShape(cornerRadius)
        )
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.35f),
            shape = RoundedCornerShape(cornerRadius)
        )
} else {
    this
        .drawBehind {
            val cornerPx = cornerRadius.toPx()
            // 左上高光
            drawRoundRect(
                color = NeuColors.ShadowLight.copy(alpha = 0.75f),
                topLeft = Offset(-1.5.dp.toPx(), -1.5.dp.toPx()),
                size = Size(size.width + 1.dp.toPx(), size.height + 1.dp.toPx()),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
            // 右下暗影
            drawRoundRect(
                color = NeuColors.ShadowDark.copy(alpha = 0.20f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
        }
        .background(
            brush = NeuColors.ButtonGradient,
            shape = RoundedCornerShape(cornerRadius)
        )
        .border(
            width = 1.dp,
            color = NeuColors.BorderButton,
            shape = RoundedCornerShape(cornerRadius)
        )
}

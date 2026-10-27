package com.car.xiaoouscreen.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import kotlin.math.roundToInt

/**
 * 1. 拟物开关 (NeuSwitch)
 * 内凹跑道底槽（关闭态 #E1E8F0，开启态蓝渐变 #3B82F6 → #2563EB），
 * 圆形凸起拨块内部中心带 5dp 状态指示灯（开启态发蓝光，关闭态为暗灰）。
 * 外包 48dp 最小车规触控热区。
 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val trackWidth = if (isCompact) 42.dp else 52.dp
    val trackHeight = if (isCompact) 22.dp else 28.dp
    val thumbSize = if (isCompact) 18.dp else 22.dp
    val padding = 2.dp

    val maxOffset = trackWidth - thumbSize - padding * 2
    val animatedOffset by animateDpAsState(
        targetValue = if (checked) maxOffset else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "thumbOffset"
    )

    // 车规级触控外框
    Box(
        modifier = modifier
            .size(width = if (isCompact) 46.dp else 56.dp, height = if (isCompact) 32.dp else 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        // 内凹跑道底槽
        Box(
            modifier = Modifier
                .size(width = trackWidth, height = trackHeight)
                .neuInset(
                    cornerRadius = if (isCompact) 11.dp else 14.dp,
                    backgroundColor = if (checked) Color(0xFF2563EB) else Color(0xFFE1E8F0)
                )
                .then(
                    if (checked) {
                        Modifier.background(
                            brush = NeuColors.PrimaryBlueGradient,
                            shape = RoundedCornerShape(if (isCompact) 11.dp else 14.dp)
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            // 圆形凸起拨块
            Box(
                modifier = Modifier
                    .offset(x = animatedOffset + padding)
                    .size(thumbSize)
                    .neuButton(isPressed = false, cornerRadius = if (isCompact) 9.dp else 11.dp),
                contentAlignment = Alignment.Center
            ) {
                // 中心指示微光原点
                Box(
                    modifier = Modifier
                        .size(if (isCompact) 4.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (checked) Color(0xFF60A5FA) else Color(0xFF94A3B8)
                        )
                        .then(
                            if (checked) {
                                Modifier.shadow(
                                    elevation = 4.dp,
                                    shape = CircleShape,
                                    ambientColor = Color(0xFF38BDF8),
                                    spotColor = Color(0xFF38BDF8)
                                )
                            } else Modifier
                        )
                )
            }
        }
    }
}

/**
 * 2. 居中基准横向滑动控制条 (NeuCenteredSlider)
 * 内凹滑轨 + 正中间 50% 处绘制一条垂直零点刻度线 (neu-slider-center-tick)
 * + 蓝色渐变填充条 + 拟物圆形凸起滑块 + 左右 − / + 拟物步进按钮
 * + 右上角实时数值槽与「居中默认值 / 已自定义」标签。
 */
@Composable
fun NeuCenteredSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    step: Int,
    defaultCenter: Int,
    unit: String = "",
    label: String,
    desc: String,
    modifier: Modifier = Modifier
) {
    val isDefaultCenter = value == defaultCenter
    val fraction = ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .neuCard(cornerRadius = 16.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部信息与状态徽章
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )
                    // 居中默认值 / 已自定义 徽章
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isDefaultCenter) Color(0xFFD1FAE5) else Color(0xFFE2E8F0),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isDefaultCenter) "居中默认值" else "已自定义",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDefaultCenter) Color(0xFF047857) else Color(0xFF475569)
                        )
                    }
                }
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 右上角实时数值槽
            Box(
                modifier = Modifier
                    .neuInset(cornerRadius = 8.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$value$unit",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.AccentBlue
                )
            }
        }

        // 滑轨与左右步进按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 左侧 − 步进按钮
            NeuStepButton(
                text = "−",
                onClick = {
                    val next = (value - step).coerceAtLeast(min)
                    onValueChange(next)
                }
            )

            // 中间内凹滑轨容器 (双击重置居中)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .neuInset(cornerRadius = 14.dp)
                    .pointerInput(min, max, step) {
                        detectTapGestures(
                            onDoubleTap = { onValueChange(defaultCenter) },
                            onTap = { offset ->
                                val pct = (offset.x / size.width).coerceIn(0f, 1f)
                                val raw = min + pct * (max - min)
                                val stepped = (raw / step).roundToInt() * step
                                onValueChange(stepped.coerceIn(min, max))
                            }
                        )
                    }
                    .pointerInput(min, max, step) {
                        detectDragGestures { change, _ ->
                            val pct = (change.position.x / size.width).coerceIn(0f, 1f)
                            val raw = min + pct * (max - min)
                            val stepped = (raw / step).roundToInt() * step
                            onValueChange(stepped.coerceIn(min, max))
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val totalWidthPx = constraints.maxWidth.toFloat()
                val thumbRadiusPx = with(density) { 11.dp.toPx() }
                val availableWidth = (totalWidthPx - thumbRadiusPx * 2f).coerceAtLeast(0f)
                val thumbOffsetPx = fraction * availableWidth
                val fillWidthDp = with(density) { (thumbOffsetPx + thumbRadiusPx).toDp() }

                // 蓝色渐变进度条
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(fillWidthDp)
                        .background(
                            brush = NeuColors.PrimaryBlueGradient,
                            shape = RoundedCornerShape(14.dp)
                        )
                )

                // 正中间 50% 刻度基准线 (neu-slider-center-tick)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .height(16.dp)
                        .background(
                            color = Color(0xFF64748B).copy(alpha = 0.55f),
                            shape = RoundedCornerShape(1.dp)
                        )
                )

                // 拟物圆形凸起滑块
                Box(
                    modifier = Modifier
                        .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                        .size(24.dp)
                        .neuButton(isPressed = false, cornerRadius = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(NeuColors.AccentBlue)
                    )
                }
            }

            // 右侧 + 步进按钮
            NeuStepButton(
                text = "+",
                onClick = {
                    val next = (value + step).coerceAtMost(max)
                    onValueChange(next)
                }
            )
        }
    }
}

/**
 * 步进按钮 (48dp 触控热区)
 */
@Composable
fun NeuStepButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(38.dp)
            .neuButton(isPressed = isPressed, cornerRadius = 10.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = NeuColors.TextPrimary
        )
    }
}

/**
 * 3. 拟物复选框 (NeuCheckbox)
 * 22dp 圆角方形内凹槽，勾选后切换为蓝色凸起渐变并绘制白色对勾。
 * 附带 48dp 最小触控热区。
 */
@Composable
fun NeuCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .then(
                    if (checked) {
                        Modifier
                            .neuButton(isPressed = false, isPrimary = true, cornerRadius = 6.dp)
                    } else {
                        Modifier
                            .neuInset(cornerRadius = 6.dp)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已勾选",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * 4. 拟物下拉选择框 (NeuDropdown)
 * 内凹槽样式下拉选择框，带右侧下箭头与拟物弹出菜单。
 */
@Composable
fun <T> NeuDropdown(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neuInset(cornerRadius = 12.dp)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = itemLabel(selectedItem),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = NeuColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "展开选项",
                tint = NeuColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    brush = NeuColors.CardGradient,
                    shape = RoundedCornerShape(12.dp)
                )
                .border(1.dp, NeuColors.BorderCard, RoundedCornerShape(12.dp))
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) NeuColors.AccentBlue else NeuColors.TextPrimary
                        )
                    },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 5. 拟物 ? 说明小按钮 (NeuInfoButton)
 * 21dp 圆形凸起 ? 小图标按钮，点击后触发全局 popover
 */
@Composable
fun NeuInfoButton(
    info: AnnotationInfo,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onShowInfo(info) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .neuButton(isPressed = false, cornerRadius = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "?",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.AccentBlue,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 拟物通用按钮组件 (车规级 48dp 最小热区)
 */
@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    textColor: Color? = null,
    cornerRadius: Dp = 12.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val finalTextColor = textColor ?: if (isPrimary) Color.White else NeuColors.TextPrimary

    Box(
        modifier = modifier
            .neuButton(
                isPressed = isPressed,
                isPrimary = isPrimary,
                cornerRadius = cornerRadius
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = finalTextColor
        )
    }
}

/**
 * 三段式对齐选择控件 (左 / 中 / 右)
 */
@Composable
fun NeuSegmentedAlign(
    alignMode: String,
    onAlignChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuInset(cornerRadius = 14.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val options = listOf("left" to "左", "center" to "中", "right" to "右")
        options.forEach { (mode, text) ->
            val isSelected = alignMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuButton(
                        isPressed = isSelected,
                        cornerRadius = 10.dp
                    )
                    .clickable { onAlignChange(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (isSelected) NeuColors.AccentBlue else NeuColors.TextPrimary
                )
            }
        }
    }
}

/**
 * 车规级悬浮拟物 Toast
 */
@Composable
fun NeuToast(
    message: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { 40 }),
        modifier = modifier
    ) {
        if (!message.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .neuCard(cornerRadius = 16.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NeuColors.AccentBlue)
                )
                Text(
                    text = message,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuColors.TextPrimary
                )
            }
        }
    }
}

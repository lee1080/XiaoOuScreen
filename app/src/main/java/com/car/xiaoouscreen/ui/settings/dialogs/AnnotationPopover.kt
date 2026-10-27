package com.car.xiaoouscreen.ui.settings.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard

/**
 * 功能注释说明浮层卡片 (Annotation Popover Card)
 * 包含标题、详细说明、蓝色底推荐值框、关闭按钮，点击外部自动关闭
 */
@Composable
fun AnnotationPopover(
    info: AnnotationInfo,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 半透明遮罩 (点击外部关闭)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            // 拟物卡片主体 (阻止点击穿透)
            Box(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 380.dp)
                    .neuCard(cornerRadius = 20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 拦截点击事件 */ }
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 头部：图标 + 标题 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(NeuColors.AccentBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "i",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = info.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                        }

                        // 关闭按钮 ✕
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .neuButton(isPressed = false, cornerRadius = 8.dp)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    // 详细说明内容
                    Text(
                        text = info.content,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = NeuColors.TextSecondary
                    )

                    // 蓝色底推荐值框
                    if (info.recommend.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = Color(0xFFEFF6FF),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = info.recommend,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.AccentBlue,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

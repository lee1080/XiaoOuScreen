package com.car.xiaoouscreen.ui.settings.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.model.AppUpdateUiState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset

/**
 * 软件更新弹窗 (适配车载宽屏 1920x384 / 1920x720)
 */
@Composable
fun UpdateDialog(
    state: AppUpdateUiState,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = state.updateInfo ?: return
    val isDownloading = state.isDownloading

    Dialog(
        onDismissRequest = {
            if (!isDownloading && !info.forceUpdate) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (!isDownloading && !info.forceUpdate) onDismiss()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 680.dp)
                    .neuFloatingShell(cornerRadius = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 头部：图标 + 标题 + 版本标签 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .neuButton(cornerRadius = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "软件更新",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = info.title.ifBlank { "发现新版本" },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.TextPrimary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFE0F2FE), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "v${info.versionName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeuColors.AccentBlue
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    if (!info.fileSizeMB.isNullOrBlank()) {
                                        Text(
                                            text = "大小: ${info.fileSizeMB}",
                                            fontSize = 11.sp,
                                            color = NeuColors.TextSecondary
                                        )
                                    }
                                    if (!info.updateTime.isNullOrBlank()) {
                                        Text(
                                            text = "发布: ${info.updateTime}",
                                            fontSize = 11.sp,
                                            color = NeuColors.TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (!isDownloading && !info.forceUpdate) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .neuButton(cornerRadius = 8.dp)
                                    .clickable { onDismiss() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = NeuColors.TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // 更新日志内容区
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .neuInset(cornerRadius = 14.dp)
                            .padding(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "更新内容：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.TextPrimary
                            )
                            Text(
                                text = info.changelog.ifBlank { "优化车载用户体验与稳定性" },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    // 进度条与状态展示区
                    if (isDownloading) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "正在下载更新安装包...",
                                    fontSize = 12.sp,
                                    color = NeuColors.TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${state.downloadProgress}%",
                                    fontSize = 12.sp,
                                    color = NeuColors.AccentBlue,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            LinearProgressIndicator(
                                progress = { state.downloadProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = NeuColors.AccentBlue,
                                trackColor = Color(0xFFE2E8F0)
                            )
                        }
                    }

                    // 错误提示
                    if (!state.downloadError.isNullOrBlank()) {
                        Text(
                            text = "❌ ${state.downloadError}",
                            fontSize = 11.sp,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // 底部按钮操作栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!info.forceUpdate && !isDownloading) {
                            NeuButton(
                                text = "稍后再说",
                                onClick = onDismiss,
                                isPrimary = false,
                                modifier = Modifier.width(100.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        if (isDownloading) {
                            NeuButton(
                                text = "下载中 (${state.downloadProgress}%)",
                                onClick = { },
                                isPrimary = true,
                                modifier = Modifier.width(140.dp)
                            )
                        } else {
                            NeuButton(
                                text = if (state.downloadError != null) "重试更新" else "立即更新",
                                onClick = onStartDownload,
                                isPrimary = true,
                                modifier = Modifier.width(120.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

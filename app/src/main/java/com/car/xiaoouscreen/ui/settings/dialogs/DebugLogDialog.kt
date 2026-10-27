package com.car.xiaoouscreen.ui.settings.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import com.carlyrics.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志管理弹窗
 * 提供实时日志查看、一键清空日志、一键复制到剪贴板，以及导出保存日志到公共/外部文件夹
 */
@Composable
fun DebugLogDialog(
    onDismiss: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    var logContent by remember {
        mutableStateOf(AppLogger.readAppLog().ifBlank { "(当前暂无调试日志记录，开启调试开关后系统将自动收集)" })
    }

    // Android SAF 原生文件管理器导出选择器
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(logContent.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                onShowToast("调试日志已成功保存至所选文件")
            } catch (e: Exception) {
                onShowToast("写入日志文件失败: ${e.message}")
            }
        } else {
            onShowToast("已取消保存日志")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 540.dp, max = 780.dp)
                    .fillMaxHeight(0.86f)
                    .neuFloatingShell(cornerRadius = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 头部
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
                                    .size(36.dp)
                                    .neuButton(cornerRadius = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Assignment,
                                    contentDescription = "调试日志",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "车载调试日志管理",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Text(
                                    text = "捕获歌词源调度、气象报文与实车信号状态，便于排查问题",
                                    fontSize = 11.sp,
                                    color = NeuColors.TextSecondary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .neuButton(cornerRadius = 8.dp)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    // 日志内容展示区
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .neuInset(cornerRadius = 14.dp)
                            .padding(12.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = logContent,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeuColors.TextPrimary,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // 底部操作栏：清空日志、复制日志、保存到文件、关闭
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 清除日志
                            NeuButton(
                                text = "清除日志",
                                onClick = {
                                    AppLogger.clearAppLog()
                                    logContent = "(日志已清空)"
                                    onShowToast("调试日志已成功清空")
                                },
                                textColor = NeuColors.AccentRose
                            )

                            // 复制日志
                            NeuButton(
                                text = "复制日志",
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("xiaoou_log", logContent)
                                    cm?.setPrimaryClip(clip)
                                    onShowToast("已复制日志至剪贴板")
                                },
                                textColor = NeuColors.AccentBlue
                            )

                            // 保存到文件夹 (调起系统原生文件管理器)
                            NeuButton(
                                text = "保存到文件夹",
                                onClick = {
                                    try {
                                        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        val defaultFileName = "xiaoou_log_$timeStr.txt"
                                        createDocumentLauncher.launch(defaultFileName)
                                    } catch (e: Exception) {
                                        onShowToast("调起系统文件管理器失败: ${e.message}")
                                    }
                                },
                                textColor = NeuColors.AccentGreen
                            )
                        }

                        NeuButton(
                            text = "关闭",
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

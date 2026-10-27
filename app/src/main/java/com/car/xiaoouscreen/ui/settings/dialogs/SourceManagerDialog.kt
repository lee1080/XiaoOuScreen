package com.car.xiaoouscreen.ui.settings.dialogs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCheckbox
import com.car.xiaoouscreen.ui.settings.model.LyricSourceItemState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset

/**
 * 歌词源插件批量管理弹窗 (规范重构版)
 *
 * 核心规范：
 * 1. 移除一键测速及每一行的测速按钮，移除延迟时间显示
 * 2. 移除原先顶部的快捷添加自定义单源输入行
 * 3. 每个源后方提供单独「删除」按钮；GD Studio 作为默认源受保护不可手动删除
 * 4. 前方复选框支持多选点选，顶部提供全选、反选与「删除所选」
 * 5. 移除原先底部的恢复默认列表、取消、确认保存按钮；将「导入源」按钮移至弹窗底部
 */
@Composable
fun SourceManagerDialog(
    sources: List<LyricSourceItemState>,
    onDismiss: () -> Unit,
    onSwitchToImport: () -> Unit,
    onToggleEnabled: (String) -> Unit,
    onDeleteSource: (String) -> Unit,
    onDeleteMultiple: (List<String>) -> Unit,
    onShowToast: (String) -> Unit
) {
    // 选中的复选框 ID 列表
    val selectedIds = remember { mutableStateListOf<String>() }

    fun isDefaultSource(src: LyricSourceItemState): Boolean {
        return src.id.equals("gdstudio_proxy", ignoreCase = true) ||
                src.id.equals("gdstudio", ignoreCase = true) ||
                src.name.contains("GD Studio", ignoreCase = true)
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
                    .widthIn(min = 520.dp, max = 740.dp)
                    .fillMaxHeight(0.85f)
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
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "源管理",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "歌词源插件批量管理",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Text(
                                    text = "勾选启用参与识别，支持点选批量删除或从下方导入新插件",
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

                    // 顶部快捷批量操作栏 (全选、反选、删除所选)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeuButton(
                                text = "全选",
                                onClick = {
                                    selectedIds.clear()
                                    selectedIds.addAll(sources.map { it.id })
                                }
                            )
                            NeuButton(
                                text = "反选",
                                onClick = {
                                    val currentAll = sources.map { it.id }.toSet()
                                    val inverted = currentAll - selectedIds.toSet()
                                    selectedIds.clear()
                                    selectedIds.addAll(inverted)
                                }
                            )
                            NeuButton(
                                text = "删除所选",
                                onClick = {
                                    if (selectedIds.isEmpty()) {
                                        onShowToast("请先在左侧勾选需要删除的歌词源")
                                        return@NeuButton
                                    }
                                    val toDelete = selectedIds.filter { id ->
                                        val item = sources.find { it.id == id }
                                        item != null && !isDefaultSource(item)
                                    }
                                    if (toDelete.isEmpty()) {
                                        onShowToast("默认源 GD Studio 不可删除")
                                    } else {
                                        onDeleteMultiple(toDelete)
                                        selectedIds.removeAll(toDelete.toSet())
                                        onShowToast("已成功删除 ${toDelete.size} 个歌词源")
                                    }
                                },
                                textColor = NeuColors.AccentRose
                            )
                        }

                        Box(
                            modifier = Modifier
                                .neuInset(cornerRadius = 8.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "已选中 ${selectedIds.size} / ${sources.size} 个源",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    // 歌词源插件列表
                    if (sources.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .neuInset(cornerRadius = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "当前暂无可管理的歌词源插件，请点击下方「导入源」添加。",
                                fontSize = 12.sp,
                                color = NeuColors.TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sources, key = { it.id }) { src ->
                                val isChecked = selectedIds.contains(src.id)
                                val isDefault = isDefaultSource(src)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .neuCard(
                                            cornerRadius = 14.dp,
                                            borderColor = if (src.enabled) Color(0xFF93C5FD) else NeuColors.BorderCard
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // 多选勾选框 (控制删除/批量选定)
                                        NeuCheckbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    if (!selectedIds.contains(src.id)) selectedIds.add(src.id)
                                                } else {
                                                    selectedIds.remove(src.id)
                                                }
                                            }
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = src.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeuColors.TextPrimary
                                                )
                                                if (isDefault) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(
                                                            text = "默认内置源",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF64748B)
                                                        )
                                                    }
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                color = if (src.official) Color(0xFFDBEAFE) else Color(0xFFD1FAE5),
                                                                shape = RoundedCornerShape(4.dp)
                                                            )
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(
                                                            text = if (src.official) "内置源" else "导入源",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (src.official) Color(0xFF1D4ED8) else Color(0xFF047857)
                                                        )
                                                    }
                                                }
                                            }

                                            Text(
                                                text = src.url,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = NeuColors.TextMuted,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }

                                    // 单项右侧：启用开关状态 + 单项删除按钮
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // 启用状态切换
                                        Box(
                                            modifier = Modifier
                                                .clickable { onToggleEnabled(src.id) }
                                                .background(
                                                    if (src.enabled) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (src.enabled) "已启用" else "已停用",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (src.enabled) NeuColors.AccentGreen else NeuColors.TextMuted
                                            )
                                        }

                                        // 单项删除按钮 (默认源 GD Studio 禁用不可删除)
                                        if (isDefault) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "不可删除",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = NeuColors.TextMuted
                                                )
                                            }
                                        } else {
                                            NeuButton(
                                                text = "删除",
                                                onClick = {
                                                    onDeleteSource(src.id)
                                                    selectedIds.remove(src.id)
                                                    onShowToast("已删除歌词源：「${src.name}」")
                                                },
                                                textColor = NeuColors.AccentRose
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 弹窗底部操作栏：将「导入源」按钮移动至下方
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeuButton(
                            text = "+ 导入歌词源插件...",
                            onClick = onSwitchToImport,
                            isPrimary = true
                        )

                        NeuButton(
                            text = "完成",
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

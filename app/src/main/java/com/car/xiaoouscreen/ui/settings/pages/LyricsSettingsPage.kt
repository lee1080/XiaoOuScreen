package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCenteredSlider
import com.car.xiaoouscreen.ui.settings.components.NeuCheckbox
import com.car.xiaoouscreen.ui.settings.components.NeuDropdown
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.LyricSourceItemState
import com.car.xiaoouscreen.ui.settings.model.LyricsState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset

/**
 * 模块 4：歌词设置（独立一级菜单）
 * 汇集歌词核心源与数据接口：
 *   - 识别与歌词源（歌词源插件列表、导入源、删除选中、打开管理弹窗）
 *   - 偏好源下拉选择
 *   - 歌词时间偏移居中滑块（-2000ms ~ +2000ms）
 *   - 高级接口凭据（自定义歌词 URL、自定义封面 URL、API Key）
 */
@Composable
fun LyricsSettingsPage(
    state: LyricsState,
    sources: List<LyricSourceItemState>,
    onToggleSourceEnabled: (String) -> Unit,
    onReorderSources: (List<String>) -> Unit = {},
    onOpenSourceManagerDialog: () -> Unit,
    onToggleShowDefaultClockWhenNoLyrics: (Boolean) -> Unit = {},
    onTimeOffsetChange: (Int) -> Unit,
    onSaveCustomLyricUrl: (String) -> Unit,
    onSaveCustomCoverUrl: (String) -> Unit,
    onSaveCustomApiKey: (String) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabledSourcesCount = sources.count { it.enabled }
    var lyricUrlInput by remember(state.customLyricUrl) { mutableStateOf(state.customLyricUrl) }
    var coverUrlInput by remember(state.customCoverUrl) { mutableStateOf(state.customCoverUrl) }
    var apiKeyInput by remember(state.customApiKey) { mutableStateOf(state.customApiKey) }

    val localSources = remember(sources) { mutableStateListOf(*sources.toTypedArray()) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 62.dp.toPx() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ====================================================================
        // 4.1 识别与歌词源
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 16.dp)
                        .background(NeuColors.AccentBlue, RoundedCornerShape(2.dp))
                )
                Text(
                    text = "歌词源插件与识别",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }
            Text(
                text = "支持复选框启用、导入源（URL/文件/单源）与批量测速",
                fontSize = 11.sp,
                color = NeuColors.TextMuted
            )
        }

        // 歌词源插件跨列大卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 18.dp, borderColor = Color(0xFFBFDBFE))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 顶部标题栏 + 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "多平台歌词源插件",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFDBEAFE), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "已启用 $enabledSourcesCount / ${sources.size}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.AccentBlue
                                )
                            }
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "歌词源插件管理说明",
                                    content = "勾选下方复选框可开启对应源参与匹配；点击“+ 导入源”可从 URL、文件或单源新增；点击“删除选中源”清理旧源；点击“打开源管理弹窗”进行批量测速与管理。",
                                    recommend = "建议至少保持 2 个歌词源处于勾选状态"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }
                        Text(
                            text = "支持长按拖拽排序歌词源插件，越靠上优先级越高；勾选参与匹配的源将按序轮询匹配",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )
                    }

                    NeuButton(
                        text = "源管理",
                        onClick = onOpenSourceManagerDialog,
                        isPrimary = true
                    )
                }

                // 单排可拖动排序源列表 (匹配专属播放器优先，未匹配按列表优先级依次查询)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    localSources.forEachIndexed { index, sourceItem ->
                        val isBeingDragged = (draggingIndex == index)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isBeingDragged) 10f else 0f)
                                .graphicsLayer {
                                    translationY = if (isBeingDragged) dragOffsetY else 0f
                                    shadowElevation = if (isBeingDragged) 12f else 0f
                                }
                                .neuInset(
                                    cornerRadius = 12.dp,
                                    backgroundColor = if (isBeingDragged) Color(0xFFE0F2FE) else if (sourceItem.enabled) Color(0xFFF0FDF4) else Color(0xFFEFF4F9)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 左侧：复选框 + 序号 + 标题与分类
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    NeuCheckbox(
                                        checked = sourceItem.enabled,
                                        onCheckedChange = { onToggleSourceEnabled(sourceItem.id) }
                                    )

                                    // 优先级序号指示徽章
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (sourceItem.enabled) Color(0xFFDCFCE7) else Color(0xFFE2E8F0),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (sourceItem.enabled) NeuColors.AccentGreen else NeuColors.TextMuted
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = sourceItem.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (sourceItem.enabled) NeuColors.TextPrimary else NeuColors.TextSecondary
                                            )
                                            val isDefault = sourceItem.id.contains("gdstudio", true) || sourceItem.name.contains("GD Studio", true)
                                            if (isDefault) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            color = Color(0xFFF1F5F9),
                                                            shape = RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "默认内置源",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF64748B)
                                                    )
                                                }
                                            } else if (sourceItem.category.isNotEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = sourceItem.category,
                                                        fontSize = 9.sp,
                                                        color = NeuColors.TextSecondary
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = sourceItem.url,
                                            fontSize = 10.sp,
                                            color = NeuColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // 右侧：上移/下移便捷箭头 + 可长按/拖动排序的手柄图标
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (index > 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clickable {
                                                    val tmp = localSources[index]
                                                    localSources[index] = localSources[index - 1]
                                                    localSources[index - 1] = tmp
                                                    onReorderSources(localSources.map { it.id })
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = "上移",
                                                tint = NeuColors.TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    if (index < localSources.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clickable {
                                                    val tmp = localSources[index]
                                                    localSources[index] = localSources[index + 1]
                                                    localSources[index + 1] = tmp
                                                    onReorderSources(localSources.map { it.id })
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "下移",
                                                tint = NeuColors.TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // 拖动手柄图标 (支持手势上下滑动实时排序)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .pointerInput(localSources) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingIndex = index
                                                        dragOffsetY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetY += dragAmount.y
                                                        val currentIdx = draggingIndex ?: return@detectDragGestures
                                                        if (dragOffsetY > itemHeightPx * 0.65f && currentIdx < localSources.lastIndex) {
                                                            val tmp = localSources[currentIdx]
                                                            localSources[currentIdx] = localSources[currentIdx + 1]
                                                            localSources[currentIdx + 1] = tmp
                                                            draggingIndex = currentIdx + 1
                                                            dragOffsetY -= itemHeightPx
                                                        } else if (dragOffsetY < -itemHeightPx * 0.65f && currentIdx > 0) {
                                                            val tmp = localSources[currentIdx]
                                                            localSources[currentIdx] = localSources[currentIdx - 1]
                                                            localSources[currentIdx - 1] = tmp
                                                            draggingIndex = currentIdx - 1
                                                            dragOffsetY += itemHeightPx
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingIndex = null
                                                        dragOffsetY = 0f
                                                        onReorderSources(localSources.map { it.id })
                                                    },
                                                    onDragCancel = {
                                                        draggingIndex = null
                                                        dragOffsetY = 0f
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "拖动排序",
                                            tint = if (isBeingDragged) NeuColors.AccentBlue else NeuColors.TextMuted,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 未匹配到歌词显示默认时钟画面
        // ====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 16.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "未匹配到歌词显示默认时钟画面",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )
                        NeuInfoButton(
                            info = AnnotationInfo(
                                title = "未匹配到歌词显示默认时钟画面",
                                content = "开启后，若当前播放的音乐未能匹配到歌词，小欧屏将自动保持或退回展示默认时钟与天气主界面，避免展示暂无歌词或空白歌词占位。",
                                recommend = "默认保持开启"
                            ),
                            onShowInfo = onShowInfo
                        )
                    }
                    Text(
                        text = "播放音乐未检索到对应歌词时，自动切回默认时钟与天气仪表界面",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuSwitch(
                    checked = state.showDefaultClockWhenNoLyrics,
                    onCheckedChange = onToggleShowDefaultClockWhenNoLyrics
                )
            }
        }

        // ====================================================================
        // 4.2 歌词时间偏移 (居中滑块：-2000ms ~ +2000ms)
        // ====================================================================
        NeuCenteredSlider(
            value = state.timeOffset,
            onValueChange = onTimeOffsetChange,
            min = -2000,
            max = 2000,
            step = 100,
            defaultCenter = 0,
            unit = " ms",
            label = "歌词时间偏移",
            desc = "微调蓝牙或车机音源延迟导致的词曲不同步（默认居中 0 ms）"
        )

        // ====================================================================
        // 4.3 高级接口凭据（自定义歌词 URL、封面 URL、API Key）
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 16.dp)
                        .background(NeuColors.AccentBlue, RoundedCornerShape(2.dp))
                )
                Text(
                    text = "自定义接口与凭据",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }
            Text(
                text = "时间轴微调与私有服务地址配置",
                fontSize = 11.sp,
                color = NeuColors.TextMuted
            )
        }

        // 自定义歌词 URL 与封面 URL（自适应排布：宽度紧张时自适应转为单行满宽）
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isStacked = maxWidth < 840.dp
            if (isStacked) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 自定义歌词 地址URL
                    LyricsUniformInputCard(
                        title = "自定义歌词 地址URL",
                        desc = "配置私有歌词解析服务或外部代理网关",
                        value = lyricUrlInput,
                        onValueChange = { lyricUrlInput = it },
                        onApply = { onSaveCustomLyricUrl(lyricUrlInput) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 自定义封面 地址URL
                    LyricsUniformInputCard(
                        title = "自定义封面 地址URL",
                        desc = "配置高清无损专辑图 CDN 解析服务",
                        value = coverUrlInput,
                        onValueChange = { coverUrlInput = it },
                        onApply = { onSaveCustomCoverUrl(coverUrlInput) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 自定义歌词 地址URL
                    LyricsUniformInputCard(
                        title = "自定义歌词 地址URL",
                        desc = "配置私有歌词解析服务或外部代理网关",
                        value = lyricUrlInput,
                        onValueChange = { lyricUrlInput = it },
                        onApply = { onSaveCustomLyricUrl(lyricUrlInput) },
                        modifier = Modifier.weight(1f)
                    )

                    // 自定义封面 地址URL
                    LyricsUniformInputCard(
                        title = "自定义封面 地址URL",
                        desc = "配置高清无损专辑图 CDN 解析服务",
                        value = coverUrlInput,
                        onValueChange = { coverUrlInput = it },
                        onApply = { onSaveCustomCoverUrl(coverUrlInput) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 自定义 API Key 卡片
        LyricsUniformInputCard(
            title = "API Key 鉴权凭据",
            desc = "私有歌词接口 Token，无鉴权要求可留空",
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            onApply = { onSaveCustomApiKey(apiKeyInput) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 统一尺寸、规范统一的输入框卡片
 */
@Composable
private fun LyricsUniformInputCard(
    title: String,
    desc: String,
    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp)
            .neuCard(cornerRadius = 16.dp)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .neuInset(cornerRadius = 10.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeuColors.TextPrimary
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(NeuColors.AccentBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NeuButton(
                    text = "应用",
                    onClick = onApply,
                    textColor = NeuColors.AccentBlue
                )
            }
        }
    }
}

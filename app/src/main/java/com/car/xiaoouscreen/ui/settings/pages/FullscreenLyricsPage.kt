package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCenteredSlider
import com.car.xiaoouscreen.ui.settings.components.NeuDropdown
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.LyricsState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import com.carlyrics.skin.SkinType

/**
 * 模块 5：全屏歌词设置（独立一级菜单）
 *   - 启用全屏歌词：独立拟物开关大卡片（与左侧菜单栏快捷开关双向同步）
 *   - 【核心要求】删除预览窗口！
 *   - 【核心要求】只有启用了全屏歌词才可以更改下方的设置（外观与排版）
 */
@Composable
fun FullscreenLyricsPage(
    state: LyricsState,
    onToggleMasterEnabled: (Boolean) -> Unit,
    onSkinThemeChange: (String) -> Unit,
    onSplitRatioChange: (Int) -> Unit,
    onCoverScaleChange: (Int) -> Unit,
    onTitleFontSizeChange: (Int) -> Unit,
    onLyricFontSizeChange: (Int) -> Unit,
    onResetSlidersToCenter: () -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ====================================================================
        // 5.1 启用全屏歌词（主开关大卡片，与菜单栏第 5 项双向同步）
        // ====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(
                    cornerRadius = 18.dp,
                    borderColor = if (state.masterEnabled) Color(0xFF93C5FD).copy(alpha = 0.8f) else Color.Transparent
                )
                .padding(18.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "启用全屏歌词",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeuColors.TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (state.masterEnabled) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (state.masterEnabled) "已启用" else "已关闭",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.masterEnabled) NeuColors.AccentGreen else NeuColors.TextSecondary
                            )
                        }
                    }
                    Text(
                        text = "开启后在车机副屏界面全屏展示流动逐字歌词与高保真专辑视觉",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuSwitch(
                    checked = state.masterEnabled,
                    onCheckedChange = onToggleMasterEnabled
                )
            }
        }

        // ====================================================================
        // 5.2 下方分组：外观与排版参数配置（仅在开启全屏歌词后允许修改）
        // ====================================================================
        if (!state.masterEnabled) {
            // 未启用时的优雅提示槽
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuInset(cornerRadius = 14.dp, backgroundColor = Color(0xFFF1F5F9))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ⓘ",
                        fontSize = 14.sp,
                        color = NeuColors.TextMuted
                    )
                    Text(
                        text = "全屏歌词目前处于关闭状态。开启上方「启用全屏歌词」后方可调整下方外观与排版参数。",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }
            }
        }

        // 外观与排版内容区 (未开启时置灰且锁定，完全禁止用户交互与修改)
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!state.masterEnabled) Modifier.alpha(0.38f) else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 标题条与滑块全部归中按钮
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
                            text = "外观与排版参数",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )
                    }

                    NeuButton(
                        text = "参数全部重置默认",
                        onClick = { if (state.masterEnabled) onResetSlidersToCenter() },
                        textColor = NeuColors.TextSecondary
                    )
                }

                // 皮肤主题下拉框卡片 (单卡片精致全宽布局，排版随皮肤自适应：动感波浪居中，其余左对齐；跑马灯默认开启)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuCard(cornerRadius = 16.dp)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "皮肤主题",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                NeuInfoButton(
                                    info = AnnotationInfo(
                                        title = "全屏歌词皮肤主题",
                                        content = "切换全屏歌词界面的材质质感或昼夜配色风格。超长跑马灯已默认常驻开启；除“动感波浪”采用居中对齐外，其余皮肤均自动采用优雅左对齐排版。",
                                        recommend = "推荐：浅色拟物经典 / 液态毛玻璃"
                                    ),
                                    onShowInfo = { if (state.masterEnabled) onShowInfo(it) }
                                )
                            }
                            Text(
                                text = "超长跑马灯默认开启 · 动感波浪居中/其余左对齐",
                                fontSize = 11.sp,
                                color = NeuColors.TextMuted
                            )
                        }

                        val skinMap = listOf(
                            SkinType.DEFAULT.name to SkinType.DEFAULT.label,
                            SkinType.GLASSMORPHISM.name to SkinType.GLASSMORPHISM.label,
                            SkinType.MINIMAL_NEON.name to SkinType.MINIMAL_NEON.label,
                            SkinType.VIBRANT_WAVE.name to SkinType.VIBRANT_WAVE.label,
                            SkinType.VINYL_PLAYER.name to SkinType.VINYL_PLAYER.label,
                            SkinType.IMMERSIVE.name to SkinType.IMMERSIVE.label
                        )

                        val currentThemeKey = when (state.skinTheme) {
                            "liquid_glass" -> SkinType.GLASSMORPHISM.name
                            "dark_streamer" -> SkinType.MINIMAL_NEON.name
                            "minimal_pure" -> SkinType.VIBRANT_WAVE.name
                            "neumorphic" -> SkinType.DEFAULT.name
                            else -> state.skinTheme
                        }

                        NeuDropdown(
                            items = skinMap,
                            selectedItem = skinMap.firstOrNull { it.first == currentThemeKey } ?: skinMap[0],
                            onItemSelected = { if (state.masterEnabled) onSkinThemeChange(it.first) },
                            itemLabel = { it.second }
                        )
                    }
                }

                // 4 个居中滑块（两两双列并排，默认值：左右风格比 25%，封面缩放 85%，歌名字号 27px，歌词字号 88px）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        NeuCenteredSlider(
                            value = state.splitRatio,
                            onValueChange = { if (state.masterEnabled) onSplitRatioChange(it) },
                            min = 15,
                            max = 80,
                            step = 5,
                            defaultCenter = 25,
                            unit = "%",
                            label = "左右风格比",
                            desc = "调节专辑封面区与歌词区的宽度比例（默认 25%）"
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NeuCenteredSlider(
                            value = state.coverScale,
                            onValueChange = { if (state.masterEnabled) onCoverScaleChange(it) },
                            min = 50,
                            max = 150,
                            step = 5,
                            defaultCenter = 85,
                            unit = "%",
                            label = "封面缩放",
                            desc = "缩放全屏歌词左侧专辑封面尺寸（默认 85%）"
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        NeuCenteredSlider(
                            value = state.titleFontSize,
                            onValueChange = { if (state.masterEnabled) onTitleFontSizeChange(it) },
                            min = 14,
                            max = 36,
                            step = 1,
                            defaultCenter = 27,
                            unit = "px",
                            label = "歌名字号",
                            desc = "调节歌曲标题文字大小（默认 27px）"
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NeuCenteredSlider(
                            value = state.lyricFontSize,
                            onValueChange = { if (state.masterEnabled) onLyricFontSizeChange(it) },
                            min = 50,
                            max = 130,
                            step = 2,
                            defaultCenter = 88,
                            unit = "px",
                            label = "歌词字号",
                            desc = "调节全屏主高亮歌词文字大小（默认 88px）"
                        )
                    }
                }
            }

            if (!state.masterEnabled) {
                // 透明拦截遮罩层：完全覆盖在下方外观与排版设置区，拦截并消费所有触摸手势，防止用户修改
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }
        }
    }
}

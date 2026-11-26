package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.car.AmbientLightManager
import com.car.xiaoouscreen.car.LightingColor64
import com.car.xiaoouscreen.ui.settings.components.NeuCenteredSlider
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.SmartSceneState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import kotlin.math.roundToInt

/**
 * 智能场景联动设置页面
 * 车规级简洁交互标准：
 *   1. 顶部并排卡片：转向灯联动 + 变道辅助联动
 *   2. 座舱氛围灯音乐同步总控
 *   3. 联动参数（展开/折叠）：
 *      - 律动色彩模式（封面色彩同步 / 多彩随机流转）
 *      - 基准亮度（10%~100%，含夜间自动减半保护）
 *      - 律动灵敏度（50%~150%）
 *      - 氛围灯运行状态（当前亮度挡位与色号预览）
 */
@Composable
fun SmartScenePage(
    state: SmartSceneState,
    onToggleAmbientSync: (Boolean) -> Unit,
    onSelectColorMode: (isCoverMode: Boolean) -> Unit,
    onToggleFollowCarBrightness: (Boolean) -> Unit,
    onCustomBrightnessChange: (Int) -> Unit,
    onToggleTurnSignalSync: (Boolean) -> Unit,
    onToggleLaneChangeAssist: (Boolean) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ====================================================================
        // 1. 顶部：转向灯联动 与 变道辅助联动 (双卡片并排同一行)
        // ====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1.1 转向灯联动卡片
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuCard(
                        cornerRadius = 18.dp,
                        borderColor = if (state.turnSignalSyncEnabled) Color(0xFF93C5FD).copy(alpha = 0.8f) else Color.Transparent
                    )
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                text = "转向灯联动",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeuColors.TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (state.turnSignalSyncEnabled) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (state.turnSignalSyncEnabled) "已开启" else "已关闭",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.turnSignalSyncEnabled) NeuColors.AccentGreen else NeuColors.TextSecondary
                                )
                            }
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "转向灯联动",
                                    content = "副屏与车辆转向灯及双闪信号实时同步。开启转向灯时，副屏对应侧边框同频呼吸闪烁；开启双闪时双侧同步闪烁，提供余光确认。",
                                    recommend = "建议开启，辅助变道与转向视觉确认。"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }

                        NeuSwitch(
                            checked = state.turnSignalSyncEnabled,
                            onCheckedChange = onToggleTurnSignalSync
                        )
                    }

                    Text(
                        text = "开启转向灯或双闪时，副屏边框同步呼吸闪烁",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 1.2 变道辅助联动卡片
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuCard(
                        cornerRadius = 18.dp,
                        borderColor = if (state.laneChangeAssistEnabled) Color(0xFF93C5FD).copy(alpha = 0.8f) else Color.Transparent
                    )
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                text = "变道辅助联动",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeuColors.TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (state.laneChangeAssistEnabled) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (state.laneChangeAssistEnabled) "已开启" else "已关闭",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.laneChangeAssistEnabled) NeuColors.AccentGreen else NeuColors.TextSecondary
                                )
                            }
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "变道辅助联动",
                                    content = "联动车辆盲区监测系统（BSD/LCA）。侧后方有来车时，副屏侧边显示黄色预警光幕；若此时开启同向转向灯，立即触发红黄交替强闪警示。",
                                    recommend = "建议开启，提升并线变道安全性。"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }

                        NeuSwitch(
                            checked = state.laneChangeAssistEnabled,
                            onCheckedChange = onToggleLaneChangeAssist
                        )
                    }

                    Text(
                        text = "侧后方盲区来车预警与打灯变道危险警示",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ====================================================================
        // 2. 座舱氛围灯音乐同步总控卡片
        // ====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(
                    cornerRadius = 18.dp,
                    borderColor = if (state.ambientSyncMasterEnabled) Color(0xFF93C5FD).copy(alpha = 0.8f) else Color.Transparent
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
                            text = "座舱氛围灯音乐同步",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeuColors.TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (state.ambientSyncMasterEnabled) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (state.ambientSyncMasterEnabled) "已开启" else "已关闭",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.ambientSyncMasterEnabled) NeuColors.AccentGreen else NeuColors.TextSecondary
                            )
                        }

                        NeuInfoButton(
                            info = AnnotationInfo(
                                title = "氛围灯音乐同步",
                                content = "支持车机内所有音乐应用。全车氛围灯随音乐低音节拍实时起伏律动，音乐暂停时平稳恢复常亮，夜间时段自动柔和减半。",
                                recommend = "建议开启，营造沉浸式座舱视听氛围。"
                            ),
                            onShowInfo = onShowInfo
                        )
                    }

                    Text(
                        text = "播放音乐时，全车氛围灯随低音节拍实时律动",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuSwitch(
                    checked = state.ambientSyncMasterEnabled,
                    onCheckedChange = onToggleAmbientSync
                )
            }
        }

        // ====================================================================
        // 3. 折叠/展开配置区：总控开启时展开，关闭时平滑折叠隐退
        // ====================================================================
        AnimatedVisibility(
            visible = state.ambientSyncMasterEnabled,
            enter = expandVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(180))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ====================================================================
                // 3.1 氛围灯色彩模式
                // ====================================================================
                val isCoverSelected = state.colorFollowsCover
                val isSystemColorSelected = !state.colorFollowsCover

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuCard(
                            cornerRadius = 18.dp,
                            borderColor = Color(0xFF93C5FD).copy(alpha = 0.4f)
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 标题行与说明
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "色彩模式",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeuColors.TextPrimary
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (isCoverSelected) Color(0xFFDBEAFE) else Color(0xFFF3E8FF),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isCoverSelected) "封面色彩" else "系统色彩",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCoverSelected) NeuColors.AccentBlue else Color(0xFF9333EA)
                                    )
                                }
                                NeuInfoButton(
                                    info = AnnotationInfo(
                                        title = "色彩模式",
                                        content = "1.【根据封面颜色变换】：切歌时自动识别专辑大面积主色调，整曲保持该主题色，避免频繁跳变。\n\n2.【使用系统设置颜色】：完全保留原车中控氛围灯色彩设置。用户在原车中控设置为随机就是随机，设置为固定单色就为单色，软件仅随音乐节奏起伏律动。",
                                        recommend = "可根据个人喜好自由选择。"
                                    ),
                                    onShowInfo = onShowInfo
                                )
                            }
                        }

                        Text(
                            text = "选择音乐律动时的色彩变化方式",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )

                        // 选项按键排
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 选项按钮 1：根据封面颜色变换
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isCoverSelected) {
                                            Modifier.neuInset(
                                                cornerRadius = 14.dp,
                                                backgroundColor = Color(0xFFF0F7FF)
                                            )
                                        } else {
                                            Modifier.neuCard(
                                                cornerRadius = 14.dp,
                                                borderColor = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    )
                                    .clickable {
                                        onSelectColorMode(true)
                                    }
                                    .padding(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // 指示微光灯
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isCoverSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8))
                                                    .then(
                                                        if (isCoverSelected) {
                                                            Modifier.shadow(
                                                                elevation = 4.dp,
                                                                shape = CircleShape,
                                                                ambientColor = Color(0xFF38BDF8),
                                                                spotColor = Color(0xFF38BDF8)
                                                            )
                                                        } else Modifier
                                                    )
                                            )

                                            Text(
                                                text = "根据封面颜色变换",
                                                fontSize = 13.sp,
                                                fontWeight = if (isCoverSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                                color = if (isCoverSelected) NeuColors.AccentBlue else NeuColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (isCoverSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = NeuColors.AccentBlue,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "已启用",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = "切歌时自动识别专辑大面积主色，整曲锁定该主题色，避免频繁跳色",
                                        fontSize = 10.sp,
                                        color = if (isCoverSelected) NeuColors.AccentBlue.copy(alpha = 0.85f) else NeuColors.TextMuted,
                                        lineHeight = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // 选项按钮 2：使用系统设置颜色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isSystemColorSelected) {
                                            Modifier.neuInset(
                                                cornerRadius = 14.dp,
                                                backgroundColor = Color(0xFFFAF5FF)
                                            )
                                        } else {
                                            Modifier.neuCard(
                                                cornerRadius = 14.dp,
                                                borderColor = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    )
                                    .clickable {
                                        onSelectColorMode(false)
                                    }
                                    .padding(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // 指示微光灯
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSystemColorSelected) Color(0xFFA855F7) else Color(0xFF94A3B8))
                                                    .then(
                                                        if (isSystemColorSelected) {
                                                            Modifier.shadow(
                                                                elevation = 4.dp,
                                                                shape = CircleShape,
                                                                ambientColor = Color(0xFFA855F7),
                                                                spotColor = Color(0xFFA855F7)
                                                            )
                                                        } else Modifier
                                                    )
                                            )

                                            Text(
                                                text = "使用系统设置颜色",
                                                fontSize = 13.sp,
                                                fontWeight = if (isSystemColorSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                                color = if (isSystemColorSelected) Color(0xFF9333EA) else NeuColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (isSystemColorSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = Color(0xFF9333EA),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "已启用",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = "由原车中控控制颜色（单色/随机），软件仅随音乐节奏起伏律动",
                                        fontSize = 10.sp,
                                        color = if (isSystemColorSelected) Color(0xFF9333EA).copy(alpha = 0.85f) else NeuColors.TextMuted,
                                        lineHeight = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // ====================================================================
                // 3.2 氛围灯基准亮度（支持夜间自动柔和减半）
                // ====================================================================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuCard(
                            cornerRadius = 18.dp,
                            borderColor = Color(0xFF93C5FD).copy(alpha = 0.4f)
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 标题行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "基准亮度",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeuColors.TextPrimary
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = Color(0xFFDBEAFE),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${state.customBrightnessPercent}%",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.AccentBlue
                                    )
                                }
                                NeuInfoButton(
                                    info = AnnotationInfo(
                                        title = "基准亮度",
                                        content = "1.【基准亮度】：设置白天律动的基础灯光亮度（10% ~ 100%）。\n\n2.【夜间柔和保护】：傍晚及夜间时段自动按 50% 柔和输出，防止强光干扰夜间行车视线。\n\n3.【暂停平稳常亮】：音乐暂停时，自动按当前基准亮度保持常亮，不刺眼、不闪烁。",
                                        recommend = "建议设置为 80%。"
                                    ),
                                    onShowInfo = onShowInfo
                                )
                            }
                        }

                        Text(
                            text = "设定白天律动基础亮度，夜间自动减半柔和输出，音乐暂停平稳常亮",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )

                        // 基准亮度滑块
                        NeuCenteredSlider(
                            value = state.customBrightnessPercent,
                            onValueChange = onCustomBrightnessChange,
                            min = 10,
                            max = 100,
                            step = 10,
                            defaultCenter = 80,
                            unit = "%",
                            label = "基准亮度",
                            desc = "调节白天律动亮度，夜间自动按 50% 柔和输出"
                        )

                        // 昼夜换算实时看板胶囊
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .neuInset(cornerRadius = 12.dp, backgroundColor = Color(0xFFF8FAFC))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                        text = if (state.isNightMode) "🌙 当前时段: 夜间模式" else "☀️ 当前时段: 白天模式",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.isNightMode) Color(0xFF6366F1) else Color(0xFFEA580C)
                                    )
                                    Text(
                                        text = "·",
                                        fontSize = 11.sp,
                                        color = NeuColors.TextMuted
                                    )
                                    Text(
                                        text = "基准: ${state.customBrightnessPercent}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NeuColors.TextSecondary
                                    )
                                }

                                Text(
                                    text = "实发: ${(state.effectiveScale * 100).roundToInt()}% (最高 ${(10 * state.effectiveScale).roundToInt()} 挡)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (state.isNightMode) Color(0xFF6366F1) else NeuColors.AccentBlue
                                )
                            }
                        }
                    }
                }

                // ====================================================================
                // 3.4 氛围灯运行状态监测
                // ====================================================================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 16.dp)
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "运行状态",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "氛围灯状态",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                            }

                            // 连接状态指示灯
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (state.isServiceConnected) NeuColors.AccentGreen else NeuColors.TextMuted)
                                )
                                Text(
                                    text = if (state.isServiceConnected) "服务已连接" else "服务连接中",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (state.isServiceConnected) NeuColors.AccentGreen else NeuColors.TextMuted
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 实时亮度挡位 (0..10) - 独立局部状态收集，杜绝 25Hz 律动触发整屏重绘
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .neuCard(cornerRadius = 12.dp)
                                    .padding(10.dp)
                            ) {
                                LuminanceMeterCard()
                            }

                            // 当前色号与色彩预览
                            val currentColorRgb = LightingColor64.getColorByIndex(state.currentColorIndex)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .neuCard(cornerRadius = 12.dp)
                                    .padding(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "当前色彩",
                                        fontSize = 10.sp,
                                        color = NeuColors.TextMuted
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color(currentColorRgb))
                                        )
                                        Text(
                                            text = "#${state.currentColorIndex}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeuColors.TextPrimary
                                        )
                                        Text(
                                            text = if (state.colorFollowsCover) "封面色彩" else "系统色彩",
                                            fontSize = 10.sp,
                                            color = NeuColors.TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LuminanceMeterCard() {
    val luminanceLevel by AmbientLightManager.currentLuminance.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "当前亮度",
            fontSize = 10.sp,
            color = NeuColors.TextMuted
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$luminanceLevel",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeuColors.AccentBlue
            )
            Text(
                text = "/ 10 挡",
                fontSize = 11.sp,
                color = NeuColors.TextSecondary
            )

            // 小型动态能量条
            Row(
                modifier = Modifier.padding(start = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (step in 1..10) {
                    val isActive = step <= luminanceLevel
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((6 + step * 1.4f).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isActive) NeuColors.AccentBlue else Color(0xFFCBD5E1))
                    )
                }
            }
        }
    }
}

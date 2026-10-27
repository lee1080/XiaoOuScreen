package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import com.carlyrics.skin.SkinType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCenteredSlider
import com.car.xiaoouscreen.ui.settings.components.NeuCheckbox
import com.car.xiaoouscreen.ui.settings.components.NeuDropdown
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSegmentedAlign
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.LyricSourceItemState
import com.car.xiaoouscreen.ui.settings.model.LyricsState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import kotlin.math.roundToInt

/**
 * 模块 4：全屏歌词设置
 *   - 排第一：预览窗口（1920 × 384 严格锁定 5:1 比例）
 *            【核心】显示小欧形象开关影响右侧虚拟小欧图片避让展示
 *   - 排第二：启用全屏歌词开关
 *   - 下方分组一：外观与排版（滑块归中、皮肤主题、跑马灯、对齐方式、4 个居中滑块）
 *   - 下方分组二：识别与歌词源（歌词源插件大卡片、导入源、删除选中、偏好源下拉）
 *   - 下方分组三：高级（时间偏移居中滑块、自定义歌词URL、封面URL、API Key）
 */
@Composable
fun LyricsPage(
    state: LyricsState,
    showXiaoOuAvatar: Boolean,
    sources: List<LyricSourceItemState>,
    onToggleMasterEnabled: (Boolean) -> Unit,
    onSkinThemeChange: (String) -> Unit,
    onToggleLongMarquee: (Boolean) -> Unit,
    onAlignModeChange: (String) -> Unit,
    onSplitRatioChange: (Int) -> Unit,
    onCoverScaleChange: (Int) -> Unit,
    onTitleFontSizeChange: (Int) -> Unit,
    onLyricFontSizeChange: (Int) -> Unit,
    onTimeOffsetChange: (Int) -> Unit,
    onResetSlidersToCenter: () -> Unit,
    onToggleSourceEnabled: (String) -> Unit,
    onDeleteCheckedSources: () -> Unit,
    onPreferredSourceChange: (String) -> Unit,
    onOpenImportDialog: () -> Unit,
    onOpenSourceManagerDialog: () -> Unit,
    onSaveCustomLyricUrl: (String) -> Unit,
    onSaveCustomCoverUrl: (String) -> Unit,
    onSaveCustomApiKey: (String) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 4.1 预览窗口 (1920 × 384，严格锁定 5:1 比例)
        LyricsPreviewSection(
            state = state,
            showXiaoOuAvatar = showXiaoOuAvatar
        )

        // 4.2 启用全屏歌词独立开关
        LyricsMasterSwitchCard(
            masterEnabled = state.masterEnabled,
            onToggleMasterEnabled = onToggleMasterEnabled
        )

        // 4.3 外观与排版
        LyricsAppearanceSection(
            state = state,
            onSkinThemeChange = onSkinThemeChange,
            onToggleLongMarquee = onToggleLongMarquee,
            onAlignModeChange = onAlignModeChange,
            onSplitRatioChange = onSplitRatioChange,
            onCoverScaleChange = onCoverScaleChange,
            onTitleFontSizeChange = onTitleFontSizeChange,
            onLyricFontSizeChange = onLyricFontSizeChange,
            onResetSlidersToCenter = onResetSlidersToCenter,
            onShowInfo = onShowInfo
        )

        // 4.4 识别与歌词源
        LyricsSourcesSection(
            sources = sources,
            preferredSourceId = state.preferredSourceId,
            onToggleSourceEnabled = onToggleSourceEnabled,
            onDeleteCheckedSources = onDeleteCheckedSources,
            onPreferredSourceChange = onPreferredSourceChange,
            onOpenImportDialog = onOpenImportDialog,
            onOpenSourceManagerDialog = onOpenSourceManagerDialog,
            onShowInfo = onShowInfo
        )

        // 4.5 高级
        LyricsAdvancedSection(
            state = state,
            onTimeOffsetChange = onTimeOffsetChange,
            onSaveCustomLyricUrl = onSaveCustomLyricUrl,
            onSaveCustomCoverUrl = onSaveCustomCoverUrl,
            onSaveCustomApiKey = onSaveCustomApiKey
        )
    }
}

/**
 * 4.1 排第一：预览窗口 (1920 × 384，严格锁定 aspectRatio(5f / 1f))
 */
@Composable
private fun LyricsPreviewSection(
    state: LyricsState,
    showXiaoOuAvatar: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neuCard(cornerRadius = 18.dp, borderColor = Color(0xFF93C5FD).copy(alpha = 0.8f))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 顶部状态条
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
                            .background(NeuColors.AccentBlue, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "1920 × 384",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "预览窗口",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )
                    if (showXiaoOuAvatar) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFEF3C7), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "3D小欧右侧避让中",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }

                Text(
                    text = "分割比 ${state.splitRatio}%:${100 - state.splitRatio}% · 封面 ${state.coverScale}% · 偏移 ${if (state.timeOffset >= 0) "+${state.timeOffset}" else "${state.timeOffset}"}ms",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary
                )
            }

            // 1920x384 (5:1) 比例拟物视口容器
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(5f / 1f)
                    .clip(RoundedCornerShape(16.dp))
                    .then(getPreviewSkinModifier(state.skinTheme))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：专辑封面与歌曲信息区 (按 splitRatio% 动态分配宽度)
                    val leftWeight = (state.splitRatio.toFloat() / 100f).coerceIn(0.2f, 0.8f)
                    val rightWeight = 1f - leftWeight

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(leftWeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 专辑封面 (根据 coverScale% 缩放)
                        val coverBaseSize = 64.dp
                        val scaledCoverSize = coverBaseSize * (state.coverScale.toFloat() / 100f)

                        Box(
                            modifier = Modifier
                                .size(scaledCoverSize)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF6366F1), Color(0xFF3B82F6), Color(0xFF06B6D4))
                                    )
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "音乐",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size((scaledCoverSize.value * 0.45f).dp)
                            )

                            // 底部微型无损音质标签
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 2.dp)
                                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "FLAC",
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1E3A8A)
                                )
                            }
                        }

                        // 歌曲名与艺术家
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (state.longMarquee) {
                                ContinuousMarqueeText(
                                    text = "星河巡航 — 车载环绕声示例曲目",
                                    fontSize = state.titleFontSize.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = getPreviewTextColor(state.skinTheme)
                                )
                            } else {
                                Text(
                                    text = "星河巡航 — 车载环绕声示例曲目",
                                    fontSize = state.titleFontSize.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = getPreviewTextColor(state.skinTheme),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "小欧车载乐团 · 02:18 / 04:36",
                                fontSize = 10.sp,
                                color = getPreviewTextColor(state.skinTheme).copy(alpha = 0.65f),
                                maxLines = 1
                            )
                        }
                    }

                    // 居中竖分割线
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.7f)
                            .width(1.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF94A3B8).copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // 右侧：歌词预览区 + 虚拟 3D 小欧形象避让指示
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(rightWeight)
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 歌词文字显示区
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = when (state.alignMode) {
                                "left" -> Alignment.Start
                                "right" -> Alignment.End
                                else -> Alignment.CenterHorizontally
                            }
                        ) {
                            val textAlign = when (state.alignMode) {
                                "left" -> TextAlign.Start
                                "right" -> TextAlign.End
                                else -> TextAlign.Center
                            }

                            Text(
                                text = "穿越城市霓虹与夜色地平线",
                                fontSize = 10.sp,
                                color = getPreviewTextColor(state.skinTheme).copy(alpha = 0.45f),
                                textAlign = textAlign,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            // 主高亮当前句
                            Text(
                                text = "让晚风吹过全屏光影，伴你驶向下一段山海旅程",
                                fontSize = (state.lyricFontSize * 0.2f).coerceIn(12f, 26f).sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (state.skinTheme == "dark_streamer") Color(0xFF60A5FA) else NeuColors.AccentBlue,
                                textAlign = textAlign,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "Every mile shines under the starlight",
                                fontSize = 10.sp,
                                color = getPreviewTextColor(state.skinTheme).copy(alpha = 0.55f),
                                textAlign = textAlign,
                                maxLines = 1
                            )
                        }

                        // 【核心要求】虚拟小欧形象图片：
                        // 当小欧形象开关开启时，在右侧展示 3D 小欧半身像，歌词自动避让！
                        if (showXiaoOuAvatar) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(72.dp)
                                    .padding(start = 6.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.xiaoou_3d_avatar_half),
                                    contentDescription = "3D小欧虚拟形象位置避让指示",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4.2 排第二：启用全屏歌词 (独立拟物开关卡片，双向同步)
 */
@Composable
private fun LyricsMasterSwitchCard(
    masterEnabled: Boolean,
    onToggleMasterEnabled: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neuCard(cornerRadius = 16.dp)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "启用全屏歌词",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
                Text(
                    text = "开启后在车机副屏界面全屏展示流动歌词与专辑视觉",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary
                )
            }

            NeuSwitch(
                checked = masterEnabled,
                onCheckedChange = onToggleMasterEnabled
            )
        }
    }
}

/**
 * 4.3 外观与排版
 */
@Composable
private fun LyricsAppearanceSection(
    state: LyricsState,
    onSkinThemeChange: (String) -> Unit,
    onToggleLongMarquee: (Boolean) -> Unit,
    onAlignModeChange: (String) -> Unit,
    onSplitRatioChange: (Int) -> Unit,
    onCoverScaleChange: (Int) -> Unit,
    onTitleFontSizeChange: (Int) -> Unit,
    onLyricFontSizeChange: (Int) -> Unit,
    onResetSlidersToCenter: () -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    text = "外观与排版",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }

            NeuButton(
                text = "滑块全部归中",
                onClick = onResetSlidersToCenter,
                textColor = NeuColors.TextSecondary
            )
        }

        // 皮肤主题 + 超长跑马灯
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 皮肤主题下拉框
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuCard(cornerRadius = 16.dp)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                content = "切换 1920×384 歌词副屏的拟物材质或昼夜配色风格。",
                                recommend = "日间推荐：浅色拟物经典 / 液态毛玻璃"
                            ),
                            onShowInfo = onShowInfo
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
                        onItemSelected = { onSkinThemeChange(it.first) },
                        itemLabel = { it.second }
                    )
                }
            }

            // 超长跑马灯
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuCard(cornerRadius = 16.dp)
                    .padding(14.dp)
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
                                text = "超长跑马灯",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "超长文本跑马灯",
                                    content = "当歌名或歌词文字超出屏幕宽度限制时，自动启用平滑横向滚动动画。",
                                    recommend = "建议保持开启，防止长标题截断"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }
                        Text(
                            text = "歌名或歌词过长时自动开启横向平滑滚动动画",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )
                    }

                    NeuSwitch(
                        checked = state.longMarquee,
                        onCheckedChange = onToggleLongMarquee
                    )
                }
            }
        }

        // 对齐方式 (三段拟物分段器)
        NeuSegmentedAlign(
            alignMode = state.alignMode,
            onAlignChange = onAlignModeChange
        )

        // 4 个默认居中滑块 (宽屏两两双列并排)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                NeuCenteredSlider(
                    value = state.splitRatio,
                    onValueChange = onSplitRatioChange,
                    min = 20,
                    max = 80,
                    step = 5,
                    defaultCenter = 50,
                    unit = "%",
                    label = "左右分割比",
                    desc = "调节专辑封面区与歌词区的宽度比例（默认居中 50%）"
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                NeuCenteredSlider(
                    value = state.coverScale,
                    onValueChange = onCoverScaleChange,
                    min = 50,
                    max = 150,
                    step = 5,
                    defaultCenter = 100,
                    unit = "%",
                    label = "封面缩放",
                    desc = "缩放全屏歌词左侧专辑封面尺寸（默认居中 100%）"
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                NeuCenteredSlider(
                    value = state.titleFontSize,
                    onValueChange = onTitleFontSizeChange,
                    min = 14,
                    max = 30,
                    step = 1,
                    defaultCenter = 22,
                    unit = "px",
                    label = "歌名字号",
                    desc = "调节歌曲标题文字大小（默认居中 22px）"
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                NeuCenteredSlider(
                    value = state.lyricFontSize,
                    onValueChange = onLyricFontSizeChange,
                    min = 52,
                    max = 132,
                    step = 2,
                    defaultCenter = 92,
                    unit = "px",
                    label = "歌词字号",
                    desc = "调节全屏主高亮歌词文字大小（默认居中 92px 与主页一致）"
                )
            }
        }
    }
}

/**
 * 4.4 识别与歌词源
 */
@Composable
private fun LyricsSourcesSection(
    sources: List<LyricSourceItemState>,
    preferredSourceId: String,
    onToggleSourceEnabled: (String) -> Unit,
    onDeleteCheckedSources: () -> Unit,
    onPreferredSourceChange: (String) -> Unit,
    onOpenImportDialog: () -> Unit,
    onOpenSourceManagerDialog: () -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit
) {
    val enabledSourcesCount = sources.count { it.enabled }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    text = "识别与歌词源",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }
            Text(
                text = "支持复选框启用、导入源（URL/文件/单源）与删除",
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "歌词源插件",
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
                            text = "勾选需要参与歌曲匹配的多平台歌词源插件，或弹出导入界面添加新源",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeuButton(
                            text = "+ 导入源",
                            onClick = onOpenImportDialog,
                            isPrimary = true
                        )
                        NeuButton(
                            text = "删除选中源",
                            onClick = onDeleteCheckedSources,
                            textColor = NeuColors.AccentRose
                        )
                        NeuButton(
                            text = "打开源管理弹窗",
                            onClick = onOpenSourceManagerDialog,
                            textColor = NeuColors.AccentBlue
                        )
                    }
                }

                // 嵌入式双列复选框源列表
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chunked = sources.chunked(2)
                    chunked.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { sourceItem ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .neuInset(
                                            cornerRadius = 12.dp,
                                            backgroundColor = if (sourceItem.enabled) Color(0xFFF0FDF4) else Color(0xFFEFF4F9)
                                        )
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            NeuCheckbox(
                                                checked = sourceItem.enabled,
                                                onCheckedChange = { onToggleSourceEnabled(sourceItem.id) }
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = sourceItem.name,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = NeuColors.TextPrimary
                                                    )
                                                    if (sourceItem.category.isNotEmpty()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
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

                                        // 延迟标签
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = Color(0xFFDCFCE7),
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = sourceItem.latency,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = NeuColors.AccentGreen
                                            )
                                        }
                                    }
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 偏好源下拉选择
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 16.dp)
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "偏好源",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
                Text(
                    text = "多源同时命中时优先采用的首选歌词源插件",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary
                )
                val currentPref = sources.firstOrNull { it.id == preferredSourceId } ?: sources.firstOrNull()
                if (currentPref != null) {
                    NeuDropdown(
                        items = sources,
                        selectedItem = currentPref,
                        onItemSelected = { onPreferredSourceChange(it.id) },
                        itemLabel = { "${it.name} (${it.category} · ${if (it.enabled) "已启用" else "未启用"})" }
                    )
                }
            }
        }
    }
}

/**
 * 4.5 下方分组三：高级
 */
@Composable
private fun LyricsAdvancedSection(
    state: LyricsState,
    onTimeOffsetChange: (Int) -> Unit,
    onSaveCustomLyricUrl: (String) -> Unit,
    onSaveCustomCoverUrl: (String) -> Unit,
    onSaveCustomApiKey: (String) -> Unit
) {
    var lyricUrlInput by remember(state.customLyricUrl) { mutableStateOf(state.customLyricUrl) }
    var coverUrlInput by remember(state.customCoverUrl) { mutableStateOf(state.customCoverUrl) }
    var apiKeyInput by remember(state.customApiKey) { mutableStateOf(state.customApiKey) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    text = "高级",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }
            Text(
                text = "时间轴微调与自定义接口配置",
                fontSize = 11.sp,
                color = NeuColors.TextMuted
            )
        }

        // 歌词时间偏移 (居中滑块：-2000ms ~ +2000ms)
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

        // 自定义歌词 URL 与封面 URL（自适应排布：宽度紧张时自适应转为单行满宽）
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isStacked = maxWidth < 840.dp
            if (isStacked) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CustomLyricUrlSection(
                        lyricUrlInput = lyricUrlInput,
                        onLyricUrlChange = { lyricUrlInput = it },
                        onSave = { onSaveCustomLyricUrl(lyricUrlInput) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    CustomCoverUrlSection(
                        coverUrlInput = coverUrlInput,
                        onCoverUrlChange = { coverUrlInput = it },
                        onSave = { onSaveCustomCoverUrl(coverUrlInput) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CustomLyricUrlSection(
                        lyricUrlInput = lyricUrlInput,
                        onLyricUrlChange = { lyricUrlInput = it },
                        onSave = { onSaveCustomLyricUrl(lyricUrlInput) },
                        modifier = Modifier.weight(1f)
                    )
                    CustomCoverUrlSection(
                        coverUrlInput = coverUrlInput,
                        onCoverUrlChange = { coverUrlInput = it },
                        onSave = { onSaveCustomCoverUrl(coverUrlInput) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 自定义 API Key
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 16.dp)
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "API Key",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
                Text(
                    text = "私有服务鉴权凭据 Token",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary
                )
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
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
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
                        onClick = { onSaveCustomApiKey(apiKeyInput) },
                        textColor = NeuColors.AccentBlue
                    )
                }
            }
        }
    }
}

/**
 * 辅助函数：预览窗口皮肤修饰符
 */
private fun getPreviewSkinModifier(skin: String): Modifier = when (skin) {
    SkinType.GLASSMORPHISM.name, "liquid_glass" -> Modifier
        .background(
            Brush.linearGradient(
                listOf(
                    Color(0xFFE0E7FF).copy(alpha = 0.85f),
                    Color(0xFFCFFAFE).copy(alpha = 0.70f)
                )
            )
        )
        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
    SkinType.MINIMAL_NEON.name, "dark_streamer" -> Modifier
        .background(
            Brush.linearGradient(
                listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF020617))
            )
        )
        .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    SkinType.VIBRANT_WAVE.name, "minimal_pure" -> Modifier
        .background(
            Brush.linearGradient(
                listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
            )
        )
        .border(1.dp, Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    SkinType.VINYL_PLAYER.name -> Modifier
        .background(Color(0xFF18181B))
        .border(1.dp, Color(0xFFE4E4E7).copy(alpha = 0.2f), RoundedCornerShape(16.dp))
    SkinType.IMMERSIVE.name -> Modifier
        .background(
            Brush.linearGradient(
                listOf(Color(0xFF111827), Color(0xFF1F2937), Color(0xFF111827))
            )
        )
        .border(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
    else -> Modifier
        .neuInset(cornerRadius = 16.dp, backgroundColor = Color(0xFFE4EBF2))
}

/**
 * 辅助函数：根据皮肤主题获取预览窗口文字颜色
 */
private fun getPreviewTextColor(skin: String): Color = when (skin) {
    SkinType.MINIMAL_NEON.name, "dark_streamer",
    SkinType.VIBRANT_WAVE.name,
    SkinType.VINYL_PLAYER.name,
    SkinType.IMMERSIVE.name -> Color(0xFFF8FAFC)
    else -> Color(0xFF1E293B)
}

/**
 * 超长跑马灯平滑滚动组件
 */
@Composable
private fun ContinuousMarqueeText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: Color
) {
    val scrollState = rememberScrollState()
    val transition = rememberInfiniteTransition(label = "marquee")
    val offsetProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalOffset = 150.dp
        Row(
            modifier = Modifier
                .offset { IntOffset((-offsetProgress * totalOffset.toPx()).roundToInt(), 0) }
                .horizontalScroll(scrollState, enabled = false)
        ) {
            Text(
                text = "$text   ·   $text",
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CustomLyricUrlSection(
    lyricUrlInput: String,
    onLyricUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .neuCard(cornerRadius = 16.dp)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "自定义歌词 地址URL",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary
            )
            Text(
                text = "配置私有歌词解析服务或外部代理网关",
                fontSize = 11.sp,
                color = NeuColors.TextSecondary
            )
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
                        value = lyricUrlInput,
                        onValueChange = onLyricUrlChange,
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
                    onClick = onSave,
                    textColor = NeuColors.AccentBlue
                )
            }
        }
    }
}

@Composable
private fun CustomCoverUrlSection(
    coverUrlInput: String,
    onCoverUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .neuCard(cornerRadius = 16.dp)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "自定义封面 地址URL",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary
            )
            Text(
                text = "配置高清无损专辑图 CDN 解析服务",
                fontSize = 11.sp,
                color = NeuColors.TextSecondary
            )
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
                        value = coverUrlInput,
                        onValueChange = onCoverUrlChange,
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
                    onClick = onSave,
                    textColor = NeuColors.AccentBlue
                )
            }
        }
    }
}

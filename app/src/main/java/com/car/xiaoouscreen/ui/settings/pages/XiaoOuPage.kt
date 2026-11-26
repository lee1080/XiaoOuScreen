package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.MiniGearPosition
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.components.XiaoOuScreenLayoutBar
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.XiaoOuState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType

/**
 * 模块 2：小欧屏设置
 * 规范化 2x2 响应式统一卡片网格 + 极客实验室：
 *   1. 开启小欧屏投屏（核心投屏服务开关）
 *   2. 是否显示小欧形象（3D形象避让联动）
 *   3. 时钟液态玻璃（拟物折射视觉效果）
 *   4. 微型档位悬浮（P/R/N/D 悬浮胶囊 · 5大停靠位置可调）
 *   5. 实验室特性：微型档位实时背景自适应计算
 */
@Composable
fun XiaoOuPage(
    state: XiaoOuState,
    onToggleProjection: (Boolean) -> Unit,
    onToggleShowAvatar: (Boolean) -> Unit,
    onToggleLiquidGlass: (Boolean) -> Unit,
    onToggleMiniGear: (Boolean) -> Unit,
    onSelectMiniGearPosition: (Int) -> Unit,
    onToggleMiniGearRealtimeColorCalc: (Boolean) -> Unit,
    onCommitSlotA: (XiaoOuSlotType) -> Unit,
    onCommitSlotC: (XiaoOuSlotType) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPositionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 第一行：开启小欧屏投屏 + 是否显示小欧形象
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            XiaoOuUniformCard(
                title = "开启小欧屏投屏",
                desc = "向小欧副屏无线投屏时钟、天气及流动歌词；关闭时副屏休眠省电",
                checked = state.isProjecting,
                onCheckedChange = onToggleProjection,
                info = AnnotationInfo(
                    title = "小欧屏无线投屏服务",
                    content = "控制中控车机是否向右侧小欧 3D 实体副屏发送实时画面。开启后状态栏常驻绿色投屏指示灯；关闭时投屏暂停并进入待机。",
                    recommend = "日常行车建议保持开启"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )

            XiaoOuUniformCard(
                title = "显示小欧形象",
                desc = "在小欧屏展示 3D 动态小欧；全屏歌词将自动进行右侧安全避让",
                checked = state.showAvatar,
                onCheckedChange = onToggleShowAvatar,
                info = AnnotationInfo(
                    title = "显示小欧形象与避让",
                    content = "控制副屏区域是否显示 3D 小欧。开启时全屏歌词自动向左避让以防遮挡；关闭后全屏歌词可全宽满幅铺展。",
                    recommend = "建议开启，全屏歌词将自动向左避让"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )
        }

        // 第二行：时钟液态玻璃 + 微型档位悬浮 (包含修改位置入口)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            XiaoOuUniformCard(
                title = "时钟液态玻璃",
                desc = "为小欧屏时钟组件启用流体半透明毛玻璃高光折射视觉效果",
                checked = state.liquidGlassClock,
                onCheckedChange = onToggleLiquidGlass,
                info = AnnotationInfo(
                    title = "时钟液态玻璃特效",
                    content = "启用实时高斯模糊与边缘折射高光着色器，使小欧屏数字时钟呈现通透的液态玻璃质感。",
                    recommend = "支持 GPU 硬件加速的车机推荐开启"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )

            val currentPosName = MiniGearPosition.fromId(state.miniGearPosition).shortName
            XiaoOuUniformCard(
                title = "微型档位悬浮",
                desc = "悬浮显示车辆实时档位（P/R/N/D）指示胶囊 (当前: $currentPosName)",
                checked = state.miniGearFloat,
                onCheckedChange = onToggleMiniGear,
                info = AnnotationInfo(
                    title = "微型档位悬浮胶囊",
                    content = "在全屏导航或歌词界面悬浮显示微型 P / R / N / D 档位状态胶囊，无需低头看仪表。支持 5 大位置自由停靠。",
                    recommend = "支持自由停靠与车辆 CAN 总线档位实时同步"
                ),
                onShowInfo = onShowInfo,
                actionButton = {
                    NeuButton(
                        text = "修改位置",
                        onClick = { showPositionDialog = true },
                        modifier = Modifier.height(30.dp),
                        cornerRadius = 8.dp
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // 第三行：小欧屏 A / B / C 三区长条拟物布局配置条
        XiaoOuScreenLayoutBar(
            slotA = state.slotA,
            slotC = state.slotC,
            showAvatar = state.showAvatar,
            onCommitSlotA = onCommitSlotA,
            onCommitSlotC = onCommitSlotC,
            onShowInfo = onShowInfo
        )

        // 第四行：极客实验室特性 (满足实时背景计算与低开销需求)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp, start = 2.dp)
            ) {
                Text(
                    text = "🧪 极客实验室",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextSecondary
                )
                Text(
                    text = "· 实验性视觉与自适应计算特性",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary.copy(alpha = 0.7f)
                )
            }

            XiaoOuUniformCard(
                title = "微型档位实时背景自适应",
                desc = "根据微型档位所在位置的底层背景实时动态计算环境光折射与反色高对比，完美融入自定义背景；关闭时保持经典固化低功耗渲染",
                checked = state.miniGearRealtimeColorCalc,
                onCheckedChange = onToggleMiniGearRealtimeColorCalc,
                info = AnnotationInfo(
                    title = "微型档位实时背景自适应 (实验室)",
                    content = "开启后，微型档位悬浮指示器将在切档与场景更新时，对所在区域的背景色彩进行极低开销稀疏采样与明暗度分析，动态调整玻璃边缘折射色温与高对比反色，消除更换自定义背景时的生硬感；关闭时保持经典固定色渲染。",
                    recommend = "后续更换自定义背景或追求极致环境融入感时建议开启"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 弹出微型档位停靠位置选择弹窗 (同比例 5:1 小欧屏)
    if (showPositionDialog) {
        MiniGearPositionDialog(
            currentPosition = state.miniGearPosition,
            onSelectPosition = { posId ->
                onSelectMiniGearPosition(posId)
                showPositionDialog = false
            },
            onDismiss = { showPositionDialog = false }
        )
    }
}

/**
 * 微型档位位置修改同比例弹窗 (1920×384 即 5:1 比例)
 */
@Composable
private fun MiniGearPositionDialog(
    currentPosition: Int,
    onSelectPosition: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(780.dp)
                    .neuCard(cornerRadius = 24.dp)
                    .padding(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 阻断点击穿透 */ }
                    )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 顶部：标题 + 说明 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "选择微型档位停靠位置",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            brush = NeuColors.PrimaryBlueGradient,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "1920×384 5:1 屏幕映射",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            Text(
                                text = "点击小欧屏示意图中的目标槽位，即时切换副屏微型档位显示位置",
                                fontSize = 11.sp,
                                color = NeuColors.TextSecondary
                            )
                        }

                        NeuButton(
                            text = "关闭",
                            onClick = onDismiss,
                            modifier = Modifier.height(34.dp),
                            cornerRadius = 10.dp
                        )
                    }

                    // 中部：小欧屏 1920×384 (5:1) 同比例实屏拟真预览框
                    val infiniteTransition = rememberInfiniteTransition(label = "pulseAnim")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.40f,
                        targetValue = 0.95f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.96f,
                        targetValue = 1.04f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseScale"
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(5f) // 1920 : 384 = 5.0
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.5.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                            .padding(2.dp)
                    ) {
                        val boxW = maxWidth
                        val boxH = maxHeight

                        // 背景分区参考线 (A/B/C 三区)
                        Row(modifier = Modifier.fillMaxSize()) {
                            // A区：[0..535] 占 27.86%
                            Box(
                                modifier = Modifier
                                    .weight(0.2786f)
                                    .fillMaxHeight()
                                    .border(width = 0.5.dp, color = Color(0x3364748B))
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "A 区\n(状态/天气)",
                                    fontSize = 10.sp,
                                    color = Color(0xFF475569),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 13.sp
                                )
                            }
                            // B区：[535..1385] 占 44.27%
                            Box(
                                modifier = Modifier
                                    .weight(0.4427f)
                                    .fillMaxHeight()
                                    .border(width = 0.5.dp, color = Color(0x3364748B))
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "B 区\n(中央时钟 / 3D小欧)",
                                    fontSize = 10.sp,
                                    color = Color(0xFF475569),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 13.sp
                                )
                            }
                            // C区：[1385..1920] 占 27.86%
                            Box(
                                modifier = Modifier
                                    .weight(0.2786f)
                                    .fillMaxHeight()
                                    .border(width = 0.5.dp, color = Color(0x3364748B))
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "C 区\n(卡槽功能)",
                                    fontSize = 10.sp,
                                    color = Color(0xFF475569),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 13.sp
                                )
                            }
                        }

                        // 5 大微型档位物理锚点渲染
                        MiniGearPosition.values().forEach { pos ->
                            val isCurrent = pos.id == currentPosition
                            // 相对 1920x384 的几何占比
                            val relLeft = pos.defaultLeft / 1920f
                            val relTop = pos.defaultTop / 384f
                            val relWidth = 288f / 1920f   // 0.15f
                            val relHeight = 60f / 384f    // 0.15625f

                            val itemWidth = boxW * relWidth
                            val itemHeight = boxH * relHeight
                            val itemOffsetX = boxW * relLeft
                            val itemOffsetY = boxH * relTop

                            Box(
                                modifier = Modifier
                                    .offset(x = itemOffsetX, y = itemOffsetY)
                                    .size(width = itemWidth, height = itemHeight)
                                    .then(
                                        if (!isCurrent) {
                                            Modifier.graphicsLayer {
                                                scaleX = pulseScale
                                                scaleY = pulseScale
                                            }
                                        } else Modifier
                                    )
                                    .clip(RoundedCornerShape(itemHeight / 2f))
                                    .then(
                                        if (isCurrent) {
                                            Modifier
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(Color(0xFF2563EB), Color(0xFF38BDF8))
                                                    )
                                                )
                                                .border(
                                                    width = 2.dp,
                                                    color = Color(0xFF93C5FD),
                                                    shape = RoundedCornerShape(itemHeight / 2f)
                                                )
                                        } else {
                                            Modifier
                                                .background(Color(0xCC1E293B))
                                                .border(
                                                    width = 1.3.dp,
                                                    color = Color(0xFF38BDF8).copy(alpha = pulseAlpha),
                                                    shape = RoundedCornerShape(itemHeight / 2f)
                                                )
                                        }
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onSelectPosition(pos.id)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isCurrent) "${pos.shortName} ★ 当前" else pos.shortName,
                                    fontSize = 9.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) Color.White else Color.White.copy(alpha = pulseAlpha),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // 底部：5 个快速选择按钮网格
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniGearPosition.values().forEach { pos ->
                            val isCurrent = pos.id == currentPosition
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .neuButton(
                                        isPressed = false,
                                        isPrimary = isCurrent,
                                        cornerRadius = 10.dp
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onSelectPosition(pos.id)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = pos.shortName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) Color.White else NeuColors.TextPrimary
                                    )
                                    Text(
                                        text = if (isCurrent) "当前停靠" else "点击切换",
                                        fontSize = 9.sp,
                                        color = if (isCurrent) Color(0xFFBAE6FD) else NeuColors.TextSecondary
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

/**
 * 统一尺寸、统一文字排版的标准车规级开关卡片 (支持拓展右下角动作按钮)
 */
@Composable
private fun XiaoOuUniformCard(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    info: AnnotationInfo,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier,
    actionButton: (@Composable () -> Unit)? = null
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
            // 顶部：标题 + ? 按钮 + NeuSwitch
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
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )
                    NeuInfoButton(
                        info = info,
                        onShowInfo = onShowInfo
                    )
                }

                NeuSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }

            // 底部：说明文本 + 动作按钮 (若有)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (actionButton != null) 8.dp else 0.dp)
                )

                actionButton?.invoke()
            }
        }
    }
}

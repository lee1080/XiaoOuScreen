package com.car.xiaoouscreen.ui.settings.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val VIRTUAL_WHEEL_COUNT = 100_000
private const val SETTLE_DEBOUNCE_MS = 220L

/**
 * 小欧屏 A / B / C 三区长条拟物布局配置条
 *
 * 交互规范：
 * - 长条拟物形态 1:1 映射小欧屏 A（左）/ B（中）/ C（右）三个显示区域；
 * - A 区域：上下滑动无限循环切换显示模块（天气信息、歌曲封面、蓄电池信息、空白留白等）；
 * - B 区域：固定显示大时间与歌词，不可调整；
 * - C 区域：当开启「是否显示小欧形象」时呈灰色锁定态且不可滑动；关闭后解锁，与 A 区域一致可上下滑动循环调节；
 * - 车机省资源防抖机制：滑动/惯性滚动期间绝不实时刷新小欧屏，仅在滑停磁吸落定后提交唯一一次更新。
 */
@Composable
fun XiaoOuScreenLayoutBar(
    slotA: XiaoOuSlotType,
    slotC: XiaoOuSlotType,
    showAvatar: Boolean,
    onCommitSlotA: (XiaoOuSlotType) -> Unit,
    onCommitSlotC: (XiaoOuSlotType) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val modules = remember { XiaoOuSlotType.selectableModules }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neuCard(cornerRadius = 18.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ================================================================
            // 顶部标题行 + 当前三区状态速览 + 交互提示
            // ================================================================
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
                        text = "小欧屏三区显示布局",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )
                    NeuInfoButton(
                        info = AnnotationInfo(
                            title = "小欧屏 A / B / C 分区自定义架构",
                            content = "小欧屏从左到右分为 A、B、C 三个对称区域：\n• A 区域：支持上下滑动循环切换天气信息、歌曲封面、蓄电池信息、G 值准星、整车功率、实时转速与空白留白；\n• B 区域：固定为核心大时间 / 五行歌词显示区；\n• C 区域：开启「显示小欧形象」时自动锁定留给 3D 小欧；关闭后解锁，同样可上下滑动循环配置。\n\n• 歌曲封面智能补位：选择「歌曲封面」模块时，播放音乐展示专辑封面，未播放或停止后自动显示天气信息填充该位置（若选择「天气信息」则始终显示天气）。\n\n为节约车机算力，滑动过程中不会频繁重绘副屏，仅在松手停稳后自动同步至小欧屏。",
                            recommend = "建议按个人驾驶习惯自由搭配 A / C 区域模块"
                        ),
                        onShowInfo = onShowInfo
                    )

                    // 当前三区摘要胶囊
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFEFF6FF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        val cSummary = if (showAvatar) "小欧形象" else slotC.title
                        Text(
                            text = "A: ${slotA.title}  ·  B: 时间/歌词  ·  C: $cSummary",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.AccentBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "上下滑 A / C 循环切换 · 停稳后同步小欧屏",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ================================================================
            // 核心长条拟物条：映射 1920×384 超宽小欧屏 A / B / C 三区实体槽位
            // ================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .neuInset(
                        cornerRadius = 18.dp,
                        backgroundColor = Color(0xFFD9E2EC)
                    )
                    .padding(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ---------- A 区域（可上下滑动循环切换） ----------
                    SlotWheelZone(
                        zoneTag = "A 区",
                        modules = modules,
                        selectedType = slotA,
                        enabled = true,
                        onCommitSelection = onCommitSlotA,
                        modifier = Modifier
                            .weight(1.08f)
                            .fillMaxHeight()
                    )

                    // ---------- B 区域（固定显示：大时间 / 歌词） ----------
                    FixedCenterZoneB(
                        modifier = Modifier
                            .weight(1.38f)
                            .fillMaxHeight()
                    )

                    // ---------- C 区域（开启小欧形象时灰色锁定；关闭后可循环滑动） ----------
                    AnimatedContent(
                        targetState = showAvatar,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                        },
                        label = "slotCAvatarLockTransition",
                        modifier = Modifier
                            .weight(1.08f)
                            .fillMaxHeight()
                    ) { avatarLocked ->
                        if (avatarLocked) {
                            LockedAvatarZoneC(modifier = Modifier.fillMaxSize())
                        } else {
                            SlotWheelZone(
                                zoneTag = "C 区",
                                modules = modules,
                                selectedType = slotC,
                                enabled = true,
                                onCommitSelection = onCommitSlotC,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 可无限循环上下滑动的拟物滚轮区（供 A 区与解锁后的 C 区共用）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotWheelZone(
    zoneTag: String,
    modules: List<XiaoOuSlotType>,
    selectedType: XiaoOuSlotType,
    enabled: Boolean,
    onCommitSelection: (XiaoOuSlotType) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemCount = modules.size.coerceAtLeast(1)
    val initialMod = modules.indexOf(selectedType).coerceAtLeast(0)
    val baseCenter = remember(itemCount) {
        val mid = VIRTUAL_WHEEL_COUNT / 2
        mid - (mid % itemCount)
    }
    val pagerState = rememberPagerState(
        initialPage = baseCenter + initialMod,
        pageCount = { VIRTUAL_WHEEL_COUNT }
    )
    val coroutineScope = rememberCoroutineScope()

    // 防止滚轮垂直滑动/惯性残余透传给外层设置页 verticalScroll
    val isolateVerticalScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }

    // 严格落定防抖状态：滑动过程中完全不触发回调，仅在停稳后更新一次小欧屏
    var lastCommitted by remember { mutableStateOf(selectedType) }
    var isPendingSettle by remember { mutableStateOf(false) }
    val latestOnCommit by rememberUpdatedState(onCommitSelection)
    val latestSelected by rememberUpdatedState(selectedType)

    // 外部状态变更时静默对齐滚轮
    LaunchedEffect(selectedType) {
        lastCommitted = selectedType
        val curMod = Math.floorMod(pagerState.settledPage, itemCount)
        val targetMod = modules.indexOf(selectedType).coerceAtLeast(0)
        if (curMod != targetMod && !pagerState.isScrollInProgress) {
            val delta = targetMod - curMod
            pagerState.scrollToPage(pagerState.settledPage + delta)
        }
    }

    // 核心停稳提交监听：
    // 1. 拖动/甩动惯性滑行期间 (isScrollInProgress == true) 绝不通知服务；
    // 2. 停稳磁吸完成后等待 SETTLE_DEBOUNCE_MS 确认用户不再连续拨动，再唯一一次触发同步！
    LaunchedEffect(pagerState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .distinctUntilChanged()
            .collectLatest { (scrolling, settledPage) ->
                if (scrolling) {
                    isPendingSettle = true
                } else {
                    val settledType = modules[Math.floorMod(settledPage, itemCount)]
                    if (settledType != lastCommitted) {
                        isPendingSettle = true
                        delay(SETTLE_DEBOUNCE_MS)
                        if (!pagerState.isScrollInProgress) {
                            val finalType = modules[Math.floorMod(pagerState.settledPage, itemCount)]
                            if (finalType != lastCommitted) {
                                lastCommitted = finalType
                                latestOnCommit(finalType)
                            }
                        }
                    }
                    isPendingSettle = false
                }
            }
    }

    // 兜底：若用户在停稳防抖窗口内离开设置页，立刻落盘提交最终停稳项
    DisposableEffect(Unit) {
        onDispose {
            if (enabled) {
                val settledType = modules[Math.floorMod(pagerState.settledPage, itemCount)]
                if (settledType != lastCommitted && settledType != latestSelected) {
                    latestOnCommit(settledType)
                }
            }
        }
    }

    val itemHeight = 35.dp
    val wheelHeight = itemHeight * 3 // 105.dp，中心选中行 + 上下各一行预览

    Box(
        modifier = modifier
            .neuCard(
                cornerRadius = 14.dp,
                borderColor = if (isPendingSettle) Color(0xFF93C5FD) else NeuColors.BorderCard
            )
            .clip(RoundedCornerShape(14.dp))
            .nestedScroll(isolateVerticalScroll)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // 顶部角标栏：A 区 / C 区 + 上下滑提示图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFE0ECFF), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = zoneTag,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeuColors.AccentBlue
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isPendingSettle) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(NeuColors.AccentAmber)
                    )
                    Text(
                        text = "松手生效",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuColors.AccentAmber
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = "上下滑动切换",
                        tint = NeuColors.TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 滚轮主视口
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(wheelHeight)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
        ) {
            // 中心选中磁吸拟物凹透镜槽
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .neuInset(
                        cornerRadius = 10.dp,
                        backgroundColor = Color(0xFFEBF2FA)
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFF93C5FD).copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp)
                    )
            )

            // 无限循环垂直滚轮（支持轻滑步进与快甩多格惯性磁吸）
            VerticalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(itemHeight),
                contentPadding = PaddingValues(vertical = itemHeight),
                beyondBoundsPageCount = 2,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(4)
                ),
                userScrollEnabled = enabled,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val module = modules[Math.floorMod(page, itemCount)]
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val absOffset = abs(pageOffset).coerceIn(0f, 1.5f)
                val normalized = absOffset.coerceIn(0f, 1f)
                val itemScale = 1f - 0.16f * normalized
                val itemAlpha = 1f - 0.64f * normalized
                val tiltX = (pageOffset * 20f).coerceIn(-32f, 32f)
                val isCenter = absOffset < 0.35f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                            alpha = itemAlpha
                            rotationX = tiltX
                            cameraDistance = 12f * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled && !isCenter
                        ) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = slotIconFor(module),
                        contentDescription = module.title,
                        tint = if (isCenter) NeuColors.AccentBlue else NeuColors.TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = module.title,
                        fontSize = if (isCenter) 13.sp else 11.sp,
                        fontWeight = if (isCenter) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isCenter) NeuColors.TextPrimary else NeuColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!module.isImplemented) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isCenter) Color(0xFFFEF3C7) else Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "未做",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCenter) NeuColors.AccentAmber else NeuColors.TextMuted
                            )
                        }
                    }
                }
            }

            // 顶部与底部柔和滚筒渐隐遮罩（营造真实机械滚轮纵深感）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(NeuColors.CardStart.copy(alpha = 0.92f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, NeuColors.CardEnd.copy(alpha = 0.92f))
                        )
                    )
            )
        }
    }
}

/**
 * B 区域：固定显示大时间与歌词，不可滑动调整
 */
@Composable
private fun FixedCenterZoneB(modifier: Modifier = Modifier) {
    var timePreview by remember { mutableStateOf("14:20") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            timePreview = sdf.format(Date())
            delay(15_000L)
        }
    }

    Box(
        modifier = modifier
            .neuInset(
                cornerRadius = 14.dp,
                backgroundColor = Color(0xFFE7EFF8)
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 顶部标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFDBEAFE), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "B 区 · 固定",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = NeuColors.AccentBlue
                )
            }

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "固定区域",
                tint = NeuColors.TextMuted,
                modifier = Modifier.size(12.dp)
            )
        }

        // 中央大时间 + 歌词说明微缩预览
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = timePreview,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeuColors.TextPrimary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "大时间 / 五行歌词自动切换",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = NeuColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 底部固定说明
        Text(
            text = "核心展示区 · 固定不可滑动",
            fontSize = 9.sp,
            color = NeuColors.TextMuted,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * C 区域锁定态（开启「是否显示小欧形象」时为灰色不可滑动）
 */
@Composable
private fun LockedAvatarZoneC(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFD2DAE3))
            .border(
                width = 1.dp,
                color = Color(0xFFBFC9D6),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // 顶部灰色锁定标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFC2CCD8), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "C 区 · 已锁定",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B)
                )
            }

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "已锁定",
                tint = Color(0xFF7B8794),
                modifier = Modifier.size(13.dp)
            )
        }

        // 中心灰色小欧形象提示
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFC3CDD8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = "小欧形象占位",
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "小欧形象专属区",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5B6776),
                maxLines = 1
            )
            Text(
                text = "关闭小欧形象后可调节",
                fontSize = 9.sp,
                color = Color(0xFF7B8794),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun slotIconFor(type: XiaoOuSlotType): ImageVector = when (type) {
    XiaoOuSlotType.WEATHER -> Icons.Default.WbSunny
    XiaoOuSlotType.ALBUM_COVER -> Icons.Default.Album
    XiaoOuSlotType.BATTERY_INFO -> Icons.Default.BatteryChargingFull
    XiaoOuSlotType.G_FORCE -> Icons.Default.MyLocation
    XiaoOuSlotType.MOTOR_POWER -> Icons.Default.Bolt
    XiaoOuSlotType.RPM -> Icons.Default.Speed
    XiaoOuSlotType.NONE -> Icons.Default.RemoveCircleOutline
}

package com.car.xiaoouscreen.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PermDeviceInformation
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.components.NeuToast
import com.car.xiaoouscreen.ui.settings.dialogs.AnnotationPopover
import com.car.xiaoouscreen.ui.settings.dialogs.ContactAuthorDialog
import com.car.xiaoouscreen.ui.settings.dialogs.DebugLogDialog
import com.car.xiaoouscreen.ui.settings.dialogs.ImportSourceDialog
import com.car.xiaoouscreen.ui.settings.dialogs.SourceManagerDialog
import com.car.xiaoouscreen.ui.settings.model.MenuItemId
import com.car.xiaoouscreen.ui.settings.model.SettingsState
import com.car.xiaoouscreen.ui.settings.pages.FullscreenLyricsPage
import com.car.xiaoouscreen.ui.settings.pages.LyricsSettingsPage
import com.car.xiaoouscreen.ui.settings.pages.ServicePage
import com.car.xiaoouscreen.ui.settings.pages.WeatherPage
import com.car.xiaoouscreen.ui.settings.pages.WelcomePage
import com.car.xiaoouscreen.ui.settings.pages.XiaoOuPage
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import com.car.xiaoouscreen.ui.settings.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车规级「小欧屏 — 设置中心」原生响应式容器
 * 严格按照车机中控屏规范：
 *   - 顶部状态栏：和风API校验、投屏绿灯/灰灯联动、音乐播放状态回显、时钟
 *   - 左侧悬浮菜单栏：大投屏开关卡片 + 6 个一级菜单项（项5为全屏歌词，挂载独立快捷开关）
 *   - 右侧详细设置面板：统一卡片规范、独立滚动记忆、支持 16:9 与全屏多分辨率自适应
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    // 横屏响应式自适应容器 (支持标准 16:9 窗口与全屏带鱼屏)
    BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(brush = NeuColors.BgGradient)
                .padding(12.dp)
        ) {
            val screenWidth = maxWidth
            val sidebarWidth = when {
                screenWidth > 1150.dp -> 280.dp
                screenWidth > 900.dp -> 255.dp
                screenWidth > 700.dp -> 230.dp
                else -> 205.dp
            }

            // 外层悬浮拟物壳体
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .neuFloatingShell(cornerRadius = 24.dp)
                    .padding(14.dp)
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ====================================================================
                // 1. 车机顶部状态栏 (Status Bar)
                // ====================================================================
                StatusBar(
                    enabledSourcesCount = state.sources.count { it.enabled },
                    isQWeather = state.weather.enableQWeather,
                    qWeatherKey = state.weather.apiKey,
                    qWeatherHost = state.weather.apiHost,
                    isQWeatherConnected = state.weather.isQWeatherConnected,
                    isProjecting = state.xiaoou.isProjecting,
                    currentMusicTitle = state.currentMusicTitle
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ====================================================================
                // 2. 核心内容区：左侧悬浮式菜单栏 + 右侧详细设置区
                // ====================================================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 左侧悬浮式菜单栏 (Floating Left Sidebar)
                    Sidebar(
                        selectedCategory = state.activeCategory,
                        onSelectCategory = { viewModel.selectCategory(it) },
                        isProjecting = state.xiaoou.isProjecting,
                        onToggleProjection = { viewModel.toggleProjection(it) },
                        masterLyricsEnabled = state.lyrics.masterEnabled,
                        onToggleMasterLyrics = { viewModel.toggleMasterLyrics(it) },
                        modifier = Modifier
                            .width(sidebarWidth)
                            .fillMaxHeight()
                    )

                    // 右侧详细设置面板 (Right Detail Panel)
                    DetailPanel(
                        selectedCategory = state.activeCategory,
                        viewModel = viewModel,
                        state = state,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            // ====================================================================
            // 3. 全局弹窗与 Toast
            // ====================================================================
            state.activeAnnotation?.let { info ->
                AnnotationPopover(
                    info = info,
                    onDismiss = { viewModel.hideAnnotation() }
                )
            }

            if (state.showDebugLogDialog) {
                DebugLogDialog(
                    onDismiss = { viewModel.showDebugLogDialog(false) },
                    onShowToast = { viewModel.showToast(it) }
                )
            }

            if (state.showImportDialog) {
                ImportSourceDialog(
                    onDismiss = { viewModel.showImportDialog(false) },
                    onSourcesImported = { viewModel.onSourcesImported(it) },
                    onShowToast = { viewModel.showToast(it) }
                )
            }

            if (state.showSourceManagerDialog) {
                SourceManagerDialog(
                    sources = state.sources,
                    onDismiss = { viewModel.showSourceManagerDialog(false) },
                    onSwitchToImport = {
                        viewModel.showSourceManagerDialog(false)
                        viewModel.showImportDialog(true)
                    },
                    onToggleEnabled = { viewModel.toggleSourceEnabled(it) },
                    onDeleteSource = { viewModel.deleteSource(it) },
                    onDeleteMultiple = { viewModel.deleteMultipleSources(it) },
                    onShowToast = { viewModel.showToast(it) }
                )
            }

            // 轻提示 Toast
            NeuToast(
                message = state.toastMessage,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 12.dp)
            )
        }
    }
}

/**
 * 顶部状态栏
 * 严格按照逻辑精细化联动：
 *   - 天气源：开启并填写和风 API 后显示「天气源: 和风高精度」，否则显示回落
 *   - 投屏状态：投屏开启才显示绿色「小欧屏投屏中」，关闭时显示「小欧屏未投屏」
 *   - 当前播放音乐：播放音乐显示歌名，未播放显示「未播放音乐」
 */
@Composable
private fun StatusBar(
    enabledSourcesCount: Int,
    isQWeather: Boolean,
    qWeatherKey: String,
    qWeatherHost: String,
    isQWeatherConnected: Boolean,
    isProjecting: Boolean,
    currentMusicTitle: String?,
    modifier: Modifier = Modifier
) {
    // 实时车机时钟 (局限在 StatusBar 内部重组，不触发全局重组)
    var currentTime by remember { mutableStateOf("11:28") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            currentTime = sdf.format(Date())
            delay(1000)
        }
    }

    // 只要开启了和风天气且配置了有效 API Key（或测试连通），小欧屏与设置顶栏均展示和风高精度配置
    val isQWeatherValid = isQWeather && qWeatherKey.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuInset(cornerRadius = 14.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：小欧屏标题、歌词源插件徽章、天气源徽章，以及紧随其后的小欧投屏状态
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "小欧屏",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeuColors.TextPrimary,
                letterSpacing = 0.5.sp
            )

            // 歌词源插件徽章
            Box(
                modifier = Modifier
                    .background(Color(0xFFEFF6FF), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "歌词源: $enabledSourcesCount 项启用",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuColors.AccentBlue,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // 天气源徽章：真实测试连通后显示和风高精度，否则显示回落
            Box(
                modifier = Modifier
                    .background(
                        color = if (isQWeatherValid) Color(0xFFECFDF5) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isQWeatherValid) "天气: 和风高精度" else "天气: Open-Meteo",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isQWeatherValid) NeuColors.AccentGreen else NeuColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // 小欧是否投屏展示 (紧跟在天气源后方)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(
                        color = if (isProjecting) Color(0xFFDCFCE7).copy(alpha = 0.55f) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isProjecting) NeuColors.AccentGreen
                            else NeuColors.TextMuted
                        )
                )
                Text(
                    text = if (isProjecting) "投屏中" else "未投屏",
                    fontSize = 11.sp,
                    fontWeight = if (isProjecting) FontWeight.Bold else FontWeight.Normal,
                    color = if (isProjecting) NeuColors.AccentGreen else NeuColors.TextMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // 右侧：音乐胶囊、时钟
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 当前展示歌词曲目胶囊：有播放/识别音乐显示当前歌名与歌手，未播放音乐显示「未播放音乐」
            Box(
                modifier = Modifier
                    .neuInset(cornerRadius = 8.dp)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .widthIn(max = 240.dp)
            ) {
                Text(
                    text = if (!currentMusicTitle.isNullOrBlank()) currentMusicTitle else "未播放音乐",
                    fontSize = 11.sp,
                    fontWeight = if (!currentMusicTitle.isNullOrBlank()) FontWeight.Bold else FontWeight.Normal,
                    color = if (!currentMusicTitle.isNullOrBlank()) NeuColors.AccentBlue else NeuColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 车机时钟
            Text(
                text = currentTime,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NeuColors.TextPrimary
            )
        }
    }
}

/**
 * 左侧悬浮式菜单栏 (宽度自适应，圆角 22dp，拟物浮动阴影)
 * 包含：
 *   1. 顶部标题
 *   2. 大号开启/关闭投屏主开关卡片
 *   3. 6 个一级菜单项（项5为全屏歌词，带独立快捷开关）
 */
@Composable
private fun Sidebar(
    selectedCategory: MenuItemId,
    onSelectCategory: (MenuItemId) -> Unit,
    isProjecting: Boolean,
    onToggleProjection: (Boolean) -> Unit,
    masterLyricsEnabled: Boolean,
    onToggleMasterLyrics: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .neuFloatingShell(cornerRadius = 22.dp)
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
                Text(
                    text = "悬浮导航 · 车机设置中心",
                    fontSize = 10.sp,
                    color = NeuColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 【核心要求】在设置的菜单栏下做一个大的开启和关闭投屏的开关
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuCard(
                        cornerRadius = 14.dp,
                        borderColor = if (isProjecting) Color(0xFF93C5FD).copy(alpha = 0.8f) else Color.Transparent
                    )
                    .padding(horizontal = 10.dp, vertical = 10.dp)
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
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isProjecting) Color(0xFFDCFCE7) else Color(0xFFF1F5F9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "小欧屏投屏",
                                tint = if (isProjecting) NeuColors.AccentGreen else NeuColors.TextMuted,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "小欧屏投屏",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (isProjecting) Color(0xFFDCFCE7) else Color(0xFFE2E8F0),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = if (isProjecting) "投屏中" else "已暂停",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isProjecting) NeuColors.AccentGreen else NeuColors.TextSecondary
                                    )
                                }
                            }
                            Text(
                                text = "副屏无线流转服务",
                                fontSize = 9.sp,
                                color = NeuColors.TextMuted
                            )
                        }
                    }

                    NeuSwitch(
                        checked = isProjecting,
                        onCheckedChange = onToggleProjection
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 6 个一级菜单项 (支持垂直滚动自适应不同车载屏幕高度)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MenuItemId.values().forEach { item ->
                    val isSelected = selectedCategory == item

                    SidebarMenuItem(
                        item = item,
                        isSelected = isSelected,
                        onClick = { onSelectCategory(item) },
                        masterLyricsEnabled = masterLyricsEnabled,
                        onToggleMasterLyrics = onToggleMasterLyrics
                    )
                }
            }
        }
    }
}

/**
 * 菜单项组件 (第 5 项全屏歌词挂载独立拟物开关)
 */
@Composable
private fun SidebarMenuItem(
    item: MenuItemId,
    isSelected: Boolean,
    onClick: () -> Unit,
    masterLyricsEnabled: Boolean,
    onToggleMasterLyrics: (Boolean) -> Unit
) {
    val icon = when (item) {
        MenuItemId.WELCOME -> Icons.Default.Home
        MenuItemId.XIAOOU_SCREEN -> Icons.Default.PermDeviceInformation
        MenuItemId.WEATHER_SERVICE -> Icons.Default.CloudQueue
        MenuItemId.LYRICS_SETTINGS -> Icons.Default.QueueMusic
        MenuItemId.FULLSCREEN_LYRICS -> Icons.Default.MusicNote
        MenuItemId.SERVICE_SETTINGS -> Icons.Default.Settings
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(
                if (isSelected) {
                    Modifier.neuInset(
                        cornerRadius = 14.dp,
                        backgroundColor = Color(0xFFEFF4F9)
                    )
                } else {
                    Modifier.neuButton(isPressed = false, cornerRadius = 14.dp)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
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
                // 激活指示圆柱
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .background(NeuColors.AccentBlue, RoundedCornerShape(2.dp))
                    )
                }

                Icon(
                    imageVector = icon,
                    contentDescription = item.title,
                    tint = if (isSelected) NeuColors.AccentBlue else NeuColors.TextSecondary,
                    modifier = Modifier.size(17.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) NeuColors.AccentBlue else NeuColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        fontSize = 9.sp,
                        color = NeuColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 第 5 项全屏歌词直接挂载快捷拟物开关 (紧凑模式，高度完美统一)
            if (item.hasQuickSwitch) {
                NeuSwitch(
                    checked = masterLyricsEnabled,
                    onCheckedChange = onToggleMasterLyrics,
                    isCompact = true
                )
            }
        }
    }
}

/**
 * 右侧详细设置面板
 */
@Composable
private fun DetailPanel(
    selectedCategory: MenuItemId,
    viewModel: SettingsViewModel,
    state: SettingsState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .neuFloatingShell(cornerRadius = 22.dp)
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 右侧顶部标题条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = selectedCategory.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )

                    Text(
                        text = "—  ${selectedCategory.subtitle}",
                        fontSize = 12.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .neuInset(cornerRadius = 8.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "车载运行中",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuColors.AccentGreen
                    )
                }
            }

            // 针对每个菜单页保持独立的滚动位置，避免切页时跳回顶部
            val scrollStates = remember { MenuItemId.values().associateWith { androidx.compose.foundation.ScrollState(0) } }
            val currentScrollState = scrollStates[selectedCategory] ?: rememberScrollState()

            var showContactAuthorDialog by remember { mutableStateOf(false) }

            // 滚动内容与悬浮操作层容器
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 滚动内容视图
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(currentScrollState)
                ) {
                    AnimatedContent(
                        targetState = selectedCategory,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(180))
                        },
                        label = "pageTransition"
                    ) { category ->
                        when (category) {
                            MenuItemId.WELCOME -> WelcomePage(
                                state = state.welcome,
                                onNavigate = { viewModel.selectCategory(it) },
                                onGrantAllPermissions = { viewModel.checkAndRequestNextPermission() },
                                onTogglePermission = { viewModel.openPermissionSetting(it) },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                            MenuItemId.XIAOOU_SCREEN -> XiaoOuPage(
                                state = state.xiaoou,
                                onToggleProjection = { viewModel.toggleProjection(it) },
                                onToggleShowAvatar = { viewModel.toggleShowAvatar(it) },
                                onToggleLiquidGlass = { viewModel.toggleLiquidGlassClock(it) },
                                onToggleMiniGear = { viewModel.toggleMiniGearFloat(it) },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                            MenuItemId.WEATHER_SERVICE -> WeatherPage(
                                state = state.weather,
                                onToggleEnableQWeather = { viewModel.toggleEnableQWeather(it) },
                                onSaveHost = { viewModel.saveQWeatherHost(it) },
                                onSaveKey = { viewModel.saveQWeatherKey(it) },
                                onTestConnection = { viewModel.testQWeatherConnection() },
                                onClearCredentials = { viewModel.clearQWeatherCredentials() },
                                onRefreshWeather = { viewModel.refreshWeatherNow() },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                            MenuItemId.LYRICS_SETTINGS -> LyricsSettingsPage(
                                state = state.lyrics,
                                sources = state.sources,
                                onToggleSourceEnabled = { viewModel.toggleSourceEnabled(it) },
                                onDeleteCheckedSources = { viewModel.showSourceManagerDialog(true) },
                                onPreferredSourceChange = { viewModel.setPreferredSource(it) },
                                onReorderSources = { viewModel.reorderSources(it) },
                                onOpenImportDialog = { viewModel.showImportDialog(true) },
                                onOpenSourceManagerDialog = { viewModel.showSourceManagerDialog(true) },
                                onTimeOffsetChange = { viewModel.setTimeOffset(it) },
                                onSaveCustomLyricUrl = { viewModel.saveCustomLyricUrl(it) },
                                onSaveCustomCoverUrl = { viewModel.saveCustomCoverUrl(it) },
                                onSaveCustomApiKey = { viewModel.saveCustomApiKey(it) },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                            MenuItemId.FULLSCREEN_LYRICS -> FullscreenLyricsPage(
                                state = state.lyrics,
                                onToggleMasterEnabled = { viewModel.toggleMasterLyrics(it) },
                                onSkinThemeChange = { viewModel.setSkinTheme(it) },
                                onToggleLongMarquee = { viewModel.toggleLongMarquee(it) },
                                onAlignModeChange = { viewModel.setAlignMode(it) },
                                onSplitRatioChange = { viewModel.setSplitRatio(it) },
                                onCoverScaleChange = { viewModel.setCoverScale(it) },
                                onTitleFontSizeChange = { viewModel.setTitleFontSize(it) },
                                onLyricFontSizeChange = { viewModel.setLyricFontSize(it) },
                                onResetSlidersToCenter = { viewModel.resetSlidersToCenter() },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                            MenuItemId.SERVICE_SETTINGS -> ServicePage(
                                state = state.service,
                                onToggleBootAutoStart = { viewModel.toggleBootAutoStart(it) },
                                onToggleDebugLog = { viewModel.toggleDebugLog(it) },
                                onOpenDebugLogDialog = { viewModel.showDebugLogDialog(true) },
                                onCleanCache = { viewModel.cleanCache() },
                                onCacheLimitChange = { viewModel.setCacheLimitMB(it) },
                                onShowInfo = { viewModel.showAnnotation(it) }
                            )
                        }
                    }
                }

                // 仅存在在欢迎页的右下角悬浮圆形按钮
                if (selectedCategory == MenuItemId.WELCOME) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                            .size(62.dp)
                            .neuButton(cornerRadius = 31.dp, isPrimary = true)
                            .clickable { showContactAuthorDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "联系作者",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "联系作者",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 联系作者弹窗
            if (showContactAuthorDialog) {
                ContactAuthorDialog(onDismiss = { showContactAuthorDialog = false })
            }
        }
    }
}

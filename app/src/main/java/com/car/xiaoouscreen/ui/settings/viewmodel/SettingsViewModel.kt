package com.car.xiaoouscreen.ui.settings.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.car.xiaoouscreen.BuildConfig
import com.car.xiaoouscreen.data.QWeatherConfigManager
import com.car.xiaoouscreen.data.QWeatherRepository
import com.car.xiaoouscreen.location.CarGpsManager
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.LyricSourceItemState
import com.car.xiaoouscreen.ui.settings.model.MenuItemId
import com.car.xiaoouscreen.ui.settings.model.SettingsState
import com.car.xiaoouscreen.util.NotificationPermissionHelper
import com.carlyrics.CarLyricsApplication
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.lyrics.LyricsSource
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.skin.SkinType
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager
import com.carlyrics.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置中心主控 ViewModel (规范重构版)
 * 统一管理系统权限跳转、开机启动、调试日志弹窗、真实缓存清理与上限调节、和风天气真实连通性测试与歌词源插件管理
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val context: Context get() = getApplication()
    private val qWeatherConfig = QWeatherConfigManager(context)
    private val qWeatherRepo = QWeatherRepository(context)

    private val _uiState = MutableStateFlow(SettingsState())
    val uiState: StateFlow<SettingsState> = _uiState.asStateFlow()

    private var toastJob: Job? = null

    init {
        loadPersistedSettings()
        refreshSourcesAsync()
        refreshPermissionStatesAsync()
        refreshCacheSize()

        // 实时观察全局音乐识别音轨，自动回显右上角状态栏展示歌词的歌名
        viewModelScope.launch {
            try {
                CarLyricsApplication.recognitionEngine.currentTrack.collectLatest { track ->
                    val displayTitle = if (track != null && track.title.isNotBlank()) {
                        if (track.artist.isNotBlank()) {
                            "${track.title} — ${track.artist}"
                        } else {
                            track.title
                        }
                    } else {
                        null
                    }
                    _uiState.update { it.copy(currentMusicTitle = displayTitle) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "collect currentTrack failed: ${e.message}")
            }
        }
    }

    /**
     * 从本地持久化存储加载配置 (纯轻量内存与 SharedPreferences 读取，杜绝主线程重型文件 I/O)
     */
    fun loadPersistedSettings() {
        val masterEnabled = try { PrefsManager.isStandaloneLyricsEnabled() } catch (_: Exception) { false }
        val showAvatar = try { PrefsManager.isXiaoOuAvoidanceEnabled() } catch (_: Exception) { true }
        val liquidGlassClock = try { PrefsManager.isLiquidGlassClockEnabled() } catch (_: Exception) { true }
        val miniGearFloat = try { PrefsManager.isBottomLeftGearHudEnabled() } catch (_: Exception) { true }

        val splitRatio = try { (PrefsManager.getSplitRatio() * 100f).roundToInt().coerceIn(15, 80) } catch (_: Exception) { 25 }
        val rawDpi = try { PrefsManager.getDpiScale() } catch (_: Exception) { 0.85f }
        val coverScale = (rawDpi * 100f).roundToInt().coerceIn(50, 150)

        val rawInfoFont = try { PrefsManager.getInfoFontScale() } catch (_: Exception) { 27f / 22f }
        val titleFontSize = (rawInfoFont * 22f).roundToInt().coerceIn(14, 36)

        val rawFontSize = try { PrefsManager.getFontSizeSp() } catch (_: Exception) { 88f }
        val lyricFontSize = rawFontSize.roundToInt().coerceIn(50, 130)

        val timeOffset = try { PrefsManager.lyricsOffsetMs.toInt().coerceIn(-2000, 2000) } catch (_: Exception) { 0 }
        val longMarquee = true

        val skinTheme = try { PrefsManager.getSkinType().name } catch (_: Exception) { SkinType.DEFAULT.name }
        val alignMode = if (skinTheme == SkinType.VIBRANT_WAVE.name) "center" else "left"

        val customLyricUrl = try { PrefsManager.getLyricsApiUrl() } catch (_: Exception) { "" }
        val customCoverUrl = try { PrefsManager.getCoverApiUrl() } catch (_: Exception) { "" }
        val customApiKey = try { PrefsManager.getApiKey() } catch (_: Exception) { "" }

        val bootAutoStart = try { PrefsManager.isAutoStartBoot() } catch (_: Exception) { true }
        val debugLogEnabled = try { PrefsManager.isLogEnabled() } catch (_: Exception) { false }
        val cacheLimitMB = try { PrefsManager.getCacheMaxSizeMb().coerceIn(8, 128) } catch (_: Exception) { 64 }

        // 和风天气
        val qEnabled = qWeatherConfig.isEnabled
        val qHost = qWeatherConfig.apiHost.ifBlank { "devapi.qweather.com" }
        val qKey = qWeatherConfig.apiKey.ifBlank { "" }
        val qConnected = if (qEnabled && qKey.isNotBlank()) true else false
        val qLastResult = qWeatherConfig.lastTestResult

        // 真实版本号
        val appVersionStr = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // 极速快照获取当前已就绪的歌词源，绝不阻塞主线程冷启
        val fastList = LyricsSourceManager.getCachedSourcesFast()
        val sourcesList = if (fastList.isNotEmpty()) {
            fastList.map { src ->
                val isDefault = src.id.contains("gdstudio", true) || src.name.contains("GD Studio", true)
                LyricSourceItemState(
                    id = src.id,
                    name = src.name,
                    category = if (isDefault) "默认内置源"
                    else if (src.name.contains("krc", true) || src.description.contains("krc", true)) "逐字KRC"
                    else if (src.name.contains("qrc", true) || src.description.contains("qrc", true)) "逐字QRC"
                    else "逐字增强",
                    url = src.url.ifBlank { "plugin://${src.id}" },
                    enabled = src.isEnabled,
                    latency = "已就绪",
                    official = isDefault
                )
            }
        } else {
            _uiState.value.sources
        }

        val isProjecting = (WeatherOverlayService.instance?.isShowing() == true) || PrefsManager.getOverlayVisible()

        _uiState.update { current ->
            current.copy(
                xiaoou = current.xiaoou.copy(
                    isProjecting = isProjecting,
                    showAvatar = showAvatar,
                    liquidGlassClock = liquidGlassClock,
                    miniGearFloat = miniGearFloat
                ),
                weather = current.weather.copy(
                    enableQWeather = qEnabled,
                    apiHost = qHost,
                    apiKey = qKey,
                    isQWeatherConnected = qConnected,
                    lastTestResult = qLastResult
                ),
                lyrics = current.lyrics.copy(
                    masterEnabled = masterEnabled,
                    skinTheme = skinTheme,
                    longMarquee = longMarquee,
                    alignMode = alignMode,
                    splitRatio = splitRatio,
                    coverScale = coverScale,
                    titleFontSize = titleFontSize,
                    lyricFontSize = lyricFontSize,
                    timeOffset = timeOffset,
                    customLyricUrl = customLyricUrl,
                    customCoverUrl = customCoverUrl,
                    customApiKey = customApiKey
                ),
                sources = sourcesList,
                service = current.service.copy(
                    bootAutoStart = bootAutoStart,
                    debugLogEnabled = debugLogEnabled,
                    cacheLimitMB = cacheLimitMB,
                    appVersion = appVersionStr
                )
            )
        }
    }

    private fun loadSourcesFromManager(): List<LyricSourceItemState> {
        return try {
            val list = LyricsSourceManager.getAllSources()
            if (list.isNotEmpty()) {
                list.map { src ->
                    val isDefault = src.id.contains("gdstudio", true) || src.name.contains("GD Studio", true)
                    LyricSourceItemState(
                        id = src.id,
                        name = src.name,
                        category = if (isDefault) "默认内置源"
                        else if (src.script.contains("krc", true) || src.name.contains("krc", true)) "逐字KRC"
                        else if (src.script.contains("qrc", true) || src.name.contains("qrc", true)) "逐字QRC"
                        else "逐字增强",
                        url = src.url.ifBlank { "plugin://${src.id}" },
                        enabled = src.isEnabled,
                        latency = "已就绪",
                        official = isDefault
                    )
                }
            } else {
                listOf(
                    LyricSourceItemState(
                        id = "gdstudio_proxy",
                        name = "GD Studio (聚合源)",
                        category = "默认内置源",
                        url = "https://music-api.gdstudio.xyz/api.php",
                        enabled = true,
                        latency = "已就绪",
                        official = true
                    )
                )
            }
        } catch (_: Exception) {
            _uiState.value.sources
        }
    }

    /**
     * 异步并发更新歌词源列表，杜绝主线程卡顿
     */
    fun refreshSourcesAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = loadSourcesFromManager()
            _uiState.update { it.copy(sources = refreshed) }
        }
    }

    /**
     * 异步并发刷新系统权限状态
     */
    fun refreshPermissionStatesAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshPermissionStates()
        }
    }

    /**
     * 刷新核心系统权限状态
     */
    fun refreshPermissionStates() {
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        val notificationGranted = NotificationPermissionHelper.isNotificationListenerGranted(context)

        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val phoneGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        _uiState.update { current ->
            val updatedPerms = current.welcome.permissions.map { perm ->
                when (perm.id) {
                    "perm_overlay" -> perm.copy(granted = overlayGranted)
                    "perm_notification" -> perm.copy(granted = notificationGranted)
                    "perm_location" -> perm.copy(granted = locationGranted)
                    "perm_phone" -> perm.copy(granted = phoneGranted)
                    else -> perm
                }
            }
            current.copy(welcome = current.welcome.copy(permissions = updatedPerms))
        }
    }

    // ==================== 导航与弹窗 ====================

    fun selectCategory(category: MenuItemId) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun showAnnotation(info: AnnotationInfo) {
        _uiState.update { it.copy(activeAnnotation = info) }
    }

    fun hideAnnotation() {
        _uiState.update { it.copy(activeAnnotation = null) }
    }

    fun showImportDialog(show: Boolean) {
        _uiState.update { it.copy(showImportDialog = show) }
    }

    fun showSourceManagerDialog(show: Boolean) {
        _uiState.update { it.copy(showSourceManagerDialog = show) }
    }

    fun showDebugLogDialog(show: Boolean) {
        _uiState.update { it.copy(showDebugLogDialog = show) }
    }

    fun showToast(message: String) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = message) }
        toastJob = viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    // ==================== 模块 1：欢迎页权限联动 ====================

    /**
     * 打开对应权限的系统设置授权页
     */
    fun openPermissionSetting(permId: String) {
        try {
            when (permId) {
                "perm_overlay" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    }
                }
                "perm_notification" -> {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "perm_location", "perm_phone" -> {
                    // 跳转到应用详情授权页
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            showToast("打开系统设置失败: ${e.message}")
        }
    }

    /**
     * 一键检测权限：逐一检查是否有权限，若没有则跳至系统开启对应权限
     */
    fun checkAndRequestNextPermission() {
        refreshPermissionStates()
        val perms = _uiState.value.welcome.permissions
        val firstMissing = perms.find { !it.granted }

        if (firstMissing != null) {
            showToast("正在前往开启「${firstMissing.name}」")
            openPermissionSetting(firstMissing.id)
        } else {
            showToast("全部车机核心权限均已成功开启！")
        }
    }

    // ==================== 模块 2：小欧屏设置 ====================

    fun requestOverlayPermission() {
        openPermissionSetting("perm_overlay")
    }

    fun toggleProjection(enabled: Boolean) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                _uiState.update { it.copy(xiaoou = it.xiaoou.copy(isProjecting = false)) }
                showToast("请先授予「车机悬浮窗权限」以显示小欧屏")
                requestOverlayPermission()
                return
            }
            try {
                PrefsManager.setOverlayVisible(true)
                val showAvatar = _uiState.value.xiaoou.showAvatar
                val action = if (showAvatar) WeatherOverlayService.ACTION_SHOW_FITNESS else WeatherOverlayService.ACTION_SHOW_TOP
                WeatherOverlayService.send(context, action)
                _uiState.update { it.copy(xiaoou = it.xiaoou.copy(isProjecting = true)) }
                showToast("小欧屏投屏已开启 (悬浮窗已就绪)")
            } catch (e: Exception) {
                showToast("启动投屏服务失败: ${e.message}")
            }
        } else {
            try {
                PrefsManager.setOverlayVisible(false)
                WeatherOverlayService.instance?.hideOverlay()
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_HIDE)
                _uiState.update { it.copy(xiaoou = it.xiaoou.copy(isProjecting = false)) }
                showToast("小欧屏投屏已关闭")
            } catch (e: Exception) {
                showToast("关闭投屏服务失败: ${e.message}")
            }
        }
    }

    fun onOverlayStateChanged(isShowing: Boolean) {
        _uiState.update { it.copy(xiaoou = it.xiaoou.copy(isProjecting = isShowing)) }
    }

    fun setMusicTitle(title: String?) {
        _uiState.update { it.copy(currentMusicTitle = title) }
    }

    fun toggleShowAvatar(enabled: Boolean) {
        _uiState.update { it.copy(xiaoou = it.xiaoou.copy(showAvatar = enabled)) }
        try {
            PrefsManager.setXiaoOuAvatarEnabled(enabled)
            WeatherOverlayService.updateXiaoOuAvatarState(context, enabled)
        } catch (_: Exception) {}
        showToast("小欧形象已${if (enabled) "开启" else "关闭"}，需要重新投屏才能生效")
    }

    fun toggleLiquidGlassClock(enabled: Boolean) {
        _uiState.update { it.copy(xiaoou = it.xiaoou.copy(liquidGlassClock = enabled)) }
        try {
            PrefsManager.setLiquidGlassClockEnabled(enabled)
            WeatherOverlayService.updateLiquidGlassState(context, enabled)
        } catch (_: Exception) {}
        val isProj = _uiState.value.xiaoou.isProjecting || WeatherOverlayService.instance?.isShowing() == true
        showToast("「时钟液态玻璃」已${if (enabled) "开启" else "关闭"}${if (isProj) " · 已实时生效" else " · 开启投屏时生效"}")
    }

    fun toggleMiniGearFloat(enabled: Boolean) {
        _uiState.update { it.copy(xiaoou = it.xiaoou.copy(miniGearFloat = enabled)) }
        try {
            PrefsManager.setBottomLeftGearHudEnabled(enabled)
            WeatherOverlayService.updateBottomLeftGearHudState(context, enabled)
        } catch (_: Exception) {}
        val isProj = _uiState.value.xiaoou.isProjecting || WeatherOverlayService.instance?.isShowing() == true
        showToast("「微型档位悬浮」已${if (enabled) "开启" else "关闭"}${if (isProj) " · 已实时生效" else " · 开启投屏时生效"}")
    }

    // ==================== 模块 3：天气服务设置 ====================

    fun toggleEnableQWeather(enabled: Boolean) {
        qWeatherConfig.isEnabled = enabled
        val isConn = enabled && qWeatherConfig.apiKey.isNotBlank()
        _uiState.update { it.copy(weather = it.weather.copy(enableQWeather = enabled, isQWeatherConnected = isConn)) }
        showToast(
            if (enabled) "已开启和风高精度天气，专属配置已展开"
            else "已关闭和风高精度天气，自动回落至 Open-Meteo"
        )
        try {
            WeatherOverlayService.send(context, WeatherOverlayService.ACTION_RESET_LIVE)
        } catch (_: Exception) {}
    }

    fun saveQWeatherHost(host: String) {
        val trimmed = host.trim().ifBlank { "devapi.qweather.com" }
        _uiState.update { it.copy(weather = it.weather.copy(apiHost = trimmed)) }
        qWeatherConfig.apiHost = trimmed
        showToast("已保存专属 API Host：$trimmed")
    }

    fun saveQWeatherKey(key: String) {
        val trimmed = key.trim()
        val isConn = _uiState.value.weather.enableQWeather && trimmed.isNotBlank()
        _uiState.update { it.copy(weather = it.weather.copy(apiKey = trimmed, isQWeatherConnected = isConn)) }
        qWeatherConfig.apiKey = trimmed
        showToast("已保存和风天气 API Key")
    }

    /**
     * 真实执行和风天气连接测试
     */
    fun testQWeatherConnection() {
        val w = _uiState.value.weather
        val host = w.apiHost.trim().ifBlank { "devapi.qweather.com" }
        val key = w.apiKey.trim()

        if (key.isBlank()) {
            val failMsg = "连接失败：请先填写和风天气 API Key"
            _uiState.update {
                it.copy(
                    weather = it.weather.copy(
                        lastTestResult = failMsg,
                        isQWeatherConnected = false
                    )
                )
            }
            showToast("请先填写和风天气 API Key")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(weather = it.weather.copy(isTesting = true, lastTestResult = "正在测试与和风气象节点的连接...")) }

            val result = withContext(Dispatchers.IO) {
                qWeatherRepo.testConnection(key = key, host = host)
            }

            result.onSuccess { msg ->
                qWeatherRepo.resetCircuitBreaker()
                qWeatherConfig.saveConfig(key, host)
                qWeatherConfig.isEnabled = true
                qWeatherConfig.isConnected = true
                qWeatherConfig.lastTestResult = msg
                _uiState.update {
                    it.copy(
                        weather = it.weather.copy(
                            isTesting = false,
                            isQWeatherConnected = true,
                            enableQWeather = true,
                            lastTestResult = msg
                        )
                    )
                }
                showToast("和风天气连接成功！已切换为和风高精度天气")
                // 通知前台服务立即刷新
                try {
                    WeatherOverlayService.send(context, WeatherOverlayService.ACTION_RESET_LIVE)
                } catch (_: Exception) {}
            }.onFailure { err ->
                qWeatherConfig.isConnected = false
                val failMsg = "连接失败: ${err.message}"
                qWeatherConfig.lastTestResult = failMsg
                _uiState.update {
                    it.copy(
                        weather = it.weather.copy(
                            isTesting = false,
                            isQWeatherConnected = false,
                            lastTestResult = failMsg
                        )
                    )
                }
                showToast(failMsg)
            }
        }
    }

    fun clearQWeatherCredentials() {
        qWeatherConfig.clearConfig()
        _uiState.update {
            it.copy(
                weather = it.weather.copy(
                    apiHost = "devapi.qweather.com",
                    apiKey = "",
                    isQWeatherConnected = false,
                    lastTestResult = "凭据已清空 · 请重新输入或关闭开关回落至 Open-Meteo"
                )
            )
        }
        showToast("已清空和风天气 API Host 与 API Key 凭据")
        try {
            WeatherOverlayService.send(context, WeatherOverlayService.ACTION_RESET_LIVE)
        } catch (_: Exception) {}
    }

    /**
     * 手动触发实时天气更新
     * 重新请求当前使用的天气接口（和风高精度天气或 Open-Meteo），刷新当前位置与当前时间点气象数据
     */
    fun refreshWeatherNow() {
        val weatherState = _uiState.value.weather
        val canUseQWeather = weatherState.enableQWeather && weatherState.apiKey.isNotBlank()
        val providerName = if (canUseQWeather) "和风高精度天气" else "Open-Meteo"
        showToast("正在请求 $providerName 获取当前实时天气...")

        viewModelScope.launch {
            _uiState.update { it.copy(weather = it.weather.copy(isTesting = true)) }

            // 1. 若 WeatherOverlayService 运行中，通知其强制重置并刷新气象引擎
            try {
                WeatherOverlayService.send(context, WeatherOverlayService.ACTION_RESET_LIVE)
            } catch (_: Exception) {}

            val gps = CarGpsManager(context)
            val lat = gps.currentLatitude
            val lon = gps.currentLongitude

            // 2. 只有在能够调用和风更新的状态下（开启且已填写有效 API Key），才调用和风接口
            if (canUseQWeather) {
                val host = weatherState.apiHost.trim().ifBlank { "devapi.qweather.com" }
                val key = weatherState.apiKey.trim()
                qWeatherRepo.resetCircuitBreaker()
                val result = withContext(Dispatchers.IO) {
                    qWeatherRepo.testConnection(key = key, host = host, lat = lat, lon = lon)
                }
                result.onSuccess { msg ->
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val fullMsg = "$msg (更新于 $timeStr)"
                    _uiState.update {
                        it.copy(
                            weather = it.weather.copy(
                                isTesting = false,
                                lastTestResult = fullMsg
                            )
                        )
                    }
                    showToast("实况天气已成功刷新 (和风高精度)")
                }.onFailure { err ->
                    _uiState.update { it.copy(weather = it.weather.copy(isTesting = false)) }
                    showToast("天气更新失败：${err.message ?: "网络异常"}")
                }
            } else {
                delay(1000)
                _uiState.update { it.copy(weather = it.weather.copy(isTesting = false)) }
                showToast("已完成当前位置实时天气刷新 (Open-Meteo)")
            }
        }
    }

    // ==================== 模块 4：全屏歌词设置 ====================

    fun toggleMasterLyrics(enabled: Boolean) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(masterEnabled = enabled)) }
        try {
            PrefsManager.setStandaloneLyricsEnabled(enabled)
            WeatherOverlayService.instance?.evaluateLyricsStateTransition()
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
        showToast("全屏歌词已${if (enabled) "启用" else "关闭"}")
    }

    fun setSkinTheme(theme: String) {
        val skinType = try {
            when (theme) {
                "liquid_glass" -> SkinType.GLASSMORPHISM
                "dark_streamer" -> SkinType.MINIMAL_NEON
                "minimal_pure" -> SkinType.VIBRANT_WAVE
                "neumorphic" -> SkinType.DEFAULT
                else -> SkinType.valueOf(theme)
            }
        } catch (_: Exception) {
            SkinType.DEFAULT
        }
        val autoAlign = if (skinType == SkinType.VIBRANT_WAVE) "center" else "left"
        _uiState.update { it.copy(lyrics = it.lyrics.copy(skinTheme = skinType.name, alignMode = autoAlign)) }
        try {
            PrefsManager.setSkinType(skinType)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
        showToast("已切换全屏歌词皮肤主题: ${skinType.label}")
    }

    fun toggleLongMarquee(enabled: Boolean) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(longMarquee = enabled)) }
        try {
            PrefsManager.setMarqueeEnabled(enabled)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setAlignMode(mode: String) {
        val effectiveAlign = if (mode.equals("center", ignoreCase = true)) "center" else "left"
        _uiState.update { it.copy(lyrics = it.lyrics.copy(alignMode = effectiveAlign)) }
        val alignInt = if (effectiveAlign == "center") 0 else -1
        try {
            PrefsManager.setLyricsAlignment(alignInt)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setSplitRatio(ratio: Int) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(splitRatio = ratio)) }
        try {
            PrefsManager.setSplitRatio(ratio / 100f)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setCoverScale(scale: Int) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(coverScale = scale)) }
        try {
            PrefsManager.setDpiScale(scale / 100f)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setTitleFontSize(size: Int) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(titleFontSize = size)) }
        try {
            PrefsManager.setInfoFontScale(size / 22f)
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setLyricFontSize(size: Int) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(lyricFontSize = size)) }
        try {
            PrefsManager.setFontSizeSp(size.toFloat())
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun setTimeOffset(offsetMs: Int) {
        _uiState.update { it.copy(lyrics = it.lyrics.copy(timeOffset = offsetMs)) }
        try {
            PrefsManager.lyricsOffsetMs = offsetMs.toLong()
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
    }

    fun resetSlidersToCenter() {
        _uiState.update { current ->
            current.copy(
                lyrics = current.lyrics.copy(
                    splitRatio = 25,
                    coverScale = 85,
                    titleFontSize = 27,
                    lyricFontSize = 88,
                    timeOffset = 0
                )
            )
        }
        try {
            PrefsManager.setSplitRatio(0.25f)
            PrefsManager.setDpiScale(0.85f)
            PrefsManager.setInfoFontScale(27f / 22f)
            PrefsManager.setFontSizeSp(88f)
            PrefsManager.lyricsOffsetMs = 0L
            WeatherOverlayService.instance?.updateLyricsRuntimeSettings()
        } catch (_: Exception) {}
        showToast("已恢复默认排版参数 (25% / 85% / 27px / 88px)")
    }

    // ==================== 歌词源管理 (含 GD Studio 默认源保护) ====================

    fun toggleSourceEnabled(sourceId: String) {
        LyricsSourceManager.toggleSource(sourceId, !_uiState.value.sources.find { it.id == sourceId }?.enabled.let { it ?: true })
        _uiState.update { current ->
            val updated = current.sources.map {
                if (it.id == sourceId) it.copy(enabled = !it.enabled) else it
            }
            current.copy(sources = updated)
        }
    }

    fun deleteSource(sourceId: String) {
        if (sourceId.contains("gdstudio", true)) {
            showToast("默认源 GD Studio 不可删除")
            return
        }
        LyricsSourceManager.deleteSource(sourceId)
        val refreshed = loadSourcesFromManager()
        _uiState.update { it.copy(sources = refreshed) }
    }

    fun deleteMultipleSources(sourceIds: List<String>) {
        val nonDefault = sourceIds.filter { !it.contains("gdstudio", true) }
        for (id in nonDefault) {
            LyricsSourceManager.deleteSource(id)
        }
        val refreshed = loadSourcesFromManager()
        _uiState.update { it.copy(sources = refreshed) }
    }

    fun onSourcesImported(importedList: List<LyricsSource>) {
        if (importedList.isNotEmpty()) {
            showToast("成功导入 ${importedList.size} 个歌词源")
        }
        val refreshed = loadSourcesFromManager()
        _uiState.update { it.copy(sources = refreshed) }
    }

    fun reorderSources(orderedIds: List<String>) {
        val currentList = _uiState.value.sources
        val idMap = currentList.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { idMap[it] } + currentList.filterNot { orderedIds.contains(it.id) }
        _uiState.update { it.copy(sources = reordered) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                LyricsSourceManager.updateOrder(orderedIds)
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新歌词源排序异常: ${e.message}")
            }
        }
        showToast("已更新歌词源优先级排序")
    }

    fun setPreferredSource(sourceId: String) {
        try { PrefsManager.setPreferredLyricsSource(sourceId) } catch (_: Exception) {}
        val target = _uiState.value.sources.find { it.id == sourceId }
        showToast("已将偏好歌词源设为：${target?.name ?: sourceId}")
    }

    fun saveCustomLyricUrl(url: String) {
        val trimmed = url.trim()
        _uiState.update { it.copy(lyrics = it.lyrics.copy(customLyricUrl = trimmed)) }
        try { PrefsManager.setLyricsApiUrl(trimmed) } catch (_: Exception) {}
        showToast("已保存自定义歌词 URL：$trimmed")
    }

    fun saveCustomCoverUrl(url: String) {
        val trimmed = url.trim()
        _uiState.update { it.copy(lyrics = it.lyrics.copy(customCoverUrl = trimmed)) }
        try { PrefsManager.setCoverApiUrl(trimmed) } catch (_: Exception) {}
        showToast("已保存自定义封面 URL：$trimmed")
    }

    fun saveCustomApiKey(key: String) {
        val trimmed = key.trim()
        _uiState.update { it.copy(lyrics = it.lyrics.copy(customApiKey = trimmed)) }
        try { PrefsManager.setApiKey(trimmed) } catch (_: Exception) {}
        showToast("已保存全屏歌词高级 API Key")
    }

    // ==================== 模块 5：服务设置 (真实缓存清理与上限) ====================

    fun toggleBootAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(service = it.service.copy(bootAutoStart = enabled)) }
        try { PrefsManager.setAutoStartBoot(enabled) } catch (_: Exception) {}
        showToast("开机启动已${if (enabled) "开启" else "关闭"}")
    }

    fun toggleDebugLog(enabled: Boolean) {
        _uiState.update { it.copy(service = it.service.copy(debugLogEnabled = enabled)) }
        try { PrefsManager.setLogEnabled(enabled) } catch (_: Exception) {}
        showToast("调试日志记录已${if (enabled) "开启" else "关闭"}")
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val sizeBytes = withContext(Dispatchers.IO) {
                try {
                    val bytes = LyricsCacheManager.getTotalCacheSizeBytes(context)
                    val limitBytes = LyricsCacheManager.getMaxSizeMb() * 1024 * 1024
                    if (bytes > limitBytes) {
                        LyricsCacheManager.enforceSizeLimit(context)
                        LyricsCacheManager.getTotalCacheSizeBytes(context)
                    } else {
                        bytes
                    }
                } catch (_: Exception) { 0L }
            }
            val sizeMB = sizeBytes / (1024f * 1024f)
            _uiState.update {
                it.copy(
                    service = it.service.copy(
                        cacheTotalMB = sizeMB,
                        lyricCacheMB = sizeMB
                    )
                )
            }
        }
    }

    /**
     * 真实清理歌词与封面缓存
     */
    fun cleanCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 1. 彻底清理歌词与封面私有磁盘缓存及 cacheDir 碎片
                    LyricsCacheManager.clearAll(context)
                    // 2. 清理内存中已解析歌词对象 (杜绝脏歌词残留)
                    LyricsApiManager.clearPreloadCache()
                    // 3. 清理 Glide 磁盘图片缓存
                    Glide.get(context).clearDiskCache()
                } catch (e: Exception) {
                    AppLogger.e("SettingsViewModel", "清理缓存异常: ${e.message}")
                }
            }
            try {
                // 4. 清理 Glide 内存 Bitmap 池 (必须在主线程)
                Glide.get(context).clearMemory()
                // 5. 清理共享 HMI 位图引用
                com.car.xiaoouscreen.util.SharedHmiAssetManager.clearAll()
            } catch (_: Exception) {}

            refreshCacheSize()
            showToast("歌词与封面缓存已全部清理完毕")
        }
    }

    fun setCacheLimitMB(limitMB: Int) {
        val clamped = limitMB.coerceIn(8, 128)
        _uiState.update { it.copy(service = it.service.copy(cacheLimitMB = clamped)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PrefsManager.setCacheMaxSizeMb(clamped)
                LyricsCacheManager.setMaxSize(clamped)
            } catch (_: Exception) {}
            refreshCacheSize()
        }
    }
}

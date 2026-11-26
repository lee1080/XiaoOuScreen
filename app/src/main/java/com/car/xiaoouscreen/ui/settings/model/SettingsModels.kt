package com.car.xiaoouscreen.ui.settings.model

import com.car.xiaoouscreen.ui.slot.XiaoOuSlotType

/**
 * 设置一级菜单枚举与配置
 */
enum class MenuItemId(
    val indexStr: String,
    val title: String,
    val subtitle: String,
    val hasQuickSwitch: Boolean = false
) {
    WELCOME(
        indexStr = "1",
        title = "欢迎页",
        subtitle = "车机权限请求指引"
    ),
    XIAOOU_SCREEN(
        indexStr = "2",
        title = "小欧屏设置",
        subtitle = "投屏服务、小欧形象与档位悬浮"
    ),
    WEATHER_SERVICE(
        indexStr = "3",
        title = "天气服务设置",
        subtitle = "和风高精度天气开关与专属API凭据"
    ),
    LYRICS_SETTINGS(
        indexStr = "4",
        title = "歌词设置",
        subtitle = "多平台歌词源插件与高级接口"
    ),
    FULLSCREEN_LYRICS(
        indexStr = "5",
        title = "全屏歌词设置",
        subtitle = "外观皮肤、排版布局与字体调节",
        hasQuickSwitch = true
    ),
    SMART_SCENE(
        indexStr = "6",
        title = "智能场景联动",
        subtitle = "整车信号与座舱多场景联动"
    ),
    SERVICE_SETTINGS(
        indexStr = "7",
        title = "服务设置",
        subtitle = "开机启动、分项缓存清理与系统维护"
    )
}

/**
 * 车机系统权限项
 */
data class PermissionItem(
    val id: String,
    val name: String,
    val badge: String,
    val desc: String,
    val granted: Boolean,
    val infoTitle: String,
    val infoContent: String,
    val infoRecommend: String
)

/**
 * 歌词源插件数据模型
 */
data class LyricSourceItemState(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val enabled: Boolean,
    val latency: String,
    val official: Boolean
)

/**
 * 欢迎页状态
 */
data class WelcomeState(
    val greetingTitle: String = "欢迎使用小欧屏 · 智享座舱伴侣",
    val greetingSubtitle: String = "首次使用请确认核心系统权限已开启，以保障小欧屏各功能正常运作。",
    val isPermissionsCollapsed: Boolean = false,
    val permissions: List<PermissionItem> = listOf(
        PermissionItem(
            id = "perm_overlay",
            name = "车机悬浮窗权限",
            badge = "核心必选",
            desc = "用于在车机桌面及导航界面上方渲染“小欧屏”、“微型档位悬浮”与“全屏歌词”浮层",
            granted = true,
            infoTitle = "车机悬浮窗权限说明",
            infoContent = "Android 车机系统需授予 SYSTEM_ALERT_WINDOW 权限，否则小欧屏及全屏歌词无法在第三方导航界面上方显示。",
            infoRecommend = "建议状态：始终允许显示在其他应用上层"
        ),
        PermissionItem(
            id = "perm_notification",
            name = "通知监听权限",
            badge = "歌词同步",
            desc = "监听车机音乐 App 的播放状态、曲目切换与播放进度通知（MediaSession）",
            granted = true,
            infoTitle = "通知监听权限说明",
            infoContent = "通过 NotificationListenerService 实时捕获当前播放的歌名、歌手与毫秒级进度，不读取任何隐私通讯内容。",
            infoRecommend = "全屏歌词同步滚动的核心依赖权限"
        ),
        PermissionItem(
            id = "perm_location",
            name = "高精定位权限",
            badge = "天气依赖",
            desc = "随车辆行驶位置实时刷新区县级/街道级高精度天气与降水预警",
            granted = false,
            infoTitle = "车机高精定位权限说明",
            infoContent = "和风高精度天气及 Open-Meteo 均依赖车辆经纬度坐标获取实时气象，未开启时仅能显示默认城市天气。",
            infoRecommend = "建议允许，用于获取当前车辆位置的天气数据"
        ),
        PermissionItem(
            id = "perm_phone",
            name = "通话状态权限",
            badge = "通话感知",
            desc = "感知蓝牙来电振铃、接通状态与联系人姓名，在小欧屏呈现通话胶囊",
            granted = false,
            infoTitle = "车机通话感知权限说明",
            infoContent = "用于监听车载蓝牙电话来电/接通状态，小欧屏与副屏及时呈现来电胶囊与通话时长，保障行车安全。",
            infoRecommend = "建议允许，以便小欧屏实现来电悬浮提示"
        )
    )
)

/**
 * 小欧屏状态
 */
data class XiaoOuState(
    val isProjecting: Boolean = false,
    val showAvatar: Boolean = true,
    val liquidGlassClock: Boolean = true,
    val miniGearFloat: Boolean = true,
    val miniGearPosition: Int = 0,
    val miniGearRealtimeColorCalc: Boolean = false,
    val slotA: XiaoOuSlotType = XiaoOuSlotType.WEATHER,
    val slotC: XiaoOuSlotType = XiaoOuSlotType.ALBUM_COVER
)

/**
 * 天气服务状态
 */
data class WeatherState(
    val useCarOutsideTemp: Boolean = true,
    val carOutsideTempC: Float? = null,
    val enableQWeather: Boolean = false,
    val apiHost: String = "devapi.qweather.com",
    val apiKey: String = "",
    val lastTestResult: String = "未进行连接测试 · 请点击下方测试连接",
    val isQWeatherConnected: Boolean = false,
    val isTesting: Boolean = false
)

/**
 * 全屏歌词设置状态
 */
data class LyricsState(
    val masterEnabled: Boolean = false,
    val skinTheme: String = "DEFAULT", // DEFAULT | GLASSMORPHISM | MINIMAL_NEON | VIBRANT_WAVE | VINYL_PLAYER | IMMERSIVE
    val longMarquee: Boolean = true,
    val alignMode: String = "left",   // left | center (动感波浪居中，其余全量左对齐)
    val splitRatio: Int = 25,         // 15..80, 默认 25 (%)
    val coverScale: Int = 85,         // 50..150, 默认 85 (%)
    val titleFontSize: Int = 27,      // 14..36, 默认 27 (px)
    val lyricFontSize: Int = 88,      // 50..130, 默认 88 (px)
    val preferredSourceId: String = "",
    val showDefaultClockWhenNoLyrics: Boolean = true,
    val timeOffset: Int = 0,          // -2000..2000, 默认 0 (ms)
    val customLyricUrl: String = "",
    val customCoverUrl: String = "",
    val customApiKey: String = ""
)

/**
 * 服务设置状态
 */
data class ServiceState(
    val bootAutoStart: Boolean = true,
    val debugLogEnabled: Boolean = false,
    val cacheTotalMB: Float = 0f,
    val lyricCacheMB: Float = 0f,
    val weatherCacheMB: Float = 0f,
    val cacheSelectedScope: String = "all",
    val cacheLimitMB: Int = 64, // 8..128, 默认中位 64 (MB)
    val appVersion: String = "v2.6.0-Release",
    val isCheckingUpdate: Boolean = false
)

/**
 * 智能场景联动 · 氛围灯、转向灯与变道辅助设置状态
 */
data class SmartSceneState(
    // 座舱氛围灯
    val ambientSyncMasterEnabled: Boolean = false,
    val colorFollowsCover: Boolean = true,
    val randomColorMode: Boolean = false,
    val sensitivityPercent: Int = 100, // 50..150
    val isServiceConnected: Boolean = false,
    val currentLuminanceLevel: Int = 0,
    val currentColorIndex: Int = 0,

    // 氛围灯基准亮度与昼夜衰减
    val followCarBrightness: Boolean = true,
    val nativeCarBrightnessLevel: Int = 8, // 1..10
    val customBrightnessPercent: Int = 80, // 10..100
    val effectiveBaseBrightnessPercent: Int = 80, // 10..100
    val isNightMode: Boolean = false,
    val effectiveScale: Float = 0.8f,

    // 转向灯三边律动光效联动
    val turnSignalSyncEnabled: Boolean = true,

    // 变道辅助盲区光幕预警
    val laneChangeAssistEnabled: Boolean = true
)

/**
 * ? 按钮注释弹窗数据
 */
data class AnnotationInfo(
    val title: String,
    val content: String,
    val recommend: String
)

/**
 * 应用内自动更新状态
 */
data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val updateInfo: com.car.xiaoouscreen.updater.UpdateInfo? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadError: String? = null
)

/**
 * 全局设置统一聚合状态
 */
data class SettingsState(
    val activeCategory: MenuItemId = MenuItemId.WELCOME,
    val welcome: WelcomeState = WelcomeState(),
    val xiaoou: XiaoOuState = XiaoOuState(),
    val weather: WeatherState = WeatherState(),
    val lyrics: LyricsState = LyricsState(),
    val smartScene: SmartSceneState = SmartSceneState(),
    val sources: List<LyricSourceItemState> = listOf(
        LyricSourceItemState(
            id = "gdstudio_proxy",
            name = "GD Studio (聚合源)",
            category = "默认必选",
            url = "https://music-api.gdstudio.xyz/api.php",
            enabled = true,
            latency = "18ms",
            official = true
        )
    ),
    val service: ServiceState = ServiceState(),
    val update: AppUpdateUiState = AppUpdateUiState(),
    val currentMusicTitle: String? = null,
    val activeAnnotation: AnnotationInfo? = null,
    val showImportDialog: Boolean = false,
    val showSourceManagerDialog: Boolean = false,
    val showDebugLogDialog: Boolean = false,
    val toastMessage: String? = null
)

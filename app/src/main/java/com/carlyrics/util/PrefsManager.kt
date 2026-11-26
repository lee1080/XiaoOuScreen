package com.carlyrics.util

import android.content.Context
import android.content.SharedPreferences
import com.carlyrics.skin.SkinType

/**
 * 设置持久化管理器。所有用户可配置项通过 SharedPreferences 持久化存储。
 */
object PrefsManager {
    private const val TAG = "PrefsManager"
    private const val PREFS_NAME = "car_lyrics_prefs"

    private const val KEY_SKIN_TYPE = "skin_type"
    private const val KEY_FONT_SIZE_SP = "font_size_sp"
    private const val KEY_HORIZONTAL_OFFSET = "horizontal_offset"
    private const val KEY_SPLIT_RATIO = "split_ratio"
    private const val KEY_LYRICS_ALIGNMENT = "lyrics_alignment"
    private const val KEY_MARQUEE_ENABLED = "marquee_enabled"
    private const val KEY_OVERLAY_WIDTH = "overlay_width"
    private const val KEY_OVERLAY_HEIGHT = "overlay_height"
    private const val KEY_TARGET_DISPLAY_ID = "target_display_id"
    private const val KEY_LYRICS_API_URL = "lyrics_api_url"
    private const val KEY_COVER_API_URL = "cover_api_url"
    private const val KEY_DISPLAY_SCALE = "display_scale"
    private const val KEY_CACHE_MAX_SIZE_MB = "cache_max_size_mb"
    private const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
    private const val KEY_INFO_FONT_SCALE = "info_font_scale"
    private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
    private const val KEY_AUTO_START_BOOT = "auto_start_boot"
    private const val KEY_DISPLAY_AREA_TOP = "display_area_top"
    private const val KEY_DISPLAY_AREA_LEFT = "display_area_left"
    private const val KEY_DISPLAY_AREA_RIGHT = "display_area_right"
    private const val KEY_DISPLAY_AREA_BOTTOM = "display_area_bottom"
    private const val KEY_LOG_ENABLED = "log_enabled"
    private const val KEY_PREFERRED_LYRICS_SOURCE = "preferred_lyrics_source"
    private const val KEY_STANDALONE_LYRICS_ENABLED = "standalone_lyrics_enabled"
    private const val KEY_XIAOOU_AVOIDANCE_ENABLED = "xiaoou_avoidance_enabled"
    private const val KEY_LIQUID_GLASS_CLOCK_ENABLED = "liquid_glass_clock_enabled"
    private const val KEY_BOTTOM_LEFT_GEAR_HUD_ENABLED = "bottom_left_gear_hud_enabled"
    private const val KEY_MINI_GEAR_POSITION = "mini_gear_position"
    private const val KEY_MINI_GEAR_REALTIME_COLOR_ENABLED = "mini_gear_realtime_color_enabled"

    private var prefs: SharedPreferences? = null

    /**
     * 获取安全存储上下文 (Direct Boot 设备保护区支持)
     * 在 Android 7.0+ (API 24+) 车机点火但尚未解锁阶段，提供无需凭据解密的 DE 保护区存储，
     * 彻底消灭 credential encrypted storage are not available until after user is unlocked 异常。
     */
    fun getSafeStorageContext(context: Context): Context {
        val appCtx = context.applicationContext ?: context
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            appCtx.createDeviceProtectedStorageContext()
        } else {
            appCtx
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        val targetCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val dpCtx = appCtx.createDeviceProtectedStorageContext()
            val userManager = appCtx.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            val isUnlocked = userManager == null || userManager.isUserUnlocked
            if (isUnlocked) {
                try {
                    dpCtx.moveSharedPreferencesFrom(appCtx, PREFS_NAME)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "moveSharedPreferencesFrom failed: ${e.message}")
                }
            }
            dpCtx
        } else {
            appCtx
        }
        prefs = targetCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        AppLogger.d(TAG, "Initialized with ${PREFS_NAME}, all keys=${prefs?.all?.keys}")
    }

    private fun get(): SharedPreferences = prefs
        ?: throw IllegalStateException("PrefsManager not initialized. Call init(context) first.")

    // ----- Skin -----
    fun getSkinType(): SkinType {
        val name = get().getString(KEY_SKIN_TYPE, SkinType.DEFAULT.name) ?: SkinType.DEFAULT.name
        return try { SkinType.valueOf(name) } catch (_: Exception) { SkinType.DEFAULT }
    }

    fun setSkinType(type: SkinType) {
        AppLogger.d(TAG, "setSkinType: $type")
        get().edit().putString(KEY_SKIN_TYPE, type.name).apply()
    }

    /**
     * 将预设/皮肤默认的 5 项核心配置属性应用写入 PrefsManager
     * 涵盖：左右面板分割比例、是否开启歌词跑马灯、歌词文本排版、封面面板缩放、封面面板歌名/歌手字号
     */
    fun applySkinDefaultParams(type: SkinType) {
        val params = type.defaultParams
        setSplitRatio(params.splitRatio)
        setMarqueeEnabled(params.marqueeEnabled)
        setLyricsAlignment(params.lyricsAlignment)
        setDpiScale(params.displayScale)
        setInfoFontScale(params.infoFontScale)
    }

    // ----- Font Size -----
    fun getFontSizeSp(): Float {
        return get().getFloat(KEY_FONT_SIZE_SP, 88f)
    }

    fun setFontSizeSp(sp: Float) {
        AppLogger.d(TAG, "setFontSizeSp: $sp")
        get().edit().putFloat(KEY_FONT_SIZE_SP, sp).apply()
    }

    // ----- Horizontal Offset -----
    fun getHorizontalOffset(): Float {
        return get().getFloat(KEY_HORIZONTAL_OFFSET, 0f)
    }

    fun setHorizontalOffset(ratio: Float) {
        AppLogger.d(TAG, "setHorizontalOffset: $ratio")
        get().edit().putFloat(KEY_HORIZONTAL_OFFSET, ratio).apply()
    }

    // ----- Split Ratio -----
    fun getSplitRatio(): Float {
        val defaultVal = 0.25f
        return get().getFloat(KEY_SPLIT_RATIO, defaultVal)
    }

    fun setSplitRatio(ratio: Float) {
        AppLogger.d(TAG, "setSplitRatio: $ratio")
        get().edit().putFloat(KEY_SPLIT_RATIO, ratio).apply()
    }

    // ----- Lyrics Alignment (动感波浪居中，其余皮肤全量左对齐) -----
    fun getLyricsAlignment(): Int {
        val skin = getSkinType()
        return if (skin == com.carlyrics.skin.SkinType.VIBRANT_WAVE) {
            android.view.Gravity.CENTER
        } else {
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
    }

    fun setLyricsAlignment(alignment: Int) {
        AppLogger.d(TAG, "setLyricsAlignment: $alignment")
        get().edit().putInt(KEY_LYRICS_ALIGNMENT, alignment).apply()
    }

    // ----- Marquee Enabled (默认恒定开启) -----
    fun isMarqueeEnabled(): Boolean {
        return true
    }

    fun setMarqueeEnabled(enabled: Boolean) {
        AppLogger.d(TAG, "setMarqueeEnabled: $enabled")
        get().edit().putBoolean(KEY_MARQUEE_ENABLED, enabled).apply()
    }

    // ----- Overlay Size -----
    fun getOverlayWidth(defaultWidth: Int): Int {
        return get().getInt(KEY_OVERLAY_WIDTH, defaultWidth)
    }

    fun getOverlayHeight(defaultHeight: Int): Int {
        return get().getInt(KEY_OVERLAY_HEIGHT, defaultHeight)
    }

    fun setOverlaySize(width: Int, height: Int) {
        get().edit().putInt(KEY_OVERLAY_WIDTH, width).putInt(KEY_OVERLAY_HEIGHT, height).apply()
    }

    // ----- Target Display -----
    fun getTargetDisplayId(): Int {
        return get().getInt(KEY_TARGET_DISPLAY_ID, 0)
    }

    fun setTargetDisplayId(id: Int) {
        get().edit().putInt(KEY_TARGET_DISPLAY_ID, id).apply()
    }

    // ----- API URLs & Key -----
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TRANSPARENT_WHEN_IDLE = "transparent_when_idle"

    fun getLyricsApiUrl(): String {
        return get().getString(KEY_LYRICS_API_URL, "") ?: ""
    }

    fun setLyricsApiUrl(url: String) {
        get().edit().putString(KEY_LYRICS_API_URL, url).apply()
    }

    fun getCoverApiUrl(): String {
        return get().getString(KEY_COVER_API_URL, "") ?: ""
    }

    fun setCoverApiUrl(url: String) {
        get().edit().putString(KEY_COVER_API_URL, url).apply()
    }

    fun getApiKey(): String {
        return get().getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        get().edit().putString(KEY_API_KEY, key).apply()
    }

    fun isTransparentWhenIdleEnabled(): Boolean {
        return get().getBoolean(KEY_TRANSPARENT_WHEN_IDLE, false)
    }

    fun setTransparentWhenIdleEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_TRANSPARENT_WHEN_IDLE, enabled).apply()
    }

    // ----- DPI Scale (封面缩放默认 85%) -----
    fun getDpiScale(): Float {
        val defaultVal = 0.85f
        return get().getFloat(KEY_DISPLAY_SCALE, defaultVal)
    }

    fun setDpiScale(scale: Float) {
        AppLogger.d(TAG, "setDpiScale: $scale")
        get().edit().putFloat(KEY_DISPLAY_SCALE, scale).apply()
    }

    // ----- Cache -----
    fun getCacheMaxSizeMb(): Int {
        return get().getInt(KEY_CACHE_MAX_SIZE_MB, 64)
    }

    fun setCacheMaxSizeMb(mb: Int) {
        get().edit().putInt(KEY_CACHE_MAX_SIZE_MB, mb).apply()
    }

    // ----- Info Font Scale (歌名字号默认 27px，基准为22px) -----
    fun getInfoFontScale(): Float {
        return get().getFloat(KEY_INFO_FONT_SCALE, 27f / 22f)
    }

    fun setInfoFontScale(scale: Float) {
        get().edit().putFloat(KEY_INFO_FONT_SCALE, scale).apply()
    }

    // ----- Overlay Visible -----
    fun getOverlayVisible(): Boolean {
        return get().getBoolean(KEY_OVERLAY_VISIBLE, false)
    }

    fun setOverlayVisible(visible: Boolean) {
        get().edit().putBoolean(KEY_OVERLAY_VISIBLE, visible).apply()
    }

    // ----- Lyrics Time Offset -----
    var lyricsOffsetMs: Long
        get() = get().getLong(KEY_LYRICS_OFFSET_MS, 0L)
        set(value) {
            get().edit().putLong(KEY_LYRICS_OFFSET_MS, value).apply()
        }

    // ----- Auto Start on Boot -----
    fun isAutoStartBoot(): Boolean {
        return get().getBoolean(KEY_AUTO_START_BOOT, true)
    }

    fun setAutoStartBoot(enabled: Boolean) {
        get().edit().putBoolean(KEY_AUTO_START_BOOT, enabled).apply()
    }

    // ----- Cluster Display Area Adjustment -----
    fun getDisplayAreaTop(defaultVal: Int = 0): Int = get().getInt(KEY_DISPLAY_AREA_TOP, defaultVal)
    fun getDisplayAreaLeft(defaultVal: Int = 0): Int = get().getInt(KEY_DISPLAY_AREA_LEFT, defaultVal)
    fun getDisplayAreaRight(defaultVal: Int = 0): Int = get().getInt(KEY_DISPLAY_AREA_RIGHT, defaultVal)
    fun getDisplayAreaBottom(defaultVal: Int = 0): Int = get().getInt(KEY_DISPLAY_AREA_BOTTOM, defaultVal)

    fun setDisplayArea(left: Int, top: Int, right: Int, bottom: Int) {
        AppLogger.d(TAG, "setDisplayArea: left=$left, top=$top, right=$right, bottom=$bottom")
        get().edit()
            .putInt(KEY_DISPLAY_AREA_LEFT, left)
            .putInt(KEY_DISPLAY_AREA_TOP, top)
            .putInt(KEY_DISPLAY_AREA_RIGHT, right)
            .putInt(KEY_DISPLAY_AREA_BOTTOM, bottom)
            .apply()
    }

    // ----- 日志记录开关 (默认关闭 false) -----
    fun isLogEnabled(): Boolean = get().getBoolean(KEY_LOG_ENABLED, false)
    fun setLogEnabled(enabled: Boolean) {
        AppLogger.setDiskLogEnabledCache(enabled)
        get().edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
    }

    // ----- 偏好源 (auto/qq/netease/kugou/kuwo) -----
    fun getPreferredLyricsSource(): String = get().getString(KEY_PREFERRED_LYRICS_SOURCE, "auto") ?: "auto"
    fun setPreferredLyricsSource(source: String) {
        get().edit().putString(KEY_PREFERRED_LYRICS_SOURCE, source).apply()
    }

    // ----- 独立车载歌词总开关 (开启后播放音乐全屏显示，暂停或未播放 5s 平滑回退时间天气) -----
    fun isStandaloneLyricsEnabled(): Boolean = get().getBoolean(KEY_STANDALONE_LYRICS_ENABLED, false)
    fun setStandaloneLyricsEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_STANDALONE_LYRICS_ENABLED, enabled).apply()
    }

    // ----- 小欧3D形象避让/显示开关 (开启=小欧下方Mode.FITNESS且避让右侧，关闭=小欧上方Mode.TOP覆盖全屏，默认 true) -----
    fun isXiaoOuAvoidanceEnabled(): Boolean = get().getBoolean(KEY_XIAOOU_AVOIDANCE_ENABLED, true)
    fun setXiaoOuAvoidanceEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_XIAOOU_AVOIDANCE_ENABLED, enabled).apply()
    }
    fun isXiaoOuAvatarEnabled(): Boolean = isXiaoOuAvoidanceEnabled()
    fun setXiaoOuAvatarEnabled(enabled: Boolean) = setXiaoOuAvoidanceEnabled(enabled)

    // ----- 中央大时间液态玻璃质感总开关 (28px模糊/170%饱和度/10%白底叠层/1px边缘厚度与折射) -----
    fun isLiquidGlassClockEnabled(): Boolean = get().getBoolean(KEY_LIQUID_GLASS_CLOCK_ENABLED, false)
    fun setLiquidGlassClockEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_LIQUID_GLASS_CLOCK_ENABLED, enabled).apply()
    }

    // ----- 左下角常驻档位液态玻璃 HUD 开关 (置顶显示 R·N·D·P 独立微型玻璃指示器，默认开启) -----
    fun isBottomLeftGearHudEnabled(): Boolean = get().getBoolean(KEY_BOTTOM_LEFT_GEAR_HUD_ENABLED, true)
    fun setBottomLeftGearHudEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_BOTTOM_LEFT_GEAR_HUD_ENABLED, enabled).apply()
    }

    // ----- 微型档位悬浮停靠位置 (0=A区下默认, 1=A区上, 2=屏底中心, 3=C区下, 4=C区上) -----
    fun getMiniGearPosition(): Int = get().getInt(KEY_MINI_GEAR_POSITION, 0)
    fun setMiniGearPosition(position: Int) {
        get().edit().putInt(KEY_MINI_GEAR_POSITION, position).apply()
    }

    // ----- 实验室特性：微型档位实时背景自适应计算 (根据所在位置背景动态计算高对比度与反色/环境光融入) -----
    fun isMiniGearRealtimeColorEnabled(): Boolean = get().getBoolean(KEY_MINI_GEAR_REALTIME_COLOR_ENABLED, false)
    fun setMiniGearRealtimeColorEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_MINI_GEAR_REALTIME_COLOR_ENABLED, enabled).apply()
    }

    // ----- 未匹配到歌词显示默认时钟画面 (默认开启 true) -----
    private const val KEY_SHOW_DEFAULT_CLOCK_WHEN_NO_LYRICS = "show_default_clock_when_no_lyrics"
    fun isShowDefaultClockWhenNoLyrics(): Boolean = get().getBoolean(KEY_SHOW_DEFAULT_CLOCK_WHEN_NO_LYRICS, true)
    fun setShowDefaultClockWhenNoLyrics(enabled: Boolean) {
        get().edit().putBoolean(KEY_SHOW_DEFAULT_CLOCK_WHEN_NO_LYRICS, enabled).apply()
    }

    // ----- 最新实况天气场景持久化缓存 (冷启动首帧直接加载，杜绝默认晴朗20°跳变) -----
    private const val KEY_LAST_WEATHER_SCENE_JSON = "key_last_weather_scene_json"
    fun getLastWeatherSceneJson(): String? = get().getString(KEY_LAST_WEATHER_SCENE_JSON, null)
    fun setLastWeatherSceneJson(json: String) {
        get().edit().putString(KEY_LAST_WEATHER_SCENE_JSON, json).apply()
    }

    // ----- 优先使用实车车外温度传感器开关 (0x11600703，与仪表盘同步，默认开启 true) -----
    private const val KEY_USE_CAR_OUTSIDE_TEMP = "use_car_outside_temp"
    fun isUseCarOutsideTempEnabled(): Boolean = get().getBoolean(KEY_USE_CAR_OUTSIDE_TEMP, true)
    fun setUseCarOutsideTempEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_USE_CAR_OUTSIDE_TEMP, enabled).apply()
    }

    // ----- 小欧屏 A / C 区域可替换模块配置 (A 默认天气信息，C 关闭小欧形象后默认歌曲封面) -----
    private const val KEY_XIAOOU_SLOT_A = "xiaoou_slot_a"
    private const val KEY_XIAOOU_SLOT_C = "xiaoou_slot_c"

    fun getXiaoOuSlotA(): String {
        val v = get().getString(KEY_XIAOOU_SLOT_A, "weather") ?: "weather"
        return if (v.equals("temp_compare", ignoreCase = true)) "weather" else v
    }
    fun setXiaoOuSlotA(slotId: String) {
        AppLogger.d(TAG, "setXiaoOuSlotA: $slotId")
        get().edit().putString(KEY_XIAOOU_SLOT_A, slotId).apply()
    }

    fun getXiaoOuSlotC(): String {
        val v = get().getString(KEY_XIAOOU_SLOT_C, "album_cover") ?: "album_cover"
        return if (v.equals("temp_compare", ignoreCase = true)) "album_cover" else v
    }
    fun setXiaoOuSlotC(slotId: String) {
        AppLogger.d(TAG, "setXiaoOuSlotC: $slotId")
        get().edit().putString(KEY_XIAOOU_SLOT_C, slotId).apply()
    }

    // ----- 智能场景联动 · 氛围灯音乐同步配置 -----
    private const val KEY_AMBIENT_LIGHT_SYNC_ENABLED = "ambient_light_sync_enabled"
    private const val KEY_AMBIENT_LIGHT_COLOR_SYNC = "ambient_light_color_sync"
    private const val KEY_AMBIENT_LIGHT_RANDOM_COLOR = "ambient_light_random_color"
    private const val KEY_AMBIENT_LIGHT_SENSITIVITY = "ambient_light_sensitivity"

    fun isAmbientLightSyncEnabled(): Boolean = get().getBoolean(KEY_AMBIENT_LIGHT_SYNC_ENABLED, false)
    fun setAmbientLightSyncEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_AMBIENT_LIGHT_SYNC_ENABLED, enabled).apply()
    }

    fun isAmbientLightColorSyncEnabled(): Boolean = get().getBoolean(KEY_AMBIENT_LIGHT_COLOR_SYNC, true)
    fun setAmbientLightColorSyncEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_AMBIENT_LIGHT_COLOR_SYNC, enabled).apply()
    }

    fun isAmbientLightRandomColorEnabled(): Boolean = get().getBoolean(KEY_AMBIENT_LIGHT_RANDOM_COLOR, false)
    fun setAmbientLightRandomColorEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_AMBIENT_LIGHT_RANDOM_COLOR, enabled).apply()
    }

    fun getAmbientLightSensitivity(): Int = get().getInt(KEY_AMBIENT_LIGHT_SENSITIVITY, 100)
    fun setAmbientLightSensitivity(sensitivity: Int) {
        get().edit().putInt(KEY_AMBIENT_LIGHT_SENSITIVITY, sensitivity).apply()
    }

    // ----- 智能场景联动 · 氛围灯基准亮度模式与自定义亮度 -----
    private const val KEY_AMBIENT_LIGHT_FOLLOW_CAR_BRIGHTNESS = "ambient_light_follow_car_brightness"
    private const val KEY_AMBIENT_LIGHT_CUSTOM_BRIGHTNESS = "ambient_light_custom_brightness"

    fun isAmbientLightFollowCarBrightness(): Boolean = get().getBoolean(KEY_AMBIENT_LIGHT_FOLLOW_CAR_BRIGHTNESS, true)
    fun setAmbientLightFollowCarBrightness(enabled: Boolean) {
        get().edit().putBoolean(KEY_AMBIENT_LIGHT_FOLLOW_CAR_BRIGHTNESS, enabled).apply()
    }

    fun getAmbientLightCustomBrightness(): Int = get().getInt(KEY_AMBIENT_LIGHT_CUSTOM_BRIGHTNESS, 80)
    fun setAmbientLightCustomBrightness(brightnessPercent: Int) {
        get().edit().putInt(KEY_AMBIENT_LIGHT_CUSTOM_BRIGHTNESS, brightnessPercent.coerceIn(10, 100)).apply()
    }

    // ----- 智能场景联动 · 转向灯同步与变道辅助光效配置 -----
    private const val KEY_TURN_SIGNAL_SYNC_ENABLED = "turn_signal_sync_enabled"
    private const val KEY_LANE_CHANGE_ASSIST_ENABLED = "lane_change_assist_enabled"

    fun isTurnSignalSyncEnabled(): Boolean = get().getBoolean(KEY_TURN_SIGNAL_SYNC_ENABLED, true)
    fun setTurnSignalSyncEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_TURN_SIGNAL_SYNC_ENABLED, enabled).apply()
    }

    fun isLaneChangeAssistEnabled(): Boolean = get().getBoolean(KEY_LANE_CHANGE_ASSIST_ENABLED, true)
    fun setLaneChangeAssistEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_LANE_CHANGE_ASSIST_ENABLED, enabled).apply()
    }
}

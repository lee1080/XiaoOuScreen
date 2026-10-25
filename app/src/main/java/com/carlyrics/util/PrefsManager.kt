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

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val targetCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val dpCtx = appCtx.createDeviceProtectedStorageContext()
                dpCtx.moveSharedPreferencesFrom(appCtx, PREFS_NAME)
                dpCtx
            } catch (e: Exception) {
                appCtx
            }
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
        val result = try { SkinType.valueOf(name) } catch (_: Exception) { SkinType.DEFAULT }
        AppLogger.d(TAG, "getSkinType: $result (raw=$name)")
        return result
    }

    fun setSkinType(type: SkinType) {
        AppLogger.d(TAG, "setSkinType: $type")
        get().edit().putString(KEY_SKIN_TYPE, type.name).commit()
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
        val value = get().getFloat(KEY_FONT_SIZE_SP, 38f)
        AppLogger.d(TAG, "getFontSizeSp: $value")
        return value
    }

    fun setFontSizeSp(sp: Float) {
        AppLogger.d(TAG, "setFontSizeSp: $sp")
        get().edit().putFloat(KEY_FONT_SIZE_SP, sp).commit()
    }

    // ----- Horizontal Offset -----
    fun getHorizontalOffset(): Float {
        val value = get().getFloat(KEY_HORIZONTAL_OFFSET, 0f)
        AppLogger.d(TAG, "getHorizontalOffset: $value")
        return value
    }

    fun setHorizontalOffset(ratio: Float) {
        AppLogger.d(TAG, "setHorizontalOffset: $ratio")
        get().edit().putFloat(KEY_HORIZONTAL_OFFSET, ratio).commit()
    }

    // ----- Split Ratio -----
    fun getSplitRatio(): Float {
        val defaultVal = getSkinType().defaultParams.splitRatio
        val value = get().getFloat(KEY_SPLIT_RATIO, defaultVal)
        AppLogger.d(TAG, "getSplitRatio: $value")
        return value
    }

    fun setSplitRatio(ratio: Float) {
        AppLogger.d(TAG, "setSplitRatio: $ratio")
        get().edit().putFloat(KEY_SPLIT_RATIO, ratio).commit()
    }

    // ----- Lyrics Alignment -----
    fun getLyricsAlignment(): Int {
        val defaultVal = getSkinType().defaultParams.lyricsAlignment
        val value = get().getInt(KEY_LYRICS_ALIGNMENT, defaultVal)
        AppLogger.d(TAG, "getLyricsAlignment: $value")
        return value
    }

    fun setLyricsAlignment(alignment: Int) {
        AppLogger.d(TAG, "setLyricsAlignment: $alignment")
        get().edit().putInt(KEY_LYRICS_ALIGNMENT, alignment).commit()
    }

    // ----- Marquee Enabled -----
    fun isMarqueeEnabled(): Boolean {
        val defaultVal = getSkinType().defaultParams.marqueeEnabled
        val value = get().getBoolean(KEY_MARQUEE_ENABLED, defaultVal)
        AppLogger.d(TAG, "isMarqueeEnabled: $value")
        return value
    }

    fun setMarqueeEnabled(enabled: Boolean) {
        AppLogger.d(TAG, "setMarqueeEnabled: $enabled")
        get().edit().putBoolean(KEY_MARQUEE_ENABLED, enabled).commit()
    }

    // ----- Overlay Size -----
    fun getOverlayWidth(defaultWidth: Int): Int {
        return get().getInt(KEY_OVERLAY_WIDTH, defaultWidth)
    }

    fun getOverlayHeight(defaultHeight: Int): Int {
        return get().getInt(KEY_OVERLAY_HEIGHT, defaultHeight)
    }

    fun setOverlaySize(width: Int, height: Int) {
        get().edit().putInt(KEY_OVERLAY_WIDTH, width).putInt(KEY_OVERLAY_HEIGHT, height).commit()
    }

    // ----- Target Display -----
    fun getTargetDisplayId(): Int {
        return get().getInt(KEY_TARGET_DISPLAY_ID, 0)
    }

    fun setTargetDisplayId(id: Int) {
        get().edit().putInt(KEY_TARGET_DISPLAY_ID, id).commit()
    }

    // ----- API URLs & Key -----
    private const val KEY_API_KEY = "api_key"
    private const val KEY_TRANSPARENT_WHEN_IDLE = "transparent_when_idle"

    fun getLyricsApiUrl(): String {
        return get().getString(KEY_LYRICS_API_URL, "") ?: ""
    }

    fun setLyricsApiUrl(url: String) {
        get().edit().putString(KEY_LYRICS_API_URL, url).commit()
    }

    fun getCoverApiUrl(): String {
        return get().getString(KEY_COVER_API_URL, "") ?: ""
    }

    fun setCoverApiUrl(url: String) {
        get().edit().putString(KEY_COVER_API_URL, url).commit()
    }

    fun getApiKey(): String {
        return get().getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        get().edit().putString(KEY_API_KEY, key).commit()
    }

    fun isTransparentWhenIdleEnabled(): Boolean {
        return get().getBoolean(KEY_TRANSPARENT_WHEN_IDLE, false)
    }

    fun setTransparentWhenIdleEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_TRANSPARENT_WHEN_IDLE, enabled).commit()
    }

    // ----- DPI Scale -----
    fun getDpiScale(): Float {
        val defaultVal = getSkinType().defaultParams.displayScale
        val value = get().getFloat(KEY_DISPLAY_SCALE, defaultVal)
        AppLogger.d(TAG, "getDpiScale: $value")
        return value
    }

    fun setDpiScale(scale: Float) {
        AppLogger.d(TAG, "setDpiScale: $scale")
        get().edit().putFloat(KEY_DISPLAY_SCALE, scale).commit()
    }

    // ----- Cache -----
    fun getCacheMaxSizeMb(): Int {
        return get().getInt(KEY_CACHE_MAX_SIZE_MB, 10)
    }

    fun setCacheMaxSizeMb(mb: Int) {
        get().edit().putInt(KEY_CACHE_MAX_SIZE_MB, mb).commit()
    }

    // ----- Info Font Scale -----
    fun getInfoFontScale(): Float {
        return get().getFloat(KEY_INFO_FONT_SCALE, 1.0f)
    }

    fun setInfoFontScale(scale: Float) {
        get().edit().putFloat(KEY_INFO_FONT_SCALE, scale).commit()
    }

    // ----- Overlay Visible -----
    fun getOverlayVisible(): Boolean {
        return get().getBoolean(KEY_OVERLAY_VISIBLE, false)
    }

    fun setOverlayVisible(visible: Boolean) {
        get().edit().putBoolean(KEY_OVERLAY_VISIBLE, visible).commit()
    }

    // ----- Lyrics Time Offset -----
    var lyricsOffsetMs: Long
        get() = get().getLong(KEY_LYRICS_OFFSET_MS, 0L)
        set(value) {
            get().edit().putLong(KEY_LYRICS_OFFSET_MS, value).commit()
        }

    // ----- Auto Start on Boot -----
    fun isAutoStartBoot(): Boolean {
        return get().getBoolean(KEY_AUTO_START_BOOT, false)
    }

    fun setAutoStartBoot(enabled: Boolean) {
        get().edit().putBoolean(KEY_AUTO_START_BOOT, enabled).commit()
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
            .commit()
    }

    // ----- 日志记录开关 (默认关闭 false) -----
    fun isLogEnabled(): Boolean = get().getBoolean(KEY_LOG_ENABLED, false)
    fun setLogEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_LOG_ENABLED, enabled).apply()
    }

    // ----- 偏好源 (auto/qq/netease/kugou/kuwo) -----
    fun getPreferredLyricsSource(): String = get().getString(KEY_PREFERRED_LYRICS_SOURCE, "auto") ?: "auto"
    fun setPreferredLyricsSource(source: String) {
        get().edit().putString(KEY_PREFERRED_LYRICS_SOURCE, source).commit()
    }

    // ----- 独立车载歌词总开关 (开启后播放音乐全屏显示，暂停或未播放 5s 平滑回退时间天气) -----
    fun isStandaloneLyricsEnabled(): Boolean = get().getBoolean(KEY_STANDALONE_LYRICS_ENABLED, true)
    fun setStandaloneLyricsEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_STANDALONE_LYRICS_ENABLED, enabled).apply()
    }

    // ----- 小欧3D形象避让开关 (投屏在小欧下方时避让右侧500px，投屏在小欧上方时不避让或根据配置自适应，默认 true) -----
    fun isXiaoOuAvoidanceEnabled(): Boolean = get().getBoolean(KEY_XIAOOU_AVOIDANCE_ENABLED, true)
    fun setXiaoOuAvoidanceEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_XIAOOU_AVOIDANCE_ENABLED, enabled).commit()
    }

    // ----- 中央大时间液态玻璃质感总开关 (28px模糊/170%饱和度/10%白底叠层/1px边缘厚度与折射) -----
    fun isLiquidGlassClockEnabled(): Boolean = get().getBoolean(KEY_LIQUID_GLASS_CLOCK_ENABLED, false)
    fun setLiquidGlassClockEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_LIQUID_GLASS_CLOCK_ENABLED, enabled).commit()
    }

    // ----- 左下角常驻档位液态玻璃 HUD 开关 (置顶显示 R·N·D·P 独立微型玻璃指示器，默认开启) -----
    fun isBottomLeftGearHudEnabled(): Boolean = get().getBoolean(KEY_BOTTOM_LEFT_GEAR_HUD_ENABLED, true)
    fun setBottomLeftGearHudEnabled(enabled: Boolean) {
        get().edit().putBoolean(KEY_BOTTOM_LEFT_GEAR_HUD_ENABLED, enabled).commit()
    }
}

package com.carlyrics.util

import android.content.Context
import android.content.SharedPreferences
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.skin.SkinType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 预设管理器：负责保存、加载、导出与导入所有应用设置的 JSON 配置
 */
object PresetManager {
    private const val TAG = "PresetManager"
    private const val PREFS_NAME = "car_lyrics_presets"
    private const val KEY_PRESETS = "presets_json_array"
    private const val KEY_CURRENT_PRESET = "current_preset_name"
    private const val DEFAULT_PRESET_NAME = "默认预设"
    private const val Z6_PRESET_NAME = "Z6小欧屏适配"

    private val FACTORY_PRESETS_JSON = """
    [
      {
        "name": "默认预设",
        "version": 1,
        "timestamp": 1784969121786,
        "settings": {
          "skin_type": "DEFAULT",
          "font_size_sp": 38,
          "horizontal_offset": 0,
          "split_ratio": 0.35,
          "lyrics_alignment": 8388611,
          "marquee_enabled": true,
          "overlay_width": 1920,
          "overlay_height": 384,
          "target_display_id": 0,
          "display_scale": 1.0,
          "info_font_scale": 1.0,
          "lyrics_offset_ms": 0,
          "overlay_visible": true,
          "auto_start_boot": false,
          "transparent_when_idle": false,
          "lyrics_api_url": "",
          "cover_api_url": "",
          "api_key": "",
          "cache_max_size_mb": 10,
          "display_area_top": 0,
          "display_area_left": 0,
          "display_area_right": 1920,
          "display_area_bottom": 384
        }
      },
      {
        "name": "Z6小欧屏适配",
        "version": 1,
        "timestamp": 1784969121786,
        "settings": {
          "skin_type": "DEFAULT",
          "font_size_sp": 38,
          "horizontal_offset": 0,
          "split_ratio": 0.35,
          "lyrics_alignment": 8388611,
          "marquee_enabled": true,
          "overlay_width": 1920,
          "overlay_height": 384,
          "target_display_id": 2,
          "display_scale": 1.55,
          "info_font_scale": 1.0,
          "lyrics_offset_ms": 0,
          "overlay_visible": true,
          "auto_start_boot": true,
          "transparent_when_idle": true,
          "lyrics_api_url": "",
          "cover_api_url": "",
          "api_key": "",
          "cache_max_size_mb": 10,
          "display_area_top": 0,
          "display_area_left": 131,
          "display_area_right": 1792,
          "display_area_bottom": 384
        }
      }
    ]
    """.trimIndent()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val names = getPresetNames()
        // 若预设列表为空，或者未包含完整出厂预设，自动加载出厂预设 [默认预设, Z6小欧屏适配]
        if (names.isEmpty() || (!names.contains(DEFAULT_PRESET_NAME) || !names.contains(Z6_PRESET_NAME))) {
            try {
                val factoryArray = JSONArray(FACTORY_PRESETS_JSON)
                get().edit()
                    .putString(KEY_PRESETS, factoryArray.toString())
                    .apply()
                if (getCurrentPresetName().isBlank()) {
                    setCurrentPresetName(DEFAULT_PRESET_NAME)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "init factory presets error: ${e.message}")
            }
        }
    }

    private fun get(): SharedPreferences = prefs
        ?: throw IllegalStateException("PresetManager not initialized. Call init(context) first.")

    /** 获取默认预设名称 */
    fun getFactoryPresetName(): String = DEFAULT_PRESET_NAME

    /** 获取所有已保存预设的名称列表 */
    fun getPresetNames(): List<String> {
        val jsonStr = get().getString(KEY_PRESETS, "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name")
                if (name.isNotBlank()) list.add(name)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getPresetNames error: ${e.message}")
        }
        return list
    }

    /** 获取当前选择的预设名称 */
    fun getCurrentPresetName(): String {
        val saved = get().getString(KEY_CURRENT_PRESET, DEFAULT_PRESET_NAME) ?: DEFAULT_PRESET_NAME
        val names = getPresetNames()
        return if (names.contains(saved)) saved else names.firstOrNull() ?: DEFAULT_PRESET_NAME
    }

    /** 设置当前选中的预设名称 */
    fun setCurrentPresetName(name: String) {
        get().edit().putString(KEY_CURRENT_PRESET, name).apply()
    }

    /**
     * 将当前应用的所有设置抓取并打包为 JSON 字符串
     */
    fun exportCurrentSettingsAsJson(presetName: String): String {
        val root = JSONObject()
        root.put("name", presetName)
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val settings = JSONObject().apply {
            put("skin_type", PrefsManager.getSkinType().name)
            put("font_size_sp", PrefsManager.getFontSizeSp().toDouble())
            put("horizontal_offset", PrefsManager.getHorizontalOffset().toDouble())
            put("split_ratio", PrefsManager.getSplitRatio().toDouble())
            put("lyrics_alignment", PrefsManager.getLyricsAlignment())
            put("marquee_enabled", PrefsManager.isMarqueeEnabled())
            put("overlay_width", PrefsManager.getOverlayWidth(1920))
            put("overlay_height", PrefsManager.getOverlayHeight(480))
            put("target_display_id", PrefsManager.getTargetDisplayId())
            put("display_scale", PrefsManager.getDpiScale().toDouble())
            put("info_font_scale", PrefsManager.getInfoFontScale().toDouble())
            put("lyrics_offset_ms", PrefsManager.lyricsOffsetMs)
            put("overlay_visible", PrefsManager.getOverlayVisible())
            put("auto_start_boot", PrefsManager.isAutoStartBoot())
            put("transparent_when_idle", PrefsManager.isTransparentWhenIdleEnabled())
            put("lyrics_api_url", PrefsManager.getLyricsApiUrl())
            put("cover_api_url", PrefsManager.getCoverApiUrl())
            put("api_key", PrefsManager.getApiKey())
            put("cache_max_size_mb", PrefsManager.getCacheMaxSizeMb())
            put("preferred_lyrics_source", PrefsManager.getPreferredLyricsSource())
            put("display_area_top", PrefsManager.getDisplayAreaTop())
            put("display_area_left", PrefsManager.getDisplayAreaLeft())
            put("display_area_right", PrefsManager.getDisplayAreaRight())
            put("display_area_bottom", PrefsManager.getDisplayAreaBottom())
        }

        root.put("settings", settings)
        return root.toString(2)
    }

    /**
     * 保存/更新指定名称的预设
     */
    fun savePreset(presetName: String) {
        val jsonStr = get().getString(KEY_PRESETS, "[]") ?: "[]"
        val array = try { JSONArray(jsonStr) } catch (_: Exception) { JSONArray() }

        val currentJson = JSONObject(exportCurrentSettingsAsJson(presetName))
        val newArray = JSONArray()
        var updated = false

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("name") == presetName) {
                newArray.put(currentJson)
                updated = true
            } else {
                newArray.put(item)
            }
        }
        if (!updated) {
            newArray.put(currentJson)
        }

        get().edit().putString(KEY_PRESETS, newArray.toString()).apply()
        setCurrentPresetName(presetName)
    }

    /**
     * 删除指定名称的预设
     */
    fun deletePreset(presetName: String): Boolean {
        val jsonStr = get().getString(KEY_PRESETS, "[]") ?: "[]"
        val array = try { JSONArray(jsonStr) } catch (_: Exception) { JSONArray() }
        val newArray = JSONArray()
        var deleted = false

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("name") == presetName) {
                deleted = true
            } else {
                newArray.put(item)
            }
        }

        if (deleted) {
            get().edit().putString(KEY_PRESETS, newArray.toString()).apply()
            val remaining = getPresetNames()
            if (getCurrentPresetName() == presetName && remaining.isNotEmpty()) {
                setCurrentPresetName(remaining.first())
            }
        }
        return deleted
    }

    /**
     * 应用并加载预设 JSON 配置字符串回 PrefsManager
     */
    fun applyPresetJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val settings = root.optJSONObject("settings") ?: root
            val name = root.optString("name", "导入预设")

            if (settings.has("skin_type")) {
                val skinName = settings.optString("skin_type")
                val skinType = try { SkinType.valueOf(skinName) } catch (_: Exception) { SkinType.DEFAULT }
                PrefsManager.setSkinType(skinType)
            }
            if (settings.has("font_size_sp")) PrefsManager.setFontSizeSp(settings.optDouble("font_size_sp", 26.0).toFloat())
            if (settings.has("horizontal_offset")) PrefsManager.setHorizontalOffset(settings.optDouble("horizontal_offset", 0.0).toFloat())
            if (settings.has("split_ratio")) PrefsManager.setSplitRatio(settings.optDouble("split_ratio", 0.35).toFloat())
            if (settings.has("lyrics_alignment")) PrefsManager.setLyricsAlignment(settings.optInt("lyrics_alignment", 3))
            if (settings.has("marquee_enabled")) PrefsManager.setMarqueeEnabled(settings.optBoolean("marquee_enabled", true))

            val w = settings.optInt("overlay_width", 1920)
            val h = settings.optInt("overlay_height", 480)
            PrefsManager.setOverlaySize(w, h)

            if (settings.has("target_display_id")) PrefsManager.setTargetDisplayId(settings.optInt("target_display_id", 0))
            if (settings.has("display_scale")) PrefsManager.setDpiScale(settings.optDouble("display_scale", 1.0).toFloat())
            if (settings.has("info_font_scale")) PrefsManager.setInfoFontScale(settings.optDouble("info_font_scale", 1.0).toFloat())
            if (settings.has("lyrics_offset_ms")) PrefsManager.lyricsOffsetMs = settings.optLong("lyrics_offset_ms", 0L)
            if (settings.has("overlay_visible")) PrefsManager.setOverlayVisible(settings.optBoolean("overlay_visible", false))
            if (settings.has("auto_start_boot")) PrefsManager.setAutoStartBoot(settings.optBoolean("auto_start_boot", false))
            if (settings.has("transparent_when_idle")) PrefsManager.setTransparentWhenIdleEnabled(settings.optBoolean("transparent_when_idle", false))

            if (settings.has("lyrics_api_url")) {
                val u = settings.optString("lyrics_api_url", "")
                PrefsManager.setLyricsApiUrl(u)
                LyricsApiManager.LYRICS_API_URL = u
            }
            if (settings.has("cover_api_url")) {
                val u = settings.optString("cover_api_url", "")
                PrefsManager.setCoverApiUrl(u)
                LyricsApiManager.COVER_API_URL = u
            }
            if (settings.has("api_key")) {
                val k = settings.optString("api_key", "")
                PrefsManager.setApiKey(k)
                LyricsApiManager.API_KEY = k
            }
            if (settings.has("cache_max_size_mb")) PrefsManager.setCacheMaxSizeMb(settings.optInt("cache_max_size_mb", 10))
            if (settings.has("preferred_lyrics_source")) {
                PrefsManager.setPreferredLyricsSource(settings.optString("preferred_lyrics_source", "auto"))
            }

            val left = settings.optInt("display_area_left", 0)
            val top = settings.optInt("display_area_top", 0)
            val right = settings.optInt("display_area_right", 1920)
            val bottom = settings.optInt("display_area_bottom", 480)
            PrefsManager.setDisplayArea(left, top, right, bottom)

            // 将此导入的配置存入预设列表
            savePreset(name)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "applyPresetJson error: ${e.message}", e)
            false
        }
    }

    /**
     * 加载指定预设
     */
    fun loadPreset(presetName: String): Boolean {
        val jsonStr = get().getString(KEY_PRESETS, "[]") ?: "[]"
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("name") == presetName) {
                    return applyPresetJson(item.toString())
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadPreset error: ${e.message}")
        }
        return false
    }

    /** 获取指定预设的 JSON 导出的完整内容 */
    fun getPresetJsonContent(presetName: String): String? {
        val jsonStr = get().getString(KEY_PRESETS, "[]") ?: "[]"
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("name") == presetName) {
                    return item.toString(2)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

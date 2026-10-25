package com.car.xiaoouscreen.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 和风天气用户配置管理器
 * 遵循严格安全准则：API Key 与专属 API Host 不内置到代码中，由用户在车机端手动配置，存入私有 SharedPreferences
 */
class QWeatherConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ENABLED, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var apiHost: String
        get() {
            val h = prefs.getString(KEY_API_HOST, "") ?: ""
            if (h == "devapi.qweather.com") {
                prefs.edit().remove(KEY_API_HOST).apply()
                return ""
            }
            return h
        }
        set(value) {
            val cleaned = cleanHost(value)
            if (cleaned == "devapi.qweather.com" || cleaned.isEmpty()) {
                prefs.edit().remove(KEY_API_HOST).apply()
            } else {
                prefs.edit().putString(KEY_API_HOST, cleaned).apply()
            }
        }

    fun isConfigured(): Boolean = apiKey.isNotBlank() && apiHost.isNotBlank()

    fun saveConfig(key: String, host: String) {
        val cleanedHost = cleanHost(host)
        val editor = prefs.edit().putString(KEY_API_KEY, key.trim())
        if (cleanedHost == "devapi.qweather.com" || cleanedHost.isEmpty()) {
            editor.remove(KEY_API_HOST)
        } else {
            editor.putString(KEY_API_HOST, cleanedHost)
        }
        editor.apply()
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_HOST)
            .apply()
    }

    fun cleanHost(raw: String): String {
        var h = raw.trim()
        if (h.startsWith("https://")) h = h.substring(8)
        if (h.startsWith("http://")) h = h.substring(7)
        while (h.endsWith("/")) h = h.dropLast(1)
        return h.trim()
    }

    fun getBaseUrl(): String {
        val h = cleanHost(apiHost)
        return if (h.isNotEmpty()) "https://$h/" else "https://api.qweather.com/"
    }

    companion object {
        private const val PREFS_NAME = "qweather_user_config"
        private const val KEY_API_KEY = "key_qweather_api_key"
        private const val KEY_API_HOST = "key_qweather_api_host"
        private const val KEY_IS_ENABLED = "key_qweather_is_enabled"
    }
}

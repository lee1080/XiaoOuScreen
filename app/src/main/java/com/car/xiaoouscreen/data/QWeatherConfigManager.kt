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
            val h = prefs.getString(KEY_API_HOST, "devapi.qweather.com") ?: "devapi.qweather.com"
            return if (h.isBlank()) "devapi.qweather.com" else h
        }
        set(value) {
            val cleaned = cleanHost(value)
            prefs.edit().putString(KEY_API_HOST, cleaned.ifBlank { "devapi.qweather.com" }).apply()
        }

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_IS_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_CONNECTED, value).apply()

    var lastTestResult: String
        get() = prefs.getString(KEY_LAST_TEST_RESULT, "和风高精度天气配置就绪") ?: "和风高精度天气配置就绪"
        set(value) = prefs.edit().putString(KEY_LAST_TEST_RESULT, value).apply()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun saveConfig(key: String, host: String) {
        val cleanedHost = cleanHost(host).ifBlank { "devapi.qweather.com" }
        prefs.edit()
            .putString(KEY_API_KEY, key.trim())
            .putString(KEY_API_HOST, cleanedHost)
            .apply()
    }

    fun clearConfig() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_HOST)
            .remove(KEY_IS_CONNECTED)
            .remove(KEY_LAST_TEST_RESULT)
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
        val host = if (h.isNotEmpty()) h else "devapi.qweather.com"
        return "https://$host/"
    }

    companion object {
        private const val PREFS_NAME = "qweather_user_config"
        private const val KEY_API_KEY = "key_qweather_api_key"
        private const val KEY_API_HOST = "key_qweather_api_host"
        private const val KEY_IS_ENABLED = "key_qweather_is_enabled"
        private const val KEY_IS_CONNECTED = "key_qweather_is_connected"
        private const val KEY_LAST_TEST_RESULT = "key_qweather_last_test_result"
    }
}

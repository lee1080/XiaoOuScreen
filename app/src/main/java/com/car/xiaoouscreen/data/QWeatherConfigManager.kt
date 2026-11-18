package com.car.xiaoouscreen.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrivacyUtil
import org.json.JSONObject
import java.io.File

/**
 * 和风天气用户配置管理器（车规级多重本地持久化 + 旧版 CE->DE 无损迁移）
 *
 * 持久化策略（无需额外存储权限，覆盖升级 / 车机断电 / Direct Boot 全场景防丢失）：
 * 1. 主存储：DE 设备保护区 SharedPreferences（车机通电未解锁即可读取）
 * 2. 镜像存储：CE 常规区 SharedPreferences（兼容 v1.0.265 之前老版本无损自动迁移 + 双区容灾）
 * 3. 本地文件持久化：DE 内部 filesDir 与外部应用目录 Android/data/<pkg>/files/ 下的
 *    `qweather_config_persist.json`（原子写入，覆盖安装 100% 保留，且支持手动拷贝备份/恢复）
 * 4. 所有写操作强制使用 `.commit()` 同步落盘，彻底杜绝 `.apply()` 在更新强杀进程或车机熄火时丢数据。
 */
class QWeatherConfigManager(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val deContext: Context = com.carlyrics.util.PrefsManager.getSafeStorageContext(appContext)

    private val prefs: SharedPreferences =
        deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureRestoredFromLegacyOrLocalBackup()
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_ENABLED, value).commit()
            syncMirrorAndLocalFiles()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).commit()
            syncMirrorAndLocalFiles()
        }

    var apiHost: String
        get() {
            val h = prefs.getString(KEY_API_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
            return if (h.isBlank()) DEFAULT_HOST else h
        }
        set(value) {
            val cleaned = cleanHost(value)
            prefs.edit().putString(KEY_API_HOST, cleaned.ifBlank { DEFAULT_HOST }).commit()
            syncMirrorAndLocalFiles()
        }

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_IS_CONNECTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_CONNECTED, value).commit()
            syncMirrorAndLocalFiles()
        }

    var lastTestResult: String
        get() = prefs.getString(KEY_LAST_TEST_RESULT, DEFAULT_TEST_RESULT) ?: DEFAULT_TEST_RESULT
        set(value) {
            prefs.edit().putString(KEY_LAST_TEST_RESULT, value).commit()
            syncMirrorAndLocalFiles()
        }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun saveConfig(key: String, host: String) {
        val cleanedHost = cleanHost(host).ifBlank { DEFAULT_HOST }
        prefs.edit()
            .putString(KEY_API_KEY, key.trim())
            .putString(KEY_API_HOST, cleanedHost)
            .commit()
        syncMirrorAndLocalFiles()
    }

    /**
     * 仅在用户主动点击「清空凭据」时调用：同步清空主存储、CE 镜像及本地持久化备份文件，防止被自动恢复。
     */
    fun clearConfig() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_HOST)
            .remove(KEY_IS_CONNECTED)
            .remove(KEY_LAST_TEST_RESULT)
            .commit()

        getCePrefsOrNull()?.edit()
            ?.remove(KEY_API_KEY)
            ?.remove(KEY_API_HOST)
            ?.remove(KEY_IS_CONNECTED)
            ?.remove(KEY_LAST_TEST_RESULT)
            ?.commit()

        for (file in getBackupFiles()) {
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
        AppLogger.i(TAG, "用户主动清空了和风天气全部本地凭据与备份文件")
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
        val host = if (h.isNotEmpty()) h else DEFAULT_HOST
        return "https://$host/"
    }

    /**
     * 获取外部持久化配置文件路径（用于日志提示或手动备份）
     */
    fun getLocalBackupFilePath(): String? {
        return try {
            appContext.getExternalFilesDir(null)?.let { File(it, BACKUP_FILE_NAME).absolutePath }
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 自动迁移与本地持久化容灾引擎 ====================

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
            um == null || um.isUserUnlocked
        } else {
            true
        }
    }

    private fun getCePrefsOrNull(): SharedPreferences? {
        if (!isUserUnlocked()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun getBackupFiles(): List<File> {
        val list = mutableListOf<File>()
        try {
            list.add(File(deContext.filesDir, BACKUP_FILE_NAME))
        } catch (_: Exception) {}
        if (isUserUnlocked()) {
            try {
                val ceFile = File(appContext.filesDir, BACKUP_FILE_NAME)
                if (list.none { it.absolutePath == ceFile.absolutePath }) {
                    list.add(ceFile)
                }
            } catch (_: Exception) {}
            try {
                appContext.getExternalFilesDir(null)?.let { extDir ->
                    list.add(File(extDir, BACKUP_FILE_NAME))
                }
            } catch (_: Exception) {}
        }
        return list
    }

    private fun hasMeaningfulConfig(sp: SharedPreferences): Boolean {
        val k = sp.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val h = cleanHost(sp.getString(KEY_API_HOST, "") ?: "")
        return k.isNotBlank() || (h.isNotBlank() && !h.equals(DEFAULT_HOST, ignoreCase = true))
    }

    /**
     * 启动时自动排查与恢复：
     * - 若 DE 主存储为空（例如从 v1.0.265 之前老版本升级上来，或某次更新导致 SP 缺失），
     *   按优先级依次从：1) 旧 CE 区 SharedPreferences；2) 本地 JSON 持久文件 自动无损恢复。
     * - 若 DE 主存储已有有效配置但尚未落盘本地备份文件，自动补齐镜像与本地 JSON 文件。
     */
    private fun ensureRestoredFromLegacyOrLocalBackup() {
        synchronized(LOCK) {
            if (hasMeaningfulConfig(prefs)) {
                // 已有有效配置：确保本地 JSON 备份与 CE 镜像已存在（避免旧版已有配置未补写本地文件）
                if (!hasMigratedOrBackedUpInProcess) {
                    hasMigratedOrBackedUpInProcess = true
                    val anyMissing = getBackupFiles().any { !it.exists() }
                    val ceMissing = getCePrefsOrNull()?.let { !hasMeaningfulConfig(it) } ?: false
                    if (anyMissing || ceMissing) {
                        syncMirrorAndLocalFiles()
                    }
                }
                return
            }

            // 1. 优先尝试从旧版 CE 区 SharedPreferences 无损迁移
            val cePrefs = getCePrefsOrNull()
            if (cePrefs != null && hasMeaningfulConfig(cePrefs)) {
                val ceKey = cePrefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
                val ceHost = cleanHost(cePrefs.getString(KEY_API_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
                    .ifBlank { DEFAULT_HOST }
                val ceEnabled = cePrefs.getBoolean(KEY_IS_ENABLED, ceKey.isNotBlank())
                val ceConnected = cePrefs.getBoolean(KEY_IS_CONNECTED, false)
                val ceResult = cePrefs.getString(KEY_LAST_TEST_RESULT, DEFAULT_TEST_RESULT) ?: DEFAULT_TEST_RESULT

                prefs.edit()
                    .putString(KEY_API_KEY, ceKey)
                    .putString(KEY_API_HOST, ceHost)
                    .putBoolean(KEY_IS_ENABLED, ceEnabled)
                    .putBoolean(KEY_IS_CONNECTED, ceConnected)
                    .putString(KEY_LAST_TEST_RESULT, ceResult)
                    .commit()

                syncMirrorAndLocalFiles()
                hasMigratedOrBackedUpInProcess = true
                AppLogger.i(
                    TAG,
                    "已从旧版 CE 存储无损迁移和风天气配置 -> host=$ceHost, key=${PrivacyUtil.maskApiKey(ceKey)}, enabled=$ceEnabled"
                )
                return
            }

            // 2. 尝试从本地持久化 JSON 文件（DE 内部 / CE 内部 / 外部应用 files 目录）恢复
            for (file in getBackupFiles()) {
                if (!file.exists() || !file.isFile || file.length() <= 2L) continue
                try {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val fKey = json.optString(KEY_API_KEY, "").trim()
                    val fHost = cleanHost(json.optString(KEY_API_HOST, DEFAULT_HOST)).ifBlank { DEFAULT_HOST }
                    val hasValid = fKey.isNotBlank() || !fHost.equals(DEFAULT_HOST, ignoreCase = true)
                    if (!hasValid) continue

                    val fEnabled = json.optBoolean(KEY_IS_ENABLED, fKey.isNotBlank())
                    val fConnected = json.optBoolean(KEY_IS_CONNECTED, false)
                    val fResult = json.optString(KEY_LAST_TEST_RESULT, DEFAULT_TEST_RESULT)
                        .ifBlank { DEFAULT_TEST_RESULT }

                    prefs.edit()
                        .putString(KEY_API_KEY, fKey)
                        .putString(KEY_API_HOST, fHost)
                        .putBoolean(KEY_IS_ENABLED, fEnabled)
                        .putBoolean(KEY_IS_CONNECTED, fConnected)
                        .putString(KEY_LAST_TEST_RESULT, fResult)
                        .commit()

                    syncMirrorAndLocalFiles()
                    hasMigratedOrBackedUpInProcess = true
                    AppLogger.i(
                        TAG,
                        "已从本地持久文件恢复和风天气配置 (${file.absolutePath}) -> host=$fHost, key=${PrivacyUtil.maskApiKey(fKey)}, enabled=$fEnabled"
                    )
                    return
                } catch (e: Exception) {
                    AppLogger.w(TAG, "读取本地和风配置备份失败 (${file.name}): ${e.message}")
                }
            }
        }
    }

    /**
     * 将当前 DE 主配置同步镜像至 CE 区与本地 JSON 持久文件（原子覆盖写，防断电损坏）
     */
    private fun syncMirrorAndLocalFiles() {
        synchronized(LOCK) {
            val curKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
            val curHost = cleanHost(prefs.getString(KEY_API_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
                .ifBlank { DEFAULT_HOST }
            val curEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
            val curConnected = prefs.getBoolean(KEY_IS_CONNECTED, false)
            val curResult = prefs.getString(KEY_LAST_TEST_RESULT, DEFAULT_TEST_RESULT) ?: DEFAULT_TEST_RESULT

            val hasValid = curKey.isNotBlank() || !curHost.equals(DEFAULT_HOST, ignoreCase = true)

            // 1. 镜像同步至 CE 区 SharedPreferences（防空壳覆盖：若 DE 为空而 CE 尚存旧凭据，绝不覆写）
            try {
                val cePrefs = getCePrefsOrNull()
                if (cePrefs != null && (hasValid || !hasMeaningfulConfig(cePrefs))) {
                    cePrefs.edit()
                        .putString(KEY_API_KEY, curKey)
                        .putString(KEY_API_HOST, curHost)
                        .putBoolean(KEY_IS_ENABLED, curEnabled)
                        .putBoolean(KEY_IS_CONNECTED, curConnected)
                        .putString(KEY_LAST_TEST_RESULT, curResult)
                        .commit()
                }
            } catch (_: Exception) {}

            // 2. 原子写入本地 JSON 持久化文件（仅在有有效凭据或自定义域名时写入，避免空壳覆盖有效备份）
            if (!hasValid) return

            val payload = JSONObject().apply {
                put("version", 1)
                put("updatedAt", System.currentTimeMillis())
                put(KEY_API_KEY, curKey)
                put(KEY_API_HOST, curHost)
                put(KEY_IS_ENABLED, curEnabled)
                put(KEY_IS_CONNECTED, curConnected)
                put(KEY_LAST_TEST_RESULT, curResult)
            }.toString(2)

            for (file in getBackupFiles()) {
                try {
                    file.parentFile?.mkdirs()
                    val tmpFile = File(file.parentFile, "${file.name}.tmp")
                    tmpFile.writeText(payload, Charsets.UTF_8)
                    if (!tmpFile.renameTo(file)) {
                        file.writeText(payload, Charsets.UTF_8)
                        tmpFile.delete()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "写入本地和风配置备份失败 (${file.absolutePath}): ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "QWeatherConfig"
        private const val PREFS_NAME = "qweather_user_config"
        private const val BACKUP_FILE_NAME = "qweather_config_persist.json"
        const val DEFAULT_HOST = "devapi.qweather.com"
        private const val DEFAULT_TEST_RESULT = "和风高精度天气配置就绪"

        private const val KEY_API_KEY = "key_qweather_api_key"
        private const val KEY_API_HOST = "key_qweather_api_host"
        private const val KEY_IS_ENABLED = "key_qweather_is_enabled"
        private const val KEY_IS_CONNECTED = "key_qweather_is_connected"
        private const val KEY_LAST_TEST_RESULT = "key_qweather_last_test_result"

        private val LOCK = Any()
        @Volatile
        private var hasMigratedOrBackedUpInProcess = false
    }
}

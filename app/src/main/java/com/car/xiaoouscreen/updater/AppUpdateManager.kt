package com.car.xiaoouscreen.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.carlyrics.util.AppLogger
import androidx.annotation.Keep
import androidx.core.content.FileProvider
import com.car.xiaoouscreen.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 软件更新元数据
 */
@Keep
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("changelog") val changelog: String = "",
    @SerializedName("downloadUrl") val downloadUrl: String = "",
    @SerializedName("sha256") val sha256: String? = null,
    @SerializedName("fileSizeBytes") val fileSizeBytes: Long = 0L,
    @SerializedName("fileSizeMB") val fileSizeMB: String? = null,
    @SerializedName("updateTime") val updateTime: String? = null,
    @SerializedName("forceUpdate") val forceUpdate: Boolean = false
)

/**
 * 检查更新结果
 */
sealed class CheckResult {
    data class HasUpdate(val info: UpdateInfo) : CheckResult()
    data class AlreadyLatest(val currentVersion: String) : CheckResult()
    data class Error(val message: String) : CheckResult()
}

/**
 * 小欧屏 (XiaoOuScreen) 车载应用自动更新管理器
 * 接入 Gitee 自动更新源: https://gitee.com/lee1080/xiao-ou-screen-release/raw/master/version.json
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val UPDATE_JSON_URL = "https://gitee.com/lee1080/xiao-ou-screen-release/raw/master/version.json"
    }

    private val httpClient: OkHttpClient by lazy {
        com.carlyrics.util.SharedNetwork.baseOkHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val gson = com.carlyrics.util.SharedNetwork.gson

    /**
     * 1. 检查是否存在新版本
     */
    suspend fun checkUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            // 附加时间戳参数，强制穿透 Gitee Varnish/CDN 边缘节点缓存
            val cacheBustingUrl = "$UPDATE_JSON_URL?_t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(cacheBustingUrl)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("User-Agent", "XiaoOuScreen-AutoUpdater/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    Log.w(TAG, "检查更新 HTTP 响应异常: $code")
                    return@withContext CheckResult.Error("服务器响应异常 (HTTP $code)")
                }

                val jsonBody = response.body?.string()
                if (jsonBody.isNullOrBlank()) {
                    return@withContext CheckResult.Error("返回的版本信息为空")
                }

                val info = gson.fromJson(jsonBody, UpdateInfo::class.java)
                val currentCode = BuildConfig.VERSION_CODE
                Log.i(TAG, "远程版本: ${info.versionName} (${info.versionCode}), 本地版本: ${BuildConfig.VERSION_NAME} ($currentCode)")

                if (info.versionCode > currentCode) {
                    CheckResult.HasUpdate(info)
                } else {
                    CheckResult.AlreadyLatest("v${BuildConfig.VERSION_NAME}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "检查更新发生异常: ${e.message}", e)
            CheckResult.Error(e.message ?: "网络连接超时，请检查车载网络")
        }
    }

    /**
     * 2. 下载安装包并通知下载进度
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (progress: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val updateFolder = File(cacheDir, "apk_updates").apply { if (!exists()) mkdirs() }
        val apkFile = File(updateFolder, "XiaoOuScreen-v${info.versionName}.apk")

        try {
            // 如果本地已经存在且 SHA256 校验通过，直接返回无需重复下载
            if (apkFile.exists() && !info.sha256.isNullOrBlank()) {
                val existingHash = calculateSha256(apkFile)
                if (existingHash.equals(info.sha256, ignoreCase = true)) {
                    AppLogger.i(TAG, "本地已有完整有效安装包，直接使用")
                    onProgress(100)
                    return@withContext Result.success(apkFile)
                } else {
                    apkFile.delete()
                }
            }

            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "XiaoOuScreen-AutoUpdater/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("下载请求失败: HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("下载内容为空"))
                val contentLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(apkFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressReported = -1

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val currentProgress = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                if (currentProgress != lastProgressReported) {
                                    lastProgressReported = currentProgress
                                    onProgress(currentProgress)
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }
            }

            // SHA256 哈希完整性校验
            if (!info.sha256.isNullOrBlank()) {
                val computedHash = calculateSha256(apkFile)
                if (!computedHash.equals(info.sha256, ignoreCase = true)) {
                    AppLogger.e(TAG, "SHA256 校验失败! 预期: ${info.sha256}, 实际: $computedHash")
                    apkFile.delete()
                    return@withContext Result.failure(Exception("安装包校验失败 (哈希不匹配)，文件可能已损坏"))
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "下载安装包失败: ${e.message}", e)
            if (apkFile.exists()) apkFile.delete()
            Result.failure(e)
        }
    }

    /**
     * 3. 唤起系统安装器执行更新
     */
    fun installApk(file: File): Boolean {
        if (!file.exists()) {
            AppLogger.e(TAG, "安装失败: 安装包文件不存在")
            return false
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val apkUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                setDataAndType(apkUri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "唤起安装界面失败: ${e.message}", e)
            false
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var count: Int
            while (stream.read(buffer).also { count = it } != -1) {
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

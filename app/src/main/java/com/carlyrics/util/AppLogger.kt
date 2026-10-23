package com.carlyrics.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用运行日志工具。所有日志同时输出到 logcat 和本地文件。
 * 日志文件追加写入，单文件不超过 500KB，超过后自动轮转。
 */
object AppLogger {

    private var logFile: File? = null
    private var currentSize: Long = 0
    private const val MAX_FILE_SIZE = 500L * 1024L // 500KB
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "app_log.txt")
        logFile?.parentFile?.mkdirs()
        currentSize = logFile?.length() ?: 0
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        if (PrefsManager.isLogEnabled()) {
            writeToFile("D", tag, msg)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        val fullMsg = if (throwable != null) {
            "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            msg
        }
        writeToFile("E", tag, fullMsg)
    }

    @Synchronized
    private fun writeToFile(level: String, tag: String, msg: String) {
        try {
            val file = logFile ?: return
            val timestamp = dateFormat.format(Date())
            val line = "$timestamp $level/$tag: $msg\n"

            // 检查是否需要轮转
            if (currentSize + line.toByteArray().size > MAX_FILE_SIZE) {
                rotateLog()
            }

            file.appendText(line)
            currentSize += line.toByteArray().size
        } catch (_: Exception) {
            // 日志写入失败不应影响主流程
        }
    }

    private fun rotateLog() {
        val file = logFile ?: return
        // 删除旧的轮转文件
        val rotatedFile = File(file.parentFile, "app_log_1.txt")
        if (rotatedFile.exists()) {
            rotatedFile.delete()
        }
        // 重命名当前文件
        file.renameTo(rotatedFile)
        // 重新创建空文件
        file.createNewFile()
        currentSize = 0
    }

    /** 获取崩溃日志文件路径 */
    fun getCrashLogPath(): String? {
        val file = logFile ?: return null
        return File(file.parentFile, "crash_log.txt").absolutePath
    }

    /** 获取运行日志文件路径 */
    fun getAppLogPath(): String? {
        return logFile?.absolutePath
    }

    /** 清空崩溃日志 */
    fun clearCrashLog() {
        val file = logFile ?: return
        val crashFile = File(file.parentFile, "crash_log.txt")
        crashFile.delete()
        crashFile.createNewFile()
    }

    /** 清空运行日志 (含轮转文件) */
    fun clearAppLog() {
        val file = logFile ?: return
        file.delete()
        file.createNewFile()
        currentSize = 0
        val rotatedFile = File(file.parentFile, "app_log_1.txt")
        if (rotatedFile.exists()) {
            rotatedFile.delete()
        }
    }

    /** 读取崩溃日志内容 */
    fun readCrashLog(): String {
        val file = logFile ?: return "(日志未初始化)"
        val crashFile = File(file.parentFile, "crash_log.txt")
        return if (crashFile.exists()) crashFile.readText() else "(暂无崩溃日志)"
    }

    /** 读取运行日志内容 (含轮转的历史日志) */
    fun readAppLog(): String {
        val file = logFile ?: return "(日志未初始化)"
        val latestContent = if (file.exists()) file.readText() else ""
        val rotatedFile = File(file.parentFile, "app_log_1.txt")
        val rotatedContent = if (rotatedFile.exists()) {
            "=== 以下为历史轮转日志 (app_log_1.txt) ===\n${rotatedFile.readText()}"
        } else {
            ""
        }
        return latestContent + rotatedContent
    }
}

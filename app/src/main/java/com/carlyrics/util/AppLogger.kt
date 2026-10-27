package com.carlyrics.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车规级高性能异步运行日志工具。
 * 核心优化：
 * 1. 彻底移除调用方（包括主线程）同步文件写入与锁排队，采用无锁有界 Channel 异步批量落盘；
 * 2. 避免在主线程进行 SimpleDateFormat 格式化与锁争夺，移至专属后台线程；
 * 3. 内存批量聚合落盘（4KB 缓冲或 800ms 刷新），大幅降低车机 eMMC 5.1 闪存擦写与 I/O 延迟；
 * 4. 自动 500KB 轮转，提供崩溃与运行日志安全检索。
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_FILE_SIZE = 500L * 1024L // 500KB
    private const val BATCH_FLUSH_INTERVAL_MS = 800L

    private var logFile: File? = null
    @Volatile
    private var currentSize: Long = 0

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<LogEntry>(capacity = 2000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "app_log.txt")
        file.parentFile?.mkdirs()
        logFile = file
        currentSize = file.length()

        startAsyncWriter()
    }

    private fun startAsyncWriter() {
        logScope.launch {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val dateObj = Date()
            val buffer = java.lang.StringBuilder(4096)
            var lastFlushTime = System.currentTimeMillis()

            while (isActive) {
                val entry = logChannel.tryReceive().getOrNull()
                if (entry != null) {
                    dateObj.time = entry.timestamp
                    val timeStr = dateFormat.format(dateObj)
                    buffer.append(timeStr).append(' ')
                        .append(entry.level).append('/')
                        .append(entry.tag).append(": ")
                        .append(entry.message).append('\n')
                }

                val now = System.currentTimeMillis()
                val shouldFlush = buffer.isNotEmpty() && (
                    buffer.length >= 4096 ||
                    (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS) ||
                    (entry == null && buffer.length > 512)
                )

                if (shouldFlush) {
                    writeBatchToFile(buffer.toString())
                    buffer.setLength(0)
                    lastFlushTime = now
                }

                if (entry == null) {
                    delay(120L) // 队列空闲时平缓挂起
                }
            }
        }
    }

    @Synchronized
    private fun writeBatchToFile(text: String) {
        val file = logFile ?: return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (currentSize + bytes.size > MAX_FILE_SIZE) {
                rotateLog()
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            currentSize += bytes.size
        } catch (_: Exception) {
            // 日志写入失败静默
        }
    }

    @Synchronized
    fun flushSync() {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val dateObj = Date()
            val sb = java.lang.StringBuilder()
            while (true) {
                val entry = logChannel.tryReceive().getOrNull() ?: break
                dateObj.time = entry.timestamp
                val timeStr = dateFormat.format(dateObj)
                sb.append(timeStr)
                    .append(" [").append(entry.level).append("] [")
                    .append(entry.tag).append("] ")
                    .append(entry.message).append('\n')
            }
            if (sb.isNotEmpty()) {
                writeBatchToFile(sb.toString())
            }
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        if (PrefsManager.isLogEnabled()) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "D", tag, msg))
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        if (PrefsManager.isLogEnabled()) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "I", tag, msg))
        }
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        val fullMsg = if (throwable != null) {
            "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            msg
        }
        if (PrefsManager.isLogEnabled()) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "W", tag, fullMsg))
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        val fullMsg = if (throwable != null) {
            "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            msg
        }
        logChannel.trySend(LogEntry(System.currentTimeMillis(), "E", tag, fullMsg))
    }

    @Synchronized
    private fun rotateLog() {
        val file = logFile ?: return
        try {
            val rotatedFile = File(file.parentFile, "app_log_1.txt")
            if (rotatedFile.exists()) {
                rotatedFile.delete()
            }
            file.renameTo(rotatedFile)
            file.createNewFile()
            currentSize = 0
        } catch (_: Exception) {}
    }

    fun getCrashLogPath(): String? {
        val file = logFile ?: return null
        return File(file.parentFile, "crash_log.txt").absolutePath
    }

    fun getAppLogPath(): String? {
        return logFile?.absolutePath
    }

    @Synchronized
    fun clearCrashLog() {
        val file = logFile ?: return
        val crashFile = File(file.parentFile, "crash_log.txt")
        crashFile.delete()
        try { crashFile.createNewFile() } catch (_: Exception) {}
    }

    @Synchronized
    fun clearAppLog() {
        val file = logFile ?: return
        file.delete()
        try { file.createNewFile() } catch (_: Exception) {}
        currentSize = 0
        val rotatedFile = File(file.parentFile, "app_log_1.txt")
        if (rotatedFile.exists()) {
            rotatedFile.delete()
        }
    }

    fun readCrashLog(): String {
        val file = logFile ?: return "(日志未初始化)"
        val crashFile = File(file.parentFile, "crash_log.txt")
        return if (crashFile.exists()) crashFile.readText() else "(暂无崩溃日志)"
    }

    @Synchronized
    fun readAppLog(): String {
        val file = logFile ?: return "(日志未初始化)"
        val latestContent = if (file.exists()) file.readText() else ""
        val rotatedFile = File(file.parentFile, "app_log_1.txt")
        val rotatedContent = if (rotatedFile.exists()) {
            "\n=== 以下为历史轮转日志 (app_log_1.txt) ===\n${rotatedFile.readText()}"
        } else {
            ""
        }
        return latestContent + rotatedContent
    }
}

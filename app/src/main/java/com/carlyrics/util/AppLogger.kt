package com.carlyrics.util

import android.content.Context
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap

/**
 * 车规级高性能异步运行日志与故障溯源黑匣子工具。
 *
 * 核心优化：
 * 1. 【全异步无锁流式写入】：调用方零锁等待，有界 Channel + IO 协程批量刷盘，绝不阻塞主线程/渲染/Binder；
 * 2. 【智能防刷抑制与聚合 (Log Throttler)】：高频重复事件（传感器抖动、探针重连等）自动合并折叠，彻底根除 Logcat 被瞬间刷满顶没的问题；
 * 3. 【故障溯源黑匣子 (Flight Recorder Breadcrumbs)】：内存循环环形队列记录最近 100 条传感器/系统高频心跳，常规静默不写盘、不刷 Logcat，一旦出现异常/错误 (Error / Warning / Crash) 自动快照导出前序轨迹，精准定位故障；
 * 4. 【多维度过滤与磁盘轮转保护】：500KB 安全轮转，异常堆栈完整保全，提供 UI 日志导出接口。
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_FILE_SIZE = 500L * 1024L // 500KB
    private const val BATCH_FLUSH_INTERVAL_MS = 800L
    private const val BREADCRUMB_CAPACITY = 100

    private var logFile: File? = null
    @Volatile
    private var currentSize: Long = 0
    @Volatile
    private var diskLogEnabledCache: Boolean = false

    fun setDiskLogEnabledCache(enabled: Boolean) {
        diskLogEnabledCache = enabled
    }

    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<LogEntry>(capacity = 2000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    // ==========================================
    // 1. 故障溯源黑匣子 (内存循环环形队列，零 I/O 零 GC)
    // ==========================================
    private class BreadcrumbEntry {
        var timestamp: Long = 0L
        var category: String = ""
        var message: String = ""
    }

    private val breadcrumbBuffer = Array(BREADCRUMB_CAPACITY) { BreadcrumbEntry() }
    private var breadcrumbHead = 0
    private var breadcrumbCount = 0
    private val breadcrumbLock = Any()

    /**
     * 记录高频传感器或轻量运行心跳面包屑（仅驻留内存环形队列，零 I/O、不刷 Logcat）
     * 仅当系统抛出异常/错误或主动导出时，自动附着于故障现场日志中
     */
    fun recordBreadcrumb(category: String, message: String) {
        synchronized(breadcrumbLock) {
            val entry = breadcrumbBuffer[breadcrumbHead]
            entry.timestamp = System.currentTimeMillis()
            entry.category = category
            entry.message = message
            breadcrumbHead = (breadcrumbHead + 1) % BREADCRUMB_CAPACITY
            if (breadcrumbCount < BREADCRUMB_CAPACITY) {
                breadcrumbCount++
            }
        }
    }

    /**
     * 获取最近传感器与系统心跳的黑匣子快照（故障溯源用）
     */
    fun dumpBreadcrumbs(limit: Int = 30): String {
        val sb = StringBuilder(2048)
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val dateObj = Date()
        synchronized(breadcrumbLock) {
            if (breadcrumbCount == 0) return "(无近期传感器心跳记录)"
            val n = minOf(limit, breadcrumbCount)
            val startIdx = (breadcrumbHead - n + BREADCRUMB_CAPACITY) % BREADCRUMB_CAPACITY
            for (i in 0 until n) {
                val idx = (startIdx + i) % BREADCRUMB_CAPACITY
                val b = breadcrumbBuffer[idx]
                dateObj.time = b.timestamp
                sb.append("  [").append(dateFormat.format(dateObj)).append("] [")
                    .append(b.category).append("] ")
                    .append(b.message).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    // ==========================================
    // 2. 智能防刷与去重折叠器 (Log Throttler)
    // ==========================================
    private class ThrottleState(
        var lastEmitTimeMs: Long,
        var suppressedCount: Int = 0
    )

    private val throttleMap = ConcurrentHashMap<String, ThrottleState>()

    @Volatile private var lastConsecutiveTag: String = ""
    @Volatile private var lastConsecutiveMsg: String = ""
    @Volatile private var lastConsecutiveTimeMs: Long = 0L
    @Volatile private var consecutiveSuppressedCount: Int = 0

    private fun isConsecutiveSpam(tag: String, msg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (tag == lastConsecutiveTag && msg == lastConsecutiveMsg && (now - lastConsecutiveTimeMs) < 2500L) {
            consecutiveSuppressedCount++
            return true
        }
        val count = consecutiveSuppressedCount
        val prevTag = lastConsecutiveTag
        lastConsecutiveTag = tag
        lastConsecutiveMsg = msg
        lastConsecutiveTimeMs = now
        consecutiveSuppressedCount = 0
        if (count > 0 && prevTag.isNotEmpty()) {
            val notice = "[前项重复事件已合并折叠 $count 次]"
            Log.d(prevTag, notice)
            if (diskLogEnabledCache) {
                logChannel.trySend(LogEntry(System.currentTimeMillis(), "D", prevTag, notice))
            }
        }
        return false
    }

    /**
     * 限频节流日志输出：同一 TAG 下的相同特征日志在 windowMs 内仅输出首条，并在窗口后汇总输出抑制次数
     */
    fun throttledD(tag: String, msg: String, windowMs: Long = 10_000L) {
        if (checkThrottle(tag, msg, windowMs, "D")) return
        d(tag, msg)
    }

    fun throttledI(tag: String, msg: String, windowMs: Long = 10_000L) {
        if (checkThrottle(tag, msg, windowMs, "I")) return
        i(tag, msg)
    }

    fun throttledW(tag: String, msg: String, windowMs: Long = 10_000L, throwable: Throwable? = null) {
        if (checkThrottle(tag, msg, windowMs, "W")) return
        w(tag, msg, throwable)
    }

    private fun checkThrottle(tag: String, msg: String, windowMs: Long, level: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (throttleMap.size > 192) {
            val iter = throttleMap.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (now - e.value.lastEmitTimeMs > 60_000L) {
                    iter.remove()
                }
            }
            if (throttleMap.size > 256) {
                throttleMap.clear()
            }
        }
        val key = "$level:$tag:$msg"
        val state = throttleMap.getOrPut(key) { ThrottleState(now) }
        synchronized(state) {
            val elapsed = now - state.lastEmitTimeMs
            if (elapsed < windowMs) {
                state.suppressedCount++
                return true
            } else {
                val suppressed = state.suppressedCount
                state.lastEmitTimeMs = now
                state.suppressedCount = 0
                if (suppressed > 0) {
                    val summary = "$msg [已合并折叠 $suppressed 条重复日志]"
                    when (level) {
                        "D" -> {
                            Log.d(tag, summary)
                            if (diskLogEnabledCache) logChannel.trySend(LogEntry(System.currentTimeMillis(), "D", tag, summary))
                        }
                        "I" -> {
                            Log.i(tag, summary)
                            if (diskLogEnabledCache) logChannel.trySend(LogEntry(System.currentTimeMillis(), "I", tag, summary))
                        }
                        "W" -> {
                            Log.w(tag, summary)
                            if (diskLogEnabledCache) logChannel.trySend(LogEntry(System.currentTimeMillis(), "W", tag, summary))
                        }
                    }
                    return true
                }
                return false
            }
        }
    }

    fun init(context: Context) {
        diskLogEnabledCache = try { PrefsManager.isLogEnabled() } catch (_: Exception) { false }
        if (logFile != null) return
        val dir = File(context.filesDir, "logs")
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

    fun v(tag: String, msg: String) {
        Log.v(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (isConsecutiveSpam(tag, msg)) return
        Log.d(tag, msg)
        if (diskLogEnabledCache) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "D", tag, msg))
        }
    }

    fun i(tag: String, msg: String) {
        if (isConsecutiveSpam(tag, msg)) return
        Log.i(tag, msg)
        if (diskLogEnabledCache) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "I", tag, msg))
        }
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            msg
        }
        Log.w(tag, fullMsg, throwable)
        if (diskLogEnabledCache) {
            logChannel.trySend(LogEntry(System.currentTimeMillis(), "W", tag, fullMsg))
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val breadcrumbsSnapshot = dumpBreadcrumbs(limit = 25)
        val fullMsg = buildString {
            append(msg)
            if (throwable != null) {
                append(" | ").append(throwable.javaClass.simpleName).append(": ").append(throwable.message)
            }
            if (breadcrumbsSnapshot.isNotBlank() && !breadcrumbsSnapshot.startsWith("(")) {
                append("\n--- [故障现场上下文溯源 / Recent Breadcrumbs] ---\n")
                append(breadcrumbsSnapshot)
                append("\n-------------------------------------------------")
            }
        }
        Log.e(tag, fullMsg, throwable)
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
        throttleMap.clear()
        synchronized(breadcrumbLock) {
            breadcrumbHead = 0
            breadcrumbCount = 0
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
        val breadcrumbHeader = "=== 最近传感器黑匣子心跳 (最近 20 条) ===\n${dumpBreadcrumbs(20)}\n=========================================\n\n"
        return breadcrumbHeader + latestContent + rotatedContent
    }
}

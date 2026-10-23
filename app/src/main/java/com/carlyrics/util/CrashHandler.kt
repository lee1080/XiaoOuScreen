package com.carlyrics.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 * 捕获崩溃后写入日志文件，然后调用系统默认处理器确保 APP 正常退出。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var logFile: File? = null
    private var deviceInfo: String = ""
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        if (Thread.getDefaultUncaughtExceptionHandler() is CrashHandler) return

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        logFile = File(context.getExternalFilesDir(null), "crash_log.txt")

        // 收集设备信息
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        deviceInfo = buildString {
            appendLine("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("屏幕分辨率: ${metrics.widthPixels}x${metrics.heightPixels} (density=${metrics.density})")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"}")
            appendLine("---")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = dateFormat.format(Date())
            val sw = StringWriter()
            PrintWriter(sw).use { throwable.printStackTrace(it) }

            val entry = buildString {
                appendLine()
                appendLine("========== 崩溃日志 ==========")
                appendLine("时间: $timestamp")
                appendLine("线程: ${thread.name} (id=${thread.id})")
                appendLine("异常类型: ${throwable.javaClass.name}")
                appendLine("异常消息: ${throwable.message ?: "(无)"}")
                appendLine()
                appendLine("设备信息:")
                append(deviceInfo)
                appendLine()
                appendLine("调用栈:")
                append(sw.toString())
                appendLine("==============================")
            }

            logFile?.apply {
                parentFile?.mkdirs()
                appendText(entry)
            }

            AppLogger.e("CrashHandler", "未捕获异常已记录", throwable)
        } catch (_: Exception) {
            // 即使日志写入失败也不能吞掉异常
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

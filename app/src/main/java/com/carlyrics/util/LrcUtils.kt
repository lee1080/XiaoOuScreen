package com.carlyrics.util

/**
 * LRC 文本溢出处理工具
 * 处理非标准 LRC 格式、时间戳修正等
 */
object LrcUtils {

    /**
     * 合并连续的相同文本行 (部分 LRC 源存在重复)
     */
    fun deduplicateLines(lines: List<com.carlyrics.model.LyricLine>): List<com.carlyrics.model.LyricLine> {
        val result = mutableListOf<com.carlyrics.model.LyricLine>()
        for (line in lines) {
            if (result.isEmpty() || result.last().text != line.text) {
                result.add(line)
            }
        }
        return result
    }

    /**
     * 格式化时间为 mm:ss 字符串
     */
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

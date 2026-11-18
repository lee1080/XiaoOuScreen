package com.carlyrics.model

/**
 * 当前播放歌曲信息 (由多渠道识别模块产出)
 */
data class CurrentTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val position: Long = 0L,       // 当前播放进度 (ms)
    val duration: Long = 0L,       // 总时长 (ms)
    val source: MusicSource = MusicSource.UNKNOWN,
    val packageName: String = "",   // 来源应用包名
    val isPlaying: Boolean = true,  // 是否处于正在播放状态
    val mediaId: String = "",        // 播放器内部媒体唯一标识 (如汽水音乐 track_id)
    val nativeLyrics: List<LyricLine>? = null, // 原车/本地已解析歌词 (若有)
    val nativeCoverUrl: String? = null // 原车/本地提取的封面缓存路径 (若有)
)

enum class MusicSource {
    KUGOU_NATIVE,       // 原车酷狗音乐 (AIDL 原生专线)
    MEDIA_SESSION,      // 通过 MediaSession 获取
    NOTIFICATION,       // 通过通知栏获取
    BLUETOOTH,          // 通过蓝牙 AVRCP
    BROADCAST,          // 通过系统广播
    UNKNOWN
}

/**
 * 歌词字/词级时间戳 (用于卡拉OK逐字高亮)
 */
data class LyricWord(
    val text: String,
    val startTime: Long,    // 绝对时间戳 (ms)
    val duration: Long      // 持续时间 (ms)
)

/**
 * 歌词行 (API 返回的歌词解析结果)
 */
data class LyricLine(
    val timestamp: Long,    // 毫秒时间戳
    val text: String,
    val words: List<LyricWord> = emptyList()
) {
    @Transient
    private var cachedGeneratedWords: List<LyricWord>? = null

    /**
     * 获取原生字级时间戳；若当前歌词为普通LRC，则根据该行持续时间平滑估算各字符节奏
     */
    fun getOrGenerateWords(nextLineTimestamp: Long? = null): List<LyricWord> {
        if (words.isNotEmpty()) return words
        if (text.isBlank()) return emptyList()
        cachedGeneratedWords?.let { return it }

        val lineDuration = if (nextLineTimestamp != null && nextLineTimestamp > timestamp) {
            (nextLineTimestamp - timestamp).coerceIn(1200L, 10000L)
        } else {
            (text.length * 350L).coerceIn(1500L, 5000L)
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val wordDuration = (lineDuration / trimmed.length.coerceAtLeast(1)).coerceAtLeast(50L)

        val result = trimmed.mapIndexed { i, char ->
            LyricWord(
                text = char.toString(),
                startTime = timestamp + i * wordDuration,
                duration = wordDuration
            )
        }
        cachedGeneratedWords = result
        return result
    }
}

/**
 * 歌词整体 (含封面)
 */
data class LyricData(
    val lines: List<LyricLine>,
    val coverUrl: String? = null,
    val offset: Long = 0L,   // 整体偏移 (ms), 用于微调
    val sourceName: String = "",
    val rawLrc: String? = null
)

/**
 * 通知栏实时嗅探提取到的歌词信息
 */
data class NotificationLyric(
    val text: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)


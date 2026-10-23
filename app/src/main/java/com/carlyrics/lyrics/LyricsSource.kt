package com.carlyrics.lyrics

/**
 * 歌词源数据模型
 */
data class LyricsSource(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val script: String = "",
    val url: String = "",
    var isEnabled: Boolean = true,
    var order: Int = 0
)

/**
 * 歌词源合集模型
 */
data class LyricsCollection(
    val name: String? = "歌词源合集",
    val version: String? = "1.0.0",
    val description: String? = "",
    val author: String? = "",
    val updatedAt: String? = "",
    val sources: List<LyricsSourceItem>? = emptyList()
)

data class LyricsSourceItem(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val author: String? = null,
    val description: String? = null,
    val url: String? = null,
    val script: String? = null
)

data class SourceSearchItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L // 秒
)

data class SourceLyricResult(
    val lrc: String? = null,
    val cover: String? = null
)

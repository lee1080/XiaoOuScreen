package com.carlyrics.display

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 歌词全屏展示 Activity —— 用于主屏或副屏
 * 通过 Intent extras 接收歌词数据或由 Service 驱动
 */
class LyricsDisplayActivity : AppCompatActivity() {

    private lateinit var displayView: LyricsDisplayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayView = LyricsDisplayView(this)
        setContentView(displayView)

        // 读取传入数据
        val title = intent.getStringExtra("track_title") ?: ""
        val artist = intent.getStringExtra("track_artist") ?: ""
        val coverUrl = intent.getStringExtra("cover_url")

        displayView.setTrackInfo(title, artist)
        displayView.setCoverUrl(coverUrl)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 新版 LyricsDisplayView 固定横版布局，无需响应旋转
    }

    /**
     * 被外部 (PresentationService) 调用以更新进度
     */
    fun updateProgress(positionMs: Long) {
        displayView.updateProgress(positionMs)
    }

    /**
     * 被外部调用以设置歌词数据
     */
    fun setLyrics(lines: List<com.carlyrics.model.LyricLine>) {
        displayView.setLyrics(lines)
    }

    override fun onDestroy() {
        try { displayView.onDestroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}

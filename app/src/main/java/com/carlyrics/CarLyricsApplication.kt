package com.carlyrics

import android.app.Application
import com.carlyrics.recognition.MusicRecognitionEngine

/**
 * 歌词模块单例兼容层，桥接全局 Application 和识别引擎
 */
object CarLyricsApplication {
    lateinit var instance: Application
    lateinit var recognitionEngine: MusicRecognitionEngine
}

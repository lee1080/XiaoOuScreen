package com.car.xiaoouscreen.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 兼容旧版入口的歌词设置代理 Activity
 * 自动重定向至新版车规级「小欧屏 — 设置中心」
 */
class LyricsSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}

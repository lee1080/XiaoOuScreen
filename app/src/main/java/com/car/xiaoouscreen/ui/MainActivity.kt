package com.car.xiaoouscreen.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.car.xiaoouscreen.service.WeatherOverlayWindow
import com.car.xiaoouscreen.ui.settings.SettingsScreen
import com.car.xiaoouscreen.ui.settings.viewmodel.SettingsViewModel
import com.carlyrics.CarLyricsApplication
import com.carlyrics.model.CurrentTrack
import com.carlyrics.util.PrefsManager

/**
 * 车规级「小欧屏 — 设置中心」原生主入口 Activity
 * 严格按照车规级横屏交互标准，采用 Jetpack Compose 1:1 还原浅色拟物风格
 * 保留后台服务调度与 ADB 调试监听接口，移除所有传统模拟场景按键
 */
class MainActivity : ComponentActivity(), WeatherOverlayService.ServiceCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化持久化与服务
        try {
            PrefsManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "PrefsManager.init error: ${e.message}")
        }

        // 2. 初始化 ViewModel
        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        // 3. 渲染车规级 1:1 浅色拟物原生界面 (全屏元素统一按 110% 车规比例适度放大，兼顾座舱可读性与精细度)
        setContent {
            val originalDensity = LocalDensity.current
            val scaledDensity = remember(originalDensity) {
                Density(
                    density = originalDensity.density * 1.10f,
                    fontScale = originalDensity.fontScale
                )
            }
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                SettingsScreen(viewModel = viewModel)
            }
        }

        // 4. 应用横屏沉浸式全屏
        applyCarFullscreenImmersive()

        // 5. 若用户之前开启过投屏且当前未在运行，等主界面首帧稳定渲染完毕后 (延时500ms) 再平滑拉起投屏，杜绝启动争抢主线程
        window.decorView.postDelayed({
            try {
                if (PrefsManager.getOverlayVisible() && WeatherOverlayService.instance?.isShowing() != true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "无悬浮窗权限，跳过自动拉起投屏服务，防止在单屏/手机上发生崩溃或触控遮挡")
                        return@postDelayed
                    }
                    val showAvatar = PrefsManager.isXiaoOuAvatarEnabled()
                    val targetAction = if (showAvatar) WeatherOverlayService.ACTION_SHOW_FITNESS else WeatherOverlayService.ACTION_SHOW_TOP
                    val serviceIntent = Intent(this, WeatherOverlayService::class.java).apply {
                        action = targetAction
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Start WeatherOverlayService deferred: ${e.message}")
            }
        }, 500L)

        WeatherOverlayService.serviceCallback = this
    }

    override fun onResume() {
        super.onResume()
        applyCarFullscreenImmersive()
        viewModel.refreshPermissionStatesAsync()
        val isShowing = (WeatherOverlayService.instance?.isShowing() == true) || PrefsManager.getOverlayVisible()
        viewModel.onOverlayStateChanged(isShowing)

        // 实时同步当前识别到的正在展示歌词的曲目信息
        try {
            val currentTrack = CarLyricsApplication.recognitionEngine.currentTrack.value
            val title = if (currentTrack != null && currentTrack.title.isNotBlank()) {
                if (currentTrack.artist.isNotBlank()) "${currentTrack.title} — ${currentTrack.artist}" else currentTrack.title
            } else {
                null
            }
            viewModel.setMusicTitle(title)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (WeatherOverlayService.serviceCallback == this) {
            WeatherOverlayService.serviceCallback = null
        }
    }

    /**
     * 车机横屏全屏沉浸式配置 (隐藏状态栏与导航键，防止误触)
     */
    private fun applyCarFullscreenImmersive() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "applyCarFullscreenImmersive warning: ${e.message}")
        }
    }

    // ==================== WeatherOverlayService.ServiceCallback ====================

    override fun onOverlayStateChanged(
        isShowing: Boolean,
        mode: WeatherOverlayWindow.Mode?,
        displayDesc: String
    ) {
        runOnUiThread {
            viewModel.onOverlayStateChanged(isShowing)
        }
    }

    override fun onSceneChanged(scene: WeatherSceneConfig) {
        // 天气场景变更
    }

    override fun onTrackChanged(track: CurrentTrack?) {
        // 音乐曲目变更：实时同步右上角状态栏展示歌词的歌名
        runOnUiThread {
            val title = if (track != null && track.title.isNotBlank()) {
                if (track.artist.isNotBlank()) "${track.title} — ${track.artist}" else track.title
            } else {
                null
            }
            viewModel.setMusicTitle(title)
        }
    }

    override fun onGearChanged(gear: String) {
        // 档位信号变更 (支持通过 ADB 广播测试实时联动)
    }

    override fun onDriveModeChanged(mode: String) {
        // 驾驶模式变更 (支持通过 ADB 广播测试实时联动)
    }
}

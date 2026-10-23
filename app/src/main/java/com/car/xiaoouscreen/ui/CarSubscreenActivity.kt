package com.car.xiaoouscreen.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarGearManager
import com.car.xiaoouscreen.data.OpenMeteoRepository
import com.car.xiaoouscreen.location.CarGpsManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 车载副屏全屏独立预览 Activity
 * 针对 1920 × 384 超宽屏幕定制，自动隐藏系统状态栏与导航栏
 * 交互：轻触屏幕任意位置弹出关闭退出按钮 (✕)，5 秒无操作自动隐藏
 * 集成：3D 正方体向上翻转档位动效，支持车机真实档位变动与模拟测试
 */
class CarSubscreenActivity : AppCompatActivity(), CarGearManager.GearListener {

    private lateinit var weatherCanvasView: CarWeatherCanvasView
    private lateinit var closeButtonContainer: View
    private var gearCubeWebView: WebView? = null
    private lateinit var viewModel: WeatherClockViewModel

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideCloseRunnable = Runnable {
        closeButtonContainer.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()

        setContentView(R.layout.activity_car_subscreen)
        weatherCanvasView = findViewById(R.id.carWeatherCanvasView)
        closeButtonContainer = findViewById(R.id.closeButtonContainer)
        gearCubeWebView = findViewById(R.id.gearCubeWebView)

        findViewById<View>(R.id.tvCloseButton).setOnClickListener {
            finish()
        }

        gearCubeWebView?.let { setupGearWebView(it) }

        // 注册档位监听
        CarGearManager.getInstance(this).apply {
            init()
            registerListener(this@CarSubscreenActivity)
        }

        // 组装依赖
        val repository = OpenMeteoRepository(applicationContext)
        val gpsManager = CarGpsManager(applicationContext)
        viewModel = WeatherClockViewModel(repository, gpsManager)

        // 响应式收集天气场景状态并驱动 View 渲染
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneConfig.collect { scene ->
                    weatherCanvasView.updateScene(scene)
                }
            }
        }

        viewModel.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupGearWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onRollOutComplete() {
                autoHideHandler.post {
                    gearCubeWebView?.visibility = View.GONE
                }
            }

            @JavascriptInterface
            fun onPageReady() {
                autoHideHandler.post {
                    gearCubeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
                }
            }
        }, "GearBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                gearCubeWebView?.evaluateJavascript("window.setOverlayMode(true)", null)
            }
        }

        webView.loadUrl("file:///android_asset/hmi/gear_cube_flip.html")
    }

    override fun onGearChanged(gear: String, rawValue: Int) {
        val web = gearCubeWebView ?: return
        autoHideHandler.post {
            try {
                val snapshotBase64 = captureSnapshot()
                if (!snapshotBase64.isNullOrEmpty()) {
                    web.evaluateJavascript("window.updateBaseScreenSnapshot('$snapshotBase64')", null)
                }
                web.evaluateJavascript("window.setOverlayMode(true)", null)
                web.visibility = View.VISIBLE
                web.bringToFront()
                web.evaluateJavascript("window.onCarGearChange('$gear')", null)
            } catch (t: Throwable) {
                Log.e("CarSubscreenActivity", "触发档位翻转异常", t)
            }
        }
    }

    private fun captureSnapshot(): String? {
        if (weatherCanvasView.width <= 0 || weatherCanvasView.height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(weatherCanvasView.width, weatherCanvasView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            weatherCanvasView.draw(canvas)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            showCloseButtonWithTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showCloseButtonWithTimer() {
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        closeButtonContainer.visibility = View.VISIBLE
        autoHideHandler.postDelayed(hideCloseRunnable, 5000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onDestroy() {
        super.onDestroy()
        CarGearManager.getInstance(this).unregisterListener(this)
        autoHideHandler.removeCallbacks(hideCloseRunnable)
        viewModel.stop()
    }
}

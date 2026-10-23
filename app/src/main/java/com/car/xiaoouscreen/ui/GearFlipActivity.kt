package com.car.xiaoouscreen.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarGearManager

/**
 * 1920×384 3D 正方体档位向上翻转展示与模拟 Activity
 *
 * 支持：
 * 1. 真实车机环境自动对接 CarGearManager (CarCabinManager 反射监听)
 * 2. 平板 / 手机 / 模拟器 ADB 广播切档模拟 (com.car.gear.SWITCH)
 * 3. 硬件/蓝牙键盘 P/R/N/D 按键切档模拟
 * 4. 触控面板点击模拟
 */
class GearFlipActivity : AppCompatActivity(), CarGearManager.GearListener {

    companion object {
        private const val TAG = "GearFlipActivity"
        const val ACTION_GEAR_SWITCH = "com.car.gear.SWITCH"
        const val EXTRA_GEAR = "gear"           // "P", "R", "N", "D"
        const val EXTRA_RAW_GEAR = "rawGear"   // 1(N), 2(R), 4(P), 8(D)
    }

    private lateinit var webView: WebView
    private lateinit var carGearManager: CarGearManager
    private var isWebReady = false

    private val gearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_GEAR_SWITCH) {
                val gearStr = intent.getStringExtra(EXTRA_GEAR)
                val rawGear = intent.getIntExtra(EXTRA_RAW_GEAR, -1)

                Log.i(TAG, "收到切档广播: gear=$gearStr, rawGear=$rawGear")
                if (!gearStr.isNullOrEmpty()) {
                    carGearManager.simulateGear(gearStr)
                } else if (rawGear != -1) {
                    carGearManager.simulateRawGear(rawGear)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        setContentView(R.layout.activity_gear_flip)
        webView = findViewById(R.id.webViewGear)

        carGearManager = CarGearManager.getInstance(this)
        carGearManager.init()

        setupWebView()

        // 注册 ADB 模拟广播
        val filter = IntentFilter(ACTION_GEAR_SWITCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gearReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(gearReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        carGearManager.registerListener(this)
    }

    override fun onPause() {
        super.onPause()
        carGearManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(gearReceiver)
        } catch (_: Exception) {}
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#03060B"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isWebReady = true
                Log.i(TAG, "档位 HMI 页面加载完成")
                // 页面加载完成后同步当前档位
                dispatchGearToWeb(carGearManager.getCurrentGear())
            }
        }

        // 注入 Android <-> JS 桥接对象
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPageReady() {
                runOnUiThread {
                    isWebReady = true
                    dispatchGearToWeb(carGearManager.getCurrentGear())
                }
            }

            @JavascriptInterface
            fun requestSwitchGear(gear: String) {
                runOnUiThread {
                    carGearManager.simulateGear(gear)
                }
            }

            @JavascriptInterface
            fun isCarConnected(): Boolean {
                return carGearManager.isConnectedToCar()
            }
        }, "GearBridge")

        // 加载资产目录中的 HTML
        webView.loadUrl("file:///android_asset/hmi/gear_cube_flip.html")
    }

    // 实现 CarGearManager.GearListener
    override fun onGearChanged(gear: String, rawValue: Int) {
        Log.i(TAG, "监听到档位状态: $gear (raw=$rawValue)")
        dispatchGearToWeb(gear)
    }

    private fun dispatchGearToWeb(gear: String) {
        runOnUiThread {
            val script = "if (window.onCarGearChange) { window.onCarGearChange('$gear'); }"
            webView.evaluateJavascript(script, null)
        }
    }

    // 监听外接键盘 P/R/N/D 按键直接模拟切档
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val targetGear = when (keyCode) {
            KeyEvent.KEYCODE_P -> "P"
            KeyEvent.KEYCODE_R -> "R"
            KeyEvent.KEYCODE_N -> "N"
            KeyEvent.KEYCODE_D -> "D"
            else -> null
        }
        if (targetGear != null) {
            carGearManager.simulateGear(targetGear)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
    }
}

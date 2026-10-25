package com.car.xiaoouscreen.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import com.car.xiaoouscreen.BuildConfig
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.car.CarCallManager
import com.car.xiaoouscreen.data.QWeatherRepository
import com.car.xiaoouscreen.domain.TimeOfDay
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherState
import com.car.xiaoouscreen.service.ThirdDisplayFinder
import com.car.xiaoouscreen.service.WeatherOverlayService
import com.car.xiaoouscreen.service.WeatherOverlayWindow
import com.carlyrics.model.CurrentTrack
import com.carlyrics.util.PrefsManager
import com.carlyrics.CarLyricsApplication
import com.car.xiaoouscreen.util.NotificationPermissionHelper
import kotlinx.coroutines.launch

/**
 * 主屏控制中心与 4 时段 × 6 天气 = 24 状态模拟控制台
 */
class MainActivity : AppCompatActivity(), WeatherOverlayService.ServiceCallback {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvDisplayInfo: TextView
    private lateinit var tvPermissionNotice: TextView
    private lateinit var tvCurrentSceneInfo: TextView
    private lateinit var tvAppVersion: TextView

    // 车载歌词控制
    private lateinit var switchStandaloneLyrics: SwitchCompat
    private lateinit var btnLyricsSettings: Button
    private lateinit var tvLyricsStatus: TextView

    // 中央大时间液态玻璃控制
    private lateinit var switchLiquidGlassClock: SwitchCompat
    private lateinit var tvLiquidGlassStatus: TextView

    // 左下角常驻微型档位指示 HUD
    private lateinit var switchBottomLeftGearHud: SwitchCompat
    private lateinit var tvBottomLeftGearHudStatus: TextView

    // 和风高精天气控制
    private lateinit var switchEnableQWeather: SwitchCompat
    private lateinit var tvQWeatherBadge: TextView
    private lateinit var tvQWeatherDetail: TextView
    private lateinit var btnConfigQWeather: Button
    private lateinit var btnTestQWeather: Button
    private lateinit var btnClearQWeather: Button
    private lateinit var qWeatherRepo: QWeatherRepository
    private lateinit var gpsManager: com.car.xiaoouscreen.location.CarGpsManager

    // 时段选择按钮
    private lateinit var btnTimeDawn: Button
    private lateinit var btnTimeDay: Button
    private lateinit var btnTimeDusk: Button
    private lateinit var btnTimeNight: Button

    // 天气选择按钮
    private lateinit var btnWeatherClear: Button
    private lateinit var btnWeatherCloudy: Button
    private lateinit var btnWeatherOvercast: Button
    private lateinit var btnWeatherRain: Button
    private lateinit var btnWeatherSnow: Button
    private lateinit var btnWeatherStorm: Button
    private lateinit var btnWeatherSleet: Button

    // 档位模拟选择按钮
    private lateinit var tvCurrentGearBadge: TextView
    private lateinit var btnSimGearP: Button
    private lateinit var btnSimGearR: Button
    private lateinit var btnSimGearN: Button
    private lateinit var btnSimGearD: Button
    private lateinit var btnSimGearCombo: Button
    private var currentSimGear = "P"

    // 驾驶模式模拟选择按钮
    private lateinit var tvCurrentDriveModeBadge: TextView
    private lateinit var btnModeECO: Button
    private lateinit var btnModeNORMAL: Button
    private lateinit var btnModeSPORT: Button
    private lateinit var btnModeRACE: Button
    private lateinit var btnModeCUSTOM: Button
    private lateinit var btnModeCombo: Button
    private var currentSimDriveMode = "SPORT"

    // 通话模拟选择按钮
    private lateinit var tvCurrentCallBadge: TextView
    private lateinit var btnCallIncoming: Button
    private lateinit var btnCallOutgoing: Button
    private lateinit var btnCallActive: Button
    private lateinit var btnCallHangup: Button
    private lateinit var btnCallWeChatIncoming: Button
    private lateinit var btnCallWeChatActive: Button
    private lateinit var btnCallResetIdle: Button

    // 特殊状态
    private var selectedTimeOfDay: TimeOfDay = TimeOfDay.DAY
    private var selectedWeatherState: WeatherState = WeatherState.CLEAR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvDisplayInfo = findViewById(R.id.tvDisplayInfo)
        tvPermissionNotice = findViewById(R.id.tvPermissionNotice)
        tvCurrentSceneInfo = findViewById(R.id.tvCurrentSceneInfo)
        tvAppVersion = findViewById(R.id.tvAppVersion)
        tvAppVersion.text = "小欧屏 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // 车载歌词
        switchStandaloneLyrics = findViewById(R.id.switchStandaloneLyrics)
        btnLyricsSettings = findViewById(R.id.btnLyricsSettings)
        tvLyricsStatus = findViewById(R.id.tvLyricsStatus)
        setupLyricsControls()

        // 中央大时间液态玻璃
        switchLiquidGlassClock = findViewById(R.id.switchLiquidGlassClock)
        tvLiquidGlassStatus = findViewById(R.id.tvLiquidGlassStatus)
        setupLiquidGlassControls()

        // 左下角常驻微型档位 HUD
        switchBottomLeftGearHud = findViewById(R.id.switchBottomLeftGearHud)
        tvBottomLeftGearHudStatus = findViewById(R.id.tvBottomLeftGearHudStatus)
        setupBottomLeftGearHudControls()

        // 和风高精气象服务
        switchEnableQWeather = findViewById(R.id.switchEnableQWeather)
        tvQWeatherBadge = findViewById(R.id.tvQWeatherBadge)
        tvQWeatherDetail = findViewById(R.id.tvQWeatherDetail)
        btnConfigQWeather = findViewById(R.id.btnConfigQWeather)
        btnTestQWeather = findViewById(R.id.btnTestQWeather)
        btnClearQWeather = findViewById(R.id.btnClearQWeather)
        qWeatherRepo = QWeatherRepository(this)
        gpsManager = com.car.xiaoouscreen.location.CarGpsManager(applicationContext)
        gpsManager.startLocationUpdates { _, _ ->
            runOnUiThread { updateQWeatherUI() }
        }
        setupQWeatherControls()

        btnTimeDawn = findViewById(R.id.btnTimeDawn)
        btnTimeDay = findViewById(R.id.btnTimeDay)
        btnTimeDusk = findViewById(R.id.btnTimeDusk)
        btnTimeNight = findViewById(R.id.btnTimeNight)

        btnWeatherClear = findViewById(R.id.btnWeatherClear)
        btnWeatherCloudy = findViewById(R.id.btnWeatherCloudy)
        btnWeatherOvercast = findViewById(R.id.btnWeatherOvercast)
        btnWeatherRain = findViewById(R.id.btnWeatherRain)
        btnWeatherSnow = findViewById(R.id.btnWeatherSnow)
        btnWeatherStorm = findViewById(R.id.btnWeatherStorm)
        btnWeatherSleet = findViewById(R.id.btnWeatherSleet)

        // 启动后台前台服务
        WeatherOverlayService.send(this, "INIT")
        WeatherOverlayService.serviceCallback = this

        setupProjectionControls()
        setupGearSimulationControls()
        setupDriveModeSimulationControls()
        setupCallSimulationControls()
        setup24MatrixSimulationControls()
        updateDisplayInfo()
        checkOverlayPermission()
        NotificationPermissionHelper.tryAutoGrantNotificationListener(this)
        if (!NotificationPermissionHelper.isNotificationListenerGranted(this)) {
            NotificationPermissionHelper.promptNotificationPermission(this)
        }
        highlightSelectedButtons()
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        NotificationPermissionHelper.tryAutoGrantNotificationListener(this)
        if (NotificationPermissionHelper.isNotificationListenerGranted(this)) {
            CarLyricsApplication.recognitionEngine.startMediaSessionListening()
            CarLyricsApplication.recognitionEngine.forceRefresh()
        }
        updateDisplayInfo()
        updateLyricsStatusUI()
        switchStandaloneLyrics.isChecked = PrefsManager.isStandaloneLyricsEnabled()
    }

    private fun setupProjectionControls() {
        findViewById<Button>(R.id.btnShowTop).setOnClickListener {
            if (ensureOverlayPermission()) {
                WeatherOverlayService.send(this, WeatherOverlayService.ACTION_SHOW_TOP)
                Toast.makeText(this, "已向小欧上方发起投屏 (2038)", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnShowFitness).setOnClickListener {
            if (ensureOverlayPermission()) {
                WeatherOverlayService.send(this, WeatherOverlayService.ACTION_SHOW_FITNESS)
                Toast.makeText(this, "已向小欧下方发起投屏 (2037)", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnHide).setOnClickListener {
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_HIDE)
            Toast.makeText(this, "已停止副屏投屏", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSwitchDisplay).setOnClickListener {
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_CYCLE_DISPLAY)
            updateDisplayInfo()
            Toast.makeText(this, "已切换目标屏幕", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnPreviewFullscreen).setOnClickListener {
            startActivity(Intent(this, CarSubscreenActivity::class.java))
        }

        tvPermissionNotice.setOnClickListener {
            requestOverlayPermission()
        }
    }

    private fun setup24MatrixSimulationControls() {
        // 4 时段监听
        btnTimeDawn.setOnClickListener {
            selectedTimeOfDay = TimeOfDay.DAWN
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnTimeDay.setOnClickListener {
            selectedTimeOfDay = TimeOfDay.DAY
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnTimeDusk.setOnClickListener {
            selectedTimeOfDay = TimeOfDay.DUSK
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnTimeNight.setOnClickListener {
            selectedTimeOfDay = TimeOfDay.NIGHT
            highlightSelectedButtons()
            applyCurrentSelection()
        }

        // 7 天气类型监听 (4×7=28 矩阵)
        btnWeatherClear.setOnClickListener {
            selectedWeatherState = WeatherState.CLEAR
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherCloudy.setOnClickListener {
            selectedWeatherState = WeatherState.CLOUDY
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherOvercast.setOnClickListener {
            selectedWeatherState = WeatherState.OVERCAST
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherRain.setOnClickListener {
            selectedWeatherState = WeatherState.RAIN
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherSnow.setOnClickListener {
            selectedWeatherState = WeatherState.SNOW
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherStorm.setOnClickListener {
            selectedWeatherState = WeatherState.STORM
            highlightSelectedButtons()
            applyCurrentSelection()
        }
        btnWeatherSleet.setOnClickListener {
            selectedWeatherState = WeatherState.SLEET
            highlightSelectedButtons()
            applyCurrentSelection()
        }

        // 闪电触发
        findViewById<Button>(R.id.btnTriggerLightning).setOnClickListener {
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_TRIGGER_LIGHTNING)
            Toast.makeText(this, "⚡ 触发闪电击穿与电感脉冲", Toast.LENGTH_SHORT).show()
        }

        // 恢复真实 GPS 联网
        findViewById<Button>(R.id.btnResetLive).setOnClickListener {
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_RESET_LIVE)
            val isQ = qWeatherRepo.configManager.isEnabled && qWeatherRepo.configManager.isConfigured()
            val modeName = if (isQ) "和风地面实况 + 分钟降水雷达" else "Open-Meteo 免费全球数值"
            Toast.makeText(this, "已刷新实时气象 ($modeName)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupQWeatherControls() {
        updateQWeatherUI()

        switchEnableQWeather.setOnCheckedChangeListener { _, isChecked ->
            qWeatherRepo.configManager.isEnabled = isChecked
            qWeatherRepo.resetCircuitBreaker()
            updateQWeatherUI()
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_RESET_LIVE)
            if (isChecked && !qWeatherRepo.configManager.isConfigured()) {
                Toast.makeText(this, "和风服务已开启，请填写 API Key", Toast.LENGTH_SHORT).show()
                showConfigQWeatherDialog()
            } else {
                val modeDesc = if (isChecked) "已开启和风高精气象与雷达" else "已切换为 Open-Meteo 兜底"
                Toast.makeText(this, modeDesc, Toast.LENGTH_SHORT).show()
            }
        }

        btnConfigQWeather.setOnClickListener {
            showConfigQWeatherDialog()
        }

        btnTestQWeather.setOnClickListener {
            val isEnabled = qWeatherRepo.configManager.isEnabled
            val key = qWeatherRepo.configManager.apiKey
            val host = qWeatherRepo.configManager.apiHost

            if (!isEnabled) {
                Toast.makeText(this, "请先打开【启用】开关", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (host.isBlank() || key.isBlank()) {
                Toast.makeText(this, "请先配置 API Host 与 API Key", Toast.LENGTH_SHORT).show()
                showConfigQWeatherDialog()
                return@setOnClickListener
            }
            tvQWeatherBadge.text = "正在握手探测..."
            tvQWeatherBadge.setTextColor(Color.parseColor("#FBBF24"))
            lifecycleScope.launch {
                val lat = gpsManager.currentLatitude
                val lon = gpsManager.currentLongitude
                val res = qWeatherRepo.testConnection(key, host, lat, lon)
                val locDesc = String.format(Locale.US, "📍 定位坐标: %.4f°E, %.4f°N", lon, lat)
                if (res.isSuccess) {
                    Toast.makeText(this@MainActivity, "✅ 握手成功", Toast.LENGTH_SHORT).show()
                    tvQWeatherBadge.text = "🟢 高精模式激活"
                    tvQWeatherBadge.setTextColor(Color.parseColor("#4ADE80"))
                    tvQWeatherDetail.text = "状态: 握手成功\n$locDesc\nHost: $host\n${res.getOrNull()}"
                } else {
                    val err = res.exceptionOrNull()?.message ?: "未知错误"
                    Toast.makeText(this@MainActivity, "❌ 握手失败: $err", Toast.LENGTH_LONG).show()
                    tvQWeatherBadge.text = "🔴 握手失败"
                    tvQWeatherBadge.setTextColor(Color.parseColor("#F87171"))
                    tvQWeatherDetail.text = "状态: 握手失败\n$locDesc\nHost: $host\n错误: $err"
                }
            }
        }

        btnClearQWeather.setOnClickListener {
            qWeatherRepo.configManager.clearConfig()
            qWeatherRepo.resetCircuitBreaker()
            Toast.makeText(this, "已清除配置，恢复纯 Open-Meteo 兜底", Toast.LENGTH_SHORT).show()
            updateQWeatherUI()
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_RESET_LIVE)
        }
    }

    private fun updateQWeatherUI() {
        val isEnabled = qWeatherRepo.configManager.isEnabled
        val configured = qWeatherRepo.configManager.isConfigured()
        val host = qWeatherRepo.configManager.apiHost
        val key = qWeatherRepo.configManager.apiKey
        val maskedKey = if (key.length > 8) "${key.take(4)}****${key.takeLast(4)}" else if (key.isNotEmpty()) "****" else "未配置"
        val displayHost = if (host.isNotEmpty()) host else "未配置"
        val locText = String.format(Locale.US, "📍 当前定位: %.4f°E, %.4f°N", gpsManager.currentLongitude, gpsManager.currentLatitude)

        // 避免触发 OnCheckedChangeListener 递归
        if (switchEnableQWeather.isChecked != isEnabled) {
            switchEnableQWeather.isChecked = isEnabled
        }

        if (!isEnabled) {
            tvQWeatherBadge.text = "⚪ 服务已停用"
            tvQWeatherBadge.setTextColor(Color.parseColor("#94A3B8"))
            tvQWeatherDetail.text = "状态: 已停用 (仅使用 Open-Meteo 免费全球数值预报)\n$locText\nHost: $displayHost\nKey: $maskedKey"
        } else if (!configured) {
            tvQWeatherBadge.text = "🟡 待配置"
            tvQWeatherBadge.setTextColor(Color.parseColor("#FBBF24"))
            val missing = when {
                host.isBlank() && key.isBlank() -> "待填写专属 Host 与 Key"
                host.isBlank() -> "待填写专属 API Host"
                else -> "待填写 API Key"
            }
            tvQWeatherDetail.text = "状态: $missing (点击下方配置按钮)\n$locText\nHost: $displayHost\nKey: $maskedKey"
        } else {
            tvQWeatherBadge.text = "🟢 和风配置就绪"
            tvQWeatherBadge.setTextColor(Color.parseColor("#4ADE80"))
            tvQWeatherDetail.text = "状态: 高精气象服务运行中\n$locText\nHost: $displayHost\nKey: $maskedKey"
        }
    }

    private fun showConfigQWeatherDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvNotice = TextView(this).apply {
            text = "和风天气已全面推行专属域名机制，请在控制台 (console.qweather.com) ->【设置】或【项目凭据】查看专属 API Host 与 Key："
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }

        val tvHost = TextView(this).apply {
            text = "专属 API Host (如 xxxxxx.qweatherapi.com):"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        val currentHost = qWeatherRepo.configManager.apiHost.let {
            if (it == "devapi.qweather.com") "" else it
        }
        val etHost = EditText(this).apply {
            setText(currentHost)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "例如 xxxxxx.qweatherapi.com"
        }

        val tvKey = TextView(this).apply {
            text = "API Key (32 位凭证):"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        val etKey = EditText(this).apply {
            setText(qWeatherRepo.configManager.apiKey)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            hint = "粘贴控制台生成的 32 位 Key"
        }

        layout.addView(tvNotice)
        layout.addView(tvHost)
        layout.addView(etHost)
        layout.addView(tvKey)
        layout.addView(etKey)

        AlertDialog.Builder(this)
            .setTitle("配置和风天气 (Host & Key)")
            .setView(layout)
            .setPositiveButton("保存并测试") { _, _ ->
                val newHost = etHost.text.toString().trim()
                val newKey = etKey.text.toString().trim()
                qWeatherRepo.configManager.saveConfig(newKey, newHost)
                qWeatherRepo.resetCircuitBreaker()
                if (newKey.isNotEmpty() && newHost.isNotEmpty()) {
                    qWeatherRepo.configManager.isEnabled = true
                }
                updateQWeatherUI()
                WeatherOverlayService.send(this, WeatherOverlayService.ACTION_RESET_LIVE)
                btnTestQWeather.performClick()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyCurrentSelection() {
        WeatherOverlayService.simulate(
            context = this,
            timeOfDay = selectedTimeOfDay,
            weatherState = selectedWeatherState,
            windSpeed = if (selectedWeatherState == WeatherState.STORM) 28f else 12f
        )
        Toast.makeText(this, "已切换: [${selectedTimeOfDay.name}] × [${selectedWeatherState.name}]", Toast.LENGTH_SHORT).show()
    }

    private fun highlightSelectedButtons() {
        val activeBg = Color.parseColor("#0284C7")
        val normalBg = Color.parseColor("#1E293B")

        // 高亮时段
        btnTimeDawn.setBackgroundColor(if (selectedTimeOfDay == TimeOfDay.DAWN) activeBg else normalBg)
        btnTimeDay.setBackgroundColor(if (selectedTimeOfDay == TimeOfDay.DAY) activeBg else normalBg)
        btnTimeDusk.setBackgroundColor(if (selectedTimeOfDay == TimeOfDay.DUSK) activeBg else normalBg)
        btnTimeNight.setBackgroundColor(if (selectedTimeOfDay == TimeOfDay.NIGHT) activeBg else normalBg)

        // 高亮天气
        btnWeatherClear.setBackgroundColor(if (selectedWeatherState == WeatherState.CLEAR) activeBg else normalBg)
        btnWeatherCloudy.setBackgroundColor(if (selectedWeatherState == WeatherState.CLOUDY) activeBg else normalBg)
        btnWeatherOvercast.setBackgroundColor(if (selectedWeatherState == WeatherState.OVERCAST) activeBg else normalBg)
        btnWeatherRain.setBackgroundColor(if (selectedWeatherState == WeatherState.RAIN) activeBg else normalBg)
        btnWeatherSnow.setBackgroundColor(if (selectedWeatherState == WeatherState.SNOW) activeBg else normalBg)
        btnWeatherStorm.setBackgroundColor(if (selectedWeatherState == WeatherState.STORM) activeBg else normalBg)
        btnWeatherSleet.setBackgroundColor(if (selectedWeatherState == WeatherState.SLEET) activeBg else normalBg)
    }

    private fun updateDisplayInfo() {
        tvDisplayInfo.text = ThirdDisplayFinder.describe(this)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = Settings.canDrawOverlays(this)
            tvPermissionNotice.visibility = if (hasPermission) View.GONE else View.VISIBLE
        } else {
            tvPermissionNotice.visibility = View.GONE
        }
    }

    private fun ensureOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予【显示在其他应用上层】权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return false
        }
        return true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "打开权限设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==========================================
    // WeatherOverlayService 回调
    // ==========================================
    override fun onOverlayStateChanged(
        isShowing: Boolean,
        mode: WeatherOverlayWindow.Mode?,
        displayDesc: String
    ) {
        runOnUiThread {
            if (isShowing && mode != null) {
                val modeStr = if (mode == WeatherOverlayWindow.Mode.TOP) "小欧上方 (2038)" else "小欧下方 (2037)"
                tvStatusBadge.text = "副屏: 运行中 [$modeStr]"
                tvStatusBadge.setTextColor(getColor(android.R.color.holo_green_light))
            } else {
                tvStatusBadge.text = "副屏: 未启动"
                tvStatusBadge.setTextColor(getColor(android.R.color.darker_gray))
            }
            tvDisplayInfo.text = displayDesc
        }
    }

    override fun onSceneChanged(scene: WeatherSceneConfig) {
        runOnUiThread {
            tvCurrentSceneInfo.text = "当前渲染状态: 时段=${scene.timeOfDay.name} · " +
                    "天气=${scene.weatherDescription} (${scene.temperatureCelsius.toInt()}°C) · " +
                    "风速=${scene.windSpeedKmh.toInt()}km/h · 雨粒子=${scene.rainParticleCount} · 雪粒子=${scene.snowParticleCount}"
        }
    }

    private fun setupGearSimulationControls() {
        tvCurrentGearBadge = findViewById(R.id.tvCurrentGearBadge)
        btnSimGearP = findViewById(R.id.btnSimGearP)
        btnSimGearR = findViewById(R.id.btnSimGearR)
        btnSimGearN = findViewById(R.id.btnSimGearN)
        btnSimGearD = findViewById(R.id.btnSimGearD)
        btnSimGearCombo = findViewById(R.id.btnSimGearCombo)

        btnSimGearP.setOnClickListener { switchGearInternal("P") }
        btnSimGearR.setOnClickListener { switchGearInternal("R") }
        btnSimGearN.setOnClickListener { switchGearInternal("N") }
        btnSimGearD.setOnClickListener { switchGearInternal("D") }

        btnSimGearCombo.setOnClickListener {
            val combo = listOf("P", "R", "N", "D")
            combo.forEachIndexed { index, gear ->
                btnSimGearCombo.postDelayed({
                    switchGearInternal(gear)
                }, index * 450L)
            }
        }
        highlightSelectedGearButton("P")
    }

    private fun switchGearInternal(gear: String) {
        currentSimGear = gear
        highlightSelectedGearButton(gear)
        WeatherOverlayService.switchGear(this, gear)
        Toast.makeText(this, "🏎️ 触发小欧屏档位 3D 翻转: $gear 档", Toast.LENGTH_SHORT).show()
    }

    private fun highlightSelectedGearButton(gear: String) {
        val normalBg = Color.parseColor("#1E293B")
        btnSimGearP.setBackgroundColor(if (gear == "P") Color.parseColor("#D97706") else normalBg)
        btnSimGearR.setBackgroundColor(if (gear == "R") Color.parseColor("#E11D48") else normalBg)
        btnSimGearN.setBackgroundColor(if (gear == "N") Color.parseColor("#64748B") else normalBg)
        btnSimGearD.setBackgroundColor(if (gear == "D") Color.parseColor("#059669") else normalBg)
        tvCurrentGearBadge.text = "当前: $gear 档"
    }

    override fun onGearChanged(gear: String) {
        runOnUiThread {
            highlightSelectedGearButton(gear)
        }
    }

    private fun setupDriveModeSimulationControls() {
        tvCurrentDriveModeBadge = findViewById(R.id.tvCurrentDriveModeBadge)
        btnModeECO = findViewById(R.id.btnModeECO)
        btnModeNORMAL = findViewById(R.id.btnModeNORMAL)
        btnModeSPORT = findViewById(R.id.btnModeSPORT)
        btnModeRACE = findViewById(R.id.btnModeRACE)
        btnModeCUSTOM = findViewById(R.id.btnModeCUSTOM)
        btnModeCombo = findViewById(R.id.btnModeCombo)

        btnModeECO.setOnClickListener { switchDriveModeInternal("ECO") }
        btnModeNORMAL.setOnClickListener { switchDriveModeInternal("NORMAL") }
        btnModeSPORT.setOnClickListener { switchDriveModeInternal("SPORT") }
        btnModeRACE.setOnClickListener { switchDriveModeInternal("RACE") }
        btnModeCUSTOM.setOnClickListener { switchDriveModeInternal("CUSTOM") }

        btnModeCombo.setOnClickListener {
            val combo = listOf("ECO", "NORMAL", "SPORT", "RACE", "CUSTOM")
            combo.forEachIndexed { index, mode ->
                btnModeCombo.postDelayed({
                    switchDriveModeInternal(mode)
                }, index * 1200L)
            }
        }
        highlightSelectedDriveModeButton("SPORT")
    }

    private fun switchDriveModeInternal(mode: String) {
        currentSimDriveMode = mode
        highlightSelectedDriveModeButton(mode)
        WeatherOverlayService.switchDriveMode(this, mode)
        Toast.makeText(this, "🏎️ 触发小欧屏驾驶模式切换: $mode", Toast.LENGTH_SHORT).show()
    }

    private fun highlightSelectedDriveModeButton(mode: String) {
        val normalBg = Color.parseColor("#1E293B")
        btnModeECO.setBackgroundColor(if (mode == "ECO") Color.parseColor("#059669") else normalBg)
        btnModeNORMAL.setBackgroundColor(if (mode == "NORMAL") Color.parseColor("#0284C7") else normalBg)
        btnModeSPORT.setBackgroundColor(if (mode == "SPORT") Color.parseColor("#EA580C") else normalBg)
        btnModeRACE.setBackgroundColor(if (mode == "RACE") Color.parseColor("#DC2626") else normalBg)
        btnModeCUSTOM.setBackgroundColor(if (mode == "CUSTOM") Color.parseColor("#475569") else normalBg)
        tvCurrentDriveModeBadge.text = "当前: $mode"
    }

    override fun onDriveModeChanged(mode: String) {
        runOnUiThread {
            highlightSelectedDriveModeButton(mode)
        }
    }

    private fun setupCallSimulationControls() {
        tvCurrentCallBadge = findViewById(R.id.tvCurrentCallBadge)
        btnCallIncoming = findViewById(R.id.btnCallIncoming)
        btnCallOutgoing = findViewById(R.id.btnCallOutgoing)
        btnCallActive = findViewById(R.id.btnCallActive)
        btnCallHangup = findViewById(R.id.btnCallHangup)
        btnCallWeChatIncoming = findViewById(R.id.btnCallWeChatIncoming)
        btnCallWeChatActive = findViewById(R.id.btnCallWeChatActive)
        btnCallResetIdle = findViewById(R.id.btnCallResetIdle)

        btnCallIncoming.setOnClickListener {
            simulateCallInternal("normal", "ringing", "张总", "13800009527")
        }
        btnCallOutgoing.setOnClickListener {
            simulateCallInternal("normal", "dialing", "李思琪", "13912345678")
        }
        btnCallActive.setOnClickListener {
            simulateCallInternal("normal", "active", "张总", "13800009527")
        }
        btnCallHangup.setOnClickListener {
            simulateCallInternal("normal", "hangup", "张总", "13800009527")
        }
        btnCallWeChatIncoming.setOnClickListener {
            simulateCallInternal("wechat", "ringing", "老婆", "微信语音")
        }
        btnCallWeChatActive.setOnClickListener {
            simulateCallInternal("wechat", "active", "老婆", "微信语音")
        }
        btnCallResetIdle.setOnClickListener {
            simulateCallInternal("normal", "idle", "", "")
        }

        CarCallManager.getInstance(this).addListener(object : CarCallManager.CallListener {
            override fun onCallChanged(callInfo: CarCallManager.CallInfo) {
                runOnUiThread {
                    updateCallBadge(callInfo)
                }
            }
        })
    }

    private fun simulateCallInternal(type: String, state: String, name: String, number: String) {
        WeatherOverlayService.simulateCall(this, type, state, name, number)
        Toast.makeText(this, "📞 触发通话模拟: $type - $state ($name)", Toast.LENGTH_SHORT).show()
    }

    private fun updateCallBadge(callInfo: CarCallManager.CallInfo) {
        val typeLabel = if (callInfo.type == CarCallManager.CallType.WECHAT) "微信" else "普通"
        val stateLabel = when (callInfo.state) {
            CarCallManager.CallState.RINGING -> "来电振铃"
            CarCallManager.CallState.DIALING -> "正在呼出"
            CarCallManager.CallState.ACTIVE -> "通话中(${callInfo.durationSeconds}s)"
            CarCallManager.CallState.TERMINATED -> "已挂断"
            CarCallManager.CallState.IDLE -> "空闲"
        }
        tvCurrentCallBadge.text = "当前: $typeLabel · $stateLabel"
        val badgeColor = when (callInfo.state) {
            CarCallManager.CallState.ACTIVE -> Color.parseColor("#10B981")
            CarCallManager.CallState.RINGING -> Color.parseColor("#F59E0B")
            CarCallManager.CallState.DIALING -> Color.parseColor("#0EA5E9")
            CarCallManager.CallState.TERMINATED -> Color.parseColor("#EF4444")
            CarCallManager.CallState.IDLE -> Color.parseColor("#64748B")
        }
        tvCurrentCallBadge.setTextColor(badgeColor)
    }

    private fun setupLyricsControls() {
        switchStandaloneLyrics.isChecked = PrefsManager.isStandaloneLyricsEnabled()

        switchStandaloneLyrics.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setStandaloneLyricsEnabled(isChecked)
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_CHECK_LYRICS_STATE)

            if (isChecked && !NotificationPermissionHelper.isNotificationListenerGranted(this)) {
                NotificationPermissionHelper.promptNotificationPermission(this)
            }
        }

        btnLyricsSettings.setOnClickListener {
            val intent = Intent(this, LyricsSettingsActivity::class.java)
            startActivity(intent)
        }

        tvLyricsStatus.setOnClickListener {
            if (!NotificationPermissionHelper.isNotificationListenerGranted(this)) {
                NotificationPermissionHelper.promptNotificationPermission(this)
            }
        }
        updateLyricsStatusUI()
    }

    private fun updateLyricsStatusUI() {
        if (!NotificationPermissionHelper.isNotificationListenerGranted(this)) {
            tvLyricsStatus.text = "⚠️ 未获取通知读取权限 (无法识别音乐)，点击前往开启"
            tvLyricsStatus.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            val track = CarLyricsApplication.recognitionEngine.currentTrack.value
            onTrackChanged(track)
        }
    }

    override fun onTrackChanged(track: CurrentTrack?) {
        runOnUiThread {
            if (!NotificationPermissionHelper.isNotificationListenerGranted(this)) {
                tvLyricsStatus.text = "⚠️ 未获取通知读取权限 (无法识别音乐)，点击前往开启"
                tvLyricsStatus.setTextColor(Color.parseColor("#F59E0B"))
                return@runOnUiThread
            }
            tvLyricsStatus.setTextColor(Color.parseColor("#94A3B8"))
            if (track != null && track.title.isNotBlank()) {
                val stateDesc = if (track.isPlaying) "正在播放" else "已暂停 (5s后回退天气)"
                tvLyricsStatus.text = "🎵 $stateDesc: ${track.title} - ${track.artist}"
            } else {
                tvLyricsStatus.text = "🎵 当前音轨: 等待识别音乐... (通知监听已就绪)"
            }
        }
    }

    private fun setupLiquidGlassControls() {
        val isEnabled = PrefsManager.isLiquidGlassClockEnabled()
        switchLiquidGlassClock.isChecked = isEnabled
        updateLiquidGlassStatusUI(isEnabled)

        switchLiquidGlassClock.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setLiquidGlassClockEnabled(isChecked)
            updateLiquidGlassStatusUI(isChecked)
            WeatherOverlayService.updateLiquidGlassState(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "已开启: 中央大时间液态玻璃质感 (28px模糊/170%饱和度/10%白底/折射厚度)" else "已恢复: 经典拟真大气荧光时钟",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateLiquidGlassStatusUI(isEnabled: Boolean) {
        if (isEnabled) {
            tvLiquidGlassStatus.text = "💎 当前时钟质感: 液态玻璃质感 (Blur 28px · Sat 170% · 10% White · Refraction Rim)"
            tvLiquidGlassStatus.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            tvLiquidGlassStatus.text = "⏰ 当前时钟质感: 经典拟真大气荧光 (默认基准)"
            tvLiquidGlassStatus.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun setupBottomLeftGearHudControls() {
        val isEnabled = PrefsManager.isBottomLeftGearHudEnabled()
        switchBottomLeftGearHud.isChecked = isEnabled
        updateBottomLeftGearHudStatusUI(isEnabled)

        switchBottomLeftGearHud.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBottomLeftGearHudEnabled(isChecked)
            updateBottomLeftGearHudStatusUI(isChecked)
            WeatherOverlayService.updateBottomLeftGearHudState(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "已开启: 左下角常驻微型档位 HUD (置顶悬浮 · 液态玻璃)" else "已关闭: 左下角常驻微型档位 HUD",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateBottomLeftGearHudStatusUI(isEnabled: Boolean) {
        if (isEnabled) {
            tvBottomLeftGearHudStatus.text = "🚗 当前状态: 开启 (常驻置顶微型指示 · R/N/D/P 四档平滑滑移)"
            tvBottomLeftGearHudStatus.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            tvBottomLeftGearHudStatus.text = "🚗 当前状态: 已关闭 (左下角不显示)"
            tvBottomLeftGearHudStatus.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gpsManager.stopLocationUpdates()
        WeatherOverlayService.serviceCallback = null
    }
}

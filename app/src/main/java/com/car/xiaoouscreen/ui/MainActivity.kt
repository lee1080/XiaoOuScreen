package com.car.xiaoouscreen.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import com.car.xiaoouscreen.BuildConfig
import com.car.xiaoouscreen.R
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
            Toast.makeText(this, "已恢复实时 Open-Meteo GPS 联网模式", Toast.LENGTH_SHORT).show()
        }
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

    override fun onDestroy() {
        super.onDestroy()
        WeatherOverlayService.serviceCallback = null
    }
}

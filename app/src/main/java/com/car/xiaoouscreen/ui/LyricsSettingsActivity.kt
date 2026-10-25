package com.car.xiaoouscreen.ui

import com.car.xiaoouscreen.service.WeatherOverlayService

import com.carlyrics.*
import com.carlyrics.skin.*
import com.carlyrics.model.*
import com.carlyrics.lyrics.*
import com.carlyrics.util.*
import com.carlyrics.display.*
import com.carlyrics.recognition.*

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import com.carlyrics.util.AppLogger
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carlyrics.display.LyricsDisplayView
import com.carlyrics.lyrics.LyricsApiManager
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.PresetManager
import com.carlyrics.util.LyricsCacheManager
import com.carlyrics.model.LyricLine
import com.carlyrics.recognition.MusicRecognitionEngine
import com.carlyrics.skin.LyricsSkin
import com.carlyrics.skin.SkinFactory
import com.carlyrics.skin.SkinType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsSettingsActivity : AppCompatActivity() {

    private fun syncSettingsToService() {
        try {
            WeatherOverlayService.send(this, WeatherOverlayService.ACTION_UPDATE_LYRICS_SETTINGS)
        } catch (_: Exception) {}
    }


    companion object {
        private const val TAG = "CarLyrics"
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val DEMO_COVER_URL = "demo://preview_cover"
        private const val DEMO_TRACK_TITLE = "See You Again (feat. Charlie Puth)"
        private const val DEMO_TRACK_ARTIST = "Wiz Khalifa / Charlie Puth"
        private const val DEMO_TOTAL_DURATION_MS = 42000L
    }

    private val demoLyricLines: List<LyricLine> by lazy {
        listOf(
            LyricLine(0L, "It's been a long day without you, my friend"),
            LyricLine(4500L, "这句歌词恰好与歌词视口边界只差一个字临界测试"),
            LyricLine(10500L, "And I'll tell you all about it when I see you again, we've come a long way from where we began (超长跑马灯效果自适应演示)"),
            LyricLine(22000L, "Damn, who knew? All the planes we flew, good things we've been through"),
            LyricLine(28000L, "That I'll be standing right here talking to you 'bout another path"),
            LyricLine(34000L, "小欧屏全场景动态渲染与等比例自适应缩放系统")
        )
    }

    private var demoAnimator: ValueAnimator? = null

    private fun startDemoPlayback() {
        stopDemoPlayback()
        demoAnimator = ValueAnimator.ofFloat(0f, DEMO_TOTAL_DURATION_MS.toFloat()).apply {
            duration = DEMO_TOTAL_DURATION_MS
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val progressMs = (anim.animatedValue as Float).toLong()
                try {
                    if (::previewLyricsContainer.isInitialized) {
                        previewLyricsContainer.updateProgress(progressMs)
                    } else {
                        displayView.updateProgress(progressMs)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "demo updateProgress error: ${e.message}", e)
                }
            }
        }
        demoAnimator?.start()
    }

    private fun stopDemoPlayback() {
        demoAnimator?.cancel()
        demoAnimator = null
    }

    private fun pauseDemoPlayback() {
        demoAnimator?.pause()
    }

    private fun resumeDemoPlayback() {
        if (demoAnimator == null) {
            startDemoPlayback()
        } else if (demoAnimator?.isPaused == true) {
            demoAnimator?.resume()
        }
    }

    private fun ensureDemoCoverBitmap() {
        val cached = LyricsCacheManager.getCachedCover(DEMO_COVER_URL)
        if (cached != null && !cached.isRecycled) return

        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 深邃暗夜蓝紫声学渐变
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(
                    Color.parseColor("#FF1E1B4B"),
                    Color.parseColor("#FF0F172A"),
                    Color.parseColor("#FF020617")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // 2. 动感同心光环 (黑胶/声学律动纹理)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#1538BDF8")
            strokeWidth = 2f
        }
        for (r in listOf(100f, 150f, 190f, 220f, 245f)) {
            canvas.drawCircle(size / 2f, size / 2f, r, ringPaint)
        }

        // 3. 中心黑胶圆盘
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0F172A")
            style = Paint.Style.FILL
            setShadowLayer(20f, 0f, 0f, Color.parseColor("#6638BDF8"))
        }
        canvas.drawCircle(size / 2f, size / 2f, 90f, centerPaint)

        // 4. 音符与文字
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 56f
            setShadowLayer(10f, 0f, 4f, Color.parseColor("#80000000"))
        }
        canvas.drawText("♫", size / 2f, size / 2f + 18f, textPaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E038BDF8")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 28f
            letterSpacing = 0.15f
        }
        canvas.drawText("XIAO OU HI-FI", size / 2f, size / 2f + 160f, brandPaint)

        val subBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            textSize = 18f
            letterSpacing = 0.2f
        }
        canvas.drawText("STUDIO MASTER", size / 2f, size / 2f + 195f, subBrandPaint)

        LyricsCacheManager.cacheCover(DEMO_COVER_URL, bitmap)
    }

    private fun bindDemoDataToPreview() {
        try {
            ensureDemoCoverBitmap()
            if (::previewLyricsContainer.isInitialized) {
                previewLyricsContainer.setTrackInfo(DEMO_TRACK_TITLE, DEMO_TRACK_ARTIST)
                previewLyricsContainer.setCoverUrl(DEMO_COVER_URL)
                previewLyricsContainer.setLyrics(demoLyricLines)
                val currentMs = (demoAnimator?.animatedValue as? Float)?.toLong() ?: 0L
                previewLyricsContainer.updateProgress(currentMs)
            } else {
                displayView.setTrackInfo(DEMO_TRACK_TITLE, DEMO_TRACK_ARTIST)
                displayView.setCoverUrl(DEMO_COVER_URL)
                displayView.setLyrics(demoLyricLines)
                val currentMs = (demoAnimator?.animatedValue as? Float)?.toLong() ?: 0L
                displayView.updateProgress(currentMs)
            }
            if (demoAnimator == null || !demoAnimator!!.isRunning) {
                startDemoPlayback()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "bindDemoDataToPreview error: ${e.message}", e)
        }
    }

    private lateinit var lyricsApi: LyricsApiManager
    

    private val recognitionEngine: MusicRecognitionEngine
        get() = CarLyricsApplication.recognitionEngine

    // UI
    private lateinit var displayView: LyricsSkin
    private lateinit var previewLyricsContainer: com.carlyrics.display.LyricsNormalizedContainerView
    private var aspectPreviewFrame: com.carlyrics.display.AspectPreviewFrame? = null
    private lateinit var screenSpinner: Spinner
    private lateinit var skinSpinner: Spinner
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var btnApplySize: Button
    private lateinit var btnRefresh: Button
    private lateinit var statusText: TextView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var fontSizeLabel: TextView
    private lateinit var offsetSeekBar: SeekBar
    private lateinit var offsetLabel: TextView
    private lateinit var splitRatioSeekBar: SeekBar
    private lateinit var splitRatioLabel: TextView
    private lateinit var timeOffsetSeekBar: SeekBar
    private lateinit var timeOffsetLabel: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var displayScaleSeekBar: SeekBar
    private lateinit var displayScaleLabel: TextView
    private lateinit var infoFontScaleSeekBar: SeekBar
    private lateinit var infoFontScaleLabel: TextView
    private lateinit var cacheSizeSeekBar: SeekBar
    private lateinit var cacheSizeLabel: TextView
    private lateinit var cacheCurrentSizeLabel: TextView
    private var lastSelectedDisplayPosition: Int = -1

    private var lyricsAlignmentCardWide: View? = null
    private var alignGroupWide: RadioGroup? = null
    private var lyricsAlignmentCardNarrow: View? = null
    private var alignGroupNarrow: RadioGroup? = null

    // 防止 buildUI 初始化过程中 onItemSelected 触发 switchSkin 覆写 PrefsManager
    private var isUIInitializing = true

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // overlayService removed
    private var lastTrackTitle: String = ""
    private var lastTrackArtist: String = ""
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var coverUrl: String? = null
    private var currentSkinType: SkinType = SkinType.DEFAULT
    private var currentFontSizeSp: Float = LyricsDisplayView.DEFAULT_FONT_SIZE_SP
    private var currentHorizontalOffset: Float = 0f
    private var currentSplitRatio: Float = 0.35f
    private var currentLyricsAlignment: Int = android.view.Gravity.START
    private var currentMarqueeEnabled: Boolean = true
    private var currentDpiScale: Float = 1.0f
    private var currentInfoFontScale: Float = 1.0f
    private var currentXiaoOuAvoidanceEnabled: Boolean = true
    private var targetDisplayId: Int = 0
    
    // 进度插值
    private var lastKnownPosition: Long = 0L
    private var lastPositionNanos: Long = 0L
    private var fetchRetryCount = 0
    private var fetchJob: Job? = null
    private var preloadTriggered = false
    private var presetSpinnerWide: Spinner? = null
    private var presetSpinnerNarrow: Spinner? = null
    private var preferredSourceSpinnerWide: Spinner? = null
    private var preferredSourceSpinnerNarrow: Spinner? = null
    private var pendingExportJson: String? = null

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonStr = inputStream.bufferedReader().use { it.readText() }
                    if (PresetManager.applyPresetJson(jsonStr)) {
                        reloadSettingsFromPreset()
                        Toast.makeText(this, "成功从文件导入预设！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "无效的预设 JSON 文件", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val jsonToWrite = pendingExportJson ?: return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonToWrite.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "预设导出成功！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出写入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var sourcesStatusTextViewWide: TextView? = null
    private var sourcesStatusTextViewNarrow: TextView? = null
    private var preferredSourceOptions: List<Pair<String, String>> = emptyList()
    private var sourceFileImportMode: String = ""
    private var pendingCollectionCallback: ((String) -> Unit)? = null
    private var pendingSingleSourceCallback: ((String) -> Unit)? = null
    private var currentImportCollectionDialog: AlertDialog? = null

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "archive.zip"
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank()) {
                            name = displayName
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "getFileNameFromUri error: ${e.message}")
            }
        } else if (uri.scheme == "file") {
            uri.lastPathSegment?.let { if (it.isNotBlank()) name = it }
        }
        return name
    }

    private fun isZipFile(uri: Uri, fileName: String): Boolean {
        if (fileName.endsWith(".zip", ignoreCase = true)) return true
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(4)
                val read = stream.read(header)
                read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isZipFile(file: java.io.File): Boolean {
        if (file.name.endsWith(".zip", ignoreCase = true)) return true
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                val read = stream.read(header)
                read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
            }
        } catch (e: Exception) {
            false
        }
    }

    private val sourceFileImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val fileName = getFileNameFromUri(uri)

            if (sourceFileImportMode == "collection") {
                lifecycleScope.launch {
                    val isZip = isZipFile(uri, fileName)
                    if (isZip) {
                        Toast.makeText(this@LyricsSettingsActivity, "正在解压并解析 ZIP 歌词源...", Toast.LENGTH_SHORT).show()
                        try {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val parseRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionFromZip(stream, fileName)
                                parseRes.onSuccess { collection ->
                                    currentImportCollectionDialog?.dismiss()
                                    currentImportCollectionDialog = null
                                    showCollectionSelectDialog(collection)
                                }
                                parseRes.onFailure { e ->
                                    Toast.makeText(this@LyricsSettingsActivity, "ZIP 解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@LyricsSettingsActivity, "读取 ZIP 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        try {
                            contentResolver.openInputStream(uri)?.use { inputStream ->
                                val text = inputStream.bufferedReader().use { it.readText() }
                                pendingCollectionCallback?.invoke(text)
                                val jsonRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionJson(text)
                                if (jsonRes.isSuccess) {
                                    currentImportCollectionDialog?.dismiss()
                                    currentImportCollectionDialog = null
                                    showCollectionSelectDialog(jsonRes.getOrThrow())
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@LyricsSettingsActivity, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val text = inputStream.bufferedReader().use { it.readText() }
                        pendingSingleSourceCallback?.invoke(text)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@LyricsSettingsActivity, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = 0xFFF2F2F7.toInt()
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        lyricsApi = LyricsApiManager()
        
        

        // 从 SharedPreferences 恢复所有持久化设置
        PrefsManager.init(this)
        PresetManager.init(this)
        LyricsCacheManager.init(this)
        currentSkinType = PrefsManager.getSkinType()
        currentFontSizeSp = PrefsManager.getFontSizeSp()
        currentHorizontalOffset = PrefsManager.getHorizontalOffset()
        currentSplitRatio = PrefsManager.getSplitRatio()
        currentLyricsAlignment = PrefsManager.getLyricsAlignment()
        currentMarqueeEnabled = PrefsManager.isMarqueeEnabled()
        currentDpiScale = PrefsManager.getDpiScale()
        currentInfoFontScale = PrefsManager.getInfoFontScale()
        currentXiaoOuAvoidanceEnabled = PrefsManager.isXiaoOuAvoidanceEnabled()
        targetDisplayId = PrefsManager.getTargetDisplayId()
        LyricsApiManager.LYRICS_API_URL = PrefsManager.getLyricsApiUrl()
        LyricsApiManager.COVER_API_URL = PrefsManager.getCoverApiUrl()
        LyricsApiManager.API_KEY = PrefsManager.getApiKey()

        buildUI()
        isUIInitializing = false  // UI 初始化完成，后续 onItemSelected 正常响应
        refreshSourcesStatusText()

        // 恢复歌词时间偏移
        displayView.lyricsOffsetMs = PrefsManager.lyricsOffsetMs

// 悬浮窗统一由 WeatherOverlayService 托管



        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    recognitionEngine.currentTrack.collectLatest { track ->
                        if (track != null) {
                            onTrackChanged(track.title, track.artist, track.position, track.duration)
                        } else {
                            updateStatusLine()
                        }
                    }
                }
                launch {
                    while (isActive) {
                        delay(3000)
                        if (recognitionEngine.currentTrack.value == null) {
                            runOnUiThread { updateStatusLine() }
                        }
                    }
                }
            }
        }

// 权限与保活由主程序托管
    }

    // ==================== UI ====================

    private fun buildUI() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFFF2F2F7.toInt())
        }

        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 顶部固定视窗容器 (Header + 1920x384 黄金比例等比缩小预览视窗)
        val topFixedBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
        }

        // 顶栏 (iOS Style Header Light)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        val btnBack = TextView(this).apply {
            text = "‹ 返回  "
            setTextColor(0xFF007AFF.toInt())
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        }
        val deviceIcon = TextView(this).apply {
            text = "\uD83C\uDFB5"
            textSize = 20f
        }
        val headerTitle = TextView(this).apply {
            text = "  车载歌词设置"
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 18f
            letterSpacing = -0.01f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerSpacer = View(this)
        val sandboxBadge = TextView(this).apply {
            text = "● 1920×384 黄金比例实况"
            setTextColor(0xFF007AFF.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(0x14007AFF)
                cornerRadius = dp(12).toFloat()
            }
        }
        header.addView(btnBack)
        header.addView(deviceIcon)
        header.addView(headerTitle)
        header.addView(headerSpacer, LinearLayout.LayoutParams(0, 0, 1f))
        header.addView(sandboxBadge)
        topFixedBar.addView(header)

        // 预览窗口外壳容器 (汽车座舱哑光黑边框)
        val previewCardWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }

        val previewCard = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                // 严格锁定 1920:384 (5:1) 小欧副屏黄金物理比例，保证预览与真实副屏及悬浮窗绝对像素级 1:1 所见即所得
                val desiredHeight = if (width > 0) (width / 5.0f).toInt() else dp(200)
                val newHeightSpec = MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY)
                super.onMeasure(widthMeasureSpec, newHeightSpec)
            }
        }.apply {
            background = GradientDrawable().apply {
                setColor(0xFF161618.toInt()) // 哑光座舱黑
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), 0x2EFFFFFF.toInt())
            }
            clipToOutline = true
            elevation = dp(4).toFloat()
        }

        previewLyricsContainer = com.carlyrics.display.LyricsNormalizedContainerView(this).apply {
            id = View.generateViewId()
            showAvatarIndicator = true // 仅在设置预览中开启形象示意图
            xiaoOuAvoidanceEnabled = currentXiaoOuAvoidanceEnabled
        }
        displayView = previewLyricsContainer.getSkin()!!

        // 充满 5:1 严格比例卡片，实现全分辨率完全等比自适应矩阵缩放
        previewCard.addView(previewLyricsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        previewCardWrapper.addView(previewCard)
        topFixedBar.addView(previewCardWrapper)

        // 底部细致分割线 (iOS subtle divider)
        val bottomDivider = View(this).apply {
            setBackgroundColor(0x14000000)
        }
        topFixedBar.addView(bottomDivider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ))

        // 动态同步滚动容器的内边距，实现下方设置往上滑动直接跑到预览窗口下方去
        topFixedBar.post {
            val barHeight = topFixedBar.height
            if (barHeight > 0) {
                scrollView.setPadding(0, barHeight, 0, dp(40))
            }
        }
        scrollView.setPadding(0, dp(280), 0, dp(40))

        // --- 控制面板（宽屏两列 / 窄屏单列）---
        val isWide = resources.configuration.screenWidthDp >= 600
        val timeOffsetMs = PrefsManager.lyricsOffsetMs
        val currentCacheMb = LyricsCacheManager.getMaxSizeMb().toInt()
        val currentCacheUsed = LyricsCacheManager.getCacheSize()

        if (isWide) {
            // 宽屏：左右两列
            val columnsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(4), dp(16), dp(32))
            }

            val leftColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rightColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // ---- 第一列：配置预设管理、歌词主题皮肤、超长歌词跑马灯、歌词文本排版、左右面板分割比例、封面面板缩放、歌词字体大小 ----
            leftColumn.addView(spacerV(4))
            leftColumn.addView(createPresetCardView(isWide = true))
            leftColumn.addView(spacerV(12))

            leftColumn.addView(cardView("歌词皮肤主题") {
                skinSpinner = Spinner(this@LyricsSettingsActivity).apply {
                    val skinLabels = SkinType.entries.map { it.label }
                    adapter = createIosSpinnerAdapter(skinLabels)
                    applyIosSpinnerStyle(this)
                    setSelection(currentSkinType.ordinal)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            if (isUIInitializing) return
                            val newType = SkinType.entries[pos]
                            if (newType != currentSkinType) {
                                switchSkin(newType)
                            }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                addView(skinSpinner)
            })
            leftColumn.addView(spacerV(12))

            leftColumn.addView(cardView("超长歌词跑马灯") {
                val marqueeRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val marqueeSwitch = Switch(this@LyricsSettingsActivity).apply {
                    text = "开启超长歌词跑马灯"
                    applyIosSwitchStyle(this)
                    isChecked = currentMarqueeEnabled
                    setOnCheckedChangeListener { _, enabled ->
                        currentMarqueeEnabled = enabled
                        PrefsManager.setMarqueeEnabled(enabled)
                        displayView.marqueeEnabled = enabled
                        syncSettingsToService()
                        updateLyricsAlignmentCardState(enabled)
                    }
                }
                marqueeRow.addView(marqueeSwitch)
                addView(marqueeRow)
            })
            leftColumn.addView(spacerV(12))

            val alignCardW = cardView("歌词文本排版") {
                val alignRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                alignGroupWide = RadioGroup(this@LyricsSettingsActivity).apply {
                    orientation = RadioGroup.HORIZONTAL
                    val checkedId = when (currentLyricsAlignment) {
                        android.view.Gravity.START -> 1; android.view.Gravity.END -> 3; else -> 2
                    }
                    val radioTint = ColorStateList.valueOf(0xFF007AFF.toInt())
                    listOf("左对齐" to 1, "居中" to 2, "右对齐" to 3).forEach { (label, id) ->
                        addView(RadioButton(this@LyricsSettingsActivity).apply {
                            text = label; setId(id); setTextColor(0xFF1C1C1E.toInt())
                            buttonTintList = radioTint
                            if (id == checkedId) isChecked = true
                        }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    setOnCheckedChangeListener { _, id ->
                        val alignment = when (id) {
                            1 -> android.view.Gravity.START
                            3 -> android.view.Gravity.END
                            else -> android.view.Gravity.CENTER
                        }
                        currentLyricsAlignment = alignment
                        PrefsManager.setLyricsAlignment(alignment)
                        displayView.lyricsTextAlignment = alignment
                        syncSettingsToService()
                    }
                }
                alignRow.addView(alignGroupWide, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(alignRow)
            }
            lyricsAlignmentCardWide = alignCardW
            leftColumn.addView(alignCardW)
            leftColumn.addView(spacerV(12))

            leftColumn.addView(cardView("左右面板分割比例") {
                val srRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                splitRatioSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 60
                    progress = ((currentSplitRatio - 0.2f) / 0.6f * 60f).toInt().coerceIn(0, 60)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val ratio = (0.2f + progress / 60f * 0.6f).coerceIn(0.2f, 0.8f)
                            splitRatioLabel.text = "%.0f%%".format(ratio * 100)
                            displayView.splitRatio = ratio
                            if (fromUser) {
                                currentSplitRatio = ratio
                                PrefsManager.setSplitRatio(ratio)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                splitRatioLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "%.0f%%".format(currentSplitRatio * 100)
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                srRow.addView(splitRatioSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                srRow.addView(splitRatioLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(srRow)
            })
            leftColumn.addView(spacerV(12))

            leftColumn.addView(cardView("封面面板缩放") {
                val dsTitle = TextView(this@LyricsSettingsActivity).apply {
                    text = "封面与整体缩放"
                    setTextColor(0xFF6C6C70.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 0, 0, dp(4))
                }
                val dsRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                displayScaleSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 30
                    progress = ((currentDpiScale - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val scale = 0.5f + progress * 0.05f
                            displayScaleLabel.text = "${"%.0f".format(scale * 100)}%"
                            displayView.scaleFactor = scale
                            if (fromUser) {
                                currentDpiScale = scale
                                PrefsManager.setDpiScale(scale)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                displayScaleLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${"%.0f".format(currentDpiScale * 100)}%"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                dsRow.addView(displayScaleSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                dsRow.addView(displayScaleLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val ifTitle = TextView(this@LyricsSettingsActivity).apply {
                    text = "歌名与歌手字号"
                    setTextColor(0xFF6C6C70.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(10), 0, dp(4))
                }
                val ifRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                infoFontScaleSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 50
                    progress = ((currentInfoFontScale - 0.5f) / 0.05f).toInt().coerceIn(0, 50)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val scale = 0.5f + progress * 0.05f
                            infoFontScaleLabel.text = "${"%.2f".format(scale)}x"
                            displayView.infoFontScale = scale
                            if (fromUser) {
                                currentInfoFontScale = scale
                                PrefsManager.setInfoFontScale(scale)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                infoFontScaleLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${"%.2f".format(currentInfoFontScale)}x"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                ifRow.addView(infoFontScaleSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                ifRow.addView(infoFontScaleLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                addView(dsTitle)
                addView(dsRow)
                addView(ifTitle)
                addView(ifRow)
            })
            leftColumn.addView(spacerV(12))

            leftColumn.addView(cardView("歌词字体大小") {
                val fsRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fontSizeSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = (LyricsDisplayView.MAX_FONT_SIZE_SP - LyricsDisplayView.MIN_FONT_SIZE_SP).toInt()
                    progress = (currentFontSizeSp - LyricsDisplayView.MIN_FONT_SIZE_SP).toInt().coerceIn(0, max)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val sp = LyricsDisplayView.MIN_FONT_SIZE_SP + progress
                            fontSizeLabel.text = "${sp.toInt()} sp"
                            displayView.fontSizeSp = sp
                            if (fromUser) {
                                currentFontSizeSp = sp
                                PrefsManager.setFontSizeSp(sp)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                fontSizeLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${currentFontSizeSp.toInt()} sp"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                fsRow.addView(fontSizeSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                fsRow.addView(fontSizeLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(fsRow)
            })

            // ---- 第二列：手动识别音乐、歌词源管理、偏好源、歌词时间微调、自定义歌词API、自定义封面API、API鉴权密钥、小欧3D形象避让 ----
            rightColumn.addView(spacerV(4))
            rightColumn.addView(cardView("手动识别音乐") {
                statusText = TextView(this@LyricsSettingsActivity).apply {
                    text = "  等待识别音乐..."
                    setTextColor(0xFF8E8E93.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(10))
                }
                btnRefresh = styledButton("手动识别当前音乐", isPrimary = true) {
                    recognitionEngine.forceRefresh()
                    val info = recognitionEngine.lastRefreshInfo
                    val msg = if (info.isNotEmpty()) "正在扫描... " else "正在重新扫描音乐..."
                    Toast.makeText(this@LyricsSettingsActivity, msg, Toast.LENGTH_SHORT).show()
                    updateStatusLine()
                }
                addView(statusText)
                addView(btnRefresh)
            })
            rightColumn.addView(spacerV(12))

            rightColumn.addView(createLyricsSourcesCard(isWide = true))
            rightColumn.addView(spacerV(12))

            rightColumn.addView(createPreferredSourceCardView(isWide = true))
            rightColumn.addView(spacerV(12))

            rightColumn.addView(cardView("歌词时间微调 (Offset)") {
                val toRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                timeOffsetLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = if (timeOffsetMs == 0L) "0 ms" else if (timeOffsetMs > 0) "+${timeOffsetMs}ms" else "${timeOffsetMs}ms"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                timeOffsetSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 40
                    progress = ((timeOffsetMs / 500) + 20).toInt().coerceIn(0, 40)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val ms = (progress - 20) * 500L
                            timeOffsetLabel.text = if (ms == 0L) "0 ms" else if (ms > 0) "+${ms}ms" else "${ms}ms"
                            displayView.lyricsOffsetMs = ms
                            if (fromUser) PrefsManager.lyricsOffsetMs = ms
                            syncSettingsToService()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                toRow.addView(timeOffsetSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                toRow.addView(timeOffsetLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(toRow)
            })
            rightColumn.addView(spacerV(12))

            rightColumn.addView(cardView("自定义歌词 API") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: https://api.example.com/lrc"
                    setText(PrefsManager.getLyricsApiUrl().ifEmpty { LyricsApiManager.LYRICS_API_URL })
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val u = text.toString().trim()
                        LyricsApiManager.LYRICS_API_URL = u
                        PrefsManager.setLyricsApiUrl(u)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            rightColumn.addView(spacerV(12))

            rightColumn.addView(cardView("自定义封面 API") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: https://api.example.com/cover"
                    setText(PrefsManager.getCoverApiUrl().ifEmpty { LyricsApiManager.COVER_API_URL })
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val u = text.toString().trim()
                        LyricsApiManager.COVER_API_URL = u
                        PrefsManager.setCoverApiUrl(u)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            rightColumn.addView(spacerV(12))

            rightColumn.addView(cardView("API 鉴权密钥 (API Key)") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: my_secret_token_123"
                    setText(PrefsManager.getApiKey().ifEmpty { LyricsApiManager.API_KEY })
                    inputType = EditorInfo.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val k = text.toString().trim()
                        LyricsApiManager.API_KEY = k
                        PrefsManager.setApiKey(k)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            rightColumn.addView(spacerV(12))

            rightColumn.addView(cardView("小欧 3D 形象避让 (500px)") {
                val xiaoOuRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val xiaoOuSwitch = Switch(this@LyricsSettingsActivity).apply {
                    text = "开启 500px 小欧 3D 形象避让 (全屏/悬浮窗同步)"
                    applyIosSwitchStyle(this)
                    isChecked = currentXiaoOuAvoidanceEnabled
                    setOnCheckedChangeListener { _, enabled ->
                        currentXiaoOuAvoidanceEnabled = enabled
                        PrefsManager.setXiaoOuAvoidanceEnabled(enabled)
                        if (::previewLyricsContainer.isInitialized) {
                            previewLyricsContainer.xiaoOuAvoidanceEnabled = enabled
                            displayView = previewLyricsContainer.getSkin() ?: displayView
                        } else {
                            displayView.xiaoOuAvoidanceEnabled = enabled
                        }
                        syncSettingsToService()
                    }
                }
                xiaoOuRow.addView(xiaoOuSwitch)
                addView(xiaoOuRow)
            })
            
            columnsRow.addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            columnsRow.addView(spacerH(12))
            columnsRow.addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rootLayout.addView(columnsRow)


        } else {
            // 窄屏：单列
            val controlPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(32))
            }
            // ---- 单列全量卡片（与两列顺序完全一致）----
            controlPanel.addView(createPresetCardView(isWide = false))
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("歌词皮肤主题") {
                skinSpinner = Spinner(this@LyricsSettingsActivity).apply {
                    val skinLabels = SkinType.entries.map { it.label }
                    adapter = createIosSpinnerAdapter(skinLabels)
                    applyIosSpinnerStyle(this)
                    setSelection(currentSkinType.ordinal)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            if (isUIInitializing) return
                            val newType = SkinType.entries[pos]
                            if (newType != currentSkinType) {
                                switchSkin(newType)
                            }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                addView(skinSpinner)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("超长歌词跑马灯") {
                val marqueeRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val marqueeSwitch = Switch(this@LyricsSettingsActivity).apply {
                    text = "开启超长歌词跑马灯"
                    applyIosSwitchStyle(this)
                    isChecked = currentMarqueeEnabled
                    setOnCheckedChangeListener { _, enabled ->
                        currentMarqueeEnabled = enabled
                        PrefsManager.setMarqueeEnabled(enabled)
                        displayView.marqueeEnabled = enabled
                        syncSettingsToService()
                        updateLyricsAlignmentCardState(enabled)
                    }
                }
                marqueeRow.addView(marqueeSwitch)
                addView(marqueeRow)
            })
            controlPanel.addView(spacerV(12))

            val alignCardN = cardView("歌词文本排版") {
                val alignRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                alignGroupNarrow = RadioGroup(this@LyricsSettingsActivity).apply {
                    orientation = RadioGroup.HORIZONTAL
                    val checkedId = when (currentLyricsAlignment) {
                        android.view.Gravity.START -> 1; android.view.Gravity.END -> 3; else -> 2
                    }
                    val radioTint = ColorStateList.valueOf(0xFF007AFF.toInt())
                    listOf("左对齐" to 1, "居中" to 2, "右对齐" to 3).forEach { (label, id) ->
                        addView(RadioButton(this@LyricsSettingsActivity).apply {
                            text = label; setId(id); setTextColor(0xFF1C1C1E.toInt())
                            buttonTintList = radioTint
                            if (id == checkedId) isChecked = true
                        }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    setOnCheckedChangeListener { _, id ->
                        val alignment = when (id) {
                            1 -> android.view.Gravity.START
                            3 -> android.view.Gravity.END
                            else -> android.view.Gravity.CENTER
                        }
                        currentLyricsAlignment = alignment
                        PrefsManager.setLyricsAlignment(alignment)
                        displayView.lyricsTextAlignment = alignment
                        syncSettingsToService()
                    }
                }
                alignRow.addView(alignGroupNarrow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(alignRow)
            }
            lyricsAlignmentCardNarrow = alignCardN
            controlPanel.addView(alignCardN)
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("左右面板分割比例") {
                val srRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                splitRatioSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 60
                    progress = ((currentSplitRatio - 0.2f) / 0.6f * 60f).toInt().coerceIn(0, 60)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val ratio = (0.2f + progress / 60f * 0.6f).coerceIn(0.2f, 0.8f)
                            splitRatioLabel.text = "%.0f%%".format(ratio * 100)
                            displayView.splitRatio = ratio
                            if (fromUser) {
                                currentSplitRatio = ratio
                                PrefsManager.setSplitRatio(ratio)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                splitRatioLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "%.0f%%".format(currentSplitRatio * 100)
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                srRow.addView(splitRatioSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                srRow.addView(splitRatioLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(srRow)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("封面面板缩放") {
                val dsTitle = TextView(this@LyricsSettingsActivity).apply {
                    text = "封面与整体缩放"
                    setTextColor(0xFF6C6C70.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 0, 0, dp(4))
                }
                val dsRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                displayScaleSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 30
                    progress = ((currentDpiScale - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val scale = 0.5f + progress * 0.05f
                            displayScaleLabel.text = "${"%.0f".format(scale * 100)}%"
                            displayView.scaleFactor = scale
                            if (fromUser) {
                                currentDpiScale = scale
                                PrefsManager.setDpiScale(scale)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                displayScaleLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${"%.0f".format(currentDpiScale * 100)}%"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                dsRow.addView(displayScaleSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                dsRow.addView(displayScaleLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val ifTitle = TextView(this@LyricsSettingsActivity).apply {
                    text = "歌名与歌手字号"
                    setTextColor(0xFF6C6C70.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(10), 0, dp(4))
                }
                val ifRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                infoFontScaleSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 50
                    progress = ((currentInfoFontScale - 0.5f) / 0.05f).toInt().coerceIn(0, 50)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val scale = 0.5f + progress * 0.05f
                            infoFontScaleLabel.text = "${"%.2f".format(scale)}x"
                            displayView.infoFontScale = scale
                            if (fromUser) {
                                currentInfoFontScale = scale
                                PrefsManager.setInfoFontScale(scale)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                infoFontScaleLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${"%.2f".format(currentInfoFontScale)}x"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                ifRow.addView(infoFontScaleSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                ifRow.addView(infoFontScaleLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                addView(dsTitle)
                addView(dsRow)
                addView(ifTitle)
                addView(ifRow)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("歌词字体大小") {
                val fsRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fontSizeSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = (LyricsDisplayView.MAX_FONT_SIZE_SP - LyricsDisplayView.MIN_FONT_SIZE_SP).toInt()
                    progress = (currentFontSizeSp - LyricsDisplayView.MIN_FONT_SIZE_SP).toInt().coerceIn(0, max)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val sp = LyricsDisplayView.MIN_FONT_SIZE_SP + progress
                            fontSizeLabel.text = "${sp.toInt()} sp"
                            displayView.fontSizeSp = sp
                            if (fromUser) {
                                currentFontSizeSp = sp
                                PrefsManager.setFontSizeSp(sp)
                                syncSettingsToService()
                            }
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                fontSizeLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = "${currentFontSizeSp.toInt()} sp"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                fsRow.addView(fontSizeSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                fsRow.addView(fontSizeLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(fsRow)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("手动识别音乐") {
                statusText = TextView(this@LyricsSettingsActivity).apply {
                    text = "  等待识别音乐..."
                    setTextColor(0xFF8E8E93.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(10))
                }
                btnRefresh = styledButton("手动识别当前音乐", isPrimary = true) {
                    recognitionEngine.forceRefresh()
                    val info = recognitionEngine.lastRefreshInfo
                    val msg = if (info.isNotEmpty()) "正在扫描... " else "正在重新扫描音乐..."
                    Toast.makeText(this@LyricsSettingsActivity, msg, Toast.LENGTH_SHORT).show()
                    updateStatusLine()
                }
                addView(statusText)
                addView(btnRefresh)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(createLyricsSourcesCard(isWide = false))
            controlPanel.addView(spacerV(12))

            controlPanel.addView(createPreferredSourceCardView(isWide = false))
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("歌词时间微调 (Offset)") {
                val toRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                timeOffsetLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = if (timeOffsetMs == 0L) "0 ms" else if (timeOffsetMs > 0) "+${timeOffsetMs}ms" else "${timeOffsetMs}ms"
                    setTextColor(0xFF007AFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, 0)
                }
                timeOffsetSeekBar = SeekBar(this@LyricsSettingsActivity).apply {
                    max = 40
                    progress = ((timeOffsetMs / 500) + 20).toInt().coerceIn(0, 40)
                    applyIosSeekBarStyle(this)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            val ms = (progress - 20) * 500L
                            timeOffsetLabel.text = if (ms == 0L) "0 ms" else if (ms > 0) "+${ms}ms" else "${ms}ms"
                            displayView.lyricsOffsetMs = ms
                            if (fromUser) PrefsManager.lyricsOffsetMs = ms
                            syncSettingsToService()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                toRow.addView(timeOffsetSeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                toRow.addView(timeOffsetLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(toRow)
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("自定义歌词 API") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: https://api.example.com/lrc"
                    setText(PrefsManager.getLyricsApiUrl().ifEmpty { LyricsApiManager.LYRICS_API_URL })
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val u = text.toString().trim()
                        LyricsApiManager.LYRICS_API_URL = u
                        PrefsManager.setLyricsApiUrl(u)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("自定义封面 API") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: https://api.example.com/cover"
                    setText(PrefsManager.getCoverApiUrl().ifEmpty { LyricsApiManager.COVER_API_URL })
                    inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val u = text.toString().trim()
                        LyricsApiManager.COVER_API_URL = u
                        PrefsManager.setCoverApiUrl(u)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("API 鉴权密钥 (API Key)") {
                addView(EditText(this@LyricsSettingsActivity).apply {
                    hint = "例如: my_secret_token_123"
                    setText(PrefsManager.getApiKey().ifEmpty { LyricsApiManager.API_KEY })
                    inputType = EditorInfo.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    styleIosInput(this)
                    val save = {
                        val k = text.toString().trim()
                        LyricsApiManager.API_KEY = k
                        PrefsManager.setApiKey(k)
                    }
                    setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save() }; false }
                    setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save() }
                })
            })
            controlPanel.addView(spacerV(12))

            controlPanel.addView(cardView("小欧 3D 形象避让 (500px)") {
                val xiaoOuRow = LinearLayout(this@LyricsSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val xiaoOuSwitch = Switch(this@LyricsSettingsActivity).apply {
                    text = "开启 500px 小欧 3D 形象避让 (全屏/悬浮窗同步)"
                    applyIosSwitchStyle(this)
                    isChecked = currentXiaoOuAvoidanceEnabled
                    setOnCheckedChangeListener { _, enabled ->
                        currentXiaoOuAvoidanceEnabled = enabled
                        PrefsManager.setXiaoOuAvoidanceEnabled(enabled)
                        if (::previewLyricsContainer.isInitialized) {
                            previewLyricsContainer.xiaoOuAvoidanceEnabled = enabled
                            displayView = previewLyricsContainer.getSkin() ?: displayView
                        } else {
                            displayView.xiaoOuAvoidanceEnabled = enabled
                        }
                        syncSettingsToService()
                    }
                }
                xiaoOuRow.addView(xiaoOuSwitch)
                addView(xiaoOuRow)
            })
            controlPanel.addView(spacerV(12))
            
            rootLayout.addView(controlPanel)
        }

        updateLyricsAlignmentCardState(currentMarqueeEnabled)

        scrollView.addView(rootLayout)
        rootFrame.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootFrame.addView(topFixedBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(rootFrame)

        updatePreviewBounds()
        syncSkinUIParams()
        bindDemoDataToPreview()
    }

    // 统一获取 overlay service，优先 bind 回调，兜底取单例
    private fun getOverlayService(): Any? = null

    private fun launchClusterAdjust() {}
    private fun setupServiceListeners() {}

    private fun onTrackChanged(title: String, artist: String, position: Long, duration: Long) {
        try {
            runOnUiThread {
                val src = recognitionEngine.lastDetectionSource
                statusText.text = "  识别到: $title - $artist  |  来源: $src"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "CrashGuard onTrackChanged UI: ${e.message}", e)
        }

        if (title != lastTrackTitle || artist != lastTrackArtist) {
            lastTrackTitle = title
            lastTrackArtist = artist
            preloadTriggered = false  // 重置预加载标志
        }

        val interpolated = interpolatePosition(position)
        // 【优化1A】当播放进度超过 80% 时，预加载下一曲
        if (!preloadTriggered && duration > 0 && interpolated > duration * 0.8) {
            preloadTriggered = true
            tryPreloadNextTrack()
        }

        setupServiceListeners()
    }

    /** 更新状态行显示调试信息 */
    private fun updateStatusLine() {
        val sb = StringBuilder("  等待识别音乐...")
        val info = recognitionEngine.lastRefreshInfo
        if (info.isNotEmpty()) {
            sb.append("  |  $info")
        }
        if (recognitionEngine.hasMediaSessionError) {
            sb.append("  |  MediaSession异常")
        }
        statusText.text = sb.toString()
    }

    /** 根据上次已知位置和时间的差值进行线性插值，使歌词滚动更平滑 */
    private fun interpolatePosition(rawPosition: Long): Long {
        val now = System.nanoTime()
        if (lastPositionNanos == 0L) {
            lastKnownPosition = rawPosition
            lastPositionNanos = now
            return rawPosition
        }
        val elapsed = ((now - lastPositionNanos) / 1_000_000).coerceAtMost(5000L)
        val estimated = lastKnownPosition + elapsed
        val result = if (rawPosition >= lastKnownPosition) {
            // 正常前进，以原始位置为准
            rawPosition
        } else if (lastKnownPosition - rawPosition > 1000) {
            // 大幅度回退（>1s）= 用户拖拽进度条，直接接受新位置
            rawPosition
        } else {
            // 微小抖动（<1s），用插值平滑
            estimated
        }
        lastKnownPosition = result
        lastPositionNanos = now
        return result
    }

    private fun fetchLyrics(title: String, artist: String, duration: Long) {
        fetchJob?.cancel()
        fetchJob = mainScope.launch {
            fetchWithRetry(title, artist, duration)
        }
    }

    private suspend fun fetchWithRetry(title: String, artist: String, duration: Long) {
        while (fetchRetryCount < MAX_RETRY) {
            val current = recognitionEngine.currentTrack.value
            val mediaId = current?.mediaId
            val pkg = current?.packageName
            val result = lyricsApi.fetchLyrics(title, artist, duration, mediaId, pkg)

            result.onSuccess { data ->
                fetchRetryCount = 0
                try {
                    applyLyricsResult(data)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "CrashGuard fetchLyrics onSuccess: ${e.message}", e)
                }
                return
            }

            result.onFailure { e ->
                fetchRetryCount++
                if (fetchRetryCount < MAX_RETRY) {
                    // 重试前更新占位提示
                    
                    
                    Toast.makeText(
                        this@LyricsSettingsActivity,
                        "获取失败，${RETRY_DELAY_MS / 1000}s 后重试 (${fetchRetryCount}/$MAX_RETRY)",
                        Toast.LENGTH_SHORT
                    ).show()
                    delay(RETRY_DELAY_MS)
                } else {
                    // 全部重试失败，显示兜底占位
                    
                    
                    Toast.makeText(
                        this@LyricsSettingsActivity,
                        "歌词获取失败 (已重试 $MAX_RETRY 次): ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** 将歌词数据应用到主界面和悬浮窗 */
    private fun applyLyricsResult(data: com.carlyrics.model.LyricData) {
        val lines = data.lines
        val cover = data.coverUrl
        currentLyricLines = lines
        coverUrl = cover

        // 1) 歌词与已知封面秒上屏
        if (::previewLyricsContainer.isInitialized) {
            previewLyricsContainer.setCoverUrl(cover)
            previewLyricsContainer.setLyrics(lines)
        } else {
            displayView.setCoverUrl(cover)
            displayView.setLyrics(lines)
        }
        
        

        // 2) 若歌词已就绪但缺失封面，在后台异步补充获取封面，绝不上锁阻塞歌词渲染
        if (cover.isNullOrEmpty()) {
            val trackTitle = lastTrackTitle
            val trackArtist = lastTrackArtist
            if (trackTitle.isNotBlank()) {
                mainScope.launch(Dispatchers.IO) {
                    val fetchedCover = lyricsApi.fetchCover(trackTitle, trackArtist)
                    if (!fetchedCover.isNullOrEmpty() && lastTrackTitle == trackTitle && lastTrackArtist == trackArtist) {
                        withContext(Dispatchers.Main) {
                            coverUrl = fetchedCover
                            if (::previewLyricsContainer.isInitialized) {
                                previewLyricsContainer.setCoverUrl(fetchedCover)
                            } else {
                                displayView.setCoverUrl(fetchedCover)
                            }
                            
                        }
                    }
                }
            }
        }
    }

    /** 尝试预加载下一曲（进度 > 80% 触发） */
    private fun tryPreloadNextTrack() {
        try {
            val next = recognitionEngine.getNextTrackInQueue()
            if (next != null && next.first.isNotBlank()) {
                AppLogger.d(TAG, "Preloading next track: ${next.first} - ${next.second}")
                mainScope.launch {
                    lyricsApi.prefetchAndCache(next.first, next.second)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "CrashGuard tryPreloadNextTrack: ${e.message}", e)
        }
    }

    // ==================== 悬浮窗操作 ====================

    private fun syncSkinUIParams() {
        try {
            currentFontSizeSp = PrefsManager.getFontSizeSp()
            currentSplitRatio = PrefsManager.getSplitRatio()
            currentLyricsAlignment = PrefsManager.getLyricsAlignment()
            currentMarqueeEnabled = PrefsManager.isMarqueeEnabled()
            currentDpiScale = PrefsManager.getDpiScale()
            currentInfoFontScale = PrefsManager.getInfoFontScale()
            currentXiaoOuAvoidanceEnabled = PrefsManager.isXiaoOuAvoidanceEnabled()

            // 0. 歌词字号
            if (::fontSizeSeekBar.isInitialized) {
                fontSizeSeekBar.progress = (currentFontSizeSp - LyricsDisplayView.MIN_FONT_SIZE_SP).toInt().coerceIn(0, fontSizeSeekBar.max)
                fontSizeLabel.text = "${currentFontSizeSp.toInt()} sp"
            }
            displayView.fontSizeSp = currentFontSizeSp

            // 1. 分割比例
            if (::splitRatioSeekBar.isInitialized) {
                splitRatioSeekBar.progress = ((currentSplitRatio - 0.20f) / 0.60f * 60f).toInt().coerceIn(0, 60)
                splitRatioLabel.text = "${(currentSplitRatio * 100).toInt()} %"
            }
            displayView.splitRatio = currentSplitRatio

            // 2. 跑马灯
            displayView.marqueeEnabled = currentMarqueeEnabled
            updateLyricsAlignmentCardState(currentMarqueeEnabled)

            // 3. 文本排版
            displayView.lyricsTextAlignment = currentLyricsAlignment
            val alignId = when (currentLyricsAlignment) {
                Gravity.START, 8388611 -> 1
                Gravity.END -> 3
                else -> 2
            }
            alignGroupWide?.check(alignId)
            alignGroupNarrow?.check(alignId)

            // 4. 封面面板缩放
            if (::displayScaleSeekBar.isInitialized) {
                displayScaleSeekBar.progress = ((currentDpiScale - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
                displayScaleLabel.text = "${"%.0f".format(currentDpiScale * 100)}%"
            }
            displayView.scaleFactor = currentDpiScale

            // 5. 封面面板歌名/歌手字号
            if (::infoFontScaleSeekBar.isInitialized) {
                infoFontScaleSeekBar.progress = ((currentInfoFontScale - 0.5f) / 0.05f).toInt().coerceIn(0, 50)
                infoFontScaleLabel.text = "${"%.2f".format(currentInfoFontScale)}x"
            }
            displayView.infoFontScale = currentInfoFontScale

            // 6. 小欧避让
            if (::previewLyricsContainer.isInitialized) {
                previewLyricsContainer.showAvatarIndicator = true
                previewLyricsContainer.xiaoOuAvoidanceEnabled = currentXiaoOuAvoidanceEnabled
                displayView = previewLyricsContainer.getSkin() ?: displayView
            } else {
                displayView.xiaoOuAvoidanceEnabled = currentXiaoOuAvoidanceEnabled
            }

            // 7. 歌词时间偏移
            displayView.lyricsOffsetMs = PrefsManager.lyricsOffsetMs
            if (::timeOffsetSeekBar.isInitialized) {
                timeOffsetSeekBar.progress = ((PrefsManager.lyricsOffsetMs / 500) + 20).toInt().coerceIn(0, 40)
                val ms = PrefsManager.lyricsOffsetMs
                timeOffsetLabel.text = if (ms == 0L) "0 ms" else if (ms > 0) "+${ms}ms" else "${ms}ms"
            }

            // 同步至运行中的悬浮窗服务
            syncSettingsToService()

        } catch (e: Exception) {
            AppLogger.e(TAG, "syncSkinUIParams error: ${e.message}", e)
        }
    }

    private fun switchSkin(newType: SkinType) {
        try {
            currentSkinType = newType
            PrefsManager.setSkinType(newType)

            // 选择歌词皮肤主题后，加载该皮肤在 Z6屏幕适配 中的默认参数
            PrefsManager.applySkinDefaultParams(newType)

            // 保存当前状态
            val prevLyrics = currentLyricLines
            val prevProgress = lastKnownPosition
            val prevTitle = lastTrackTitle
            val prevArtist = lastTrackArtist
            val prevCover = coverUrl

            // 彻底销毁旧皮肤的动画与资源，防内存/CPU 泄露
            try { (displayView as? LyricsSkin)?.onDestroy() } catch (_: Exception) {}

            // 替换皮肤 (所见即所得，与悬浮窗容器 100% 同构)
            if (::previewLyricsContainer.isInitialized) {
                previewLyricsContainer.reloadSkin(newType, force = true)
                displayView = previewLyricsContainer.getSkin()!!
            } else {
                val newSkin = SkinFactory.create(newType, com.carlyrics.util.DensityNormalization.getNormalizedContext(this))
                displayView = newSkin
                displayView.xiaoOuAvoidanceEnabled = currentXiaoOuAvoidanceEnabled
            }
            updatePreviewBounds()

            // 恢复状态并强行同步 UI 控件与面板参数
            displayView.fontSizeSp = PrefsManager.getFontSizeSp()
            syncSkinUIParams()

            bindDemoDataToPreview()

            // 通知悬浮窗重建（应用新皮肤）
            syncSettingsToService()

            Toast.makeText(this, "已切换至: ${newType.label}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLogger.e(TAG, "CrashGuard switchSkin: ${e.message}", e)
            Toast.makeText(this, "皮肤切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSourcesStatusText()
        updatePreviewBounds()
        setupServiceListeners()
        resumeDemoPlayback()
    }

    override fun onPause() {
        pauseDemoPlayback()
        super.onPause()
    }

    fun updatePreviewBounds() {
        if (::previewLyricsContainer.isInitialized) {
            previewLyricsContainer.requestLayout()
        }
    }

    private fun calculateTargetOverlayBounds(): Pair<Int, Int> {
        return 1920 to 384
    }

    // ==================== 日志查看 ====================

    @SuppressLint("SetTextI18n")
    private fun showLogDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // 日志收集控制开关 (默认关闭)
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        var logSwitch: Switch? = null
        logSwitch = Switch(this).apply {
            text = if (PrefsManager.isLogEnabled()) "运行日志收集 (已开启)" else "运行日志收集 (默认关闭)"
            applyIosSwitchStyle(this)
            isChecked = PrefsManager.isLogEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                PrefsManager.setLogEnabled(isChecked)
                text = if (isChecked) "运行日志收集 (已开启)" else "运行日志收集 (默认关闭)"
            }
        }
        switchRow.addView(logSwitch)

        // 两个 Tab 按钮
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        var currentTab = 0 // 0=崩溃日志, 1=运行日志

        val btnCrash = Button(this).apply {
            text = "崩溃日志"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFFF6900.toInt())
            textSize = 13f
        }
        val btnApp = Button(this).apply {
            text = "运行日志"
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0xFF333333.toInt())
            textSize = 13f
        }

        val logTextView = TextView(this).apply {
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xFF000000.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isHorizontalScrollBarEnabled = false
        }

        val scrollView = ScrollView(this).apply {
            addView(logTextView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            setBackgroundColor(0xFF000000.toInt())
        }

        fun loadLog(tab: Int) {
            currentTab = tab
            if (tab == 0) {
                btnCrash.setBackgroundColor(0xFFFF6900.toInt())
                btnCrash.setTextColor(0xFFFFFFFF.toInt())
                btnApp.setBackgroundColor(0xFF333333.toInt())
                btnApp.setTextColor(0xFFAAAAAA.toInt())
                logTextView.text = AppLogger.readCrashLog()
            } else {
                btnApp.setBackgroundColor(0xFFFF6900.toInt())
                btnApp.setTextColor(0xFFFFFFFF.toInt())
                btnCrash.setBackgroundColor(0xFF333333.toInt())
                btnCrash.setTextColor(0xFFAAAAAA.toInt())
                val appLogContent = AppLogger.readAppLog()
                val prefix = if (!PrefsManager.isLogEnabled()) {
                    "[提示] 运行日志记录当前处于关闭状态 (默认关闭)\n[提示] 开启上方「运行日志收集」开关后，将实时记录 API 请求与诊断轨迹。\n--------------------------------------------------\n"
                } else ""
                logTextView.text = prefix + appLogContent
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        btnCrash.setOnClickListener { loadLog(0) }
        btnApp.setOnClickListener { loadLog(1) }

        tabRow.addView(btnCrash, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(spacerH(4))
        tabRow.addView(btnApp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        dialogView.addView(switchRow)
        dialogView.addView(tabRow)
        dialogView.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(400)
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("日志查看")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .setPositiveButton("清空日志", null) // listener set after show
            .setNeutralButton("复制日志", null)
            .create()

        styleIosAlertDialog(dialog)
        dialog.show()

        // 绑定"清空日志"二次确认
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage(if (currentTab == 0) "确定要清空崩溃日志吗？" else "确定要清空运行日志吗？")
                .setPositiveButton("确定") { _, _ ->
                    if (currentTab == 0) {
                        AppLogger.clearCrashLog()
                    } else {
                        AppLogger.clearAppLog()
                    }
                    loadLog(currentTab)
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .create().apply { styleIosAlertDialog(this) }.show()
        }

        // 绑定"复制日志"
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = logTextView.text.toString()
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("log", text))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        // 初始加载崩溃日志
        loadLog(0)
    }

    private fun showDonateDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xAA000000.toInt())) // Translucent black

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { dialog.dismiss() } // Dismiss on outside tap
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createRoundedDrawable(0xFFFFFFFF.toInt(), 24f)
            setPadding(dp(24), dp(32), dp(24), dp(24))
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                if (resources.displayMetrics.widthPixels > dp(600)) dp(480) else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { /* Consume taps so it doesn't dismiss */ }
        }

        val title = TextView(this).apply {
            text = "☕️ 请作者喝咖啡"
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = -0.01f
        }

        val subtitle = TextView(this).apply {
            text = "如果这个应用对您有帮助，欢迎随意打赏\n您的支持是开源持续发电的动力！\n\n联系作者 / 意见反馈：lee1080@qq.com"
            setTextColor(0xFF6C6C70.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        val qrContainer = FrameLayout(this).apply {
            background = createRoundedDrawable(0xFFFFFFFF.toInt(), 16f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val qrImage = ImageView(this).apply {
            // donate removed
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        qrContainer.addView(qrImage)

        val btnClose = styledButton("关闭", isPrimary = false) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            }
        }

        card.addView(title)
        card.addView(subtitle)
        card.addView(qrContainer)
        card.addView(btnClose)
        container.addView(card)
        dialog.setContentView(container)

        // Apple Design pop-in animation
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.alpha = 0f
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(350)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()

        dialog.show()
    }

    private fun showDisclaimerDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x88000000.toInt()))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createRoundedDrawable(0xFFFFFFFF.toInt(), 24f)
            setPadding(dp(24), dp(28), dp(24), dp(24))
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                if (resources.displayMetrics.widthPixels > dp(600)) dp(520) else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { /* Consume taps */ }
        }

        val title = TextView(this).apply {
            text = "免责与数据来源声明"
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val disclaimerContent = TextView(this).apply {
            text = """
            本应用为个人非营利性车载控制辅助工具，歌词与专辑封面等元数据均优先检索自公共/开源数据接口（包括 LRCLIB 社区开放歌词库、Apple iTunes API 公开元数据库等）。

            本应用本身不存储、不中转、不出售任何受版权保护的音视频或歌词文件。所有歌曲商标、封面图片及歌词版权均归各自合法著作权人所有。

            本应用尊重知识产权，如版权方认为相关数据检索侵犯了您的合法权益，请通过邮箱 lee1080@qq.com 联系开发者，我们将及时配合处理或屏蔽相关数据源。
            """.trimIndent()
            setTextColor(0xFF3A3A3C.toInt())
            textSize = 13f
            setPadding(0, dp(14), 0, dp(20))
            setLineSpacing(dp(4).toFloat(), 1.1f)
        }

        val btnClose = styledButton("我已了解", isPrimary = true) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        card.addView(title)
        card.addView(disclaimerContent)
        card.addView(btnClose)
        container.addView(card)
        dialog.setContentView(container)

        // Apple Design pop-in animation
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.alpha = 0f
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        dialog.show()
    }

    // ==================== 生命周期 ====================

    override fun onDestroy() {
        stopDemoPlayback()
        fetchJob?.cancel()
        mainScope.cancel()
        // unbind removed
        super.onDestroy()
    }

    private fun updateLyricsAlignmentCardState(marqueeEnabled: Boolean) {
        val alphaVal = if (marqueeEnabled) 0.4f else 1.0f
        val enabledState = !marqueeEnabled

        lyricsAlignmentCardWide?.let { card ->
            card.alpha = alphaVal
            card.isEnabled = enabledState
        }
        alignGroupWide?.let { group ->
            group.isEnabled = enabledState
            for (i in 0 until group.childCount) {
                group.getChildAt(i).isEnabled = enabledState
            }
        }

        lyricsAlignmentCardNarrow?.let { card ->
            card.alpha = alphaVal
            card.isEnabled = enabledState
        }
        alignGroupNarrow?.let { group ->
            group.isEnabled = enabledState
            for (i in 0 until group.childCount) {
                group.getChildAt(i).isEnabled = enabledState
            }
        }
    }

    // ==================== UI 工具 (Apple HIG Light Theme Design Style) ====================

    private fun createRoundedDrawable(bgColor: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }
    }

    private fun cardView(title: String, init: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Apple HIG Light Theme Card: pure white background + subtle light border
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                createRoundedDrawable(0xFFFFFFFF.toInt(), 14f),
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0xFFE5E5EA.toInt())
                }
            ))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(2).toFloat()

            if (title.isNotBlank()) {
                val titleLabel = TextView(this@LyricsSettingsActivity).apply {
                    text = title.trim().uppercase()
                    setTextColor(0xFF6C6C70.toInt()) // iOS Secondary Label Light
                    textSize = 13f // 增大2号 (由 11f 调至 13f)
                    letterSpacing = 0.06f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                addView(titleLabel)
                addView(spacerV(10))
            }

            init()
        }
    }

    private fun styledButton(text: String, isPrimary: Boolean = false, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(if (isPrimary) 0xFFFFFFFF.toInt() else 0xFF007AFF.toInt())
            background = createRoundedDrawable(if (isPrimary) 0xFF007AFF.toInt() else 0xFFE5E5EA.toInt(), 10f)
            setOnClickListener { onClick() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = android.animation.StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(this@apply,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f)
                    ).apply { duration = 80; interpolator = android.view.animation.DecelerateInterpolator() })
                addState(intArrayOf(),
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(this@apply,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f)
                    ).apply { duration = 200; interpolator = android.view.animation.OvershootInterpolator(2f) })
            }
        }
    }

    private fun applyIosSwitchStyle(switchView: Switch) {
        switchView.setTextColor(0xFF1C1C1E.toInt())
        switchView.textSize = 15f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            switchView.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
            )
            switchView.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF34C759.toInt(), 0xFFE9E9EA.toInt())
            )
        }
    }

    private fun applyIosSeekBarStyle(seekBar: SeekBar) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.progressTintList = ColorStateList.valueOf(0xFF007AFF.toInt())
            seekBar.thumbTintList = ColorStateList.valueOf(0xFF007AFF.toInt())
            seekBar.progressBackgroundTintList = ColorStateList.valueOf(0xFFE5E5EA.toInt())
        }
    }

    private fun styleIosInput(editText: EditText) {
        editText.background = android.graphics.drawable.LayerDrawable(arrayOf(
            createRoundedDrawable(0xFFF2F2F7.toInt(), 8f),
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFFE5E5EA.toInt())
            }
        ))
        editText.setTextColor(0xFF1C1C1E.toInt())
        editText.setHintTextColor(0xFF8E8E93.toInt())
        editText.setPadding(dp(12), dp(10), dp(12), dp(10))
        editText.textSize = 14f
    }

    private fun applyIosSpinnerStyle(spinner: Spinner) {
        spinner.background = android.graphics.drawable.LayerDrawable(arrayOf(
            createRoundedDrawable(0xFFF2F2F7.toInt(), 8f),
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFFE5E5EA.toInt())
            }
        ))
        spinner.setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun styleIosAlertDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(createRoundedDrawable(0xFFFFFFFF.toInt(), 16f))
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFF007AFF.toInt())
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(0xFF8E8E93.toInt())
                textSize = 15f
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(0xFF007AFF.toInt())
                textSize = 15f
            }

            // 递归遍历纠正对话框内文字与控件颜色：文字强制为深色(#1C1C1E)，复选框强制为蓝色(#007AFF)
            dialog.window?.decorView?.let { decor ->
                applyDialogViewColors(decor)
            }
        }
    }

    private fun applyDialogViewColors(view: View) {
        val blueTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF007AFF.toInt(), 0xFFC7C7CC.toInt())
        )
        if (view is android.widget.CheckedTextView) {
            view.setTextColor(0xFF1C1C1E.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.checkMarkTintList = blueTintList
            }
        } else if (view is CheckBox) {
            view.setTextColor(0xFF1C1C1E.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.buttonTintList = blueTintList
            }
        } else if (view is RadioButton) {
            view.setTextColor(0xFF1C1C1E.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.buttonTintList = blueTintList
            }
        } else if (view is TextView && view !is Button) {
            val color = view.currentTextColor
            // 解决暗黑主题下默认白色/浅灰文字在白底弹窗上隐形的问题
            if (color == 0xFFFFFFFF.toInt() || color == 0xFFF2F2F7.toInt() || color == -1) {
                view.setTextColor(0xFF1C1C1E.toInt())
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyDialogViewColors(view.getChildAt(i))
            }
        }
    }

    private fun spacerH(dpVal: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(dpVal), dp(dpVal))
    }

    private fun spacerV(dpVal: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun createIosSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                if (view is TextView) {
                    view.setTextColor(0xFF1C1C1E.toInt()) // 选中展示项：深纯黑
                    view.textSize = 14f
                    view.typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                if (view is TextView) {
                    view.setTextColor(0xFF1C1C1E.toInt()) // 下拉菜单项：深黑
                    view.setBackgroundColor(0xFFFFFFFF.toInt()) // 纯白下拉背景
                    view.textSize = 14f
                    view.setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                return view
            }
        }
    }

    // ==================== 预设系统核心逻辑 ====================

    private fun updatePresetSpinner() {
        val names = PresetManager.getPresetNames()
        val current = PresetManager.getCurrentPresetName()
        val idx = names.indexOf(current).coerceAtLeast(0)

        presetSpinnerWide?.adapter = createIosSpinnerAdapter(names)
        presetSpinnerWide?.setSelection(idx)

        presetSpinnerNarrow?.adapter = createIosSpinnerAdapter(names)
        presetSpinnerNarrow?.setSelection(idx)
    }

    private fun reloadSettingsFromPreset() {
        isUIInitializing = true
        currentSkinType = PrefsManager.getSkinType()
        currentFontSizeSp = PrefsManager.getFontSizeSp()
        currentHorizontalOffset = PrefsManager.getHorizontalOffset()
        currentSplitRatio = PrefsManager.getSplitRatio()
        currentLyricsAlignment = PrefsManager.getLyricsAlignment()
        currentMarqueeEnabled = PrefsManager.isMarqueeEnabled()
        currentDpiScale = PrefsManager.getDpiScale()
        currentInfoFontScale = PrefsManager.getInfoFontScale()
        currentXiaoOuAvoidanceEnabled = PrefsManager.isXiaoOuAvoidanceEnabled()
        targetDisplayId = PrefsManager.getTargetDisplayId()
        LyricsApiManager.LYRICS_API_URL = PrefsManager.getLyricsApiUrl()
        LyricsApiManager.COVER_API_URL = PrefsManager.getCoverApiUrl()
        LyricsApiManager.API_KEY = PrefsManager.getApiKey()

        // 重新构建整体 UI，同步恢复所有组件显示状态
        buildUI()
        isUIInitializing = false
        displayView.lyricsOffsetMs = PrefsManager.lyricsOffsetMs

        // 关键：重建 UI 后必须重新同步所有皮肤排版参数并刷新 Demo 播放动画，做到 100% 所见即所得！
        syncSkinUIParams()
        bindDemoDataToPreview()

        syncSettingsToService()
    }

    private fun refreshPreferredSourceSpinners() {
        val enabledSources = com.carlyrics.lyrics.LyricsSourceManager.getEnabledSources()
        val options = mutableListOf(
            "自动匹配 (默认瀑布流)" to "auto"
        )
        enabledSources.forEach { s ->
            options.add(s.name to s.id)
        }
        preferredSourceOptions = options
        val labels = options.map { it.first }
        val currentVal = PrefsManager.getPreferredLyricsSource()
        val currentIdx = options.indexOfFirst { it.second == currentVal }.coerceAtLeast(0)

        runOnUiThread {
            preferredSourceSpinnerWide?.let { sp ->
                sp.adapter = createIosSpinnerAdapter(labels)
                sp.setSelection(currentIdx)
            }
            preferredSourceSpinnerNarrow?.let { sp ->
                sp.adapter = createIosSpinnerAdapter(labels)
                sp.setSelection(currentIdx)
            }
        }
    }

    private fun refreshSourcesStatusText() {
        val all = com.carlyrics.lyrics.LyricsSourceManager.getAllSources()
        val enabled = all.count { it.isEnabled }
        val statusText = if (all.isEmpty()) {
            "未安装歌词源（点击下方导入合集或单源）"
        } else {
            "已安装 ${all.size} 个源，启用 $enabled 个"
        }
        runOnUiThread {
            sourcesStatusTextViewWide?.text = statusText
            sourcesStatusTextViewNarrow?.text = statusText
        }
        refreshPreferredSourceSpinners()
    }

    private fun createLyricsSourcesCard(isWide: Boolean): View {
        return cardView("歌词源管理 (外置插件)") {
            val tvStatus = TextView(this@LyricsSettingsActivity).apply {
                setTextColor(0xFF8E8E93.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            if (isWide) sourcesStatusTextViewWide = tvStatus else sourcesStatusTextViewNarrow = tvStatus
            addView(tvStatus)
            addView(spacerV(10))

            val btnRow = LinearLayout(this@LyricsSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun createSmallBtn(label: String, onClick: () -> Unit) = Button(this@LyricsSettingsActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = createRoundedDrawable(0xFF007AFF.toInt(), 8f)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { onClick() }
            }

            val btnManage = createSmallBtn("管理源") { showSourcesManagementDialog() }
            val btnImportCol = createSmallBtn("导入合集") { showImportCollectionDialog() }
            val btnImportSingle = createSmallBtn("导入单源") { showImportSingleSourceDialog() }

            btnRow.addView(btnManage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(spacerH(8))
            btnRow.addView(btnImportCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(spacerH(8))
            btnRow.addView(btnImportSingle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(btnRow)
            addView(spacerV(6))
            addView(TextView(this@LyricsSettingsActivity).apply {
                text = "本软件为纯净空壳播放挂件，不内置受版权限制的音源。请自行导入开源源。"
                setTextColor(0xFF8E8E93.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
        }
    }

    private fun showSourcesManagementDialog() {
        val sources = com.carlyrics.lyrics.LyricsSourceManager.getAllSources()
        if (sources.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("已安装歌词源")
                .setMessage("当前未安装任何歌词源。\n\n请点击“导入合集”输入订阅链接或选择本地合集导入。")
                .setPositiveButton("导入合集") { _, _ -> showImportCollectionDialog() }
                .setNegativeButton("知道了", null)
                .create().apply { styleIosAlertDialog(this) }
                .show()
            return
        }

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(container)

        var dialog: AlertDialog? = null
        val builder = AlertDialog.Builder(this)
            .setTitle("歌词源管理 (${sources.size}个)")
            .setView(scroll)
            .setPositiveButton("完成") { _, _ ->
                refreshSourcesStatusText()
            }

        for ((index, source) in sources.withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    setBackgroundColor(0xFFE5E5EA.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        setMargins(0, dp(6), 0, dp(6))
                    }
                }
                container.addView(divider)
            }

            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = "${source.name} (v${source.version})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFF1C1C1E.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val descTv = TextView(this).apply {
                text = "${if (source.author.isNotBlank()) "作者: ${source.author} · " else ""}${source.description}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF8E8E93.toInt())
            }
            textLayout.addView(titleTv)
            textLayout.addView(spacerV(2))
            textLayout.addView(descTv)

            val switch = Switch(this).apply {
                applyIosSwitchStyle(this)
                isChecked = source.isEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    com.carlyrics.lyrics.LyricsSourceManager.toggleSource(source.id, isChecked)
                    refreshSourcesStatusText()
                }
            }

            val btnDelete = Button(this).apply {
                text = "删除"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = createRoundedDrawable(0xFFFF3B30.toInt(), 6f)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    AlertDialog.Builder(this@LyricsSettingsActivity)
                        .setTitle("确认删除")
                        .setMessage("确定要删除歌词源 [${source.name}] 吗？")
                        .setPositiveButton("删除") { _, _ ->
                            com.carlyrics.lyrics.LyricsSourceManager.deleteSource(source.id)
                            dialog?.dismiss()
                            showSourcesManagementDialog()
                            refreshSourcesStatusText()
                        }
                        .setNegativeButton("取消", null)
                        .create().apply { styleIosAlertDialog(this) }
                        .show()
                }
            }

            itemRow.addView(textLayout)
            itemRow.addView(spacerH(6))
            itemRow.addView(switch)
            itemRow.addView(spacerH(8))
            itemRow.addView(btnDelete)

            container.addView(itemRow)
        }

        dialog = builder.create().apply { styleIosAlertDialog(this) }
        dialog.show()
    }

    private fun normalizeImportInput(input: String): Pair<Boolean, String> {
        val trimmed = input.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return Pair(false, trimmed)
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return Pair(true, trimmed)
        }
        // 若看起来像网络地址/IP (包含点或冒号且不含换行)，自动补全 http://
        if ((trimmed.contains(".") || trimmed.contains(":")) && !trimmed.contains("\n")) {
            return Pair(true, "http://$trimmed")
        }
        return Pair(false, trimmed)
    }

    private fun showImportCollectionDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val etInput = EditText(this).apply {
            hint = "输入合集订阅链接 (URL) 或粘贴合集 JSON / 本地 ZIP 路径"
            setTextColor(0xFF1C1C1E.toInt())
            setHintTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 3
            maxLines = 6
            background = createRoundedDrawable(0xFFF2F2F7.toInt(), 8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(etInput)
        container.addView(spacerV(10))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = createRoundedDrawable(0xFFE5E5EA.toInt(), 8f)
            setTextColor(0xFF1C1C1E.toInt())
            setOnClickListener { onClick() }
        }

        val btnPaste = createBtn("剪贴板粘贴") {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isNotBlank()) etInput.setText(clip)
        }

        val btnPickFile = createBtn("选择本地文件 (ZIP/JSON)") {
            sourceFileImportMode = "collection"
            pendingCollectionCallback = { content ->
                etInput.setText(content)
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json", "text/*", "application/octet-stream", "*/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            sourceFileImportLauncher.launch(intent)
        }

        btnRow.addView(btnPaste, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(spacerH(8))
        btnRow.addView(btnPickFile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)
        container.addView(spacerV(8))

        container.addView(TextView(this).apply {
            text = "💡 支持直接导入包含多个 .js 歌词源的 ZIP 压缩包（如发布页一键打包的 ZIP），导入后自动解压并支持勾选启用对应源，方便车机无网离线导入！"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("导入歌词源合集")
            .setView(container)
            .setPositiveButton("解析并选择") { _, _ ->
                val input = etInput.text.toString().trim()
                if (input.isBlank()) {
                    Toast.makeText(this, "输入内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val cleanPath = input.removePrefix("file://")
                    val localFile = java.io.File(cleanPath)

                    // 1. 如果输入指向本地存在的 ZIP 文件
                    if (localFile.exists() && isZipFile(localFile)) {
                        Toast.makeText(this@LyricsSettingsActivity, "正在解压并解析本地 ZIP: ${localFile.name}", Toast.LENGTH_SHORT).show()
                        try {
                            localFile.inputStream().use { stream ->
                                val parseRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionFromZip(stream, localFile.name)
                                parseRes.onSuccess { collection ->
                                    currentImportCollectionDialog?.dismiss()
                                    currentImportCollectionDialog = null
                                    showCollectionSelectDialog(collection)
                                }
                                parseRes.onFailure { e ->
                                    Toast.makeText(this@LyricsSettingsActivity, "ZIP 解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@LyricsSettingsActivity, "读取本地 ZIP 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // 2. 如果输入是本地存在的 JSON 文件
                    if (localFile.exists() && localFile.isFile && !localFile.name.endsWith(".zip", ignoreCase = true)) {
                        try {
                            val text = localFile.readText()
                            val parseRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionJson(text)
                            parseRes.onSuccess { collection ->
                                currentImportCollectionDialog?.dismiss()
                                currentImportCollectionDialog = null
                                showCollectionSelectDialog(collection)
                            }
                            parseRes.onFailure { e ->
                                Toast.makeText(this@LyricsSettingsActivity, "本地合集 JSON 解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@LyricsSettingsActivity, "读取本地文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // 3. 网络 URL 流程
                    val (isUrl, target) = normalizeImportInput(input)

                    // 若 URL 指向远程 .zip 压缩包
                    if (isUrl && target.endsWith(".zip", ignoreCase = true)) {
                        Toast.makeText(this@LyricsSettingsActivity, "正在下载远程 ZIP 合集: $target", Toast.LENGTH_SHORT).show()
                        val bytes = com.carlyrics.lyrics.LyricsSourceManager.fetchUrlBytes(target)
                        if (bytes == null || bytes.isEmpty()) {
                            Toast.makeText(this@LyricsSettingsActivity, "下载远程 ZIP 失败或文件为空", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val parseRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionFromZip(
                            java.io.ByteArrayInputStream(bytes),
                            target.substringAfterLast("/")
                        )
                        parseRes.onSuccess { collection ->
                            currentImportCollectionDialog?.dismiss()
                            currentImportCollectionDialog = null
                            showCollectionSelectDialog(collection, target)
                        }
                        parseRes.onFailure { e ->
                            Toast.makeText(this@LyricsSettingsActivity, "ZIP 解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    val jsonContent = if (isUrl) {
                        Toast.makeText(this@LyricsSettingsActivity, "正在拉取合集清单: $target", Toast.LENGTH_SHORT).show()
                        val fetchRes = com.carlyrics.lyrics.LyricsSourceManager.fetchUrlResult(target)
                        if (fetchRes.isFailure) {
                            val errMsg = fetchRes.exceptionOrNull()?.message ?: "未知网络错误"
                            Toast.makeText(this@LyricsSettingsActivity, "拉取合集失败: $errMsg", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        fetchRes.getOrNull()
                    } else {
                        target
                    }

                    if (jsonContent.isNullOrBlank()) {
                        Toast.makeText(this@LyricsSettingsActivity, "拉取合集失败或内容为空", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val parseRes = com.carlyrics.lyrics.LyricsSourceManager.parseCollectionJson(jsonContent)
                    parseRes.onSuccess { collection ->
                        currentImportCollectionDialog?.dismiss()
                        currentImportCollectionDialog = null
                        showCollectionSelectDialog(collection, if (isUrl) target else null)
                    }
                    parseRes.onFailure { e ->
                        Toast.makeText(this@LyricsSettingsActivity, "合集解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create().apply { styleIosAlertDialog(this) }

        currentImportCollectionDialog = dialog
        dialog.show()
    }

    private fun showCollectionSelectDialog(collection: com.carlyrics.lyrics.LyricsCollection, collectionUrl: String? = null) {
        val rawSources = collection.sources ?: emptyList()
        if (rawSources.isEmpty()) {
            Toast.makeText(this, "该合集未包含任何有效源", Toast.LENGTH_SHORT).show()
            return
        }

        // 规范化源列表，赋予安全兜底的 id 和名称
        val sources = rawSources.mapIndexed { index, item ->
            val safeId = item.id?.trim()?.ifEmpty { null }
                ?: (if (!item.name.isNullOrBlank()) "source_${Math.abs(item.name.hashCode())}" else "source_${System.currentTimeMillis()}_$index")
            val safeName = item.name?.trim()?.ifEmpty { null } ?: safeId
            item.copy(id = safeId, name = safeName)
        }

        val normalizedCollection = collection.copy(sources = sources)
        val checkedStates = BooleanArray(sources.size) { true }
        val collectionTitle = collection.name?.trim()?.ifEmpty { "歌词源合集" } ?: "歌词源合集"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // 顶部标题栏：标题 + 来源 + 全选按钮
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = "选择要导入的源"
            setTextColor(0xFF1C1C1E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvSubTitle = TextView(this).apply {
            text = "$collectionTitle (共 ${sources.size} 个源)"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titleColumn.addView(tvTitle)
        titleColumn.addView(spacerV(2))
        titleColumn.addView(tvSubTitle)
        headerRow.addView(titleColumn)

        val btnToggleAll = Button(this).apply {
            text = "全不选"
            setTextColor(0xFF007AFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        headerRow.addView(btnToggleAll)
        container.addView(headerRow)
        container.addView(spacerV(12))

        // 可滚动的源列表
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val checkBoxes = mutableListOf<CheckBox>()

        fun updateToggleAllButtonText() {
            val allChecked = checkedStates.all { it }
            btnToggleAll.text = if (allChecked) "全不选" else "全选"
        }

        btnToggleAll.setOnClickListener {
            val allChecked = checkedStates.all { it }
            val newState = !allChecked
            for (i in checkedStates.indices) {
                checkedStates[i] = newState
                checkBoxes[i].isChecked = newState
            }
            updateToggleAllButtonText()
        }

        val blueTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFF007AFF.toInt(), 0xFFC7C7CC.toInt())
        )

        sources.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createRoundedDrawable(0xFFF9F9FB.toInt(), 8f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val cb = CheckBox(this).apply {
                isChecked = checkedStates[index]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    buttonTintList = blueTintList
                }
                setOnCheckedChangeListener { _, isChecked ->
                    checkedStates[index] = isChecked
                    updateToggleAllButtonText()
                }
            }
            checkBoxes.add(cb)
            row.addView(cb)
            row.addView(spacerH(10))

            val contentCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = item.name ?: "未知源"
                setTextColor(0xFF1C1C1E.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            nameRow.addView(tvName)

            val verStr = item.version?.trim()?.ifEmpty { "1.0.0" } ?: "1.0.0"
            val tvVer = TextView(this).apply {
                text = "v$verStr"
                setTextColor(0xFF007AFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = createRoundedDrawable(0xFFEBF5FF.toInt(), 4f)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(8)
                }
            }
            nameRow.addView(tvVer)
            contentCol.addView(nameRow)

            val descStr = item.description?.trim()?.ifEmpty { null } ?: item.author?.trim().orEmpty()
            if (descStr.isNotBlank()) {
                val tvDesc = TextView(this).apply {
                    text = descStr
                    setTextColor(0xFF8E8E93.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2)
                    }
                }
                contentCol.addView(tvDesc)
            }

            row.addView(contentCol)

            // 点击整行切换复选框
            row.setOnClickListener {
                cb.isChecked = !cb.isChecked
            }

            listContainer.addView(row)
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("确认导入") { _, _ ->
                val selectedIds = sources.filterIndexed { index, _ -> checkedStates[index] }
                    .mapNotNull { it.id?.trim()?.ifEmpty { null } }
                    .toSet()
                if (selectedIds.isEmpty()) {
                    Toast.makeText(this, "未勾选任何源", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    Toast.makeText(this@LyricsSettingsActivity, "正在导入 ${selectedIds.size} 个源...", Toast.LENGTH_SHORT).show()
                    val count = com.carlyrics.lyrics.LyricsSourceManager.importFromCollection(normalizedCollection, selectedIds, collectionUrl)
                    refreshSourcesStatusText()
                    Toast.makeText(this@LyricsSettingsActivity, "成功导入 $count 个歌词源！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        styleIosAlertDialog(dialog)
        dialog.show()
    }

    private fun showImportSingleSourceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        val etInput = EditText(this).apply {
            hint = "输入单源脚本链接 (URL) 或直接粘贴 JS 代码"
            setTextColor(0xFF1C1C1E.toInt())
            setHintTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 3
            maxLines = 6
            background = createRoundedDrawable(0xFFF2F2F7.toInt(), 8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(etInput)
        container.addView(spacerV(10))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun createBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = createRoundedDrawable(0xFFE5E5EA.toInt(), 8f)
            setTextColor(0xFF1C1C1E.toInt())
            setOnClickListener { onClick() }
        }

        val btnPaste = createBtn("剪贴板粘贴") {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isNotBlank()) etInput.setText(clip)
        }

        val btnPickFile = createBtn("选择本地 .js") {
            sourceFileImportMode = "single"
            pendingSingleSourceCallback = { content ->
                etInput.setText(content)
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            sourceFileImportLauncher.launch(intent)
        }

        btnRow.addView(btnPaste, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(spacerH(8))
        btnRow.addView(btnPickFile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(btnRow)

        AlertDialog.Builder(this)
            .setTitle("导入单个歌词源")
            .setView(container)
            .setPositiveButton("导入") { _, _ ->
                val input = etInput.text.toString().trim()
                if (input.isBlank()) {
                    Toast.makeText(this, "输入内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val (isUrl, target) = normalizeImportInput(input)
                    val jsContent = if (isUrl) {
                        val downloadUrl = com.carlyrics.lyrics.LyricsSourceManager.resolveSourceDownloadUrl(target, null, null, null)
                        Toast.makeText(this@LyricsSettingsActivity, "正在下载脚本: $downloadUrl", Toast.LENGTH_SHORT).show()
                        val fetchContent = com.carlyrics.lyrics.LyricsSourceManager.fetchUrlStringWithRetry(downloadUrl)
                        if (fetchContent.isNullOrBlank()) {
                            Toast.makeText(this@LyricsSettingsActivity, "下载脚本失败，请检查网络或链接是否有效", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        fetchContent
                    } else {
                        target
                    }

                    val res = com.carlyrics.lyrics.LyricsSourceManager.importFromJsContent(jsContent)
                    res.onSuccess { source ->
                        refreshSourcesStatusText()
                        Toast.makeText(this@LyricsSettingsActivity, "成功安装源: ${source.name} (v${source.version})", Toast.LENGTH_SHORT).show()
                    }
                    res.onFailure { e ->
                        Toast.makeText(this@LyricsSettingsActivity, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create().apply { styleIosAlertDialog(this) }
            .show()
    }

    private fun createPreferredSourceCardView(isWide: Boolean): View {
        return cardView("偏好源") {
            val enabledSources = com.carlyrics.lyrics.LyricsSourceManager.getEnabledSources()
            val options = mutableListOf(
                "自动匹配 (默认瀑布流)" to "auto"
            )
            enabledSources.forEach { s ->
                options.add(s.name to s.id)
            }
            preferredSourceOptions = options
            val labels = options.map { it.first }
            val currentVal = PrefsManager.getPreferredLyricsSource()
            val currentIdx = options.indexOfFirst { it.second == currentVal }.coerceAtLeast(0)

            val spinner = Spinner(this@LyricsSettingsActivity).apply {
                adapter = createIosSpinnerAdapter(labels)
                applyIosSpinnerStyle(this)
                setSelection(currentIdx)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        if (isUIInitializing) return
                        val chosen = preferredSourceOptions.getOrNull(pos)?.second ?: "auto"
                        if (chosen != PrefsManager.getPreferredLyricsSource()) {
                            PrefsManager.setPreferredLyricsSource(chosen)
                            val name = preferredSourceOptions.getOrNull(pos)?.first ?: "自动"
                            Toast.makeText(this@LyricsSettingsActivity, "偏好源已设为: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            if (isWide) {
                preferredSourceSpinnerWide = spinner
            } else {
                preferredSourceSpinnerNarrow = spinner
            }

            addView(spinner)
            addView(spacerV(6))
            addView(TextView(this@LyricsSettingsActivity).apply {
                text = "CarPlay 或手机蓝牙等外部音源无法识别客户端时优先检索此源；车机本地播放可识别时仍自动匹配同源母带。"
                setTextColor(0xFF8E8E93.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
    }

    private fun createPresetCardView(isWide: Boolean): View {
        return cardView("配置预设管理") {
            val names = PresetManager.getPresetNames()
            val currentName = PresetManager.getCurrentPresetName()
            val selectedIdx = names.indexOf(currentName).coerceAtLeast(0)

            val spinner = Spinner(this@LyricsSettingsActivity).apply {
                adapter = createIosSpinnerAdapter(names)
                applyIosSpinnerStyle(this)
                setSelection(selectedIdx)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        if (isUIInitializing) return
                        val selectedName = PresetManager.getPresetNames().getOrNull(pos) ?: return
                        if (selectedName != PresetManager.getCurrentPresetName()) {
                            PresetManager.loadPreset(selectedName)
                            reloadSettingsFromPreset()
                            Toast.makeText(this@LyricsSettingsActivity, "已切换预设: $selectedName", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            if (isWide) {
                presetSpinnerWide = spinner
            } else {
                presetSpinnerNarrow = spinner
            }

            addView(spinner)
            addView(spacerV(10))

            // 一行四个按钮: 添加 / 删除 / 导入 / 导出
            val btnRow = LinearLayout(this@LyricsSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnStyle = { btn: Button, textStr: String ->
                btn.text = textStr
                btn.textSize = 12f
                btn.setTextColor(0xFF007AFF.toInt())
                btn.background = createRoundedDrawable(0xFFF2F2F7.toInt(), 6f)
                btn.setPadding(dp(2), dp(8), dp(2), dp(8))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                btn.layoutParams = lp
            }

            val btnAdd = Button(this@LyricsSettingsActivity).apply {
                btnStyle(this, "📝 添加")
                setOnClickListener { showAddPresetDialog() }
            }
            val btnDelete = Button(this@LyricsSettingsActivity).apply {
                btnStyle(this, "🗑️ 删除")
                setOnClickListener { showDeletePresetDialog() }
            }
            val btnImport = Button(this@LyricsSettingsActivity).apply {
                btnStyle(this, "📥 导入")
                setOnClickListener { showImportPresetDialog() }
            }
            val btnExport = Button(this@LyricsSettingsActivity).apply {
                btnStyle(this, "📤 导出")
                setOnClickListener { showExportPresetDialog() }
            }

            btnRow.addView(btnAdd)
            btnRow.addView(btnDelete)
            btnRow.addView(btnImport)
            btnRow.addView(btnExport)

            addView(btnRow)
        }
    }

    private fun showAddPresetDialog() {
        val input = EditText(this).apply {
            hint = "例如：高清车机预设"
            styleIosInput(this)
        }
        AlertDialog.Builder(this)
            .setTitle("添加预设")
            .setMessage("将当前所有配置保存为新预设：")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(input)
            })
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    PresetManager.savePreset(name)
                    updatePresetSpinner()
                    Toast.makeText(this, "已保存预设 [$name]", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "预设名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create().apply { styleIosAlertDialog(this) }.show()
    }

    private fun showDeletePresetDialog() {
        val current = PresetManager.getCurrentPresetName()
        val names = PresetManager.getPresetNames()
        if (names.size <= 1) {
            Toast.makeText(this, "至少保留一个预设，无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除预设")
            .setMessage("确定要删除当前预设 [$current] 吗？此操作无法撤销。")
            .setPositiveButton("确定删除") { _, _ ->
                if (PresetManager.deletePreset(current)) {
                    val nextName = PresetManager.getCurrentPresetName()
                    PresetManager.loadPreset(nextName)
                    reloadSettingsFromPreset()
                    Toast.makeText(this, "已删除 [$current]，已切换至 [$nextName]", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create().apply { styleIosAlertDialog(this) }.show()
    }

    private fun showImportPresetDialog() {
        val options = arrayOf("🌐 从网址拉取导入", "📁 选择本地文件导入")
        AlertDialog.Builder(this)
            .setTitle("导入预设")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showImportUrlDialog()
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        importFileLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "打开文件选择器失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create().apply { styleIosAlertDialog(this) }.show()
    }

    private fun showImportUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/preset.json"
            styleIosInput(this)
        }
        AlertDialog.Builder(this)
            .setTitle("在线拉取导入预设")
            .setMessage("请输入预设 JSON 文件的网络地址：")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(input)
            })
            .setPositiveButton("拉取并导入") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val client = LyricsApiManager.sharedClient
                            val request = okhttp3.Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            val json = response.body?.string()
                            withContext(Dispatchers.Main) {
                                if (!json.isNullOrBlank() && PresetManager.applyPresetJson(json)) {
                                    reloadSettingsFromPreset()
                                    Toast.makeText(this@LyricsSettingsActivity, "成功在线导入预设！", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@LyricsSettingsActivity, "解析预设文件失败，请检查格式", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@LyricsSettingsActivity, "网络下载预设失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create().apply { styleIosAlertDialog(this) }.show()
    }

    private fun showExportPresetDialog() {
        val current = PresetManager.getCurrentPresetName()
        val jsonStr = PresetManager.exportCurrentSettingsAsJson(current)
        pendingExportJson = jsonStr

        AlertDialog.Builder(this)
            .setTitle("导出预设")
            .setMessage("将当前预设 [$current] 导出为本地文件：")
            .setPositiveButton("保存为文件") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "${current}_preset.json")
                    }
                    exportFileLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "打开导出保存弹窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create().apply { styleIosAlertDialog(this) }.show()
    }
}

package com.carlyrics.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.carlyrics.model.LyricData
import com.carlyrics.model.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 本地缓存管理器 —— 缓存封面图片和歌词数据。
 * 使用 LRU 策略全盘管控磁盘缓存，默认上限 64MB。
 */
object LyricsCacheManager {

    private const val TAG = "LyricsCache"
    private const val DEFAULT_MAX_SIZE_MB = 64L
    private const val MIN_MAX_SIZE_MB = 8L
    private const val MAX_MAX_SIZE_MB = 128L

    private var appContext: Context? = null
    private lateinit var cacheRoot: File
    private lateinit var coverDir: File
    private lateinit var lyricsDir: File
    private var maxSizeBytes: Long = DEFAULT_MAX_SIZE_MB * 1024 * 1024

    @Volatile private var initialized = false

    private val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val ctx = context.applicationContext ?: context
            appContext = ctx
            cacheRoot = File(ctx.cacheDir, "lyrics_cache")
            coverDir = File(cacheRoot, "covers")
            lyricsDir = File(cacheRoot, "lyrics")
            coverDir.mkdirs()
            lyricsDir.mkdirs()

            val mb = PrefsManager.getCacheMaxSizeMb()
            val clamped = mb.coerceIn(MIN_MAX_SIZE_MB.toInt(), MAX_MAX_SIZE_MB.toInt())
            maxSizeBytes = clamped.toLong() * 1024 * 1024
            initialized = true
        }
        // 启动时异步执行一次全盘 LRU 淘汰，将旧版本超标缓存(如 313MB)自动清理至配额以下
        enforceSizeLimitAsync()
    }

    private fun ensureInit() {
        if (!initialized) {
            try {
                init(com.carlyrics.CarLyricsApplication.instance)
            } catch (e: Exception) {
                AppLogger.e(TAG, "LyricsCacheManager auto-init failed: ${e.message}", e)
            }
        }
    }

    // ==================== 封面缓存 ====================

    // 内存活跃封面 LruCache：最大 4 张活跃封面，大小根据 byteCount 计算，总上限 8MB，支持 trimMemory
    private val memoryCoverCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * 系统低内存时释放内存中的封面位图缓存
     */
    fun clearMemoryCache() {
        synchronized(memoryCoverCache) {
            memoryCoverCache.evictAll()
        }
    }

    fun getCachedCover(url: String, reqWidth: Int = 600, reqHeight: Int = 600): Bitmap? {
        ensureInit()
        val memKey = "${url}_${reqWidth}x${reqHeight}"
        synchronized(memoryCoverCache) {
            val memHit = memoryCoverCache.get(memKey)
            if (memHit != null && !memHit.isRecycled) {
                return memHit
            } else if (memHit != null) {
                memoryCoverCache.remove(memKey)
            }
        }

        val file = coverFile(url)
        return if (file.exists()) {
            try {
                file.setLastModified(System.currentTimeMillis())
                // 智能下采样控制，防 OOM 内存暴增
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565 // 降低图片色彩深度节约 50% 内存
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                if (decoded != null) {
                    synchronized(memoryCoverCache) {
                        memoryCoverCache.put(memKey, decoded)
                    }
                }
                decoded
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to decode cached cover: ${e.message}")
                file.delete()
                null
            }
        } else null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun cacheCover(url: String, bitmap: Bitmap) {
        ensureInit()
        if (!bitmap.isRecycled) {
            val memKey = "${url}_600x600"
            synchronized(memoryCoverCache) {
                memoryCoverCache.put(memKey, bitmap)
            }
        }
        try {
            val file = coverFile(url)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.setLastModified(System.currentTimeMillis())
            enforceSizeLimitAsync()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache cover: ${e.message}", e)
        }
    }

    /**
     * 若本地已保存过原厂封面且文件有效，直接返回 file:// URI，跳过重复跨进程 Binder Bitmap 提取与 JPEG 压缩
     */
    fun getCachedNativeCoverUri(title: String, artist: String): String? {
        ensureInit()
        val key = "native_kugou_${title}_${artist}"
        val file = coverFile(key)
        return if (file.exists() && file.length() > 0L) {
            "file://${file.absolutePath}"
        } else null
    }

    /**
     * 保存车机原厂接口/U盘直读的封面 Bitmap 到本地磁盘缓存，返回本地 file:// URI 供 Glide 秒级加载
     */
    fun saveNativeCover(title: String, artist: String, bitmap: Bitmap): String? {
        ensureInit()
        return try {
            val key = "native_kugou_${title}_${artist}"
            val file = coverFile(key)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.setLastModified(System.currentTimeMillis())
            enforceSizeLimitAsync()
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save native cover: ${e.message}", e)
            null
        }
    }

    // ==================== 歌词缓存 ====================

    fun getCachedLyrics(title: String, artist: String): LyricData? {
        ensureInit()
        val file = lyricsFile(title, artist)
        return if (file.exists()) {
            try {
                file.setLastModified(System.currentTimeMillis())
                parseLyricData(file.readText())
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse cached lyrics: ${e.message}")
                null
            }
        } else null
    }

    fun cacheLyrics(title: String, artist: String, data: LyricData) {
        ensureInit()
        try {
            val json = JSONObject().apply {
                put("coverUrl", data.coverUrl ?: JSONObject.NULL)
                put("offset", data.offset)
                put("sourceName", data.sourceName)
                if (data.rawLrc != null) put("rawLrc", data.rawLrc)
                put("lines", JSONArray().apply {
                    data.lines.forEach { line ->
                        put(JSONObject().apply {
                            put("timestamp", line.timestamp)
                            put("text", line.text)
                            if (line.words.isNotEmpty()) {
                                put("words", JSONArray().apply {
                                    line.words.forEach { w ->
                                        put(JSONObject().apply {
                                            put("text", w.text)
                                            put("startTime", w.startTime)
                                            put("duration", w.duration)
                                        })
                                    }
                                })
                            }
                        })
                    }
                })
            }
            val file = lyricsFile(title, artist)
            file.writeText(json.toString())
            file.setLastModified(System.currentTimeMillis())
            enforceSizeLimitAsync()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache lyrics: ${e.message}", e)
        }
    }

    // ==================== 缓存管理 ====================

    fun clearAll(context: Context? = null) {
        ensureInit()
        try {
            if (::cacheRoot.isInitialized && cacheRoot.exists()) {
                cacheRoot.deleteRecursively()
                coverDir.mkdirs()
                lyricsDir.mkdirs()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear lyrics cache: ${e.message}", e)
        }

        // 同步清理内部私有 cacheDir 与外部 externalCacheDir 中的其他临时文件与 Glide 磁盘缓存
        val ctx = context?.applicationContext ?: appContext
        if (ctx != null) {
            try {
                ctx.cacheDir?.listFiles()?.forEach { file ->
                    if (file.name != "lyrics_cache") {
                        file.deleteRecursively()
                    }
                }
                ctx.externalCacheDir?.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to clear app cache files: ${e.message}", e)
            }
        }
    }

    fun getCacheSize(): Long {
        ensureInit()
        return try {
            if (::cacheRoot.isInitialized && cacheRoot.exists()) {
                cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * 获取应用全局完整真实缓存大小 (字节)
     * 包括：
     * 1. 歌词与封面私有磁盘缓存 (context.cacheDir/lyrics_cache)
     * 2. Glide 网络图片磁盘缓存 (context.cacheDir/image_manager_disk_cache)
     * 3. 应用内部私有 cacheDir 下的所有临时缓存文件
     * 4. 外部私有 externalCacheDir 下的缓存文件 (若存在)
     */
    fun getTotalCacheSizeBytes(context: Context): Long {
        var total = 0L
        try {
            context.cacheDir?.let { dir ->
                if (dir.exists()) {
                    total += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
            }
            context.externalCacheDir?.let { dir ->
                if (dir.exists()) {
                    total += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
            }
            if (total > maxSizeBytes) {
                enforceSizeLimitAsync(context)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getTotalCacheSizeBytes error: ${e.message}")
        }
        return total
    }

    fun setMaxSize(mb: Int) {
        val clamped = mb.coerceIn(MIN_MAX_SIZE_MB.toInt(), MAX_MAX_SIZE_MB.toInt())
        maxSizeBytes = clamped.toLong() * 1024 * 1024
        if (initialized) {
            enforceSizeLimit()
        }
    }

    fun getMaxSizeMb(): Long {
        return maxSizeBytes / (1024 * 1024)
    }

    // ==================== 内部 ====================

    private fun coverFile(url: String): File {
        val hash = md5(url)
        return File(coverDir, "$hash.jpg")
    }

    private fun lyricsFile(title: String, artist: String): File {
        val hash = md5("$title|$artist")
        return File(lyricsDir, "$hash.json")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseLyricData(jsonStr: String): LyricData? {
        return try {
            val obj = JSONObject(jsonStr)
            val linesArr = obj.getJSONArray("lines")
            val lines = (0 until linesArr.length()).map { i ->
                val lineObj = linesArr.getJSONObject(i)
                val wordsArr = lineObj.optJSONArray("words")
                val words = if (wordsArr != null) {
                    (0 until wordsArr.length()).map { j ->
                        val wObj = wordsArr.getJSONObject(j)
                        com.carlyrics.model.LyricWord(
                            text = wObj.getString("text"),
                            startTime = wObj.getLong("startTime"),
                            duration = wObj.getLong("duration")
                        )
                    }
                } else emptyList()
                LyricLine(
                    timestamp = lineObj.getLong("timestamp"),
                    text = lineObj.getString("text"),
                    words = words
                )
            }
            val coverUrl = if (obj.isNull("coverUrl")) null else obj.getString("coverUrl")
            val sourceName = obj.optString("sourceName", "")
            val rawLrc = if (obj.isNull("rawLrc")) null else obj.optString("rawLrc")
            LyricData(
                lines = lines,
                coverUrl = coverUrl,
                offset = obj.optLong("offset", 0L),
                sourceName = sourceName,
                rawLrc = rawLrc
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * LRU 淘汰：全盘管控应用真实缓存（cacheDir + externalCacheDir）。
     * 1. 优先清除 Glide 遗留的冗余磁盘缓存 (image_manager_disk_cache)
     * 2. 收集所有可清理的封面、歌词与临时文件，按 lastModified 升序（最旧的在前）淘汰，
     *    直到总占用严格降至 80% 水位线以下。
     */
    @Synchronized
    fun enforceSizeLimit(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: return
        try {
            val allFiles = mutableListOf<File>()
            ctx.cacheDir?.let { dir ->
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { allFiles.add(it) }
                }
            }
            ctx.externalCacheDir?.let { dir ->
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { allFiles.add(it) }
                }
            }

            var currentTotalSize = allFiles.sumOf { it.length() }
            if (currentTotalSize <= maxSizeBytes) return

            val curMB = currentTotalSize / (1024 * 1024)
            val maxMB = maxSizeBytes / (1024 * 1024)
            val targetBytes = (maxSizeBytes * 0.8).toLong()
            AppLogger.i(TAG, "⚡ 触发全局缓存 LRU 淘汰: 当前占用=${curMB}MB, 配额上限=${maxMB}MB, 目标水位线=${targetBytes / (1024 * 1024)}MB")

            // 1. 如果存在 Glide 遗留的磁盘缓存 (image_manager_disk_cache)，直接清空释放
            val glideDir = File(ctx.cacheDir, "image_manager_disk_cache")
            if (glideDir.exists()) {
                glideDir.listFiles()?.forEach { gf ->
                    val len = gf.length()
                    if (gf.delete()) {
                        currentTotalSize -= len
                    }
                }
            }

            if (currentTotalSize <= targetBytes) {
                AppLogger.i(TAG, "清理 Glide 冗余磁盘缓存后降至水位线: ${currentTotalSize / (1024 * 1024)}MB")
                return
            }

            // 2. 将剩余所有文件按 lastModified 升序（最旧的在前）淘汰
            val remainingFiles = allFiles
                .filter { it.exists() && it.isFile }
                .sortedBy { it.lastModified() }

            for (file in remainingFiles) {
                if (currentTotalSize <= targetBytes) break
                val len = file.length()
                if (file.delete()) {
                    currentTotalSize -= len
                }
            }

            AppLogger.i(TAG, "LRU 淘汰完成: 剩余全局缓存已降至 ${currentTotalSize / (1024 * 1024)}MB")
        } catch (e: Exception) {
            AppLogger.e(TAG, "enforceSizeLimit error: ${e.message}", e)
        }
    }

    @Volatile
    private var lastEnforceLimitMs: Long = 0L

    fun enforceSizeLimitAsync(context: Context? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastEnforceLimitMs > 0L && (now - lastEnforceLimitMs) < 45_000L) {
            return
        }
        lastEnforceLimitMs = now
        val ctx = context?.applicationContext ?: appContext
        cacheScope.launch {
            enforceSizeLimit(ctx)
        }
    }

    // ==================== 封面加载工具（缓存 + Glide） ====================

    /** 加载封面到 ImageView，优先本地缓存 */
    fun loadCoverInto(context: Context, url: String, target: ImageView) {
        val cached = getCachedCover(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        Glide.with(context)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    target.setImageBitmap(resource)
                    cacheCover(url, resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    /** 加载封面为 Bitmap 并回调，优先本地缓存 */
    fun loadCoverBitmap(context: Context, url: String, onReady: (Bitmap?) -> Unit) {
        val cached = getCachedCover(url)
        if (cached != null) {
            onReady(cached)
            return
        }
        Glide.with(context)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cacheCover(url, resource)
                    onReady(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    onReady(null)
                }
            })
    }

    /**
     * 车规级异步缩放封面加载（专供小欧屏 A / C 区域渲染，杜绝主线程磁盘 I/O 与超大分辨率 OOM）
     * 1. 先在 IO 线程尝试读取本地缓存或 file:// 路径（下采样至 sizePx × sizePx）；
     * 2. 未命中时使用 Glide override(sizePx, sizePx).centerCrop() 拉取，完成后异步落盘并在主线程回调。
     */
    fun loadCoverBitmapScaled(
        context: Context,
        url: String,
        sizePx: Int = 320,
        onReady: (Bitmap?) -> Unit
    ) {
        val appCtx = context.applicationContext ?: context
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        cacheScope.launch {
            val cached = getCachedCover(url, reqWidth = sizePx, reqHeight = sizePx)
            if (cached != null && !cached.isRecycled) {
                mainHandler.post { onReady(cached) }
                return@launch
            }
            mainHandler.post {
                try {
                    Glide.with(appCtx)
                        .asBitmap()
                        .load(url)
                        .override(sizePx, sizePx)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                cacheScope.launch {
                                    cacheCover(url, resource)
                                }
                                onReady(resource)
                            }

                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                onReady(null)
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                } catch (e: Exception) {
                    AppLogger.w(TAG, "loadCoverBitmapScaled failed: ${e.message}")
                    onReady(null)
                }
            }
        }
    }
}

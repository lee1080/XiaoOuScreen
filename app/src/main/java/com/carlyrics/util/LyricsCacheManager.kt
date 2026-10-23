package com.carlyrics.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.carlyrics.model.LyricData
import com.carlyrics.model.LyricLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 本地缓存管理器 —— 缓存封面图片和歌词数据。
 * 使用 LRU 策略管理磁盘缓存，默认最大 10MB。
 */
object LyricsCacheManager {

    private const val TAG = "LyricsCache"
    private const val DEFAULT_MAX_SIZE_MB = 10L
    private const val MIN_MAX_SIZE_MB = 5L
    private const val MAX_MAX_SIZE_MB = 50L

    private lateinit var cacheRoot: File
    private lateinit var coverDir: File
    private lateinit var lyricsDir: File
    private var maxSizeBytes: Long = DEFAULT_MAX_SIZE_MB * 1024 * 1024

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            cacheRoot = File(context.cacheDir, "lyrics_cache")
            coverDir = File(cacheRoot, "covers")
            lyricsDir = File(cacheRoot, "lyrics")
            coverDir.mkdirs()
            lyricsDir.mkdirs()

            val mb = PrefsManager.getCacheMaxSizeMb()
            setMaxSize(mb)
            initialized = true
        }
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

    fun getCachedCover(url: String, reqWidth: Int = 600, reqHeight: Int = 600): Bitmap? {
        ensureInit()
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
                BitmapFactory.decodeFile(file.absolutePath, options)
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
        try {
            val file = coverFile(url)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.setLastModified(System.currentTimeMillis())
            enforceSizeLimit()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache cover: ${e.message}", e)
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
            enforceSizeLimit()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cache lyrics: ${e.message}", e)
        }
    }

    // ==================== 缓存管理 ====================

    fun clearAll() {
        ensureInit()
        try {
            cacheRoot.deleteRecursively()
            coverDir.mkdirs()
            lyricsDir.mkdirs()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear cache: ${e.message}", e)
        }
    }

    fun getCacheSize(): Long {
        ensureInit()
        return cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
            LyricData(
                lines = lines,
                coverUrl = coverUrl,
                offset = obj.optLong("offset", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * LRU 淘汰：当缓存超过上限时，删除最旧的文件直到低于上限。
     */
    @Synchronized
    private fun enforceSizeLimit() {
        try {
            val allFiles = mutableListOf<File>()
            cacheRoot.walkTopDown().forEach { file ->
                if (file.isFile) allFiles.add(file)
            }
            val totalSize = allFiles.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            // 按最后修改时间升序（最旧的在前）
            allFiles.sortBy { it.lastModified() }

            var currentSize = totalSize
            for (file in allFiles) {
                if (currentSize <= maxSizeBytes * 0.8) break // 清理到 80% 水位线
                val fileSize = file.length()
                if (file.delete()) {
                    currentSize -= fileSize
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "enforceSizeLimit error: ${e.message}")
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
        Glide.with(context).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
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
        Glide.with(context).asBitmap().load(url).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                cacheCover(url, resource)
                onReady(resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                onReady(null)
            }
        })
    }
}

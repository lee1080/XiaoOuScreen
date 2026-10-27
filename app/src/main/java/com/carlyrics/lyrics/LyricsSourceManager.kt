package com.carlyrics.lyrics

import android.content.Context
import android.content.SharedPreferences
import com.carlyrics.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 歌词源管理系统 (负责歌词源文件的持久化、增删改查、排序、合集导入)
 */
object LyricsSourceManager {

    private const val TAG = "LyricsSourceManager"
    private const val PREFS_NAME = "lyrics_sources_prefs"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_ORDER_LIST = "sources_order"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var sourcesDir: File
    private val gson = Gson()

    @Volatile
    private var cachedSources: MutableList<LyricsSource> = mutableListOf()
    private val lock = Any()

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sourcesDir = File(appContext.filesDir, "lyrics_sources")
            if (!sourcesDir.exists()) {
                sourcesDir.mkdirs()
            }
            syncBuiltinSourcesFromAssets()
            reloadSources()
            isInitialized = true
        }
    }

    /**
     * 同步或无感升级 APK 内置的默认歌词源 (若本地缺失或内置版本高于本地，则自动更新，保留用户开关状态)
     */
    private fun syncBuiltinSourcesFromAssets() {
        try {
            val assetManager = appContext.assets
            val assetFiles = assetManager.list("lyrics_sources") ?: return
            for (filename in assetFiles) {
                if (!filename.endsWith(".js")) continue
                val script = assetManager.open("lyrics_sources/$filename").bufferedReader().use { it.readText() }
                val defaultId = filename.substringBeforeLast(".")
                val assetMeta = LyricsSourceRunner.parseMetadata(script, defaultId)
                if (assetMeta.id.isBlank()) continue

                val localFile = File(sourcesDir, "${assetMeta.id}.js")
                var shouldUpdate = false
                if (!localFile.exists()) {
                    shouldUpdate = true
                } else {
                    try {
                        val localScript = localFile.readText()
                        val localMeta = LyricsSourceRunner.parseMetadata(localScript, assetMeta.id)
                        if (isVersionGreater(assetMeta.version, localMeta.version)) {
                            shouldUpdate = true
                        }
                    } catch (_: Exception) {
                        shouldUpdate = true
                    }
                }

                if (shouldUpdate) {
                    localFile.writeText(script)
                    if (!prefs.contains(KEY_ENABLED_PREFIX + assetMeta.id)) {
                        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + assetMeta.id, true).apply()
                    }
                    AppLogger.d(TAG, "内置歌词源同步/升级: ${assetMeta.id} v${assetMeta.version}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "同步内置歌词源异常: ${e.message}", e)
        }
    }

    private fun isVersionGreater(v1: String, v2: String): Boolean {
        if (v1 == v2) return false
        val p1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val p2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val num1 = p1.getOrElse(i) { 0 }
            val num2 = p2.getOrElse(i) { 0 }
            if (num1 > num2) return true
            if (num1 < num2) return false
        }
        return false
    }

    /**
     * 从本地目录重新载入所有已安装的歌词源
     */
    fun reloadSources(): List<LyricsSource> {
        synchronized(lock) {
            val list = mutableListOf<LyricsSource>()
            val files = sourcesDir.listFiles { _, name -> name.endsWith(".js") } ?: emptyArray()

            val orderJson = prefs.getString(KEY_ORDER_LIST, "[]") ?: "[]"
            val orderList: List<String> = try {
                gson.fromJson(orderJson, Array<String>::class.java).toList()
            } catch (_: Exception) { emptyList() }

            for (file in files) {
                try {
                    val script = file.readText()
                    val defaultId = file.nameWithoutExtension
                    val meta = LyricsSourceRunner.parseMetadata(script, defaultId)
                    val isEnabled = prefs.getBoolean(KEY_ENABLED_PREFIX + meta.id, true)
                    val defaultOrder = when {
                        meta.id.contains("netease", true) -> 10
                        meta.id.contains("qq", true) -> 20
                        meta.id.contains("kugou", true) -> 30
                        meta.id.contains("kuwo", true) -> 40
                        meta.id.contains("qishui", true) || meta.id.contains("luna", true) -> 50
                        meta.id.contains("gdstudio", true) -> 80
                        meta.id.contains("lrclib", true) -> 90
                        else -> 100
                    }
                    val orderIndex = orderList.indexOf(meta.id).let { if (it >= 0) it else defaultOrder }

                    list.add(meta.copy(isEnabled = isEnabled, order = orderIndex))
                } catch (e: Exception) {
                    AppLogger.d(TAG, "加载歌词源失败: ${file.name}, ${e.message}")
                }
            }

            list.sortBy { it.order }
            cachedSources = list
            return cachedSources.toList()
        }
    }

    /**
     * 极速快照获取已安装源 (无锁非阻塞，若尚未完成首次扫描则返回当前轻量缓存或空，绝不阻塞 UI 主线程)
     */
    fun getCachedSourcesFast(): List<LyricsSource> {
        return cachedSources.toList()
    }

    /**
     * 获取全部已安装源
     */
    fun getAllSources(): List<LyricsSource> {
        synchronized(lock) {
            return cachedSources.toList()
        }
    }

    /**
     * 获取当前启用的源 (按优先级排序)
     */
    fun getEnabledSources(): List<LyricsSource> {
        synchronized(lock) {
            return cachedSources.filter { it.isEnabled }.sortedBy { it.order }
        }
    }

    /**
     * 根据当前播放器渠道（包名或渠道特征）查找最匹配的专属歌词源
     */
    fun findChannelMatchedSource(
        packageName: String?,
        availableSources: List<LyricsSource>
    ): LyricsSource? {
        if (packageName.isNullOrBlank()) return null
        val pkg = packageName.lowercase()

        // 1. QQ音乐系列 (QQ音乐手机版/车机版/Pad版/JOOX等)
        if (pkg.contains("qqmusic") || pkg.contains("tencent") || pkg.contains("joox") || pkg.contains("qmusic")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("qq") || name.contains("qq") || id.contains("tencent")
            }?.let { return it }
        }

        // 2. 网易云音乐系列 (网易云音乐手机版/车机版等)
        if (pkg.contains("netease") || pkg.contains("cloudmusic") || pkg.contains("163")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("netease") || id.contains("163") || name.contains("网易")
            }?.let { return it }
        }

        // 3. 酷我音乐系列 (酷我音乐手机版/车机版等)
        if (pkg.contains("kuwo") || pkg.contains("kwmusic")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("kuwo") || name.contains("酷我")
            }?.let { return it }
        }

        // 4. 酷狗音乐系列 (酷狗音乐手机版/车机版/概念版等)
        if (pkg.contains("kugou")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("kugou") || name.contains("酷狗")
            }?.let { return it }
        }

        // 5. 汽水音乐 / 抖音热歌系列 (汽水音乐、抖音、TikTok)
        if (pkg.contains("luna.music") || pkg.contains("qishui") || pkg.contains("aweme")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("qishui") || id.contains("luna") || name.contains("汽水")
            }?.let { return it }
        }

        // 6. 国际流媒体 (Spotify / Apple Music / YouTube Music)
        if (pkg.contains("spotify") || pkg.contains("apple.android.music") || pkg.contains("youtube")) {
            availableSources.find { s ->
                val id = s.id.lowercase()
                val name = s.name.lowercase()
                id.contains("lrclib") || name.contains("lrclib") || id.contains("spotify")
            }?.let { return it }
        }

        return null
    }

    /**
     * 对歌词源 ID 进行强制安全清洗，杜绝 ../ 等路径穿越字符
     */
    fun sanitizeSourceId(rawId: String): String {
        val cleaned = rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_").trim('_')
        return if (cleaned.isBlank()) "source_${System.currentTimeMillis()}" else cleaned
    }

    /**
     * 获取安全的歌词源目标文件，并强制验证规范化路径位于 sourcesDir 内部
     */
    private fun getSafeSourceFile(id: String): File? {
        val safeId = sanitizeSourceId(id)
        val file = File(sourcesDir, "$safeId.js")
        return try {
            if (file.canonicalPath.startsWith(sourcesDir.canonicalPath + File.separator)) {
                file
            } else {
                AppLogger.e(TAG, "检测到路径穿越尝试，已拦截: id=$id")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析安全路径异常: ${e.message}")
            null
        }
    }

    /**
     * 保存或更新单个源
     */
    fun saveSource(source: LyricsSource): Boolean {
        synchronized(lock) {
            return try {
                if (!sourcesDir.exists()) {
                    sourcesDir.mkdirs()
                }
                val safeId = sanitizeSourceId(source.id)
                val file = getSafeSourceFile(safeId) ?: return false
                file.writeText(source.script)
                prefs.edit().putBoolean(KEY_ENABLED_PREFIX + safeId, source.isEnabled).apply()
                reloadSources()
                true
            } catch (e: Exception) {
                AppLogger.d(TAG, "保存源失败: ${source.id}, ${e.message}")
                false
            }
        }
    }

    /**
     * 启用/停用源
     */
    fun toggleSource(id: String, enabled: Boolean) {
        synchronized(lock) {
            val safeId = sanitizeSourceId(id)
            prefs.edit().putBoolean(KEY_ENABLED_PREFIX + safeId, enabled).apply()
            cachedSources.find { it.id == safeId || it.id == id }?.isEnabled = enabled
        }
    }

    /**
     * 删除歌词源
     */
    fun deleteSource(id: String): Boolean {
        synchronized(lock) {
            val safeId = sanitizeSourceId(id)
            val file = getSafeSourceFile(safeId) ?: return false
            val deleted = file.delete()
            prefs.edit().remove(KEY_ENABLED_PREFIX + safeId).apply()
            reloadSources()
            return deleted
        }
    }

    /**
     * 更新排序顺序
     */
    fun updateOrder(orderedIds: List<String>) {
        synchronized(lock) {
            prefs.edit().putString(KEY_ORDER_LIST, gson.toJson(orderedIds)).apply()
            reloadSources()
        }
    }

    /**
     * 导入单源 JS 文本
     */
    fun importFromJsContent(jsContent: String, defaultId: String = ""): Result<LyricsSource> {
        return try {
            if (jsContent.isBlank()) {
                return Result.failure(IllegalArgumentException("脚本内容为空"))
            }
            val meta = LyricsSourceRunner.parseMetadata(jsContent, defaultId)
            val success = saveSource(meta.copy(isEnabled = true))
            if (success) {
                Result.success(meta)
            } else {
                Result.failure(IllegalStateException("写入源文件失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析合集内容 (JSON 字符串，支持包含 sources 字段的对象格式或源数组格式)
     */
    fun parseCollectionJson(jsonString: String): Result<LyricsCollection> {
        return try {
            val trimmed = jsonString.trim()
            val collection = if (trimmed.startsWith("[")) {
                val listType = object : TypeToken<List<LyricsSourceItem>>() {}.type
                val items: List<LyricsSourceItem> = gson.fromJson(trimmed, listType) ?: emptyList()
                LyricsCollection(name = "歌词源合集", sources = items)
            } else {
                gson.fromJson(trimmed, LyricsCollection::class.java)
            }
            if (collection == null) {
                return Result.failure(IllegalArgumentException("合集 JSON 格式无效"))
            }
            val sources = collection.sources ?: emptyList()
            if (sources.isEmpty()) {
                Result.failure(IllegalArgumentException("合集中未包含任何歌词源"))
            } else {
                Result.success(collection.copy(sources = sources))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 ZIP 输入流中解析歌词源合集
     * 自动遍历 ZIP 内所有的 .js 文件（忽略 __MACOSX、.DS_Store 等无关条目）
     * 若 ZIP 内包含 index.json 或 sources.json，也会与 .js 文件自动关联元数据
     */
    fun parseCollectionFromZip(inputStream: InputStream, zipName: String = "本地ZIP压缩包"): Result<LyricsCollection> {
        return try {
            val zipStream = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipStream.nextEntry
            val jsFiles = mutableMapOf<String, String>() // baseName -> script content
            var collectionJsonStr: String? = null

            while (entry != null) {
                val name = entry.name
                // 忽略 macOS 压缩包中的临时文件、隐藏文件以及目录
                if (!entry.isDirectory && !name.startsWith("__MACOSX") && !name.contains("/.") && !name.startsWith(".")) {
                    val baseName = File(name).name
                    if (baseName.endsWith(".js", ignoreCase = true)) {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zipStream.read(buffer).also { len = it } > 0) {
                            baos.write(buffer, 0, len)
                        }
                        val content = baos.toString("UTF-8")
                        jsFiles[baseName] = content
                    } else if (baseName.equals("index.json", ignoreCase = true) || baseName.equals("sources.json", ignoreCase = true)) {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zipStream.read(buffer).also { len = it } > 0) {
                            baos.write(buffer, 0, len)
                        }
                        val content = baos.toString("UTF-8")
                        collectionJsonStr = content
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }

            if (jsFiles.isEmpty()) {
                return Result.failure(IllegalArgumentException("ZIP 压缩包中未找到任何 .js 歌词源文件"))
            }

            val items = mutableListOf<LyricsSourceItem>()
            val usedJsNames = mutableSetOf<String>()

            // 1. 如果存在 index.json / sources.json，优先匹配其中定义的元数据与对应 JS
            if (!collectionJsonStr.isNullOrBlank()) {
                try {
                    val baseCollection = parseCollectionJson(collectionJsonStr).getOrNull()
                    if (baseCollection?.sources != null) {
                        for (item in baseCollection.sources) {
                            // 查找对应的 js 文件 (根据 url 末尾文件名、id 或 name)
                            val matchedFileName = jsFiles.keys.find { jsName ->
                                item.url?.endsWith("/$jsName") == true ||
                                item.url?.equals(jsName, ignoreCase = true) == true ||
                                jsName.equals("${item.id}.js", ignoreCase = true) ||
                                jsName.equals("${item.name}.js", ignoreCase = true)
                            }
                            if (matchedFileName != null) {
                                usedJsNames.add(matchedFileName)
                                val script = jsFiles[matchedFileName] ?: ""
                                items.add(item.copy(script = script))
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "解析内置合集 JSON 失败，回退至纯 JS 解析: ${e.message}")
                }
            }

            // 2. 对于没有在 JSON 中匹配到的其余 .js（或纯 JS 压缩包），直接提取头部元数据
            for ((fileName, script) in jsFiles) {
                if (usedJsNames.contains(fileName)) continue

                val defaultId = fileName.removeSuffix(".js").removeSuffix(".JS")
                val meta = LyricsSourceRunner.parseMetadata(script, defaultId)
                items.add(
                    LyricsSourceItem(
                        id = meta.id,
                        name = meta.name,
                        version = meta.version,
                        author = meta.author,
                        description = meta.description,
                        url = null,
                        script = script
                    )
                )
            }

            if (items.isEmpty()) {
                return Result.failure(IllegalArgumentException("未能从 ZIP 压缩包解析出任何有效歌词源"))
            }

            val cleanZipTitle = zipName.removeSuffix(".zip").removeSuffix(".ZIP")
            val collection = LyricsCollection(
                name = "本地压缩包: $cleanZipTitle",
                version = "1.0.0",
                description = "包含 ${items.size} 个解压出的 JS 歌词源",
                author = "本地导入",
                updatedAt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                sources = items
            )
            Result.success(collection)
        } catch (e: Exception) {
            AppLogger.e(TAG, "parseCollectionFromZip 异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 从合集中批量导入用户勾选的源
     */
    suspend fun importFromCollection(
        collection: LyricsCollection,
        selectedIds: Set<String>,
        collectionUrl: String? = null
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val sources = collection.sources ?: emptyList()
        for (item in sources) {
            val itemId = item.id?.trim() ?: ""
            // 如果指定了勾选集合，根据 itemId 进行判断
            if (selectedIds.isNotEmpty() && itemId.isNotEmpty() && !selectedIds.contains(itemId)) {
                continue
            }

            try {
                var scriptCode = item.script?.trim() ?: ""
                val itemUrl = item.url?.trim() ?: ""

                // 若合集中通过远程 URL 引用脚本，则在线拉取 (支持相对路径自动基于合集 CDN 拼接，或 GitHub Raw 自动转 CDN)
                if (scriptCode.isBlank()) {
                    val downloadUrl = resolveSourceDownloadUrl(itemUrl, itemId, item.name, collectionUrl)
                    if (downloadUrl.isNotBlank()) {
                        AppLogger.d(TAG, "从 CDN/远程拉取单个源: $downloadUrl (源: ${item.name ?: itemId})")
                        val resp = fetchUrlStringWithRetry(downloadUrl)
                        if (!resp.isNullOrBlank()) {
                            scriptCode = resp.trim()
                        } else {
                            AppLogger.e(TAG, "从远程拉取源脚本失败: $downloadUrl")
                        }
                    }
                }

                if (scriptCode.isNotBlank()) {
                    // 解析脚本内部 @name, @id 等元数据，做好防空兜底
                    val parsedMeta = LyricsSourceRunner.parseMetadata(scriptCode, itemId)

                    val finalId = when {
                        itemId.isNotBlank() -> itemId
                        parsedMeta.id.isNotBlank() -> parsedMeta.id
                        else -> "source_" + System.currentTimeMillis()
                    }
                    val finalName = when {
                        !item.name.isNullOrBlank() -> item.name.trim()
                        parsedMeta.name.isNotBlank() -> parsedMeta.name
                        else -> finalId
                    }
                    val finalVersion = when {
                        !item.version.isNullOrBlank() -> item.version.trim()
                        parsedMeta.version.isNotBlank() -> parsedMeta.version
                        else -> "1.0.0"
                    }
                    val finalAuthor = when {
                        !item.author.isNullOrBlank() -> item.author.trim()
                        parsedMeta.author.isNotBlank() -> parsedMeta.author
                        else -> ""
                    }
                    val finalDesc = when {
                        !item.description.isNullOrBlank() -> item.description.trim()
                        parsedMeta.description.isNotBlank() -> parsedMeta.description
                        else -> ""
                    }
                    val finalUrl = itemUrl.ifBlank { parsedMeta.url }

                    val source = LyricsSource(
                        id = finalId,
                        name = finalName,
                        version = finalVersion,
                        author = finalAuthor,
                        description = finalDesc,
                        script = scriptCode,
                        url = finalUrl,
                        isEnabled = true
                    )
                    if (saveSource(source)) {
                        count++
                        AppLogger.d(TAG, "成功导入歌词源: $finalId ($finalName)")
                    } else {
                        AppLogger.e(TAG, "保存歌词源失败: $finalId ($finalName)")
                    }
                } else {
                    AppLogger.e(TAG, "源 [${item.name ?: itemId}] 脚本内容为空且未配置有效远程地址")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入源 [${item.name ?: item.id}] 失败: ${e.message}", e)
            }
        }
        count
    }

    /**
     * 智能解析单个源的下载链接：
     * 1. 若来源于 jsDelivr CDN 合集链接，相对路径或 GitHub 原始链接自动转接为 jsDelivr CDN 直链；
     * 2. 若来源于 ghproxy 镜像，自动追加 ghproxy 前缀；
     * 3. 针对国内车机无代理环境，自动将 raw.githubusercontent.com 映射为免翻 CDN；
     * 4. 自动防止 sources/ 路径重复叠加。
     */
    fun resolveSourceDownloadUrl(
        itemUrl: String?,
        itemId: String?,
        itemName: String?,
        collectionUrl: String?
    ): String {
        val rawUrl = itemUrl?.trim() ?: ""
        val collUrl = collectionUrl?.trim() ?: ""

        // 1. 若 itemUrl 为绝对网络链接
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            // 如果合集走 jsDelivr CDN，或者 rawUrl 本身包含 GitHub 原始链接，自动转为 jsDelivr CDN 直链
            if (collUrl.contains("cdn.jsdelivr.net/gh/") || rawUrl.contains("raw.githubusercontent.com") || rawUrl.contains("github.com")) {
                val cdnMapped = mapGithubToJsdelivr(rawUrl)
                if (cdnMapped.isNotBlank()) return cdnMapped
            }
            // 如果合集走 ghproxy 镜像，且单个源链接尚未包含 ghproxy，自动拼接镜像前缀
            if (collUrl.contains("ghproxy") && !rawUrl.contains("ghproxy")) {
                return "https://ghproxy.net/$rawUrl"
            }
            return rawUrl
        }

        // 2. 若 itemUrl 为空，尝试通过 itemId 或 itemName 推断文件名
        val targetFile = when {
            rawUrl.isNotBlank() -> rawUrl.removePrefix("./")
            !itemId.isNullOrBlank() -> {
                val cleanId = itemId.replace("_official", "").replace("_proxy", "").replace("_public", "")
                "$cleanId.js"
            }
            else -> ""
        }

        if (targetFile.isBlank()) return ""

        // 3. 根据 collectionUrl 拼接基础 CDN / 镜像路径
        if (collUrl.isNotBlank()) {
            val baseDir = collUrl.substringBeforeLast("/") + "/"
            // 防止 sources/ 路径重复叠加 (如 baseDir 已经有 /sources/，而 targetFile 是 sources/xxx.js)
            val cleanTarget = if (baseDir.endsWith("/sources/") && targetFile.startsWith("sources/")) {
                targetFile.removePrefix("sources/")
            } else {
                targetFile
            }

            try {
                return java.net.URI.create(baseDir).resolve(cleanTarget).toString()
            } catch (e: Exception) {
                AppLogger.d(TAG, "URI resolve 异常: ${e.message}, 回退直接拼接")
                return baseDir + cleanTarget
            }
        }

        // 4. 若无合集 URL，默认拼接到官方 jsDelivr CDN
        val finalFileName = targetFile.substringAfterLast("/")
        return "https://cdn.jsdelivr.net/gh/lee1080/carlyrics-hub@main/sources/$finalFileName"
    }

    /**
     * 将 raw.githubusercontent.com 或 github.com 转换为 jsDelivr CDN 免翻直链
     */
    fun mapGithubToJsdelivr(url: String): String {
        val trimmed = url.trim()
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (trimmed.startsWith(rawPrefix)) {
            val path = trimmed.removePrefix(rawPrefix)
            val parts = path.split("/")
            if (parts.size >= 3) {
                val owner = parts[0]
                val repo = parts[1]
                val branch = parts[2]
                val rest = parts.drop(3).joinToString("/")
                return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$rest"
            }
        }
        val ghPrefix = "https://github.com/"
        if (trimmed.startsWith(ghPrefix)) {
            val path = trimmed.removePrefix(ghPrefix)
            val parts = path.split("/")
            if (parts.size >= 4 && (parts[2] == "raw" || parts[2] == "blob")) {
                val owner = parts[0]
                val repo = parts[1]
                val branch = parts[3]
                val rest = parts.drop(4).joinToString("/")
                return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$rest"
            }
        }
        return ""
    }

    /**
     * 辅助网络拉取 (带详细错误结果与状态码反馈)
     */
    suspend fun fetchUrlResult(url: String): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .build()
            LyricsSourceRunner.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val str = resp.body?.string()
                    if (!str.isNullOrBlank()) {
                        Result.success(str)
                    } else {
                        Result.failure(Exception("服务器响应内容为空 (HTTP ${resp.code})"))
                    }
                } else {
                    Result.failure(Exception("HTTP ${resp.code} ${resp.message}"))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "fetchUrlResult failed ($url): ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun fetchUrlString(url: String): String? {
        return fetchUrlResult(url).getOrNull()
    }

    /**
     * 拉取二进制数据 (用于下载 ZIP 等二进制合集)
     */
    suspend fun fetchUrlBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .build()
            LyricsSourceRunner.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "fetchUrlBytes failed ($url): ${e.message}", e)
            null
        }
    }

    /**
     * 带 CDN / 镜像线路自动重试的高可用拉取 (国内车机无科学上网自动秒级兜底)
     */
    suspend fun fetchUrlStringWithRetry(url: String): String? {
        val firstRes = fetchUrlResult(url)
        if (firstRes.isSuccess && !firstRes.getOrNull().isNullOrBlank()) {
            return firstRes.getOrNull()
        }

        // 首次拉取失败，尝试备用加速直链 (针对国内车机无翻墙网络环境兜底)
        val fallbackUrls = mutableListOf<String>()
        if (url.contains("raw.githubusercontent.com") || url.contains("github.com")) {
            val cdn = mapGithubToJsdelivr(url)
            if (cdn.isNotBlank() && cdn != url) fallbackUrls.add(cdn)
            fallbackUrls.add("https://ghproxy.net/$url")
        } else if (url.contains("cdn.jsdelivr.net/gh/")) {
            // jsDelivr CDN 失败时尝试 ghproxy 镜像
            val ghRaw = url.replace("https://cdn.jsdelivr.net/gh/", "https://raw.githubusercontent.com/")
                .replace("@", "/")
            fallbackUrls.add("https://ghproxy.net/$ghRaw")
        }

        for (alt in fallbackUrls) {
            AppLogger.d(TAG, "主线路拉取失败，尝试备用加速线路: $alt")
            val altRes = fetchUrlResult(alt)
            if (altRes.isSuccess && !altRes.getOrNull().isNullOrBlank()) {
                AppLogger.d(TAG, "备用直链拉取成功: $alt")
                return altRes.getOrNull()
            }
        }
        return null
    }
}

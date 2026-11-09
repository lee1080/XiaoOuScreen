package com.carlyrics.lyrics

import com.carlyrics.model.LyricData
import com.carlyrics.model.LyricLine
import com.carlyrics.model.LyricWord
import com.carlyrics.util.AppLogger
import com.carlyrics.util.LyricsCacheManager
import com.carlyrics.util.PrefsManager
import com.carlyrics.util.T2SUtil
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 歌词获取调度管理器 (纯净空壳调度核心)
 * 不内置任何版权音乐平台私有接口，全权委托外部安装的 JavaScript 歌词源插件执行
 */
class LyricsApiManager {

    companion object {
        private const val TAG = "LyricsApiManager"

        var LYRICS_API_URL = ""
        var COVER_API_URL = ""
        var API_KEY = ""

        val sharedClient: OkHttpClient by lazy {
            LyricsSourceRunner.client
        }

        private val preloadCache = object : LinkedHashMap<String, LyricData>(3, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricData>?): Boolean {
                return size > 3
            }
        }
        private val cacheLock = Any()

        fun clearPreloadCache() {
            synchronized(cacheLock) {
                preloadCache.clear()
            }
        }
    }

    private val gson = Gson()

    fun cancelAllRequests() {
        LyricsSourceRunner.cancelAll()
    }

    private fun storeLyricsCache(title: String, artist: String, data: LyricData) {
        synchronized(cacheLock) {
            preloadCache["$title|$artist"] = data
        }
        LyricsCacheManager.cacheLyrics(title, artist, data)
    }

    fun getCachedLyrics(title: String, artist: String): LyricData? {
        synchronized(cacheLock) {
            preloadCache["$title|$artist"]?.let { return it }
        }
        return LyricsCacheManager.getCachedLyrics(title, artist)?.also { diskData ->
            synchronized(cacheLock) {
                preloadCache["$title|$artist"] = diskData
            }
        }
    }

    suspend fun prefetchAndCache(
        title: String,
        artist: String,
        duration: Long = 0L,
        mediaId: String? = null,
        packageName: String? = null
    ) {
        try {
            val result = fetchLyrics(title, artist, duration, mediaId, packageName)
            result.onSuccess { data ->
                storeLyricsCache(title, artist, data)
            }
        } catch (_: Exception) {}
    }

    private fun httpGet(url: String, headers: Map<String, String>? = null): String? {
        val reqBuilder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
        return try {
            sharedClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "httpGet 失败: $url, err=${e.message}")
            null
        }
    }

    private fun httpPostForm(url: String, formParams: Map<String, String>, headers: Map<String, String>? = null): String? {
        val formBody = FormBody.Builder().apply {
            formParams.forEach { (k, v) -> add(k, v) }
        }.build()
        val reqBuilder = Request.Builder().url(url).post(formBody)
        headers?.forEach { (k, v) -> reqBuilder.header(k, v) }
        return try {
            sharedClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "httpPostForm 失败: $url, err=${e.message}")
            null
        }
    }

    fun fetchFromNeteaseOfficial(title: String, artist: String): LyricData? {
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            return null
        }
        try {
            val searchUrl = "https://music.163.com/api/search/get"
            val headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            val params = mapOf(
                "s" to "$title $artist".trim(),
                "type" to "1",
                "limit" to "5",
                "offset" to "0"
            )
            val resp = httpPostForm(searchUrl, params, headers) ?: return null
            val map = gson.fromJson(resp, Map::class.java) ?: return null
            val result = map["result"] as? Map<*, *> ?: return null
            val songs = result["songs"] as? List<*> ?: return null
            if (songs.isEmpty()) return null

            var matchedSongId: Long? = null
            var matchedCoverUrl: String? = null
            var matchedTitle: String? = null
            var matchedArtist: String? = null

            for (sObj in songs) {
                val song = sObj as? Map<*, *> ?: continue
                val sTitle = song["name"]?.toString() ?: ""
                val artists = song["artists"] as? List<*>
                val sArtist = artists?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }?.joinToString(" / ") ?: ""

                val tScore = evaluateTitleMatch(title, sTitle)
                val aScore = evaluateArtistMatch(artist, sArtist)
                // 严苛比对：标题相似度必须 >= 70，且若提供了歌手则歌手评分必须 >= 0
                if (tScore >= 70 && (if (artist.isNotBlank()) aScore >= 0 else true)) {
                    val idNum = (song["id"] as? Number)?.toLong() ?: continue
                    matchedSongId = idNum
                    matchedTitle = sTitle
                    matchedArtist = sArtist
                    val album = song["album"] as? Map<*, *>
                    matchedCoverUrl = album?.get("picUrl")?.toString()
                    break
                }
            }

            val songId = matchedSongId ?: return null
            val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            val lyricResp = httpGet(lyricUrl, headers) ?: return null
            val lyricMap = gson.fromJson(lyricResp, Map::class.java) ?: return null
            val lrcObj = lyricMap["lrc"] as? Map<*, *> ?: return null
            val rawLrc = lrcObj["lyric"]?.toString() ?: return null
            val lines = parseLrcText(rawLrc)
            if (lines.isEmpty()) return null

            if (matchedCoverUrl.isNullOrEmpty()) {
                matchedCoverUrl = fetchCoverFromNeteaseOfficialById(songId)
            }

            AppLogger.d(TAG, "网易云官方直连命中: $matchedTitle - $matchedArtist (${lines.size}行)")
            val data = LyricData(
                lines = lines,
                coverUrl = matchedCoverUrl,
                sourceName = "网易云音乐(官方直连)",
                rawLrc = rawLrc
            )
            storeLyricsCache(title, artist, data)
            return data
        } catch (e: Exception) {
            AppLogger.d(TAG, "fetchFromNeteaseOfficial 异常: ${e.message}")
            return null
        }
    }

    fun fetchCoverFromNeteaseOfficialById(songId: Long): String? {
        try {
            val url = "https://music.163.com/api/v3/song/detail?c=" + URLEncoder.encode("[{\"id\":$songId}]", "UTF-8")
            val headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            val resp = httpGet(url, headers) ?: return null
            val map = gson.fromJson(resp, Map::class.java) ?: return null
            val songs = map["songs"] as? List<*> ?: return null
            val first = songs.firstOrNull() as? Map<*, *> ?: return null
            val al = first["al"] as? Map<*, *> ?: return null
            return al["picUrl"]?.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun normalizeText(text: String): String {
        return text.trim()
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""（.*?）"""), "")
            .replace(Regex("""【.*?】"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .lowercase()
    }

    /** 去除曲名中的修饰性括号、后缀标签等（如“（你我怎么两清）”、“（DJ版）”、“ - 热播片段”） */
    private fun cleanSongTitle(raw: String): String {
        return raw.trim()
            .replace(Regex("""（.*?）"""), "")
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""【.*?】"""), "")
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""\s*[-_—].*$"""), "")
            .trim()
    }

    /** 英文曲名弱化 your/my/the 差异（此时 text 已无空格） */
    private fun softenEnglishTitle(text: String): String {
        if (!text.any { it in 'a'..'z' }) return text
        var s = text
        for (w in listOf("your", "our", "the", "and", "my")) {
            s = s.replace(w, "")
        }
        return s
    }

    /** 常见歌曲版本修饰后缀词集合 */
    private val VERSION_SUFFIXES = setOf(
        "live", "remix", "伴奏", "dj", "片段", "翻唱", "cover",
        "instrumental", "acoustic", "radioedit", "version", "deluxe",
        "纯音乐", "高潮版", "完整版", "新版", "原版", "重唱版", "合唱版", "无损",
        "3d环绕版", "dj版", "现场版", "正式版", "重置版", "试听版"
    )

    /**
     * 严谨的歌曲标题匹配判断
     * 返回评分：
     * 100: 核心标题完全精确吻合
     * 75: 标题前缀匹配且去除版本后缀（如 Live/Remix/伴奏）后一致，或长度比例 >= 0.7
     * 0: 不匹配（绝不乱匹配）
     */
    fun evaluateTitleMatch(reqTitle: String, resTitle: String): Int {
        val req = normalizeText(reqTitle)
        val res = normalizeText(resTitle)
        if (req.isEmpty() || res.isEmpty()) return 0

        val sReq = T2SUtil.convert(req)
        val sRes = T2SUtil.convert(res)

        // 1. 完全一致
        if (sReq == sRes) return 100

        // 2. 弱化英文虚词后完全一致
        val softReq = softenEnglishTitle(sReq)
        val softRes = softenEnglishTitle(sRes)
        if (softReq.isNotEmpty() && softReq == softRes) return 100

        // 3. 包含关系校验 (严防“爱”匹配“爱如潮水”等短词泛滥误匹配)
        val shorter: String
        val longer: String
        if (sReq.length <= sRes.length) {
            shorter = sReq
            longer = sRes
        } else {
            shorter = sRes
            longer = sReq
        }

        // 单字标题绝对禁止子串模糊包含匹配，必须 100% 相等
        if (shorter.length <= 1) {
            return 0
        }

        if (longer.contains(shorter)) {
            // 严格白名单过滤：检查 longer 去掉 shorter 之后，多出来的部分是否全属于已知的版本修饰词 (如 Live, Remix, 伴奏等)
            // 绝不能单凭 lengthRatio >= 0.70 就认定匹配（例如“小城故事”与“小城故事多”完全是不同歌曲！）
            var remainder = longer.replace(shorter, "").trim()
            for (suffix in VERSION_SUFFIXES) {
                remainder = remainder.replace(suffix, "")
            }
            if (remainder.isEmpty() || remainder.length <= 1) {
                return 75
            }
        }

        return 0
    }

    fun isTitleMatch(reqTitle: String, resTitle: String): Boolean {
        return evaluateTitleMatch(reqTitle, resTitle) > 0
    }

    /**
     * 歌手匹配评估：
     * 30: 歌手完全匹配或多歌手包含
     * 0: 歌手信息为空（中立）
     * -100: 歌手明确不匹配（彻底排除翻唱或同名歌曲）
     */
    fun evaluateArtistMatch(reqArtist: String, resArtist: String): Int {
        if (reqArtist.isBlank()) return 0 // 请求方未提供歌手，不惩罚也不加分
        if (resArtist.isBlank()) return 0 // 搜索结果未返回歌手，中立

        val req = normalizeText(reqArtist)
        val res = normalizeText(resArtist)

        val sReq = T2SUtil.convert(req)
        val sRes = T2SUtil.convert(res)

        // 1. 完全一致
        if (sReq.isNotEmpty() && sReq == sRes) return 30

        // 2. 知名艺名映射
        val aliases = mapOf(
            "crowdlu" to "卢广仲",
            "jaychou" to "周杰伦",
            "jjlin" to "林俊杰",
            "gem" to "邓紫棋",
            "gem邓紫棋" to "邓紫棋",
            "easonchan" to "陈奕迅",
            "mayday" to "五月天",
            "faye" to "王菲",
            "beyond" to "beyond"
        )
        val reqAlias = aliases[sReq]
        val resAlias = aliases[sRes]
        if (reqAlias != null && (normalizeText(reqAlias) == sRes || sRes.contains(normalizeText(reqAlias)))) return 30
        if (resAlias != null && (normalizeText(resAlias) == sReq || sReq.contains(normalizeText(resAlias)))) return 30

        // 3. 多歌手拆分匹配 (支持 "YOON MIRAE, Tiger JK & Bizzy", "周杰伦 & 蔡依林", "A / B", "A feat. B")
        // 注意：必须在进行 normalizeText 去除非字母数字前，按分隔符完成多歌手拆分！
        val delimiter = Regex("""(?i)\s*(?:feat\.?|ft\.?|with|vs\.?|[&、/,\+，;；])\s*""")
        val reqArtists = reqArtist.split(delimiter).map { T2SUtil.convert(normalizeText(it)) }.filter { it.isNotEmpty() }
        val resArtists = resArtist.split(delimiter).map { T2SUtil.convert(normalizeText(it)) }.filter { it.isNotEmpty() }

        val anyMatch = reqArtists.any { r ->
            resArtists.any { a ->
                r == a || (r.length >= 2 && a.length >= 2 && (r.contains(a) || a.contains(r)))
            }
        }
        if (anyMatch) return 30

        // 明确提供了歌手，但完全不匹配（如请求周杰伦，返回蓝心羽翻唱）
        return -100
    }

    fun isArtistMatch(reqArtist: String, resArtist: String): Boolean {
        return evaluateArtistMatch(reqArtist, resArtist) >= 0
    }

    /**
     * 依据【1.播放器渠道自动识别 (最高优先级) -> 2.用户偏好源 (次优先级) -> 3.其余全量已启用源 (严格兜底)】
     * 重排歌词源瀑布流调度优先级
     */
    private fun sortSourcesByPreferenceAndChannel(
        sources: MutableList<LyricsSource>,
        packageName: String?
    ) {
        val actualPkg = packageName?.ifBlank { null }
            ?: try { com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.packageName } catch (_: Exception) { null }

        // 1. 最高优先级：若匹配到具体播放器渠道，默认优先选择对应的专属歌词源
        val channelMatched = LyricsSourceManager.findChannelMatchedSource(actualPkg, sources)

        val sortedList = mutableListOf<LyricsSource>()

        if (channelMatched != null) {
            sortedList.add(channelMatched)
            AppLogger.d(TAG, "【播放器渠道命中】[$actualPkg] 自动优先调度专属源: ${channelMatched.name} (${channelMatched.id})")
        }

        // 2. 若未匹配到播放器渠道 (如蓝牙音乐) 或其余所有源：严格按照用户在设置中手动拖拽排序的优先级顺序查询
        for (src in sources) {
            if (sortedList.none { it.id == src.id }) {
                sortedList.add(src)
            }
        }

        sources.clear()
        sources.addAll(sortedList)
        AppLogger.d(TAG, "歌词源瀑布流调度序列更新为: ${sources.joinToString(" -> ") { it.name }}")
    }

    /**
     * 获取歌词核心调度
     */
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Long = 0L,
        mediaId: String? = null,
        packageName: String? = null
    ): Result<LyricData> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            AppLogger.d(TAG, "fetchLyrics 忽略无效/占位符曲名: title=$title, artist=$artist")
            return@withContext Result.failure(IllegalArgumentException("无效的曲目标题"))
        }

        getCachedLyrics(title, artist)?.let {
            AppLogger.d(TAG, "从预加载缓存命中歌词: $title - $artist")
            return@withContext Result.success(it)
        }

        // 若当前播放器为网易云或偏好源为 netease，优先尝试官方直连
        val actualPkg = packageName ?: try { com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.packageName } catch (_: Exception) { null }
        if (actualPkg?.contains("netease") == true || PrefsManager.getPreferredLyricsSource().contains("netease")) {
            coroutineContext.ensureActive()
            val neteaseDirect = fetchFromNeteaseOfficial(title, artist)
            if (neteaseDirect != null && neteaseDirect.lines.isNotEmpty()) {
                storeLyricsCache(title, artist, neteaseDirect)
                return@withContext Result.success(neteaseDirect)
            }
        }

        val enabledSources = LyricsSourceManager.getEnabledSources().toMutableList()
        if (enabledSources.isEmpty()) {
            AppLogger.d(TAG, "未安装或未启用任何歌词源插件")
            return@withContext Result.failure(IllegalStateException("未安装任何歌词源，请在设置中导入歌词源"))
        }

        // 偏好源动态优先与渠道智能调度
        sortSourcesByPreferenceAndChannel(enabledSources, packageName)

        val durationSec = duration / 1000L

        // 瀑布流遍历已启用源
        for (source in enabledSources) {
            coroutineContext.ensureActive()
            try {
                // 1. 若支持 match 且 mediaId 存在，优先尝试直连
                if (!mediaId.isNullOrBlank()) {
                    coroutineContext.ensureActive()
                    val directMatch = LyricsSourceRunner.match(source, title, artist, durationSec, mediaId)
                    coroutineContext.ensureActive()
                    if (directMatch != null && !directMatch.lrc.isNullOrBlank()) {
                        val parsedLines = parseLrcText(directMatch.lrc)
                        if (parsedLines.isNotEmpty()) {
                            AppLogger.d(TAG, "[${source.name}] mediaId 直查命中歌词 (${parsedLines.size}行)")
                            val data = LyricData(
                                lines = parsedLines,
                                coverUrl = directMatch.cover,
                                sourceName = source.name,
                                rawLrc = directMatch.lrc
                            )
                            storeLyricsCache(title, artist, data)
                            return@withContext Result.success(data)
                        }
                    }
                }

                // 2. 多级智能降级搜索匹配 (解决副标题过长、短视频UGC创作者名与官方原唱差异等问题)
                val cleanTitle = cleanSongTitle(title)
                val queries = mutableListOf<Triple<String, String, Boolean>>()
                // 1) 原始准确检索
                queries.add(Triple(title, artist, false))
                // 2) 去除各类修饰性括号后检索
                if (cleanTitle.isNotEmpty() && cleanTitle != title) {
                    queries.add(Triple(cleanTitle, artist, false))
                }
                // 3) 纯净曲名兜底检索 (针对汽水音乐/抖音短视频自媒体博主名与原唱不一致的场景)
                if (artist.isNotBlank()) {
                    queries.add(Triple(cleanTitle.ifEmpty { title }, "", true))
                }

                var matchedCandidate: SourceSearchItem? = null
                var matchedScore = -1

                for ((qTitle, qArtist, _) in queries) {
                    coroutineContext.ensureActive()
                    val searchItems = LyricsSourceRunner.search(source, qTitle, qArtist, durationSec)
                    coroutineContext.ensureActive()
                    if (searchItems.isEmpty()) continue

                    var bestCandidate: SourceSearchItem? = null
                    var bestScore = -1

                    for (item in searchItems) {
                        // 1) 标题校验 (支持带括号与去括号双向比对)
                        val titleScore = evaluateTitleMatch(title, item.title).let {
                            if (it >= 70) it else evaluateTitleMatch(cleanTitle, item.title)
                        }
                        if (titleScore <= 0) continue

                        // 2) 歌手校验：若请求方提供了明确歌手，候选曲目歌手若明确不匹配（<0），坚决淘汰，绝不乱匹配同名歌曲
                        val artistScore = evaluateArtistMatch(artist, item.artist)
                        if (artist.isNotBlank() && artistScore < 0) {
                            continue
                        }

                        // 3) 时长校验 (如果双方都有时长信息)
                        var durationScore = 0
                        if (durationSec > 0 && item.duration > 0) {
                            val diff = abs(item.duration - durationSec)
                            if (diff <= 3) {
                                durationScore = 30
                            } else if (diff <= 8) {
                                durationScore = 20
                            } else if (diff <= 15) {
                                durationScore = 5
                            } else {
                                // 时长差异超过 15 秒
                                if (titleScore == 100 && artistScore >= 0 && diff <= 25) {
                                    durationScore = -15
                                } else {
                                    continue
                                }
                            }
                        }

                        val totalScore = titleScore + artistScore + durationScore
                        if (totalScore > bestScore && totalScore >= 70) {
                            bestScore = totalScore
                            bestCandidate = item
                        }
                    }

                    if (bestCandidate != null) {
                        matchedCandidate = bestCandidate
                        matchedScore = bestScore
                        break
                    }
                }

                val candidate = matchedCandidate
                if (candidate == null) {
                    AppLogger.d(TAG, "[${source.name}] 搜索结果中无符合严谨匹配阈值的歌曲 (title=$title, artist=$artist)")
                    continue
                }

                // 4. 获取具体歌词内容与封面
                coroutineContext.ensureActive()
                val lyricResult = LyricsSourceRunner.getLyrics(source, candidate.id) ?: continue
                coroutineContext.ensureActive()
                if (lyricResult.lrc.isNullOrBlank()) continue

                val lines = parseLrcText(lyricResult.lrc)
                if (lines.isNotEmpty()) {
                    AppLogger.d(TAG, "[${source.name}] 成功匹配并获取歌词: ${candidate.title} - ${candidate.artist} (${lines.size}行, 匹配评分: $matchedScore)")
                    var coverUrl = lyricResult.cover
                    if (coverUrl.isNullOrBlank() || coverUrl.contains("artistpicserver.kuwo.cn") || coverUrl.contains("NO_PIC")) {
                        coroutineContext.ensureActive()
                        coverUrl = fetchCover(candidate.title, candidate.artist)
                    }
                    val data = LyricData(
                        lines = lines,
                        coverUrl = coverUrl,
                        sourceName = source.name,
                        rawLrc = lyricResult.lrc
                    )
                    storeLyricsCache(title, artist, data)
                    return@withContext Result.success(data)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.d(TAG, "[${source.name}] 检索失败: ${e.message}")
            }
        }

        coroutineContext.ensureActive()
        fetchFromNeteaseOfficial(title, artist)?.let { data ->
            storeLyricsCache(title, artist, data)
            return@withContext Result.success(data)
        }

        Result.failure(Exception("所有歌词源均未检索到匹配歌词"))
    }

    /**
     * 单独获取封面 (同样采用严格匹配，严禁随机匹配错误封面)
     */
    suspend fun fetchCover(title: String, artist: String, packageName: String? = null): String? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            return@withContext null
        }
        getCachedLyrics(title, artist)?.coverUrl?.let {
            if (it.isNotBlank()) return@withContext it
        }

        // 若当前播放器为网易云或偏好源为 netease，优先尝试官方直连
        val actualPkg = packageName ?: try { com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.packageName } catch (_: Exception) { null }
        if (actualPkg?.contains("netease") == true || PrefsManager.getPreferredLyricsSource().contains("netease")) {
            coroutineContext.ensureActive()
            val neteaseDirect = fetchFromNeteaseOfficial(title, artist)
            if (!neteaseDirect?.coverUrl.isNullOrBlank()) {
                return@withContext neteaseDirect?.coverUrl
            }
        }

        val enabledSources = LyricsSourceManager.getEnabledSources().toMutableList()
        sortSourcesByPreferenceAndChannel(enabledSources, packageName)
        val cleanTitle = cleanSongTitle(title)
        for (source in enabledSources) {
            try {
                var items = LyricsSourceRunner.search(source, title, artist, 0L)
                if (items.isEmpty() && cleanTitle.isNotEmpty() && cleanTitle != title) {
                    items = LyricsSourceRunner.search(source, cleanTitle, artist, 0L)
                }
                if (items.isEmpty() && artist.isNotBlank()) {
                    items = LyricsSourceRunner.search(source, cleanTitle.ifEmpty { title }, "", 0L)
                }
                val match = items.firstOrNull { item ->
                    val tScore = evaluateTitleMatch(title, item.title).let { s ->
                        if (s >= 70) s else evaluateTitleMatch(cleanTitle, item.title)
                    }
                    val aScore = evaluateArtistMatch(artist, item.artist)
                    tScore >= 70 && (if (artist.isNotBlank()) aScore >= 0 else true)
                }
                if (match != null) {
                    val res = LyricsSourceRunner.getLyrics(source, match.id)
                    if (!res?.cover.isNullOrBlank()) {
                        return@withContext res?.cover
                    }
                }
            } catch (_: Exception) {}
        }
        fetchFromNeteaseOfficial(title, artist)?.coverUrl?.let { return@withContext it }
        null
    }

    /**
     * 增强型 LRC 解析引擎 (支持毫秒级、[offset] 校准、逐字卡拉OK与 YRC 解析)
     */
    fun parseLrcText(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
        var globalOffset = 0L

        // 1. 提取头部 [offset:xxx] 标签 (解决时间线整体偏慢/偏快问题)
        for (line in lrc.lines()) {
            val offsetMatch = offsetRegex.find(line)
            if (offsetMatch != null) {
                globalOffset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                break
            }
        }

        val timeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?\]""")
        val yrcLineRegex = Regex("""^\[(\d+),(\d+)\]""")

        // 字级/词级时间戳正则支持：
        // 1. Kugou/QRC/增强 LRC 格式：<start,dur(,flags)?>text
        // 2. NetEase YRC/括号格式：(start,dur(,flags)?)text
        val wordTagRegex = Regex("""(?:<|\()(\d+),(\d+)(?:,\d+)?(?:>|\))([^<>()\[\]\n\r]+)""")

        for (line in lrc.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // 过滤元数据行，如 [ti:...], [ar:...], [al:...], [by:...], [offset:...]
            if (trimmedLine.startsWith("[ti:") || trimmedLine.startsWith("[ar:") ||
                trimmedLine.startsWith("[al:") || trimmedLine.startsWith("[by:") ||
                trimmedLine.startsWith("[offset:") || trimmedLine.startsWith("[hash:") ||
                trimmedLine.startsWith("[sign:") || trimmedLine.startsWith("[total:")) {
                continue
            }

            // 检查标准 LRC 行：[mm:ss.xx]
            val standardTimes = timeRegex.findAll(trimmedLine).toList()
            if (standardTimes.isNotEmpty()) {
                val rawContent = timeRegex.replace(trimmedLine, "").trim()
                if (rawContent.isEmpty()) continue

                for (match in standardTimes) {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val ms = match.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                        if (it.length == 2) it.toLong() * 10 else it.toLong()
                    } ?: 0L
                    val lineTs = (min * 60_000 + sec * 1000 + ms + globalOffset).coerceAtLeast(0L)

                    // 解析行内逐字时间戳
                    val words = mutableListOf<LyricWord>()
                    val wordMatches = wordTagRegex.findAll(rawContent).toList()

                    val cleanText = if (wordMatches.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (wm in wordMatches) {
                            val startVal = wm.groupValues[1].toLong()
                            val durVal = wm.groupValues[2].toLong()
                            val wText = wm.groupValues[3]
                            // 若 startVal < 60_000 则判定为行内相对偏移，否则为歌曲绝对时间戳
                            val wordStart = if (startVal < 60_000L) {
                                lineTs + startVal
                            } else {
                                (startVal + globalOffset).coerceAtLeast(0L)
                            }
                            words.add(LyricWord(text = wText, startTime = wordStart, duration = durVal))
                            sb.append(wText)
                        }
                        sb.toString()
                    } else {
                        rawContent.replace(Regex("""<[^>]+>|\([0-9,]+\)"""), "").trim()
                    }

                    if (cleanText.isNotBlank()) {
                        result.add(LyricLine(timestamp = lineTs, text = cleanText, words = words))
                    }
                }
                continue
            }

            // 检查网易 YRC 格式：[15200,3500](15200,350,0)歌词
            val yrcMatch = yrcLineRegex.find(trimmedLine)
            if (yrcMatch != null) {
                val lineTs = (yrcMatch.groupValues[1].toLong() + globalOffset).coerceAtLeast(0L)
                val rawContent = trimmedLine.substring(yrcMatch.range.last + 1).trim()
                val wordMatches = wordTagRegex.findAll(rawContent).toList()
                val words = mutableListOf<LyricWord>()
                val sb = StringBuilder()
                for (wm in wordMatches) {
                    val startVal = wm.groupValues[1].toLong()
                    val durVal = wm.groupValues[2].toLong()
                    val wText = wm.groupValues[3]
                    val wordStart = (startVal + globalOffset).coerceAtLeast(0L)
                    words.add(LyricWord(text = wText, startTime = wordStart, duration = durVal))
                    sb.append(wText)
                }
                val cleanText = sb.toString().trim()
                if (cleanText.isNotBlank()) {
                    result.add(LyricLine(timestamp = lineTs, text = cleanText, words = words))
                }
            }
        }

        return result.sortedBy { it.timestamp }
    }
}

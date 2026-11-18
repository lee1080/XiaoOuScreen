package com.carlyrics.lyrics

import android.util.Base64
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
            // 2. NetEase YRC/括号格式：(start,dur(,flags)?>)text
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

    private fun decodeQqEntities(text: String): String {
        return text
            .replace("&#58;", ":")
            .replace("&#46;", ".")
            .replace("&#32;", " ")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&amp;", "&")
    }

    fun fetchFromQQOfficial(title: String, artist: String, durationSec: Long = 0L): LyricData? {
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            return null
        }
        return try {
            val cleanTitle = cleanSongTitle(title)
            val queries = mutableListOf<Triple<String, String, Boolean>>()
            queries.add(Triple(title, artist, false))
            if (cleanTitle.isNotEmpty() && cleanTitle != title) {
                queries.add(Triple(cleanTitle, artist, false))
            }
            if (artist.isNotBlank()) {
                queries.add(Triple(cleanTitle.ifEmpty { title }, "", true))
            }

            val headers = mapOf(
                "Referer" to "https://y.qq.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            for ((qTitle, qArtist, isFallback) in queries) {
                val kw = if (qArtist.isNotBlank()) "$qTitle $qArtist".trim() else qTitle.trim()
                val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&format=json&w=" + URLEncoder.encode(kw, "UTF-8")
                val resp = httpGet(searchUrl, headers) ?: continue
                val map = gson.fromJson(resp, Map::class.java) ?: continue
                val dataObj = map["data"] as? Map<*, *> ?: continue
                val songObj = dataObj["song"] as? Map<*, *> ?: continue
                val songList = songObj["list"] as? List<*> ?: continue
                if (songList.isEmpty()) continue

                var bestSongMid: String? = null
                var bestAlbumMid: String? = null
                var bestTitle: String? = null
                var bestArtist: String? = null
                var bestScore = -1

                for (sObj in songList) {
                    val sMap = sObj as? Map<*, *> ?: continue
                    val sTitle = sMap["songname"]?.toString() ?: ""
                    val singers = sMap["singer"] as? List<*>
                    val sArtist = singers?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }?.joinToString(" / ") ?: ""

                    val tScore = evaluateTitleMatch(title, sTitle).let {
                        if (it >= 70) it else evaluateTitleMatch(cleanTitle, sTitle)
                    }
                    if (tScore <= 0) continue

                    val aScore = evaluateArtistMatch(artist, sArtist)
                    if (!isFallback && artist.isNotBlank() && aScore < 0) {
                        continue
                    }

                    var durationScore = 0
                    val sDur = (sMap["interval"] as? Number)?.toLong() ?: 0L
                    if (durationSec > 0 && sDur > 0) {
                        val diff = abs(sDur - durationSec)
                        if (diff <= 3) durationScore = 30
                        else if (diff <= 8) durationScore = 20
                        else if (diff <= 15) durationScore = 5
                        else if (tScore == 100 && (aScore >= 0 || isFallback) && diff <= 25) durationScore = -15
                        else continue
                    }

                    val totalScore = tScore + (if (aScore < 0) -10 else aScore) + durationScore
                    if (totalScore > bestScore && totalScore >= 65) {
                        bestScore = totalScore
                        bestSongMid = sMap["songmid"]?.toString()
                        bestAlbumMid = sMap["albummid"]?.toString()
                        bestTitle = sTitle
                        bestArtist = sArtist
                    }
                }

                if (bestSongMid != null) {
                    val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$bestSongMid&format=json&nobase64=1"
                    val lResp = httpGet(lyricUrl, headers) ?: continue
                    val lMap = gson.fromJson(lResp, Map::class.java) ?: continue
                    val rawLrc = lMap["lyric"]?.toString() ?: continue
                    if (rawLrc.isBlank()) continue

                    val decodedLrc = decodeQqEntities(rawLrc)
                    val lines = parseLrcText(decodedLrc)
                    if (lines.isNotEmpty()) {
                        val coverUrl = if (!bestAlbumMid.isNullOrBlank() && bestAlbumMid != "00000000000000") {
                            "https://y.gtimg.cn/music/photo_new/T002R300x300M000${bestAlbumMid}.jpg"
                        } else null
                        AppLogger.d(TAG, "QQ音乐官方直连命中: $bestTitle - $bestArtist (${lines.size}行, 评分: $bestScore)")
                        val data = LyricData(
                            lines = lines,
                            coverUrl = coverUrl,
                            sourceName = "QQ音乐(官方直连)",
                            rawLrc = decodedLrc
                        )
                        storeLyricsCache(title, artist, data)
                        return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.d(TAG, "fetchFromQQOfficial 异常: ${e.message}")
            null
        }
    }

    fun fetchFromKugouOfficial(title: String, artist: String, durationSec: Long = 0L): LyricData? {
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            return null
        }
        return try {
            val cleanTitle = cleanSongTitle(title)
            val queries = mutableListOf<Triple<String, String, Boolean>>()
            queries.add(Triple(title, artist, false))
            if (cleanTitle.isNotEmpty() && cleanTitle != title) {
                queries.add(Triple(cleanTitle, artist, false))
            }
            if (artist.isNotBlank()) {
                queries.add(Triple(cleanTitle.ifEmpty { title }, "", true))
            }

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            for ((qTitle, qArtist, isFallback) in queries) {
                val kw = if (qArtist.isNotBlank()) "$qTitle $qArtist".trim() else qTitle.trim()
                val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&showtype=1&page=1&pagesize=5&keyword=" + URLEncoder.encode(kw, "UTF-8")
                val resp = httpGet(searchUrl, headers) ?: continue
                val map = gson.fromJson(resp, Map::class.java) ?: continue
                val dataObj = map["data"] as? Map<*, *> ?: continue
                val infoList = dataObj["info"] as? List<*> ?: continue
                if (infoList.isEmpty()) continue

                var bestHash: String? = null
                var bestAlbumId: String? = null
                var bestTitle: String? = null
                var bestArtist: String? = null
                var bestScore = -1

                for (sObj in infoList) {
                    val sMap = sObj as? Map<*, *> ?: continue
                    val sTitle = sMap["songname"]?.toString() ?: ""
                    val sArtist = sMap["singername"]?.toString() ?: ""
                    val hash = sMap["hash"]?.toString() ?: continue
                    if (hash.isBlank()) continue

                    val tScore = evaluateTitleMatch(title, sTitle).let {
                        if (it >= 70) it else evaluateTitleMatch(cleanTitle, sTitle)
                    }
                    if (tScore <= 0) continue

                    val aScore = evaluateArtistMatch(artist, sArtist)
                    if (!isFallback && artist.isNotBlank() && aScore < 0) {
                        continue
                    }

                    var durationScore = 0
                    val sDur = (sMap["duration"] as? Number)?.toLong() ?: 0L
                    if (durationSec > 0 && sDur > 0) {
                        val diff = abs(sDur - durationSec)
                        if (diff <= 3) durationScore = 30
                        else if (diff <= 8) durationScore = 20
                        else if (diff <= 15) durationScore = 5
                        else if (tScore == 100 && (aScore >= 0 || isFallback) && diff <= 25) durationScore = -15
                        else continue
                    }

                    val totalScore = tScore + (if (aScore < 0) -10 else aScore) + durationScore
                    if (totalScore > bestScore && totalScore >= 65) {
                        bestScore = totalScore
                        bestHash = hash
                        bestAlbumId = sMap["album_id"]?.toString()
                        bestTitle = sTitle
                        bestArtist = sArtist
                    }
                }

                if (bestHash != null) {
                    val lyricSearchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=&hash=$bestHash"
                    val lsResp = httpGet(lyricSearchUrl, headers) ?: continue
                    val lsMap = gson.fromJson(lsResp, Map::class.java) ?: continue
                    val candidates = lsMap["candidates"] as? List<*> ?: continue
                    val firstCand = candidates.firstOrNull() as? Map<*, *> ?: continue
                    val id = firstCand["id"]?.toString() ?: continue
                    val accesskey = firstCand["accesskey"]?.toString() ?: continue
                    if (id.isBlank() || accesskey.isBlank()) continue

                    val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=" +
                            URLEncoder.encode(accesskey, "UTF-8") + "&fmt=lrc&charset=utf8"
                    val dlResp = httpGet(dlUrl, headers) ?: continue
                    val dlMap = gson.fromJson(dlResp, Map::class.java) ?: continue
                    val b64Content = dlMap["content"]?.toString() ?: continue
                    if (b64Content.isBlank()) continue

                    val decodedLrc = String(Base64.decode(b64Content, Base64.DEFAULT), Charsets.UTF_8)
                    val lines = parseLrcText(decodedLrc)
                    if (lines.isNotEmpty()) {
                        var coverUrl: String? = null
                        if (!bestAlbumId.isNullOrBlank() && bestAlbumId != "0") {
                            coverUrl = fetchCoverFromKugouAlbumId(bestAlbumId)
                        }
                        AppLogger.d(TAG, "酷狗音乐官方直连命中: $bestTitle - $bestArtist (${lines.size}行, 评分: $bestScore)")
                        val data = LyricData(
                            lines = lines,
                            coverUrl = coverUrl,
                            sourceName = "酷狗音乐(官方直连)",
                            rawLrc = decodedLrc
                        )
                        storeLyricsCache(title, artist, data)
                        return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.d(TAG, "fetchFromKugouOfficial 异常: ${e.message}")
            null
        }
    }

    fun fetchCoverFromKugouAlbumId(albumId: String): String? {
        try {
            val url = "http://mobilecdn.kugou.com/api/v3/album/info?format=json&albumid=$albumId"
            val resp = httpGet(url) ?: return null
            val map = gson.fromJson(resp, Map::class.java) ?: return null
            val dataObj = map["data"] as? Map<*, *> ?: return null
            val imgUrl = dataObj["imgurl"]?.toString() ?: return null
            return imgUrl.replace("{size}", "400")
        } catch (_: Exception) {
            return null
        }
    }

    fun fetchFromNeteaseOfficial(title: String, artist: String, durationSec: Long = 0L): LyricData? {
        if (title.isBlank() || com.carlyrics.recognition.MusicRecognitionEngine.isInvalidPlaceholderTrack(title, artist)) {
            return null
        }
        return try {
            val cleanTitle = cleanSongTitle(title)
            val queries = mutableListOf<Triple<String, String, Boolean>>()
            queries.add(Triple(title, artist, false))
            if (cleanTitle.isNotEmpty() && cleanTitle != title) {
                queries.add(Triple(cleanTitle, artist, false))
            }
            if (artist.isNotBlank()) {
                queries.add(Triple(cleanTitle.ifEmpty { title }, "", true))
            }

            val searchUrl = "https://music.163.com/api/search/get"
            val headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            for ((qTitle, qArtist, isFallback) in queries) {
                val params = mapOf(
                    "s" to (if (qArtist.isNotBlank()) "$qTitle $qArtist" else qTitle).trim(),
                    "type" to "1",
                    "limit" to "5",
                    "offset" to "0"
                )
                val resp = httpPostForm(searchUrl, params, headers) ?: continue
                val map = gson.fromJson(resp, Map::class.java) ?: continue
                val result = map["result"] as? Map<*, *> ?: continue
                val songs = result["songs"] as? List<*> ?: continue
                if (songs.isEmpty()) continue

                var matchedSongId: Long? = null
                var matchedCoverUrl: String? = null
                var matchedTitle: String? = null
                var matchedArtist: String? = null
                var bestScore = -1

                for (sObj in songs) {
                    val song = sObj as? Map<*, *> ?: continue
                    val sTitle = song["name"]?.toString() ?: ""
                    val artists = song["artists"] as? List<*>
                    val sArtist = artists?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }?.joinToString(" / ") ?: ""

                    val tScore = evaluateTitleMatch(title, sTitle).let {
                        if (it >= 70) it else evaluateTitleMatch(cleanTitle, sTitle)
                    }
                    if (tScore <= 0) continue

                    val aScore = evaluateArtistMatch(artist, sArtist)
                    if (!isFallback && artist.isNotBlank() && aScore < 0) {
                        continue
                    }

                    var durationScore = 0
                    val sDur = ((song["duration"] as? Number)?.toLong() ?: 0L) / 1000L
                    if (durationSec > 0 && sDur > 0) {
                        val diff = abs(sDur - durationSec)
                        if (diff <= 3) durationScore = 30
                        else if (diff <= 8) durationScore = 20
                        else if (diff <= 15) durationScore = 5
                        else if (tScore == 100 && (aScore >= 0 || isFallback) && diff <= 25) durationScore = -15
                        else continue
                    }

                    val totalScore = tScore + (if (aScore < 0) -10 else aScore) + durationScore
                    if (totalScore > bestScore && totalScore >= 65) {
                        val idNum = (song["id"] as? Number)?.toLong() ?: continue
                        bestScore = totalScore
                        matchedSongId = idNum
                        matchedTitle = sTitle
                        matchedArtist = sArtist
                        val album = song["album"] as? Map<*, *>
                        matchedCoverUrl = album?.get("picUrl")?.toString()
                    }
                }

                val songId = matchedSongId ?: continue
                val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
                val lyricResp = httpGet(lyricUrl, headers) ?: continue
                val lyricMap = gson.fromJson(lyricResp, Map::class.java) ?: continue
                val lrcObj = lyricMap["lrc"] as? Map<*, *> ?: continue
                val rawLrc = lrcObj["lyric"]?.toString() ?: continue
                val lines = parseLrcText(rawLrc)
                if (lines.isEmpty()) continue

                if (matchedCoverUrl.isNullOrEmpty()) {
                    matchedCoverUrl = fetchCoverFromNeteaseOfficialById(songId)
                }

                AppLogger.d(TAG, "网易云官方直连命中: $matchedTitle - $matchedArtist (${lines.size}行, 评分: $bestScore)")
                val data = LyricData(
                    lines = lines,
                    coverUrl = matchedCoverUrl,
                    sourceName = "网易云音乐(官方直连)",
                    rawLrc = rawLrc
                )
                storeLyricsCache(title, artist, data)
                return data
            }
            null
        } catch (e: Exception) {
            AppLogger.d(TAG, "fetchFromNeteaseOfficial 异常: ${e.message}")
            null
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

    /** 去除曲名中的修饰性括号、后缀标签等（如“（你我怎么两清）”、“（DJ版）”、“ - 热播片段”、“0.8x降调版”） */
    private fun cleanSongTitle(raw: String): String {
        val s = raw.trim()
            .replace(Regex("""（.*?）"""), "")
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""【.*?】"""), "")
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""\s*[-_—].*$"""), "")
            .trim()
        return s.replace(Regex("""(?i)\s*(?:[0-9.]+[xX])?\s*(?:降调|升调|变调|加速|减速|慢速|DJ|dj|微醺|热播|完整|女声|男声|烟嗓|弹唱|经典|现场|翻唱|伴奏|重置|试听)*(?:版|片段)"""), "").trim()
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
        "3d环绕版", "dj版", "现场版", "正式版", "重置版", "试听版",
        "降调版", "升调版", "变调版", "加速版", "减速版", "慢速版", "微醺版", "微醺",
        "08x", "09x", "11x", "12x", "8x", "9x"
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

        val actualPkg = packageName ?: try { com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.packageName } catch (_: Exception) { null }
        val durationSec = duration / 1000L

        // 1. 若当前播放器为网易云或偏好源为 netease，优先尝试网易云官方直连
        if (actualPkg?.contains("netease") == true || PrefsManager.getPreferredLyricsSource().contains("netease")) {
            coroutineContext.ensureActive()
            val neteaseDirect = fetchFromNeteaseOfficial(title, artist, durationSec)
            if (neteaseDirect != null && neteaseDirect.lines.isNotEmpty()) {
                storeLyricsCache(title, artist, neteaseDirect)
                return@withContext Result.success(neteaseDirect)
            }
        }

        // 2. 若当前播放器为 QQ 音乐或偏好源为 qq，优先尝试 QQ 音乐官方直连
        if (actualPkg?.contains("qqmusic") == true || actualPkg?.contains("tencent") == true || PrefsManager.getPreferredLyricsSource().contains("qq")) {
            coroutineContext.ensureActive()
            val qqDirect = fetchFromQQOfficial(title, artist, durationSec)
            if (qqDirect != null && qqDirect.lines.isNotEmpty()) {
                storeLyricsCache(title, artist, qqDirect)
                return@withContext Result.success(qqDirect)
            }
        }

        // 3. 若当前播放器为酷狗音乐或偏好源为 kugou，优先尝试酷狗官方直连
        if (actualPkg?.contains("kugou") == true || PrefsManager.getPreferredLyricsSource().contains("kugou")) {
            coroutineContext.ensureActive()
            val kugouDirect = fetchFromKugouOfficial(title, artist, durationSec)
            if (kugouDirect != null && kugouDirect.lines.isNotEmpty()) {
                storeLyricsCache(title, artist, kugouDirect)
                return@withContext Result.success(kugouDirect)
            }
        }

        // 4. 执行已安装并启用的外部 JavaScript 歌词源插件匹配
        val enabledSources = LyricsSourceManager.getEnabledSources().toMutableList()
        if (enabledSources.isNotEmpty()) {
            sortSourcesByPreferenceAndChannel(enabledSources, packageName)

            for (source in enabledSources) {
                coroutineContext.ensureActive()
                try {
                    // 1) 若支持 match 且 mediaId 存在，优先尝试直连
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

                    // 2) 多级智能降级搜索匹配
                    val cleanTitle = cleanSongTitle(title)
                    val queries = mutableListOf<Triple<String, String, Boolean>>()
                    queries.add(Triple(title, artist, false))
                    if (cleanTitle.isNotEmpty() && cleanTitle != title) {
                        queries.add(Triple(cleanTitle, artist, false))
                    }
                    if (artist.isNotBlank()) {
                        queries.add(Triple(cleanTitle.ifEmpty { title }, "", true))
                    }

                    var matchedCandidate: SourceSearchItem? = null
                    var matchedScore = -1

                    for ((qTitle, qArtist, isFallback) in queries) {
                        coroutineContext.ensureActive()
                        val searchItems = LyricsSourceRunner.search(source, qTitle, qArtist, durationSec)
                        coroutineContext.ensureActive()
                        if (searchItems.isEmpty()) continue

                        var bestCandidate: SourceSearchItem? = null
                        var bestScore = -1

                        for (item in searchItems) {
                            val titleScore = evaluateTitleMatch(title, item.title).let {
                                if (it >= 70) it else evaluateTitleMatch(cleanTitle, item.title)
                            }
                            if (titleScore <= 0) continue

                            val artistScore = evaluateArtistMatch(artist, item.artist)
                            if (!isFallback && artist.isNotBlank() && artistScore < 0) {
                                continue
                            }

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
                                    if (titleScore == 100 && (artistScore >= 0 || isFallback) && diff <= 25) {
                                        durationScore = -15
                                    } else {
                                        continue
                                    }
                                }
                            }

                            val totalScore = titleScore + (if (artistScore < 0) -10 else artistScore) + durationScore
                            if (totalScore > bestScore && totalScore >= 65) {
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
        }

        // 5. 官方原生多平台级联兜底 (QQ音乐 -> 酷狗音乐 -> 网易云音乐)
        coroutineContext.ensureActive()
        fetchFromQQOfficial(title, artist, durationSec)?.let { data ->
            return@withContext Result.success(data)
        }

        coroutineContext.ensureActive()
        fetchFromKugouOfficial(title, artist, durationSec)?.let { data ->
            return@withContext Result.success(data)
        }

        coroutineContext.ensureActive()
        fetchFromNeteaseOfficial(title, artist, durationSec)?.let { data ->
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

        val actualPkg = packageName ?: try { com.carlyrics.CarLyricsApplication.recognitionEngine.currentTrack.value?.packageName } catch (_: Exception) { null }
        if (actualPkg?.contains("netease") == true || PrefsManager.getPreferredLyricsSource().contains("netease")) {
            coroutineContext.ensureActive()
            val neteaseDirect = fetchFromNeteaseOfficial(title, artist)
            if (!neteaseDirect?.coverUrl.isNullOrBlank()) {
                return@withContext neteaseDirect?.coverUrl
            }
        }
        if (actualPkg?.contains("qqmusic") == true || actualPkg?.contains("tencent") == true || PrefsManager.getPreferredLyricsSource().contains("qq")) {
            coroutineContext.ensureActive()
            val qqDirect = fetchFromQQOfficial(title, artist)
            if (!qqDirect?.coverUrl.isNullOrBlank()) {
                return@withContext qqDirect?.coverUrl
            }
        }
        if (actualPkg?.contains("kugou") == true || PrefsManager.getPreferredLyricsSource().contains("kugou")) {
            coroutineContext.ensureActive()
            val kgDirect = fetchFromKugouOfficial(title, artist)
            if (!kgDirect?.coverUrl.isNullOrBlank()) {
                return@withContext kgDirect?.coverUrl
            }
        }

        val enabledSources = LyricsSourceManager.getEnabledSources().toMutableList()
        if (enabledSources.isNotEmpty()) {
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
        }

        // 官方原生多平台级联兜底
        fetchFromQQOfficial(title, artist)?.coverUrl?.let { if (it.isNotBlank()) return@withContext it }
        fetchFromKugouOfficial(title, artist)?.coverUrl?.let { if (it.isNotBlank()) return@withContext it }
        fetchFromNeteaseOfficial(title, artist)?.coverUrl?.let { if (it.isNotBlank()) return@withContext it }
        null
    }

    /**
     * 增强型 LRC 解析引擎 (支持毫秒级、[offset] 校准、逐字卡拉OK与 YRC 解析)
     */
    fun parseLrcText(lrc: String): List<LyricLine> = Companion.parseLrcText(lrc)
}

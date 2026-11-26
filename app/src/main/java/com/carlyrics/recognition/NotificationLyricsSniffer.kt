package com.carlyrics.recognition

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.carlyrics.model.NotificationLyric
import com.carlyrics.util.AppLogger
import java.util.Locale
import java.util.regex.Pattern

/**
 * 通知栏歌词反射嗅探器 (RemoteViews Dynamic Reflection Lyric Sniffer)
 *
 * 核心机制参考车机级原厂 Launcher (如欧尚/长安桌面) 的黑科技设计：
 * 1. 跨进程加载第三方音乐应用上下文 (createPackageContext)
 * 2. 内存中将通知栏自定义 RemoteViews 布局实时展开为 View 树 (remoteViews.apply)
 * 3. 递归遍历采集所有 TextView 可见文本
 * 4. 结合 extras 属性并采用多维特征黑名单与加权评分算法，精准提纯出正在播放的实时单句歌词
 * 5. 特别针对汽水音乐 (com.luna.music)、网易云、QQ音乐等提供免 API 零延迟本地即时歌词透传
 */
object NotificationLyricsSniffer {

    private const val TAG = "NotifLyricsSniffer"

    /** 核心受支持的音乐包名白名单 (优先深度嗅探) */
    private val TARGET_PACKAGES = setOf(
        "com.luna.music",                       // 汽水音乐 (Luna) 手机版
        "com.luna.music.car",                   // 汽水音乐 (Luna) 车机版
        "com.netease.cloudmusic",               // 网易云音乐
        "com.tencent.qqmusic",                  // QQ音乐手机版
        "com.tencent.qqmusiccar",               // QQ音乐车机版
        "com.kugou.android",                    // 酷狗音乐手机版
        "com.kugou.android.auto",               // 酷狗音乐车机版
        "com.kugou.android.lite",               // 酷狗概念版
        "cn.kuwo.player",                       // 酷我音乐
        "com.spotify.music",                    // Spotify
        "com.apple.android.music",              // Apple Music
        "com.google.android.apps.youtube.music" // YouTube Music
    )

    /** 播放进度与时间格式正则 (纯数字、时间码、斜杠、冒号)，必须排除 */
    private val TIME_PROGRESS_REGEX = Pattern.compile("^[0-9:./ -]+$")

    /** 歌词时间戳格式正则 (如 [01:23.45] 或 <0,500>) */
    private val LYRIC_TIMESTAMP_REGEX = Pattern.compile("(\\[\\d{1,3}:\\d{1,2}|<\\d+,\\d+>)")

    /** 车规级节流与 PackageContext 缓存：消除每秒重复 createPackageContext 与高频 View 树动态展开引起的 GC 掉帧 */
    private val packageContextCache = java.util.concurrent.ConcurrentHashMap<String, Context>()
    private val lastRemoteViewParseTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REMOTE_VIEW_PARSE_INTERVAL_MS = 350L

    fun clearPackageContextCache() {
        packageContextCache.clear()
        lastRemoteViewParseTime.clear()
    }

    /** 候选歌词临时评分数据 */
    private data class Candidate(
        val text: String,
        val score: Int
    )

    /**
     * 判断是否是值得嗅探歌词的通知
     */
    fun isTargetMusicNotification(sbn: StatusBarNotification?): Boolean {
        if (sbn == null) return false
        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        if (TARGET_PACKAGES.contains(pkg)) {
            return true
        }
        val notification = sbn.notification ?: return false
        val extras = notification.extras
        val hasMediaToken = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val isTransport = notification.category == Notification.CATEGORY_TRANSPORT
        val hasMediaPlayback = extras?.getBoolean("android.media.playback", false) == true
        return hasMediaToken || isTransport || hasMediaPlayback
    }

    /**
     * 从 StatusBarNotification 中提取当前播放的实时歌词
     *
     * @param context 宿主上下文
     * @param sbn 状态栏通知
     * @return 嗅探提取出的歌词信息，若未提取出则返回 null
     */
    fun extractLyrics(context: Context, sbn: StatusBarNotification?): NotificationLyric? {
        if (sbn == null) return null
        val notification = sbn.notification ?: return null
        val pkg = sbn.packageName

        if (!isTargetMusicNotification(sbn)) {
            return null
        }

        return try {
            val extras = notification.extras
            val excludedTexts = collectExcludedTexts(context, extras, pkg)
            val candidates = mutableListOf<Candidate>()

            // 1. 从 Notification.extras 收集候选 (极低开销，纯内存键值匹配)
            collectExtrasCandidates(candidates, extras, excludedTexts)

            // 核心性能优化：若 extras 已提取到高置信度歌词候选 (score >= 35)，直接短路跳过耗时的 RemoteViews 反射与布局展开！
            val topExtraCandidate = candidates.maxByOrNull { it.score }
            val needRemoteViews = topExtraCandidate == null || topExtraCandidate.score < 35

            if (needRemoteViews) {
                val now = android.os.SystemClock.uptimeMillis()
                val lastParse = lastRemoteViewParseTime[pkg] ?: 0L
                if (now - lastParse >= REMOTE_VIEW_PARSE_INTERVAL_MS) {
                    lastRemoteViewParseTime[pkg] = now
                    // 2. 从 RemoteViews 收集候选 (内存中动态反射 View 树，受 350ms 节流保护)
                    notification.contentView?.let { rv ->
                        collectRemoteViewCandidates(context, candidates, rv, pkg, excludedTexts)
                    }
                    notification.bigContentView?.let { rv ->
                        collectRemoteViewCandidates(context, candidates, rv, pkg, excludedTexts)
                    }
                    notification.headsUpContentView?.let { rv ->
                        collectRemoteViewCandidates(context, candidates, rv, pkg, excludedTexts)
                    }
                }
            }

            // 3. 选取加权得分最高且符合歌词特征的最佳候选
            val bestCandidate = candidates.maxByOrNull { it.score }
            if (bestCandidate != null && bestCandidate.score > 0) {
                AppLogger.d(TAG, "从通知栏嗅探到歌词 [$pkg]: \"${bestCandidate.text}\" (score=${bestCandidate.score})")
                NotificationLyric(
                    text = bestCandidate.text,
                    packageName = pkg,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "嗅探通知栏歌词异常 [pkg=$pkg]: ${e.message}")
            null
        }
    }

    /**
     * 收集排除项黑名单 (歌名、歌手、应用名、包名等)，避免将元数据误判为歌词
     */
    private fun collectExcludedTexts(context: Context, extras: Bundle?, pkg: String): MutableSet<String> {
        val set = mutableSetOf<String>()
        addExcluded(set, pkg)

        // 尝试获取应用名称 (复用 MusicRecognitionEngine 缓存，避免重复 PackageManager Binder IPC)
        addExcluded(set, MusicRecognitionEngine.getCachedAppDisplayName(context, pkg))

        if (extras == null) return set

        addExcluded(set, extras.getCharSequence(Notification.EXTRA_TITLE))
        addExcluded(set, extras.getCharSequence("android.title.big"))
        addExcluded(set, extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        addExcluded(set, extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        addExcluded(set, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))

        return set
    }

    private fun addExcluded(set: MutableSet<String>, text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            set.add(text.toString().trim().lowercase(Locale.ROOT))
        }
    }

    /**
     * 从 Notification.extras 中搜集候选
     */
    private fun collectExtrasCandidates(
        candidates: MutableList<Candidate>,
        extras: Bundle?,
        excluded: Set<String>
    ) {
        if (extras == null) return

        // 1. android.bigText (多行/大图展开文案，常见于网易云/汽水)
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
            addCandidate(candidates, it, "extra_big_text", excluded, baseScore = 40)
        }

        // 2. android.text (通知正文)
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let {
            addCandidate(candidates, it, "extra_text", excluded, baseScore = 28)
        }

        // 3. android.textLines (InboxStyle 多行文本)
        val textLines = extras.getCharSequenceArray("android.textLines")
        textLines?.forEach { line ->
            if (line != null) {
                addCandidate(candidates, line, "extra_text_lines", excluded, baseScore = 35)
            }
        }

        // 4. 遍历所有自定义 Bundle Key，检索含有 lyric/lrc/karaoke 语义的字段
        for (key in extras.keySet()) {
            if (looksLikeLyricsKey(key)) {
                val value = extras.get(key)
                if (value is CharSequence) {
                    addCandidate(candidates, value, key, excluded, baseScore = 80)
                }
            }
        }
    }

    /**
     * 从 RemoteViews 中跨进程反射并在内存中展开 View 树，递归提取所有 TextView
     */
    private fun collectRemoteViewCandidates(
        context: Context,
        candidates: MutableList<Candidate>,
        remoteViews: RemoteViews,
        pkg: String,
        excluded: Set<String>
    ) {
        var container: FrameLayout? = null
        try {
            // 复用目标应用 PackageContext 缓存，杜绝每次通知到达重复 createPackageContext
            val appContext = context.applicationContext ?: context
            if (packageContextCache.size >= 3 && !packageContextCache.containsKey(pkg)) {
                packageContextCache.clear()
            }
            val targetContext = packageContextCache.getOrPut(pkg) {
                appContext.createPackageContext(
                    pkg,
                    Context.CONTEXT_RESTRICTED or Context.CONTEXT_IGNORE_SECURITY
                )
            }
            container = FrameLayout(targetContext)
            // 内存中实例化 RemoteViews 结构树
            val renderedView = remoteViews.apply(targetContext, container)

            val viewTexts = LinkedHashSet<String>()
            collectTextFromView(viewTexts, renderedView)

            for (text in viewTexts) {
                addCandidate(candidates, text, "remote_view", excluded, baseScore = 24)
            }
        } catch (_: Exception) {
            // 安全捕获：目标应用资源未就绪或主题不支持时平滑降级，移除失效 Context 缓存
            packageContextCache.remove(pkg)
        } finally {
            try {
                container?.removeAllViews()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 深度递归遍历 View 树采集全部可见字符串
     */
    private fun collectTextFromView(set: MutableSet<String>, view: View?) {
        if (view == null) return

        if (view is TextView) {
            val text = view.text
            if (!text.isNullOrBlank()) {
                set.add(text.toString())
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTextFromView(set, view.getChildAt(i))
            }
        }
    }

    /**
     * 候选清洗、黑名单排除与加权评分算法
     */
    private fun addCandidate(
        candidates: MutableList<Candidate>,
        rawText: CharSequence,
        sourceTag: String,
        excluded: Set<String>,
        baseScore: Int
    ) {
        val normalized = normalizeLyrics(rawText)
        if (normalized.isBlank() || shouldExclude(normalized, sourceTag, excluded)) {
            return
        }

        var score = baseScore

        // 1. 包含换行符：多行歌词特征，大幅加分 (+60)
        if (normalized.contains("\n")) {
            score += 60
        }

        // 2. 包含歌词时间戳格式 (如 [00:12] 或 <0,500>)：极大可能为 LRC 逐字词轨 (+45)
        if (LYRIC_TIMESTAMP_REGEX.matcher(normalized).find()) {
            score += 45
        }

        // 3. 字段 Key 明确包含歌词语义 (+80)
        if (looksLikeLyricsKey(sourceTag)) {
            score += 80
        }

        // 4. 长度在 6 ~ 90 字符之间：典型单句歌词最佳区间 (+20)
        val len = normalized.length
        if (len in 6..90) {
            score += 20
        }

        // 5. 字符长度 > 120：可能是长文简介或专辑说明，轻度扣分 (-12)
        if (len > 120) {
            score -= 12
        }

        candidates.add(Candidate(text = normalized, score = score))
    }

    /**
     * 文本清洗与格式规范化
     */
    private fun normalizeLyrics(cs: CharSequence): String {
        return cs.toString()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    /**
     * 排除规则检查
     */
    private fun shouldExclude(text: String, sourceTag: String, excluded: Set<String>): Boolean {
        if (text.isBlank()) return true

        // 如果字段名显式标明为歌词，豁免排除
        if (looksLikeLyricsKey(sourceTag)) return false

        val lower = text.lowercase(Locale.ROOT)

        // 1. 命中黑名单 (歌名、歌手名、专辑名等)
        if (excluded.contains(lower)) return true

        // 2. 字符过短 (<= 2 字符，通常是图标或缩写)
        if (lower.length <= 2) return true

        // 3. 纯播放时长与进度数字 (如 03:25 或 01:20 / 04:30)
        if (TIME_PROGRESS_REGEX.matcher(lower).matches()) return true

        // 4. 网页链接
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true

        return false
    }

    /**
     * 检查 Key 是否具备歌词语义
     */
    private fun looksLikeLyricsKey(key: String): Boolean {
        if (key.isBlank()) return false
        val lower = key.lowercase(Locale.ROOT)
        return lower.contains("lyric") ||
            lower.contains("lyrics") ||
            lower.contains("lrc") ||
            lower.contains("karaoke")
    }
}

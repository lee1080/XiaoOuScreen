package com.carlyrics.lyrics

import com.carlyrics.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Script
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 歌词源 JavaScript 沙箱执行器 (基于 Rhino 引擎)
 */
object LyricsSourceRunner {

    private const val TAG = "LyricsSourceRunner"
    private val gson = Gson()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .writeTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 立即取消所有进行中的歌词源 HTTP 网络请求 (切歌时秒级响应，防超时挂起)
     */
    fun cancelAll() {
        try {
            client.dispatcher.cancelAll()
        } catch (_: Exception) {}
    }

    private const val RUNNER_POLYFILL = """
        var module = { exports: {} };
        var exports = module.exports;

        var http = {
            get: function(url, headers) {
                var u = (url !== undefined && url !== null) ? String(url) : "";
                var res = __nativeHttp.get(u, headers || null);
                return (res !== null && res !== undefined) ? String(res) : null;
            },
            post: function(url, body, headers) {
                var u = (url !== undefined && url !== null) ? String(url) : "";
                var b = (body !== undefined && body !== null) ? String(body) : "";
                var res = __nativeHttp.post(u, b, headers || null);
                return (res !== null && res !== undefined) ? String(res) : null;
            }
        };

        var crypto = {
            md5: function(str) {
                var s = (str !== undefined && str !== null) ? String(str) : "";
                return String(__nativeCrypto.md5(s) || "");
            },
            base64Encode: function(str) {
                var s = (str !== undefined && str !== null) ? String(str) : "";
                return String(__nativeCrypto.base64Encode(s) || "");
            },
            base64Decode: function(str) {
                var s = (str !== undefined && str !== null) ? String(str) : "";
                return String(__nativeCrypto.base64Decode(s) || "");
            }
        };

        var console = {
            log: function() {
                var args = Array.prototype.slice.call(arguments);
                __nativeConsole.log(args.join(" "));
            },
            error: function() {
                var args = Array.prototype.slice.call(arguments);
                __nativeConsole.error(args.join(" "));
            }
        };

        function formatTime(totalSec) {
            var s = parseFloat(totalSec || 0);
            var min = Math.floor(s / 60);
            var sec = Math.floor(s % 60);
            var ms = Math.floor((s % 1) * 100);
            return (min < 10 ? "0" : "") + min + ":" + (sec < 10 ? "0" : "") + sec + "." + (ms < 10 ? "0" : "") + ms;
        }

        function __callSearch(title, artist, duration) {
            var fn = (typeof search === 'function') ? search :
                     (module.exports && typeof module.exports.search === 'function') ? module.exports.search :
                     (typeof source !== 'undefined' && typeof source.search === 'function') ? source.search : null;
            if (!fn) return "[]";
            var res = fn(title, artist, duration);
            if (!res) return "[]";
            if (typeof res === 'string') return res;
            return JSON.stringify(res);
        }

        function __callGetLyrics(id) {
            var fn = (typeof getLyrics === 'function') ? getLyrics :
                     (module.exports && typeof module.exports.getLyrics === 'function') ? module.exports.getLyrics :
                     (typeof source !== 'undefined' && typeof source.getLyrics === 'function') ? source.getLyrics : null;
            if (!fn) return null;
            var res = fn(id);
            if (!res) return null;
            if (typeof res === 'string') return res;
            return JSON.stringify(res);
        }

        function __callMatch(title, artist, duration, mediaId) {
            var fn = (typeof match === 'function') ? match :
                     (module.exports && typeof module.exports.match === 'function') ? module.exports.match :
                     (typeof source !== 'undefined' && typeof source.match === 'function') ? source.match : null;
            if (!fn) return null;
            var res = fn(title, artist, duration, mediaId);
            if (!res) return null;
            if (typeof res === 'string') return res;
            return JSON.stringify(res);
        }
    """

    /**
     * 从脚本头部提取元数据 (@name, @id, @version, @author, @description)
     */
    fun parseMetadata(script: String, defaultId: String = ""): LyricsSource {
        val name = Regex("""@name\s+([^\r\n]+)""").find(script)?.groupValues?.get(1)?.trim()
            ?: defaultId.ifEmpty { "未命名源" }
        val id = Regex("""@id\s+([^\r\n]+)""").find(script)?.groupValues?.get(1)?.trim()
            ?: defaultId.ifEmpty { "source_" + System.currentTimeMillis() }
        val version = Regex("""@version\s+([^\r\n]+)""").find(script)?.groupValues?.get(1)?.trim() ?: "1.0.0"
        val author = Regex("""@author\s+([^\r\n]+)""").find(script)?.groupValues?.get(1)?.trim() ?: "社区开发者"
        val desc = Regex("""@description\s+([^\r\n]+)""").find(script)?.groupValues?.get(1)?.trim() ?: ""

        return LyricsSource(
            id = id,
            name = name,
            version = version,
            author = author,
            description = desc,
            script = script
        )
    }

    /**
     * 执行源的 search 函数
     */
    fun search(source: LyricsSource, title: String, artist: String, duration: Long): List<SourceSearchItem> {
        val rawJson = executeJsMethod(source.script, "__callSearch", title, artist, duration) as? String
        if (rawJson.isNullOrBlank() || rawJson == "[]") return emptyList()

        return try {
            val listType = object : TypeToken<List<SourceSearchItem>>() {}.type
            gson.fromJson(rawJson, listType) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.d(TAG, "[${source.name}] 解析搜索结果异常: ${e.message}")
            emptyList()
        }
    }

    /**
     * 执行源的 getLyrics 函数
     */
    fun getLyrics(source: LyricsSource, id: String): SourceLyricResult? {
        val rawJson = executeJsMethod(source.script, "__callGetLyrics", id) as? String
        if (rawJson.isNullOrBlank()) return null

        return try {
            gson.fromJson(rawJson, SourceLyricResult::class.java)
        } catch (e: Exception) {
            AppLogger.d(TAG, "[${source.name}] 解析歌词结果异常: ${e.message}")
            null
        }
    }

    /**
     * 执行源的 direct match 函数 (可选生命周期，如汽水音乐根据 mediaId 直查)
     */
    fun match(source: LyricsSource, title: String, artist: String, duration: Long, mediaId: String?): SourceLyricResult? {
        val rawJson = executeJsMethod(source.script, "__callMatch", title, artist, duration, mediaId ?: "") as? String
        if (rawJson.isNullOrBlank()) return null

        return try {
            gson.fromJson(rawJson, SourceLyricResult::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // 预编译脚本缓存 (基于脚本内容 MD5，最多缓存 32 个源，超限自动淘汰，防长期运行内存泄漏)
    private val scriptCacheLock = Any()
    private val compiledScriptCache = object : java.util.LinkedHashMap<String, Script>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?): Boolean {
            return size > 32
        }
    }

    /**
     * Rhino 安全沙箱 ContextFactory：
     * 1. 禁用所有未经白名单许可的原生 Java 类访问，杜绝 RCE、提权与反射逃逸
     * 2. 注入真实墙上时钟超时监控与指令安全上限，防止死循环冻结车机，同时保证复杂 JSON 解析正常完成
     */
    private class SandboxContextFactory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setGenerateObserverCount(true)
            cx.instructionObserverThreshold = 20_000

            // 严格白名单控制暴露给 JS 的 Java 类
            cx.setClassShutter(object : ClassShutter {
                override fun visibleToScripts(fullClassName: String): Boolean {
                    return fullClassName.startsWith("com.carlyrics.lyrics.LyricsSourceRunner") ||
                           fullClassName.startsWith("java.lang.String") ||
                           fullClassName.startsWith("java.lang.Number") ||
                           fullClassName.startsWith("java.lang.Boolean") ||
                           fullClassName.startsWith("java.lang.Double") ||
                           fullClassName.startsWith("java.lang.Integer") ||
                           fullClassName.startsWith("java.lang.Long") ||
                           fullClassName.startsWith("java.lang.Float") ||
                           fullClassName.startsWith("java.util.Map") ||
                           fullClassName.startsWith("java.util.List")
                }
            })
            return cx
        }

        override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
            val startTime = cx?.getThreadLocal("startTime") as? Long ?: return
            val maxDurationMs = cx.getThreadLocal("maxDurationMs") as? Long ?: 3500L
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > maxDurationMs) {
                throw IllegalStateException("JS 脚本执行超时已超过 ${maxDurationMs}ms (累计执行指令: $instructionCount)")
            }
            if (instructionCount > 10_000_000) {
                throw IllegalStateException("JS 脚本执行指令数超上限 (10,000,000 条)，已被沙箱强制熔断")
            }
        }
    }

    private val sandboxFactory = SandboxContextFactory()

    /**
     * 底层 Rhino 沙箱执行调度 (严格安全沙箱 + 预编译缓存)
     */
    private fun executeJsMethod(script: String, funcName: String, vararg args: Any?): Any? {
        val cx = sandboxFactory.enterContext()
        return try {
            cx.putThreadLocal("startTime", System.currentTimeMillis())
            cx.putThreadLocal("maxDurationMs", 3500L)

            // 使用安全标准对象 (不暴露 Packages, JavaAdapter, JavaImporter)
            val scope = cx.initSafeStandardObjects()

            // 注入受控宿主 API
            ScriptableObject.putProperty(scope, "__nativeHttp", Context.javaToJS(JsHttp, scope))
            ScriptableObject.putProperty(scope, "__nativeCrypto", Context.javaToJS(JsCrypto, scope))
            ScriptableObject.putProperty(scope, "__nativeConsole", Context.javaToJS(JsConsole, scope))

            // 预编译缓存：同一脚本只编译一次，极大提升切歌与搜索效率
            val scriptHash = JsCrypto.md5(script)
            val compiled = synchronized(scriptCacheLock) {
                compiledScriptCache[scriptHash] ?: cx.compileString(RUNNER_POLYFILL + "\n" + script, "source_$scriptHash.js", 1, null).also {
                    compiledScriptCache[scriptHash] = it
                }
            }
            compiled.exec(cx, scope)

            val func = scope.get(funcName, scope) as? Function ?: return null
            val jsArgs = args.map { Context.javaToJS(it, scope) }.toTypedArray()
            val result = func.call(cx, scope, scope, jsArgs)
            Context.jsToJava(result, Any::class.java)
        } catch (e: Exception) {
            AppLogger.e(TAG, "JS 沙箱拦截或执行异常 [$funcName]: ${e.message}", e)
            null
        } finally {
            Context.exit()
        }
    }

    // ==================== 注入 JS 宿主 API ====================

    object JsHttp {

        private fun isSafePublicUrl(urlStr: String): Boolean {
            val lower = urlStr.trim().lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            return try {
                val uri = java.net.URI(urlStr)
                val host = uri.host?.lowercase() ?: return false
                if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
                if (host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") return false
                if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
                if (host.startsWith("172.")) {
                    val parts = host.split(".")
                    if (parts.size >= 2) {
                        val second = parts[1].toIntOrNull()
                        if (second != null && second in 16..31) return false
                    }
                }
                if (host.startsWith("100.")) {
                    val parts = host.split(".")
                    if (parts.size >= 2) {
                        val second = parts[1].toIntOrNull()
                        if (second != null && second in 64..127) return false
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic
        @JvmOverloads
        fun get(url: Any?, headersObj: Any? = null): String? {
            val urlStr = url?.toString() ?: return null
            if (!isSafePublicUrl(urlStr)) {
                AppLogger.d(TAG, "http.get 拦截私有网段/回环请求 (SSRF 防护): $urlStr")
                return null
            }
            val reqBuilder = Request.Builder().url(urlStr)
            val headers = parseHeaders(headersObj)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            return try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "http.get 失败: $urlStr, err=${e.message}")
                null
            }
        }

        @JvmStatic
        @JvmOverloads
        fun post(url: Any?, bodyStr: Any?, headersObj: Any? = null): String? {
            val urlStr = url?.toString() ?: return null
            if (!isSafePublicUrl(urlStr)) {
                AppLogger.d(TAG, "http.post 拦截私有网段/回环请求 (SSRF 防护): $urlStr")
                return null
            }
            val bodyText = bodyStr?.toString() ?: ""
            val headers = parseHeaders(headersObj)
            val cType = headers["Content-Type"] ?: headers["content-type"] ?: "application/json; charset=utf-8"
            val body = bodyText.toRequestBody(cType.toMediaTypeOrNull())
            val reqBuilder = Request.Builder().url(urlStr).post(body)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            return try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "http.post 失败: $urlStr, err=${e.message}")
                null
            }
        }

        private fun parseHeaders(headersObj: Any?): Map<String, String> {
            if (headersObj == null) return emptyMap()
            if (headersObj is org.mozilla.javascript.Scriptable) {
                val map = mutableMapOf<String, String>()
                for (id in headersObj.ids) {
                    val key = id.toString()
                    val value = headersObj.get(key, headersObj)?.toString() ?: ""
                    map[key] = value
                }
                return map
            }
            if (headersObj is Map<*, *>) {
                return headersObj.entries.associate { it.key.toString() to it.value.toString() }
            }
            if (headersObj is String && headersObj.startsWith("{")) {
                return try {
                    val map = gson.fromJson(headersObj, Map::class.java)
                    map.entries.associate { it.key.toString() to it.value.toString() }
                } catch (_: Exception) { emptyMap() }
            }
            return emptyMap()
        }
    }

    object JsCrypto {
        @JvmStatic
        fun md5(input: Any?): String {
            val str = input?.toString() ?: ""
            return try {
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(str.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { "" }
        }

        @JvmStatic
        fun base64Encode(input: Any?): String {
            val str = input?.toString() ?: ""
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Base64.getEncoder().encodeToString(str.toByteArray())
                } else {
                    android.util.Base64.encodeToString(str.toByteArray(), android.util.Base64.NO_WRAP)
                }
            } catch (_: Exception) { "" }
        }

        @JvmStatic
        fun base64Decode(input: Any?): String {
            val str = input?.toString() ?: ""
            return try {
                val cleaned = str.replace("\r", "").replace("\n", "").replace(" ", "").trim()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        String(Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
                    } catch (_: Exception) {
                        String(Base64.getMimeDecoder().decode(str), Charsets.UTF_8)
                    }
                } else {
                    String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }
            } catch (e: Exception) {
                AppLogger.d("LyricsSourceJS", "base64Decode error: ${e.message}")
                ""
            }
        }
    }

    object JsConsole {
        @JvmStatic
        fun log(msg: Any?) {
            AppLogger.d("LyricsSourceJS", msg?.toString() ?: "null")
        }

        @JvmStatic
        fun error(msg: Any?) {
            AppLogger.e("LyricsSourceJS", msg?.toString() ?: "null")
        }
    }
}

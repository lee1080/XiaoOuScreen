package com.carlyrics.lyrics

import com.carlyrics.util.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 歌词源 JavaScript 沙箱执行器 (基于 Rhino 引擎)
 */
object LyricsSourceRunner {

    private const val TAG = "LyricsSourceRunner"
    private val gson = Gson()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
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

    /**
     * 底层 Rhino 沙箱执行调度
     */
    private fun executeJsMethod(script: String, funcName: String, vararg args: Any?): Any? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1 // 解释执行模式，兼容 Android 全平台与 Dalvik/ART
            cx.languageVersion = Context.VERSION_ES6

            val scope = cx.initStandardObjects()

            // 注入宿主 API
            ScriptableObject.putProperty(scope, "__nativeHttp", Context.javaToJS(JsHttp, scope))
            ScriptableObject.putProperty(scope, "__nativeCrypto", Context.javaToJS(JsCrypto, scope))
            ScriptableObject.putProperty(scope, "__nativeConsole", Context.javaToJS(JsConsole, scope))

            // 注入运行环境与垫片
            cx.evaluateString(scope, RUNNER_POLYFILL, "polyfill.js", 1, null)
            // 执行源脚本
            cx.evaluateString(scope, script, "source.js", 1, null)

            val func = scope.get(funcName, scope) as? Function ?: return null
            val jsArgs = args.map { Context.javaToJS(it, scope) }.toTypedArray()
            val result = func.call(cx, scope, scope, jsArgs)
            Context.jsToJava(result, Any::class.java)
        } catch (e: Exception) {
            AppLogger.e(TAG, "JS 执行异常 [$funcName]: ${e.message}", e)
            null
        } finally {
            Context.exit()
        }
    }

    // ==================== 注入 JS 宿主 API ====================

    object JsHttp {
        @JvmStatic
        @JvmOverloads
        fun get(url: Any?, headersObj: Any? = null): String? {
            val urlStr = url?.toString() ?: return null
            val reqBuilder = Request.Builder().url(urlStr)
            val headers = parseHeaders(headersObj)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            return try {
                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) resp.body?.string() else null
            } catch (e: Exception) {
                AppLogger.d(TAG, "http.get 失败: $urlStr, err=${e.message}")
                null
            }
        }

        @JvmStatic
        @JvmOverloads
        fun post(url: Any?, bodyStr: Any?, headersObj: Any? = null): String? {
            val urlStr = url?.toString() ?: return null
            val bodyText = bodyStr?.toString() ?: ""
            val headers = parseHeaders(headersObj)
            val cType = headers["Content-Type"] ?: headers["content-type"] ?: "application/json; charset=utf-8"
            val body = bodyText.toRequestBody(cType.toMediaTypeOrNull())
            val reqBuilder = Request.Builder().url(urlStr).post(body)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            return try {
                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) resp.body?.string() else null
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

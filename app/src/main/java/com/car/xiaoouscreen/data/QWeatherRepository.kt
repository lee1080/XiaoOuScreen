package com.car.xiaoouscreen.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.carlyrics.util.PrivacyUtil
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

class QWeatherRepository(private val context: Context) {

    val configManager = QWeatherConfigManager(context)
    private val prefs: SharedPreferences =
        com.carlyrics.util.PrefsManager.getSafeStorageContext(context)
            .getSharedPreferences("qweather_data_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 内存 L1 缓存
    var lastCachedNow: QWeatherNowDto? = null
        private set
    var lastCachedMinutely: QWeatherMinutelyResponse? = null
        private set
    var lastNowTimestamp: Long = 0L
        private set
    var lastMinutelyTimestamp: Long = 0L
        private set

    // 异常熔断标记 (如遇到 403 或 429，临时熔断降级为 Open-Meteo，15分钟冷却期满后自动自愈探测)
    private var circuitBrokenTimeMs: Long = 0L
    var isCircuitBroken: Boolean = false
        get() {
            if (field) {
                val elapsed = System.currentTimeMillis() - circuitBrokenTimeMs
                if (elapsed >= CIRCUIT_BREAKER_COOLDOWN_MS) {
                    Log.i(TAG, "QWeather 熔断冷却期满 (15分钟)，自动恢复接口探测自愈")
                    field = false
                    circuitBrokenTimeMs = 0L
                }
            }
            return field
        }
        private set

    init {
        loadCacheFromDisk()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private var cachedHost: String? = null
    private var cachedApi: QWeatherApi? = null

    private fun createApi(customHost: String? = null): QWeatherApi {
        val host = customHost ?: configManager.getBaseUrl()
        if (customHost == null && cachedApi != null && cachedHost == host) {
            return cachedApi!!
        }
        val api = Retrofit.Builder()
            .baseUrl(host)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QWeatherApi::class.java)
        if (customHost == null) {
            cachedHost = host
            cachedApi = api
        }
        return api
    }

    /**
     * 极轻量探测握手：用于设置页面的“测试连接”功能 (支持传入当前 GPS/网络坐标校验实际位置)
     */
    suspend fun testConnection(
        key: String,
        host: String = configManager.apiHost,
        lat: Double? = null,
        lon: Double? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val trimmedKey = key.trim()
            if (trimmedKey.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API Key 不能为空"))
            }

            var cleanHost = host.trim()
            if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8)
            if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7)
            if (cleanHost.endsWith("/")) cleanHost = cleanHost.dropLast(1)
            if (cleanHost.isEmpty()) cleanHost = configManager.apiHost
            val baseUrl = "https://$cleanHost/"

            val queryLoc = if (lat != null && lon != null) {
                String.format(Locale.US, "%.2f,%.2f", lon, lat)
            } else {
                "116.40,39.90"
            }

            try {
                val testApi = createApi(baseUrl)
                val response = testApi.getWeatherNow(location = queryLoc, key = trimmedKey)
                if (response.code == "200") {
                    resetCircuitBreaker()
                    val locLabel = if (lat != null && lon != null) {
                        String.format(Locale.US, "[%.2f°E, %.2f°N]", lon, lat)
                    } else {
                        "[基准站]"
                    }
                    val now = response.now
                    Result.success("连接成功！$locLabel 实况气温: ${now?.temp ?: "--"}℃, 现象: ${now?.text ?: "--"}, 风向: ${now?.windDir ?: "--"} ${now?.windScale ?: "--"}级")
                } else {
                    val msg = when (response.code) {
                        "401" -> "API Key 错误或无权访问"
                        "402" -> "账户已欠费或配额用尽"
                        "403" -> "403 无权访问 (和风已全面推行专属域名机制，需使用控制台分配的专属 API Host)"
                        "404" -> "未查询到对应气象数据"
                        "429" -> "请求过于频繁 (超过 QPS 限制)"
                        else -> "接口返回错误码: ${response.code}"
                    }
                    Result.failure(Exception(msg))
                }
            } catch (e: HttpException) {
                val err = when (e.code()) {
                    403 -> "403 Invalid Host: 和风已全面推行专属域名，请在控制台 (console.qweather.com) 获取当前项目的专属 API Host"
                    401 -> "401 Unauthorized: API Key 无效"
                    429 -> "429 Too Many Requests: 接口频次超限"
                    else -> "HTTP 请求失败 [${e.code()}]: ${e.message()}"
                }
                Result.failure(Exception(err))
            } catch (e: Exception) {
                Result.failure(Exception("连接超时或网络异常: ${e.message}"))
            }
        }

    /**
     * 获取地面国家站实况 (温度、体感温、风向风级、天气现象)
     */
    suspend fun getWeatherNow(lat: Double, lon: Double): Result<QWeatherNowDto> =
        withContext(Dispatchers.IO) {
            if (!configManager.isConfigured() || isCircuitBroken) {
                lastCachedNow?.let { return@withContext Result.success(it) }
                return@withContext Result.failure(IllegalStateException("QWeather not configured or circuit broken"))
            }

            val loc = String.format(Locale.US, "%.2f,%.2f", lon, lat)
            Log.d(TAG, "📡 [QWeather API] 实际请求实况数据: BaseUrl=${configManager.getBaseUrl()}, Location=${PrivacyUtil.maskCoordinate(lon)},${PrivacyUtil.maskCoordinate(lat)}")
            try {
                val api = createApi()
                val response = api.getWeatherNow(location = loc, key = configManager.apiKey)
                if (response.code == "200" && response.now != null) {
                    val now = response.now
                    lastCachedNow = now
                    lastNowTimestamp = System.currentTimeMillis()
                    saveNowToDisk(now)
                    Result.success(now)
                } else {
                    checkErrorCode(response.code)
                    lastCachedNow?.let { Result.success(it) }
                        ?: Result.failure(Exception("QWeather now error: ${response.code}"))
                }
            } catch (e: HttpException) {
                checkHttpException(e)
                lastCachedNow?.let { Result.success(it) } ?: Result.failure(e)
            } catch (e: Exception) {
                Log.w(TAG, "QWeather now request failed, using cache if available: ${e.message}")
                lastCachedNow?.let { Result.success(it) } ?: Result.failure(e)
            }
        }

    /**
     * 获取未来 2 小时多普勒雷达 1km 分钟降水外推
     */
    suspend fun getMinutelyPrecipitation(lat: Double, lon: Double): Result<QWeatherMinutelyResponse> =
        withContext(Dispatchers.IO) {
            if (!configManager.isConfigured() || isCircuitBroken) {
                lastCachedMinutely?.let { return@withContext Result.success(it) }
                return@withContext Result.failure(IllegalStateException("QWeather not configured or circuit broken"))
            }

            val loc = String.format(Locale.US, "%.2f,%.2f", lon, lat)
            Log.d(TAG, "📡 [QWeather API] 实际请求分钟降水数据: BaseUrl=${configManager.getBaseUrl()}, Location=${PrivacyUtil.maskCoordinate(lon)},${PrivacyUtil.maskCoordinate(lat)}")
            try {
                val api = createApi()
                val response = api.getMinutely5m(location = loc, key = configManager.apiKey)
                if (response.code == "200") {
                    lastCachedMinutely = response
                    lastMinutelyTimestamp = System.currentTimeMillis()
                    saveMinutelyToDisk(response)
                    Result.success(response)
                } else {
                    checkErrorCode(response.code)
                    lastCachedMinutely?.let { Result.success(it) }
                        ?: Result.failure(Exception("QWeather minutely error: ${response.code}"))
                }
            } catch (e: HttpException) {
                checkHttpException(e)
                lastCachedMinutely?.let { Result.success(it) } ?: Result.failure(e)
            } catch (e: Exception) {
                Log.w(TAG, "QWeather minutely failed: ${e.message}")
                lastCachedMinutely?.let { Result.success(it) } ?: Result.failure(e)
            }
        }

    fun resetCircuitBreaker() {
        isCircuitBroken = false
        circuitBrokenTimeMs = 0L
    }

    private fun checkErrorCode(code: String) {
        if (code == "401" || code == "403" || code == "402") {
            Log.e(TAG, "QWeather critical error $code, triggering circuit breaker (15 min cooldown)")
            isCircuitBroken = true
            circuitBrokenTimeMs = System.currentTimeMillis()
        }
    }

    private fun checkHttpException(e: HttpException) {
        if (e.code() == 401 || e.code() == 403 || e.code() == 429) {
            Log.e(TAG, "QWeather critical HTTP ${e.code()}, triggering circuit breaker (15 min cooldown)")
            isCircuitBroken = true
            circuitBrokenTimeMs = System.currentTimeMillis()
        }
    }

    private fun saveNowToDisk(now: QWeatherNowDto) {
        try {
            val nowMs = System.currentTimeMillis()
            lastNowTimestamp = nowMs
            prefs.edit()
                .putString(KEY_CACHE_NOW, gson.toJson(now))
                .putLong(KEY_CACHE_NOW_TIME, nowMs)
                .apply()
        } catch (_: Exception) {}
    }

    private fun saveMinutelyToDisk(resp: QWeatherMinutelyResponse) {
        try {
            val nowMs = System.currentTimeMillis()
            lastMinutelyTimestamp = nowMs
            prefs.edit()
                .putString(KEY_CACHE_MINUTELY, gson.toJson(resp))
                .putLong(KEY_CACHE_MINUTELY_TIME, nowMs)
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadCacheFromDisk() {
        try {
            val nowJson = prefs.getString(KEY_CACHE_NOW, null)
            if (nowJson != null) {
                lastCachedNow = gson.fromJson(nowJson, QWeatherNowDto::class.java)
                lastNowTimestamp = prefs.getLong(KEY_CACHE_NOW_TIME, 0L)
            }
            val minJson = prefs.getString(KEY_CACHE_MINUTELY, null)
            val minTime = prefs.getLong(KEY_CACHE_MINUTELY_TIME, 0L)
            // 雷达分钟级降水有效生命周期最多 35 分钟，过期则自动丢弃
            if (minJson != null && System.currentTimeMillis() - minTime < 35 * 60 * 1000L) {
                lastCachedMinutely = gson.fromJson(minJson, QWeatherMinutelyResponse::class.java)
                lastMinutelyTimestamp = minTime
            } else {
                lastCachedMinutely = null
                lastMinutelyTimestamp = 0L
                prefs.edit().remove(KEY_CACHE_MINUTELY).remove(KEY_CACHE_MINUTELY_TIME).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache: ${e.message}")
        }
    }

    fun clearCache() {
        lastCachedNow = null
        lastCachedMinutely = null
        lastNowTimestamp = 0L
        lastMinutelyTimestamp = 0L
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "QWeatherRepo"
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 15 * 60 * 1000L // 15分钟熔断冷却期
        private const val KEY_CACHE_NOW = "key_cached_now_json"
        private const val KEY_CACHE_NOW_TIME = "key_cached_now_time"
        private const val KEY_CACHE_MINUTELY = "key_cached_minutely_json"
        private const val KEY_CACHE_MINUTELY_TIME = "key_cached_minutely_time"
    }
}

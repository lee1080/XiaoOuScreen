package com.car.xiaoouscreen.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.car.xiaoouscreen.domain.WeatherSceneConfig
import com.car.xiaoouscreen.domain.WeatherSceneMapper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class OpenMeteoRepository(context: Context) {

    private val prefs: SharedPreferences =
        com.carlyrics.util.PrefsManager.getSafeStorageContext(context)
            .getSharedPreferences("car_weather_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 最新一次获取的未来数小时最大降水概率 (0-100)
     */
    var lastMaxPrecipProbability: Int = 0
        private set

    var lastSunriseUnix: Long = 0L
        private set

    var lastSunsetUnix: Long = 0L
        private set

    init {
        // 构造时立即从本地 7 天离线天文缓存中还原当天的日落与日出时间戳，杜绝冷启动时内存变量为 0L
        try {
            val json = prefs.getString(KEY_CACHED_JSON, null)
            if (!json.isNullOrBlank()) {
                val cachedResponse = gson.fromJson(json, OpenMeteoResponse::class.java)
                val nowUnix = System.currentTimeMillis() / 1000L
                val (sunrise, sunset) = extractTodaySunTimes(cachedResponse.daily, nowUnix)
                lastSunriseUnix = sunrise
                lastSunsetUnix = sunset
                lastMaxPrecipProbability = cachedResponse.hourly?.precipitationProbability?.take(6)?.maxOrNull() ?: 0
                Log.d(TAG, "📦 OpenMeteoRepo 构造即刻恢复离线天文台: sunrise=$sunrise, sunset=$sunset")
            }
        } catch (_: Exception) {}
    }

    private val api: OpenMeteoApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoApi::class.java)
    }

    /**
     * 获取天气场景配置
     * 1. 优先请求 Open-Meteo
     * 2. 成功后写入本地持久化缓存
     * 3. 失败（地下车库/过隧道/断网）则无缝读取离线缓存，并以当前系统时间重新计算昼夜时段
     */
    suspend fun getWeatherScene(lat: Double, lon: Double): Result<WeatherSceneConfig> =
        withContext(Dispatchers.IO) {
            val nowUnix = System.currentTimeMillis() / 1000L

            try {
                val response = api.getForecast(latitude = lat, longitude = lon)
                val current = response.current ?: throw IllegalStateException("Current weather is null")
                val daily = response.daily

                // 精准匹配当天的日出日落时间戳 (防止跨天多日缓存首元素错位)
                val (sunriseUnix, sunsetUnix) = extractTodaySunTimes(daily, nowUnix)
                lastSunriseUnix = sunriseUnix
                lastSunsetUnix = sunsetUnix

                val probs = response.hourly?.precipitationProbability ?: emptyList()
                lastMaxPrecipProbability = probs.take(6).maxOrNull() ?: 0

                // 写入本地持久化缓存
                saveToCache(response)

                val scene = WeatherSceneMapper.mapToScene(
                    wmoCode = current.weatherCode,
                    nowUnix = nowUnix,
                    sunriseUnix = sunriseUnix,
                    sunsetUnix = sunsetUnix,
                    windSpeed = current.windSpeed10m,
                    temperature = current.temperature2m,
                    windDirection = current.windDirection10m
                )
                Result.success(scene)
            } catch (e: Exception) {
                Log.w(TAG, "Network weather request failed: ${e.message}, falling back to cache")
                val cachedScene = loadFromCache(nowUnix)
                if (cachedScene != null) {
                    Result.success(cachedScene)
                } else {
                    // 全空保底：使用默认场景并按当前本地时间判定白天或夜晚
                    val fallback = WeatherSceneMapper.mapToScene(
                        wmoCode = 0,
                        nowUnix = nowUnix,
                        sunriseUnix = 0L,
                        sunsetUnix = 0L
                    )
                    Result.success(fallback)
                }
            }
        }

    private fun saveToCache(response: OpenMeteoResponse) {
        try {
            val json = gson.toJson(response)
            prefs.edit().putString(KEY_CACHED_JSON, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache weather data", e)
        }
    }

    private fun loadFromCache(nowUnix: Long): WeatherSceneConfig? {
        val json = prefs.getString(KEY_CACHED_JSON, null) ?: return null
        return try {
            val cachedResponse = gson.fromJson(json, OpenMeteoResponse::class.java)
            val current = cachedResponse.current ?: return null
            val (sunriseUnix, sunsetUnix) = extractTodaySunTimes(cachedResponse.daily, nowUnix)
            lastSunriseUnix = sunriseUnix
            lastSunsetUnix = sunsetUnix
            lastMaxPrecipProbability = cachedResponse.hourly?.precipitationProbability?.take(6)?.maxOrNull() ?: 0

            WeatherSceneMapper.mapToScene(
                wmoCode = current.weatherCode,
                nowUnix = nowUnix, // 使用当前最新的时间重新跑映射逻辑
                sunriseUnix = sunriseUnix,
                sunsetUnix = sunsetUnix,
                windSpeed = current.windSpeed10m,
                temperature = current.temperature2m,
                windDirection = current.windDirection10m
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached weather", e)
            null
        }
    }

    private fun extractTodaySunTimes(daily: DailyWeatherDto?, nowUnix: Long): Pair<Long, Long> {
        if (daily == null || daily.sunrise.isNullOrEmpty() || daily.sunset.isNullOrEmpty()) {
            return Pair(0L, 0L)
        }
        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = nowUnix * 1000L }
        val nowYear = calNow.get(java.util.Calendar.YEAR)
        val nowDay = calNow.get(java.util.Calendar.DAY_OF_YEAR)

        val calItem = java.util.Calendar.getInstance()
        for (i in daily.sunrise.indices) {
            val rise = daily.sunrise[i]
            calItem.timeInMillis = rise * 1000L
            if (calItem.get(java.util.Calendar.YEAR) == nowYear && calItem.get(java.util.Calendar.DAY_OF_YEAR) == nowDay) {
                val set = daily.sunset.getOrNull(i) ?: 0L
                return Pair(rise, set)
            }
        }
        // 兜底：若跨天缓存未能精确匹配今天，选择离当前时刻最近的日落所在的日
        val bestIdx = daily.sunset.indices.minByOrNull { kotlin.math.abs(daily.sunset[it] - nowUnix) } ?: 0
        return Pair(daily.sunrise.getOrElse(bestIdx) { 0L }, daily.sunset.getOrElse(bestIdx) { 0L })
    }

    companion object {
        private const val TAG = "CarWeatherRepo"
        private const val KEY_CACHED_JSON = "key_cached_open_meteo_json"
    }
}

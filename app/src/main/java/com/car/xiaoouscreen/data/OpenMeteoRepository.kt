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
        context.getSharedPreferences("car_weather_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

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

                // 寻找离当前最近的今天日出日落时间戳
                val sunriseUnix = daily?.sunrise?.firstOrNull() ?: 0L
                val sunsetUnix = daily?.sunset?.firstOrNull() ?: 0L

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
            val sunriseUnix = cachedResponse.daily?.sunrise?.firstOrNull() ?: 0L
            val sunsetUnix = cachedResponse.daily?.sunset?.firstOrNull() ?: 0L

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

    companion object {
        private const val TAG = "CarWeatherRepo"
        private const val KEY_CACHED_JSON = "key_cached_open_meteo_json"
    }
}

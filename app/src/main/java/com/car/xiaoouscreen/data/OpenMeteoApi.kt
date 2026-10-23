package com.car.xiaoouscreen.data

import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {

    /**
     * 获取 Open-Meteo 实时与日出日落天气数据
     * 关键参数：
     * - timeformat=unixtime: 强制返回秒级整数时间戳，防止系统时区格式混乱
     * - timezone=auto: 自动使用目标经纬度的自然地理时区
     */
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m",
        @Query("daily") daily: String = "sunrise,sunset",
        @Query("timeformat") timeformat: String = "unixtime",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}

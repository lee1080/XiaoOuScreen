package com.car.xiaoouscreen.data

import com.google.gson.annotations.SerializedName

/**
 * Open-Meteo API 响应数据结构 (设置 timeformat=unixtime)
 */
data class OpenMeteoResponse(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("timezone") val timezone: String,
    @SerializedName("current") val current: CurrentWeatherDto?,
    @SerializedName("daily") val daily: DailyWeatherDto?
)

data class CurrentWeatherDto(
    @SerializedName("time") val time: Long,
    @SerializedName("temperature_2m") val temperature2m: Float,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: Int,
    @SerializedName("weather_code") val weatherCode: Int,
    @SerializedName("wind_speed_10m") val windSpeed10m: Float,
    @SerializedName("wind_direction_10m") val windDirection10m: Float
)

data class DailyWeatherDto(
    @SerializedName("time") val time: List<Long>,
    @SerializedName("sunrise") val sunrise: List<Long>,
    @SerializedName("sunset") val sunset: List<Long>
)

package com.car.xiaoouscreen.data

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 和风天气 API Retrofit 接口
 */
interface QWeatherApi {

    /**
     * 实况天气：获取国家级自动站气象实况 (温度、体感、风向、风级、实况现象)
     * location: 经度,纬度 (如 "121.47,31.23")
     */
    @GET("v7/weather/now")
    suspend fun getWeatherNow(
        @Query("location") location: String,
        @Query("key") key: String
    ): QWeatherNowResponse

    /**
     * 分钟级降水：获取未来2小时每5分钟降水雷达外推序列
     */
    @GET("v7/minutely/5m")
    suspend fun getMinutely5m(
        @Query("location") location: String,
        @Query("key") key: String
    ): QWeatherMinutelyResponse
}

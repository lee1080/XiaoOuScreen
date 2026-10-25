package com.car.xiaoouscreen.data

import com.google.gson.annotations.SerializedName

/**
 * 和风天气 /v7/weather/now 实时天气响应模型
 */
data class QWeatherNowResponse(
    @SerializedName("code") val code: String,
    @SerializedName("updateTime") val updateTime: String?,
    @SerializedName("now") val now: QWeatherNowDto?
)

data class QWeatherNowDto(
    @SerializedName("obsTime") val obsTime: String?,
    @SerializedName("temp") val temp: String,                 // 摄氏度
    @SerializedName("feelsLike") val feelsLike: String,       // 体感温度
    @SerializedName("icon") val icon: String,                 // 图标代码如 "100"
    @SerializedName("text") val text: String,                 // 天气文本如 "晴"
    @SerializedName("wind360") val wind360: String,           // 风向360角度
    @SerializedName("windDir") val windDir: String,           // 风向文本如 "东北风"
    @SerializedName("windScale") val windScale: String,       // 风力等级如 "2"
    @SerializedName("windSpeed") val windSpeed: String,       // 风速 km/h
    @SerializedName("humidity") val humidity: String,         // 相对湿度 %
    @SerializedName("precip") val precip: String,             // 过去1小时降水量 mm
    @SerializedName("pressure") val pressure: String,         // 气压 hPa
    @SerializedName("vis") val vis: String,                   // 能见度 km
    @SerializedName("cloud") val cloud: String?               // 云量 %
)

/**
 * 和风天气 /v7/minutely/5m 分钟级降水响应模型
 */
data class QWeatherMinutelyResponse(
    @SerializedName("code") val code: String,
    @SerializedName("updateTime") val updateTime: String?,
    @SerializedName("summary") val summary: String?,          // "预计25分钟后雨停"
    @SerializedName("minutely") val minutely: List<QWeatherMinutelyItem>?
)

data class QWeatherMinutelyItem(
    @SerializedName("fxTime") val fxTime: String,             // 预报时间点
    @SerializedName("precip") val precip: String,             // 降水量 mm
    @SerializedName("type") val type: String                  // "rain" 或 "snow"
)

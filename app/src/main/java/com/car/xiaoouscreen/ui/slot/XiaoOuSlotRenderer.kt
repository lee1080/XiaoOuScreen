package com.car.xiaoouscreen.ui.slot

import android.graphics.Bitmap
import android.graphics.Canvas
import com.car.xiaoouscreen.domain.WeatherSceneConfig

/**
 * 零 GC 复用的分区渲染上下文（每帧原地更新字段，杜绝 onDraw 内存分配）
 */
class XiaoOuSlotRenderContext {
    var currentConfig: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    var previousConfig: WeatherSceneConfig = WeatherSceneConfig.DEFAULT
    var transitionProgress: Float = 1f
    var lyricsModeProgress: Float = 0f
    var timeStr: String = "12:00"
    var dateStr: String = ""
    var lightningActive: Boolean = false
    var lightningIntensity: Float = 0f
    var nowUptimeMs: Long = 0L

    // 实时音乐与专辑封面数据（供歌曲封面模块使用）
    var trackTitle: String = ""
    var trackArtist: String = ""
    var isMusicPlaying: Boolean = false
    var coverBitmap: Bitmap? = null
    var coverFadeAlpha: Float = 1f
    var albumCoverSlotAlpha: Float = 0f

    // 12V 蓄电池实车总线数据（供蓄电池信息模块使用）
    var batteryVoltageV: Float? = null
    var batterySohPercent: Int? = null
    var batterySocPercent: Int? = null
    var batteryLowWarning: Boolean = false

    // 室外温度实车传感器数据（供天气信息等模块使用）
    var carOutsideTempC: Float? = null
    var carCabinTempC: Float? = null

    // 车载实时 G 值加速度数据（供 G 值十字准星模块使用）
    var gForceX: Float = 0f
    var gForceY: Float = 0f
    var gForcePeak: Float = 0f
    var gForcePeakX: Float = 0f
    var gForcePeakY: Float = 0f

    // 新能源电机输出 (+kW) / 动能回收 (-kW) 高压母线功率数据（供电机功率模块使用）
    var motorPowerKw: Float? = null
    var motorPowerPeakOutKw: Float = 0f
    var motorPowerPeakRegenKw: Float = 0f
    var motorPowerHvSocPercent: Int? = null
    var motorPowerIsPlugCharging: Boolean = false

    // 发动机 / 电机实时转速数据（供转速自适应模块使用：油车单显发动机、纯电单显电机、混动双显）
    var engineRpm: Int? = null
    var motorRpm: Int? = null
    var enginePeakRpm: Int = 0
    var motorPeakRpm: Int = 0
    var powertrainMode: com.car.xiaoouscreen.car.CarGearManager.PowertrainMode = com.car.xiaoouscreen.car.CarGearManager.PowertrainMode.PURE_ICE

    // 当前档位（如 "P", "R", "N", "D"），供功率表判断是否处于停车驻车状态
    var currentGear: String = "P"
    val isParked: Boolean get() = currentGear.equals("P", ignoreCase = true) || currentGear.equals("N", ignoreCase = true)

    // 标记当前区域在歌词模式下是否需要绘制小时间（避免 A/C 同为天气时重复绘制或完全缺失）
    var showSmallTimeInWeatherSlot: Boolean = true
}

/**
 * 小欧屏 A / C 区域可插拔模块渲染器接口
 *
 * 车规级性能铁律：
 * - 所有 Paint / Path / RectF / Shader / 文本测量缓存必须在初始化或 invalidateData() 时预分配；
 * - 严禁在 [draw] 中创建任何临时对象。
 */
interface XiaoOuSlotRenderer {
    val type: XiaoOuSlotType

    /**
     * 当底层业务数据变更时通知渲染器标记缓存失效
     */
    fun invalidateData() {}

    /**
     * 在指定区域 [region] 内绘制模块内容
     * @param alphaFactor 0f..1f（用于模块切换平滑淡入淡出、通话临时避让、小欧形象避让等）
     */
    fun draw(
        canvas: Canvas,
        region: XiaoOuSlotRegion,
        ctx: XiaoOuSlotRenderContext,
        alphaFactor: Float
    )

    /**
     * 可选：返回本次渲染的左侧文字锚点 X（供左下角档位 HUD 等对齐）
     */
    fun getLastLeftAnchorX(): Float = 185f

    /**
     * 释放离屏资源
     */
    fun release() {}
}

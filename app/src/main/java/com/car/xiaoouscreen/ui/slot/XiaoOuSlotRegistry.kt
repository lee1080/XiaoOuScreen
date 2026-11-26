package com.car.xiaoouscreen.ui.slot

import java.util.EnumMap

/**
 * 小欧屏分区模块渲染器注册表（A / C 区域各独立持有一套实例，互不干扰）
 *
 * 扩展方式：新增模块时在此注册对应 [XiaoOuSlotRenderer] 实例即可。
 */
object XiaoOuSlotRegistry {

    fun createSlotRenderers(): Map<XiaoOuSlotType, XiaoOuSlotRenderer> {
        val map = EnumMap<XiaoOuSlotType, XiaoOuSlotRenderer>(XiaoOuSlotType::class.java)
        map[XiaoOuSlotType.WEATHER] = WeatherSlotRenderer()
        map[XiaoOuSlotType.ALBUM_COVER] = AlbumCoverSlotRenderer()
        map[XiaoOuSlotType.BATTERY_INFO] = BatteryInfoSlotRenderer()
        map[XiaoOuSlotType.G_FORCE] = GForceSlotRenderer()
        map[XiaoOuSlotType.MOTOR_POWER] = MotorPowerSlotRenderer()
        map[XiaoOuSlotType.RPM] = RpmSlotRenderer()
        map[XiaoOuSlotType.NONE] = EmptySlotRenderer()
        return map
    }
}

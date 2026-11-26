package com.car.xiaoouscreen.ui.slot

/**
 * 小欧屏 A / C 可替换区域模块枚举（可扩展插件化注册表入口）
 *
 * 架构说明：
 * - 小欧屏 1920×384 划分为 A（左 130~535）、B（中 535~1385 固定时间/歌词）、C（右 1385~1790）三个区域。
 * - 未来新增任何副屏模块（如胎压、导航指引、音频频谱、车速等），只需：
 *   1. 在本枚举新增一项；
 *   2. 实现对应 [XiaoOuSlotRenderer] 并在 [XiaoOuSlotRegistry] 注册即可，无需改动主画布管线。
 */
enum class XiaoOuSlotType(
    val id: String,
    val title: String,
    val subtitle: String,
    val isImplemented: Boolean
) {
    WEATHER(
        id = "weather",
        title = "天气信息",
        subtitle = "实时温度 · 气象 · 日期",
        isImplemented = true
    ),
    ALBUM_COVER(
        id = "album_cover",
        title = "歌曲封面",
        subtitle = "播放封面 · 停播天气",
        isImplemented = true
    ),
    BATTERY_INFO(
        id = "battery_info",
        title = "蓄电池信息",
        subtitle = "12V 实时电压 · 健康度",
        isImplemented = true
    ),
    G_FORCE(
        id = "g_force",
        title = "G 值准星",
        subtitle = "实时加速度十字准星",
        isImplemented = true
    ),
    MOTOR_POWER(
        id = "motor_power",
        title = "整车功率",
        subtitle = "动力输出 +kW · 动能回收 -kW",
        isImplemented = true
    ),
    RPM(
        id = "rpm",
        title = "实时转速",
        subtitle = "发动机 · 电机智能自适应",
        isImplemented = true
    ),
    NONE(
        id = "none",
        title = "空白留白",
        subtitle = "不显示此区域内容",
        isImplemented = true
    );

    companion object {
        val selectableModules: List<XiaoOuSlotType> = values().toList()

        fun fromId(id: String?, default: XiaoOuSlotType): XiaoOuSlotType {
            if (id.isNullOrBlank()) return default
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: default
        }
    }
}

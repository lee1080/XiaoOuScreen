package com.car.xiaoouscreen.ui

/**
 * 欧尚 Z6 小欧副屏 (1920×384) 微型档位指示器 5 大停靠位置定义
 *
 * 坐标系严格对齐 1920×384 逻辑安全区：
 * - 槽体尺寸：宽 288px，高 60px，同心圆角 17px
 * - 安全边距：上下留白 10px，左右留白依据对称三区锚定
 *
 * 5 大停靠点：
 * 1. BOTTOM_LEFT  (默认位置): A 区域下边中心 [X: 188.5, Y: 314..374]
 * 2. TOP_LEFT     (上方位):   A 区域上边中心 [X: 188.5, Y: 10..70]
 * 3. BOTTOM_CENTER(屏底中心): 整个副屏底边正中 [X: 816.0, Y: 314..374]
 * 4. BOTTOM_RIGHT (C区下边):  C 区域下边中心 [X: 1443.5, Y: 314..374]
 * 5. TOP_RIGHT    (C区上边):  C 区域上边中心 [X: 1443.5, Y: 10..70]
 */
enum class MiniGearPosition(
    val id: Int,
    val title: String,
    val shortName: String,
    val description: String,
    val defaultLeft: Float,
    val defaultTop: Float
) {
    BOTTOM_LEFT(
        id = 0,
        title = "A区下边 · 默认位置",
        shortName = "A区下 (默认)",
        description = "左下角天气/状态区下方，经典常驻停靠位置",
        defaultLeft = 188.5f,
        defaultTop = 314f
    ),
    TOP_LEFT(
        id = 1,
        title = "A区上边",
        shortName = "A区上",
        description = "左上角天气/状态区上方，视线平视自然醒目",
        defaultLeft = 188.5f,
        defaultTop = 10f
    ),
    BOTTOM_CENTER(
        id = 2,
        title = "小欧屏底边中心",
        shortName = "屏底中心",
        description = "副屏整个底边水平正中，与中央大时钟/形象轴对称",
        defaultLeft = 816.0f, // (1920 - 288) / 2 = 816f
        defaultTop = 314f
    ),
    BOTTOM_RIGHT(
        id = 3,
        title = "C区下边",
        shortName = "C区下",
        description = "右下角功能卡槽区下方，副驾驶亲和视野",
        defaultLeft = 1443.5f, // 1385 + (405 - 288) / 2 = 1443.5f
        defaultTop = 314f
    ),
    TOP_RIGHT(
        id = 4,
        title = "C区上边",
        shortName = "C区上",
        description = "右上角功能卡槽区上方，通透无遮挡视野",
        defaultLeft = 1443.5f,
        defaultTop = 10f
    );

    val defaultRight: Float get() = defaultLeft + 288f
    val defaultBottom: Float get() = defaultTop + 60f

    companion object {
        fun fromId(id: Int): MiniGearPosition = values().firstOrNull { it.id == id } ?: BOTTOM_LEFT
    }
}

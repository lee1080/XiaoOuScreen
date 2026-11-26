package com.car.xiaoouscreen.ui.slot

/**
 * 小欧屏 1920×384 绝对对称三区坐标系定义
 *
 * 物理安全区：左 0~130px，右 1790~1920px
 * - A 区域 (LEFT_A) : [130, 535]，宽 405px，中心 332.5px
 * - B 区域 (中心固定): [535, 1385]，宽 850px，中心 960.0px
 * - C 区域 (RIGHT_C): [1385, 1790]，宽 405px，中心 1587.5px
 *
 * A 与 C 区域宽度完全一致（405px），任何 [XiaoOuSlotRenderer] 均可在 A / C 区域无差别复用渲染。
 */
enum class XiaoOuSlotRegion(
    val leftX: Float,
    val rightX: Float,
    val dividerX: Float
) {
    LEFT_A(
        leftX = 130f,
        rightX = 535f,
        dividerX = 535f
    ),
    RIGHT_C(
        leftX = 1385f,
        rightX = 1790f,
        dividerX = 1385f
    );

    val width: Float
        get() = rightX - leftX

    val centerX: Float
        get() = (leftX + rightX) * 0.5f
}

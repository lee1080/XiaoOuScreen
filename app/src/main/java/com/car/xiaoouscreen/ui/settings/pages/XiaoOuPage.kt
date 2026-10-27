package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.XiaoOuState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard

/**
 * 模块 2：小欧屏设置
 * 规范化 2x2 响应式统一卡片网格：
 *   1. 开启小欧屏投屏（核心投屏服务开关）
 *   2. 是否显示小欧形象（3D形象避让联动）
 *   3. 时钟液态玻璃（拟物折射视觉效果）
 *   4. 微型档位悬浮（P/R/N/D 悬浮胶囊）
 */
@Composable
fun XiaoOuPage(
    state: XiaoOuState,
    onToggleProjection: (Boolean) -> Unit,
    onToggleShowAvatar: (Boolean) -> Unit,
    onToggleLiquidGlass: (Boolean) -> Unit,
    onToggleMiniGear: (Boolean) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 第一行：开启小欧屏投屏 + 是否显示小欧形象
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            XiaoOuUniformCard(
                title = "开启小欧屏投屏",
                desc = "向小欧副屏无线投屏时钟、天气及流动歌词；关闭时副屏休眠省电",
                checked = state.isProjecting,
                onCheckedChange = onToggleProjection,
                info = AnnotationInfo(
                    title = "小欧屏无线投屏服务",
                    content = "控制中控车机是否向右侧小欧 3D 实体副屏发送实时画面。开启后状态栏常驻绿色投屏指示灯；关闭时投屏暂停并进入待机。",
                    recommend = "日常行车建议保持开启以享受完整座舱互动体验"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )

            XiaoOuUniformCard(
                title = "是否显示小欧形象",
                desc = "在小欧屏展示 3D 动态小欧；全屏歌词将自动进行右侧安全避让",
                checked = state.showAvatar,
                onCheckedChange = onToggleShowAvatar,
                info = AnnotationInfo(
                    title = "是否显示小欧形象与避让",
                    content = "控制副屏区域是否显示 3D 小欧。开启时全屏歌词自动向左避让以防遮挡；关闭后全屏歌词可全宽满幅铺展。",
                    recommend = "建议开启以获得完整语音与表情联动体验"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )
        }

        // 第二行：时钟液态玻璃 + 微型档位悬浮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            XiaoOuUniformCard(
                title = "时钟液态玻璃",
                desc = "为小欧屏时钟组件启用流体半透明毛玻璃高光折射视觉效果",
                checked = state.liquidGlassClock,
                onCheckedChange = onToggleLiquidGlass,
                info = AnnotationInfo(
                    title = "时钟液态玻璃特效",
                    content = "启用实时高斯模糊与边缘折射高光着色器，使小欧屏数字时钟呈现通透的液态玻璃质感。",
                    recommend = "支持 GPU 硬件加速的车机推荐开启"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )

            XiaoOuUniformCard(
                title = "微型档位悬浮",
                desc = "在屏幕状态区悬浮显示实时车辆档位（P/R/N/D）微型指示胶囊",
                checked = state.miniGearFloat,
                onCheckedChange = onToggleMiniGear,
                info = AnnotationInfo(
                    title = "微型档位悬浮胶囊",
                    content = "在全屏导航或歌词界面角落悬浮显示微型 P / R / N / D 档位状态胶囊，无需低头看仪表。",
                    recommend = "支持自由停靠与车辆 CAN 总线档位实时同步"
                ),
                onShowInfo = onShowInfo,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 统一尺寸、统一文字排版的标准车规级开关卡片
 */
@Composable
private fun XiaoOuUniformCard(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    info: AnnotationInfo,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp)
            .neuCard(cornerRadius = 16.dp)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部：标题 + ? 按钮 + NeuSwitch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )
                    NeuInfoButton(
                        info = info,
                        onShowInfo = onShowInfo
                    )
                }

                NeuSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }

            // 底部：两行以内的清晰说明文本，行高和排版规整统一
            Text(
                text = desc,
                fontSize = 11.sp,
                color = NeuColors.TextSecondary,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

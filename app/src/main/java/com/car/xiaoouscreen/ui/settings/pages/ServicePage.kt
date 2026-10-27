package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCenteredSlider
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.ServiceState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard

/**
 * 模块 6：服务设置 (规范重构版)
 *
 * 核心规范：
 * 1. 开机启动：参照 CarLyricsApp 稳定自启广播
 * 2. 调试日志：接入 AppLogger 弹窗系统，支持清除日志、复制日志、保存日志到文件夹
 * 3. 清理缓存：卡片高度统一为 116.dp，接入实际业务，专门清理歌词与封面缓存
 * 4. 缓存空间上限：中位 64MB，最小 8MB，最大 128MB
 * 5. 版本信息：展示真实构建版本，彻底移除检查更新按钮
 */
@Composable
fun ServicePage(
    state: ServiceState,
    onToggleBootAutoStart: (Boolean) -> Unit,
    onToggleDebugLog: (Boolean) -> Unit,
    onOpenDebugLogDialog: () -> Unit,
    onCleanCache: () -> Unit,
    onCacheLimitChange: (Int) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCacheDisplay = String.format("%.1f", state.cacheTotalMB.coerceAtLeast(state.lyricCacheMB))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 第一行：开机启动 + 调试日志开关 (并排展示，统一 116.dp 高度)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 5.1 开机启动
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(116.dp)
                    .neuCard(cornerRadius = 16.dp)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
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
                                text = "开机启动",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "随车机点火开机启动",
                                    content = "参照 CarLyricsApp 经过实车验证的启动架构，监听 BOOT_COMPLETED 与 ACC 上电广播，通电后秒级拉起小欧屏与投屏服务。",
                                    recommend = "强烈建议保持开启以保障车机体验"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }

                        NeuSwitch(
                            checked = state.bootAutoStart,
                            onCheckedChange = onToggleBootAutoStart
                        )
                    }

                    Text(
                        text = "车辆通电点火时自动在后台拉起核心投屏服务与悬浮窗",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary,
                        lineHeight = 16.sp,
                        maxLines = 2
                    )
                }
            }

            // 5.2 调试日志记录开关
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(116.dp)
                    .neuCard(cornerRadius = 16.dp)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
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
                                text = "调试日志记录开关",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                            NeuInfoButton(
                                info = AnnotationInfo(
                                    title = "实车问题排查日志系统",
                                    content = "已接入 AppLogger 本地持久化与崩溃捕获。开启后自动记录歌词源匹配与网络交互，可随时打开弹窗清除、复制或导出文件。",
                                    recommend = "日常使用建议关闭以减少车机闪存写入"
                                ),
                                onShowInfo = onShowInfo
                            )
                        }

                        NeuSwitch(
                            checked = state.debugLogEnabled,
                            onCheckedChange = onToggleDebugLog
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "捕获歌词源调度与气象网络报文",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )

                        NeuButton(
                            text = "管理日志",
                            onClick = onOpenDebugLogDialog,
                            textColor = NeuColors.AccentBlue
                        )
                    }
                }
            }
        }

        // 第二行：清理缓存统一卡片 (高度严格规范为 116.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .neuCard(cornerRadius = 16.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "清理缓存",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFDBEAFE), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "已占用：$totalCacheDisplay MB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.AccentBlue
                            )
                        }
                        NeuInfoButton(
                            info = AnnotationInfo(
                                title = "歌词与专辑封面缓存说明",
                                content = "自动缓存离线 LRC/逐字歌词文本与高精度专辑封面图片，避免重复联网流量消耗与闪存碎片。清理后不影响个人配置。",
                                recommend = "当车机存储告警或想释放空间时可一键清空"
                            ),
                            onShowInfo = onShowInfo
                        )
                    }

                    NeuButton(
                        text = "立即清理歌词与封面缓存",
                        onClick = onCleanCache,
                        isPrimary = true
                    )
                }

                Text(
                    text = "一键清空本地离线 LRC/KRC 歌词文本、专辑封面缩略图与内存图片缓存（保留个人设置与天气密钥）",
                    fontSize = 11.sp,
                    color = NeuColors.TextSecondary,
                    lineHeight = 16.sp,
                    maxLines = 2
                )
            }
        }

        // 第三行：缓存空间上限 (滑动控制条，中位 64MB，最小 8M，最大 128M)
        NeuCenteredSlider(
            value = state.cacheLimitMB,
            onValueChange = onCacheLimitChange,
            min = 8,
            max = 128,
            step = 8,
            defaultCenter = 64,
            unit = " MB",
            label = "缓存空间上限",
            desc = "超出配额后按 LRU 策略自动淘汰最旧的封面与歌词（中位 64 MB，范围 8~128 MB）"
        )

        // 第四行：关于与版本信息 (统一 116.dp 高度卡片，无检查更新按钮)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .neuCard(cornerRadius = 16.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "关于与版本信息",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )

                    Box(
                        modifier = Modifier
                            .background(Color(0xFFECFDF5), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "车机正式稳定版",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.AccentGreen
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "当前构建版本：${state.appVersion}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.TextPrimary
                        )
                        Text(
                            text = "针对 Android Automotive OS / 长安欧尚座舱深度优化适配",
                            fontSize = 11.sp,
                            color = NeuColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

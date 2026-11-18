package com.car.xiaoouscreen.ui.settings.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.components.NeuSwitch
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.WeatherState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset

/**
 * 模块 3：天气服务设置
 * 开启和风高精度天气 + 仅开启后展开/可编辑和风天气配置 (折叠动画 + 回落机制)
 */
@Composable
fun WeatherPage(
    state: WeatherState,
    onToggleUseCarOutsideTemp: (Boolean) -> Unit = {},
    onToggleEnableQWeather: (Boolean) -> Unit,
    onSaveHost: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onSilentPersistDraft: (String, String) -> Unit = { _, _ -> },
    onTestConnection: () -> Unit,
    onClearCredentials: () -> Unit,
    onRefreshWeather: () -> Unit = {},
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var hostInput by remember(state.apiHost) { mutableStateOf(state.apiHost) }
    var keyInput by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    val latestHostInput by rememberUpdatedState(hostInput)
    val latestKeyInput by rememberUpdatedState(keyInput)
    val latestSilentPersist by rememberUpdatedState(onSilentPersistDraft)
    val canUseQWeather = state.enableQWeather && state.apiKey.isNotBlank()

    // 切页或退出设置时，自动将输入框里未点「应用」的非空改动同步落盘到本地，防止丢失
    DisposableEffect(Unit) {
        onDispose {
            latestSilentPersist(latestHostInput, latestKeyInput)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 3.1 立即更新当前天气操作卡片 (排在第一位，即时更新实时天气)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 16.dp, borderColor = Color(0xFFE2E8F0))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "即时更新实时天气",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )

                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (canUseQWeather) Color(0xFFDCFCE7) else Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (canUseQWeather) "当前源：和风高精度" else "当前源：Open-Meteo",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canUseQWeather) Color(0xFF15803D) else NeuColors.TextSecondary
                            )
                        }
                    }

                    Text(
                        text = "点击立即重新请求天气接口，即时同步当前车辆位置与当前时间点的实况气象与降水预警",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuButton(
                    text = if (state.isTesting) "正在更新天气..." else "立即更新天气",
                    onClick = { if (!state.isTesting) onRefreshWeather() },
                    isPrimary = true,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        // 3.2 优先使用实车车外温度开关卡片 (位于“开启和风高精度天气”上方)
        val hasCarTemp = state.carOutsideTempC != null
        val formattedCarTemp = state.carOutsideTempC?.let {
            String.format(java.util.Locale.US, "%.1f", it).removeSuffix(".0")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(
                    cornerRadius = 18.dp,
                    borderColor = if (state.useCarOutsideTemp) Color(0xFFBFDBFE) else NeuColors.BorderCard
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "优先使用实车车外温度",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )

                        Box(
                            modifier = Modifier
                                .background(
                                    color = when {
                                        !state.useCarOutsideTemp -> Color(0xFFE2E8F0)
                                        hasCarTemp -> Color(0xFFDCFCE7)
                                        else -> Color(0xFFDBEAFE)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    !state.useCarOutsideTemp -> "已关闭 · 使用在线气象站温度"
                                    hasCarTemp -> "已开启 · 实车车外 ${formattedCarTemp}°C"
                                    else -> "已开启 · 等待实车信号（自动回退在线）"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !state.useCarOutsideTemp -> NeuColors.TextSecondary
                                    hasCarTemp -> Color(0xFF15803D)
                                    else -> NeuColors.AccentBlue
                                }
                            )
                        }

                        NeuInfoButton(
                            info = AnnotationInfo(
                                title = "实车车外温度融合说明",
                                content = "开启后，小欧屏主气温将直接读取车辆 CAN 总线车外环境温度传感器（与原车仪表盘实时同步，零网络延迟、地库/山区/隧道更贴近真实体感）；天空天气现象、风向风级与分钟降水仍由在线气象源驱动。未读到有效车辆信号时自动无缝回退在线气温。",
                                recommend = "推荐开启 · 与仪表盘车外温度完全一致"
                            ),
                            onShowInfo = onShowInfo
                        )
                    }

                    Text(
                        text = "直接读取车辆车外温度传感器并与仪表同步；天空现象、风力及降水仍由在线气象源提供，无传感器信号时自动回退在线温度。",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuSwitch(
                    checked = state.useCarOutsideTemp,
                    onCheckedChange = onToggleUseCarOutsideTemp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        // 3.3 开启和风高精度天气主开关卡片 (排在实车车外温度下方)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(
                    cornerRadius = 18.dp,
                    borderColor = if (state.enableQWeather) Color(0xFFBFDBFE) else NeuColors.BorderCard
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "开启和风高精度天气",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.TextPrimary
                        )

                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (state.enableQWeather) Color(0xFFDBEAFE) else Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (state.enableQWeather) "已开启 · 使用和风专属配置" else "已关闭 · 自动回落到 Open-Meteo",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.enableQWeather) NeuColors.AccentBlue else NeuColors.TextSecondary
                            )
                        }

                        NeuInfoButton(
                            info = AnnotationInfo(
                                title = "和风高精度天气与回落机制说明",
                                content = "开启后使用下方填写的专属 API Host 与 API Key 请求和风天气分钟级降水与空气质量数据；关闭此开关时，系统将自动无缝回落到免费的 Open-Meteo 国际天气接口，无需任何密钥。",
                                recommend = "说明：关闭时自动回落到 Open-Meteo"
                            ),
                            onShowInfo = onShowInfo
                        )
                    }

                    Text(
                        text = "开启后展开并可编辑下方「和风天气配置」；关闭时自动回落到 Open-Meteo 气象源。",
                        fontSize = 11.sp,
                        color = NeuColors.TextSecondary
                    )
                }

                NeuSwitch(
                    checked = state.enableQWeather,
                    onCheckedChange = onToggleEnableQWeather,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        // 3.3 动态折叠区标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 16.dp)
                        .background(
                            if (state.enableQWeather) NeuColors.AccentBlue else Color(0xFF94A3B8),
                            RoundedCornerShape(2.dp)
                        )
                )
                Text(
                    text = "└─ 和风天气配置（仅开启后展开 / 可编辑）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
            }

            Text(
                text = if (state.enableQWeather) "已展开 · 可编辑凭据" else "未开启 · 当前已回落至 Open-Meteo",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state.enableQWeather) NeuColors.AccentGreen else NeuColors.AccentAmber
            )
        }

        // 3.3 折叠与展开区域 (使用 AnimatedVisibility)
        AnimatedVisibility(
            visible = !state.enableQWeather,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // 关闭状态下的回落提示卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuInset(cornerRadius = 16.dp)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .neuCard(cornerRadius = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "提示",
                                tint = NeuColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "当前处于 Open-Meteo 自动回落模式",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextPrimary
                            )
                            Text(
                                text = "和风天气配置已收起锁定。如需配置专属 API Host 与 API Key，请先开启上方「开启和风高精度天气」开关。",
                                fontSize = 11.sp,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    NeuButton(
                        text = "开启并展开配置",
                        onClick = { onToggleEnableQWeather(true) },
                        isPrimary = true
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.enableQWeather,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // 开启状态下的和风天气配置面板
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuCard(cornerRadius = 18.dp, borderColor = Color(0xFFBFDBFE))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 自适应输入排布：并排太短时自动换为单行满宽，极大提升车机长文本与密钥输入便利性
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isStacked = maxWidth < 840.dp
                    if (isStacked) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            HostInputSection(
                                hostInput = hostInput,
                                onHostInputChange = { hostInput = it },
                                onSaveHost = onSaveHost,
                                onShowInfo = onShowInfo,
                                modifier = Modifier.fillMaxWidth()
                            )
                            KeyInputSection(
                                keyInput = keyInput,
                                onKeyInputChange = { keyInput = it },
                                onSaveKey = onSaveKey,
                                onShowInfo = onShowInfo,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            HostInputSection(
                                hostInput = hostInput,
                                onHostInputChange = { hostInput = it },
                                onSaveHost = onSaveHost,
                                onShowInfo = onShowInfo,
                                modifier = Modifier.weight(1f)
                            )
                            KeyInputSection(
                                keyInput = keyInput,
                                onKeyInputChange = { keyInput = it },
                                onSaveKey = onSaveKey,
                                onShowInfo = onShowInfo,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 底部状态回显槽 + 清空凭据 + 测试连接
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .neuInset(cornerRadius = 10.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(NeuColors.AccentGreen)
                            )
                            Text(
                                text = state.lastTestResult,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeuButton(
                            text = "清空凭据",
                            onClick = {
                                hostInput = ""
                                keyInput = ""
                                onClearCredentials()
                            },
                            textColor = NeuColors.AccentRose
                        )

                        NeuButton(
                            text = "测试连接",
                            onClick = {
                                onSaveHost(hostInput)
                                onSaveKey(keyInput)
                                onTestConnection()
                            },
                            isPrimary = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostInputSection(
    hostInput: String,
    onHostInputChange: (String) -> Unit,
    onSaveHost: (String) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "专属 API Host",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary
            )
            NeuInfoButton(
                info = AnnotationInfo(
                    title = "和风天气专属 API Host",
                    content = "和风天气开发者控制台分配的独立请求域名（如 devapi.qweather.com 或自定义私有域），无需携带 https:// 前缀。",
                    recommend = "示例：devapi.qweather.com"
                ),
                onShowInfo = onShowInfo
            )
        }
        Text(
            text = "输入和风天气接口域名（支持免费开发版或商业版独立 Host）",
            fontSize = 11.sp,
            color = NeuColors.TextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuInset(cornerRadius = 10.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = hostInput,
                    onValueChange = onHostInputChange,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = NeuColors.TextPrimary
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NeuColors.AccentBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            NeuButton(
                text = "应用",
                onClick = { onSaveHost(hostInput) },
                textColor = NeuColors.AccentBlue
            )
        }
    }
}

@Composable
private fun KeyInputSection(
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "API Key",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary
            )
            NeuInfoButton(
                info = AnnotationInfo(
                    title = "和风天气 API Key 密钥",
                    content = "在和风天气控制台创建的 Web API 凭据 Key，仅加密保存在车机本地存储中。",
                    recommend = "支持点击“测试连接”实时验证 Key 有效性"
                ),
                onShowInfo = onShowInfo
            )
        }
        Text(
            text = "填写用于请求实时天气与分钟级降水预报的密钥凭据",
            fontSize = 11.sp,
            color = NeuColors.TextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .neuInset(cornerRadius = 10.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = keyInput,
                    onValueChange = onKeyInputChange,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = NeuColors.TextPrimary
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NeuColors.AccentBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            NeuButton(
                text = "应用",
                onClick = { onSaveKey(keyInput) },
                textColor = NeuColors.AccentBlue
            )
        }
    }
}

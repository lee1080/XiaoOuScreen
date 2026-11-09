package com.car.xiaoouscreen.ui.settings.pages

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuInfoButton
import com.car.xiaoouscreen.ui.settings.model.AnnotationInfo
import com.car.xiaoouscreen.ui.settings.model.PermissionItem
import com.car.xiaoouscreen.ui.settings.model.WelcomeState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuInset

/**
 * 模块 1：欢迎页 (规范重构版)
 * 顶部欢迎语卡片 + 权限请求指引网格 (支持原生授权弹窗、全部开启后自动折叠与逐一跳转检测)
 */
@Composable
fun WelcomePage(
    state: WelcomeState,
    onGrantAllPermissions: () -> Unit,
    onTogglePermission: (String) -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val grantedCount = state.permissions.count { it.granted }
    val isAllGranted = grantedCount == state.permissions.size

    // 原生运行时危险权限请求启动器 (定位、电话)
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onGrantAllPermissions()
    }

    fun handleRequestPermission(permId: String) {
        when (permId) {
            "perm_location" -> {
                runtimePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            "perm_phone" -> {
                runtimePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_CALL_LOG
                    )
                )
            }
            else -> {
                onTogglePermission(permId)
            }
        }
    }

    fun handleProgressiveCheck() {
        val firstMissing = state.permissions.find { !it.granted }
        if (firstMissing != null) {
            handleRequestPermission(firstMissing.id)
        } else {
            onGrantAllPermissions()
        }
    }

    // 当全部权限开启时，默认折叠权限列表；未全部开启时默认展开
    var isManuallyToggled by remember { mutableStateOf(false) }
    var isCollapsed by remember(isAllGranted) {
        mutableStateOf(if (!isManuallyToggled) isAllGranted else false)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1.1 欢迎语与愿景卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neuCard(cornerRadius = 18.dp, borderColor = Color(0xFFBFDBFE))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶栏：欢迎标题 + 右侧【一键检测并授权】操作按钮 (去除了前往全屏歌词设置按钮)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.greetingTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary
                    )

                    NeuButton(
                        text = if (isAllGranted) "全部权限已就绪 ($grantedCount/${state.permissions.size})"
                        else "一键检测并授权 ($grantedCount/${state.permissions.size})",
                        onClick = { handleProgressiveCheck() },
                        isPrimary = !isAllGranted
                    )
                }

                // 名言引用排版 (科技不应该是高高在上的，科技应该服务每一个人！)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 12.dp)
                        .background(Color(0xFFEFF6FF).copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 4.dp, height = 20.dp)
                                .background(NeuColors.AccentBlue, RoundedCornerShape(2.dp))
                        )
                        Text(
                            text = "“ 科技不应该是高高在上的，科技应该服务每一个人！ ”",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.AccentBlue,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                // 详细说明三大板块 (方案 A 最终确认版)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WelcomeStatementBlock(
                        title = "使用前必读",
                        tagBgColor = Color(0xFFFEE2E2),
                        tagTextColor = Color(0xFFDC2626),
                        content = "本软件专为长安欧尚 Z6 系列车型（配备小欧屏版本）量身打造，且为完全开源免费软件！若您是通过任何付费渠道购买获取的，那您一定是被骗了，请立即申请退款维权，切勿让二手倒卖分子得逞！"
                    )

                    WelcomeStatementBlock(
                        title = "二次开发与开源规范",
                        tagBgColor = Color(0xFFDBEAFE),
                        tagTextColor = Color(0xFF1D4ED8),
                        content = "诚挚欢迎广大车友与极客基于本软件进行二次开发，若需源码可随时联系作者。但使用与二开的前提是必须承诺恪守开源协议，严禁将其重新打包为闭源或收费软件对外售卖。"
                    )

                    WelcomeStatementBlock(
                        title = "致同行开发者与逆向探索者",
                        tagBgColor = Color(0xFFDCFCE7),
                        tagTextColor = Color(0xFF15803D),
                        content = "无论是参考本项目的实现思路，还是出于技术好奇为了绕过授权验证而进行逆向破解，技术探索本身都值得尊重；但由衷恳请各位：请务必将由此诞生的所有衍生软件同样保持完全免费，绝不走向商业牟利。让我们共同呵护风清气正的开源环境，多为车机生态开发真正免费、良心且好用的精品软件！"
                    )
                }
            }
        }

        // 1.2 权限请求指引分组标题栏 (提供折叠/展开按钮)
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
                        .background(NeuColors.AccentBlue, RoundedCornerShape(2.dp))
                )
                Text(
                    text = "权限请求指引",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )

                if (isAllGranted) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFDCFCE7), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "核心权限均已开启",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF15803D)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isCollapsed) "展开查看权限详情 ▼" else "收起权限指引 ▲",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.AccentBlue,
                    modifier = Modifier
                        .clickable {
                            isManuallyToggled = true
                            isCollapsed = !isCollapsed
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 权限卡片列表区域 (动画折叠展开)
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 每两项成一行
                val chunked = state.permissions.chunked(2)
                chunked.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { perm ->
                            PermissionCard(
                                perm = perm,
                                onToggle = { handleRequestPermission(perm.id) },
                                onShowInfo = onShowInfo,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 底部预留悬浮按钮避让空间
        Spacer(modifier = Modifier.height(72.dp))
    }
}

@Composable
private fun WelcomeStatementBlock(
    title: String,
    tagBgColor: Color,
    tagTextColor: Color,
    content: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .neuCard(cornerRadius = 14.dp, borderColor = Color(0xFFE2E8F0))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(tagBgColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tagTextColor
                    )
                }
            }
            Text(
                text = content,
                fontSize = 11.sp,
                color = NeuColors.TextSecondary,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun PermissionCard(
    perm: PermissionItem,
    onToggle: () -> Unit,
    onShowInfo: (AnnotationInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp)
            .neuCard(
                cornerRadius = 16.dp,
                borderColor = if (perm.granted) NeuColors.BorderCard else Color(0xFFFCD34D)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶栏：权限名 + 状态徽章 + ? 详情按钮，右侧为状态胶囊与操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = perm.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (perm.granted) Color(0xFFDCFCE7) else Color(0xFFCFFAFE),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = perm.badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (perm.granted) Color(0xFF166534) else Color(0xFF155E75),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    NeuInfoButton(
                        info = AnnotationInfo(
                            title = perm.infoTitle,
                            content = perm.infoContent,
                            recommend = perm.infoRecommend
                        ),
                        onShowInfo = onShowInfo
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 状态胶囊
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (perm.granted) Color(0xFFD1FAE5) else Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (perm.granted) "已开启" else "未开启",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (perm.granted) Color(0xFF047857) else Color(0xFFB45309)
                        )
                    }

                    NeuButton(
                        text = if (perm.granted) "重检" else "去开启",
                        onClick = onToggle,
                        isPrimary = !perm.granted,
                        textColor = if (perm.granted) NeuColors.TextSecondary else null
                    )
                }
            }

            // 底栏：清晰说明文本（行高 16sp，最多 2 行，超出省略号）
            Text(
                text = perm.desc,
                fontSize = 11.sp,
                color = NeuColors.TextSecondary,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

package com.car.xiaoouscreen.ui.settings.dialogs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.R
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import java.io.File

/**
 * 联系作者与交流共创弹窗
 * 展示作者微信二维码、抖音关注二维码与富哥赞助倡议
 */
@Composable
fun ContactAuthorDialog(
    onDismiss: () -> Unit
) {
    // 优先加载本地开发路径图片，若不可用则回退至打包的 Drawable 资源
    val wechatBitmap = remember {
        try {
            val f = File("/Users/liyafei/Desktop/liyafei/车机研究/微信.png")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }

    val douyinBitmap = remember {
        try {
            val f = File("/Users/liyafei/Desktop/liyafei/车机研究/抖音.png")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 680.dp)
                    .neuFloatingShell(cornerRadius = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 顶部标题栏 + 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .neuButton(cornerRadius = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "联系作者",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "联系作者 · 交流共创",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Text(
                                    text = "开源不易，期待与广大欧尚车友及开发者携手共建",
                                    fontSize = 11.sp,
                                    color = NeuColors.TextSecondary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .neuButton(cornerRadius = 10.dp)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = NeuColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 2. 双二维码卡片展示区 (并排呈现)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 左侧：微信二维码卡片
                        QrCard(
                            modifier = Modifier.weight(1f),
                            title = "微信扫码联系作者",
                            subtitle = "获取源码 / Bug反馈 / 车友交流 / 赞助作者",
                            badgeText = "微信交流",
                            badgeBg = Color(0xFFDCFCE7),
                            badgeColor = Color(0xFF15803D)
                        ) {
                            if (wechatBitmap != null) {
                                Image(
                                    bitmap = wechatBitmap.asImageBitmap(),
                                    contentDescription = "作者微信二维码",
                                    modifier = Modifier
                                        .size(148.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.qrcode_wechat),
                                    contentDescription = "作者微信二维码",
                                    modifier = Modifier
                                        .size(148.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }

                        // 右侧：抖音二维码卡片
                        QrCard(
                            modifier = Modifier.weight(1f),
                            title = "抖音扫码关注",
                            subtitle = "关注车机玩法与更新动态",
                            badgeText = "抖音关注",
                            badgeBg = Color(0xFFFEE2E2),
                            badgeColor = Color(0xFFDC2626)
                        ) {
                            if (douyinBitmap != null) {
                                Image(
                                    bitmap = douyinBitmap.asImageBitmap(),
                                    contentDescription = "抖音二维码",
                                    modifier = Modifier
                                        .size(148.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.qrcode_douyin),
                                    contentDescription = "抖音二维码",
                                    modifier = Modifier
                                        .size(148.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }

                    // 3. 底部富哥赞助文案 (选项 3)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neuInset(cornerRadius = 14.dp)
                            .background(Color(0xFFFFF1F2).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "如果这款小工具让您的座舱体验变得更好，欢迎赞助支持后续维护！",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFBE123C),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeBg: Color,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .neuCard(cornerRadius = 16.dp, borderColor = Color(0xFFE2E8F0))
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }

            // 二维码区域
            Box(
                modifier = Modifier
                    .size(154.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                content()
            }

            // 主标题
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary,
                textAlign = TextAlign.Center
            )

            // 副标题说明
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = NeuColors.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

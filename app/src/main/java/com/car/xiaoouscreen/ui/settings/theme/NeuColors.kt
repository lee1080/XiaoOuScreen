package com.car.xiaoouscreen.ui.settings.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 车规级浅色拟物风全局色板 (Light Neumorphism Color Tokens)
 * 严格按照车规级与 HTML 原型 1:1 定义
 */
object NeuColors {
    // 全局背景渐变色
    val BgStart = Color(0xFFDCE4EC)
    val BgMiddle = Color(0xFFE8EEF5)
    val BgEnd = Color(0xFFDDE5EE)
    val BgGradient = Brush.linearGradient(listOf(BgStart, BgMiddle, BgEnd))

    // 悬浮外壳与主面板表面渐变
    val ShellStart = Color(0xFFEEF3F8)
    val ShellEnd = Color(0xFFE3EAF2)
    val ShellGradient = Brush.linearGradient(listOf(ShellStart, ShellEnd))

    // 悬浮侧边栏渐变
    val SidebarStart = Color(0xFFF1F5F9)
    val SidebarEnd = Color(0xFFE5ECF3)
    val SidebarGradient = Brush.linearGradient(listOf(SidebarStart, SidebarEnd))

    // 凸起卡片渐变
    val CardStart = Color(0xFFF0F5FA)
    val CardEnd = Color(0xFFE6EDF4)
    val CardGradient = Brush.linearGradient(listOf(CardStart, CardEnd))

    // 拟物凸起按钮渐变
    val ButtonStart = Color(0xFFF4F8FB)
    val ButtonEnd = Color(0xFFE2EAF2)
    val ButtonGradient = Brush.linearGradient(listOf(ButtonStart, ButtonEnd))

    // 激活状态 / 菜单选中态渐变
    val ActiveMenuStart = Color(0xFFE2E9F1)
    val ActiveMenuEnd = Color(0xFFEDF2F8)
    val ActiveMenuGradient = Brush.linearGradient(listOf(ActiveMenuStart, ActiveMenuEnd))

    // 内凹槽底色 (Inset Trough)
    val InsetBg = Color(0xFFE4EBF2)
    val InsetDarkShadow = Color(0xFFC4CED9)
    val InsetLightHighlight = Color(0xFFFFFFFF)

    // 文本层级
    val TextPrimary = Color(0xFF2C3A47)
    val TextSecondary = Color(0xFF637381)
    val TextMuted = Color(0xFF8E9BAE)

    // 强调色 (Accent)
    val AccentBlue = Color(0xFF2563EB)
    val BlueGradientStart = Color(0xFF3B82F6)
    val BlueGradientEnd = Color(0xFF2563EB)
    val PrimaryBlueGradient = Brush.linearGradient(listOf(BlueGradientStart, BlueGradientEnd))

    val AccentCyan = Color(0xFF0891B2)
    val AccentGreen = Color(0xFF10B981)
    val AccentRose = Color(0xFFE11D48)
    val AccentAmber = Color(0xFFD97706)

    // 边框色
    val BorderShell = Color(0xFFFFFFFF).copy(alpha = 0.70f)
    val BorderSidebar = Color(0xFFFFFFFF).copy(alpha = 0.85f)
    val BorderCard = Color(0xFFFFFFFF).copy(alpha = 0.65f)
    val BorderInset = Color(0xFFFFFFFF).copy(alpha = 0.50f)
    val BorderButton = Color(0xFFFFFFFF).copy(alpha = 0.75f)

    // 外投影色
    val ShadowDark = Color(0xFFC7D1DC)
    val ShadowDarkFloating = Color(0xFFA6B4C4)
    val ShadowLight = Color(0xFFFFFFFF)
}

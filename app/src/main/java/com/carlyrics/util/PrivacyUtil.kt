package com.carlyrics.util

import java.util.Locale

/**
 * 隐私与敏感数据脱敏工具类
 * 严格落实《个人信息保护法》(PIPL) 与车载座舱隐私安全规范
 */
object PrivacyUtil {

    /**
     * 手机号码脱敏 (保留前3后4，中间4位掩码)
     * 例如："13800009527" -> "138****9527"
     */
    fun maskPhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val trimmed = phone.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length >= 7) {
            val prefix = digits.take(3)
            val suffix = digits.takeLast(4)
            "$prefix****$suffix"
        } else if (trimmed.length > 2) {
            trimmed.first() + "***" + trimmed.last()
        } else {
            "***"
        }
    }

    /**
     * 联系人姓名脱敏 (仅保留首字符或姓氏)
     * 例如："张三" -> "张*"，"诸葛孔明" -> "诸***"
     */
    fun maskName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val trimmed = name.trim()
        return if (trimmed.length <= 1) {
            "*"
        } else {
            trimmed.first() + "*".repeat(trimmed.length - 1)
        }
    }

    /**
     * GPS 地理坐标脱敏 (模糊化处理，抹除精确小数点)
     * 例如：104.182394 -> "104.1*°"
     */
    fun maskCoordinate(coord: Double): String {
        return if (coord == 0.0) {
            "0.0"
        } else {
            String.format(Locale.US, "%.1f*°", coord)
        }
    }

    /**
     * API Key 脱敏 (保留前4位)
     */
    fun maskApiKey(key: String?): String {
        if (key.isNullOrBlank()) return "(未配置)"
        val trimmed = key.trim()
        return if (trimmed.length <= 6) {
            "******"
        } else {
            trimmed.take(4) + "..." + trimmed.takeLast(2)
        }
    }
}

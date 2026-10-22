package com.mediakit.app

import java.util.Locale

/** 文件名清洗与扩展名处理：纯函数，可单测。 */
object Names {

    /** 各家文件系统都不接受、或者会把人坑到的字符 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")

    /** 去掉非法字符；超长时截断但保留扩展名（"File name too long" 就是没截断导致的）。 */
    fun sanitize(name: String, maxLen: Int = 100): String {
        val cleaned = name.replace(ILLEGAL, "_").trim().trimEnd('.', ' ').ifEmpty { "未命名" }
        if (cleaned.length <= maxLen) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
        val base = if (ext.isEmpty()) cleaned else cleaned.substring(0, dot)
        return base.take(maxLen - ext.length) + ext
    }

    /** 小写扩展名；没有扩展名返回空串 */
    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    /** 干净的主文件名（不含扩展名） */
    fun base(name: String, fallback: String = "音频"): String {
        val n = sanitize(name).trimStart('.')
        val dot = n.lastIndexOf('.')
        val b = if (dot > 0) n.substring(0, dot) else n
        return b.trim().ifEmpty { fallback }
    }
}

package com.mediakit.app

import java.util.Locale

/**
 * 纯格式化 / 解析工具：不碰任何 Android API，可以直接在 JVM 上单测。
 *
 * 时间小数字符串一律用 Locale.US —— 这是拿去当 ffmpeg 参数的，
 * 德语、法语等地区的默认 Locale 会把小数点写成逗号（"30,000"），ffmpeg 直接报错。
 */
object Fmt {

    /** 解析 90 / 1:30 / 00:01:30.5；不合法返回 null。 */
    fun parseTime(raw: String): Double? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.matches(Regex("^\\d+(\\.\\d+)?$"))) return t.toDoubleOrNull()
        val parts = t.split(':')
        if (parts.size < 2 || parts.size > 3) return null
        var total = 0.0
        for (i in parts.indices) {
            val v = parts[i].toDoubleOrNull() ?: return null
            if (v < 0.0) return null
            // 除第一段（时/分可以大于 60）外，其余都必须 < 60；非最后一段还必须是整数。
            // 所以 "1:70"、"0:90"、"1:2:99" 都判错，不替用户猜。
            if (i > 0 && v >= 60.0) return null
            if (i < parts.size - 1 && v != v.toLong().toDouble()) return null
            total = total * 60 + v
        }
        return total
    }

    /** 秒 -> ffmpeg 参数（必须带 Locale.US） */
    fun sec(seconds: Double): String = String.format(Locale.US, "%.3f", seconds)

    // 毫秒版本故意不叫 sec(Long)：sec(30) 这种字面量会静默选到 Long 重载，
    // 30 秒变 0.030 秒，不报错、只是剪出来的片子不对。名字分开来就不会撞。
    fun secMs(ms: Long): String = sec(ms / 1000.0)

    /** 0:12.3 */
    fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val totalSec = t / 1000
        return String.format(Locale.US, "%d:%02d.%d", totalSec / 60, totalSec % 60, (t % 1000) / 100)
    }

    /** 12.3秒 / 1分05秒 */
    fun dur(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        if (t < 60_000L) return String.format(Locale.US, "%.1f秒", t / 1000.0)
        val totalSec = t / 1000
        return String.format(Locale.US, "%d分%02d秒", totalSec / 60, totalSec % 60)
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

package com.mediakit.app

/**
 * ffmpeg 参数拼装：纯函数，不依赖任何 Android API，可以在 JVM 上单测。
 *
 * 踩过的坑都固化在这里，别再凭感觉改：
 *  1) copy 模式必须沿用**源容器**，并且只映射首条视频/音频。
 *     `-map 0` 会把字幕、数据轨一起带过去，目标容器装不下就整条命令失败
 *     （实测：mkv+srt、mkv+opus、wav 三种都会挂，详见 tools/smoke.sh）。
 *  2) 别拿 `.m4a` 当兜底容器 —— 它走的是 ipod muxer，连 mp3 都不收。
 *  3) 时间参数一律 [Fmt.sec]（Locale.US），否则逗号小数点地区会产出 "30,000"。
 */
object FfmpegArgs {

    /** 视频容器 */
    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "ts", "m2ts", "3gp", "flv", "wmv"
    )

    /** 音频容器 */
    private val AUDIO_EXT = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mka", "amr", "wma", "aiff", "ape"
    )

    private val MIME = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "ts" to "video/mp2t", "m2ts" to "video/mp2t", "3gp" to "video/3gpp",
        "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "opus" to "audio/opus", "mka" to "audio/x-matroska"
    )

    fun isVideoExt(ext: String): Boolean = ext in VIDEO_EXT
    fun isAudioExt(ext: String): Boolean = ext in AUDIO_EXT

    fun mimeFor(ext: String): String = MIME[ext] ?: "application/octet-stream"

    /**
     * 快速剪的输出容器：源容器一定认得自己里面的轨道，所以直接沿用；
     * 未知/不认识的扩展名退回 mp4（h264+aac 最保险）。
     */
    fun clipContainer(srcName: String, precise: Boolean): String {
        if (precise) return "mp4"
        val e = Names.ext(srcName)
        return if (e in VIDEO_EXT || e in AUDIO_EXT) e else "mp4"
    }

    /** 提取音频：先试无损拷贝，失败（源编码和容器不兼容）再转码 */
    fun extractCopy(srcPath: String, outPath: String): List<String> =
        listOf("-y", "-i", srcPath, "-vn", "-c:a", "copy", outPath)

    /** 提取音频：按目标格式转码。fmt 用 m4a/mp3/flac/wav 之一 */
    fun extractEncode(srcPath: String, outPath: String, fmt: String): List<String> {
        val a = mutableListOf("-y", "-i", srcPath, "-vn")
        a += when (fmt) {
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
            "flac" -> listOf("-c:a", "flac")
            "wav" -> listOf("-c:a", "pcm_s16le")
            else -> listOf("-c:a", "aac", "-b:a", "192k")
        }
        a += outPath
        return a
    }

    /**
     * 剪辑。
     * @param durSec 输出时长；null 表示"到结尾"
     */
    fun clip(
        srcPath: String,
        outPath: String,
        startSec: Double,
        durSec: Double?,
        precise: Boolean
    ): List<String> {
        val a = mutableListOf("-y")
        if (startSec > 0.0) {
            // -ss 放在 -i 前面是输入定位，比输出定位快得多
            a += "-ss"
            a += Fmt.sec(startSec)
        }
        a += "-i"
        a += srcPath
        if (durSec != null && durSec > 0.0) {
            a += "-t"
            a += Fmt.sec(durSec)
        }
        if (precise) {
            a += listOf(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart"
            )
        } else {
            // 只搬首条视频 + 首条音频：字幕/数据轨装不进别的容器，-map 0 会让整条命令失败
            a += listOf("-c", "copy", "-map", "0:v:0?", "-map", "0:a:0?")
        }
        a += outPath
        return a
    }

    /** 解码成 8kHz 单声道 PCM，用来算波形包络（快且省内存） */
    fun decodeToPcm(srcPath: String, rawPath: String): List<String> =
        listOf("-y", "-i", srcPath, "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", rawPath)

    /** 输出音频编码参数（按导出容器决定） */
    fun audioCodec(outExt: String): List<String> = when (outExt) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
        "flac" -> listOf("-c:a", "flac")
        "wav" -> listOf("-c:a", "pcm_s16le")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "128k")
        "ogg" -> listOf("-c:a", "libvorbis", "-q:a", "5")
        else -> listOf("-c:a", "aac", "-b:a", "192k")
    }

    /** 只保留一段：能省掉 filter_complex，也就不用整段解码 */
    fun exportOne(
        srcPath: String,
        outPath: String,
        startMs: Long,
        endMs: Long,
        totalMs: Long,
        outExt: String
    ): List<String> {
        val a = mutableListOf("-y")
        if (startMs > 0L) {
            a += "-ss"
            a += Fmt.secMs(startMs)
        }
        a += "-i"
        a += srcPath
        if (totalMs > 0L && endMs < totalMs) {
            a += "-t"
            a += Fmt.secMs(endMs - startMs)
        }
        a += "-vn"
        a += audioCodec(outExt)
        a += outPath
        return a
    }

    /** 多段拼接：atrim 切段 + concat 拼起来 */
    fun concatFilter(kept: List<LongRange>): String {
        val sb = StringBuilder()
        kept.forEachIndexed { i, r ->
            sb.append("[0:a]atrim=start=").append(Fmt.secMs(r.first))
                .append(":end=").append(Fmt.secMs(r.last))
                .append(",asetpts=PTS-STARTPTS[a").append(i).append("];")
        }
        kept.indices.forEach { sb.append("[a").append(it).append("]") }
        sb.append("concat=n=").append(kept.size).append(":v=0:a=1[out]")
        return sb.toString()
    }

    fun exportConcat(
        srcPath: String,
        outPath: String,
        kept: List<LongRange>,
        outExt: String
    ): List<String> {
        val a = mutableListOf("-y", "-i", srcPath, "-filter_complex", concatFilter(kept),
            "-map", "[out]", "-vn")
        a += audioCodec(outExt)
        a += outPath
        return a
    }
}

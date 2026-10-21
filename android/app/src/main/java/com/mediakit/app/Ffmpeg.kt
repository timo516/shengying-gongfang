package com.mediakit.app

import android.content.Context
import java.io.File

/**
 * ffmpeg 执行封装。
 *
 * 关键点：ffmpeg 二进制被打包在 jniLibs/arm64-v8a/libffmpeg.so。
 * Android 从 API 29 起禁止执行 data 目录里的文件（W^X），但 nativeLibraryDir 里的是例外，
 * 所以必须走 /data/app/.../lib/arm64/libffmpeg.so 这个路径来执行。
 * 名字必须叫 lib*.so 才能被当 native 库分发（Gradle 只认这个命名）。
 */
object Ffmpeg {

    private var cached: File? = null

    fun bin(ctx: Context): File {
        cached?.let { return it }
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        cached = f
        return f
    }

    fun available(ctx: Context): Boolean {
        val f = bin(ctx)
        return f.exists() && f.canExecute()
    }

    fun versionText(ctx: Context): String {
        return try {
            val p = ProcessBuilder(bin(ctx).absolutePath, "-version").start()
            val line = p.inputStream.bufferedReader().readLine() ?: ""
            p.waitFor()
            line
        } catch (e: Throwable) {
            "无法启动 ffmpeg: ${e.message}"
        }
    }

    /**
     * 执行 ffmpeg。
     * @param totalUs 总时长（微秒），用于算进度；<=0 表示进度只报「处理中」
     * @param onProgress 0f..1f
     * @return null 表示成功，否则返回错误信息
     */
    fun run(
        ctx: Context,
        args: List<String>,
        totalUs: Long,
        onProgress: (Float) -> Unit
    ): String? {
        val cmd = ArrayList<String>()
        cmd.add(bin(ctx).absolutePath)
        cmd.addAll(args)
        // 放最后也能生效：ffmpeg 的全局选项不限位置
        cmd.addAll(listOf("-progress", "pipe:1", "-nostats", "-loglevel", "error", "-hide_banner"))

        val pb = ProcessBuilder(cmd)
        pb.directory(ctx.cacheDir)
        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            return "启动失败: ${e.message}"
        }

        // 看门狗：ffmpeg 万一卡死（坏文件、编码器异常），不能让 busy 永远为 true
        val timeLimitMs =
            if (totalUs > 0) (totalUs / 1000L * 4L + 120_000L).coerceIn(180_000L, 1_800_000L)
            else 300_000L
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        var finished = false
        val watchdog = Thread {
            val start = System.currentTimeMillis()
            while (true) {
                try {
                    Thread.sleep(500)
                } catch (_: Throwable) {
                    return@Thread
                }
                if (finished) return@Thread
                if (System.currentTimeMillis() - start > timeLimitMs) {
                    timedOut.set(true)
                    try {
                        proc.destroyForcibly()
                    } catch (_: Throwable) {
                    }
                    return@Thread
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        val errBuffer = StringBuilder()
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine {
                    if (errBuffer.length < 8000) {
                        errBuffer.append(it).append('\n')
                    }
                }
            } catch (_: Throwable) {
            }
        }
        errThread.start()

        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@forEachLine
                val key = line.substring(0, i)
                val value = line.substring(i + 1)
                when (key) {
                    "out_time_us" -> {
                        val v = value.toLongOrNull()
                        if (v != null && v >= 0 && totalUs > 0) {
                            onProgress((v.toFloat() / totalUs).coerceIn(0f, 1f))
                        }
                    }
                    "out_time_ms" -> {
                        // 某些老版本只给 ms（注意：ffmpeg 的这个字段其实是微秒，历史 bug）
                        val v = value.toLongOrNull()
                        if (v != null && v >= 0 && totalUs > 0) {
                            onProgress((v.toFloat() / totalUs).coerceIn(0f, 1f))
                        }
                    }
                    "progress" -> if (value.trim() == "end") onProgress(1f)
                }
            }
        } catch (_: Throwable) {
        }

        val code = try {
            proc.waitFor()
        } catch (e: Throwable) {
            proc.destroy()
            -1
        }
        finished = true
        errThread.join(1000)

        if (timedOut.get()) {
            return "处理超时（超过 ${timeLimitMs / 60000} 分钟），已强制停止。文件可能损坏或太大"
        }
        return if (code == 0) null else {
            val tail = errBuffer.toString().trim().lines().takeLast(6).joinToString("\n")
            if (tail.isEmpty()) "ffmpeg 退出码 $code" else tail
        }
    }
}

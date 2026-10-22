package com.mediakit.app

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ffmpeg 执行封装。
 *
 * 关键点：ffmpeg 二进制被打包在 jniLibs/arm64-v8a/libffmpeg.so。
 * Android 从 API 29 起禁止执行 data 目录里的文件（W^X），但 nativeLibraryDir 里的是例外，
 * 所以必须走 /data/app/.../lib/arm64/libffmpeg.so 这个路径来执行。
 * 名字必须叫 lib*.so 才能被当 native 库分发（Gradle 只认这个命名）。
 */
object Ffmpeg {

    @Volatile
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

    /**
     * 执行 ffmpeg。
     * @param totalUs 总时长（微秒），用于算进度；<=0 表示进度只报「处理中」
     * @param onProgress 0f..1f，在读取 ffmpeg 输出的线程上回调 —— 别在里面直接碰 View
     * @return null 表示成功，否则返回错误信息
     */
    fun run(
        ctx: Context,
        args: List<String>,
        totalUs: Long,
        onProgress: (Float) -> Unit
    ): String? {
        val cmd = ArrayList<String>(args.size + 8)
        cmd.add(bin(ctx).absolutePath)
        cmd.addAll(args)
        // 全局选项，放最后也生效（ffmpeg 3.3.2 / 6.x 实测都行）
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
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val watchdog = Thread {
            val start = System.currentTimeMillis()
            while (!finished.get()) {
                try {
                    Thread.sleep(500)
                } catch (_: Throwable) {
                    return@Thread
                }
                if (System.currentTimeMillis() - start > timeLimitMs) {
                    timedOut.set(true)
                    try {
                        // destroyForcibly() 是 API 26 才加的；minSdk 是 24，Android 7.x 上直接调
                        // 会抛 NoSuchMethodError，正好把看门狗自己搞崩
                        if (android.os.Build.VERSION.SDK_INT >= 26) proc.destroyForcibly()
                        else proc.destroy()
                    } catch (_: Throwable) {
                    }
                    return@Thread
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        // stderr 必须有人读：管道写满后 ffmpeg 会阻塞在写错误上，整个任务就假死了
        val errLines = Collections.synchronizedList(ArrayList<String>(256))
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine {
                    if (errLines.size < 200) errLines.add(it)
                }
            } catch (_: Throwable) {
            }
        }
        errThread.isDaemon = true
        errThread.start()

        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@forEachLine
                val key = line.substring(0, i)
                val value = line.substring(i + 1)
                when (key) {
                    // 注意：ffmpeg 的 out_time_ms 实际也是微秒（历史 bug），两者都按微秒算
                    "out_time_us", "out_time_ms" -> {
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
        } catch (_: Throwable) {
            proc.destroy()
            -1
        }
        finished.set(true)
        try {
            errThread.join(1000)
        } catch (_: Throwable) {
        }

        if (timedOut.get()) {
            return "处理超时（超过 ${timeLimitMs / 60000} 分钟），已强制停止。文件可能损坏或太大"
        }
        return if (code == 0) {
            null
        } else {
            val tail = synchronized(errLines) { errLines.takeLast(6) }
            val text = tail.joinToString("\n").trim()
            if (text.isEmpty()) "ffmpeg 退出码 $code" else text
        }
    }
}

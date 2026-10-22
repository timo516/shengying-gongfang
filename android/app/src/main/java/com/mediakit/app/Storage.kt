package com.mediakit.app

import android.content.Context
import android.os.StatFs
import java.io.File

/** 磁盘相关：空间预检、临时工作目录。 */
object Storage {

    /** 留一点余量，别把盘刚好写满（写满后系统级异常很难看） */
    private const val HEADROOM = 50L * 1024 * 1024

    /**
     * 写之前先看空间。源文件大小拿不到（返回 0）时跳过检查。
     * @throws IllegalStateException 空间不够，消息可以直接给用户看
     */
    fun requireFree(dir: File, needBytes: Long) {
        if (needBytes <= 0L) return
        val free = try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Throwable) {
            return
        }
        if (free < needBytes + HEADROOM) {
            throw IllegalStateException(
                "空间不够：这个文件约 ${Fmt.humanSize(needBytes)}，可用只有 ${Fmt.humanSize(free)}。清点空间再试。"
            )
        }
    }

    /**
     * 剪辑器的临时工作目录。
     * 用 cacheDir 而不是 filesDir：filesDir 系统永远不清，用户也看不见，
     * 导入一个大视频就能把内部存储塞满，而且只能靠卸载 App 清掉。
     */
    fun workDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "audio_work")
        if (!d.exists() && !d.mkdirs()) throw IllegalStateException("无法创建临时目录")
        return d
    }

    fun clearWorkDir(ctx: Context) {
        try {
            File(ctx.cacheDir, "audio_work").deleteRecursively()
        } catch (_: Throwable) {
        }
    }
}

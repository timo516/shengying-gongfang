package com.mediakit.app

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast

/**
 * 长任务保障。
 *
 * 1.0.3 只有「提示 + 屏幕常亮」，代价是用户切后台后进程随时可能被当缓存回收，
 * 跑了几分钟的活回来只剩半截文件。1.1.0 起改成：
 *   1) 前台服务（[WorkService]）—— 切后台/锁屏也不回收；
 *   2) FLAG_KEEP_SCREEN_ON —— 屏幕不会自己熄灭（熄屏是后台冻结最常见的起点）；
 *   3) 开跑前一条明确提示。
 */
object LongTask {

    /** 预计处理时长 ≥ 60 秒算长任务 */
    const val THRESHOLD_MS = 60_000L

    /** 素材 ≥ 150MB 也按长任务处理（复制、解码本身就要一会儿） */
    const val THRESHOLD_BYTES = 150L * 1024 * 1024

    fun isLong(ms: Long): Boolean = ms >= THRESHOLD_MS

    fun isLongBytes(bytes: Long): Boolean = bytes >= THRESHOLD_BYTES

    fun start(activity: Activity, title: String = "处理中") {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
        WorkService.start(activity.applicationContext, title)
        try {
            Toast.makeText(
                activity,
                "⏳ 长任务：已开后台保护，切走也不会被系统中断（别手动划掉本应用）",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
    }

    /** 任务结束（成功或失败都调），撤掉常亮和前台服务 */
    fun stop(activity: Activity) {
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
        WorkService.stop(activity.applicationContext)
    }
}

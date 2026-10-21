package com.mediakit.app

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast

/**
 * 长任务提示（折中方案）。
 *
 * 没有引入前台服务：那需要 FOREGROUND_SERVICE 权限 + 常驻通知 + targetSdk 34 的
 * 前台服务类型声明，对一个「本地小工具」来说太重。
 * 现在的做法是：
 *  1) 任务开跑前弹一条明确提示：保持本页在前台 / 可分屏或开小窗 / 别切后台；
 *  2) 任务期间加 FLAG_KEEP_SCREEN_ON，屏幕不会自己熄灭（熄屏是后台冻结最常见的起点）；
 *  3) 任务结束立刻撤掉标记。
 *
 * 剩下的风险（用户手动切走、系统低内存回收）靠上面的提示让用户自己规避。
 */
object LongTask {

    /** 预计处理时长 ≥ 60 秒算长任务 */
    const val THRESHOLD_MS = 60_000L

    /** 素材 ≥ 150MB 也按长任务处理（复制、解码本身就要一会儿） */
    const val THRESHOLD_BYTES = 150L * 1024 * 1024

    fun isLong(ms: Long): Boolean = ms >= THRESHOLD_MS

    fun isLongBytes(bytes: Long): Boolean = bytes >= THRESHOLD_BYTES

    /** 长任务开始：屏幕常亮 + 提示别切后台 */
    fun start(activity: Activity) {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
        try {
            Toast.makeText(
                activity,
                "⏳ 长任务：请保持本页在前台（可分屏或开小窗），别切到后台，否则系统可能中断处理",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) {
        }
    }

    /** 任务结束（成功或失败都调用），撤销屏幕常亮 */
    fun stop(activity: Activity) {
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
    }
}

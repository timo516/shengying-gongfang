package com.mediakit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 长任务期间的前台服务。
 *
 * 只解决一件事：用户切到后台/锁屏时，进程别被系统当缓存进程回收掉。
 * 不做这件事的代价是——跑了四分钟的精确剪，回来发现输出只剩半截。
 *
 * 注意：Android 13+ 没给 POST_NOTIFICATIONS 的话这条通知可能不显示，
 * 但服务本身照跑（系统会在"正在运行的应用"里列出来），所以故意不申请那个权限。
 */
class WorkService : Service() {

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIF_ID = 1
        const val EXTRA_TITLE = "title"

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, WorkService::class.java).putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
                // 后台启动受限（API 31+）时会抛异常；降级成"只有界面提示"，别让任务起不来
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, WorkService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "处理中"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "处理任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("处理中，请保持本页在前台")
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }
}

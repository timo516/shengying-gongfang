package com.mediakit.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/**
 * 存储权限兼容层。
 *
 * Android 10+ 保存走 MediaStore，不需要任何权限；
 * 但 Android 7/8/9（API 24–28）写公共 Download 必须运行时授予 WRITE_EXTERNAL_STORAGE，
 * 否则 Output.publishLegacy 会直接抛 SecurityException。
 */
object Perm {
    private const val REQ_WRITE = 2001

    fun ensureLegacyStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 29) return
        try {
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            activity.requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_WRITE
            )
        } catch (_: Throwable) {
        }
    }

    fun hasLegacyStorage(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return true
        return try {
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }
}

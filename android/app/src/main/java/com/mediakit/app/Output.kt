package com.mediakit.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/**
 * 结果统一落到 手机存储/Download/ 根目录，不套任何专属子文件夹。
 *
 * Android 10+ 走 MediaStore 写公共 Download 目录，不需要特殊权限；
 * Android 9 及以下直接写 Download 目录（WRITE_EXTERNAL_STORAGE，manifest 里已限 maxSdkVersion=28）。
 */
object Output {

    /** 最近一次保存出来的位置，用来给界面弹「查看」引导 */
    @Volatile
    var lastSaved: String? = null
        private set

    /** 取走最近一次保存的位置，取完即清空，避免下次误弹旧路径 */
    fun takeLastSaved(): String? {
        val v = lastSaved
        lastSaved = null
        return v
    }

    fun publish(ctx: Context, src: File, displayName: String, mime: String): String {
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(ctx, src, displayName, mime)
        } else {
            publishLegacy(ctx, src, displayName, mime)
        }
        lastSaved = saved
        return saved
    }

    /** Android 10+：MediaStore 写公共 Download，免权限 */
    private fun publishScoped(ctx: Context, src: File, displayName: String, mime: String): String {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("创建文件失败（没有写入权限？）")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("打开输出流失败")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
        // MediaStore 遇到重名会自动改名（xxx (1).m4a），以它回的为准，
        // 否则界面上给用户看的是一个不存在的路径
        val real = queryDisplayName(ctx, uri) ?: displayName
        return "Download/$real"
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    /** Android 9 及以下：直接写公共 Download 目录 */
    @Suppress("DEPRECATION")
    private fun publishLegacy(ctx: Context, src: File, displayName: String, mime: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${dir.absolutePath}")
        }
        val dst = uniqueFile(dir, displayName)
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        if (dst.length() == 0L) {
            dst.delete()
            throw IllegalStateException("写出来的文件是空的")
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
        return dst.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    // ---------------- 保存后的目录引导 ----------------

    /** 保存成功后的引导：带一个「查看」按钮，点了直接进文件管理器的 Download 目录 */
    fun promptSaved(activity: Activity, savedPath: String) {
        // 任务跑完时页面可能已经被用户按返回关掉了，这时候 show() 就是 BadTokenException
        if (activity.isFinishing || activity.isDestroyed) return
        val name = savedPath.substringAfterLast('/')
        AlertDialog.Builder(activity)
            .setTitle("✅ 已保存")
            .setMessage("$name\n\n位置：$savedPath")
            .setPositiveButton("查看") { _, _ -> viewInFileManager(activity, savedPath) }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 「查看」按钮的真正入口。
     *
     * 老版本把 openFolder() 直接挂在按钮上，失败时静默返回 false，
     * 界面上表现就是「按钮点不动」。现在失败一定有反馈：路径进剪贴板 + Toast。
     */
    fun viewInFileManager(activity: Activity, savedPath: String) {
        val ok = try {
            openFolder(activity, savedPath)
        } catch (t: Throwable) {
            false
        }
        if (ok) return
        copyPath(activity, savedPath)
        toast(activity, "没找到能打开的文件管理器；路径已复制到剪贴板")
    }

    /**
     * 打开保存文件所在的目录。顺序：厂商定制文件管理器 → 系统文档界面 → 交给系统挑。
     *
     * 关键修复：data 和 type 必须一次性用 setDataAndType 设好。
     * Intent.setData() 会清掉 type，setType() 又会清掉 data，两边互相覆盖；
     * 老版本先 setData(url) 再 `type = "..."`，最终 Intent 里没有 data，
     * queryIntentActivities 得到 0 个目标 → 点了毫无反应。
     */
    fun openFolder(activity: Activity, savedPath: String): Boolean {
        val rel = storageRelDir(savedPath)
        val dirUri = documentDirUri(rel)
        val pm = activity.packageManager

        // ① 各家厂商自带文件管理器（列表顺序＝优先级）
        for (pkg in FILE_MANAGERS) {
            // 直接定位到 Download 目录：先试 document URI，再试老式 resource/folder
            if (startQuietly(activity, dirIntent(dirUri, "vnd.android.document/directory", pkg, rel))) return true
            if (startQuietly(activity, dirIntent(dirUri, "resource/folder", pkg, rel))) return true
            // 有的定制管理器两个都不认，退一步：把管理器本身拉起来，至少进去就是文件界面
            val launch = try {
                pm.getLaunchIntentForPackage(pkg)
            } catch (t: Throwable) {
                null
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putPathExtras(launch, rel)
                if (startQuietly(activity, launch)) return true
            }
        }

        // ② 隐式：让系统从所有处理目录的 App 里挑
        if (startQuietly(activity, dirIntent(dirUri, "vnd.android.document/directory", null, rel))) return true

        return false
    }

    /** 机型优先级表：找不到的包会被自动跳过 */
    private val FILE_MANAGERS = listOf(
        "com.android.fileexplorer",           // 小米 MIUI / HyperOS（国内）
        "com.mi.android.globalFileexplorer",  // 小米 国际版
        "com.oplus.filemanager",              // OPPO ColorOS 12+ / realme UI
        "com.coloros.filemanager",            // OPPO ColorOS 5~11
        "com.oppo.filemanager",               // OPPO 更老机型
        "com.oneplus.filemanager",            // 一加
        "com.vivo.filemanager",               // vivo / iQOO
        "com.bbk.fileexplorer",               // vivo 老机型
        "com.huawei.hidisk",                  // 华为「文件管理」
        "com.huawei.filemanager",
        "com.hihonor.filemanager",            // 荣耀
        "com.sec.android.app.myfiles",        // 三星「我的文件」
        "com.meizu.filemanager",              // 魅族
        "com.zui.filemanager",                // 联想 / 摩托罗拉
        "com.android.documentsui",            // 系统文档界面（兜底）
        "com.google.android.documentsui"
    )

    private fun dirIntent(dirUri: Uri, type: String, pkg: String?, rel: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            // ★ 一次性设 data + type，分成两步会互相清空
            setDataAndType(dirUri, type)
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putPathExtras(this, rel)
        }

    /** 给定制管理器塞目录路径：各家认的 key 不同，认不出就忽略，无副作用 */
    private fun putPathExtras(intent: Intent, rel: String) {
        @Suppress("DEPRECATION")
        val abs = File(Environment.getExternalStorageDirectory(), rel).absolutePath
        intent.putExtra("explorer_path", abs)
        intent.putExtra("currentPath", abs)
        intent.putExtra("key_dir_path", abs)
    }

    private fun startQuietly(activity: Activity, intent: Intent): Boolean {
        val can = try {
            intent.resolveActivity(activity.packageManager) != null
        } catch (t: Throwable) {
            false
        }
        if (!can) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 外部存储的目录 document URI，例如 primary:Download */
    private fun documentDirUri(rel: String): Uri {
        val id = if (rel.contains('/')) Uri.encode(rel) else rel
        return Uri.parse("content://com.android.externalstorage.documents/document/primary:$id")
    }

    /** 把保存路径换算成相对内部存储根目录的目录名，例如 "Download" */
    private fun storageRelDir(savedPath: String): String {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath // /storage/emulated/0
        val dir = savedPath.substringBeforeLast('/', "")
        if (dir.startsWith("$root/")) return dir.removePrefix("$root/")
        if (dir == root) return ""
        if (dir.startsWith("/")) return Environment.DIRECTORY_DOWNLOADS
        return if (dir.isEmpty()) Environment.DIRECTORY_DOWNLOADS else dir
    }

    private fun copyPath(activity: Activity, savedPath: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("保存位置", savedPath))
        } catch (_: Throwable) {
        }
    }

    private fun toast(activity: Activity, msg: String) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }
}
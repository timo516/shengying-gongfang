package com.mediakit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 单页界面，全部用系统控件拼（不依赖 androidx / AppCompat）。
 * 选文件 → 看信息 → 抽音频 / 剪片段 → 结果直接进 Download 根目录。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1001
        /** 两次点击之间的最小间隔：5 秒内重复点不生效 */
        private const val COOLDOWN_MS = 5000L
    }

    private var uri: Uri? = null
    private var info: MediaInfo.Info? = null
    private var busy = false
    private var lastPct = -1
    /** 上一次开始任务的时间点（用于 5 秒防重复点） */
    private var lastRunAt = 0L

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private val actionButtons = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Perm.ensureLegacyStorage(this)
        showProgress(false)
        statusView.text = if (Ffmpeg.available(this)) {
            "已就绪（内置 ffmpeg，完全离线）"
        } else {
            "⚠️ 没找到内置 ffmpeg。可能这台设备的 CPU 架构不在支持范围内（arm64 / armv7 / x86 / x86_64）"
        }
        handleSendIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleSendIntent(intent)
    }

    // 结果直接进公共 Download 目录（MediaStore 写入），不需要「所有文件访问」这种敏感权限

    @Suppress("DEPRECATION")
    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            uri = u
            loadInfo(u)
        }
    }

    // ---------------- UI ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }

        column.addView(TextView(this).apply {
            text = "声影工坊"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }, matchWrap(top = 0))

        statusView = TextView(this).apply {
            text = "准备中…"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(10))
        }
        column.addView(statusView, matchWrap())

        val pick = actionButton("① 选择视频 / 音频") { pickFile() }
        column.addView(pick, matchWrap(top = 4))

        infoView = TextView(this).apply {
            text = "还没选文件"
            textSize = 13f
            setTextColor(getColor(R.color.text_body))
            setPadding(dp(2), dp(12), dp(2), dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        column.addView(infoView, matchWrap())

        column.addView(sectionTitle("音频剪辑器"), matchWrap(top = 14))
        column.addView(actionButton("🎧 打开剪辑器（拖波形选段、删中间、拼接）") { openEditor() }, matchWrap(top = 6))
        column.addView(hint("剪辑器里可以再选文件：视频会自动抽出音轨后再剪，纯音频直接导入。只处理声音，不动画面。"))

        column.addView(sectionTitle("提取音频（原音轨不动）"), matchWrap(top = 18))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(actionButton("m4a 无损") { extractAudio("m4a") }, weight())
        row1.addView(actionButton("mp3 192k") { extractAudio("mp3") }, weight())
        column.addView(row1, matchWrap(top = 6))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(actionButton("flac") { extractAudio("flac") }, weight())
        row2.addView(actionButton("wav") { extractAudio("wav") }, weight())
        column.addView(row2, matchWrap(top = 6))
        column.addView(hint("m4a 会把视频里的 AAC 音轨原样搬出来，几秒完成、音质零损失。源音轨不是 AAC 时自动转码。"))

        column.addView(sectionTitle("剪辑片段"), matchWrap(top = 18))
        startInput = timeInput("起点  例如 0:30 或 90（留空=从头）")
        endInput = timeInput("终点  例如 1:10（留空=到结尾）")
        column.addView(startInput, matchWrap(top = 6))
        column.addView(endInput, matchWrap(top = 6))
        column.addView(actionButton("快速剪（无损，切在关键帧）") { clip(false) }, matchWrap(top = 8))
        column.addView(actionButton("精确剪（重编码，一秒不差，慢）") { clip(true) }, matchWrap(top = 6))
        column.addView(hint("快速剪直接搬数据，几秒完成，但起点会落在最近的关键帧上（可能差零点几秒）。要严丝合缝用精确剪。"))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        column.addView(progress, matchWrap(top = 18))

        progressText = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.END
        }
        column.addView(progressText, matchWrap())

        column.addView(hint("结果直接保存到 手机存储/Download。保存完会弹一个「查看」按钮，点了直接跳到文件管理器的 Download 目录。全程不联网、不上传。"), matchWrap(top = 14))

        return ScrollView(this).apply { addView(column) }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.accent))
        setPadding(0, dp(6), 0, 0)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_hint))
        setPadding(dp(2), dp(8), dp(2), 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.setOnClickListener { onClick() }
        actionButtons.add(b)
        return b
    }

    private fun timeInput(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { rightMargin = dp(4); leftMargin = dp(4) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setActionsEnabled(enabled: Boolean) {
        for (b in actionButtons) b.isEnabled = enabled
    }

    private fun showProgress(visible: Boolean) {
        progress.visibility = if (visible) View.VISIBLE else View.GONE
        progressText.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setProgress(p: Float, label: String? = null) {
        val pct = (p.coerceIn(0f, 1f) * 100).toInt()
        if (label == null && pct == lastPct) return
        lastPct = pct
        runOnUiThread {
            progress.progress = pct
            progressText.text = label ?: "$pct%"
        }
    }

    // ---------------- 选文件 ----------------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (_: Throwable) {
            toast("这台设备没有可用的文件选择器")
        }
    }

    /** 打开音频剪辑器；当前已选文件会直接带过去 */
    private fun openEditor() {
        val i = Intent(this, AudioEditorActivity::class.java)
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            i.putExtra(AudioEditorActivity.EXTRA_URI, it.toString())
        }
        try {
            startActivity(i)
        } catch (t: Throwable) {
            toast("打不开剪辑器：${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            uri = u
            loadInfo(u)
        }
    }

    private fun loadInfo(u: Uri) {
        statusView.text = "读取文件信息…"
        infoView.text = "…"
        Thread {
            val i = try {
                MediaInfo.probe(this, u)
            } catch (_: Throwable) {
                null
            }
            val name = displayName(u)
            runOnUiThread {
                info = i
                if (i == null) {
                    infoView.text = "文件：$name\n读不出信息，但仍可以试着处理（ffmpeg 更宽容）"
                    statusView.text = "已选择文件"
                    return@runOnUiThread
                }
                val sb = StringBuilder()
                sb.append("文件  ").append(name).append('\n')
                sb.append("时长  ").append(i.durationText)
                if (i.width > 0) sb.append("   画面  ").append(i.width).append('x').append(i.height)
                sb.append('\n')
                sb.append("轨道  ")
                if (i.hasVideo) sb.append("视频 ")
                if (i.hasAudio) sb.append("音频") else sb.append("没有音频（抽不了音轨）")
                infoView.text = sb.toString()
                statusView.text = "已选择文件"
            }
        }.start()
    }

    // ---------------- 任务调度 ----------------

    private fun runTask(title: String, longTask: Boolean = false, block: () -> String) {
        if (busy) {
            toast("正在处理，稍等")
            return
        }
        // Android 7/8/9 写公共 Download 需要存储权限，先申请再去干活
        if (!Perm.hasLegacyStorage(this)) {
            Perm.ensureLegacyStorage(this)
            toast("需要存储权限才能保存到 Download，请点允许后重试")
            return
        }
        // 防连续狂点：5 秒内重复点击直接无效，避免同一份结果被反复保存
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastRunAt
        if (lastRunAt != 0L && gap < COOLDOWN_MS) {
            val wait = ((COOLDOWN_MS - gap) / 1000L + 1L)
            toast("刚点过了，${wait} 秒后再试")
            return
        }
        lastRunAt = now
        busy = true
        setActionsEnabled(false)
        lastPct = -1
        showProgress(true)
        setProgress(0f, "准备中…")
        if (longTask) {
            // 折中方案：不开前台服务，开跑前提示 + 期间保持屏幕常亮
            LongTask.start(this)
            statusView.text = "$title（请保持本页在前台）"
        } else {
            statusView.text = title
        }
        Thread {
            val msg = try {
                block()
            } catch (t: Throwable) {
                "⚠️ " + (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                busy = false
                LongTask.stop(this@MainActivity)
                setActionsEnabled(true)
                showProgress(false)
                statusView.text = msg
                // 保存成功的出口统一弹「查看」目录引导（提取音频 / 剪片段都走这里）
                val saved = Output.takeLastSaved()
                if (saved != null && msg.startsWith("✅")) Output.promptSaved(this@MainActivity, saved)
            }
        }.start()
    }

    // ---------------- 提取音频 ----------------

    private fun extractAudio(fmt: String) {
        val u = uri ?: run {
            toast("先选一个文件")
            return
        }
        // 时长 ≥ 60 秒的素材按长任务处理，开跑前提示别切后台
        runTask("提取音频中…", LongTask.isLong(info?.durationMs ?: 0L)) {
            val src = copyToCache(u)
            val base = baseName(u)
            val out = File(cacheDir, "$base.$fmt")
            try {
                if (out.exists()) out.delete()
                val path = out.absolutePath
                val totalUs = (info?.durationMs ?: 0L) * 1000L

                var err: String? = if (fmt == "m4a") {
                    Ffmpeg.run(this, listOf("-y", "-i", src.absolutePath, "-vn", "-c:a", "copy", path), totalUs) { setProgress(it) }
                } else {
                    val enc = when (fmt) {
                        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
                        "flac" -> listOf("-c:a", "flac")
                        else -> listOf("-c:a", "pcm_s16le")
                    }
                    val a = mutableListOf("-y", "-i", src.absolutePath, "-vn")
                    a.addAll(enc)
                    a.add(path)
                    Ffmpeg.run(this, a, totalUs) { setProgress(it) }
                }

                if (err != null && fmt == "m4a") {
                    // 源音轨不是 AAC / ALAC，copy 塞不进 m4a，改成转码
                    setProgress(0f, "源音轨不是 AAC，转码中…")
                    err = Ffmpeg.run(
                        this,
                        listOf("-y", "-i", src.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", path),
                        totalUs
                    ) { setProgress(it) }
                }
                if (err != null) throw IllegalStateException(err)
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

                setProgress(1f, "100%")
                val mime = when (fmt) {
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    "wav" -> "audio/wav"
                    else -> "audio/mp4"
                }
                val saved = Output.publish(this, out, "${base}.$fmt", mime)
                "✅ 已保存\n$saved\n大小 ${humanSize(out.length())}"
            } finally {
                src.delete()
                out.delete()
            }
        }
    }

    // ---------------- 剪辑 ----------------

    private fun clip(precise: Boolean) {
        val u = uri ?: run {
            toast("先选一个文件")
            return
        }
        val startText = startInput.text.toString().trim()
        val endText = endInput.text.toString().trim()

        val s = if (startText.isEmpty()) 0.0 else parseTime(startText)
        if (s == null) {
            toast("起点格式不对，写成 0:30 或 90")
            return
        }
        val e = if (endText.isEmpty()) null else parseTime(endText)
        if (endText.isNotEmpty() && e == null) {
            toast("终点格式不对，写成 1:10 或 70")
            return
        }
        if (e != null && e <= s) {
            toast("终点必须大于起点")
            return
        }

        // 「精确剪」一定要重编码，不管素材多长都提醒；快速剪只看素材时长
        runTask(
            if (precise) "精确剪辑中（要重编码，慢）…" else "剪辑中…",
            precise || LongTask.isLong(info?.durationMs ?: 0L)
        ) {
            val src = copyToCache(u)
            val base = baseName(u)
            val out = File(cacheDir, "$base 片段.mp4")
            try {
                if (out.exists()) out.delete()

                var lengthSec = 0.0
                if (e != null) {
                    lengthSec = e - s
                } else {
                    val total = (info?.durationMs ?: 0L) / 1000.0
                    if (total > s) lengthSec = total - s
                }
                val totalUs = (lengthSec * 1_000_000).toLong().coerceAtLeast(0L)

                val a = mutableListOf<String>()
                a.add("-y")
                if (s > 0) {
                    // -ss 放 -i 前面是输入定位，快得多
                    a.add("-ss")
                    a.add("%.3f".format(s))
                }
                a.add("-i")
                a.add(src.absolutePath)
                if (e != null) {
                    a.add("-t")
                    a.add("%.3f".format(e - s))
                }
                if (precise) {
                    a.addAll(
                        listOf(
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                            "-movflags", "+faststart"
                        )
                    )
                } else {
                    // 注意：这里不加 -avoid_negative_ts make_zero，实测会把时长弄错
                    a.addAll(listOf("-c", "copy", "-map", "0"))
                }
                a.add(out.absolutePath)

                val err = Ffmpeg.run(this, a, totalUs) { setProgress(it) }
                if (err != null) {
                    throw IllegalStateException(
                        if (!precise) "$err\n\n（这个容器不适合直接剪切，试试「精确剪」）" else err
                    )
                }
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

                setProgress(1f, "100%")
                val saved = Output.publish(this, out, out.name, "video/mp4")
                "✅ 已保存\n$saved\n大小 ${humanSize(out.length())}"
            } finally {
                src.delete()
                out.delete()
            }
        }
    }

    // ---------------- 杂项 ----------------

    private fun copyToCache(u: Uri): File {
        val name = sanitize(displayName(u))
        val ext = name.substringAfterLast('.', "").ifEmpty { "mp4" }.lowercase()
        val dst = File(cacheDir, "input_pending.$ext")
        if (dst.exists()) dst.delete()
        // 先看源文件多大、内部存储还剩多少，避免把缓存盘塞爆后只报个看不懂的 IO 异常
        val need = try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
        if (need > 0L) {
            val free = android.os.StatFs(cacheDir.absolutePath).availableBytes
            if (free < need + 50L * 1024 * 1024) {
                throw IllegalStateException(
                    "手机空间不够：这个文件约 ${humanSize(need)}，可用只有 ${humanSize(free)}。清点空间再试。"
                )
            }
        }
        runOnUiThread { setProgress(0f, "准备文件…") }
        contentResolver.openInputStream(u)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("读不到这个文件，可能权限没给")
        return dst
    }

    private fun displayName(u: Uri): String {
        var name = "未命名"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val v = c.getString(idx)
                        if (!v.isNullOrBlank()) name = v
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return name
    }

    private fun baseName(u: Uri): String {
        val n = sanitize(displayName(u))
        val dot = n.lastIndexOf('.')
        val base = if (dot > 0) n.substring(0, dot) else n
        return base.ifBlank { "音频" }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().ifEmpty { "未命名" }
        // 文件名过长会直接报 File name too long，截断但保留扩展名
        if (cleaned.length <= 80) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
        val base = if (ext.isEmpty()) cleaned else cleaned.substring(0, dot)
        return base.take(80 - ext.length) + ext
    }

    /** 支持 90 / 1:30 / 00:01:30.5 */
    private fun parseTime(raw: String): Double? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.matches(Regex("^\\d+(\\.\\d+)?$"))) return t.toDoubleOrNull()
        val parts = t.split(':')
        if (parts.size < 2 || parts.size > 3) return null
        var total = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0) return null
            total = total * 60 + v
        }
        return total
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        return if (mb < 1024) "%.1f MB".format(mb) else "%.2f GB".format(mb / 1024.0)
    }
}
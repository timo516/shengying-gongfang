package com.mediakit.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 单页界面，全部用系统控件拼（不依赖 androidx / AppCompat）。
 * 选文件 → 看信息 → 抽音频 / 剪片段 → 结果直接进 Download 根目录。
 *
 * 1.1.0 起：参数拼装全部走 [FfmpegArgs]（可能的话别再在 Activity 里拼 ffmpeg 参数），
 * 时间/尺寸格式化走 [Fmt]（一律 Locale.US），UI 小工具走 [BaseActivity]。
 */
class MainActivity : BaseActivity() {

    companion object {
        private const val REQ_PICK = 1001

        /** 成功保存后这段时间内不允许再跑：防止同一份结果被反复保存 */
        private const val COOLDOWN_MS = 5000L
    }

    private var uri: Uri? = null
    private var info: MediaInfo.Info? = null
    private var busy = false
    private var lastPct = -1
    private var lastSuccessAt = 0L

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText

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
        setIntent(intent)
        handleSendIntent(intent)
    }

    // 结果直接进公共 Download 目录（MediaStore 写入），不需要「所有文件访问」这种敏感权限

    @Suppress("DEPRECATION")
    private fun handleSendIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return
        uri = u
        loadInfo(u)
    }

    // ---------------- UI ----------------

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

        column.addView(actionButton("① 选择视频 / 音频") { pickFile() }, matchWrap(top = 4))

        infoView = TextView(this).apply {
            text = "还没选文件"
            textSize = 13f
            setTextColor(getColor(R.color.text_body))
            setPadding(dp(2), dp(12), dp(2), dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        column.addView(infoView, matchWrap())

        column.addView(sectionTitle("音频剪辑器"), matchWrap(top = 14))
        column.addView(
            actionButton("🎧 打开剪辑器（拖波形选段、删中间、拼接）") { openEditor() },
            matchWrap(top = 6)
        )
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

        column.addView(
            hint("结果直接保存到 手机存储/Download。保存完会弹一个「查看」按钮，点了直接跳到文件管理器的 Download 目录。全程不联网、不上传。"),
            matchWrap(top = 14)
        )

        return ScrollView(this).apply { addView(column) }
    }

    private fun timeInput(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
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
                // 分享进来的 Uri 不是 persistable 的，正常，忽略
            }
            i.putExtra(AudioEditorActivity.EXTRA_URI, it.toString())
            // 同包内传递也要显式带上 grant 标志：分享进来的临时授权只给到本包，别指望自动继承
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != Perm.REQ_WRITE) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("已授权，可以再点一次了")
            return
        }
        val canAskAgain = try {
            shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } catch (_: Throwable) {
            false
        }
        if (canAskAgain) {
            toast("没给存储权限的话，结果存不到 Download")
        } else {
            toast("存储权限被永久拒绝，需要去设置里手动打开")
            openAppSettings()
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
                if (!uiAlive()) return@runOnUiThread
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
        // 防连续狂点：上一次成功保存后 5 秒内的重复点击直接无效
        val now = SystemClock.elapsedRealtime()
        if (lastSuccessAt != 0L && now - lastSuccessAt < COOLDOWN_MS) {
            toast("刚保存过，${(COOLDOWN_MS - (now - lastSuccessAt)) / 1000L + 1L} 秒后再试")
            return
        }
        busy = true
        setActionsEnabled(false)
        lastPct = -1
        showProgress(true)
        setProgress(0f, "准备中…")
        if (longTask) {
            LongTask.start(this, title)
            statusView.text = "$title（长任务，切后台也不会中断）"
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
                if (!uiAlive()) return@runOnUiThread
                setActionsEnabled(true)
                showProgress(false)
                statusView.text = msg
                // 保存成功的出口统一弹「查看」目录引导（提取音频 / 剪片段都走这里）
                if (msg.startsWith("✅")) {
                    lastSuccessAt = SystemClock.elapsedRealtime()
                    val saved = Output.takeLastSaved()
                    if (saved != null) Output.promptSaved(this@MainActivity, saved)
                }
            }
        }.start()
    }

    // ---------------- 提取音频 ----------------

    private fun extractAudio(fmt: String) {
        val u = uri ?: run {
            toast("先选一个文件")
            return
        }
        // 时长 ≥ 60 秒的素材按长任务处理
        runTask("提取音频中…", LongTask.isLong(info?.durationMs ?: 0L)) {
            val name = displayName(u)
            val src = copyToCache(u)
            val base = Names.base(name)
            val out = File(cacheDir, "$base.$fmt")
            try {
                if (out.exists()) out.delete()
                val totalUs = (info?.durationMs ?: 0L) * 1000L

                var err = if (fmt == "m4a") {
                    Ffmpeg.run(this, FfmpegArgs.extractCopy(src.absolutePath, out.absolutePath), totalUs) {
                        setProgress(it)
                    }
                } else {
                    Ffmpeg.run(this, FfmpegArgs.extractEncode(src.absolutePath, out.absolutePath, fmt), totalUs) {
                        setProgress(it)
                    }
                }

                if (err != null && fmt == "m4a") {
                    // 源音轨不是 AAC / ALAC，copy 塞不进 m4a，改成转码
                    setProgress(0f, "源音轨不是 AAC，转码中…")
                    err = Ffmpeg.run(
                        this,
                        FfmpegArgs.extractEncode(src.absolutePath, out.absolutePath, "m4a"),
                        totalUs
                    ) { setProgress(it) }
                }
                if (err != null) throw IllegalStateException(err)
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

                setProgress(1f, "100%")
                val size = out.length()
                val saved = Output.publish(this, out, "${base}.$fmt", FfmpegArgs.mimeFor(fmt))
                "✅ 已保存\n$saved\n大小 ${Fmt.humanSize(size)}"
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

        val s = if (startText.isEmpty()) 0.0 else Fmt.parseTime(startText)
        if (s == null) {
            toast("起点格式不对，写成 0:30 或 90")
            return
        }
        val e = if (endText.isEmpty()) null else Fmt.parseTime(endText)
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
            val name = displayName(u)
            val src = copyToCache(u)
            // 关键：copy 模式沿用源容器。写死 .mp4 时，带字幕 mkv / opus 音轨 / wav 都会整条失败
            val ext = FfmpegArgs.clipContainer(name, precise)
            val out = File(cacheDir, "${Names.base(name)} 片段.$ext")
            try {
                if (out.exists()) out.delete()

                val totalSec = (info?.durationMs ?: 0L) / 1000.0
                val durSec = when {
                    e != null -> e - s
                    totalSec > s -> totalSec - s
                    else -> null
                }
                val totalUs = ((durSec ?: 0.0) * 1_000_000).toLong()

                val err = Ffmpeg.run(
                    this,
                    FfmpegArgs.clip(src.absolutePath, out.absolutePath, s, durSec, precise),
                    totalUs
                ) { setProgress(it) }
                if (err != null) {
                    throw IllegalStateException(
                        if (!precise) "$err\n\n（这个文件不太适合直接剪切，试试「精确剪」）" else err
                    )
                }
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

                setProgress(1f, "100%")
                val size = out.length()
                val saved = Output.publish(this, out, out.name, FfmpegArgs.mimeFor(ext))
                "✅ 已保存\n$saved\n大小 ${Fmt.humanSize(size)}"
            } finally {
                src.delete()
                out.delete()
            }
        }
    }

    // ---------------- 杂项 ----------------

    private fun copyToCache(u: Uri): File {
        val name = Names.sanitize(displayName(u))
        val ext = Names.ext(name).ifEmpty { "mp4" }
        val dst = File(cacheDir, "input_pending.$ext")
        if (dst.exists()) dst.delete()
        // 先看源文件多大、内部存储还剩多少，避免把缓存盘塞爆后只报个看不懂的 IO 异常
        Storage.requireFree(cacheDir, sizeOf(u))
        runOnUiThread { setProgress(0f, "准备文件…") }
        contentResolver.openInputStream(u)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("读不到这个文件，可能权限没给")
        return dst
    }
}

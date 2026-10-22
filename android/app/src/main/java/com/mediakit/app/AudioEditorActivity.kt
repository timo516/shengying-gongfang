package com.mediakit.app

import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 音频剪辑器：波形时间轴 + 双滑块选区 + 多段删除 + 拼接导出。
 *
 * 只处理声音，不碰画面。入口两条：
 *  1) 先「提取音频」，再进来剪
 *  2) 直接导入音频文件；导入视频也行，会先自动抽音轨再剪
 *
 * 引擎还是内置的 ffmpeg：删除多段就是用 atrim 把保留段切出来再 concat 拼上，
 * 音频重编码比视频快得多，几分钟的素材通常几秒到十几秒。
 */
class AudioEditorActivity : BaseActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        private const val REQ_PICK = 2001
    }

    /** 两列排布，按钮字号比主页小一号 */
    override val buttonTextSize: Float = 13f

    private var srcFile: File? = null
    private var durationMs = 0L
    private var busy = false

    /** 已标记删除的区间（毫秒），升序且已合并 */
    private val deleted = ArrayList<LongRange>()

    private var previewing = false
    private var previewStartMs = 0L
    private var previewEndMs = 0L

    private lateinit var wave: WaveView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var selView: TextView
    private lateinit var delView: TextView
    private lateinit var playBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastPct = -1
    private var lastPlayLabel = ""

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val pos = try {
                    p.currentPosition.toLong()
                } catch (_: Throwable) {
                    0L
                }
                wave.playheadMs = pos
                if (previewing && pos >= previewEndMs) stopPreview()
                val label = if (previewing) "■ 试听中" else if (isPlaying(p)) "⏸ 暂停" else "▶ 播放"
                // 80ms 一次，只在文案真的变了才写，省掉无意义的重绘
                if (label != lastPlayLabel) {
                    playBtn.text = label
                    lastPlayLabel = label
                }
                handler.postDelayed(this, 80)
            }
        }
    }

    private fun isPlaying(p: MediaPlayer): Boolean = try {
        p.isPlaying
    } catch (_: Throwable) {
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Perm.ensureLegacyStorage(this)
        showProgress(false)
        if (!Ffmpeg.available(this)) {
            statusView.text = "⚠️ 没找到内置 ffmpeg。可能这台设备的 CPU 架构不在支持范围内"
            setActionsEnabled(false)
        } else {
            statusView.text = "先导入一段音频（或视频，会自动抽音轨）"
        }
        val us = intent?.getStringExtra(EXTRA_URI)
        if (!us.isNullOrEmpty()) {
            importUri(Uri.parse(us))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        releasePlayer()
        // 临时文件放 cacheDir，正常退出就清掉；任务还在跑的时候别动（正在读它）
        if (!busy) Storage.clearWorkDir(this)
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }

        titleView = TextView(this).apply {
            text = "音频剪辑"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            maxLines = 2
        }
        col.addView(titleView, matchWrap())

        statusView = TextView(this).apply {
            text = "准备中…"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(8))
        }
        col.addView(statusView, matchWrap())

        col.addView(actionButton("① 导入音频 / 视频") { pickFile() }, matchWrap(top = 2))

        infoView = TextView(this).apply {
            text = "还没导入文件"
            textSize = 13f
            setTextColor(getColor(R.color.text_body))
            setPadding(dp(2), dp(10), dp(2), dp(6))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        col.addView(infoView, matchWrap())

        wave = WaveView(this)
        val waveLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        waveLp.topMargin = dp(4)
        col.addView(wave, waveLp)
        wave.onSelectionChanged = { updateSelText() }
        wave.onSeek = { ms -> seekTo(ms) }

        selView = TextView(this).apply {
            text = "选区 —"
            textSize = 13f
            setTextColor(getColor(R.color.accent))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(10), dp(2), 0)
        }
        col.addView(selView, matchWrap())

        col.addView(hint("拖蓝色手柄改边界；按选区中间可整体挪；按选区外移动播放头。"), matchWrap(top = 2))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(actionButton("起点=播放头") { setStartAtPlayhead() }, weight())
        row1.addView(actionButton("终点=播放头") { setEndAtPlayhead() }, weight())
        col.addView(row1, matchWrap(top = 8))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playBtn = actionButton("▶ 播放") { togglePlay() }
        row2.addView(playBtn, weight())
        row2.addView(actionButton("试听选区") { previewSelection() }, weight())
        col.addView(row2, matchWrap(top = 6))

        col.addView(sectionTitle("剪辑操作"), matchWrap(top = 16))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(actionButton("删掉这段") { markDeleteSelection() }, weight())
        row3.addView(actionButton("只留这段") { keepOnlySelection() }, weight())
        col.addView(row3, matchWrap(top = 6))

        delView = TextView(this).apply {
            text = "没有标记删除的片段"
            textSize = 12f
            setTextColor(getColor(R.color.text_body))
            setPadding(dp(2), dp(10), dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        col.addView(delView, matchWrap())

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(actionButton("撤销上一次") { undoDelete() }, weight())
        row4.addView(actionButton("清空标记") { clearDelete() }, weight())
        col.addView(row4, matchWrap(top = 6))

        col.addView(actionButton("② 导出（把剩下的部分拼起来）") { export() }, matchWrap(top = 16))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        col.addView(progress, matchWrap(top = 14))

        progressText = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.END
        }
        col.addView(progressText, matchWrap())

        col.addView(
            hint("只处理声音，不涉及画面。导出时音频要重编码一次（很快）；相邻片段衔接处可能有毫秒级误差。"),
            matchWrap(top = 12)
        )

        return ScrollView(this).apply { addView(col) }
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

    // ---------------- 导入 ----------------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (_: Throwable) {
            toast("这台设备没有可用的文件选择器")
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
            importUri(u)
        }
    }

    /** 复制到私有缓存目录；是视频就先抽音轨 */
    private fun importUri(u: Uri) {
        if (busy) {
            toast("正在处理，稍等")
            return
        }
        // 大素材导入要「复制 + 抽音轨 + 算波形」，按长任务提醒
        val longTask = LongTask.isLongBytes(sizeOf(u))
        busy = true
        setActionsEnabled(false)
        showProgress(true)
        setProgress(0f, "读取文件…")
        if (longTask) {
            LongTask.start(this, "导入素材")
            statusView.text = "准备中…（长任务，切后台也不会中断）"
        } else {
            statusView.text = "准备中…"
        }

        Thread {
            val result = try {
                prepareSource(u)
            } catch (t: Throwable) {
                null to (t.message ?: t.javaClass.simpleName)
            }
            val file = result.first
            val err = result.second
            runOnUiThread {
                busy = false
                LongTask.stop(this@AudioEditorActivity)
                if (!uiAlive()) return@runOnUiThread
                if (file == null) {
                    setActionsEnabled(true)
                    showProgress(false)
                    statusView.text = "⚠️ " + (err ?: "导入失败")
                    return@runOnUiThread
                }
                statusView.text = "就绪，拖手柄选范围"
                showProgress(false)
                setActionsEnabled(true)
                infoView.text = "文件  ${file.name}\n时长  ${Fmt.dur(durationMs)}" +
                    if (deleted.isEmpty()) "" else "\n已标记删除 ${deleted.size} 段"
                updateSelText()
                updateDelText()
            }
        }.start()
    }

    /** @return (音频文件, 错误信息) */
    private fun prepareSource(u: Uri): Pair<File?, String?> {
        val work = Storage.workDir(this)
        work.listFiles()?.forEach { it.delete() }

        val name = displayName(u)
        val ext = Names.ext(name).ifEmpty { "bin" }

        setProgress(0f, "复制文件…")
        // 内部存储就这么大，先看一眼再复制，别写成半截才报 ENOSPC
        Storage.requireFree(work, sizeOf(u))
        val raw = File(work, "input.$ext")
        contentResolver.openInputStream(u)?.use { input ->
            raw.outputStream().use { out -> input.copyTo(out) }
        } ?: return null to "读不到这个文件，可能权限没给"

        val info = try {
            MediaInfo.probe(this, u)
        } catch (_: Throwable) {
            null
        }
        val maybeVideo = info?.hasVideo == true || (info == null && FfmpegArgs.isVideoExt(ext))

        val audio: File
        if (maybeVideo) {
            setProgress(0f, "抽音轨…")
            val a = File(work, "audio.m4a")
            var err = Ffmpeg.run(this, FfmpegArgs.extractCopy(raw.absolutePath, a.absolutePath), 0L) {
                setProgress(it * 0.5f, "抽音轨…")
            }
            if (err != null || !a.exists() || a.length() == 0L) {
                a.delete()
                err = Ffmpeg.run(
                    this,
                    FfmpegArgs.extractEncode(raw.absolutePath, a.absolutePath, "m4a"),
                    0L
                ) { setProgress(it * 0.5f, "转码音轨…") }
                if (err != null) return null to "抽音轨失败：$err"
            }
            raw.delete()
            audio = a
        } else {
            audio = raw
        }

        setProgress(0.5f, "分析波形…")
        // 把已知时长传进去：既能给出真实进度，也让看门狗按素材长度缩放
        // （传 0 时进度永远不回调、超时固定 5 分钟，长素材必挂）
        val totalUs = (info?.durationMs ?: 0L) * 1000L
        val peaks = buildPeaks(audio, totalUs) { setProgress(0.5f + it * 0.45f, "分析波形…") }
        durationMs = peaks.second
        if (durationMs <= 0L) return null to "解不出音频内容，这个文件可能没有音轨"
        if (peaks.first.isEmpty()) return null to "波形为空，文件可能损坏"

        srcFile = audio
        deleted.clear()
        runOnUiThread {
            wave.peaks = peaks.first
            wave.durationMs = durationMs
            wave.selStartMs = 0L
            wave.selEndMs = durationMs
            wave.playheadMs = 0L
            wave.deleted = ArrayList<LongRange>()
            titleView.text = name
            preparePlayer()
        }
        return audio to null
    }

    /** 用 ffmpeg 解码成 8kHz 单声道 PCM 再算包络，快且省内存 */
    private fun buildPeaks(src: File, totalUs: Long, onProgress: (Float) -> Unit): Pair<FloatArray, Long> {
        val raw = File(src.parentFile ?: cacheDir, "peaks.raw")
        if (raw.exists()) raw.delete()
        val err = Ffmpeg.run(
            this,
            FfmpegArgs.decodeToPcm(src.absolutePath, raw.absolutePath),
            totalUs
        ) { onProgress(it) }
        if (err != null || !raw.exists() || raw.length() == 0L) {
            raw.delete()
            return FloatArray(0) to 0L
        }
        val samples = (raw.length() / 2).toInt()
        if (samples <= 0) {
            raw.delete()
            return FloatArray(0) to 0L
        }
        val data = FloatArray(Timeline.BUCKETS)
        try {
            DataInputStream(BufferedInputStream(FileInputStream(raw), 1 shl 16)).use { din ->
                var i = 0
                while (i < samples) {
                    val lo = din.read()
                    val hi = din.read()
                    if (hi < 0 || lo < 0) break
                    val v = ((hi shl 8) or lo).toShort().toInt()
                    val a = abs(v).toFloat() / 32768f
                    val b = (i.toLong() * Timeline.BUCKETS / samples).toInt()
                        .coerceIn(0, Timeline.BUCKETS - 1)
                    if (a > data[b]) data[b] = a
                    i++
                }
            }
        } catch (_: Throwable) {
        }
        raw.delete()

        var mx = 0f
        for (x in data) if (x > mx) mx = x
        if (mx > 0f) for (i in data.indices) data[i] = sqrt(data[i] / mx)
        return data to (samples.toLong() * 1000L / 8000L)
    }

    // ---------------- 播放 ----------------

    private fun preparePlayer() {
        releasePlayer()
        val f = srcFile ?: return
        Thread {
            val p = try {
                MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                }
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (p == null) {
                    if (uiAlive()) statusView.text = "预览播放不可用，但剪辑导出不受影响"
                    return@runOnUiThread
                }
                player = p
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }.start()
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun togglePlay() {
        val p = player ?: run {
            toast("播放器还没准备好")
            return
        }
        try {
            if (isPlaying(p)) {
                p.pause()
                previewing = false
            } else {
                if (previewing && p.currentPosition >= previewEndMs) p.seekTo(previewStartMs.toInt())
                p.start()
            }
        } catch (_: Throwable) {
        }
    }

    private fun seekTo(ms: Long) {
        try {
            player?.seekTo(ms.toInt())
        } catch (_: Throwable) {
        }
        previewing = false
    }

    private fun previewSelection() {
        val p = player ?: run {
            toast("播放器还没准备好")
            return
        }
        if (wave.selEndMs - wave.selStartMs < Timeline.MIN_SEL_MS) {
            toast("选区太短了")
            return
        }
        try {
            previewStartMs = wave.selStartMs
            previewEndMs = wave.selEndMs
            p.seekTo(previewStartMs.toInt())
            previewing = true
            p.start()
        } catch (_: Throwable) {
        }
    }

    private fun stopPreview() {
        previewing = false
        try {
            player?.pause()
        } catch (_: Throwable) {
        }
    }

    // ---------------- 选区 / 删除标记 ----------------

    private fun setStartAtPlayhead() {
        val maxStart = (wave.selEndMs - Timeline.MIN_SEL_MS).coerceAtLeast(0L)
        wave.selStartMs = wave.playheadMs.coerceIn(0L, maxStart)
        updateSelText()
    }

    private fun setEndAtPlayhead() {
        val minEnd = (wave.selStartMs + Timeline.MIN_SEL_MS).coerceAtMost(durationMs)
        wave.selEndMs = wave.playheadMs.coerceIn(minEnd, durationMs)
        updateSelText()
    }

    private fun updateSelText() {
        val s = wave.selStartMs
        val e = wave.selEndMs
        selView.text = "选区  ${Fmt.clock(s)} → ${Fmt.clock(e)}　共 ${Fmt.dur(e - s)}"
    }

    private fun markDeleteSelection() {
        if (durationMs <= 0) {
            toast("先导入一个文件")
            return
        }
        val s = wave.selStartMs
        val e = wave.selEndMs
        if (e - s < Timeline.MIN_SEL_MS) {
            toast("选区太短了")
            return
        }
        addDeleted(s, e)
        val whole = deleted.size == 1 && deleted[0].first <= 0L && deleted[0].last >= durationMs
        statusView.text = if (whole) "整段都标记删除了，撤销一下" else "已标记删除，可继续选下一段"
    }

    /** 只保留当前选区，其余全部标记删除 */
    private fun keepOnlySelection() {
        if (durationMs <= 0) {
            toast("先导入一个文件")
            return
        }
        val s = wave.selStartMs
        val e = wave.selEndMs
        if (e - s < Timeline.MIN_SEL_MS) {
            toast("选区太短了")
            return
        }
        deleted.clear()
        if (s > 0L) addDeleted(0L, s)
        if (e < durationMs) addDeleted(e, durationMs)
        statusView.text =
            if (deleted.isEmpty()) "没裁掉任何东西（选区就是全片）" else "已裁成选区，点导出"
    }

    private fun addDeleted(s0: Long, e0: Long) {
        val s = s0.coerceIn(0L, durationMs)
        val e = e0.coerceIn(0L, durationMs)
        if (e <= s) return
        // 注意别写成 deleted + (s..e)：LongRange 本身是 Iterable<Long>，
        // 会被推到 plus(elements: Iterable<Any>) 上去，结果变成 List<Any>
        val merged = Timeline.merge(deleted.toList() + listOf(s..e))
        deleted.clear()
        deleted.addAll(merged)
        wave.deleted = ArrayList(deleted)
        updateDelText()
    }

    private fun undoDelete() {
        if (deleted.isEmpty()) {
            toast("没有可撤销的")
            return
        }
        deleted.removeAt(deleted.size - 1)
        wave.deleted = ArrayList(deleted)
        updateDelText()
        statusView.text = "已撤销一次删除"
    }

    private fun clearDelete() {
        if (deleted.isEmpty()) return
        deleted.clear()
        wave.deleted = ArrayList<LongRange>()
        updateDelText()
        statusView.text = "删除标记已清空"
    }

    private fun keptSegments(): List<LongRange> = Timeline.kept(deleted, durationMs)

    private fun updateDelText() {
        if (durationMs <= 0L) return
        if (deleted.isEmpty()) {
            delView.text = "没有标记删除的片段"
            return
        }
        val kept = keptSegments()
        delView.text = "已标记删除 ${deleted.size} 段（共 ${Fmt.dur(Timeline.total(deleted))}）\n" +
            "导出后剩 ${kept.size} 段，共 ${Fmt.dur(Timeline.total(kept))}"
    }

    // ---------------- 导出 ----------------

    private fun export() {
        val src = srcFile ?: run {
            toast("先导入一个文件")
            return
        }
        if (busy) {
            toast("正在处理，稍等")
            return
        }
        // Android 7/8/9 写公共 Download 需要存储权限
        if (!Perm.hasLegacyStorage(this)) {
            Perm.ensureLegacyStorage(this)
            toast("需要存储权限才能保存到 Download，请点允许后重试")
            return
        }
        val kept = keptSegments()
        if (kept.isEmpty()) {
            toast("整段都被删掉了，没东西可导出")
            return
        }
        val totalMs = Timeline.total(kept)
        // 长素材或多段拼接（filter_complex 重编码）都按长任务处理
        val longTask = LongTask.isLong(totalMs) || kept.size > 1
        busy = true
        setActionsEnabled(false)
        showProgress(true)
        lastPct = -1
        setProgress(0f, "准备导出…")
        statusView.text = if (deleted.isEmpty()) "导出整段…" else "拼接导出中…"
        if (longTask) LongTask.start(this, "导出音频")

        Thread {
            val msg = try {
                doExport(src, kept, totalMs)
            } catch (t: Throwable) {
                "⚠️ " + (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                busy = false
                LongTask.stop(this@AudioEditorActivity)
                if (!uiAlive()) return@runOnUiThread
                setActionsEnabled(true)
                showProgress(false)
                statusView.text = msg
                // 导出成功的出口弹「查看」目录引导
                if (msg.startsWith("✅")) {
                    val saved = Output.takeLastSaved()
                    if (saved != null) Output.promptSaved(this@AudioEditorActivity, saved)
                }
            }
        }.start()
    }

    private fun doExport(src: File, kept: List<LongRange>, totalMs: Long): String {
        val ext = Names.ext(src.name)
        val outExt = when (ext) {
            "mp3", "flac", "wav", "opus", "ogg" -> ext
            else -> "m4a"
        }

        val out = File(cacheDir, "${Names.base(src.name)}_剪辑.$outExt")
        if (out.exists()) out.delete()

        val args = if (kept.size == 1) {
            val r = kept[0]
            FfmpegArgs.exportOne(src.absolutePath, out.absolutePath, r.first, r.last, durationMs, outExt)
        } else {
            FfmpegArgs.exportConcat(src.absolutePath, out.absolutePath, kept, outExt)
        }

        val err = Ffmpeg.run(this, args, totalMs * 1000L) { setProgress(it * 0.9f, "导出中…") }
        if (err != null) throw IllegalStateException(err)
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

        setProgress(0.95f, "保存中…")
        val size = out.length()
        val saved = Output.publish(this, out, out.name, FfmpegArgs.mimeFor(outExt))
        out.delete()
        setProgress(1f, "100%")
        return "✅ 已保存\n$saved\n大小 ${Fmt.humanSize(size)}"
    }
}

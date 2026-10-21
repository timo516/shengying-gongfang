package com.mediakit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
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
class AudioEditorActivity : Activity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        private const val REQ_PICK = 2001

        /** 波形采样桶数 */
        private const val BUCKETS = 1600

        /** 选区最短 300ms，太短了手柄会叠在一起 */
        private const val MIN_SEL_MS = 300L
    }

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
    private val actionButtons = ArrayList<Button>()

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val pos = try {
                    p.currentPosition.toLong()
                } catch (t: Throwable) {
                    0L
                }
                wave.playheadMs = pos
                if (previewing && pos >= previewEndMs) stopPreview()
                playBtn.text = if (previewing) "■ 试听中" else if (isPlaying(p)) "⏸ 暂停" else "▶ 播放"
                handler.postDelayed(this, 80)
            }
        }
    }

    private fun isPlaying(p: MediaPlayer): Boolean = try {
        p.isPlaying
    } catch (t: Throwable) {
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
    }

    // ---------------- UI ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        val waveLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
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
        b.textSize = 13f
        b.setPadding(dp(6), 0, dp(6), 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        actionButtons.add(b)
        return b
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply {
            rightMargin = dp(4)
            leftMargin = dp(4)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setActionsEnabled(enabled: Boolean) {
        for (b in actionButtons) b.isEnabled = enabled
    }

    private fun showProgress(visible: Boolean) {
        progress.visibility = if (visible) View.VISIBLE else View.GONE
        progressText.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private var lastPct = -1

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

    /** 复制到私有工作目录；是视频就先抽音轨 */
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
            LongTask.start(this)
            statusView.text = "准备中…（请保持本页在前台）"
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
                if (file == null) {
                    setActionsEnabled(true)
                    showProgress(false)
                    statusView.text = "⚠️ " + (err ?: "导入失败")
                    return@runOnUiThread
                }
                statusView.text = "就绪，拖手柄选范围"
                showProgress(false)
                setActionsEnabled(true)
                infoView.text = "文件  ${file.name}\n时长  ${fmtDur(durationMs)}" +
                    if (deleted.isEmpty()) "" else "\n已标记删除 ${deleted.size} 段"
                updateSelText()
                updateDelText()
            }
        }.start()
    }

    /** @return (音频文件, 错误信息) */
    private fun prepareSource(u: Uri): Pair<File?, String?> {
        val work = File(filesDir, "audio_work")
        if (!work.exists() && !work.mkdirs()) return null to "无法创建临时目录"
        work.listFiles()?.forEach { it.delete() }

        val name = displayName(u)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US).ifEmpty { "bin" }

        setProgress(0f, "复制文件…")
        val raw = File(work, "input.$ext")
        contentResolver.openInputStream(u)?.use { input ->
            raw.outputStream().use { out -> input.copyTo(out) }
        } ?: return null to "读不到这个文件，可能权限没给"

        val info = try {
            MediaInfo.probe(this, u)
        } catch (t: Throwable) {
            null
        }
        val videoExt = setOf("mp4", "mkv", "mov", "m4v", "avi", "webm", "flv", "ts", "3gp", "wmv")
        val maybeVideo = info?.hasVideo == true || (info == null && ext in videoExt)

        val audio: File
        if (maybeVideo) {
            setProgress(0f, "抽音轨…")
            val a = File(work, "audio.m4a")
            var err = Ffmpeg.run(
                this,
                listOf("-y", "-i", raw.absolutePath, "-vn", "-c:a", "copy", a.absolutePath),
                0L
            ) { setProgress(it * 0.5f, "抽音轨…") }
            if (err != null || !a.exists() || a.length() == 0L) {
                a.delete()
                err = Ffmpeg.run(
                    this,
                    listOf(
                        "-y", "-i", raw.absolutePath, "-vn",
                        "-c:a", "aac", "-b:a", "192k", "-strict", "-2",
                        a.absolutePath
                    ),
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
        val peaks = buildPeaks(audio) { setProgress(0.5f + it * 0.45f, "分析波形…") }
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
    private fun buildPeaks(src: File, onProgress: (Float) -> Unit): Pair<FloatArray, Long> {
        val raw = File(src.parentFile ?: cacheDir, "peaks.raw")
        if (raw.exists()) raw.delete()
        val err = Ffmpeg.run(
            this,
            listOf(
                "-y", "-i", src.absolutePath, "-vn",
                "-ac", "1", "-ar", "8000", "-f", "s16le",
                raw.absolutePath
            ),
            0L
        ) { onProgress(it) }
        if (err != null || !raw.exists() || raw.length() == 0L) {
            raw.delete()
            return FloatArray(0) to 0L
        }
        val samples = (raw.length() / 2).toInt()
        val data = FloatArray(BUCKETS)
        if (samples <= 0) {
            raw.delete()
            return FloatArray(0) to 0L
        }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(raw), 1 shl 16)).use { din ->
                var i = 0
                while (i < samples) {
                    val lo = din.read()
                    val hi = din.read()
                    if (hi < 0) break
                    val v = ((hi shl 8) or lo).toShort().toInt()
                    val a = abs(v).toFloat() / 32768f
                    val b = (i.toLong() * BUCKETS / samples).toInt().coerceIn(0, BUCKETS - 1)
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
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (p == null) {
                    statusView.text = "预览播放不可用，但剪辑导出不受影响"
                } else {
                    player = p
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                }
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
        if (wave.selEndMs - wave.selStartMs < MIN_SEL_MS) {
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
        val maxStart = (wave.selEndMs - MIN_SEL_MS).coerceAtLeast(0L)
        wave.selStartMs = wave.playheadMs.coerceIn(0L, maxStart)
        updateSelText()
    }

    private fun setEndAtPlayhead() {
        val minEnd = (wave.selStartMs + MIN_SEL_MS).coerceAtMost(durationMs)
        wave.selEndMs = wave.playheadMs.coerceIn(minEnd, durationMs)
        updateSelText()
    }

    private fun updateSelText() {
        val s = wave.selStartMs
        val e = wave.selEndMs
        selView.text = "选区  ${fmt(s)} → ${fmt(e)}　共 ${fmtDur(e - s)}"
    }

    private fun markDeleteSelection() {
        if (durationMs <= 0) {
            toast("先导入一个文件")
            return
        }
        val s = wave.selStartMs
        val e = wave.selEndMs
        if (e - s < MIN_SEL_MS) {
            toast("选区太短了")
            return
        }
        addDeleted(s, e)
        if (deleted.size == 1 && deleted[0].first <= 0L && deleted[0].last >= durationMs) {
            statusView.text = "整段都标记删除了，撤销一下"
        } else {
            statusView.text = "已标记删除，可继续选下一段"
        }
    }

    /** 只保留当前选区，其余全部标记删除 */
    private fun keepOnlySelection() {
        if (durationMs <= 0) {
            toast("先导入一个文件")
            return
        }
        val s = wave.selStartMs
        val e = wave.selEndMs
        if (e - s < MIN_SEL_MS) {
            toast("选区太短了")
            return
        }
        deleted.clear()
        if (s > 0L) addDeleted(0L, s)
        if (e < durationMs) addDeleted(e, durationMs)
        statusView.text = if (deleted.isEmpty()) "没裁掉任何东西（选区就是全片）" else "已裁成选区，点导出"
    }

    private fun addDeleted(s0: Long, e0: Long) {
        val s = s0.coerceIn(0L, durationMs)
        val e = e0.coerceIn(0L, durationMs)
        if (e <= s) return
        val all = ArrayList<LongRange>(deleted)
        all.add(s..e)
        all.sortBy { it.first }
        val merged = ArrayList<LongRange>()
        for (r in all) {
            if (merged.isEmpty()) {
                merged.add(r)
                continue
            }
            val last = merged[merged.size - 1]
            if (r.first <= last.last) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
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

    private fun keptSegments(): List<LongRange> {
        if (durationMs <= 0L) return emptyList()
        if (deleted.isEmpty()) return listOf(0L..durationMs)
        val kept = ArrayList<LongRange>()
        var cur = 0L
        for (r in deleted.sortedBy { it.first }) {
            if (r.first > cur) kept.add(cur..r.first)
            cur = maxOf(cur, r.last)
        }
        if (cur < durationMs) kept.add(cur..durationMs)
        return kept.filter { it.last - it.first >= 50L }
    }

    private fun updateDelText() {
        if (durationMs <= 0L) return
        if (deleted.isEmpty()) {
            delView.text = "没有标记删除的片段"
            return
        }
        val kept = keptSegments()
        val delMs = deleted.sumOf { it.last - it.first }
        val keptMs = kept.sumOf { it.last - it.first }
        delView.text = "已标记删除 ${deleted.size} 段（共 ${fmtDur(delMs)}）\n" +
            "导出后剩 ${kept.size} 段，共 ${fmtDur(keptMs)}"
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
        val totalMs = kept.sumOf { it.last - it.first }
        // 长素材或多段拼接（filter_complex 重编码）都按长任务提醒
        val longTask = LongTask.isLong(totalMs) || kept.size > 1
        busy = true
        setActionsEnabled(false)
        showProgress(true)
        lastPct = -1
        setProgress(0f, "准备导出…")
        statusView.text = if (deleted.isEmpty()) "导出整段…" else "拼接导出中…"
        if (longTask) LongTask.start(this)

        Thread {
            val msg = try {
                doExport(src, kept, totalMs)
            } catch (t: Throwable) {
                "⚠️ " + (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                busy = false
                LongTask.stop(this@AudioEditorActivity)
                setActionsEnabled(true)
                showProgress(false)
                statusView.text = msg
                // 导出成功的出口弹「查看」目录引导
                val saved = Output.takeLastSaved()
                if (saved != null && msg.startsWith("✅")) Output.promptSaved(this@AudioEditorActivity, saved)
            }
        }.start()
    }

    private fun doExport(src: File, kept: List<LongRange>, totalMs: Long): String {
        val ext = src.name.substringAfterLast('.', "").lowercase(Locale.US)
        val codec = when (ext) {
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
            "flac" -> listOf("-c:a", "flac")
            "wav" -> listOf("-c:a", "pcm_s16le")
            else -> listOf("-c:a", "aac", "-b:a", "192k", "-strict", "-2")
        }
        val outExt = when (ext) {
            "mp3" -> "mp3"
            "flac" -> "flac"
            "wav" -> "wav"
            else -> "m4a"
        }
        val mime = when (outExt) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }

        val base = sanitizeBase(src.name)
        val out = File(cacheDir, "${base}_剪辑.$outExt")
        if (out.exists()) out.delete()

        val args = ArrayList<String>()
        args.add("-y")
        if (kept.size == 1) {
            val r = kept[0]
            if (r.first > 0L) {
                args.add("-ss")
                args.add(sec(r.first))
            }
            args.add("-i")
            args.add(src.absolutePath)
            if (r.last < durationMs) {
                args.add("-t")
                args.add(sec(r.last - r.first))
            }
        } else {
            args.add("-i")
            args.add(src.absolutePath)
            val sb = StringBuilder()
            kept.forEachIndexed { i, r ->
                sb.append("[0:a]atrim=start=").append(sec(r.first))
                    .append(":end=").append(sec(r.last))
                    .append(",asetpts=PTS-STARTPTS[a").append(i).append("];")
            }
            kept.indices.forEach { sb.append("[a").append(it).append("]") }
            sb.append("concat=n=").append(kept.size).append(":v=0:a=1[out]")
            args.add("-filter_complex")
            args.add(sb.toString())
            args.add("-map")
            args.add("[out]")
        }
        args.add("-vn")
        args.addAll(codec)
        args.add(out.absolutePath)

        val err = Ffmpeg.run(this, args, totalMs * 1000L) { setProgress(it * 0.9f, "导出中…") }
        if (err != null) throw IllegalStateException(err)
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("输出文件是空的")

        setProgress(0.95f, "保存中…")
        val size = out.length()
        val saved = Output.publish(this, out, out.name, mime)
        out.delete()
        setProgress(1f, "100%")
        return "✅ 已保存\n$saved\n大小 ${humanSize(size)}"
    }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    // ---------------- 杂项 ----------------

    private fun sanitizeBase(name: String): String {
        val noExt = if (name.contains('.')) name.substringBeforeLast('.') else name
        val cleaned = noExt.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
        return (cleaned.ifEmpty { "音频" }).take(60)
    }

    /** 源文件大小（拿不到就返回 0，只影响长任务提示，不影响功能） */
    private fun sizeOf(u: Uri): Long {
        return try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
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

    /** 0:12.3 */
    private fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val totalSec = t / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val tenth = (t % 1000) / 100
        return String.format(Locale.US, "%d:%02d.%d", m, s, tenth)
    }

    /** 12.3秒 / 1分05秒 */
    private fun fmtDur(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        if (t < 60_000L) return String.format(Locale.US, "%.1f秒", t / 1000.0)
        val totalSec = t / 1000
        return String.format(Locale.US, "%d分%02d秒", totalSec / 60, totalSec % 60)
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
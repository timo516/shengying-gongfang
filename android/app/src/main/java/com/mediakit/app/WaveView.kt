package com.mediakit.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * 音频时间轴：波形 + 双滑块选区 + 已删除片段标记 + 播放头。
 * 纯 Canvas 手绘，不依赖任何第三方库。
 *
 * 交互：
 *  - 按住左/右手柄（±30dp）→ 拖动该边界
 *  - 按在选区内部 → 整体平移选区
 *  - 按在选区外 → 移动播放头（回调 onSeek）
 */
class WaveView(context: Context) : View(context) {

    var durationMs: Long = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** 波形包络，每项 0f..1f（已做 sqrt 提亮，画时上下对称） */
    var peaks: FloatArray = FloatArray(0)
        set(v) {
            field = v
            invalidate()
        }

    var selStartMs: Long = 0L
        set(v) {
            field = if (durationMs > 0) v.coerceIn(0L, durationMs) else v.coerceAtLeast(0L)
            invalidate()
        }

    var selEndMs: Long = 0L
        set(v) {
            field = if (durationMs > 0) v.coerceIn(0L, durationMs) else v.coerceAtLeast(0L)
            invalidate()
        }

    var playheadMs: Long = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** 已标记删除的区间（毫秒） */
    var deleted: List<LongRange> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    var onSeek: ((Long) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val inset = 14f * d
    private val handleHalf = 9f * d
    private val grab = 30f * d
    private val minSelMs = 300L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.wave_bg) }
    private val wavePaint = Paint().apply { color = context.getColor(R.color.wave_line) }
    private val waveSelPaint = Paint().apply { color = context.getColor(R.color.wave_sel) }
    private val delPaint = Paint().apply { color = context.getColor(R.color.wave_del) }
    private val selFillPaint = Paint().apply { color = context.getColor(R.color.wave_sel_fill) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.wave_sel) }
    private val gripPaint = Paint().apply {
        color = context.getColor(R.color.wave_grip)
        strokeWidth = 1.6f * d
    }
    private val playPaint = Paint().apply {
        color = context.getColor(R.color.wave_playhead)
        strokeWidth = 2f * d
    }
    private val rect = RectF()

    private var mode = 0 // 0=无 1=起点 2=终点 3=整体平移 4=播放头
    private var pressX = 0f
    private var pressStartMs = 0L
    private var pressEndMs = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val left = inset
        val right = w - inset
        val cw = right - left
        if (cw <= 0f) return
        val dur = durationMs

        rect.set(left, 0f, right, h)
        canvas.drawRoundRect(rect, 8f * d, 8f * d, bgPaint)

        if (dur > 0 && peaks.isNotEmpty()) {
            val mid = h / 2f
            val amp = h / 2f - 4f * d
            val step = 2f * d
            var x = left
            while (x < right) {
                val idx = (((x - left) / cw) * peaks.size).toInt().coerceIn(0, peaks.size - 1)
                val p = peaks[idx].coerceIn(0f, 1f)
                val half = max(1f * d, p * amp)
                val ms = (dur * ((x - left) / cw)).toLong()
                val inSel = ms >= selStartMs && ms <= selEndMs
                canvas.drawLine(x, mid - half, x, mid + half, if (inSel) waveSelPaint else wavePaint)
                x += step
            }
        } else {
            canvas.drawLine(left, h / 2f, right, h / 2f, wavePaint)
        }

        if (dur > 0) {
            for (r in deleted) {
                val x1 = left + cw * (r.first.toFloat() / dur)
                val x2 = left + cw * (r.last.toFloat() / dur)
                canvas.drawRect(x1, 0f, x2, h, delPaint)
            }
            val x1 = left + cw * (selStartMs.toFloat() / dur)
            val x2 = left + cw * (selEndMs.toFloat() / dur)
            canvas.drawRect(x1, 0f, x2, h, selFillPaint)

            drawHandle(canvas, x1, h)
            drawHandle(canvas, x2, h)

            val px = left + cw * (playheadMs.toFloat() / dur)
            canvas.drawLine(px, 0f, px, h, playPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, h: Float) {
        rect.set(x - handleHalf, 0f, x + handleHalf, h)
        canvas.drawRoundRect(rect, handleHalf, handleHalf, handlePaint)
        val cy = h / 2f
        canvas.drawLine(x - 4f * d, cy - 8f * d, x + 4f * d, cy - 8f * d, gripPaint)
        canvas.drawLine(x - 4f * d, cy, x + 4f * d, cy, gripPaint)
        canvas.drawLine(x - 4f * d, cy + 8f * d, x + 4f * d, cy + 8f * d, gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        val left = inset
        val right = (width - inset).toFloat()
        val cw = right - left
        if (cw <= 0f) return false
        val dur = durationMs

        fun msOf(x: Float): Long = (((x - left) / cw).coerceIn(0f, 1f) * dur).toLong()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val x1 = left + cw * (selStartMs.toFloat() / dur)
                val x2 = left + cw * (selEndMs.toFloat() / dur)
                val d1 = abs(x - x1)
                val d2 = abs(x - x2)
                mode = when {
                    d1 <= grab && d1 <= d2 -> 1
                    d2 <= grab -> 2
                    x > x1 && x < x2 -> 3
                    else -> 4
                }
                pressX = x
                pressStartMs = selStartMs
                pressEndMs = selEndMs
                if (mode == 4) {
                    val ms = msOf(x)
                    playheadMs = ms
                    onSeek?.invoke(ms)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                when (mode) {
                    1 -> {
                        val maxStart = (selEndMs - minSelMs).coerceAtLeast(0L)
                        selStartMs = msOf(x).coerceIn(0L, maxStart)
                        onSelectionChanged?.invoke()
                    }
                    2 -> {
                        val minEnd = (selStartMs + minSelMs).coerceAtMost(dur)
                        selEndMs = msOf(x).coerceIn(minEnd, dur)
                        onSelectionChanged?.invoke()
                    }
                    3 -> {
                        val span = pressEndMs - pressStartMs
                        val deltaMs = ((x - pressX) / cw * dur).toLong()
                        var s = pressStartMs + deltaMs
                        if (s < 0L) s = 0L
                        if (s + span > dur) s = dur - span
                        if (s < 0L) s = 0L
                        selStartMs = s
                        selEndMs = s + span
                        onSelectionChanged?.invoke()
                    }
                    4 -> {
                        val ms = msOf(x)
                        playheadMs = ms
                        onSeek?.invoke(ms)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

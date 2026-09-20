package com.rokidmirror.sender.app.session

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * Synthetic test source (spec "Integration tests"): colour bars, a moving grid, a frame
 * counter, a monotonic timestamp and an alternating black/white square, drawn straight into
 * the encoder input surface with a hardware canvas. Lets the receiver be developed and soak
 * tested without MediaProjection.
 */
class SyntheticSource(private val surface: Surface, private val width: Int, private val height: Int, private val fps: Int) {
    private companion object { const val TAG = "Synthetic" }

    @Volatile private var running = false
    private var thread: Thread? = null
    private val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = height / 12f; isFakeBoldText = true }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = height / 28f }

    fun start() {
        running = true
        thread = Thread({ loop() }, "synthetic-source").apply { start() }
        MirrorLog.i(TAG, "started", "w" to width, "h" to height, "fps" to fps)
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val frameNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        var frame = 0L
        while (running) {
            val canvas: Canvas = try { surface.lockHardwareCanvas() } catch (e: Exception) { MirrorLog.w(TAG, "lock_failed", "error" to e.message); break }
            try { draw(canvas, frame) } finally { runCatching { surface.unlockCanvasAndPost(canvas) } }
            frame++
            next += frameNs
            val sleepNs = next - System.nanoTime()
            if (sleepNs > 0) SystemClock.sleep(sleepNs / 1_000_000) else next = System.nanoTime()
        }
    }

    private fun draw(c: Canvas, frame: Long) {
        c.drawColor(Color.DKGRAY)
        // colour bars (top third)
        val barW = width / bars.size.toFloat()
        for ((i, col) in bars.withIndex()) { paint.color = col; c.drawRect(i * barW, 0f, (i + 1) * barW, height / 3f, paint) }
        // moving grid (middle third)
        paint.color = Color.LTGRAY; paint.strokeWidth = 2f
        val step = width / 12f
        val offset = (frame * 4 % step.toInt()).toFloat()
        var x = -offset
        while (x < width) { c.drawLine(x, height / 3f, x, 2 * height / 3f, paint); x += step }
        var y = height / 3f + offset
        while (y < 2 * height / 3f) { c.drawLine(0f, y, width.toFloat(), y, paint); y += step }
        // alternating black/white square (latency marker) and frame counter (bottom third)
        paint.color = if (frame % 2 == 0L) Color.WHITE else Color.BLACK
        val sq = height / 5f
        c.drawRect(Rect(0, (2 * height / 3f).toInt(), sq.toInt(), (2 * height / 3f + sq).toInt()), paint)
        c.drawText("F %06d".format(frame), sq + 24f, 2 * height / 3f + textPaint.textSize, textPaint)
        c.drawText("t=%d ms".format(System.nanoTime() / 1_000_000), sq + 24f, 2 * height / 3f + textPaint.textSize * 2, smallPaint)
        c.drawText("Rokid Mirror synthetic %dx%d@%d".format(width, height, fps), sq + 24f, 2 * height / 3f + textPaint.textSize * 2.6f, smallPaint)
    }
}

package com.rokidmirror.sender.app.session

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Surface
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlin.random.Random

/** Which picture a synthetic source draws. */
enum class SyntheticPattern(val label: String, val sourceName: String) {
    /** Colour bars, moving grid, frame counter, timestamp and a black/white latency marker. */
    TEST_PATTERN("Test pattern", "Test pattern"),

    /** Falling glyph columns; a readable demo that suits the green monochrome panel. */
    MATRIX_RAIN("Matrix rain", "Matrix rain"),
}

/**
 * Drives the encoder from a generated picture instead of the screen (spec "Integration tests"),
 * so the receiver and the network path can be exercised without MediaProjection consent.
 *
 * Frames go through [EglCanvasSurface]: a codec input surface cannot be painted with a locked
 * Canvas, which is what made the earlier version fail on device.
 */
class SyntheticSource(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val pattern: SyntheticPattern,
) {
    private companion object { const val TAG = "Synthetic" }

    private val painter: Painter = when (pattern) {
        SyntheticPattern.TEST_PATTERN -> TestPatternPainter(width, height)
        SyntheticPattern.MATRIX_RAIN -> MatrixRainPainter(width, height)
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "synthetic-source").apply { start() }
        MirrorLog.i(TAG, "started", "pattern" to pattern.name, "w" to width, "h" to height, "fps" to fps)
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        // An EGL context belongs to the thread that made it current. Creating it anywhere but
        // here left it bound to the calling thread, so every eglMakeCurrent on this thread
        // failed with BAD_ACCESS, no buffers ever reached the encoder and the stream was empty.
        val egl = try {
            EglCanvasSurface(output, width, height)
        } catch (e: Throwable) {
            MirrorLog.e(TAG, "egl_setup_failed", e)
            return
        }
        val frameNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        var frame = 0L
        try {
            while (running) {
                val now = System.nanoTime()
                egl.drawFrame(now) { canvas -> painter.paint(canvas, frame, now) }
                frame++
                next += frameNs
                val sleepNs = next - System.nanoTime()
                if (sleepNs > 0) SystemClock.sleep(sleepNs / 1_000_000) else next = System.nanoTime()
            }
        } catch (e: Throwable) {
            MirrorLog.e(TAG, "render_loop_failed", e, "frame" to frame)
        } finally {
            runCatching { egl.release() }
            MirrorLog.i(TAG, "stopped", "frames" to frame)
        }
    }

    private interface Painter {
        fun paint(canvas: Canvas, frame: Long, nowNs: Long)
    }

    /** Colour bars, a scrolling grid, a frame counter and an alternating latency marker. */
    private class TestPatternPainter(private val width: Int, private val height: Int) : Painter {
        private val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE, Color.DKGRAY)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = height / 12f; isFakeBoldText = true }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = height / 28f }

        override fun paint(canvas: Canvas, frame: Long, nowNs: Long) {
            canvas.drawColor(Color.DKGRAY)
            val barWidth = width / bars.size.toFloat()
            for ((i, colour) in bars.withIndex()) {
                paint.color = colour
                canvas.drawRect(i * barWidth, 0f, (i + 1) * barWidth, height / 3f, paint)
            }
            paint.color = Color.LTGRAY
            paint.strokeWidth = 2f
            val step = width / 12f
            val offset = (frame * 4 % step.toInt()).toFloat()
            var x = -offset
            while (x < width) { canvas.drawLine(x, height / 3f, x, 2 * height / 3f, paint); x += step }
            var y = height / 3f + offset
            while (y < 2 * height / 3f) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += step }

            paint.color = if (frame % 2 == 0L) Color.WHITE else Color.BLACK
            val square = height / 5f
            canvas.drawRect(0f, 2 * height / 3f, square, 2 * height / 3f + square, paint)
            val textLeft = square + 24f
            canvas.drawText("F %06d".format(frame), textLeft, 2 * height / 3f + big.textSize, big)
            canvas.drawText("t=%d ms".format(nowNs / 1_000_000), textLeft, 2 * height / 3f + big.textSize * 2, small)
            canvas.drawText("Rokid Mirror %dx%d".format(width, height), textLeft, 2 * height / 3f + big.textSize * 2.6f, small)
        }
    }

    /**
     * Character rain. Each column holds a trail of glyphs that falls at its own speed; the
     * leading glyph is white and the trail fades to dark green, and glyphs mutate as they fall.
     */
    private class MatrixRainPainter(private val width: Int, private val height: Int) : Painter {
        private val glyphs = buildString {
            for (c in 'ｦ'..'ﾟ') append(c)      // half-width katakana, the classic look
            append("0123456789")
            append(":.=*+-<>¦|╌")
        }
        private val random = Random(7)
        private val cellSize = (height / 26f).coerceAtLeast(14f)
        private val columns = (width / cellSize).toInt().coerceAtLeast(1)
        private val rows = (height / cellSize).toInt().coerceAtLeast(1)
        private val trail = 14

        /** Head position of each column, in rows, and its speed in rows per frame. */
        private val head = FloatArray(columns) { -random.nextFloat() * rows }
        private val speed = FloatArray(columns) { 0.25f + random.nextFloat() * 0.75f }
        private val chars = Array(columns) { CharArray(rows + trail + 1) { glyphs[random.nextInt(glyphs.length)] } }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cellSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        private val buffer = CharArray(1)

        override fun paint(canvas: Canvas, frame: Long, nowNs: Long) {
            canvas.drawColor(Color.BLACK)
            for (column in 0 until columns) {
                val x = column * cellSize + cellSize / 2f
                head[column] += speed[column]
                if (head[column] - trail > rows) {
                    head[column] = -random.nextFloat() * 6f
                    speed[column] = 0.25f + random.nextFloat() * 0.75f
                }
                val headRow = head[column].toInt()
                for (offset in 0 until trail) {
                    val row = headRow - offset
                    if (row < 0 || row > rows) continue
                    // Mutate a glyph occasionally so the trail shimmers.
                    if (random.nextInt(64) == 0) chars[column][row.coerceIn(0, chars[column].size - 1)] = glyphs[random.nextInt(glyphs.length)]
                    val fade = 1f - offset / trail.toFloat()
                    paint.color = when (offset) {
                        0 -> Color.WHITE
                        1 -> Color.rgb(190, 255, 190)
                        else -> Color.rgb((40 * fade).toInt(), (90 + 165 * fade).toInt().coerceAtMost(255), (40 * fade).toInt())
                    }
                    buffer[0] = chars[column][row.coerceIn(0, chars[column].size - 1)]
                    canvas.drawText(buffer, 0, 1, x, row * cellSize + cellSize, paint)
                }
            }
        }
    }
}

package com.rokidmirror.receiver.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal see-through friendly overlay: status (top), pairing code (center), warning (bottom),
 * optional debug stats. Text stays legible regardless of mirrored content scale. Colours favour
 * the green monochrome Micro-LED panel (bright text, no dark fills).
 */
class OverlayView(context: Context) : FrameLayout(context) {
    private val status = text(16f, Gravity.TOP or Gravity.START)
    private val code = text(44f, Gravity.CENTER).apply { typeface = Typeface.MONOSPACE; visibility = GONE }
    private val hint = text(14f, Gravity.CENTER or Gravity.BOTTOM).apply { visibility = GONE }
    private val warning = text(14f, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { visibility = GONE }
    /**
     * Debug stats sit directly above the info line in one column. They used to be pinned to
     * opposite bottom corners, and on a 480 px panel both lines are wider than half the screen,
     * so they were drawn over each other and neither was readable.
     */
    private val bottomStack = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this@OverlayView.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }
    private val debug = text(11f, Gravity.START, bottomStack).apply { typeface = Typeface.MONOSPACE; visibility = GONE }
    /** Always visible: proves the app is alive and carries the facts the M0 audit needs. */
    private val info = text(10f, Gravity.START, bottomStack)
    private val indicator = View(context).apply { setBackgroundColor(Color.WHITE); visibility = GONE }
    private val cursor = CursorView(context).apply { visibility = GONE }
    /** Locally drawn grey steps, shown only while idle; see [setTestCard]. */
    private val testCard = GreyRampView(context).apply { visibility = GONE }

    var debugEnabled: Boolean = false
        set(value) { field = value; if (!value) debug.visibility = GONE }

    /**
     * Status, info and hint text. Hidden during steady streaming so the mirrored image is not
     * competing with a heads-up display; warnings, the pairing code and the active-mirroring
     * indicator are never hidden.
     */
    var chromeVisible: Boolean = true
        set(value) {
            field = value
            post {
                val v = if (value) VISIBLE else GONE
                status.visibility = v
                info.visibility = v
                if (hint.text.isNullOrBlank()) hint.visibility = GONE else hint.visibility = v
                if (!value) debug.visibility = GONE else if (debugEnabled && !debug.text.isNullOrBlank()) debug.visibility = VISIBLE
            }
        }

    init {
        // Always-visible "mirroring active" indicator (spec security requirement).
        addView(indicator, LayoutParams(10, 10, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) })
        addView(testCard, LayoutParams(LayoutParams.MATCH_PARENT, TEST_CARD_HEIGHT, Gravity.CENTER))
        addView(cursor, LayoutParams(CURSOR_SIZE, CURSOR_SIZE))
    }

    /**
     * Remote cursor for mouse mode, positioned in display pixels. Null hides it. Drawn in the
     * overlay rather than mirrored from the phone, so it costs no bandwidth and stays crisp at
     * any zoom level.
     */
    fun setPointer(x: Float?, y: Float?, pressed: Boolean) = post {
        if (x == null || y == null) {
            cursor.visibility = GONE
        } else {
            val lp = cursor.layoutParams as LayoutParams
            lp.leftMargin = (x - CURSOR_SIZE / 2).toInt()
            lp.topMargin = (y - CURSOR_SIZE / 2).toInt()
            cursor.layoutParams = lp
            cursor.pressed(pressed)
            cursor.visibility = VISIBLE
        }
    }

    private fun text(sizeSp: Float, gravity: Int, column: LinearLayout? = null) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = sizeSp; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        if (column == null) {
            setPadding(12, 12, 12, 12)
            this@OverlayView.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
        } else {
            setPadding(12, 2, 12, 2)
            this.gravity = gravity
            column.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        const val CURSOR_SIZE = 36
        const val TEST_CARD_HEIGHT = 44
    }

    /** Five steps from black to white, plus a smooth gradient, drawn locally. */
    private class GreyRampView(context: Context) : View(context) {
        private val paint = Paint()
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f }

        override fun onDraw(canvas: Canvas) {
            val steps = intArrayOf(0, 64, 128, 192, 255)
            val bandWidth = width / steps.size.toFloat()
            val half = height / 2f
            steps.forEachIndexed { i, v ->
                paint.color = Color.rgb(v, v, v)
                canvas.drawRect(i * bandWidth, 0f, (i + 1) * bandWidth, half, paint)
            }
            // A continuous ramp underneath: banding or speckle here is the clearest tell.
            for (x in 0 until width) {
                val v = (x * 255 / width.coerceAtLeast(1)).coerceIn(0, 255)
                paint.color = Color.rgb(v, v, v)
                canvas.drawRect(x.toFloat(), half, x + 1f, height.toFloat(), paint)
            }
            canvas.drawText("grey test card (drawn on the glasses)", 4f, half - 3f, label)
        }
    }

    /** A crosshair with a bright core: legible on a monochrome see-through panel. */
    private class CursorView(context: Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var down = false

        fun pressed(value: Boolean) { if (down != value) { down = value; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - 3f
            for (p in listOf(shadow, stroke)) {
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawLine(cx - r, cy, cx - r / 2.5f, cy, p)
                canvas.drawLine(cx + r / 2.5f, cy, cx + r, cy, p)
                canvas.drawLine(cx, cy - r, cx, cy - r / 2.5f, p)
                canvas.drawLine(cx, cy + r / 2.5f, cx, cy + r, p)
            }
            canvas.drawCircle(cx, cy, if (down) r / 2f else 2.5f, fill)
        }
    }

    fun setStatus(s: String) = post { status.text = s; if (chromeVisible) status.visibility = VISIBLE }
    fun setInfo(s: String) = post { info.text = s; if (chromeVisible) info.visibility = VISIBLE }
    /**
     * Also drives the grey test card: it appears only while idle, so it can never cover a stream.
     * The card is drawn by the glasses with no video in the path, which separates a panel that
     * dithers mid-greys (card speckled) from grain introduced by the video pipeline (card smooth).
     */
    fun setStreamingIndicator(on: Boolean) = post {
        indicator.visibility = if (on) VISIBLE else GONE
        testCard.visibility = if (on) GONE else VISIBLE
    }
    fun showPairingCode(formatted: String?) = post {
        code.text = formatted ?: ""
        code.visibility = if (formatted != null) VISIBLE else GONE
        hint.text = if (formatted != null) "Enter this code on the phone" else ""
        hint.visibility = code.visibility
    }
    fun setWarning(w: String?) = post { warning.text = w ?: ""; warning.visibility = if (w != null) VISIBLE else GONE }
    fun setDebug(d: String?) = post { if (debugEnabled) { debug.text = d ?: ""; debug.visibility = if (d != null && chromeVisible) VISIBLE else GONE } }
    fun setHint(h: String?) = post { if (code.visibility != VISIBLE) { hint.text = h ?: ""; hint.visibility = if (h != null && chromeVisible) VISIBLE else GONE } }
}

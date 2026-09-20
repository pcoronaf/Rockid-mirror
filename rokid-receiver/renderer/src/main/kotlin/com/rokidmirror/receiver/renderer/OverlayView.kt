package com.rokidmirror.receiver.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
    /** Always visible: proves the app is alive and carries the facts the M0 audit needs. */
    private val info = text(10f, Gravity.BOTTOM or Gravity.START)
    private val debug = text(11f, Gravity.BOTTOM or Gravity.END).apply { typeface = Typeface.MONOSPACE; visibility = GONE }
    private val indicator = View(context).apply { setBackgroundColor(Color.WHITE); visibility = GONE }
    private val cursor = CursorView(context).apply { visibility = GONE }

    var debugEnabled: Boolean = false
        set(value) { field = value; if (!value) debug.visibility = GONE }

    init {
        // Always-visible "mirroring active" indicator (spec security requirement).
        addView(indicator, LayoutParams(10, 10, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) })
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

    private fun text(sizeSp: Float, gravity: Int) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = sizeSp; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        setPadding(12, 12, 12, 12)
        addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
    }

    private companion object { const val CURSOR_SIZE = 36 }

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

    fun setStatus(s: String) = post { status.text = s }
    fun setInfo(s: String) = post { info.text = s }
    fun setStreamingIndicator(on: Boolean) = post { indicator.visibility = if (on) VISIBLE else GONE }
    fun showPairingCode(formatted: String?) = post {
        code.text = formatted ?: ""
        code.visibility = if (formatted != null) VISIBLE else GONE
        hint.text = if (formatted != null) "Enter this code on the phone" else ""
        hint.visibility = code.visibility
    }
    fun setWarning(w: String?) = post { warning.text = w ?: ""; warning.visibility = if (w != null) VISIBLE else GONE }
    fun setDebug(d: String?) = post { if (debugEnabled) { debug.text = d ?: ""; debug.visibility = if (d != null) VISIBLE else GONE } }
    fun setHint(h: String?) = post { if (code.visibility != VISIBLE) { hint.text = h ?: ""; hint.visibility = if (h != null) VISIBLE else GONE } }
}

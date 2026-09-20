package com.rokidmirror.receiver.renderer

import android.content.Context
import android.graphics.Color
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
    private val warning = text(14f, Gravity.BOTTOM or Gravity.START).apply { visibility = GONE }
    private val debug = text(11f, Gravity.BOTTOM or Gravity.END).apply { typeface = Typeface.MONOSPACE; visibility = GONE }
    private val indicator = View(context).apply { setBackgroundColor(Color.WHITE); visibility = GONE }

    var debugEnabled: Boolean = false
        set(value) { field = value; if (!value) debug.visibility = GONE }

    init {
        // Always-visible "mirroring active" indicator (spec security requirement).
        addView(indicator, LayoutParams(10, 10, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) })
    }

    private fun text(sizeSp: Float, gravity: Int) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = sizeSp; setShadowLayer(4f, 0f, 0f, Color.BLACK)
        setPadding(12, 12, 12, 12)
        addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
    }

    fun setStatus(s: String) = post { status.text = s }
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

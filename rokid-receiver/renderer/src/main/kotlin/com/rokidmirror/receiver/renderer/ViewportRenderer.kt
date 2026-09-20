package com.rokidmirror.receiver.renderer

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.rokidmirror.protocol.viewport.Placement
import com.rokidmirror.protocol.viewport.ViewportMath
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.platform.DisplayInfo
import com.rokidmirror.receiver.platform.RenderTarget
import com.rokidmirror.receiver.telemetry.ReceiverLog
import kotlin.math.roundToInt

/**
 * Zero-copy viewport: the decoder renders the full frame into the SurfaceView, and zoom/pan
 * are realised by laying the SurfaceView out larger than the display and offsetting it.
 * SurfaceFlinger/HWC does the scaling and crops to the display, so no extra GPU pass runs on
 * the glasses. Transitions are immediate (spec MVP). Fallback if the device rejects oversized
 * surfaces: a TextureView with a matrix (not needed so far; see docs/architecture.md).
 */
class ViewportRenderer(private val host: FrameLayout, target: RenderTarget, display: DisplayInfo) {
    private companion object { const val TAG = "Renderer" }

    val surfaceView: SurfaceView = target.surfaceView
    var displayWidth = display.width; private set
    var displayHeight = display.height; private set
    var sourceWidth = 0; private set
    var sourceHeight = 0; private set
    var viewport: ViewportState = ViewportState(); private set
    var onSurfaceReady: ((SurfaceHolder) -> Unit)? = null
    var onSurfaceLost: (() -> Unit)? = null
    var lastPlacement: Placement? = null; private set

    init {
        host.addView(surfaceView, FrameLayout.LayoutParams(display.width, display.height))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { onSurfaceReady?.invoke(holder) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { onSurfaceLost?.invoke() }
        })
    }

    fun setDisplaySize(width: Int, height: Int) { displayWidth = width; displayHeight = height; apply() }

    /** New source geometry (STREAM_FORMAT); recenters as the specification requires. */
    fun setSource(width: Int, height: Int) {
        if (width == sourceWidth && height == sourceHeight) return
        sourceWidth = width; sourceHeight = height
        viewport = ViewportMath.onSourceChanged(viewport)
        apply()
    }

    fun setViewport(state: ViewportState) { viewport = state; apply() }

    private fun apply() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val p = ViewportMath.placement(viewport, sourceWidth, sourceHeight, displayWidth, displayHeight)
        lastPlacement = p
        host.post {
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            lp.width = p.width.roundToInt().coerceAtLeast(1)
            lp.height = p.height.roundToInt().coerceAtLeast(1)
            lp.leftMargin = p.left.roundToInt()
            lp.topMargin = p.top.roundToInt()
            surfaceView.layoutParams = lp
        }
        ReceiverLog.d(TAG, "placement", "mode" to viewport.fitMode, "scale" to viewport.scale, "w" to p.width.toInt(), "h" to p.height.toInt(), "l" to p.left.toInt(), "t" to p.top.toInt())
    }
}

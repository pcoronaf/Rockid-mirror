package com.rokidmirror.sender.capture

import android.content.Intent
import android.view.Surface
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Events that need a reaction from the session coordinator. */
sealed class CaptureEvent {
    /** Android reported new captured-content dimensions (selected-app resize, rotation). */
    data class ContentResized(val width: Int, val height: Int) : CaptureEvent()
    data class VisibilityChanged(val visible: Boolean) : CaptureEvent()
    /** The projection ended (user stopped it from system UI, lock screen, or an error). */
    data class Stopped(val reason: String) : CaptureEvent()
}

/**
 * Screen capture boundary from the specification. Implementations must obtain fresh consent
 * per session and never reuse a MediaProjection token for a second VirtualDisplay.
 */
interface CaptureController {
    val state: StateFlow<CaptureState>
    val events: SharedFlow<CaptureEvent>

    /** Builds the consent intent. Must not cache a previous result. */
    suspend fun prepareCapture(mode: CaptureMode = CaptureMode.USER_CHOICE): CaptureRequest

    /**
     * Starts capture into [sink]. Must be called from a foreground service of type
     * mediaProjection (Android 14+ requirement) with the activity result of [prepareCapture].
     */
    suspend fun start(resultCode: Int, data: Intent, sink: Surface, target: CaptureGeometry): CaptureSession

    /** Resizes the existing VirtualDisplay; never creates a second one. */
    suspend fun resize(width: Int, height: Int, densityDpi: Int)

    /** Swaps the output surface (encoder restart) while keeping the projection alive. */
    suspend fun setSurface(sink: Surface?)

    suspend fun stop()
}

package com.rokidmirror.sender.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MediaProjection implementation of [CaptureController].
 *
 * Android 14+ rules honoured here (docs: developer.android.com/media/grow/media-projection):
 *  - a projection token is used for exactly one VirtualDisplay; a new session needs new consent;
 *  - the callback is registered before `createVirtualDisplay`;
 *  - size changes resize the existing VirtualDisplay or swap its surface, never re-create it;
 *  - `onStop` releases everything immediately (lock screen, user stop, protected content).
 */
class MediaProjectionCaptureController(private val context: Context) : CaptureController {
    private companion object { const val TAG = "Capture" }

    private val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val thread = HandlerThread("capture-callbacks").apply { start() }
    private val handler = Handler(thread.looper)

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var sourceGeometry: CaptureGeometry? = null
    private var displayGeometry: CaptureGeometry? = null

    override suspend fun prepareCapture(mode: CaptureMode): CaptureRequest {
        _state.value = CaptureState.AwaitingPermission
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = when (mode) {
                CaptureMode.WHOLE_DISPLAY -> MediaProjectionConfig.createConfigForDefaultDisplay()
                CaptureMode.USER_CHOICE -> MediaProjectionConfig.createConfigForUserChoice()
            }
            CaptureRequest(manager.createScreenCaptureIntent(config), singleAppChoiceOffered = mode == CaptureMode.USER_CHOICE)
        } else {
            CaptureRequest(manager.createScreenCaptureIntent(), singleAppChoiceOffered = false)
        }
    }

    override suspend fun start(resultCode: Int, data: Intent, sink: Surface, target: CaptureGeometry): CaptureSession =
        suspendCancellableCoroutine { cont ->
            handler.post {
                try {
                    check(projection == null) { "capture already active; stop() first" }
                    val mp = manager.getMediaProjection(resultCode, data)
                        ?: throw MirrorException(ErrorCode.CAPTURE_PERMISSION_DENIED, "getMediaProjection returned null")
                    mp.registerCallback(callback, handler)
                    projection = mp
                    val vd = mp.createVirtualDisplay(
                        "RokidMirror",
                        target.width, target.height, target.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        sink,
                        displayCallback,
                        handler,
                    ) ?: throw MirrorException(ErrorCode.CAPTURE_STOPPED, "createVirtualDisplay returned null")
                    virtualDisplay = vd
                    displayGeometry = target
                    sourceGeometry = sourceGeometry ?: target
                    _state.value = CaptureState.Active(sourceGeometry!!, target)
                    MirrorLog.i(TAG, "capture_started", "w" to target.width, "h" to target.height, "dpi" to target.densityDpi)
                    cont.resume(object : CaptureSession { override val geometry: CaptureGeometry get() = displayGeometry ?: target })
                } catch (e: SecurityException) {
                    // Thrown when the token was already used or the FGS of type mediaProjection is not running.
                    releaseInternal("security: ${e.message}")
                    _state.value = CaptureState.Error(ErrorCode.CAPTURE_PERMISSION_DENIED, e.message)
                    cont.resumeWithException(MirrorException(ErrorCode.CAPTURE_PERMISSION_DENIED, e.message, e))
                } catch (e: MirrorException) {
                    releaseInternal(e.message ?: "error")
                    _state.value = CaptureState.Error(e.code, e.details)
                    cont.resumeWithException(e)
                } catch (e: Exception) {
                    releaseInternal("error: ${e.message}")
                    _state.value = CaptureState.Error(ErrorCode.UNKNOWN, e.message)
                    cont.resumeWithException(MirrorException(ErrorCode.UNKNOWN, e.message, e))
                }
            }
        }

    /** Informs the controller of the logical source size (used for STREAM_FORMAT source geometry). */
    fun setSourceGeometry(geometry: CaptureGeometry) {
        sourceGeometry = geometry
        (_state.value as? CaptureState.Active)?.let { _state.value = it.copy(source = geometry) }
    }

    override suspend fun resize(width: Int, height: Int, densityDpi: Int) = suspendCancellableCoroutine { cont ->
        handler.post {
            val vd = virtualDisplay
            if (vd != null) {
                vd.resize(width, height, densityDpi)
                displayGeometry = CaptureGeometry(width, height, densityDpi)
                (_state.value as? CaptureState.Active)?.let { _state.value = it.copy(virtualDisplay = displayGeometry!!) }
                MirrorLog.i(TAG, "virtual_display_resized", "w" to width, "h" to height)
            }
            cont.resume(Unit)
        }
    }

    override suspend fun setSurface(sink: Surface?) = suspendCancellableCoroutine { cont ->
        handler.post {
            virtualDisplay?.surface = sink
            MirrorLog.d(TAG, "virtual_display_surface_swapped", "hasSurface" to (sink != null))
            cont.resume(Unit)
        }
    }

    override suspend fun stop() = suspendCancellableCoroutine { cont ->
        handler.post {
            releaseInternal("stopped by sender")
            _state.value = CaptureState.Idle
            cont.resume(Unit)
        }
    }

    private fun releaseInternal(reason: String) {
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        projection?.let { mp ->
            runCatching { mp.unregisterCallback(callback) }
            runCatching { mp.stop() }
        }
        projection = null
        MirrorLog.i(TAG, "capture_released", "reason" to reason)
    }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Android ended the projection: lock screen, user stopped it, or the app was killed.
            virtualDisplay?.let { runCatching { it.release() } }
            virtualDisplay = null
            projection?.let { runCatching { it.unregisterCallback(this) } }
            projection = null
            _state.value = CaptureState.Stopped("projection stopped by system")
            _events.tryEmit(CaptureEvent.Stopped("projection stopped by system"))
            MirrorLog.i(TAG, "projection_on_stop")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            MirrorLog.i(TAG, "captured_content_resize", "w" to width, "h" to height)
            _events.tryEmit(CaptureEvent.ContentResized(width, height))
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            (_state.value as? CaptureState.Active)?.let { _state.value = it.copy(contentVisible = isVisible) }
            _events.tryEmit(CaptureEvent.VisibilityChanged(isVisible))
        }
    }

    private val displayCallback = object : VirtualDisplay.Callback() {
        override fun onStopped() { MirrorLog.d(TAG, "virtual_display_stopped") }
    }
}

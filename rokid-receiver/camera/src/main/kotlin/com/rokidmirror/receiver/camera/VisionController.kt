package com.rokidmirror.receiver.camera

import android.content.Context
import android.graphics.Bitmap
import com.rokidmirror.receiver.telemetry.ReceiverLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the glasses UI needs to know about vision mode. */
data class VisionStatus(
    val running: Boolean,
    val peopleVisible: Int = 0,
    val fps: Float = 0f,
    val detector: String = "",
    val error: String? = null,
)

/**
 * Vision mode: enhance the camera picture for the glasses panel and bracket the people in it.
 *
 * Everything happens on the glasses. No frame is recorded, stored or transmitted, nobody is
 * recognised, and the device's own camera indicator is left exactly as the platform drives it.
 */
class VisionController(
    context: Context,
    private val view: VisionView,
    private val detectorFactory: () -> PersonDetector = { MlKitPersonDetector() },
    private val onStatus: (VisionStatus) -> Unit,
) {
    private companion object { const val TAG = "Vision" }

    private val detection = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vision-detect") }
    private val detecting = AtomicBoolean(false)
    private var detector: PersonDetector? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var people: List<DetectedPerson> = emptyList()

    /** Extra contrast around mid grey, 1.0 leaves the stretch alone. */
    @Volatile var gain: Float = 1.4f

    private var frames = 0
    private var windowStartNs = 0L
    private var fps = 0f

    private val source = CameraVisionSource(context) { luma, width, height, rowStride, rotation ->
        onFrame(luma, width, height, rowStride, rotation)
    }

    val isRunning: Boolean get() = source.isRunning

    fun start() {
        if (source.isRunning) return
        detector = detector ?: runCatching { detectorFactory() }
            .onFailure { ReceiverLog.e(TAG, "detector_unavailable", it) }
            .getOrNull()
        windowStartNs = System.nanoTime()
        frames = 0
        source.start()
            .onSuccess {
                ReceiverLog.i(TAG, "vision_started", "detector" to (detector?.description ?: "none"))
                onStatus(VisionStatus(running = true, detector = detector?.description ?: "no detector"))
            }
            .onFailure {
                ReceiverLog.w(TAG, "vision_start_failed", "error" to it.message)
                onStatus(VisionStatus(running = false, error = it.message ?: "camera unavailable"))
            }
    }

    fun stop() {
        source.stop()
        view.clear()
        people = emptyList()
        onStatus(VisionStatus(running = false))
    }

    fun release() {
        stop()
        detector?.close(); detector = null
        detection.shutdownNow()
        bitmap?.recycle(); bitmap = null
    }

    fun hasPermission(): Boolean = source.hasPermission()

    private fun onFrame(luma: ByteArray, width: Int, height: Int, rowStride: Int, rotation: Int) {
        if (pixels.size < width * height) pixels = IntArray(width * height)
        ContrastFilter.enhance(luma, width, height, gain = gain, rowStride = rowStride, out = pixels)

        val target = bitmapFor(width, height)
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        view.submit(target, rotation, people)

        // One detection at a time: dropping frames keeps the picture live and the glasses cool.
        val activeDetector = detector
        if (activeDetector != null && detecting.compareAndSet(false, true)) {
            val copy = pixels.copyOf(width * height)
            detection.execute {
                try {
                    people = activeDetector.detect(VisionFrame(copy, width, height, rotation))
                } catch (e: Throwable) {
                    ReceiverLog.w(TAG, "detect_error", "error" to e.message)
                } finally {
                    detecting.set(false)
                }
            }
        }
        countFrame(activeDetector)
    }

    private fun countFrame(activeDetector: PersonDetector?) {
        frames++
        val now = System.nanoTime()
        val elapsed = now - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            fps = frames * 1e9f / elapsed
            frames = 0
            windowStartNs = now
            onStatus(VisionStatus(running = true, peopleVisible = people.size, fps = fps, detector = activeDetector?.description ?: "no detector"))
        }
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val existing = bitmap
        if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) return existing
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }
}

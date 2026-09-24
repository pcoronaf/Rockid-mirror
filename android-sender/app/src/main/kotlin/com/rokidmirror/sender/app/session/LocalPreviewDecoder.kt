package com.rokidmirror.sender.app.session

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.sender.telemetry.MirrorLog
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes the phone's own outgoing stream so the glasses view can show the real picture.
 *
 * The glasses cannot photograph a mirroring session: a decoded frame lives in the decoder's own
 * surface and cannot be read back. But the phone already has every access unit it is sending, so
 * it can decode a second copy locally and apply the same viewport the glasses are using. That
 * shows exactly what the wearer sees, with no extra traffic on the link.
 *
 * It runs only while the glasses view is open.
 */
class LocalPreviewDecoder(private val onError: (String) -> Unit = {}) {
    private companion object { const val TAG = "LocalPreview" }

    private val thread = HandlerThread("local-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val inputBuffers = LinkedBlockingQueue<Int>()
    private val started = AtomicBoolean(false)
    private val awaitingKeyframe = AtomicBoolean(true)
    private var codec: MediaCodec? = null

    val isRunning: Boolean get() = started.get()

    @Synchronized
    fun start(surface: Surface, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        }
        return try {
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.setCallback(callback, handler)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            inputBuffers.clear()
            awaitingKeyframe.set(true)
            started.set(true)
            MirrorLog.i(TAG, "local_preview_started", "w" to width, "h" to height)
            true
        } catch (e: Exception) {
            MirrorLog.e(TAG, "local_preview_failed", e)
            onError(e.message ?: "decoder unavailable")
            stop()
            false
        }
    }

    /** Feeds a unit the transport is already sending. Cheap to call when not running. */
    fun submit(unit: EncodedAccessUnit) {
        if (!started.get()) return
        val decoder = codec ?: return
        // Starting mid-stream is meaningless without a keyframe to build on.
        if (awaitingKeyframe.get()) {
            if (!unit.isKeyframe) return
            awaitingKeyframe.set(false)
        }
        val index = inputBuffers.poll(4, TimeUnit.MILLISECONDS) ?: return
        try {
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(unit.data)
            decoder.queueInputBuffer(index, 0, unit.size, unit.captureTimestampNs / 1000, 0)
        } catch (e: Exception) {
            MirrorLog.w(TAG, "submit_failed", "error" to e.message)
        }
    }

    @Synchronized
    fun stop() {
        started.set(false)
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
        inputBuffers.clear()
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { inputBuffers.offer(index) }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            MirrorLog.e(TAG, "local_preview_error", e)
            if (!e.isTransient) { started.set(false); onError(e.diagnosticInfo) }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            MirrorLog.d(TAG, "local_preview_format", "format" to format)
        }
    }
}

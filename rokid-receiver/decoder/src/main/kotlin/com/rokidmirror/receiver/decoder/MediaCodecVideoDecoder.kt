package com.rokidmirror.receiver.decoder

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.receiver.telemetry.PipelineStats
import com.rokidmirror.receiver.telemetry.ReceiverLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-first H.264 decoder rendering straight to the display Surface (no CPU copy).
 * Every decoded frame is released for rendering immediately: there is no playout buffer, by
 * design (spec: prefer dropping late frames over buffering). Presentation timestamps are the
 * sender's capture timestamps, which are monotonic.
 */
class MediaCodecVideoDecoder(
    private val stats: PipelineStats,
    private val clockNs: () -> Long = System::nanoTime,
    private val onError: (String) -> Unit = {},
) : VideoDecoder {
    private companion object { const val TAG = "Decoder"; const val KEYFRAME_BUFFER_WAIT_MS = 8L }

    private val thread = HandlerThread("video-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val machine = DecoderStateMachine()
    private var codec: MediaCodec? = null
    private val inputBuffers = LinkedBlockingQueue<Int>()
    private val pendingFrameIds = ConcurrentLinkedQueue<Pair<Long, Long>>() // (ptsUs, frameId) submitted, not yet output
    /** pts -> frameId of recently output frames, for the (optional) frame-rendered callback; bounded. */
    private val renderedLookup = object : java.util.LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > 32
    }
    private val configured = AtomicBoolean(false)
    var decoderName: String = ""; private set
    var outputWidth = 0; private set
    var outputHeight = 0; private set

    override val isConfigured: Boolean get() = configured.get()

    @Synchronized
    override fun configure(format: DecoderFormat, surface: Surface): Boolean {
        stopInternal()
        val mime = format.codec.mimeType
        val mf = MediaFormat.createVideoFormat(mime, format.width, format.height).apply {
            format.csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            format.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(format.width, 1280))
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(format.height, 1280))
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mf)
            ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createVideoFormat(mime, format.width, format.height))
        if (name == null) { ReceiverLog.e(TAG, "no_decoder", null, "mime" to mime); machine.onError(); onError("no decoder for $mime"); return false }
        return try {
            val c = MediaCodec.createByCodecName(name)
            c.setCallback(callback, handler)
            try {
                c.configure(mf, surface, null, 0)
            } catch (e: Exception) {
                // Some decoders reject KEY_LOW_LATENCY / MAX_* keys: retry with the minimal format.
                c.reset(); c.setCallback(callback, handler)
                val minimal = MediaFormat.createVideoFormat(mime, format.width, format.height).apply {
                    format.csd0?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                    format.csd1?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
                }
                c.configure(minimal, surface, null, 0)
            }
            c.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                val id = synchronized(renderedLookup) { renderedLookup.remove(presentationTimeUs) }
                if (id != null) stats.onPresented(id, nanoTime)
            }, handler)
            c.start()
            codec = c
            decoderName = name
            inputBuffers.clear(); pendingFrameIds.clear(); synchronized(renderedLookup) { renderedLookup.clear() }
            machine.onConfigured()
            configured.set(true)
            ReceiverLog.i(TAG, "configured", "decoder" to name, "w" to format.width, "h" to format.height, "csd" to (format.csd0 != null))
            true
        } catch (e: Exception) {
            ReceiverLog.e(TAG, "configure_failed", e, "decoder" to name)
            machine.onError(); onError("configure failed: ${e.message}"); false
        }
    }

    override fun submit(unit: EncodedAccessUnit): DecodeResult {
        val c = codec ?: return DecodeResult.NOT_CONFIGURED
        if (!machine.shouldSubmit(unit.isKeyframe, unit.isConfig)) { stats.onDropped(unit.frameId); return DecodeResult.DROPPED_AWAITING_KEYFRAME }
        val index = inputBuffers.poll() ?: if (unit.isKeyframe) inputBuffers.poll(KEYFRAME_BUFFER_WAIT_MS, TimeUnit.MILLISECONDS) else null
        if (index == null) {
            // Decoder is behind: drop and wait for the next keyframe rather than queueing.
            machine.onFrameLost()
            stats.onDropped(unit.frameId)
            return DecodeResult.DROPPED_NO_INPUT_BUFFER
        }
        return try {
            val buf = c.getInputBuffer(index) ?: return DecodeResult.FAILED
            buf.clear(); buf.put(unit.data)
            val ptsUs = unit.captureTimestampNs / 1000
            val flags = if (unit.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            if (!unit.isConfig) { pendingFrameIds.add(ptsUs to unit.frameId); stats.onSubmitted(unit.frameId, clockNs()) }
            c.queueInputBuffer(index, 0, unit.size, ptsUs, flags)
            DecodeResult.QUEUED
        } catch (e: Exception) {
            ReceiverLog.e(TAG, "submit_failed", e)
            machine.onError(); onError("submit failed: ${e.message}")
            DecodeResult.FAILED
        }
    }

    override fun flush() {
        handler.post {
            runCatching { codec?.flush(); codec?.start() }
            inputBuffers.clear(); pendingFrameIds.clear()
            machine.onFlush()
        }
    }

    @Synchronized
    override fun stop() = stopInternal()

    private fun stopInternal() {
        configured.set(false)
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
        inputBuffers.clear(); pendingFrameIds.clear()
        machine.onStopped()
        stats.reset()
    }

    /** Pops submitted entries up to [ptsUs]; entries the decoder skipped are discarded (bounded queue). */
    private fun frameIdFor(ptsUs: Long): Long? {
        while (true) {
            val head = pendingFrameIds.peek() ?: return null
            if (head.first < ptsUs) { pendingFrameIds.poll(); continue }
            return if (head.first == ptsUs) { pendingFrameIds.poll(); head.second } else null
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { inputBuffers.offer(index) }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val now = clockNs()
            val render = info.size > 0
            runCatching { codec.releaseOutputBuffer(index, render) }
            if (render) {
                val frameId = frameIdFor(info.presentationTimeUs)
                if (frameId != null) {
                    synchronized(renderedLookup) { renderedLookup[info.presentationTimeUs] = frameId }
                    stats.onDecoded(frameId, now)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            ReceiverLog.e(TAG, "codec_error", e, "recoverable" to e.isRecoverable, "transient" to e.isTransient)
            if (!e.isTransient) { machine.onError(); this@MediaCodecVideoDecoder.onError(e.diagnosticInfo) }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputWidth = format.getInteger(MediaFormat.KEY_WIDTH); outputHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            ReceiverLog.i(TAG, "output_format", "w" to outputWidth, "h" to outputHeight)
        }
    }

}

package com.rokidmirror.sender.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Zero-copy Surface-input H.264 encoder: VirtualDisplay -> encoder input Surface -> access units.
 * Output buffers are copied once into a ByteArray (compressed data, a few KB) for the network.
 *
 * Latency choices: low-latency feature when available, no B-frames, 1 s GOP, CBR when
 * supported, realtime priority, `max-fps-to-encoder` to drop surplus frames before encoding.
 */
class MediaCodecVideoEncoder(
    private val onAccessUnit: (EncodedAccessUnit) -> Unit,
    private val clockNs: () -> Long = System::nanoTime,
) : VideoEncoder {
    private companion object {
        const val TAG = "Encoder"
        const val KEY_MAX_B_FRAMES = "max-bframes" // MediaFormat.KEY_MAX_B_FRAMES (API 29)
        const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder" // MediaFormat.KEY_MAX_FPS_TO_ENCODER (API 29)
    }

    private val thread = HandlerThread("video-encoder").apply { start() }
    private val handler = Handler(thread.looper)

    private val _format = MutableStateFlow<EncodedVideoFormat?>(null)
    override val format: StateFlow<EncodedVideoFormat?> = _format.asStateFlow()
    private val _stats = MutableStateFlow(EncoderStats())
    override val stats: StateFlow<EncoderStats> = _stats.asStateFlow()

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var config: EncoderConfig? = null
    private var probe: EncoderProbe? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

    private var frameId = 0L
    private var frames = 0L
    private var keyframes = 0L
    private var restarts = 0
    private var windowStartNs = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowEncodeNs = 0L
    private var forceKeyframeOnNext = false

    override suspend fun start(config: EncoderConfig): Surface = onHandler {
        check(codec == null) { "encoder already started" }
        startInternal(config)
    }

    override fun requestKeyFrame() {
        handler.post {
            val c = codec ?: return@post
            runCatching {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }.onFailure { MirrorLog.w(TAG, "keyframe_request_failed", "error" to it.message) }
        }
    }

    override suspend fun reconfigure(config: EncoderConfig): Surface = onHandler {
        val current = this.config ?: throw IllegalStateException("not started")
        val c = codec ?: throw IllegalStateException("not started")
        if (current.onlyBitrateDiffers(config)) {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, config.bitrate) })
            this.config = config
            MirrorLog.i(TAG, "bitrate_changed", "bps" to config.bitrate)
            surface!!
        } else {
            MirrorLog.i(TAG, "encoder_restart", "w" to config.width, "h" to config.height, "fps" to config.fps, "bps" to config.bitrate)
            releaseCodec()
            restarts++
            startInternal(config)
        }
    }

    override suspend fun stop() = onHandler {
        releaseCodec()
        _format.value = null
    }

    private fun startInternal(config: EncoderConfig): Surface {
        val probe = probe ?: EncoderCapabilities.probe(config.codec)?.also { probe = it }
            ?: throw MirrorException(ErrorCode.ENCODER_UNAVAILABLE, "no ${config.codec.mimeType} encoder")
        val format = MediaFormat.createVideoFormat(config.codec.mimeType, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(KEY_MAX_B_FRAMES, 0)
            setFloat(KEY_MAX_FPS_TO_ENCODER, config.fps.toFloat())
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (probe.supportsCbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (config.lowLatency && probe.supportsLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (config.codec.mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                // Baseline/Main keep decoder compatibility high on the glasses; the encoder may
                // ignore the hint, which is fine.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        val c = try {
            MediaCodec.createByCodecName(probe.encoderName)
        } catch (e: Exception) {
            throw MirrorException(ErrorCode.ENCODER_UNAVAILABLE, "create ${probe.encoderName}: ${e.message}", e)
        }
        try {
            c.setCallback(callback, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Retry once without the optional profile/level hints that some encoders reject.
            runCatching { c.reset() }
            try {
                format.apply { removeKey(MediaFormat.KEY_PROFILE); removeKey(MediaFormat.KEY_LEVEL); removeKey(MediaFormat.KEY_LATENCY) }
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e2: Exception) {
                runCatching { c.release() }
                throw MirrorException(ErrorCode.ENCODER_CONFIGURATION_FAILED, "${probe.encoderName}: ${e2.message}", e2)
            }
        }
        val s = c.createInputSurface()
        c.start()
        codec = c
        surface = s
        this.config = config
        csd0 = null; csd1 = null
        windowStartNs = clockNs(); windowFrames = 0; windowBytes = 0; windowEncodeNs = 0
        forceKeyframeOnNext = true
        MirrorLog.i(TAG, "encoder_started", "name" to probe.encoderName, "w" to config.width, "h" to config.height,
            "fps" to config.fps, "bps" to config.bitrate, "lowLatency" to (probe.supportsLowLatency && config.lowLatency), "cbr" to probe.supportsCbr)
        return s
    }

    private fun releaseCodec() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* Surface input */ }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (this@MediaCodecVideoEncoder.codec !== codec) return
            try {
                val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: return
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val nowNs = clockNs()
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (isConfig) {
                    val data = ByteArray(info.size).also { buffer.get(it) }
                    splitCsd(data)
                    publishFormat(codec.outputFormat)
                    return
                }
                if (info.size == 0) return
                val cfg = if (isKey) csdPrefix() else null
                val data = ByteArray((cfg?.size ?: 0) + info.size)
                cfg?.copyInto(data)
                buffer.get(data, cfg?.size ?: 0, info.size)
                val captureNs = info.presentationTimeUs * 1000 // SurfaceFlinger timestamps are CLOCK_MONOTONIC
                val unit = EncodedAccessUnit(
                    frameId = frameId++,
                    isKeyframe = isKey,
                    isConfig = false,
                    captureTimestampNs = captureNs,
                    encodeTimestampNs = nowNs,
                    data = data,
                )
                account(unit, info, nowNs)
                onAccessUnit(unit)
            } catch (e: Exception) {
                MirrorLog.e(TAG, "output_error", e)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            MirrorLog.e(TAG, "codec_error", e, "recoverable" to e.isRecoverable, "transient" to e.isTransient)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            format.getByteBuffer("csd-0")?.let { csd0 = it.toArray() }
            format.getByteBuffer("csd-1")?.let { csd1 = it.toArray() }
            publishFormat(format)
        }
    }

    private fun publishFormat(mediaFormat: MediaFormat) {
        val cfg = config ?: return
        _format.value = EncodedVideoFormat(
            codec = cfg.codec,
            width = mediaFormat.getIntegerOr(MediaFormat.KEY_WIDTH, cfg.width),
            height = mediaFormat.getIntegerOr(MediaFormat.KEY_HEIGHT, cfg.height),
            fps = cfg.fps,
            csd0 = csd0,
            csd1 = csd1,
            encoderName = probe?.encoderName ?: "",
            lowLatencyEnabled = cfg.lowLatency && (probe?.supportsLowLatency ?: false),
        )
    }

    /** Annex-B config buffer holds SPS and PPS; split on start codes. */
    private fun splitCsd(data: ByteArray) {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts += i; i += 4 } else i++
        }
        if (starts.size >= 2) {
            csd0 = data.copyOfRange(starts[0], starts[1])
            csd1 = data.copyOfRange(starts[1], data.size)
        } else {
            csd0 = data; csd1 = null
        }
    }

    /** SPS+PPS prepended to every keyframe so decoders can (re)start at any IDR. */
    private fun csdPrefix(): ByteArray? {
        val a = csd0 ?: return null
        val b = csd1 ?: return a
        return a + b
    }

    private fun account(unit: EncodedAccessUnit, info: MediaCodec.BufferInfo, nowNs: Long) {
        frames++
        if (unit.isKeyframe) keyframes++
        windowFrames++
        windowBytes += unit.size
        windowEncodeNs += (nowNs - unit.captureTimestampNs).coerceAtLeast(0)
        val elapsed = nowNs - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            val sec = elapsed / 1e9f
            val s = EncoderStats(
                frames = frames, keyframes = keyframes, lastPtsUs = info.presentationTimeUs, lastSizeBytes = unit.size,
                fps = windowFrames / sec, bitrateBps = (windowBytes * 8 / sec).toLong(),
                avgEncodeMs = if (windowFrames > 0) windowEncodeNs / windowFrames / 1e6f else 0f, restarts = restarts,
            )
            _stats.value = s
            MirrorLog.d(TAG, "encoder_stats", "frame" to frames, "pts_us" to info.presentationTimeUs, "bytes" to unit.size,
                "fps" to "%.1f".format(s.fps), "kbps" to s.bitrateBps / 1000, "encode_ms" to "%.1f".format(s.avgEncodeMs), "key" to keyframes)
            windowStartNs = nowNs; windowFrames = 0; windowBytes = 0; windowEncodeNs = 0
        }
    }

    private suspend fun <T> onHandler(block: () -> T): T = suspendCancellableCoroutine { cont ->
        handler.post {
            try { cont.resume(block()) } catch (e: Throwable) { cont.resumeWithException(e) }
        }
    }

    private fun ByteBuffer.toArray(): ByteArray { val d = duplicate(); d.rewind(); return ByteArray(d.remaining()).also { d.get(it) } }
    private fun MediaFormat.getIntegerOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default
}

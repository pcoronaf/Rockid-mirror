package com.rokidmirror.protocol

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads

/** Concrete encoder parameters for a session. */
data class StreamParams(val codec: VideoCodec, val width: Int, val height: Int, val fps: Int, val bitrate: Int, val preset: StreamPreset)

/** Optional local encoder limits discovered by capability probing. */
data class EncoderLimits(val maxWidth: Int, val maxHeight: Int, val maxFps: Int, val minBitrate: Int, val maxBitrate: Int)

/**
 * Chooses stream parameters from a preset, the source geometry, receiver capabilities and
 * encoder limits. Deterministic and side-effect free so it is unit-testable.
 */
object StreamNegotiator {
    private const val ALIGN = 16

    fun negotiate(preset: StreamPreset, sourceW: Int, sourceH: Int, caps: Payloads.Capabilities, encoder: EncoderLimits? = null): StreamParams {
        require(sourceW > 0 && sourceH > 0)
        val codec = caps.codecs.firstOrNull { it.name == VideoCodec.H264.wireName }?.let { VideoCodec.H264 }
            ?: throw MirrorException(ErrorCode.UNSUPPORTED_CODEC, "receiver codecs: ${caps.codecs.map { it.name }}")

        val landscapeSource = sourceW >= sourceH
        val decLong = maxOf(caps.maxDecode.width, caps.maxDecode.height)
        val decShort = minOf(caps.maxDecode.width, caps.maxDecode.height)
        var longEdge = minOf(preset.longEdge, decLong, maxOf(sourceW, sourceH))
        encoder?.let { longEdge = minOf(longEdge, maxOf(it.maxWidth, it.maxHeight)) }
        var shortEdge = (longEdge.toDouble() * minOf(sourceW, sourceH) / maxOf(sourceW, sourceH)).toInt()
        if (shortEdge > decShort) {
            shortEdge = decShort
            longEdge = (shortEdge.toDouble() * maxOf(sourceW, sourceH) / minOf(sourceW, sourceH)).toInt()
        }
        longEdge = align(longEdge)
        shortEdge = align(shortEdge)
        val (w, h) = if (landscapeSource) longEdge to shortEdge else shortEdge to longEdge

        var fps = minOf(preset.fps, caps.maxDecode.fps)
        encoder?.let { fps = minOf(fps, it.maxFps) }
        if (fps < 1) fps = 1

        var bitrate = preset.startBitrate
        encoder?.let { bitrate = bitrate.coerceIn(it.minBitrate, it.maxBitrate) }
        return StreamParams(codec, w, h, fps, bitrate, preset)
    }

    fun align(v: Int): Int = maxOf(ALIGN, (v / ALIGN) * ALIGN)
}

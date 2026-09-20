package com.rokidmirror.sender.encoder

import android.view.Surface
import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.protocol.video.EncodedAccessUnit
import kotlinx.coroutines.flow.StateFlow

data class EncoderConfig(
    val codec: VideoCodec = VideoCodec.H264,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    /** Seconds between IDR frames; short for fast recovery. */
    val iFrameIntervalSec: Float = 1f,
    val lowLatency: Boolean = true,
) {
    /** True when the change can be applied without restarting the codec. */
    fun onlyBitrateDiffers(other: EncoderConfig) = copy(bitrate = other.bitrate) == other
}

/** Negotiated output format including codec-specific data needed by the decoder. */
data class EncodedVideoFormat(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val csd0: ByteArray?,
    val csd1: ByteArray?,
    val encoderName: String,
    val lowLatencyEnabled: Boolean,
)

/** Surface-input hardware encoder boundary from the specification. */
interface VideoEncoder {
    val format: StateFlow<EncodedVideoFormat?>
    val stats: StateFlow<EncoderStats>

    /** Configures and starts the codec, returning the input surface for the VirtualDisplay. */
    suspend fun start(config: EncoderConfig): Surface

    fun requestKeyFrame()

    /**
     * Applies a new configuration. Bitrate-only changes are applied live and the same surface is
     * returned; anything else restarts the codec and returns a new input surface that the caller
     * must hand to the VirtualDisplay.
     */
    suspend fun reconfigure(config: EncoderConfig): Surface

    suspend fun stop()
}

/** Instantaneous counters logged once per second (Task B acceptance: frame, pts, size, fps, bitrate). */
data class EncoderStats(
    val frames: Long = 0,
    val keyframes: Long = 0,
    val lastPtsUs: Long = 0,
    val lastSizeBytes: Int = 0,
    val fps: Float = 0f,
    val bitrateBps: Long = 0,
    val avgEncodeMs: Float = 0f,
    val restarts: Int = 0,
)

package com.rokidmirror.protocol

/**
 * Wire-level constants shared by the Android sender, the Rokid receiver and the desktop tools.
 *
 * Compatibility policy (docs/protocol.md): fields are only ever added; a field never changes
 * meaning. Any incompatible change increments [PROTOCOL_VERSION].
 */
object Protocol {
    const val PROTOCOL_VERSION: Int = 1

    /** DNS-SD service type advertised by receivers and browsed by senders. */
    const val SERVICE_TYPE: String = "_rokidmirror._tcp."

    /** Default TCP port of the receiver control channel (advertised via DNS-SD; may differ). */
    const val DEFAULT_CONTROL_PORT: Int = 47010

    /** Default UDP port of the receiver video channel (always announced in HELLO_ACK). */
    const val DEFAULT_VIDEO_PORT: Int = 47011

    /** Maximum UDP datagram we emit. 1500 MTU - 20 IPv4 - 8 UDP - safety margin. */
    const val MAX_DATAGRAM_BYTES: Int = 1400

    /** Maximum control frame size accepted on the TCP channel (JSON is tiny; guards memory). */
    const val MAX_CONTROL_FRAME_BYTES: Int = 64 * 1024

    /** How long the receiver waits for the remaining fragments of an access unit. */
    const val REASSEMBLY_TIMEOUT_MS: Long = 120

    /** Keep-alive period on the control channel. */
    const val PING_INTERVAL_MS: Long = 1000

    /** Control channel is considered dead after this silence. */
    const val CONTROL_TIMEOUT_MS: Long = 5000

    /** Pairing codes are single use and expire after this long. */
    const val PAIRING_CODE_TTL_MS: Long = 90_000
}

/** Video codecs understood by the protocol. Only H.264 is implemented in v0.1. */
enum class VideoCodec(val wireName: String, val mimeType: String) {
    H264("h264", "video/avc"),
    H265("h265", "video/hevc");

    companion object {
        fun fromWire(name: String): VideoCodec? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Encoder presets from the specification. Values are starting points, not constants: the
 * sender clamps them to encoder and receiver capabilities and the adaptive controller
 * moves within [minBitrate, maxBitrate].
 */
enum class StreamPreset(
    val wireName: String,
    val longEdge: Int,
    val fps: Int,
    val startBitrate: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
) {
    LOW("low", longEdge = 854, fps = 30, startBitrate = 1_200_000, minBitrate = 600_000, maxBitrate = 1_500_000),
    BALANCED("balanced", longEdge = 1280, fps = 30, startBitrate = 3_000_000, minBitrate = 1_000_000, maxBitrate = 4_000_000),
    INTERACTIVE("interactive", longEdge = 1280, fps = 60, startBitrate = 5_000_000, minBitrate = 1_500_000, maxBitrate = 7_000_000);

    companion object {
        fun fromWire(name: String): StreamPreset? = entries.firstOrNull { it.wireName == name }
    }
}

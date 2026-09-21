package com.rokidmirror.protocol.control

import kotlinx.serialization.Serializable

/**
 * Typed payloads for every [MessageType]. All fields added after v1 must be optional with
 * defaults so older peers keep decoding.
 */
object Payloads {
    /** Sender -> receiver. First plaintext message. */
    @Serializable
    data class Hello(
        val senderId: String,
        val senderName: String,
        val appVersion: String,
        /** Base64 X.509 SubjectPublicKeyInfo of an ephemeral EC P-256 key. */
        val ephemeralPublicKey: String,
        /** Receiver-issued credential id from a previous pairing, if any. */
        val credentialId: String? = null,
        /** Base64 random 16 bytes; part of the transcript. */
        val nonce: String,
    )

    /** Receiver -> sender. Second plaintext message. */
    @Serializable
    data class HelloAck(
        val receiverId: String,
        val receiverName: String,
        val appVersion: String,
        val ephemeralPublicKey: String,
        val videoPort: Int,
        /** True when the credential in HELLO is unknown/absent and a pairing code is required. */
        val pairingRequired: Boolean,
        val nonce: String,
    )

    enum class PairMode { CODE, CREDENTIAL }
    /**
     * COMMIT/REVEAL are used once per bit of the pairing code (see crypto/PairingCommitment);
     * CREDENTIAL authenticates a previously issued credential; ISSUE delivers a new credential
     * (always encrypted).
     */
    enum class PairStage { COMMIT, REVEAL, CREDENTIAL, ISSUE }

    /** Sender -> receiver. */
    @Serializable
    data class PairRequest(
        val mode: PairMode,
        val stage: PairStage,
        /** Bit index for COMMIT/REVEAL rounds. */
        val round: Int = 0,
        /** COMMIT: base64 commitment. */
        val commitment: String? = null,
        /** REVEAL: base64 nonce that opens the commitment. */
        val nonce: String? = null,
        /** CREDENTIAL: base64 HMAC proof of the stored credential. */
        val proof: String? = null,
        val credentialId: String? = null,
    )

    /** Receiver -> sender. */
    @Serializable
    data class PairConfirm(
        val stage: PairStage,
        val accepted: Boolean,
        val round: Int = 0,
        val commitment: String? = null,
        val nonce: String? = null,
        val proof: String? = null,
        /** CREDENTIAL stage (sent encrypted): the long-term credential the sender persists. */
        val credentialId: String? = null,
        val credentialSecret: String? = null,
        val reason: String? = null,
    )

    @Serializable
    data class DisplaySize(val width: Int, val height: Int, val densityDpi: Int = 0, val refreshHz: Float = 0f)

    @Serializable
    data class CodecCapability(val name: String, val profiles: List<String> = emptyList(), val hardware: Boolean = false, val lowLatency: Boolean = false)

    @Serializable
    data class DecodeLimit(val width: Int, val height: Int, val fps: Int)

    @Serializable
    data class InputCapability(val touchBar: Boolean = false, val imu: Boolean = false, val keys: Boolean = false)

    /** Receiver -> sender (encrypted). Must arrive before STREAM_START. */
    @Serializable
    data class Capabilities(
        val display: DisplaySize,
        val codecs: List<CodecCapability>,
        val maxDecode: DecodeLimit,
        val input: InputCapability = InputCapability(),
        val receiverModel: String = "",
        val receiverOs: String = "",
        /** Decoder component actually selected on the receiver, e.g. "c2.qti.avc.decoder". */
        val decoderName: String = "",
        val headViewportSupported: Boolean = false,
    )

    @Serializable
    data class StreamStart(
        val codec: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val preset: String,
        val sourceName: String = "",
        val sessionShortId: Long,
    )

    @Serializable
    data class StreamStop(val reason: String = "")

    /** Codec configuration and current source geometry. Re-sent on every geometry change. */
    @Serializable
    data class StreamFormat(
        val codec: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        /** Base64 codec-specific data (SPS for H.264). */
        val csd0: String? = null,
        /** Base64 codec-specific data (PPS for H.264). */
        val csd1: String? = null,
        /** Logical source (phone or app) size before encoder scaling. */
        val sourceWidth: Int = width,
        val sourceHeight: Int = height,
        val rotationDegrees: Int = 0,
    )

    @Serializable
    data class ViewportSet(
        val scale: Float,
        val centerX: Float,
        val centerY: Float,
        val fitMode: String,
    )

    @Serializable
    data class ProfileSet(
        val id: String,
        val name: String,
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val fitMode: String,
        val preferredStreamPreset: String,
    )

    @Serializable
    data class KeyframeRequest(val reason: String = "", val lastFrameId: Long = -1)

    @Serializable
    data class Ping(val sentNs: Long)

    @Serializable
    data class Pong(val sentNs: Long, val receiverNs: Long)

    /** Receiver -> sender periodic statistics (all durations in milliseconds). */
    @Serializable
    data class Stats(
        val packetsReceived: Long,
        val packetsLost: Long,
        val framesDelivered: Long,
        val framesDropped: Long,
        val framesDecoded: Long,
        val decodeLagFrames: Int,
        val reassemblyMs: Float,
        val decodeMs: Float,
        val renderMs: Float,
        val networkMs: Float = -1f,
        val fps: Float,
        val bitrateBps: Long,
        val clockOffsetNs: Long = 0,
    )

    @Serializable
    data class Error(val code: String, val message: String, val recoverable: Boolean)

    @Serializable
    data class Goodbye(val reason: String = "")

    /**
     * Remote pointer (mouse mode). Coordinates are normalized to the captured source frame, so
     * the receiver can place the cursor whatever the current zoom or pan is. Sent by the phone
     * only while mouse mode is on; `visible = false` removes the cursor.
     */
    @Serializable
    data class Pointer(
        val x: Float,
        val y: Float,
        val visible: Boolean = true,
        val pressed: Boolean = false,
    )
}

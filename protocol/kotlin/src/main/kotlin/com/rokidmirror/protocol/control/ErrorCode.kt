package com.rokidmirror.protocol.control

/** Typed error categories from the specification. */
enum class ErrorCode(val recoverable: Boolean, val userMessage: String) {
    CAPTURE_PERMISSION_DENIED(true, "Screen capture permission was not granted."),
    CAPTURE_STOPPED(true, "Screen capture was stopped."),
    CAPTURE_PROTECTED_CONTENT(true, "This content cannot be captured."),
    ENCODER_UNAVAILABLE(false, "No suitable video encoder is available."),
    ENCODER_CONFIGURATION_FAILED(true, "The video encoder could not be configured."),
    RECEIVER_NOT_FOUND(true, "No glasses receiver was found on this network."),
    PAIRING_FAILED(true, "Pairing failed. Check the code and try again."),
    AUTHENTICATION_FAILED(true, "The receiver did not accept this phone. Pair again."),
    NETWORK_LOST(true, "The network connection was lost."),
    PROTOCOL_VERSION_MISMATCH(false, "The glasses app and the phone app are incompatible versions."),
    UNSUPPORTED_CODEC(false, "The receiver does not support the video codec."),
    DECODER_FAILED(true, "The receiver could not decode the video."),
    RENDERER_FAILED(true, "The receiver could not display the video."),
    PLATFORM_API_UNAVAILABLE(false, "A required platform feature is unavailable."),
    RESOURCE_EXHAUSTED(true, "The device is out of resources."),
    UNKNOWN(true, "Something went wrong.");

    companion object {
        fun fromWire(name: String): ErrorCode = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** A typed error with a machine-readable code, a user-safe message and debug-only details. */
open class MirrorException(
    val code: ErrorCode,
    val details: String? = null,
    cause: Throwable? = null,
) : Exception(details ?: code.userMessage, cause) {
    val userMessage: String get() = code.userMessage
    val recoverable: Boolean get() = code.recoverable
}

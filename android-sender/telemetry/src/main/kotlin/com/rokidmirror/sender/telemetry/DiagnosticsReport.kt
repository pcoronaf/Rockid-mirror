package com.rokidmirror.sender.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sanitized diagnostics export (spec "Logging and diagnostics"). Contains only versions,
 * negotiated parameters and counters; never identifiers of captured content, pairing material
 * or IP addresses beyond the receiver name.
 */
@Serializable
data class DiagnosticsReport(
    val generatedAtEpochMs: Long,
    val senderVersion: String,
    val protocolVersion: Int,
    val phoneModel: String,
    val androidVersion: String,
    val receiverName: String? = null,
    val receiverModel: String? = null,
    val receiverOs: String? = null,
    val receiverVersion: String? = null,
    val negotiatedCodec: String? = null,
    val encodedWidth: Int = 0,
    val encodedHeight: Int = 0,
    val fps: Float = 0f,
    val targetFps: Int = 0,
    val bitrateBps: Long = 0,
    val rttMs: Float = -1f,
    val lossFraction: Float = 0f,
    val encodeMsAvg: Float = 0f,
    val encodeMsMax: Float = 0f,
    val receiverReassemblyMs: Float = -1f,
    val receiverDecodeMs: Float = -1f,
    val receiverRenderMs: Float = -1f,
    val networkMs: Float = -1f,
    val droppedFramesReceiver: Long = 0,
    val sendQueueDrops: Long = 0,
    val sessionDurationSec: Long = 0,
    val encoderName: String? = null,
    val encoderLowLatency: Boolean = false,
    val transport: String = "lan-udp-aead",
    val recentEvents: List<String> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    fun toText(): String = buildString {
        appendLine("Rokid Mirror diagnostics")
        appendLine("sender $senderVersion  protocol v$protocolVersion  $phoneModel / Android $androidVersion")
        appendLine("receiver ${receiverName ?: "-"} (${receiverModel ?: "-"}, ${receiverOs ?: "-"}, app ${receiverVersion ?: "-"})")
        appendLine("stream ${negotiatedCodec ?: "-"} ${encodedWidth}x$encodedHeight @ ${"%.1f".format(fps)}/$targetFps fps, ${bitrateBps / 1000} kbps, encoder ${encoderName ?: "-"} lowLatency=$encoderLowLatency")
        appendLine("rtt ${"%.1f".format(rttMs)} ms  loss ${"%.2f".format(lossFraction * 100)} %  network ~${"%.1f".format(networkMs)} ms")
        appendLine("encode avg ${"%.1f".format(encodeMsAvg)} ms max ${"%.1f".format(encodeMsMax)} ms  reassembly ${"%.1f".format(receiverReassemblyMs)} ms  decode ${"%.1f".format(receiverDecodeMs)} ms  render ${"%.1f".format(receiverRenderMs)} ms")
        appendLine("dropped(receiver) $droppedFramesReceiver  sendQueueDrops $sendQueueDrops  duration ${sessionDurationSec}s")
        if (recentEvents.isNotEmpty()) { appendLine("events:"); recentEvents.forEach { appendLine("  $it") } }
    }

    companion object { private val json = Json { prettyPrint = true; encodeDefaults = true } }
}

package com.rokidmirror.sender.app.session

import com.rokidmirror.protocol.StreamParams
import com.rokidmirror.protocol.StreamPreset
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.sender.encoder.EncoderStats
import com.rokidmirror.sender.telemetry.LatencySnapshot
import com.rokidmirror.sender.transport.TransportStatistics

/** Everything the phone UI shows about the current session. */
data class SessionInfo(
    val receiverName: String? = null,
    val receiverId: String? = null,
    val networkDescription: String = "-",
    val capabilities: Payloads.Capabilities? = null,
    val preset: StreamPreset = StreamPreset.BALANCED,
    val stream: StreamParams? = null,
    val currentBitrate: Int = 0,
    val encoderStats: EncoderStats = EncoderStats(),
    val latency: LatencySnapshot? = null,
    val transport: TransportStatistics = TransportStatistics(),
    val sourceName: String = "",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sessionStartedAtNs: Long = 0,
    val encoderName: String? = null,
    val encoderLowLatency: Boolean = false,
    val recentEvents: List<String> = emptyList(),
) {
    /** Best available end-to-end software latency estimate: encode + network/2 + receiver stages. */
    val estimatedLatencyMs: Float?
        get() {
            val rs = transport.receiverStats ?: return null
            val enc = latency?.encodeMsAvg ?: return null
            val net = if (transport.rttMs >= 0) transport.rttMs / 2f else 0f
            return enc + net + rs.reassemblyMs.coerceAtLeast(0f) + rs.decodeMs.coerceAtLeast(0f) + rs.renderMs.coerceAtLeast(0f)
        }
}

/** Shown as a dialog while the receiver waits for the code displayed on the glasses. */
data class PairingPrompt(val receiverName: String)

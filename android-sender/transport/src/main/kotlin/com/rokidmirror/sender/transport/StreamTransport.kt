package com.rokidmirror.sender.transport

import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.protocol.video.EncodedAccessUnit
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class ReceiverEndpoint(val id: String, val name: String, val host: String, val controlPort: Int)

sealed class TransportState {
    object Disconnected : TransportState()
    data class Connecting(val endpoint: ReceiverEndpoint) : TransportState()
    data class Handshaking(val endpoint: ReceiverEndpoint, val awaitingPairingCode: Boolean) : TransportState()
    data class Connected(val endpoint: ReceiverEndpoint, val capabilities: Payloads.Capabilities?) : TransportState()
    data class Failed(val endpoint: ReceiverEndpoint?, val code: ErrorCode, val details: String?) : TransportState()
}

data class TransportStatistics(
    val rttMs: Float = -1f,
    val minRttMs: Float = -1f,
    val packetsSent: Long = 0,
    val bytesSent: Long = 0,
    val unitsSent: Long = 0,
    val unitsDroppedAtSender: Long = 0,
    val receiverStats: Payloads.Stats? = null,
    val lossFraction: Float = 0f,
    val clockOffsetNs: Long = 0,
)

/** Asynchronous notifications from the receiver or the link. */
sealed class TransportEvent {
    data class CapabilitiesReceived(val capabilities: Payloads.Capabilities) : TransportEvent()
    data class KeyframeRequested(val reason: String) : TransportEvent()
    data class StatsReceived(val stats: Payloads.Stats) : TransportEvent()
    /** A snapshot of what the glasses are showing. */
    data class PreviewReceived(val frame: Payloads.PreviewFrame) : TransportEvent()
    data class ReceiverError(val code: ErrorCode, val message: String) : TransportEvent()
    data class Disconnected(val code: ErrorCode, val details: String?) : TransportEvent()
    /** A new credential was issued during pairing; persist it. */
    data class CredentialIssued(val credential: PairingCredential) : TransportEvent()
}

/** Specification interface. No caller outside this package touches sockets or crypto. */
interface StreamTransport {
    val state: StateFlow<TransportState>
    val statistics: StateFlow<TransportStatistics>
    val events: SharedFlow<TransportEvent>

    /**
     * Connects and completes the handshake. When the receiver requires a pairing code the
     * transport asks [pairingCodeProvider] (which typically shows a dialog) and continues.
     */
    suspend fun connect(receiver: ReceiverEndpoint, credential: PairingCredential?, pairingCodeProvider: suspend () -> String)

    /** Non-blocking; late delta frames are dropped in favour of the next keyframe. */
    fun sendVideo(unit: EncodedAccessUnit)

    suspend fun sendControl(message: ControlMessage)

    /** Convenience for typed payloads. */
    suspend fun <T> send(type: com.rokidmirror.protocol.control.MessageType, serializer: kotlinx.serialization.KSerializer<T>, payload: T)

    suspend fun close(reason: String = "")

    val sessionId: String?
    val sessionShortId: Long
}

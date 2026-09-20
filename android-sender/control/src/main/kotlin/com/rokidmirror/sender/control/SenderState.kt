package com.rokidmirror.sender.control

import com.rokidmirror.protocol.control.ErrorCode

/** Sender UI states from the specification, driven by [SenderStateMachine]. */
sealed class SenderState {
    object Idle : SenderState()
    object Searching : SenderState()
    data class ReceiverFound(val count: Int) : SenderState()
    /** Connecting/handshaking. [codeRequired] true while the user must type the glasses' code. */
    data class Pairing(val receiverName: String, val codeRequired: Boolean) : SenderState()
    data class Ready(val receiverName: String) : SenderState()
    data class AwaitingCapturePermission(val receiverName: String) : SenderState()
    data class Streaming(val receiverName: String, val sourceName: String) : SenderState()
    data class Paused(val receiverName: String, val reason: String) : SenderState()
    data class Recovering(val receiverName: String, val attempt: Int, val wasStreaming: Boolean) : SenderState()
    data class Error(val code: ErrorCode, val details: String?, val receiverName: String?) : SenderState()

    val receiverNameOrNull: String?
        get() = when (this) {
            is Pairing -> receiverName; is Ready -> receiverName; is AwaitingCapturePermission -> receiverName
            is Streaming -> receiverName; is Paused -> receiverName; is Recovering -> receiverName; is Error -> receiverName
            else -> null
        }
}

/** Everything that can move the sender state machine. */
sealed class SenderEvent {
    object StartSearch : SenderEvent()
    data class ReceiversChanged(val count: Int) : SenderEvent()
    data class ConnectRequested(val receiverName: String) : SenderEvent()
    object PairingCodeRequired : SenderEvent()
    object PairingCodeSubmitted : SenderEvent()
    object Connected : SenderEvent()
    object CapturePermissionRequested : SenderEvent()
    object CapturePermissionDenied : SenderEvent()
    data class StreamStarted(val sourceName: String) : SenderEvent()
    data class CaptureStopped(val reason: String) : SenderEvent()
    data class ContentHidden(val hidden: Boolean) : SenderEvent()
    data class ConnectionLost(val recoverable: Boolean) : SenderEvent()
    data class ReconnectAttempt(val attempt: Int) : SenderEvent()
    object Reconnected : SenderEvent()
    object StopRequested : SenderEvent()
    object Disconnected : SenderEvent()
    data class Failed(val code: ErrorCode, val details: String? = null) : SenderEvent()
    object Dismissed : SenderEvent()
}

/**
 * Pure reducer: (state, event) -> state. Side effects live in the session coordinator, which
 * makes this table unit-testable and keeps the UI free of scattered booleans.
 */
object SenderStateMachine {
    fun reduce(state: SenderState, event: SenderEvent): SenderState = when (event) {
        SenderEvent.StartSearch -> if (state is SenderState.Idle || state is SenderState.Error || state is SenderState.ReceiverFound) SenderState.Searching else state
        is SenderEvent.ReceiversChanged -> when (state) {
            is SenderState.Searching, is SenderState.ReceiverFound -> if (event.count > 0) SenderState.ReceiverFound(event.count) else SenderState.Searching
            else -> state
        }
        is SenderEvent.ConnectRequested -> SenderState.Pairing(event.receiverName, codeRequired = false)
        SenderEvent.PairingCodeRequired -> (state as? SenderState.Pairing)?.copy(codeRequired = true) ?: state
        SenderEvent.PairingCodeSubmitted -> (state as? SenderState.Pairing)?.copy(codeRequired = false) ?: state
        SenderEvent.Connected -> state.receiverNameOrNull?.let { SenderState.Ready(it) } ?: state
        SenderEvent.CapturePermissionRequested -> (state as? SenderState.Ready)?.let { SenderState.AwaitingCapturePermission(it.receiverName) } ?: state
        SenderEvent.CapturePermissionDenied -> (state as? SenderState.AwaitingCapturePermission)?.let { SenderState.Ready(it.receiverName) } ?: state
        is SenderEvent.StreamStarted -> state.receiverNameOrNull?.let { SenderState.Streaming(it, event.sourceName) } ?: state
        is SenderEvent.CaptureStopped -> when (state) {
            is SenderState.Streaming, is SenderState.Paused, is SenderState.AwaitingCapturePermission -> SenderState.Ready(state.receiverNameOrNull!!)
            is SenderState.Recovering -> state.copy(wasStreaming = false)
            else -> state
        }
        is SenderEvent.ContentHidden -> when {
            state is SenderState.Streaming && event.hidden -> SenderState.Paused(state.receiverName, "content not visible")
            state is SenderState.Paused && !event.hidden -> SenderState.Streaming(state.receiverName, "")
            else -> state
        }
        is SenderEvent.ConnectionLost -> when (state) {
            is SenderState.Streaming, is SenderState.Paused -> if (event.recoverable) SenderState.Recovering(state.receiverNameOrNull!!, 0, wasStreaming = true) else SenderState.Error(ErrorCode.NETWORK_LOST, null, state.receiverNameOrNull)
            is SenderState.Ready, is SenderState.AwaitingCapturePermission -> if (event.recoverable) SenderState.Recovering(state.receiverNameOrNull!!, 0, wasStreaming = false) else SenderState.Error(ErrorCode.NETWORK_LOST, null, state.receiverNameOrNull)
            is SenderState.Pairing -> SenderState.Error(ErrorCode.NETWORK_LOST, null, state.receiverName)
            else -> state
        }
        is SenderEvent.ReconnectAttempt -> (state as? SenderState.Recovering)?.copy(attempt = event.attempt) ?: state
        SenderEvent.Reconnected -> (state as? SenderState.Recovering)?.let { if (it.wasStreaming) SenderState.Streaming(it.receiverName, "") else SenderState.Ready(it.receiverName) } ?: state
        SenderEvent.StopRequested -> when (state) {
            is SenderState.Streaming, is SenderState.Paused -> SenderState.Ready(state.receiverNameOrNull!!)
            is SenderState.Recovering -> SenderState.Idle
            else -> state
        }
        SenderEvent.Disconnected -> if (state is SenderState.Error) state else SenderState.Idle
        is SenderEvent.Failed -> SenderState.Error(event.code, event.details, state.receiverNameOrNull)
        SenderEvent.Dismissed -> if (state is SenderState.Error) SenderState.Idle else state
    }
}

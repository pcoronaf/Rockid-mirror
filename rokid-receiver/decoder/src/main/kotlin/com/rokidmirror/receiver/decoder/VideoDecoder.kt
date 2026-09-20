package com.rokidmirror.receiver.decoder

import android.view.Surface
import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.protocol.video.EncodedAccessUnit

data class DecoderFormat(val codec: VideoCodec, val width: Int, val height: Int, val csd0: ByteArray?, val csd1: ByteArray?)

enum class DecodeResult { QUEUED, DROPPED_NO_INPUT_BUFFER, DROPPED_AWAITING_KEYFRAME, NOT_CONFIGURED, FAILED }

/** Decoder boundary from the specification; the decoder owns the output Surface. */
interface VideoDecoder {
    fun configure(format: DecoderFormat, surface: Surface): Boolean
    /** @param unit complete access unit; Annex-B for H.264. */
    fun submit(unit: EncodedAccessUnit): DecodeResult
    fun flush()
    fun stop()
    val isConfigured: Boolean
}

/**
 * Pure decoder state machine (unit-tested with a mock decoder): decides which units may be
 * submitted so a decoder never receives a delta frame whose reference chain is broken.
 */
class DecoderStateMachine {
    enum class State { UNCONFIGURED, AWAITING_KEYFRAME, DECODING, FAILED }

    var state: State = State.UNCONFIGURED; private set
    var droppedAwaitingKeyframe = 0L; private set

    fun onConfigured() { state = State.AWAITING_KEYFRAME }
    fun onError() { state = State.FAILED }
    fun onFlush() { if (state == State.DECODING) state = State.AWAITING_KEYFRAME }
    fun onStopped() { state = State.UNCONFIGURED }
    /** A frame was lost before decode (no buffer, timeout, network); wait for a keyframe again. */
    fun onFrameLost() { if (state == State.DECODING) state = State.AWAITING_KEYFRAME }

    /** @return true when the unit may be submitted to the codec. */
    fun shouldSubmit(isKeyframe: Boolean, isConfig: Boolean): Boolean = when (state) {
        State.UNCONFIGURED, State.FAILED -> false
        State.DECODING -> true
        State.AWAITING_KEYFRAME -> if (isKeyframe || isConfig) { if (isKeyframe) state = State.DECODING; true } else { droppedAwaitingKeyframe++; false }
    }
}

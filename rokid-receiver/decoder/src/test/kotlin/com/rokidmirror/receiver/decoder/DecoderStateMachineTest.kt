package com.rokidmirror.receiver.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderStateMachineTest {
    @Test
    fun rejectsEverythingUntilConfigured() {
        val m = DecoderStateMachine()
        assertFalse(m.shouldSubmit(isKeyframe = true, isConfig = false))
        m.onConfigured()
        assertEquals(DecoderStateMachine.State.AWAITING_KEYFRAME, m.state)
    }

    @Test
    fun waitsForKeyframeThenDecodes() {
        val m = DecoderStateMachine().apply { onConfigured() }
        assertFalse(m.shouldSubmit(false, false))
        assertTrue(m.shouldSubmit(false, true)) // config allowed
        assertEquals(DecoderStateMachine.State.AWAITING_KEYFRAME, m.state)
        assertTrue(m.shouldSubmit(true, false))
        assertEquals(DecoderStateMachine.State.DECODING, m.state)
        assertTrue(m.shouldSubmit(false, false))
        assertEquals(1, m.droppedAwaitingKeyframe)
    }

    @Test
    fun lossAndFlushRequireNewKeyframe() {
        val m = DecoderStateMachine().apply { onConfigured(); shouldSubmit(true, false) }
        m.onFrameLost()
        assertFalse(m.shouldSubmit(false, false))
        assertTrue(m.shouldSubmit(true, false))
        m.onFlush()
        assertEquals(DecoderStateMachine.State.AWAITING_KEYFRAME, m.state)
    }

    @Test
    fun errorIsTerminalUntilReconfigured() {
        val m = DecoderStateMachine().apply { onConfigured(); onError() }
        assertFalse(m.shouldSubmit(true, false))
        m.onStopped(); m.onConfigured()
        assertTrue(m.shouldSubmit(true, false))
    }
}

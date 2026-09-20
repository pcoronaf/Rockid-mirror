package com.rokidmirror.protocol

import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ControlCodecTest {
    private val factory = MessageFactory("sess-1") { 1234L }

    @Test
    fun roundTripsTypedPayload() {
        val msg = factory.create(MessageType.VIEWPORT_SET, Payloads.ViewportSet.serializer(), Payloads.ViewportSet(1.5f, 0.25f, 0.75f, "CUSTOM"))
        val decoded = ControlCodec.decode(ControlCodec.encode(msg))
        assertEquals(MessageType.VIEWPORT_SET, decoded.type)
        assertEquals(1L, decoded.sequence)
        assertEquals(Protocol.PROTOCOL_VERSION, decoded.protocolVersion)
        val p = ControlCodec.payloadOf(decoded, Payloads.ViewportSet.serializer())
        assertEquals(0.25f, p.centerX, 0f)
        assertEquals("CUSTOM", p.fitMode)
    }

    @Test
    fun sequenceIncrements() {
        factory.create(MessageType.PING)
        val second = factory.create(MessageType.PING)
        assertEquals(2L, second.sequence)
    }

    @Test
    fun rejectsOtherProtocolVersion() {
        val json = """{"protocolVersion":99,"type":"PING","sessionId":"s","sequence":1,"timestampNs":0,"payload":{}}"""
        try {
            ControlCodec.decode(json.encodeToByteArray())
            fail()
        } catch (e: MirrorException) {
            assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, e.code)
        }
    }

    @Test
    fun ignoresUnknownFieldsForForwardCompatibility() {
        val json = """{"protocolVersion":1,"type":"PING","sessionId":"s","sequence":1,"timestampNs":0,"payload":{"sentNs":5,"futureField":true},"futureEnvelope":1}"""
        val msg = ControlCodec.decode(json.encodeToByteArray())
        assertEquals(5L, ControlCodec.payloadOf(msg, Payloads.Ping.serializer()).sentNs)
    }

    @Test
    fun malformedJsonIsTypedError() {
        try {
            ControlCodec.decode("{nope".encodeToByteArray()); fail()
        } catch (e: MirrorException) {
            assertTrue(e.code == ErrorCode.UNKNOWN)
        }
    }
}

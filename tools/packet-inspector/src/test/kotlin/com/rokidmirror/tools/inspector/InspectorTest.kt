package com.rokidmirror.tools.inspector

import com.rokidmirror.protocol.video.VideoPacketHeader
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorTest {
    @Test
    fun describesHeaderFromHex() {
        val h = VideoPacketHeader(flags = 1, sessionShortId = 42, frameId = 7, packetSequence = 99, fragmentIndex = 0, fragmentCount = 2, payloadLength = 3, captureTimestampNs = 1000, encodeTimestampNs = 6_000_000)
        val hex = (h.toByteArray() + byteArrayOf(1, 2, 3)).joinToString("") { "%02x".format(it) }
        val text = Inspector.describeHeader(Inspector.bytesFrom(hex))
        assertTrue(text.contains("frame=7") && text.contains("fragment=1/2") && text.contains("key=true"))
    }

    @Test
    fun walksLengthPrefixedDatagrams() {
        val h = VideoPacketHeader(0, 1, 2, 3, 0, 1, 0, 0, 0).toByteArray()
        val blob = byteArrayOf(0, h.size.toByte()) + h + byteArrayOf(0, h.size.toByte()) + h
        assertTrue(Inspector.walkDatagrams(blob).size == 2)
    }
}

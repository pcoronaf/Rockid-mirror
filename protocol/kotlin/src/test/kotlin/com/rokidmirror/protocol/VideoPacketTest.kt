package com.rokidmirror.protocol

import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.Fragmenter
import com.rokidmirror.protocol.video.ReassemblyResult
import com.rokidmirror.protocol.video.Reassembler
import com.rokidmirror.protocol.video.VideoPacketHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VideoPacketTest {
    private val session = 0xDEADBEEFL

    private fun unit(id: Long, size: Int, key: Boolean = false, config: Boolean = false) =
        EncodedAccessUnit(id, key, config, id * 1000, id * 1000 + 5, Random(id).nextBytes(size))

    @Test
    fun headerRoundTrip() {
        val h = VideoPacketHeader(flags = VideoPacketHeader.Flags.KEYFRAME or VideoPacketHeader.Flags.END_OF_ACCESS_UNIT, sessionShortId = session, frameId = 0xFFFF_FFF0L, packetSequence = 7, fragmentIndex = 2, fragmentCount = 3, payloadLength = 4, captureTimestampNs = -5L, encodeTimestampNs = Long.MAX_VALUE)
        val bytes = h.toByteArray() + byteArrayOf(1, 2, 3, 4)
        assertEquals(VideoPacketHeader.SIZE, VideoPacketHeader.SIZE.also { assertEquals(40, it) })
        val parsed = VideoPacketHeader.parse(bytes)
        assertEquals(h, parsed)
        assertTrue(parsed.isKeyframe && parsed.isEndOfAccessUnit && !parsed.isConfig)
    }

    @Test
    fun fragmentAndReassembleInOrder() {
        val f = Fragmenter(session, 1400)
        val r = Reassembler(session, timeoutNs = 100_000_000)
        val au = unit(1, 5000, key = true)
        val packets = f.fragment(au)
        assertEquals(4, packets.size)
        assertTrue(packets.last().header.isEndOfAccessUnit)
        assertFalse(packets.first().header.isEndOfAccessUnit)
        var delivered: EncodedAccessUnit? = null
        for ((i, p) in packets.withIndex()) {
            val h = VideoPacketHeader.parse(p.bytes)
            val res = r.accept(h, p.bytes.copyOfRange(VideoPacketHeader.SIZE, p.bytes.size), 10L + i)
            if (res is ReassemblyResult.Complete) delivered = res.unit
        }
        assertArrayEquals(au.data, delivered!!.data)
        assertEquals(1L, delivered.frameId)
        assertTrue(delivered.isKeyframe)
        assertEquals(1000L, delivered.captureTimestampNs)
        assertEquals(1L, r.stats.framesDelivered)
        assertEquals(0L, r.stats.packetsLost)
    }

    @Test
    fun reorderedFragmentsStillAssemble() {
        val f = Fragmenter(session, 1400)
        val r = Reassembler(session, timeoutNs = 100_000_000)
        val au = unit(1, 4000, key = true)
        val packets = f.fragment(au).reversed()
        var out: EncodedAccessUnit? = null
        for (p in packets) {
            val res = r.accept(p.header, p.bytes.copyOfRange(VideoPacketHeader.SIZE, p.bytes.size), 0)
            if (res is ReassemblyResult.Complete) out = res.unit
        }
        assertArrayEquals(au.data, out!!.data)
    }

    @Test
    fun lostFragmentDropsFrameAndWaitsForKeyframe() {
        val f = Fragmenter(session, 1400)
        val r = Reassembler(session, timeoutNs = 50)
        fun feed(au: EncodedAccessUnit, skipIndex: Int = -1, t: Long): List<EncodedAccessUnit> {
            val out = mutableListOf<EncodedAccessUnit>()
            for (p in f.fragment(au)) {
                if (p.header.fragmentIndex == skipIndex) continue
                val res = r.accept(p.header, p.bytes.copyOfRange(VideoPacketHeader.SIZE, p.bytes.size), t)
                if (res is ReassemblyResult.Complete) out += res.unit
            }
            return out
        }
        assertEquals(listOf(1L), feed(unit(1, 3000, key = true), t = 0).map { it.frameId })
        assertEquals(listOf(2L), feed(unit(2, 1000), t = 10).map { it.frameId })
        // frame 3 loses a fragment; frame 4 (delta) arrives after the timeout -> 3 dropped, 4 skipped
        assertTrue(feed(unit(3, 3000), skipIndex = 1, t = 20).isEmpty())
        assertTrue(feed(unit(4, 1000), t = 100).isEmpty())
        assertTrue(r.isAwaitingKeyframe)
        assertEquals(1L, r.stats.framesDropped)
        assertEquals(1L, r.stats.framesSkippedAwaitingKeyframe)
        assertEquals(1L, r.stats.packetsLost)
        assertEquals(1L, r.stats.keyframeRequestsSuggested)
        // keyframe restores delivery
        assertEquals(listOf(5L), feed(unit(5, 2000, key = true), t = 110).map { it.frameId })
        assertFalse(r.isAwaitingKeyframe)
        assertEquals(listOf(6L), feed(unit(6, 500), t = 120).map { it.frameId })
    }

    @Test
    fun newerCompleteKeyframeSkipsOlderIncompleteFrames() {
        val f = Fragmenter(session, 1400)
        val r = Reassembler(session, timeoutNs = 1_000_000_000)
        val delivered = mutableListOf<Long>()
        fun push(au: EncodedAccessUnit, skip: Int = -1) {
            for (p in f.fragment(au)) {
                if (p.header.fragmentIndex == skip) continue
                val res = r.accept(p.header, p.bytes.copyOfRange(VideoPacketHeader.SIZE, p.bytes.size), 0)
                if (res is ReassemblyResult.Complete) delivered += res.unit.frameId
            }
        }
        push(unit(1, 1000, key = true))
        push(unit(2, 3000), skip = 0) // incomplete, never completes
        push(unit(3, 1000, key = true)) // complete keyframe -> jump ahead immediately, no timeout wait
        assertEquals(listOf(1L, 3L), delivered)
        assertEquals(1L, r.stats.framesDropped)
    }

    @Test
    fun ignoresOtherSessionsAndDuplicates() {
        val f = Fragmenter(session, 1400)
        val r = Reassembler(session, timeoutNs = 1_000)
        val p = f.fragment(unit(1, 100, key = true)).single()
        val payload = p.bytes.copyOfRange(VideoPacketHeader.SIZE, p.bytes.size)
        assertTrue(r.accept(p.header.copy(sessionShortId = 1), payload, 0) is ReassemblyResult.Ignored)
        assertTrue(r.accept(p.header, payload, 0) is ReassemblyResult.Complete)
        assertTrue(r.accept(p.header, payload, 0) is ReassemblyResult.Ignored)
    }

    @Test
    fun sealCallbackCanGrowPayloadWithinBudget() {
        val f = Fragmenter(session, 1400, sealOverheadBytes = 16)
        val packets = f.fragment(unit(9, 10_000)) { _, plain -> plain + ByteArray(16) }
        assertTrue(packets.all { it.bytes.size <= 1400 })
        assertTrue(packets.all { VideoPacketHeader.parse(it.bytes).payloadLength == it.bytes.size - VideoPacketHeader.SIZE })
    }
}

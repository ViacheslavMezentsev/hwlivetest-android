package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RttFrameDecoderTest {
    private fun frame(seq: Int, tick: Long, vararg words: Long): ByteArray =
        ByteBuffer.allocate(8 + words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD1A6.toShort()).putShort(seq.toShort()).putInt(tick.toInt())
            .apply { words.forEach { putInt(it.toInt()) } }.array()

    @Test fun everySplitAndUnsignedPayload() {
        val bytes = frame(65535, 0xFFFFFFFFL, 0xFFFFFFFFL, 0xD1A6L)
        for (split in 0..bytes.size) {
            val decoder = RttFrameDecoder(2)
            val parsed = decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(listOf(RttFrame(65535, 0xFFFFFFFFL, listOf(0xFFFFFFFFL, 0xD1A6L))), parsed)
            assertEquals(0, decoder.pendingBytes)
        }
    }

    @Test fun garbageAndBytewiseInput() {
        val decoder = RttFrameDecoder(1)
        val bytes = byteArrayOf(0, 1, 0xA6.toByte()) + frame(1, 2, 3)
        val parsed = bytes.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(1, parsed.size)
        assertEquals(3L, decoder.stats.discardedBytes)
    }

    @Test fun wrapLossDuplicateAndBackwardCounters() {
        val decoder = RttFrameDecoder(1)
        decoder.feed(frame(65535, 0xFFFFFFFFL, 0) + frame(0, 0, 0) +
            frame(3, 10, 0) + frame(3, 10, 0) + frame(2, 9, 0))
        assertEquals(5L, decoder.stats.frames)
        assertEquals(2L, decoder.stats.missingEstimate)
        assertEquals(1L, decoder.stats.duplicates)
        assertEquals(1L, decoder.stats.sequenceDiscontinuities)
        assertEquals(1L, decoder.stats.tickWraps)
        assertEquals(1L, decoder.stats.repeatedTicks)
        assertEquals(1L, decoder.stats.tickBackwards)
    }

    @Test fun incompleteTailAndReset() {
        val decoder = RttFrameDecoder(1)
        assertTrue(decoder.feed(frame(4, 5, 6).copyOf(11)).isEmpty())
        assertEquals(11, decoder.pendingBytes)
        decoder.reset()
        assertEquals(0, decoder.pendingBytes)
        assertEquals(RttStreamStats(), decoder.stats)
        assertEquals(1, decoder.feed(frame(0, 0, 0)).size)
        assertEquals(0L, decoder.stats.missingEstimate)
    }

    @Test fun largeNoiseDoesNotAccumulate() {
        val decoder = RttFrameDecoder(1)
        decoder.feed(ByteArray(100000))
        assertEquals(0, decoder.pendingBytes)
        assertEquals(100000L, decoder.stats.discardedBytes)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectOversizedLayout() {
        RttFrameDecoder(65)
    }
}

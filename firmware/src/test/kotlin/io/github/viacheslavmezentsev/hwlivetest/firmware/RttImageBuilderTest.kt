package io.github.viacheslavmezentsev.hwlivetest.firmware

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttDoctor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RttImageBuilderTest {
    private fun build(fields: List<Long> = listOf(0x20000000L), available: Long = 6656,
        tick: Long = 0x20004750) = RttImageBuilder.build(0x2001DE00, available, fields,
        tick, 412, RttDoctor.timers.first(), 100_000_000)

    @Test fun referenceBinaryAndAbi() {
        val b = build((0..5).map { 0x20000000L + it * 4 })
        val i = b.image
        assertEquals(5020, i.bytes.size)
        assertEquals(0x2001F000L, i.vectors)
        assertEquals(100_000_000L, (i.prescaler + 1L) * (i.reload + 1L) * 100)
        val words = ByteBuffer.wrap(i.bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word(address: Long) = words.getInt((address - i.base).toInt()).toUInt().toLong()
        assertEquals(b.buffer, word(b.control + 28))
        assertEquals(b.bufferBytes.toLong(), word(b.control + 32))
        assertEquals(0L, word(b.control + 36))
        assertEquals(i.tick, word(i.stubStart + 148))
        assertEquals(32L, word(i.stubStart + 144))
        val table = word(i.stubStart + 140)
        assertEquals(6L, word(table))
        assertEquals(0x20000014L, word(table + 24))
        // Reproduce the independently assembled reference hash, with unpatched parameters.
        val stub = ByteArray(152)
        i.bytes.copyInto(stub, 0, (i.stubStart - i.base).toInt(), (i.stubEnd - i.base).toInt())
        val hash = MessageDigest.getInstance("SHA-256").digest(stub).joinToString("") { "%02x".format(it) }
        assertEquals("856583cca9c26943882d13c01d94b0eb1961fc0c8fc1450e733ed1656750253c", hash)
    }

    @Test fun allChannelCountsFitWholeFrames() {
        for (count in 1..6) {
            val b = build((0 until count).map { 0x20000000L + it * 4 })
            assertEquals(0, b.bufferBytes % (8 + 4 * count))
            assertTrue(b.image.stubEnd + 32 <= b.image.vectors)
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsPeripheralsUnalignedAndImageOverlap() {
        rejected { build(listOf(0x40000000)) }
        rejected { build(listOf(0x20000001)) }
        rejected { build(listOf(0x2001DE00)) }
        rejected { build(tick = 0x2001DF00) }
    }
    @Test fun rejectsInsufficientSpaceAndChannelCounts() {
        rejected { build(available = 4096) }
        rejected { build(emptyList()) }
        rejected { build(List(7) { 0x20000000 }) }
    }
}

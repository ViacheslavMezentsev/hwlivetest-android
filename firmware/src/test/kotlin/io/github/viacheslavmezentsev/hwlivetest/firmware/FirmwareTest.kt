package io.github.viacheslavmezentsev.hwlivetest.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareTest {
    /** Память: Flash с 0x08000000, байт = младший байт адреса. */
    private val flash = { a: Long, n: Int ->
        IntArray(n) { k ->
            val b = a + 4L * k
            ((b and 0xFF) or (((b + 1) and 0xFF) shl 8) or (((b + 2) and 0xFF) shl 16) or (((b + 3) and 0xFF) shl 24)).toInt()
        }
    }

    @Test
    fun parsesStandardHex() {
        val hex = """
            :020000040800F2
            :100000000000022009010008110100081301000886
            :04001000FFFFFFFFF0
            :0400000508000101ED
            :00000001FF
        """.trimIndent()
        val s = IntelHex.parse(hex)
        assertEquals(1, s.size)
        assertEquals(0x08000000L, s[0].address)
        assertEquals(20, s[0].data.size)
        assertEquals(0x20, s[0].data[3].toInt())
    }

    @Test(expected = IntelHex.FormatException::class)
    fun rejectsBadChecksum() {
        IntelHex.parse(":020000040800F3\n:00000001FF")
    }

    @Test
    fun writeThenParseRoundTripsAcross64k() {
        val data = ByteArray(0x10020) { (it * 7).toByte() }
        val text = IntelHex.write(Segment(0x0800FFF0L, data), startAddress = 0x08000101L)
        val back = IntelHex.parse(text)
        assertEquals(1, back.size)
        assertEquals(0x0800FFF0L, back[0].address)
        assertArrayEquals(data, back[0].data)
        assertTrue(text.endsWith(":00000001FF\r\n"))
    }

    @Test
    fun readsInAligned1kChunks() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val data = FlashTools.read(0x08000002L, 2050, { a, n -> calls += a to n; flash(a, n) })
        assertEquals(2050, data.size)
        assertEquals(0x02, data[0].toInt())
        assertEquals(listOf(0x08000000L to 256, 0x08000400L to 256, 0x08000800L to 1), calls)
    }

    @Test
    fun checkFindsMismatches() {
        val good = Segment(0x08000010L, ByteArray(32) { (0x10 + it).toByte() })
        assertTrue(FlashTools.check(listOf(good), flash).ok)
        val bad = Segment(0x08000010L, ByteArray(32) { if (it == 5) 0 else (0x10 + it).toByte() })
        val r = FlashTools.check(listOf(bad), flash)
        assertFalse(r.ok)
        assertEquals(1, r.mismatches)
        assertEquals(0x08000015L, r.first[0].address)
    }

    @Test
    fun trimsErasedTail() {
        val d = ByteArray(64) { if (it < 20) 1 else -1 }
        assertEquals(32, FlashTools.trimErased(d).size)
        assertEquals(0, FlashTools.trimErased(ByteArray(16) { -1 }).size)
    }

    @Test
    fun sha256Known() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", IntelHex.sha256(ByteArray(0)))
    }
}

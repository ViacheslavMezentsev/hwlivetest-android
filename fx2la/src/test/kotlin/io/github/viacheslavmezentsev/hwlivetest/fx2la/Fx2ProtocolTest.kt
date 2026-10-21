package io.github.viacheslavmezentsev.hwlivetest.fx2la

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Fx2ProtocolTest {
    /** TC-07: байты START совпадают с приложением C ТЗ ЛА. */
    @Test
    fun startBytesMatchAppendixC() {
        val expected = mapOf(
            20_000L to "00 05 db", 25_000L to "00 04 af", 50_000L to "40 03 bf", 100_000L to "40 01 df",
            200_000L to "40 00 ef", 250_000L to "40 00 bf", 500_000L to "40 00 5f", 1_000_000L to "40 00 2f",
            2_000_000L to "40 00 17", 3_000_000L to "40 00 0f", 4_000_000L to "40 00 0b", 6_000_000L to "40 00 07",
            8_000_000L to "40 00 05", 12_000_000L to "40 00 03", 16_000_000L to "40 00 02", 24_000_000L to "40 00 01",
        )
        assertEquals(Fx2Protocol.SAMPLE_RATES.toSet(), expected.keys)
        for ((rate, hex) in expected) {
            val bytes = Fx2Protocol.start(rate)!!.bytes().joinToString(" ") { "%02x".format(it) }
            assertEquals("$rate", hex, bytes)
        }
    }

    @Test
    fun unreachableRatesAreRejected() {
        assertNull(Fx2Protocol.start(48_000_000L))
        assertNull(Fx2Protocol.start(7_000_000L))
        assertNull(Fx2Protocol.start(10_000L))
    }

    @Test
    fun buffersFollowTenMillisecondsAndHalfSecondQueue() {
        assertEquals(240_128, Fx2Protocol.bufferSize(24_000_000L, 262_144))
        assertEquals(32, Fx2Protocol.transferCount(24_000_000L, 240_128))
        assertEquals(16_384, Fx2Protocol.bufferSize(24_000_000L, 16_384))
        assertEquals(512, Fx2Protocol.bufferSize(20_000L, 262_144))
        assertEquals(20, Fx2Protocol.transferCount(20_000L, 512))
        assertEquals(10_240, Fx2Protocol.bufferSize(1_000_000L, 262_144))
    }

    /** TC-08: на полной скорости USB частоты выше 500 кГц недоступны. */
    @Test
    fun fullSpeedLimitsRates() {
        assertEquals(500_000L, Fx2Protocol.allowedRates(false).max())
        assertEquals(24_000_000L, Fx2Protocol.allowedRates(true).max())
        assertFalse(Fx2Protocol.isHighSpeed(64))
        assertTrue(Fx2Protocol.isHighSpeed(512))
    }

    /** TC-01. */
    @Test
    fun knownDevices() {
        assertTrue(Fx2Ids.isFx2(0x0925, 0x3881))
        assertTrue(Fx2Ids.isFx2(0x1D50, 0x608C))
        assertTrue(Fx2Ids.isFx2(0x04B4, 0x8613))
        assertFalse(Fx2Ids.isFx2(0x0483, 0x3748))
        assertEquals("fx2lafw-saleae-logic.fw", Fx2Ids.find(0x0925, 0x3881)!!.firmwareName)
    }
}

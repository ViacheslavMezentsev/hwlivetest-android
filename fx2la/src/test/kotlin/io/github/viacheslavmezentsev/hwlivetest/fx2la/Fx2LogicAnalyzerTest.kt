package io.github.viacheslavmezentsev.hwlivetest.fx2la

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Fx2LogicAnalyzerTest {
    /** TC-02: последовательность загрузки — сброс, порции, запуск. */
    @Test
    fun uploadHoldsCpuInResetAroundChunks() {
        val t = FakeFx2(firmware = null)
        val la = Fx2LogicAnalyzer(t)
        assertNull(la.firmwareVersion())
        la.uploadFirmware(Fx2Firmware.parse(ByteArray(5000) { 2 }))
        assertEquals(listOf(0xE600, 0, 4096, 0xE600), t.out.map { it.second })
        assertTrue(t.out.all { it.first == 0xA0 })
        assertArrayEquals(byteArrayOf(1), t.out.first().third)
        assertArrayEquals(byteArrayOf(0), t.out.last().third)
    }

    /** TC-06. */
    @Test
    fun firmwareVersionIsChecked() {
        assertEquals("1.3", Fx2LogicAnalyzer(FakeFx2()).requireFirmware().toString())
        try {
            Fx2LogicAnalyzer(FakeFx2(firmware = Fx2Protocol.Version(2, 0))).requireFirmware()
            fail()
        } catch (e: Fx2Exception) {
            assertEquals(Fx2ErrorKind.FIRMWARE_VERSION, e.kind)
        }
        try {
            Fx2LogicAnalyzer(FakeFx2(firmware = null)).requireFirmware()
            fail()
        } catch (e: Fx2Exception) {
            assertEquals(Fx2ErrorKind.NO_FIRMWARE, e.kind)
        }
    }

    /** TC-09, TC-29: поток 24 МГц, статистика каналов по синтетическому сигналу. */
    @Test
    fun probeCountsLevelsAndEdges() {
        // D0: меандр 1 МГц (24 отсчёта на период); D1: ШИМ 25 % с периодом 240 отсчётов (100 кГц);
        // D7: всегда 1; остальные — 0.
        val t = FakeFx2(sample = { n ->
            val d0 = if (n % 24 < 12) 1 else 0
            val d1 = if (n % 240 < 60) 2 else 0
            d0 or d1 or 0x80
        })
        val r = Fx2LogicAnalyzer(t).probeCapture(24_000_000L, 1000)
        assertEquals(24_000_000L, r.receivedSamples)
        assertFalse(r.stalled)
        assertTrue(r.keepsUp)
        assertEquals(1, t.out.size)
        assertArrayEquals(byteArrayOf(0x40, 0, 1), t.out[0].third)
        assertEquals(240_128 to 32, t.streams.single())
        val c = r.channels
        assertEquals(1_000_000.0, c[0].frequencyHz(r.captureSeconds), 1.0)
        assertEquals(0.5, c[0].duty, 1e-6)
        assertEquals(100_000.0, c[1].frequencyHz(r.captureSeconds), 1.0)
        assertEquals(0.25, c[1].duty, 1e-6)
        assertEquals("1", c[7].level)
        assertEquals("0", c[3].level)
        assertEquals("перепады", c[0].level)
    }

    /** TC-10: поток вдвое медленнее нужного. */
    @Test
    fun slowStreamIsReported() {
        val r = Fx2LogicAnalyzer(FakeFx2(nsPerByte = 2e9 / 24e6)).probeCapture(24_000_000L, 500)
        assertFalse(r.keepsUp)
        assertEquals(0.5, r.keepUpRatio, 0.01)
    }

    /** TC-11: обрыв потока — полученные данные сохраняются. */
    @Test
    fun stallKeepsReceivedData() {
        val r = Fx2LogicAnalyzer(FakeFx2(stallAfter = 1_000_000L)).probeCapture(8_000_000L, 1000)
        assertTrue(r.stalled)
        assertEquals(1_000_000L, r.receivedSamples)
        assertFalse(r.keepsUp)
    }

    /** TC-08, TC-26: неверные параметры отвергаются без обращения к прибору. */
    @Test
    fun badConfigDoesNotTouchDevice() {
        val fs = FakeFx2(packetSize = 64)
        try {
            Fx2LogicAnalyzer(fs).probeCapture(1_000_000L, 100)
            fail()
        } catch (e: Fx2Exception) {
            assertEquals(Fx2ErrorKind.BAD_CONFIG, e.kind)
            assertTrue(e.message!!.contains("полной скорости"))
        }
        try {
            Fx2LogicAnalyzer(FakeFx2()).probeCapture(7_000_000L, 100)
            fail()
        } catch (e: Fx2Exception) {
            assertEquals(Fx2ErrorKind.BAD_CONFIG, e.kind)
        }
        assertTrue(fs.out.isEmpty())
    }
}

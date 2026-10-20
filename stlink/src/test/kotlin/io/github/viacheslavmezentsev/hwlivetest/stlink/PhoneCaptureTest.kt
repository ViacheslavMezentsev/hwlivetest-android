package io.github.viacheslavmezentsev.hwlivetest.stlink

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.CortexCore
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.Jep106
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCaptureTest {

    /** Настоящая запись с телефона: V2-1 от Nucleo + плата с STM32F411. */
    @Test
    fun stm32f411OverNucleoV21() {
        val t = ReplayTransport.fromResource("phone-v21-stm32f411.txt")
        val st = StLink(t)
        val c = st.connect()
        assertEquals("V2J45M31", c.version.label)
        assertEquals(StLinkMode.MASS, c.initialMode)
        assertEquals(3.23, c.targetVoltage!!, 0.01)
        assertEquals(0x2BA01477, c.dpIdCode)
        assertTrue(c.warnings.isEmpty())

        val info = TargetIdentifier(st).identify()
        assertEquals(CortexCore.M4, info.cpuId?.core)
        assertEquals("r0p1", info.cpuId?.revisionLabel)
        assertEquals("MEM-AP", info.romBaseSource)
        assertEquals(Jep106.ST, info.rom?.designer)
        // У F411 part ROM-таблицы (0x411) не равен DEV_ID (0x431) — это нормально.
        assertEquals(0x411, info.rom?.partNo)
        assertEquals(McuVendor.STM32, info.vendor)
        assertEquals(0x431, info.stm32Part?.devId)
        assertEquals(0x1000, (info.idRegister!! ushr 16) and 0xFFFF)
        assertEquals(512, info.flashSizeKb)
        assertTrue(info.notes.toString(), info.notes.isEmpty())
        assertEquals("STM32F411xC/E", info.shortName)

        repeat(2) { assertEquals(0x410FC241, st.read32(0xE000ED00L)) }
        t.assertFinished()
    }
}

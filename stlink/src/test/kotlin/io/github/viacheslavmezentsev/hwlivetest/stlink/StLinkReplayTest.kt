package io.github.viacheslavmezentsev.hwlivetest.stlink

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.CortexCore
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.Jep106
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StLinkReplayTest {

    @Test
    fun bluePillF103OverV2() {
        val t = ReplayTransport.fromResource("synthetic-v2-f103.txt")
        val log = mutableListOf<String>()
        val st = StLink(t) { log += it }
        val c = st.connect()
        assertEquals("V2J37S7", c.version.label)
        assertEquals(StLinkMode.DFU, c.initialMode)
        assertEquals(3.28, c.targetVoltage!!, 0.01)
        assertEquals(0x1BA01477, c.dpIdCode)

        val info = TargetIdentifier(st).identify()
        assertEquals(CortexCore.M3, info.cpuId?.core)
        assertEquals("r1p1", info.cpuId?.revisionLabel)
        assertEquals(0xE00FF000L, info.romBase)
        assertEquals(Jep106.ST, info.rom?.designer)
        assertEquals(0x410, info.rom?.partNo)
        assertEquals(McuVendor.STM32, info.vendor)
        assertEquals(0x410, info.stm32Part?.devId)
        assertEquals(128, info.flashSizeKb)
        assertTrue(info.notes.toString(), info.notes.isEmpty())
        t.assertFinished()

        // Журнал в том же формате, что и запись: его можно сразу превратить в тест.
        assertEquals("> f1 80", log.first())
        assertEquals("< 29 47 83 04 48 37", log[1])
    }

    @Test
    fun nucleoF030OverV21UsesM0DbgmcuAddress() {
        val t = ReplayTransport.fromResource("synthetic-v21-f030.txt")
        val st = StLink(t)
        val c = st.connect()
        assertEquals("V2J37M26", c.version.label)
        assertEquals(0x0BB11477, c.dpIdCode)
        val info = TargetIdentifier(st).identify()
        assertEquals(CortexCore.M0, info.cpuId?.core)
        assertEquals(0x40015800L, info.idRegisterAddress)
        assertEquals(0x440, info.stm32Part?.devId)
        assertEquals(64, info.flashSizeKb)
        t.assertFinished()
    }

    @Test
    fun noTargetGivesClearError() {
        val t = ReplayTransport.fromResource("synthetic-v2-no-target.txt")
        val st = StLink(t)
        try {
            st.connect()
            fail("ожидалась ошибка")
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.NO_TARGET, e.kind)
            assertEquals(0x09, e.status)
        }
        t.assertFinished()
    }

    @Test
    fun v3VersionUsesApiV3Command() {
        val t = ReplayTransport.fromResource("synthetic-v3-version.txt")
        val v = StLink(t).readVersion()
        assertEquals("V3J7M3B5S1", v.label)
        t.assertFinished()
    }

    @Test
    fun oldFirmwareIsRejected() {
        val t = ReplayTransport.fromResource("synthetic-v2-old-firmware.txt")
        try {
            StLink(t).connect()
            fail("ожидалась ошибка")
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.OLD_FIRMWARE, e.kind)
        }
        t.assertFinished()
    }

    @Test
    fun unalignedReadIsBadArgument() {
        val st = StLink(ReplayTransport(emptyList()))
        try {
            st.read32(0x20000001L)
            fail("ожидалась ошибка")
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.BAD_ARGUMENT, e.kind)
        }
    }

    @Test
    fun faultOnReadIsSwdFault() {
        val t = ReplayTransport.parse(
            """
            > f2 36 00 00 00 60
            < 11 00 00 00 00 00 00 00
            """.trimIndent(),
        )
        try {
            StLink(t).read32(0x60000000L)
            fail("ожидалась ошибка")
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.SWD_FAULT, e.kind)
            assertEquals(0x11, e.status)
        }
    }
}

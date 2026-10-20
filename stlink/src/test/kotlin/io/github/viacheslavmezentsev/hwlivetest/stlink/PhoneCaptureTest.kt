package io.github.viacheslavmezentsev.hwlivetest.stlink

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.CortexCore
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.Jep106
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
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

    private fun identify(name: String, waitRetries: Int = StLink.DEFAULT_WAIT_RETRIES): Triple<ReplayTransport, StLink, TargetInfo> {
        val t = ReplayTransport.fromResource(name)
        val st = StLink(t, waitRetries = waitRetries, sleep = {})
        st.connect()
        return Triple(t, st, TargetIdentifier(st).identify())
    }

    @Test
    fun stm32f103OverV21() {
        val (t, st, info) = identify("phone-v21-stm32f103.txt")
        assertEquals("V2J43M28", st.version?.label)
        assertEquals(CortexCore.M3, info.cpuId?.core)
        assertEquals("r1p1", info.cpuId?.revisionLabel)
        assertEquals(0x410, info.rom?.partNo)
        assertEquals(McuVendor.STM32, info.vendor)
        assertEquals(0x410, info.stm32Part?.devId)
        assertEquals(128, info.flashSizeKb)
        assertTrue(info.notes.toString(), info.notes.isEmpty())
        t.assertFinished()
    }

    @Test
    fun at32f403aOverV21() {
        val (t, _, info) = identify("phone-v21-at32f403a.txt")
        assertEquals(CortexCore.M4, info.cpuId?.core)
        assertEquals(Jep106.ARM, info.rom?.designer)
        assertEquals(0x4C4, info.rom?.partNo)
        assertEquals(McuVendor.AT32, info.vendor)
        assertEquals(0x70050347, info.idRegister)
        assertEquals(1024, info.flashSizeKb)
        t.assertFinished()
    }

    @Test
    fun nucleoF030r8OnboardV21() {
        val (t, st, info) = identify("phone-v21-nucleo-f030r8.txt")
        assertEquals("V2J45M31", st.version?.label)
        assertEquals("0483:374B", st.version?.vidPid)
        assertEquals(CortexCore.M0, info.cpuId?.core)
        assertEquals(0x40015800L, info.idRegisterAddress)
        assertEquals(0x440, info.stm32Part?.devId)
        assertEquals(64, info.flashSizeKb)
        assertTrue(info.notes.toString(), info.notes.isEmpty())
        t.assertFinished()
    }

    /** Записано старой версией без повторов: WAIT попадает в заметки, остальное читается. */
    @Test
    fun stm32f401WithDpWaitWithoutRetries() {
        val (t, st, info) = identify("phone-v21-stm32f401-wait.txt", waitRetries = 0)
        assertEquals(null, info.cpuId)
        assertEquals(null, info.rom)
        assertEquals(0x423, info.stm32Part?.devId)
        assertEquals(null, info.flashSizeKb)
        assertEquals(3, info.notes.count { "DP WAIT" in it })
        assertEquals(0x410FC241, st.read32(0xE000ED00L))
        t.assertFinished()
    }

    @Test
    fun f429iDiscoOnboardV2StartsInDfu() {
        val (t, st, info) = identify("phone-v2-f429idisco-wait.txt", waitRetries = 0)
        assertEquals("V2J39S0", st.version?.label)
        assertEquals(CortexCore.M4, info.cpuId?.core)
        assertEquals(0x411, info.rom?.partNo)
        assertEquals("STM32F42x/43x", info.shortName)
        assertEquals(null, info.flashSizeKb)
        assertTrue(info.notes.single().contains("DP WAIT"))
        t.assertFinished()
    }
}

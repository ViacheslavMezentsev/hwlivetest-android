package io.github.viacheslavmezentsev.hwlivetest.diag

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.*
import org.junit.Assert.*
import org.junit.Test

class GenericCortexTest {
    private fun target(cpuid: Int = 0x410FC241) = TargetInfo(
        CpuId(cpuid), null, "", null, null, null, McuVendor.UNKNOWN, null, null, null, emptyList(),
    )

    @Test fun unknownM4ReadsOnlyCoreRegistersAndDoesNotInventFlashOrClocks() {
        val reads = mutableListOf<Long>()
        var tick = 100
        val mem = object : DebugMemory {
            override fun read32(address: Long): Int {
                reads += address
                assertTrue("Unexpected device-specific read: $address", address >= 0xE0000000L)
                return when (address) {
                    Diagnostics.DHCSR -> 0x01000000
                    Diagnostics.SYST_CSR -> 1
                    Diagnostics.SYST_RVR -> 7999
                    Diagnostics.SYST_CVR -> tick--
                    else -> 0
                }
            }
        }
        val report = Diagnostics(mem, target(), sleep = {}).run()
        assertTrue(report.text().contains("Карта Flash не определена"))
        assertTrue(report.text().contains("внешний опорный сигнал"))
        assertFalse(report.text().contains("HCLK/8"))
        assertFalse(report.summary.contains("Flash чистая"))
        assertFalse(reads.contains(0x08000000L))
        assertFalse(reads.contains(0x40023800L))
    }

    @Test fun baselineDoesNotReadMainlineFaultOrTraceRegisters() {
        val reads = mutableListOf<Long>()
        val mem = object : DebugMemory {
            override fun read32(address: Long): Int { reads += address; return 0 }
        }
        Diagnostics(mem, target(0x410CC601), sleep = {}).run()
        assertFalse(reads.contains(Diagnostics.CFSR))
        assertFalse(reads.contains(Diagnostics.HFSR))
        assertFalse(reads.contains(Diagnostics.DEMCR))
        assertFalse(reads.contains(Diagnostics.VTOR))
    }

    @Test fun manualF4EnablesReadHeuristicsWithoutChangingIdentification() {
        val chosen = target().copy(profileOverride = McuProfile.STM32_F4)
        val reads = mutableListOf<Long>()
        val mem = object : DebugMemory {
            override fun read32(address: Long): Int { reads += address; return 0 }
        }
        val report = Diagnostics(mem, chosen, sleep = {}).run()
        assertTrue(reads.contains(0x40023800L))
        assertTrue(reads.contains(0x08000000L))
        assertTrue(report.text().contains("Выбран пользователем"))
        assertEquals(McuVendor.UNKNOWN, chosen.vendor)
        assertNull(chosen.stm32Part)
        assertNull(chosen.flashSizeKb)
        assertEquals(McuProfile.GENERIC, chosen.copy(profileOverride = null).profile)
    }
}

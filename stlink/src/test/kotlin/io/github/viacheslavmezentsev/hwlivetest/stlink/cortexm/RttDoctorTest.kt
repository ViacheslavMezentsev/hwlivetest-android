package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import org.junit.Assert.*
import org.junit.Test

class RttDoctorTest {
    @Test fun readsOnlyExplicitRegistersAndHandlesSecondNvicBank() {
        val reads = mutableListOf<Long>()
        val memory = object : DebugMemory {
            override fun read32(address: Long): Int {
                reads += address
                return when (address) {
                    0x40023840L -> 1 // TIM2 clocked, even if CEN = 0
                    0xE000E204L -> 1 shl 18 // TIM5 pending, bank 1
                    else -> 0
                }
            }
        }
        val result = RttDoctor.inspectF411(memory, 0x431)
        assertEquals(listOf(false, true, true, false), result.map { it.candidate })
        assertTrue(result.last().pending)
        assertEquals(21, reads.size)
        assertFalse(reads.any { it == 0xE000EDF0L || it == 0x40000010L })
    }
    @Test fun wrongDeviceDoesNotReadAnything() {
        val memory = object : DebugMemory { override fun read32(address: Long): Int = error("Must not read") }
        try { RttDoctor.inspectF411(memory, 0x413); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun eachBusyFlagExcludesTimer() {
        val t = RttDoctor.timers.first()
        val clear = RttDoctor.Observation(t, false, 0, 0, false, false, false)
        listOf(clear.copy(cr1 = 1), clear.copy(dier = 2), clear.copy(enabled = true),
            clear.copy(active = true), clear.copy(pending = true), clear.copy(clockEnabled = true))
            .forEach { assertFalse(it.candidate) }
    }
}

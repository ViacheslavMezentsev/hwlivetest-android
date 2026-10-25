package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.*
import org.junit.Assert.*
import org.junit.Test

class RttTeardownTest {
    private val plan = RttTeardown.Plan(RttDoctor.timers.first(), 0x20001000, 0x20001078, 0x20000000, 0x08000000, 0)
    private class Target : DebugMemory, TargetWrite {
        val mem = mutableMapOf<Long, Int>(0x40023840L to 0x81, 0xE000ED08L to 0x20002000)
        var halted = false
        var pc = 0x20001000L
        var active = true
        var endless = false
        var runs = 0
        var frozenOnRun = 0
        var failVtor = false
        var restoreWhileActive = false
        override fun read32(address: Long): Int = if (address == 0xE000E300L && active) 1 shl 28 else mem[address] ?: 0
        override fun write32(address: Long, value: Int) {
            if (address == 0xE000ED08L) { restoreWhileActive = active; if (failVtor) return }
            mem[address] = value
        }
        override fun readProgramCounter() = pc
        override fun step(): CoreState { if (!endless) active = false; return coreState() }
        override fun coreState() = CoreState(if (halted) CoreState.S_HALT else 0)
        override fun halt(): CoreState { halted = true; return coreState() }
        override fun go(): CoreState {
            halted = false; runs++
            if (frozenOnRun != runs) mem[0x20000000] = (mem[0x20000000] ?: 0) + 250
            return coreState()
        }
        fun elapsed() { if (!halted && frozenOnRun != runs) mem[0x20000000] = (mem[0x20000000] ?: 0) + 250 }
        override fun reset(): CoreState = error("No reset allowed")
        override fun resetHalt(): CoreState = error("No reset allowed")
        override fun pulseNrst(): Unit = error("No reset allowed")
        override fun detach(): CoreState = error("No implicit detach")
        override fun writeBlock32(address: Long, words: IntArray): Unit = error("No block writes")
        override fun writeBlock16(address: Long, halves: ShortArray): Unit = error("No block writes")
        override fun writeBytes(address: Long, bytes: ByteArray): Unit = error("No block writes")
    }
    private fun run(t: Target) = RttTeardown(t, t) { t.elapsed() }.run(plan, 4)
    @Test fun drainsRestoresAndChecksLiveness() {
        val t = Target(); val result = run(t)
        assertTrue(result.toString(), result.ok)
        assertEquals(1, result.steps)
        assertFalse(t.restoreWhileActive)
        assertEquals(0x80, t.mem[0x40023840]) // preserve firmware's unrelated RCC bit
        assertEquals(0x08000000, t.mem[0xE000ED08])
        assertFalse(t.halted)
    }
    @Test fun refusesUnknownHandler() {
        val t = Target(); t.pc = 0x08001000
        val result = run(t)
        assertFalse(result.ok); assertEquals("drain_handler", result.stage); assertTrue(t.halted)
        assertEquals(0x20002000, t.mem[0xE000ED08])
    }
    @Test fun limitsSteps() {
        val t = Target(); t.endless = true
        val result = run(t)
        assertFalse(result.ok); assertEquals(4, result.steps); assertTrue(t.halted)
    }
    @Test fun frozenDuringQuiescenceDoesNotRestoreVector() {
        val t = Target(); t.frozenOnRun = 1
        val result = run(t)
        assertFalse(result.ok); assertEquals("quiesce", result.stage)
        assertEquals(0x20002000, t.mem[0xE000ED08]); assertTrue(t.halted)
    }
    @Test fun frozenAfterResumeIsNotSuccess() {
        val t = Target(); t.frozenOnRun = 2
        val result = run(t)
        assertFalse(result.ok); assertEquals("liveness", result.stage); assertTrue(t.halted)
    }
    @Test fun readbackFailureStopsCore() {
        val t = Target(); t.failVtor = true
        val result = run(t)
        assertFalse(result.ok); assertEquals("restore", result.stage); assertTrue(t.halted)
    }
}

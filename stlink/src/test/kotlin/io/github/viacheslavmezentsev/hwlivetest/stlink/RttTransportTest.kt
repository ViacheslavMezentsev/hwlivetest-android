package io.github.viacheslavmezentsev.hwlivetest.stlink

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.*
import org.junit.Assert.*
import org.junit.Test

class RttTransportTest {
    private val sim = SimTarget()
    private val st = StLink(sim, sleep = {})
    private val tw = st.targetWrite(WritePermit.grant("RTT test"))
    private val cb = 0x20000000L
    private val buffer = 0x20000100L
    private fun ring(rd: Int, wr: Int): RttRingReader {
        val header = intArrayOf(0x47414944, 0x54545220, 0, 0, 1, 0, 0, buffer.toInt(), 32, wr, rd, 0)
        header.forEachIndexed { i, value -> tw.write32(cb + 4 * i, value) }
        repeat(8) { tw.write32(buffer + 4 * it, it) }
        return RttRingReader(st, tw, cb, buffer, 32)
    }
    @Test fun wrapsAndAcknowledgesExactlyCopiedBytes() {
        val r = ring(24, 8)
        assertArrayEquals(byteArrayOf(6,0,0,0,7,0,0,0,0,0,0,0), r.poll(12))
        assertEquals(4, sim.word(cb + 40))
        assertArrayEquals(byteArrayOf(1,0,0,0), r.poll())
        assertEquals(8, sim.word(cb + 40))
        assertTrue(r.poll().isEmpty())
    }
    @Test fun changedDescriptorDoesNotAcknowledge() {
        val r = ring(0, 8)
        tw.write32(cb + 28, 0x40000000)
        try { r.poll(); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, sim.word(cb + 40))
    }
    @Test fun externalConsumerIsRejected() {
        val r = ring(0, 8); r.poll(4)
        tw.write32(cb + 40, 0)
        try { r.poll(); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun ambiguousAckFailurePermanentlyStopsReader() {
        ring(0, 8)
        val broken = object : TargetWrite by tw {
            override fun write32(address: Long, value: Int) { tw.write32(address, value); error("USB completion lost") }
        }
        val r = RttRingReader(st, broken, cb, buffer, 32)
        try { r.poll(); fail() } catch (_: IllegalStateException) { }
        val traffic = sim.commands.size
        try { r.poll(); fail() } catch (_: IllegalStateException) { }
        assertEquals(traffic, sim.commands.size)
    }
    private fun image() = RttInstaller.Image(0x20001000, ByteArray(1024), 0x20001200, 404,
        0x20001080, 0x20001100, 0x20000000, RttDoctor.timers.first(), 9, 9999)
    @Test fun installsCopiesVectorsAndReturnsRecovery() {
        tw.write32(0xE000ED08, 0x08000000)
        tw.write32(0x08000000 + 4 * 20, 0x08001235)
        val result = RttInstaller(st, tw).install(image(), 0x431)
        assertTrue(result.toString(), result.started)
        assertEquals(0x08000000, result.recovery!!.originalVtor)
        assertEquals(0x08001235, sim.word(0x20001200 + 4 * 20))
        assertEquals(0x20001081, sim.word(0x20001200 + 4 * 44))
        assertFalse(sim.halted)
        assertEquals(0, sim.resetCount)
    }
    @Test fun refusesBusyTimerBeforeUpload() {
        tw.write32(0x40023840, 1)
        val result = RttInstaller(st, tw).install(image(), 0x431)
        assertFalse(result.started); assertEquals("resources", result.stage)
        assertTrue(sim.halted); assertNull(result.recovery)
        assertFalse(sim.mem.containsKey(image().base))
    }
    @Test fun partialInstallRetainsRecoveryAndHalts() {
        val broken = object : TargetWrite by tw {
            override fun write32(address: Long, value: Int) {
                if (address == 0xE000ED08L) error("USB disconnected")
                tw.write32(address, value)
            }
        }
        val result = RttInstaller(st, broken).install(image(), 0x431)
        assertFalse(result.started); assertEquals("configure", result.stage)
        assertNotNull(result.recovery); assertTrue(sim.halted); assertEquals(0, sim.resetCount)
    }
    @Test fun f1InstallAndRecoveryUseF1RccWithoutTouchingF4Rcc() {
        tw.write32(0xE000ED08, 0x08000000)
        tw.write32(0x4002101C, 0x100)
        tw.write32(0x40023840, 0x1234)
        val result = RttInstaller(st, tw).install(image().copy(vectorBytes = 236), 0x410)
        assertTrue(result.toString(), result.started)
        assertEquals(0x101, sim.word(0x4002101C))
        assertEquals(0x1234, sim.word(0x40023840))
        assertEquals(0x4002101CL, result.recovery!!.rccEnable)
        assertEquals(0x20005000L, result.recovery!!.ramEnd)

    }
    @Test fun f1ImageOutsideItsSramRejectedBeforeHalt() {
        try { RttInstaller(st, tw).install(image().copy(base = 0x20010000), 0x410); fail() }
        catch (_: IllegalArgumentException) {}
        assertFalse(sim.halted)
    }

}

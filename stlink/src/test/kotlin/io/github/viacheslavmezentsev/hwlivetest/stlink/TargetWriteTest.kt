package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TargetWriteTest {
    @Test fun stepAndReadPcPreserveMask() {
        val (sim, _, tw) = setup()
        tw.halt()
        assertEquals(0x20001000L, tw.readProgramCounter())
        assertTrue(tw.step().halted)
        assertEquals(0x20001002L, tw.readProgramCounter())
        assertFalse(sim.maskInterrupts)
        sim.maskInterrupts = true
        tw.step()
        assertTrue(sim.maskInterrupts)
    }
    @Test fun stepTimeoutLeavesCoreHalted() {
        val (sim, _, tw) = setup()
        tw.halt(); sim.stepWorks = false
        try { tw.step(); fail() } catch (_: StLinkException) { }
        assertTrue(sim.halted)
        assertFalse(sim.maskInterrupts)
        assertEquals(0, sim.resetCount)
    }
    @Test fun pcTransferTimeout() {
        val (sim, _, tw) = setup()
        tw.halt(); sim.registerReady = false
        try { tw.readProgramCounter(); fail() } catch (_: StLinkException) { }
        assertTrue(sim.halted)
    }
    private fun padded(hex: String): ByteArray {
        val b = hex.split(" ").map { it.toInt(16).toByte() }
        return ByteArray(16).also { b.toByteArray().copyInto(it) }
    }

    private fun setup(sim: SimTarget = SimTarget()): Triple<SimTarget, StLink, TargetWrite> {
        val st = StLink(sim, sleep = {})
        return Triple(sim, st, st.targetWrite(WritePermit.grant("тест")))
    }

    /** TC-31: кодирование команд изменения цели. */
    @Test
    fun encodesWriteCommands() {
        assertArrayEquals(padded("f2 35 f0 ed 00 e0 03 00 5f a0"), StLinkWriteProtocol.writeDebugReg(0xE000EDF0L, 0xA05F0003.toInt()))
        assertArrayEquals(padded("f2 08 00 00 00 20 00 04"), StLinkWriteProtocol.writeMem(4, 0x20000000L, 1024))
        assertArrayEquals(padded("f2 48 00 04 00 08 00 01"), StLinkWriteProtocol.writeMem(2, 0x08000400L, 256))
        assertArrayEquals(padded("f2 0d 10 00 00 20 40"), StLinkWriteProtocol.writeMem(1, 0x20000010L, 64))
        assertArrayEquals(padded("f2 3c 02"), StLinkWriteProtocol.driveNrst(StLinkWriteProtocol.NRST_PULSE))
    }

    @Test
    fun writeBlocksRespectLimits() {
        for ((w, a, n) in listOf(Triple(4, 0x20000002L, 8), Triple(4, 0x20000000L, 1028), Triple(1, 0x20000000L, 65), Triple(4, 0x200003FCL, 8))) {
            try {
                StLinkWriteProtocol.writeMem(w, a, n)
                fail("$w $a $n")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** TC-32: без действующего разрешения — отказ без обмена. */
    @Test
    fun revokedPermitRefusesWithoutTraffic() {
        val sim = SimTarget()
        val st = StLink(sim, sleep = {})
        val permit = WritePermit.grant("тест")
        val tw = st.targetWrite(permit)
        permit.revoke()
        for (action in listOf<() -> Unit>({ tw.halt() }, { tw.write32(0x20000000L, 1) }, { tw.writeBytes(0x20000000L, byteArrayOf(1)) }, { tw.pulseNrst() }, { tw.step() }, { tw.readProgramCounter() })) {
            try {
                action()
                fail()
            } catch (e: StLinkException) {
                assertEquals(StLinkErrorKind.NOT_PERMITTED, e.kind)
            }
        }
        assertTrue(sim.commands.isEmpty())
    }

    /** TC-33: остановка и запуск. */
    @Test
    fun haltAndGo() {
        val (sim, _, tw) = setup()
        assertTrue(tw.halt().halted)
        assertTrue(sim.halted)
        assertFalse(tw.go().halted)
        assertFalse(sim.halted)
        assertTrue(sim.debugEnabled)
    }

    /** TC-34: сброс с остановкой восстанавливает DEMCR; сброс с запуском оставляет ядро работать. */
    @Test
    fun resetHaltRestoresDemcr() {
        val (sim, _, tw) = setup()
        sim.demcr = 0x01000000 // TRCENA
        val s = tw.resetHalt()
        assertTrue(s.halted)
        assertEquals(0x01000000, sim.demcr)
        assertEquals(1, sim.resetCount)
        assertFalse(tw.reset().halted)
        assertEquals(2, sim.resetCount)
    }

    @Test
    fun resetHaltTimesOutWhenVectorCatchFails() {
        val (sim, _, tw) = setup(SimTarget(vectorCatchWorks = false))
        try {
            tw.resetHalt()
            fail()
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.SWD_FAULT, e.kind)
            assertTrue(e.message!!.contains("NRST"))
        }
        assertEquals(0, sim.demcr)
    }

    /** TC-35: запись блоков в ОЗУ и чтение обратно; разрез на границе 1 КБ. */
    @Test
    fun blockWritesReadBack() {
        val (sim, st, tw) = setup()
        val words = IntArray(300) { 0x11110000 + it }
        tw.writeBlock32(0x200003F0L, words)
        assertArrayEquals(words, st.readBlock(0x200003F0L, 300))
        val writes = sim.commands.filter { it[0] == 0xF2.toByte() && it[1] == 0x08.toByte() }
        assertEquals(listOf(16, 1024, 160), writes.map { Hex.u16le(it, 6) })
        tw.writeBlock16(0x20001000L, shortArrayOf(0x1234, 0x5678))
        assertEquals(0x56781234, sim.word(0x20001000L))
        tw.writeBytes(0x20002001L, ByteArray(100) { it.toByte() })
        assertEquals(99.toByte(), sim.mem[0x20002001L + 99])
        try {
            tw.writeBlock32(0x20000002L, intArrayOf(1))
            fail()
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.BAD_ARGUMENT, e.kind)
        }
    }

    @Test
    fun halfWordsNeedJ26() {
        val sim = SimTarget(jtag = 25)
        val st = StLink(sim, sleep = {})
        st.readVersion()
        try {
            st.targetWrite(WritePermit.grant("тест")).writeBlock16(0x20000000L, shortArrayOf(1))
            fail()
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.OLD_FIRMWARE, e.kind)
        }
    }

    /** TC-36: отключение после остановки запускает ядро и снимает C_DEBUGEN. */
    @Test
    fun detachRunsCore() {
        val (sim, _, tw) = setup()
        tw.halt()
        val s = tw.detach()
        assertFalse(s.halted)
        assertFalse(sim.debugEnabled)
    }

    @Test
    fun nrstPulse() {
        val (sim, _, tw) = setup()
        tw.pulseNrst()
        assertEquals(1, sim.nrstPulses)
    }
}

/** TC-20: в списке команд чтения нет команд изменения цели; они только в [StLinkWriteProtocol]. */
class WhitelistTest {
    @Test
    fun readProtocolHasNoWriteCommands() {
        val names = StLinkProtocol::class.java.declaredFields.map { it.name }
        val forbidden = listOf("WRITE", "RUNCORE", "FORCEDEBUG", "RESET", "NRST", "STEP", "OPTION")
        assertTrue(names.none { n -> forbidden.any { n.contains(it) } })
        val writeNames = StLinkWriteProtocol::class.java.declaredFields.map { it.name }
        assertTrue(writeNames.none { it.contains("RUNCORE") || it.contains("FORCEDEBUG") || it.contains("RESETSYS") || it.contains("OPTION") })
    }
}

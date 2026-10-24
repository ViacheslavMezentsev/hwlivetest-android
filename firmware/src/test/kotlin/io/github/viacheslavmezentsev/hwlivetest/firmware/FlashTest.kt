package io.github.viacheslavmezentsev.hwlivetest.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashTest {
    private val f411 = PlanTarget(0x431, 512, 3.3, "STM32F411")
    private val f103 = PlanTarget(0x410, 64, 3.3, "STM32F103")

    private fun image(address: Long, n: Int) = listOf(Segment(address, ByteArray(n) { (it * 7 + 3).toByte() }))

    private fun sim(t: PlanTarget) = FlashSim(FlashLayout.of(t.devId!!, t.flashKb)!!)

    private fun flasher(s: FlashSim, psize: Int = 32, canWrite16: Boolean = true) =
        Flasher(s, s, s.layout, psize, canWrite16, s.clock)

    @Test
    fun layouts() {
        val f4 = FlashLayout.of(0x431, 512)!!
        assertEquals(8, f4.units.size)
        assertEquals(512 * 1024, f4.size)
        assertEquals(0x08020000L, f4.units[5].address)
        val f42x = FlashLayout.of(0x419, 2048)!!
        assertEquals(24, f42x.units.size)
        assertEquals(12, f42x.units[12].index)
        assertEquals(0x08100000L, f42x.units[12].address)
        assertTrue(f42x.dualBank)
        assertEquals(2048, FlashLayout.of(0x414, 512)!!.units[0].size)
        assertNull(FlashLayout.of(0x468, 128))
    }

    /** TC-03: ТЗ прошивки 5.1.1, 5.1.2. */
    @Test
    fun planForty() {
        val p = FlashPlan.build(image(0x08000000L, 40 * 1024), f411)
        assertTrue(p.allowed)
        assertEquals(listOf(0, 1, 2), p.units.map { it.index })
        assertEquals(8 * 1024, p.bytesLost)
        assertEquals(32, p.psize)
        assertTrue(p.estimateMs > 0)
        assertEquals("секторы 0–2", FlashPlan.unitsText(p.units))
    }

    /** TC-04: ТЗ прошивки 3.1.4 — отказы плана. */
    @Test
    fun planRefusals() {
        assertFalse(FlashPlan.build(image(0x08080000L, 16), f411).allowed)
        assertFalse(FlashPlan.build(image(0x08000000L, 16), f411.copy(devId = null)).allowed)
        assertFalse(FlashPlan.build(image(0x08000000L, 16), f411.copy(devId = 0x468)).allowed)
        val low = FlashPlan.build(image(0x08000000L, 16), f411.copy(voltage = 1.5))
        assertFalse(low.allowed)
        assertTrue(low.refusals.single().contains("1.50"))
    }

    /** TC-04: RDP 1 и WRP — отказ без записи в цель. */
    @Test
    fun protectionRefusesWithoutWrites() {
        val s = sim(f411)
        s.words[0x40023C14L] = 0x0FFF55ED // RDP 1
        val plan = FlashPlan.build(image(0x08000000L, 1024), f411)
        val r = flasher(s).flash(plan, backup = false, run = true)
        assertEquals(FlashErrorKind.REFUSED, r.kind)
        assertTrue(s.regWrites.isEmpty())
        assertEquals(0, s.resetHalts)

        val w = sim(f411)
        w.words[0x40023C14L] = 0x0FFEAAED // nWRP0 = 0: сектор 0 защищён
        val r2 = flasher(w).flash(plan, backup = false, run = true)
        assertEquals(FlashErrorKind.REFUSED, r2.kind)
        assertTrue(r2.message.contains("сектор"))
        assertTrue(w.regWrites.isEmpty())

        val f1 = sim(f103)
        f1.words[0x40022020L] = -2 // страницы 0–3 защищены
        val r3 = flasher(f1).flash(FlashPlan.build(image(0x08000000L, 100), f103), backup = false, run = true)
        assertEquals(FlashErrorKind.REFUSED, r3.kind)
        assertTrue(f1.regWrites.isEmpty())
    }

    /** TC-05: ТЗ прошивки 5.1.3. */
    @Test
    fun sha256Check() {
        val img = image(0x08000000L, 64)
        val sha = IntelHex.sha256(img[0].data)
        assertTrue(FlashPlan.build(img, f411, expectedSha256 = "${sha.uppercase()}  fw.hex").allowed)
        assertFalse(FlashPlan.build(img, f411, expectedSha256 = "00" + sha.drop(2)).allowed)
        assertTrue(FlashPlan.build(img, f411, fileSha256 = "abc", expectedSha256 = "abc").allowed)
    }

    /** TC-06: ТЗ прошивки 4.2.3 — сектор 5, второй банк, полное стирание. */
    @Test
    fun f4EraseSequence() {
        val s = sim(f411)
        s.flash.fill(0)
        val r = flasher(s).erase(listOf(s.layout.units[5]))
        assertTrue(r.message, r.ok)
        assertEquals(listOf(5), s.erasedUnits)
        val crWrites = s.regWrites.filter { it.first == 0x40023C10L }.map { it.second }
        val ser = 2 or (5 shl 3) or (2 shl 8)
        assertTrue(crWrites.containsAll(listOf(ser, ser or (1 shl 16))))
        assertTrue(s.locked)
        assertTrue(s.halted)
        assertEquals(0, s.runs)
        assertEquals(0xFF.toByte(), s.flash[0x20000])
        assertEquals(0.toByte(), s.flash[0x1FFFF])

        val big = FlashSim(FlashLayout.of(0x419, 2048)!!)
        assertTrue(flasher(big).erase(listOf(big.layout.units[13])).ok)
        assertTrue(big.regWrites.any { it.first == 0x40023C10L && (it.second ushr 3) and 0x1F == 0x11 })
        big.flash.fill(0)
        assertTrue(flasher(big).erase(null).ok)
        assertTrue(big.regWrites.any { it.first == 0x40023C10L && it.second and (1 shl 15) != 0 && it.second and 4 != 0 })
        assertTrue(big.flash.all { it == 0xFF.toByte() })
    }

    /** TC-07: ТЗ прошивки 4.2.2. */
    @Test
    fun psizeByVoltage() {
        assertEquals(32, FlashPlan.psizeFor(3.3))
        assertEquals(16, FlashPlan.psizeFor(2.5))
        assertEquals(8, FlashPlan.psizeFor(1.9))
        assertEquals(16, FlashPlan.psizeFor(null))
        val p = FlashPlan.build(image(0x08000000L, 16), f411.copy(voltage = null))
        assertEquals(16, p.psize)
        assertEquals(1, p.warnings.size)
    }

    /** TC-08: ТЗ прошивки 4.2.4 — блоки по 256 байт, хвост 0xFF; PGSERR в середине. */
    @Test
    fun f4WriteAndError() {
        for (ps in listOf(32, 16, 8)) {
            val s = sim(f411)
            val img = image(0x08000000L, 1001)
            val r = flasher(s, ps).flash(FlashPlan.build(img, f411), backup = false, run = true)
            assertTrue("$ps: ${r.message}", r.ok)
            assertArrayEquals(img[0].data, s.flash.copyOf(1001))
            assertEquals(0xFF.toByte(), s.flash[1001])
            assertEquals(if (ps == 8) 16 else 4, s.blockWrites)
            assertTrue(r.running)
            assertTrue(s.locked)
        }
        val e = sim(f411)
        e.errorAtBlock = 2
        val r = flasher(e).flash(FlashPlan.build(image(0x08000000L, 1000), f411), backup = false, run = true)
        assertEquals(FlashErrorKind.FLASH_ERROR, r.kind)
        assertTrue(r.message.contains("PGSERR"))
        assertEquals(2, e.blockWrites)
        assertTrue(e.locked)
        assertTrue(e.halted)
    }

    /** TC-09: ТЗ прошивки 4.3 — F1, страницы и полуслова; J25 — отказ. */
    @Test
    fun f1EraseAndWrite() {
        val s = sim(f103)
        s.flash.fill(0)
        val img = image(0x08000400L, 3000)
        val r = flasher(s).flash(FlashPlan.build(img, f103), backup = false, run = false)
        assertTrue(r.message, r.ok)
        assertEquals(listOf(1, 2, 3), s.erasedUnits)
        assertTrue(s.regWrites.contains(0x40022014L to 0x08000800))
        assertArrayEquals(img[0].data, s.flash.copyOfRange(0x400, 0x400 + 3000))
        assertEquals(0.toByte(), s.flash[0x3FF])
        assertFalse(r.running)
        assertTrue(s.halted)

        val old = sim(f103)
        val r2 = flasher(old, canWrite16 = false).flash(FlashPlan.build(img, f103), backup = false, run = true)
        assertEquals(FlashErrorKind.REFUSED, r2.kind)
        assertTrue(old.regWrites.isEmpty())
    }

    /** TC-10: ТЗ прошивки 4.1.4, 3.1.8. */
    @Test
    fun busyTimeout() {
        val s = sim(f411)
        s.stuckBusy = true
        val r = flasher(s).erase(listOf(s.layout.units[1]))
        assertEquals(FlashErrorKind.FLASH_TIMEOUT, r.kind)
        assertTrue(s.now >= 4000)
        assertTrue(s.locked)
        val f1 = sim(f103)
        f1.stuckBusy = true
        assertEquals(FlashErrorKind.FLASH_TIMEOUT, flasher(f1).erase(null).kind)
        assertTrue(f1.now in 500..600)
    }

    /** TC-11: ТЗ прошивки 3.1.7. */
    @Test
    fun watchdogFrozenAndRestored() {
        val s = sim(f411)
        assertTrue(flasher(s).erase(listOf(s.layout.units[0], s.layout.units[1])).ok)
        assertTrue(s.dbgAtStart.all { it and (3 shl 11) == 3 shl 11 })
        assertEquals(2, s.dbgAtStart.size)
        assertEquals(1, s.words[s.dbgmcu])
        val f1 = sim(f103)
        assertTrue(flasher(f1).erase(listOf(f1.layout.units[0])).ok)
        assertTrue(f1.dbgAtStart.single() and (3 shl 8) == 3 shl 8)
        assertEquals(1, f1.words[f1.dbgmcu])
    }

    /** TC-12: ТЗ прошивки 5.5.3, 5.5.4. */
    @Test
    fun cancelAndWait() {
        val s = sim(f411)
        var erased = 0
        val r = flasher(s).flash(FlashPlan.build(image(0x08000000L, 40 * 1024), f411), backup = false, run = true) { step, _, _ ->
            if (step == FlashStep.ERASE) erased++
            !(step == FlashStep.ERASE && erased == 2)
        }
        assertEquals(FlashErrorKind.INCOMPLETE, r.kind)
        assertEquals(1, s.erasedUnits.size)
        assertTrue(s.locked)
        assertTrue(s.halted)
        assertEquals(0, s.blockWrites)

        val w = sim(f411)
        w.waitAtBlock = 3
        val r2 = flasher(w).flash(FlashPlan.build(image(0x08000000L, 2000), f411), backup = true, run = true)
        assertEquals(FlashErrorKind.INCOMPLETE, r2.kind)
        assertEquals(3, w.blockWrites)
        assertTrue(w.locked)
        assertTrue(w.halted)
        assertNotNull(r2.hint)
        // ТЗ прошивки 3.1.5 (р.1.2): копия только стираемого сектора 0.
        assertEquals(listOf(0x08000000L to 16 * 1024), r2.backup!!.map { it.address to it.data.size })
        assertTrue(r2.steps.any { it.step == FlashStep.BACKUP && it.done == 16 * 1024L })

        val c = sim(f411)
        val r3 = flasher(c).flash(FlashPlan.build(image(0x08000000L, 100), f411), backup = true, run = true) { step, _, _ -> step != FlashStep.BACKUP }
        assertEquals(FlashErrorKind.CANCELLED, r3.kind)
        assertTrue(c.regWrites.isEmpty())
        // ТЗ прошивки 3.1.5 (р.1.3): ядро, остановленное ради копии, снова запущено.
        assertFalse(c.halted)
    }

    /** TC-13: ТЗ прошивки 5.5.6. */
    @Test
    fun verifyFailureKeepsCoreHalted() {
        val s = sim(f411)
        s.corruptAt = 0x08000123L
        val r = flasher(s).flash(FlashPlan.build(image(0x08000000L, 1000), f411), backup = false, run = true)
        assertEquals(FlashErrorKind.VERIFY_FAILED, r.kind)
        assertEquals(0x08000123L, r.mismatches.first().address)
        assertEquals(0, s.runs)
        assertTrue(s.halted)
    }

    /** TC-21: ТЗ прошивки 3.1.6 — нет записи option bytes ни в одном сценарии. */
    @Test
    fun noOptionByteWrites() {
        for (t in listOf(f411, f103)) {
            val s = sim(t)
            flasher(s).flash(FlashPlan.build(image(0x08000000L, 5000), t), backup = true, run = true)
            flasher(s).erase(null)
            assertTrue(s.forbiddenWrites.isEmpty())
            assertTrue(s.regWrites.isNotEmpty())
        }
    }

    /** TC-19 (часть): история без переводов строк внутри записи, экранирование. */
    @Test
    fun historyJson() {
        val e = HistoryEntry(0, "прошивка", "ST-Link/V2", "V2J37S7", "STM32F411", "a\"b.hex", "ff", "секторы 0–2", 1500, "ok", "строка 1\nстрока 2")
        val j = e.toJson()
        assertFalse(j.contains('\n'))
        assertTrue(j.contains("\"time\":\"1970-01-01T00:00:00Z\""))
        assertTrue(j.contains("a\\\"b.hex"))
        assertTrue(j.contains("строка 1\\nстрока 2"))
        assertTrue(e.report().contains("Длительность: 1.5 с"))
    }

    /** TC-22: ТЗ прошивки 3.1.5 (р.1.2) — копия смежных единиц — один участок, HEX из нескольких участков разбирается обратно. */
    @Test
    fun backupOfErasedUnits() {
        val s = sim(f103)
        for (i in s.flash.indices) s.flash[i] = (i % 251).toByte()
        val img = listOf(Segment(0x08000400L, ByteArray(1500) { 1 }), Segment(0x08003000L, ByteArray(10) { 2 }))
        val r = flasher(s).flash(FlashPlan.build(img, f103), backup = true, run = false)
        assertTrue(r.message, r.ok)
        val b = r.backup!!
        assertEquals(listOf(0x08000400L to 2048, 0x08003000L to 1024), b.map { it.address to it.data.size })
        assertEquals((0x400 % 251).toByte(), b[0].data[0])
        val back = IntelHex.parse(IntelHex.write(b))
        assertEquals(b.map { it.address }, back.map { it.address })
        assertArrayEquals(b[1].data, back[1].data)
        assertTrue(r.steps.map { it.step }.containsAll(listOf(FlashStep.BACKUP, FlashStep.ERASE, FlashStep.WRITE, FlashStep.VERIFY)))
    }

    /** TC-23: ТЗ прошивки 3.1.5 (р.1.3) — копия читается при остановленном ядре. */
    @Test
    fun backupReadsWithCoreHalted() {
        val s = sim(f411)
        var haltedDuringBackup = false
        val r = flasher(s).flash(FlashPlan.build(image(0x08000000L, 100), f411), backup = true, run = true) { step, _, _ ->
            if (step == FlashStep.BACKUP) haltedDuringBackup = s.halted
            true
        }
        assertTrue(r.message, r.ok)
        assertTrue(haltedDuringBackup)
        assertFalse(s.halted)
        assertTrue(r.steps.first().step == FlashStep.STOP)
    }
}

package io.github.viacheslavmezentsev.hwlivetest.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ТЗ прошивки 5.9 (р.1.4): ELF как источник образа и таблицы сигналов. Файлы — `resources/elf`. */
class ElfTest {
    private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/elf/$name")!!.use { it.readBytes() }

    /** TC-24: образ ELF совпадает с HEX от objcopy, .data берётся по LMA во Flash. */
    @Test
    fun loadSegmentsMatchObjcopyHex() {
        for (v in listOf("t4.elf", "t5.elf")) {
            val elf = ElfFile.parse(res(v))
            val segs = elf.loadSegments()
            assertEquals(1, segs.size)
            assertEquals(0x08000000L, segs[0].address)
            assertTrue(segs[0].end <= 0x08000200L)
            // начальное значение static int32_t offset = -5 лежит во Flash сразу за .text
            val tail = segs[0].data.copyOfRange(segs[0].data.size - 16, segs[0].data.size - 12)
            assertArrayEquals(byteArrayOf(-5, -1, -1, -1), tail)
        }
        assertArrayEquals(ElfFile.parse(res("t4.elf")).loadSegments()[0].data, ElfFile.parse(res("t5.elf")).loadSegments()[0].data)
    }

    @Test
    fun rejectsNonElf() {
        assertFalse(ElfFile.isElf(":020000040800F2".toByteArray()))
        try {
            ElfFile.parse(ByteArray(64))
            fail()
        } catch (_: ElfFile.FormatException) {
        }
    }

    /** TC-25: переменные, поля структур, массивы и типы из DWARF 4 и 5 одинаковы. */
    @Test
    fun dwarfSignals() {
        for (v in listOf("t4.elf", "t5.elf")) {
            val sig = Dwarf.of(ElfFile.parse(res(v))).signals().associateBy { it.name }
            val s = sig["sensors.cached_data.voltage_batt"]
            assertNotNull(v, s)
            assertEquals(0x20000010L + 4, s!!.address)
            assertEquals(SignalKind.FLOAT, s.kind)
            assertEquals(4, s.size)
            assertEquals(0x20000010L, sig["sensors.count"]!!.address)
            assertEquals(SignalKind.SIGNED, sig["sensors.cached_data.temp"]!!.kind)
            assertEquals(2, sig["sensors.cached_data.temp"]!!.size)
            assertEquals(SignalKind.BOOL, sig["sensors.cached_data.ok"]!!.kind)
            assertEquals(0x20000010L + 16, sig["sensors.hist[0]"]!!.address)
            assertEquals(0x20000010L + 24, sig["sensors.hist[2]"]!!.address)
            assertEquals(0x2000002CL, sig["uwTick"]!!.address)
            assertEquals(0x20000000L, sig["offset"]!!.address)
            assertEquals(8, sig["big"]!!.size)
            assertEquals(0x080000ACL, sig["table[0]"]!!.address)
            assertNotNull(sig["buf[7]"])
            assertNull(sig["buf[8]"]) // массивы — до 8 элементов
            assertTrue(sig["uwTick"]!!.type.contains("uint32_t"))
        }
    }

    /** TC-26: addresses.json schema 2 из ELF читается разборщиком приложения по тем же правилам. */
    @Test
    fun signalTableJson() {
        val t = SignalTable.fromElf(res("t5.elf"), "t5.elf")
        val names = t.entries.map { it.signal.name }
        assertTrue("sensors.cached_data.voltage_batt" in names)
        assertFalse("big" in names) // double — 8 байт, слово не читается
        assertFalse("table[0]" in names) // во Flash, не ОЗУ
        val temp = t.entries.first { it.signal.name == "sensors.cached_data.temp" }
        // temp по смещению 8 от 0x20000014: адрес 0x2000001C, выровнен, младшие 16 бит
        assertEquals(0x2000001CL, temp.word)
        assertEquals(0, temp.shift)
        assertEquals(0xFFFFL, temp.mask)
        val flags = t.entries.first { it.signal.name == "sensors.cached_data.flags" }
        assertEquals(16, flags.shift)
        assertEquals(0xFF0000L, flags.mask)
        val json = t.toAddressesJson()
        assertTrue(json.contains("\"schema\": 2"))
        assertTrue(json.contains("\"elf_sha256\": \"${IntelHex.sha256(res("t5.elf"))}\""))
        assertTrue(json.contains("\"topic\": \"/elf/sensors/cached_data/voltage_batt\""))
        assertTrue(json.contains("\"kind\": \"float32\""))
        // образ таблицы совпадает с образом плана прошивки из того же ELF
        val plan = FlashPlan.build(ElfFile.parse(res("t5.elf")).loadSegments(), PlanTarget(0x431, 512, 3.3, "F411"))
        assertEquals(plan.imageSha256, t.imageSha256)
        assertTrue(t.warnings.isEmpty())
    }
}

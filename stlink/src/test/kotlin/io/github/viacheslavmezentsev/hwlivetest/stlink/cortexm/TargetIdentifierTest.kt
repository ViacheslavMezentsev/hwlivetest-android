package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Память цели в виде таблицы; чтение неизвестного адреса — AP FAULT. */
private class FakeMemory(private val words: Map<Long, Int>, private val apBase: Int? = null) : DebugMemory {
    val reads = mutableListOf<Long>()

    override fun read32(address: Long): Int {
        reads += address
        return words[address] ?: throw StLinkException(StLinkErrorKind.SWD_FAULT, "AP FAULT", status = 0x11)
    }

    override fun readApRegister(ap: Int, register: Int): Int? = apBase
}

/** Слова ROM-таблицы по базе 0xE00FF000 для заданных part и JEP106. */
private fun romWords(part: Int, jep: Jep106, base: Long = 0xE00FF000L): Map<Long, Int> = mapOf(
    base + 0xFE0 to (part and 0xFF),
    base + 0xFE4 to (((jep.id and 0x0F) shl 4) or ((part ushr 8) and 0x0F)),
    base + 0xFE8 to (0x08 or ((jep.id ushr 4) and 0x07)),
    base + 0xFEC to 0,
    base + 0xFD0 to (jep.continuation and 0x0F),
    base + 0xFF0 to 0x0D,
    base + 0xFF4 to 0x10,
    base + 0xFF8 to 0x05,
    base + 0xFFC to 0xB1,
)

class TargetIdentifierTest {

    @Test
    fun cpuIdDecoding() {
        val m4 = CpuId(0x410FC241)
        assertEquals(CortexCore.M4, m4.core)
        assertEquals("r0p1", m4.revisionLabel)
        assertEquals(CortexCore.M0_PLUS, CpuId(0x410CC601).core)
        assertEquals(CortexCore.M7, CpuId(0x411FC272).core)
        assertNull(CpuId(0x12345678).core)
    }

    @Test
    fun dpIdCodeDecoding() {
        val id = DpIdCode(0x2BA01477)
        assertEquals(0xBA, id.partNo)
        assertEquals(1, id.version)
        assertEquals(2, id.revision)
        assertEquals(Jep106.ARM, id.designer)
        assertTrue(id.looksValid)
    }

    @Test
    fun jep106Labels() {
        assertEquals("STMicroelectronics", Jep106.ST.name)
        assertEquals("ARM", Jep106.ARM.name)
        assertEquals(0x23B, Jep106.ARM.designer)
        assertEquals("JEP106 банк 8, код 0x51", Jep106(7, 0x51).label)
    }

    @Test
    fun romBaseRegister() {
        assertEquals(0xE00FF000L, RomTable.baseFromApRegister(0xE00FF003.toInt()))
        assertNull(RomTable.baseFromApRegister(-1))
        assertNull(RomTable.baseFromApRegister(0xE00FF002.toInt()))
    }

    @Test
    fun blackPillF411() {
        val mem = FakeMemory(
            romWords(0x431, Jep106.ST) + mapOf(
                0xE000ED00L to 0x410FC241,
                0xE0042000L to 0x10006431,
                0x1FFF7A20L to 0x02000000, // F_SIZE в старшей половине: 0x0200 = 512 КБ
            ),
            apBase = 0xE00FF003.toInt(),
        )
        val info = TargetIdentifier(mem).identify()
        assertEquals(McuVendor.STM32, info.vendor)
        assertEquals("STM32F411xC/E", info.stm32Part?.name)
        assertEquals(512, info.flashSizeKb)
        assertTrue(info.notes.toString(), info.notes.isEmpty())
        // На Cortex-M4 не трогаем периферийный адрес 0x40015800.
        assertFalse(mem.reads.contains(0x40015800L))
    }

    @Test
    fun f429DiscoWithoutApBaseUsesDefaultRomAddress() {
        val mem = FakeMemory(
            romWords(0x419, Jep106.ST) + mapOf(
                0xE000ED00L to 0x410FC241,
                0xE0042000L to 0x20036419,
                0x1FFF7A20L to 0x08000000,
            ),
            apBase = null,
        )
        val info = TargetIdentifier(mem).identify()
        assertEquals("по умолчанию", info.romBaseSource)
        assertEquals("STM32F42x/43x", info.stm32Part?.name)
        assertEquals(2048, info.flashSizeKb)
    }

    @Test
    fun cloneWithArmRomTableIsReportedAsCompatible() {
        val mem = FakeMemory(
            romWords(0x4C3, Jep106.ARM) + mapOf(
                0xE000ED00L to 0x412FC231,
                0xE0042000L to 0x13030410,
                0x1FFFF7E0L to 0xFFFF0040.toInt(),
            ),
        )
        val info = TargetIdentifier(mem).identify()
        assertEquals(McuVendor.STM32_COMPATIBLE, info.vendor)
        assertEquals(64, info.flashSizeKb)
    }

    @Test
    fun at32IsRecognizedByPidFormat() {
        val mem = FakeMemory(
            romWords(0x4C4, Jep106.ARM) + mapOf(
                0xE000ED00L to 0x410FC241,
                0xE0042000L to 0x70050242,
                0x1FFFF7E0L to 0xFFFF0400.toInt(),
            ),
        )
        val info = TargetIdentifier(mem).identify()
        assertEquals(McuVendor.AT32, info.vendor)
        assertEquals(0x70050242, info.idRegister)
        assertEquals(1024, info.flashSizeKb)
    }

    @Test
    fun faultsAreCollectedAsNotes() {
        val mem = FakeMemory(mapOf(0xE000ED00L to 0x410FC241))
        val info = TargetIdentifier(mem).identify()
        assertEquals(CortexCore.M4, info.cpuId?.core)
        assertEquals(McuVendor.UNKNOWN, info.vendor)
        assertTrue(info.notes.any { it.startsWith("ROM-таблица") })
        assertTrue(info.summary().isNotEmpty())
    }

    @Test
    fun read16PicksCorrectHalf() {
        val mem = FakeMemory(mapOf(0x1FFF7A20L to 0x0200ABCD))
        assertEquals(0xABCD, mem.read16(0x1FFF7A20L))
        assertEquals(0x0200, mem.read16(0x1FFF7A22L))
    }
}

package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RttPreparationTest {
    private fun symbols(vararg names: String): ElfFile {
        val strings = ("\u0000" + names.joinToString("\u0000") + "\u0000").toByteArray()
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x464C457F); b.put(4, 1); b.put(5, 1); b.putShort(18, 40)
        b.putInt(32, 52); b.putShort(46, 40); b.putShort(48, 3)
        b.putInt(96, 3); b.putInt(108, 176); b.putInt(112, strings.size)
        b.putInt(136, 2); b.putInt(148, 256); b.putInt(152, 16 * names.size)
        b.putInt(156, 1); b.putInt(168, 16)
        b.position(176); b.put(strings)
        var nameOffset = 1
        names.forEachIndexed { i, name ->
            val at = 256 + 16 * i
            b.putInt(at, nameOffset); b.putInt(at + 4, 0x20000000 + i * 4); b.putInt(at + 8, 4)
            b.put(at + 12, 0x11); b.putShort(at + 14, 1)
            nameOffset += name.length + 1
        }
        return ElfFile.parse(b.array())
    }
    @Test fun resolvesExactNamedObjects() {
        val s = RttElfSymbols.inspect(symbols("uwTick", "SystemCoreClock"))
        assertEquals("uwTick" to 0x20000000L, s.tick)
        assertEquals(0x20000004L, s.coreClock)
    }
    @Test fun rejectsAmbiguousTicks() {
        assertNull(RttElfSymbols.inspect(symbols("uwTick", "platform_tick")).tick)
        assertNull(RttElfSymbols.inspect(symbols("uwTick", "uwTick")).tick)
    }
    @Test fun referenceLayoutIncludesVectorAlignment() {
        val l = RttReferenceLayout.calculate(0x2001DE00, 6, 404)
        assertEquals(0x2001F000L, l.vectors)
        assertEquals(5012L, l.bytes)
        assertEquals(4096, l.bufferBytes)
        assertTrue(l.bytes < 6656)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesShortVectorTable() {
        RttReferenceLayout.calculate(0x2001DE00, 6, 100)
    }
}

package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RttElfAdviceTest {
    private fun elf(sp: Int = 0x20020000, ramSize: Int = 0x1000): ElfFile {
        val b = ByteBuffer.allocate(132).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x464C457F); b.put(4, 1); b.put(5, 1)
        b.putShort(18, 40); b.putInt(28, 52); b.putShort(42, 32); b.putShort(44, 2)
        b.putInt(52, 1); b.putInt(56, 128); b.putInt(60, 0x08000000); b.putInt(64, 0x08000000)
        b.putInt(68, 4); b.putInt(72, 4)
        b.putInt(84, 1); b.putInt(92, 0x20000000); b.putInt(100, 0); b.putInt(104, ramSize)
        b.putInt(128, sp)
        return ElfFile.parse(b.array())
    }
    @Test fun usesMemorySizeIncludingBssAndStackMargin() {
        val a = RttElfAdvice.inspect(elf())
        assertEquals(0x20001000L, a.base)
        assertEquals(0x1E800L, a.available)
    }
    @Test fun reportsNoRoomRatherThanNegativeSpace() {
        assertEquals(0L, RttElfAdvice.inspect(elf(ramSize = 0x20000)).available)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongStack() {
        RttElfAdvice.inspect(elf(sp = 0x10000000))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRamBeyondProfile() {
        RttElfAdvice.inspect(elf(ramSize = 0x20004))
    }
}

package io.github.viacheslavmezentsev.hwlivetest.firmware

/** ТЗ RTT 2.3: offline estimate, never proof of a reservation. F411 SRAM only. */
object RttElfAdvice {
    data class Advice(val dataEnd: Long, val initialStack: Long, val base: Long, val available: Long)
    fun inspect(elf: ElfFile): Advice {
        require(elf.machine == ElfFile.EM_ARM) { "Нужен ELF для ARM" }
        val ramStart = 0x20000000L
        val ramEnd = 0x20020000L
        val ranges = elf.programHeaders.filter { it.type == ElfFile.PT_LOAD && it.memSize > 0 &&
            it.vaddr < ramEnd && it.vaddr + it.memSize > ramStart }
        require(ranges.isNotEmpty()) { "В ELF нет загружаемых областей SRAM; оценка недоступна" }
        require(ranges.all { it.vaddr >= ramStart && it.vaddr + it.memSize <= ramEnd }) { "ELF выходит за SRAM F411" }
        val vector = elf.programHeaders.firstOrNull { it.type == ElfFile.PT_LOAD && it.paddr == 0x08000000L && it.fileSize >= 4 }
            ?: error("Нет начального вектора по адресу 0x08000000")
        require(vector.offset >= 0 && vector.offset.toLong() + 4 <= elf.bytes.size) { "Повреждённый вектор ELF" }
        val sp = (0..3).fold(0L) { n, i -> n or ((elf.bytes[vector.offset + i].toLong() and 255) shl (i * 8)) }
        require(sp in (ramStart + 2048)..ramEnd && sp % 8 == 0L) { "Начальный SP не соответствует SRAM F411" }
        val dataEnd = ranges.maxOf { it.vaddr + it.memSize }
        val base = (dataEnd + 255) and 255L.inv()
        return Advice(dataEnd, sp, base, (sp - 2048 - base).coerceAtLeast(0))
    }
}

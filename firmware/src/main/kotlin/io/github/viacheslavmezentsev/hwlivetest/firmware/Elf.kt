package io.github.viacheslavmezentsev.hwlivetest.firmware

/**
 * ELF32 little-endian (ARM): заголовки программы для прошивки и секции для отладочной информации
 * (ТЗ прошивки 5.9, р.1.4). Только чтение, без внешних библиотек.
 */
class ElfFile private constructor(val bytes: ByteArray) {
    class FormatException(message: String) : IllegalArgumentException(message)

    data class Section(val name: String, val type: Int, val address: Long, val offset: Int, val size: Int)

    data class ProgramHeader(val type: Int, val offset: Int, val vaddr: Long, val paddr: Long, val fileSize: Int, val memSize: Int)

    val machine: Int
    val sections: List<Section>
    val programHeaders: List<ProgramHeader>

    init {
        if (bytes.size < 52 || u8(0) != 0x7F || u8(1) != 'E'.code || u8(2) != 'L'.code || u8(3) != 'F'.code) {
            throw FormatException("не ELF: нет сигнатуры 7F 'ELF'")
        }
        if (u8(4) != 1) throw FormatException("поддерживается только ELF32 (класс ${u8(4)})")
        if (u8(5) != 1) throw FormatException("поддерживается только little-endian")
        machine = u16(18)
        val phoff = u32(28).toInt()
        val shoff = u32(32).toInt()
        val phentsize = u16(42)
        val phnum = u16(44)
        val shentsize = u16(46)
        val shnum = u16(48)
        val shstrndx = u16(50)
        programHeaders = List(phnum) { i ->
            val o = phoff + i * phentsize
            check(o + 32 <= bytes.size, "заголовок программы $i за концом файла")
            ProgramHeader(u32(o).toInt(), u32(o + 4).toInt(), u32(o + 8), u32(o + 12), u32(o + 16).toInt(), u32(o + 20).toInt())
        }
        val raw = List(shnum) { i ->
            val o = shoff + i * shentsize
            check(o + 40 <= bytes.size, "заголовок секции $i за концом файла")
            longArrayOf(u32(o), u32(o + 4), u32(o + 12), u32(o + 16), u32(o + 20))
        }
        val names = raw.getOrNull(shstrndx)
        sections = raw.map { r ->
            val name = if (names != null) cString((names[3] + r[0]).toInt()) else ""
            Section(name, r[1].toInt(), r[2], r[3].toInt(), r[4].toInt())
        }
    }

    fun section(name: String): Section? = sections.firstOrNull { it.name == name && it.type != SHT_NOBITS }

    fun sectionBytes(name: String): ByteArray? = section(name)?.let { s ->
        check(s.offset + s.size <= bytes.size, "секция $name за концом файла")
        bytes.copyOfRange(s.offset, s.offset + s.size)
    }

    /**
     * Образ для прошивки: загружаемые сегменты (PT_LOAD) с данными в файле, по физическим адресам
     * (LMA: начальные значения .data лежат во Flash). Смежные участки сливаются (ТЗ прошивки 5.9.1).
     */
    fun loadSegments(): List<Segment> {
        val parts = programHeaders.filter { it.type == PT_LOAD && it.fileSize > 0 }.map { p ->
            check(p.offset + p.fileSize <= bytes.size, "сегмент ${"0x%08X".format(p.paddr)} за концом файла")
            Segment(p.paddr, bytes.copyOfRange(p.offset, p.offset + p.fileSize))
        }.sortedBy { it.address }
        if (parts.isEmpty()) throw FormatException("в ELF нет загружаемых сегментов с данными")
        val out = ArrayList<Segment>()
        for (s in parts) {
            val last = out.lastOrNull()
            when {
                last != null && s.address < last.end -> throw FormatException("сегменты ELF перекрываются у ${"0x%08X".format(s.address)}")
                last != null && s.address == last.end -> out[out.size - 1] = Segment(last.address, last.data + s.data)
                else -> out += s
            }
        }
        return out
    }

    private fun check(ok: Boolean, what: String) {
        if (!ok) throw FormatException("повреждённый ELF: $what")
    }

    private fun u8(o: Int): Int = bytes[o].toInt() and 0xFF
    private fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    private fun u32(o: Int): Long = (u16(o).toLong() or (u16(o + 2).toLong() shl 16)) and 0xFFFFFFFFL

    private fun cString(o: Int): String {
        if (o < 0 || o >= bytes.size) return ""
        var e = o
        while (e < bytes.size && bytes[e] != 0.toByte()) e++
        return String(bytes, o, e - o, Charsets.UTF_8)
    }

    companion object {
        const val PT_LOAD = 1
        const val SHT_NOBITS = 8
        const val EM_ARM = 40

        fun parse(bytes: ByteArray): ElfFile = ElfFile(bytes)

        fun isElf(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
    }
}

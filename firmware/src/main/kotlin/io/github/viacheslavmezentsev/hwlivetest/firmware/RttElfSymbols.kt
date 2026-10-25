package io.github.viacheslavmezentsev.hwlivetest.firmware

/** ТЗ RTT 2.4: bounded ELF32 symbol lookup; names alone do not prove firmware identity. */
object RttElfSymbols {
    data class Symbols(val tick: Pair<String, Long>?, val coreClock: Long?)
    fun inspect(elf: ElfFile): Symbols {
        val b = elf.bytes
        fun u32(o: Int): Long {
            require(o >= 0 && o.toLong() + 4 <= b.size) { "Повреждённая таблица ELF" }
            return (0..3).fold(0L) { n, i -> n or ((b[o + i].toLong() and 255) shl (8 * i)) }
        }
        val shoff = u32(32)
        val shsize = (b[46].toInt() and 255) or ((b[47].toInt() and 255) shl 8)
        val found = mutableMapOf<String, MutableSet<Long>>()
        elf.sections.forEachIndexed { index, s ->
            if (s.type != 2) return@forEachIndexed
            val header = shoff + index.toLong() * shsize
            require(header >= 0 && header + 40 <= b.size)
            val link = u32(header.toInt() + 24)
            val entry = u32(header.toInt() + 36)
            require(link < elf.sections.size && entry >= 16 && entry <= Int.MAX_VALUE)
            val strings = elf.sections[link.toInt()]
            require(strings.type == 3 && strings.offset >= 0 && strings.size >= 0 && strings.offset.toLong() + strings.size <= b.size)
            require(s.offset >= 0 && s.size >= 0 && s.offset.toLong() + s.size <= b.size && s.size % entry == 0L)
            var offset = s.offset.toLong()
            while (offset < s.offset.toLong() + s.size) {
                val o = offset.toInt()
                val nameOffset = u32(o)
                val value = u32(o + 4)
                val size = u32(o + 8)
                val section = (b[o + 14].toInt() and 255) or ((b[o + 15].toInt() and 255) shl 8)
                if (section in 1 until elf.sections.size && size == 4L && b[o + 12].toInt() and 15 == 1 &&
                    value in 0x20000000L..0x2001FFFCL && value % 4 == 0L && nameOffset < strings.size) {
                    val start = strings.offset + nameOffset.toInt()
                    var end = start
                    while (end < strings.offset + strings.size && b[end] != 0.toByte()) end++
                    require(end < strings.offset + strings.size)
                    val name = String(b, start, end - start, Charsets.UTF_8)
                    if (name in setOf("uwTick", "platform_tick", "SystemCoreClock")) found.getOrPut(name) { mutableSetOf() }.add(value)
                }
                offset += entry
            }
        }
        fun unique(name: String) = found[name]?.singleOrNull()
        val ticks = listOf("uwTick", "platform_tick").mapNotNull { n -> unique(n)?.let { n to it } }
        return Symbols(ticks.singleOrNull(), unique("SystemCoreClock"))
    }
}

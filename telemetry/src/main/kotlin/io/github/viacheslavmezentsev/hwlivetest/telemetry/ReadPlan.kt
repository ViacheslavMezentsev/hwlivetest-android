package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Группировка адресов полей в блоки для чтения одной командой. Соседние слова в SRAM
 * и Flash объединяются, если между ними не больше [maxGapWords] лишних слов и блок не
 * пересекает границу 1 КБ. Регистры периферии и ядра читаются по одному: лишнее чтение
 * соседнего регистра может иметь побочный эффект.
 */
object ReadPlan {
    data class Block(val address: Long, val words: Int) {
        val end: Long get() = address + 4L * words
        operator fun contains(a: Long): Boolean = a >= address && a < end
    }

    fun mergeable(address: Long): Boolean = when (MemoryMap.region(address)) {
        MemoryMap.Region.SRAM, MemoryMap.Region.FLASH, MemoryMap.Region.SYSTEM -> true
        else -> false
    }

    fun blocks(addresses: Collection<Long>, maxGapWords: Int = 4, maxBytes: Int = 1024): List<Block> {
        val sorted = addresses.filter { it and 3L == 0L }.distinct().sorted()
        val out = ArrayList<Block>()
        var start = -1L
        var last = -1L
        fun flush() {
            if (start >= 0) out += Block(start, ((last - start) / 4 + 1).toInt())
        }
        for (a in sorted) {
            val canJoin = start >= 0 && mergeable(a) && mergeable(start) &&
                (a - last) / 4 - 1 <= maxGapWords &&
                (a and 0x3FFL.inv()) == (start and 0x3FFL.inv()) &&
                a + 4 - start <= maxBytes
            if (canJoin) {
                last = a
            } else {
                flush()
                start = a
                last = a
            }
        }
        flush()
        return out
    }
}

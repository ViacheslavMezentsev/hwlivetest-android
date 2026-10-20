package io.github.viacheslavmezentsev.hwlivetest.firmware

/**
 * Однокнопочные инструменты, которые только читают Flash: резервная копия и сверка
 * с HEX (аналоги `backup` и `check` из stm32-flasher). Чтение идёт блоками по 1 КБ.
 */
object FlashTools {
    const val CHUNK_BYTES = 1024

    /**
     * Прочитать [length] байт начиная с [address] через [readBlock] (слова, адрес
     * выровнен на 4). [progress] получает число прочитанных байт; вернув false, отменяет.
     */
    fun read(
        address: Long,
        length: Int,
        readBlock: (Long, Int) -> IntArray,
        progress: (Int) -> Boolean = { true },
    ): ByteArray {
        val start = address and 3L.inv()
        val end = address + length
        val total = (end - start).toInt()
        val words = (total + 3) / 4
        val out = ByteArray(words * 4)
        var done = 0
        while (done < words) {
            val a = start + 4L * done
            val room = ((CHUNK_BYTES - (a % CHUNK_BYTES)) / 4).toInt()
            val n = minOf(words - done, room)
            val w = readBlock(a, n)
            for (k in 0 until n) {
                val v = w[k]
                val o = 4 * (done + k)
                out[o] = v.toByte()
                out[o + 1] = (v ushr 8).toByte()
                out[o + 2] = (v ushr 16).toByte()
                out[o + 3] = (v ushr 24).toByte()
            }
            done += n
            if (!progress(minOf(done * 4, total))) throw CancelledException()
        }
        val skip = (address - start).toInt()
        return out.copyOfRange(skip, skip + length)
    }

    class CancelledException : RuntimeException("отменено")

    /** Обрезать хвост из 0xFF (стёртая Flash) с выравниванием на 16 байт. */
    fun trimErased(data: ByteArray): ByteArray {
        var n = data.size
        while (n > 0 && data[n - 1] == 0xFF.toByte()) n--
        n = ((n + 15) / 16) * 16
        return data.copyOf(minOf(n, data.size))
    }

    data class Mismatch(val address: Long, val expected: Byte, val actual: Byte)

    data class CheckResult(val bytes: Int, val mismatches: Int, val first: List<Mismatch>) {
        val ok: Boolean get() = mismatches == 0
    }

    /** Сравнить участки HEX с памятью. Помнит первые [keep] расхождений. */
    fun check(
        segments: List<Segment>,
        readBlock: (Long, Int) -> IntArray,
        keep: Int = 10,
        progress: (Int, Int) -> Boolean = { _, _ -> true },
    ): CheckResult {
        val total = segments.sumOf { it.data.size }
        var base = 0
        var bad = 0
        val first = ArrayList<Mismatch>()
        for (s in segments) {
            val actual = read(s.address, s.data.size, readBlock) { progress(base + it, total) }
            for (i in s.data.indices) {
                if (s.data[i] != actual[i]) {
                    bad++
                    if (first.size < keep) first += Mismatch(s.address + i, s.data[i], actual[i])
                }
            }
            base += s.data.size
        }
        return CheckResult(total, bad, first)
    }
}

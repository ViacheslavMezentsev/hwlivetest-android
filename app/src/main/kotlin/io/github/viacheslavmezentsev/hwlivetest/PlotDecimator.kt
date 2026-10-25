package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.25: первый, минимум, максимум и последний отсчёт на пиксель.
 * -1 разделяет непрерывные участки. Исходные данные и статистика не изменяются.
 */
object PlotDecimator {
    fun indices(times: LongArray, values: DoubleArray, from: Long, to: Long,
                pixels: Int, gapMs: Long): IntArray {
        require(times.size == values.size)
        val width = pixels.coerceAtLeast(1)
        val span = (to - from).coerceAtLeast(1).toDouble()
        val result = ArrayList<Int>()
        var bucket = -1
        var first = -1
        var last = -1
        var min = -1
        var max = -1
        var previous = -1
        fun flush() {
            if (first >= 0) result.addAll(listOf(first, min, max, last).distinct().sorted())
            first = -1
        }
        fun breakLine() {
            flush()
            if (result.isNotEmpty() && result.last() != -1) result.add(-1)
            previous = -1
            bucket = -1
        }
        for (i in times.indices) {
            if (!values[i].isFinite()) { breakLine(); continue }
            if (previous >= 0 && times[i] - times[previous] > gapMs) breakLine()
            val nextBucket = (((times[i] - from) / span) * width).toInt().coerceIn(0, width - 1)
            if (nextBucket != bucket) { flush(); bucket = nextBucket }
            if (first < 0) { first = i; min = i; max = i }
            if (values[i] < values[min]) min = i
            if (values[i] > values[max]) max = i
            last = i
            previous = i
        }
        flush()
        if (result.lastOrNull() == -1) result.removeAt(result.lastIndex)
        return result.toIntArray()
    }
}

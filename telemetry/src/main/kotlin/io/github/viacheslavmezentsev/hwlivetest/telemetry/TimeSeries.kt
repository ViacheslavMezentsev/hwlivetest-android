package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Кольцевой буфер отсчётов одного поля: время (мс от начала опроса) и значение.
 * Потокобезопасен: пишет поток опроса, читает поток отрисовки.
 */
class TimeSeries(val capacity: Int) {
    private val t = LongArray(capacity)
    private val v = DoubleArray(capacity)
    private var start = 0
    private var size = 0

    @Synchronized
    fun add(timeMs: Long, value: Double) {
        val idx = (start + size) % capacity
        t[idx] = timeMs
        v[idx] = value
        if (size < capacity) size++ else start = (start + 1) % capacity
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }

    @Synchronized
    fun count(): Int = size

    @Synchronized
    fun lastTime(): Long? = if (size == 0) null else t[(start + size - 1) % capacity]

    @Synchronized
    fun lastValue(): Double? = if (size == 0) null else v[(start + size - 1) % capacity]

    /** Отсчёты в [fromMs]..[toMs], по возрастанию; верхняя граница фиксирует паузу графика. */
    @Synchronized
    fun window(fromMs: Long, toMs: Long = Long.MAX_VALUE): Window {
        var first = 0
        while (first < size && t[(start + first) % capacity] < fromMs) first++
        var end = first
        while (end < size && t[(start + end) % capacity] <= toMs) end++
        val n = end - first
        val wt = LongArray(n)
        val wv = DoubleArray(n)
        for (k in 0 until n) {
            val idx = (start + first + k) % capacity
            wt[k] = t[idx]
            wv[k] = v[idx]
        }
        return Window(wt, wv)
    }

    class Window(val times: LongArray, val values: DoubleArray) {
        val size: Int get() = times.size

        fun finiteValues(): List<Double> = values.filter { it.isFinite() }

        val min: Double? get() = finiteValues().minOrNull()
        val max: Double? get() = finiteValues().maxOrNull()
        val mean: Double? get() = finiteValues().takeIf { it.isNotEmpty() }?.average()
    }
}

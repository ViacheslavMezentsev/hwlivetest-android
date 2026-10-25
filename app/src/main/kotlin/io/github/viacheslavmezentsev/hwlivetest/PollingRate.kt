package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.25: предел прямого SWD-опроса сохраняет пятиминутную историю. */
object PollingRate {
    const val MIN_HZ = 1
    const val MAX_HZ = 20
    const val DEFAULT_HZ = 10
    fun clamp(hz: Int) = hz.coerceIn(MIN_HZ, MAX_HZ)
    fun periodMs(hz: Int): Long = (1000L + clamp(hz) - 1) / clamp(hz)
    fun delayAfter(periodMs: Long, elapsedMs: Long): Long =
        (periodMs.coerceAtLeast(50L) - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(1L)
}

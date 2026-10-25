package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.19: активность — свежий успешный ответ, а не наличие старого значения. */
object SignalFreshness {
    fun active(connected: Boolean, polling: Boolean, now: Long, readAt: Long?): Boolean =
        connected && polling && readAt != null && now - readAt in 0..1500L
}

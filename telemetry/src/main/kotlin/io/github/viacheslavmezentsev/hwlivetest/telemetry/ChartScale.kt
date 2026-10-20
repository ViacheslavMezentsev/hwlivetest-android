package io.github.viacheslavmezentsev.hwlivetest.telemetry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** «Красивые» границы и деления оси (1, 2, 5 × 10^n). */
object ChartScale {
    data class Axis(val min: Double, val max: Double, val step: Double) {
        val ticks: List<Double>
            get() {
                val out = mutableListOf<Double>()
                var x = min
                var guard = 0
                while (x <= max + step * 1e-9 && guard++ < 100) {
                    out += if (abs(x) < step * 1e-9) 0.0 else x
                    x += step
                }
                return out
            }
    }

    fun niceStep(range: Double, maxTicks: Int): Double {
        val raw = range / maxTicks.coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val nice = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    /**
     * Ось Y по видимым значениям: небольшой запас сверху и снизу, границы на делениях.
     * Постоянное значение рисуется посередине с диапазоном ±10 % (или ±1 для нуля).
     */
    fun yAxis(min: Double?, max: Double?, maxTicks: Int = 5): Axis {
        if (min == null || max == null || !min.isFinite() || !max.isFinite()) return Axis(0.0, 1.0, 0.2)
        var lo = min
        var hi = max
        if (hi - lo < 1e-12 * maxOf(1.0, abs(hi))) {
            val d = if (abs(hi) > 1e-12) abs(hi) * 0.1 else 1.0
            lo -= d
            hi += d
        } else {
            val pad = (hi - lo) * 0.05
            lo -= pad
            hi += pad
        }
        val step = niceStep(hi - lo, maxTicks)
        return Axis(floor(lo / step) * step, ceil(hi / step) * step, step)
    }

    /** Подпись деления: столько знаков после точки, сколько нужно для шага [step]. */
    fun label(v: Double, step: Double): String {
        val decimals = (-floor(log10(step))).toInt().coerceIn(0, 6)
        val big = abs(v) >= 1e6 || (abs(v) < 1e-3 && v != 0.0 && step < 1e-3)
        return if (big) "%.2e".format(java.util.Locale.ROOT, v) else "%.${decimals}f".format(java.util.Locale.ROOT, v)
    }

    /** Шаг сетки по времени для окна [windowMs]: около 6 делений. */
    fun timeStepMs(windowMs: Long): Long = when {
        windowMs <= 30_000 -> 5_000
        windowMs <= 60_000 -> 10_000
        windowMs <= 300_000 -> 60_000
        else -> 300_000
    }
}

package io.github.viacheslavmezentsev.hwlivetest

import java.util.Locale
import kotlin.math.abs

/** ТЗ прошивки 5.25: среднее имеет два десятичных знака, включая целочисленные каналы. */
object ChartValues {
    fun mean(value: Double): String = if (!value.isFinite()) "—"
        else String.format(Locale.ROOT, "%.2f", if (abs(value) < 0.005) 0.0 else value)
}

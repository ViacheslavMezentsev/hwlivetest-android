package io.github.viacheslavmezentsev.hwlivetest.telemetry

/** ТЗ RTT 2.13: bounded display history; recording receives every original frame separately. */
class RttChartFeed(
    private val fields: List<Field>,
    private val series: (String) -> TimeSeries?,
    private val startedMs: Long,
) {
    private data class Point(val time: Long, val value: Double)
    private class Bucket {
        var min: Point? = null
        var max: Point? = null
        var last: Point? = null
    }
    private val buckets = List(fields.size) { Bucket() }
    private var previousTick: Long? = null
    private var elapsed = 0L
    private var window = 0L

    fun accept(frame: RttFrame) {
        previousTick?.let {
            val delta = (frame.tickMs - it) and 0xFFFFFFFFL
            if (delta >= 0x80000000L) return // Invalid time is retained in the raw recording, not drawn.
            elapsed += delta
        }
        previousTick = frame.tickMs
        if (elapsed / 200 != window) { flush(); window = elapsed / 200 }
        fields.forEachIndexed { i, field ->
            val value = field.decode(frame.words[i].toInt())
            if (value.isFinite() && field.inRange(value)) {
                val p = Point(startedMs + elapsed, value)
                val b = buckets[i]
                if (b.min == null || value < b.min!!.value) b.min = p
                if (b.max == null || value > b.max!!.value) b.max = p
                b.last = p
            }
        }
    }

    /** At most min, max and last per 200 ms: <= 4500 points per channel in five minutes. */
    fun flush() {
        buckets.forEachIndexed { i, b ->
            listOfNotNull(b.min, b.max, b.last).distinct().sortedBy { it.time }.forEach {
                series(fields[i].topic)?.add(it.time, it.value)
            }
            b.min = null; b.max = null; b.last = null
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class RttChartFeedTest {
    private val field = Field("/signal", FieldKind.INT32, 0x20000000)
    @Test fun fiveMinutesAtOneKhzKeepsPeaksAndLatestWithBoundedHistory() {
        val series = TimeSeries(6500)
        val feed = RttChartFeed(listOf(field), { series }, 0)
        repeat(300_000) { n ->
            val value = when (n % 200) { 20 -> -100L; 150 -> 200L; else -> 5L }
            feed.accept(RttFrame(n and 65535, n.toLong(), listOf(value)))
        }
        feed.flush()
        assertEquals(4500, series.count())
        val w = series.window(0)
        assertEquals(20L, w.times.first())
        assertEquals(299999L, w.times.last())
        assertEquals(-100.0, w.min!!, 0.0)
        assertEquals(200.0, w.max!!, 0.0)
        assertEquals(5.0, series.lastValue()!!, 0.0)
        assertTrue(w.times.toList().zipWithNext().all { (a,b) -> a <= b })
    }
    @Test fun tickWrapRetainsChronologicalOrder() {
        val series = TimeSeries(100)
        val feed = RttChartFeed(listOf(field), { series }, 500)
        feed.accept(RttFrame(65535, 0xFFFFFFFFL, listOf(1)))
        feed.accept(RttFrame(0, 0, listOf(2)))
        feed.flush()
        assertArrayEquals(longArrayOf(500, 501), series.window(0).times)
    }
}

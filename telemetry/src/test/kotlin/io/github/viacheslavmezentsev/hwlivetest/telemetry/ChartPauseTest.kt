package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class ChartPauseTest {
    @Test fun pausedWindowExcludesLaterSamplesAfterRingWrap() {
        val series = TimeSeries(4)
        for (t in 0L..5L) series.add(t*100, t.toDouble())
        val paused = series.window(200, 400)
        assertArrayEquals(longArrayOf(200, 300, 400), paused.times)
        assertEquals(3.0, paused.mean!!, 0.0)
        assertEquals(5.0, series.window(200).max!!, 0.0)
        assertEquals(0, series.window(600, 700).size)
        assertEquals(0, series.window(400, 300).size)
    }
}

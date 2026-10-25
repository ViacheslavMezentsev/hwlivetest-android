package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class PollingDisplayTest {
    @Test fun frequencyIsClampedAndNeverExceedsRequestedRate() {
        assertEquals(1000L, PollingRate.periodMs(-1))
        assertEquals(50L, PollingRate.periodMs(999))
        for (hz in 1..20) assertTrue(1000.0 / PollingRate.periodMs(hz) <= hz)
    }
    @Test fun slowPassesDoNotCauseCatchUpBursts() {
        assertEquals(93L, PollingRate.delayAfter(100, 7))
        assertEquals(1L, PollingRate.delayAfter(100, 500))
        assertEquals(50L, PollingRate.delayAfter(1, 0))
        var started = 0L
        for (duration in listOf(7L, 500L, 3L, 99L)) {
            val next = started + duration + PollingRate.delayAfter(100, duration)
            assertTrue(next - started >= 100L)
            started = next
        }
    }
    @Test fun averagesKeepTwoDecimalsRegardlessOfFieldType() {
        assertEquals("32.20", ChartValues.mean(32.199))
        assertEquals("1.50", ChartValues.mean(1.5))
        assertEquals("2.00", ChartValues.mean(2.0))
        assertEquals("0.00", ChartValues.mean(-0.0001))
        assertEquals("—", ChartValues.mean(Double.NaN))
    }
    @Test fun drawingRetainsExtremaAndBoundsDensityWithoutChangingValues() {
        val times = LongArray(10000) { it.toLong() }
        val values = DoubleArray(10000) { 5.0 }
        values[456] = -100.0
        values[457] = 100.0
        val original = values.copyOf()
        val indices = PlotDecimator.indices(times, values, 0, 10000, 100, 1000)
        assertTrue(indices.size <= 400)
        assertTrue(456 in indices && 457 in indices)
        assertEquals(0, indices.first())
        assertEquals(9999, indices.last())
        assertTrue(indices.toList().zipWithNext().all { (a,b) -> a < b })
        assertArrayEquals(original, values, 0.0)
    }
    @Test fun missingValuesAndTimeGapsStayDisconnected() {
        assertArrayEquals(intArrayOf(0, -1, 2, 3, -1, 4),
            PlotDecimator.indices(longArrayOf(0,1,2,3,5000),
                doubleArrayOf(1.0,Double.NaN,2.0,3.0,4.0),0,6000,1,1000))
        assertTrue(PlotDecimator.indices(longArrayOf(0), doubleArrayOf(Double.NaN),0,1,1,1000).isEmpty())
    }
    @Test fun lowFrequencyDoesNotInventGapsAndEmptyWindowIsSupported() {
        assertArrayEquals(intArrayOf(0,1,2), PlotDecimator.indices(
            longArrayOf(0,1001,2003),doubleArrayOf(1.0,2.0,3.0),0,3000,30,3000))
        assertTrue(PlotDecimator.indices(longArrayOf(), doubleArrayOf(),0,3000,0,1000).isEmpty())
    }
}

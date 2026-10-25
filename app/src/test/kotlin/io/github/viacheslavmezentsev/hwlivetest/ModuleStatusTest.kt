package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class ModuleStatusTest {
    @Test fun ratesUseActualIntervalAndIdleBecomesZero() {
        val h = TrafficHistory(2)
        val device = Any()
        h.sample(1000, device, 100, 50)
        val active = h.sample(3000, device, 4100, 2050)
        assertEquals(2000.0, active.points.last().receivedPerSecond, 0.0)
        assertEquals(1000.0, active.points.last().sentPerSecond, 0.0)
        assertEquals(3000.0, active.peakPerSecond, 0.0)
        val idle = h.sample(4000, device, 4100, 2050)
        assertEquals(0.0, idle.points.last().receivedPerSecond, 0.0)
        assertEquals(2, h.sample(5000, device, 4100, 2050).points.size)
    }
    @Test fun reconnectDoesNotCreateNegativeRateOrKeepOldPeak() {
        val h = TrafficHistory()
        val device = Any()
        h.sample(0, device, 0, 0)
        h.sample(1000, device, 10000, 10000)
        val next = h.sample(2000, Any(), 20, 10)
        assertTrue(next.points.isEmpty())
        assertEquals(0.0, next.peakPerSecond, 0.0)
        assertTrue(h.sample(3000, null, 0, 0).points.isEmpty())
    }
    @Test fun signalRequiresFreshReadingAndLivePolling() {
        assertTrue(SignalFreshness.active(true, true, 2000, 1000))
        assertFalse(SignalFreshness.active(true, true, 2600, 1000))
        assertFalse(SignalFreshness.active(false, true, 2000, 1000))
        assertFalse(SignalFreshness.active(true, false, 2000, 1000))
        assertFalse(SignalFreshness.active(true, true, 2000, null))
    }
    @Test fun expertPagesFollowEngineerOrder() {
        assertEquals(listOf(WorkspacePage.TARGET, WorkspacePage.FLASH, WorkspacePage.SIGNALS, WorkspacePage.JOURNAL), WorkspacePage.entries.toList())
    }
}

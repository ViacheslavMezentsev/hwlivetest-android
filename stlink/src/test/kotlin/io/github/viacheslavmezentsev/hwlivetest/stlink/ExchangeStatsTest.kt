package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.*
import org.junit.Test

class ExchangeStatsTest {
    @Test fun operationResetPreservesLifetimeTrafficTotals() {
        val stats = ExchangeStats()
        stats.write(1000, 16)
        stats.read(2000, 64)
        stats.reset()
        assertEquals(ExchangeStats.Totals(64, 16), stats.totals())
        stats.read(1000, 32)
        assertEquals(ExchangeStats.Totals(96, 16), stats.totals())
        assertTrue(stats.lines().first().contains("0 передач"))
    }
}

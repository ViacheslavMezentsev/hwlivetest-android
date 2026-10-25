package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class RttRecordingLimitsTest {
    @Test fun hourBudgetIncludesRawMcapArchiveAndReserveWithoutIntOverflow() {
        val bytes = RttRecordingLimits.completionSpace(3_600_000, 6)
        assertTrue(bytes > Int.MAX_VALUE.toLong())
        assertTrue(bytes < 3L * 1024 * 1024 * 1024)
        assertTrue(RttRecordingLimits.completionSpace(6000, 1) < bytes)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeFrames() {
        RttRecordingLimits.completionSpace(-1, 6)
    }
}

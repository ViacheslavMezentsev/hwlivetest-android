package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class GenericMemoryTest {
    @Test fun genericCodeIsReadableWithoutAssumedStm32Bounds() {
        val c = MemoryMap.check(0x1000L, null, null, stm32Layout = false)
        assertEquals(MemoryMap.Region.CODE, c.region)
        assertTrue(c.readable)
        assertNotNull(c.warning)
        assertFalse(MemoryMap.check(0x1002L, null, null, false).readable)
        assertFalse(MemoryMap.check(0x100000000L, null, null, false).readable)
    }

    @Test fun genericSamplerReadsOnlyRequestedWordsAndDeduplicatesAliases() {
        val s = FieldSampler(listOf(
            Field("/a", FieldKind.UINT32, 0x10000000L),
            Field("/b", FieldKind.UINT32, 0x10000008L),
            Field("/alias", FieldKind.UINT32, 0x10000000L),
        ))
        s.groupReads = false
        val reads = mutableListOf<Pair<Long, Int>>()
        val result = s.sample(1, readBlock = { a, n -> reads += a to n; IntArray(n) { 42 } })
        assertEquals(listOf(0x10000000L to 1, 0x10000008L to 1), reads)
        assertTrue(result.all { it.value == 42.0 })
    }
}

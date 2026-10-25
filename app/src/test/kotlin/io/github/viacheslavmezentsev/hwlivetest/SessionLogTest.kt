package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class SessionLogTest {
    @org.junit.Test fun debugDetailsRemainInExportAndAreSeparatelyMarked() {
        val log = SessionLog()
        log.add("Проба завершена")
        log.add("SRAM details\nregisters", debugOnly = true)
        org.junit.Assert.assertEquals(listOf("Проба завершена"), log.snapshot().filter { !it.debugOnly }.map { it.text })
        org.junit.Assert.assertTrue(log.text().contains("registers"))
    }

    @Test fun rowIdentitySurvivesEvictionAndClear() {
        val log = SessionLog(2)
        log.add("first")
        log.add("second")
        val saved = log.snapshot()
        log.add("third")
        assertEquals(saved[1], log.snapshot()[0])
        assertEquals("second\nthird", log.text())
        assertEquals(2, saved.size)
        val last = log.snapshot().last().id
        log.clear()
        log.add("second")
        assertTrue(log.snapshot().single().id > last)
    }
    @Test fun copyIncludesProtocolAndMultilineEntries() {
        val log = SessionLog()
        log.add("# report\n# details")
        log.add("> f1")
        assertEquals("# report\n# details\n> f1", log.text())
        assertTrue(log.tail(1).endsWith("> f1"))
        assertFalse(SessionLog.isProtocol("# report"))
        assertTrue(SessionLog.isProtocol("# < 10 bytes"))
    }
}

package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class ConnectionReuseTest {
    // TC-41: ТЗ прошивки 5.14 — переходы не переоткрывают действующий транспорт.
    @Test fun repeatedRequestsShareOneConnection() {
        val reuse = ConnectionReuse<Any>()
        var opens = 0
        fun request() = reuse.getOrOpen("adapter", true) { opens++; Any() }
        val first = request()
        repeat(4) { assertSame(first, request()) }
        assertEquals(1, opens)
    }

    @Test fun detachOrNewDeviceOrLostPermissionRequiresOpen() {
        val reuse = ConnectionReuse<Any>()
        val first = reuse.getOrOpen("first", true) { Any() }
        val second = reuse.getOrOpen("second", true) { Any() }
        assertNotSame(first, second)
        val denied = reuse.getOrOpen("second", false) { Any() }
        assertNotSame(second, denied)
        reuse.clear()
        assertNotSame(denied, reuse.getOrOpen("second", true) { Any() })
    }

    @Test fun failedOpenDoesNotRestorePreviousTarget() {
        val reuse = ConnectionReuse<Any>()
        val first = reuse.getOrOpen("adapter", true) { Any() }
        try {
            reuse.getOrOpen("adapter", false) { error("USB lost") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        var opens = 0
        val next = reuse.getOrOpen("adapter", true) { opens++; Any() }
        assertNotSame(first, next)
        assertEquals(1, opens)
    }

    @Test fun workspaceWrapsInBothDirections() {
        assertEquals(WorkspacePage.TARGET, WorkspacePage.JOURNAL.next(1))
        assertEquals(WorkspacePage.JOURNAL, WorkspacePage.TARGET.next(-1))
        WorkspacePage.entries.forEach { assertEquals(it, it.next(1).next(-1)) }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import org.junit.Assert.*
import org.junit.Test

class RttSessionTest {
    private val plan = RttTeardown.Plan(RttDoctor.timers.first(), 0x20001000, 0x20001078, 0x20000000, 0x08000000, 0)
    private class Output : RttSession.Sink {
        var closed = false
        var failWrite = false
        var failClose = false
        override fun accept(bytes: ByteArray) { if (failWrite) error("disk full") }
        override fun close() { closed = true; if (failClose) error("close failed") }
    }
    private fun session(events: MutableList<String>, installOk: Boolean = true,
        recoveryOk: Boolean = true, read: () -> ByteArray = { byteArrayOf(1, 2, 3, 4) }): RttSession {
        var time = 0L
        return RttSession(
            install = { events += "install"; RttInstaller.Result(installOk, plan, "upload", if (installOk) null else "upload failed") },
            poll = { events += "poll"; read() },
            teardown = { _, _ -> events += "teardown"; RttTeardown.Result(recoveryOk, if (recoveryOk) "complete" else "liveness", 0) },
            nowMs = { time }, sleep = { time += it })
    }
    @Test fun boundedRecordingAlwaysRestoresAndCloses() {
        val events = mutableListOf<String>(); val output = Output()
        val result = session(events).run(25, output)
        assertTrue(result.toString(), result.ok)
        assertEquals(12L, result.bytes)
        assertEquals("teardown", events.last()); assertTrue(output.closed)
    }
    @Test fun cancellationBeforeInstallDoesNotTouchDevice() {
        val events = mutableListOf<String>(); val output = Output(); val s = session(events)
        s.cancel(); val result = s.run(25, output)
        assertEquals("cancelled", result.reason); assertTrue(events.isEmpty()); assertTrue(output.closed)
    }
    @Test fun cancellationDuringPollingStillRestores() {
        val events = mutableListOf<String>(); lateinit var s: RttSession
        s = session(events, read = { s.cancel(); byteArrayOf(1,2,3,4) })
        val result = s.run(25, Output())
        assertEquals("cancelled", result.reason); assertTrue(result.recovery!!.ok)
        assertEquals(1, events.count { it == "poll" })
    }
    @Test fun partialInstallationRestoresWithoutPolling() {
        val events = mutableListOf<String>(); val output = Output()
        val result = session(events, installOk = false).run(25, output)
        assertEquals(listOf("install", "teardown"), events)
        assertFalse(result.ok); assertTrue(result.recovery!!.ok); assertTrue(output.closed)
    }
    @Test fun readFailureAndSinkFailureBothRestore() {
        for (sinkFailure in listOf(false, true)) {
            val events = mutableListOf<String>(); val output = Output().also { it.failWrite = sinkFailure }
            val s = session(events, read = { if (!sinkFailure) error("USB") else byteArrayOf(1) })
            val result = s.run(25, output)
            assertFalse(result.ok); assertTrue(result.recovery!!.ok); assertTrue(output.closed)
        }
    }
    @Test fun recoveryFailureIsNotMaskedBySavedBytes() {
        val result = session(mutableListOf(), recoveryOk = false).run(25, Output())
        assertTrue(result.bytes > 0); assertFalse(result.ok); assertEquals("liveness", result.recovery!!.stage)
    }
    @Test fun emptyStreamIsNotSuccess() {
        val result = session(mutableListOf(), read = { byteArrayOf() }).run(25, Output())
        assertEquals("no_data", result.reason); assertTrue(result.recovery!!.ok); assertFalse(result.ok)
    }
    @Test fun closeFailureIsSeparateFromRestoration() {
        val result = session(mutableListOf()).run(25, Output().also { it.failClose = true })
        assertFalse(result.ok); assertTrue(result.recovery!!.ok); assertEquals("close failed", result.closeError)
    }
    @Test fun interruptStillRestoresThenRestoresThreadFlag() {
        try {
            val result = session(mutableListOf(), read = { throw InterruptedException() }).run(25, Output())
            assertEquals("interrupted", result.reason); assertTrue(result.recovery!!.ok)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun finalTailIsWrittenBeforeCloseAndIncludedInTotal() {
        var time = 0L
        val received = mutableListOf<Byte>()
        var closed = false
        val result = RttSession(
            install = { RttInstaller.Result(true, plan, "complete") },
            poll = { byteArrayOf() },
            teardown = { _, accept ->
                assertFalse(closed)
                accept(byteArrayOf(1, 2, 3))
                RttTeardown.Result(true, "complete", 0)
            }, nowMs = { time }, sleep = { time += it }
        ).run(10, object : RttSession.Sink {
            override fun accept(bytes: ByteArray) { received.addAll(bytes.toList()) }
            override fun close() { closed = true }
        })
        assertEquals(3L, result.bytes); assertEquals(3, received.size)
        assertTrue(closed); assertTrue(result.ok)
    }

    @Test fun sessionCannotBeReused() {
        val s = session(mutableListOf()); s.run(1, Output())
        try { s.run(1, Output()); fail() } catch (_: IllegalStateException) { }
    }
}

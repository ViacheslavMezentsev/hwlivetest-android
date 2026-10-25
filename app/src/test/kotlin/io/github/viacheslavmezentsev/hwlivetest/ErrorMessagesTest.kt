package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbAccessException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMessagesTest {
    @Test
    fun everyErrorKindHasReadableText() {
        StLinkErrorKind.entries.forEach {
            val text = ErrorMessages.describe(StLinkException(it, "x"))
            assertTrue(text, text.isNotBlank())
        }
        UsbAccessException.Reason.entries.forEach {
            val text = ErrorMessages.describe(UsbAccessException(it, "x"))
            assertTrue(text, text.isNotBlank())
        }
    }

    @Test
    fun noTargetMentionsDevice() {
        val text = ErrorMessages.describe(StLinkException(StLinkErrorKind.NO_TARGET, "Устройство не отвечает"))
        assertTrue(text.startsWith("Нет устройства"))
    }

    @Test
    fun sessionLogKeepsLastLines() {
        val log = SessionLog(maxLines = 2)
        log.add("a")
        log.add("b")
        log.add("c")
        assertEquals("b\nc", log.text())
    }

    @Test
    fun sessionLogTailAndProtocolLines() {
        val log = SessionLog()
        (1..5).forEach { log.add("# $it") }
        assertTrue(log.tail(2).startsWith("… ещё 3 строк"))
        assertTrue(log.tail(2).endsWith("# 4\n# 5"))
        assertEquals("# 1\n# 2\n# 3\n# 4\n# 5", log.tail(10))
        assertTrue(SessionLog.isProtocol("> f2 36"))
        assertTrue(SessionLog.isProtocol("< 80 00"))
        assertTrue(SessionLog.isProtocol("# < 1024 байт: 00 …"))
        assertTrue(!SessionLog.isProtocol("# опрос остановлен"))
    }
}

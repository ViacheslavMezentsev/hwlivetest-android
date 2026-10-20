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
    fun noTargetMentionsTarget() {
        val text = ErrorMessages.describe(StLinkException(StLinkErrorKind.NO_TARGET, "Цель не отвечает"))
        assertTrue(text.startsWith("Нет цели"))
    }

    @Test
    fun sessionLogKeepsLastLines() {
        val log = SessionLog(maxLines = 2)
        log.add("a")
        log.add("b")
        log.add("c")
        assertEquals("b\nc", log.text())
    }
}

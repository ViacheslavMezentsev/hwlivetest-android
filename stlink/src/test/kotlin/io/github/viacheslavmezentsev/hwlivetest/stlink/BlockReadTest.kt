package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BlockReadTest {
    private val v2 = "> f1 80\n< 29 47 83 04 48 37\n" // V2J37S7: есть GETLASTRWSTATUS2

    @Test
    fun encodesReadMem32() {
        assertArrayEquals(
            Hex.parse("f2 07 54 0c 00 20 3c 00").copyOf(16),
            StLinkProtocol.readMem32(0x20000C54L, 60),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToCross1kBoundary() {
        StLinkProtocol.readMem32(0x200003FCL, 8)
    }

    @Test
    fun readsWordsAndChecksStatus() {
        val t = ReplayTransport.parse(
            v2 + """
            > f2 07 54 0c 00 20 0c
            < 01 00 00 00 02 00 00 00 03 00 00 00
            > f2 3e
            < 80 00 00 00 00 00 00 00 00 00 00 00
            """.trimIndent(),
        )
        val st = StLink(t)
        st.readVersion()
        assertArrayEquals(intArrayOf(1, 2, 3), st.readBlock(0x20000C54L, 3))
        t.assertFinished()
    }

    @Test
    fun splitsAt1kBoundaryAndRetriesWait() {
        val t = ReplayTransport.parse(
            v2 + """
            > f2 07 f8 03 00 20 08
            < 0a 00 00 00 0b 00 00 00
            > f2 3e
            < 14 00 00 00 00 00 00 00 00 00 00 00
            > f2 07 f8 03 00 20 08
            < 0a 00 00 00 0b 00 00 00
            > f2 3e
            < 80 00 00 00 00 00 00 00 00 00 00 00
            > f2 07 00 04 00 20 04
            < 0c 00 00 00
            > f2 3e
            < 80 00 00 00 00 00 00 00 00 00 00 00
            """.trimIndent(),
        )
        val st = StLink(t, sleep = {})
        st.readVersion()
        assertArrayEquals(intArrayOf(10, 11, 12), st.readBlock(0x200003F8L, 3))
        t.assertFinished()
    }

    @Test
    fun oldFirmwareUsesShortStatusAndReportsFault() {
        // V2J14S0: без GETLASTRWSTATUS2
        val t = ReplayTransport.parse(
            """
            > f1 80
            < 23 80 83 04 48 37
            > f2 07 00 00 00 08 04
            < 00 00 00 00
            > f2 3b
            < 11 00
            """.trimIndent(),
        )
        val st = StLink(t)
        assertEquals(14, st.readVersion().jtag)
        try {
            st.readBlock(0x08000000L, 1)
            fail("ожидалась ошибка")
        } catch (e: StLinkException) {
            assertEquals(StLinkErrorKind.SWD_FAULT, e.kind)
            assertEquals(0x11, e.status)
        }
        t.assertFinished()
    }
}

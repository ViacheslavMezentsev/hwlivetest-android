package io.github.viacheslavmezentsev.hwlivetest.diag

import io.github.viacheslavmezentsev.hwlivetest.stlink.Hex
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLink
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkProtocol
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkTransport
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проигрывание записи обмена (формат журнала приложения). */
private class Replay(text: String) : StLinkTransport {
    private val steps = text.lineSequence().map { it.trim() }
        .filter { it.startsWith(">") || it.startsWith("<") }.toList()
    private var pos = 0

    override fun write(data: ByteArray) {
        val s = steps[pos++]
        assertTrue("ожидался ответ: $s", s.startsWith(">"))
        assertArrayEquals(s, Hex.parse(s.substring(1)).copyOf(StLinkProtocol.CMD_SIZE), data)
    }

    override fun read(length: Int): ByteArray {
        val s = steps[pos++]
        assertTrue("ожидалась команда: $s", s.startsWith("<"))
        return Hex.parse(s.substring(1)).also { assertEquals(length, it.size) }
    }

    fun finished() = assertEquals(steps.size, pos)
}

class PhoneStartTest {
    @Test
    fun startOnStm32f411FromPhone() {
        val text = javaClass.getResourceAsStream("/captures/phone-v21-stm32f411-start.txt")!!.bufferedReader().readText()
        val t = Replay(text)
        val st = StLink(t, sleep = {})
        val c = st.connect()
        val info = TargetIdentifier(st).identify()
        val r = Diagnostics(st, info, AdapterFacts("ST-Link/V2-1", c.version.label, c.targetVoltage), sleep = {}).run()
        t.finished()
        assertEquals("MCU отвечает · ядро работает · прошивка есть · PLL от HSE 100 МГц", r.summary)
        assertEquals(Level.OK, r.level)
        assertEquals("Ядро выполняет инструкции", r.sections.first { it.title == "Ядро" }.headline)
        assertTrue(r.text(), r.text().contains("HSE ≈ 25 МГц"))
    }
}

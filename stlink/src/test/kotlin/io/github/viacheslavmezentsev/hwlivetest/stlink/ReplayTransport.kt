package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Проигрывает запись обмена: проверяет каждую команду и отдаёт записанные ответы. */
class ReplayTransport(private val steps: List<Step>) : StLinkTransport {
    sealed class Step {
        abstract val line: Int

        class Out(val data: ByteArray, override val line: Int) : Step()
        class In(val data: ByteArray, override val line: Int) : Step()
    }

    private var pos = 0

    override fun write(data: ByteArray) {
        val step = next("команда ${Hex.bytes(data)}")
        assertTrue("строка ${step.line}: ожидался ответ, а пришла команда ${Hex.bytes(data)}", step is Step.Out)
        val expected = (step as Step.Out).data.copyOf(StLinkProtocol.CMD_SIZE)
        assertArrayEquals("строка ${step.line}: команда", expected, data)
    }

    override fun read(length: Int): ByteArray {
        val step = next("чтение $length байт")
        assertTrue("строка ${step.line}: ожидалась команда, а пришло чтение", step is Step.In)
        val bytes = (step as Step.In).data
        assertEquals("строка ${step.line}: длина ответа", length, bytes.size)
        return bytes
    }

    fun assertFinished() {
        assertEquals("в записи остались неиспользованные шаги", steps.size, pos)
    }

    private fun next(what: String): Step {
        check(pos < steps.size) { "Запись закончилась, а программа делает: $what" }
        return steps[pos++]
    }

    companion object {
        fun parse(text: String): ReplayTransport {
            val steps = mutableListOf<Step>()
            text.lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                when {
                    line.startsWith(">") -> steps += Step.Out(Hex.parse(line.substring(1)), i + 1)
                    line.startsWith("<") -> steps += Step.In(Hex.parse(line.substring(1)), i + 1)
                }
            }
            return ReplayTransport(steps)
        }

        fun fromResource(name: String): ReplayTransport {
            val stream = ReplayTransport::class.java.getResourceAsStream("/captures/$name")
                ?: error("Нет ресурса captures/$name")
            return parse(stream.bufferedReader(Charsets.UTF_8).readText())
        }
    }
}

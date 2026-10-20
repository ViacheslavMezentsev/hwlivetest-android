package io.github.viacheslavmezentsev.hwlivetest.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class McapWriterTest {
    private class Rec(val op: Int, val start: Int, val body: ByteBuffer)

    /** Разбор файла по записям: проверка структуры по спецификации. */
    private fun records(b: ByteArray): List<Rec> {
        assertArrayEquals(McapWriter.MAGIC, b.copyOfRange(0, 8))
        assertArrayEquals(McapWriter.MAGIC, b.copyOfRange(b.size - 8, b.size))
        val out = ArrayList<Rec>()
        var p = 8
        while (p < b.size - 8) {
            val op = b[p].toInt() and 0xFF
            val len = ByteBuffer.wrap(b, p + 1, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
            out += Rec(op, p, ByteBuffer.wrap(b, p + 9, len).slice().order(ByteOrder.LITTLE_ENDIAN))
            p += 9 + len
        }
        assertEquals(b.size - 8, p)
        return out
    }

    private fun ByteBuffer.str(): String {
        val n = int
        val a = ByteArray(n)
        get(a)
        return String(a, Charsets.UTF_8)
    }

    @Test
    fun writesValidUnchunkedFileWithSummary() {
        val bo = ByteArrayOutputStream()
        McapWriter(bo).use { w ->
            val v = w.addChannel("/diag/sensor/batt_v", Ros1Type.FLOAT32)
            val n = w.addChannel("/diag/sensor/task_count", Ros1Type.UINT32)
            val v2 = w.addChannel("/diag/raw/batt_v", Ros1Type.FLOAT32)
            w.write(v, 1_000_000_000L, 31.89)
            w.write(n, 1_000_000_000L, 6.0)
            w.write(v2, 1_100_000_000L, 31.87)
            assertEquals(3, w.messageCount)
        }
        val b = bo.toByteArray()
        val recs = records(b)
        val ops = recs.map { it.op }
        assertEquals(McapWriter.OP_HEADER, ops.first())
        assertEquals(McapWriter.OP_FOOTER, ops.last())
        // Данные: заголовок, 2 схемы, 3 канала, 3 сообщения, конец данных.
        assertEquals(listOf(1, 3, 4, 3, 4, 4, 5, 5, 5, 0x0F), ops.subList(0, 10))
        assertEquals("ros1", recs[0].body.str())

        val schema = recs[1].body
        assertEquals(1, schema.short.toInt())
        assertEquals("std_msgs/Float32", schema.str())
        assertEquals("ros1msg", schema.str())
        assertEquals("float32 data", schema.str())

        val msg = recs[6].body
        assertEquals(0, msg.short.toInt())
        assertEquals(0, msg.int)
        assertEquals(1_000_000_000L, msg.long)
        assertEquals(1_000_000_000L, msg.long)
        assertEquals(31.89f, msg.float, 0f)

        val task = recs[7].body
        task.position(2 + 4 + 16)
        assertEquals(6, task.int)

        // Подвал: смещения сводки и CRC.
        val footer = recs.last()
        val summaryStart = footer.body.long.toInt()
        val offsetStart = footer.body.long.toInt()
        val crc = footer.body.int.toLong() and 0xFFFFFFFFL
        assertEquals(McapWriter.OP_SCHEMA, b[summaryStart].toInt())
        assertEquals(McapWriter.OP_SUMMARY_OFFSET, b[offsetStart].toInt())
        val c = CRC32().apply { update(b, summaryStart, footer.start + 1 + 8 + 16 - summaryStart) }
        assertEquals(c.value, crc)
        val stats = recs.first { it.op == McapWriter.OP_STATISTICS }.body
        assertEquals(3L, stats.long)
        assertEquals(2, stats.short.toInt())
        assertEquals(3, stats.int)
    }

    @Test
    fun encodesRos1Types() {
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), Ros1Type.UINT32.encode(4294967295.0))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), Ros1Type.INT32.encode(-2.0))
        assertEquals(8, Ros1Type.FLOAT64.encode(1.0).size)
        assertEquals(Ros1Type.FLOAT32, Ros1Type.byName("std_msgs/Float32"))
    }
}

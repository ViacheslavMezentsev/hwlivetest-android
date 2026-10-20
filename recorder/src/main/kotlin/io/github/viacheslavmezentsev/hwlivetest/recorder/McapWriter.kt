package io.github.viacheslavmezentsev.hwlivetest.recorder

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Типы сообщений ROS 1, в которых пишутся значения полей (как у stm32-diag-record). */
enum class Ros1Type(val rosName: String, val definition: String, val size: Int) {
    FLOAT32("std_msgs/Float32", "float32 data", 4),
    FLOAT64("std_msgs/Float64", "float64 data", 8),
    UINT32("std_msgs/UInt32", "uint32 data", 4),
    INT32("std_msgs/Int32", "int32 data", 4);

    /** Сериализация ROS 1: одно поле data, little-endian. */
    fun encode(value: Double): ByteArray {
        val bits: Long = when (this) {
            FLOAT32 -> java.lang.Float.floatToRawIntBits(value.toFloat()).toLong()
            FLOAT64 -> java.lang.Double.doubleToRawLongBits(value)
            UINT32 -> value.toLong() and 0xFFFFFFFFL
            INT32 -> value.toLong().toInt().toLong()
        }
        return ByteArray(size) { (bits ushr (8 * it)).toByte() }
    }

    companion object {
        fun byName(name: String?): Ros1Type? = entries.firstOrNull { it.rosName == name }
    }
}

/**
 * Запись MCAP (https://mcap.dev/spec) без чанков и сжатия: профиль `ros1`, схемы
 * `ros1msg`. В конце — сводка (схемы, каналы, статистика) со смещениями, чтобы
 * Foxglove и PlotJuggler сразу видели длительность и каналы. CRC сводки считается,
 * CRC секции данных — 0 (по спецификации: «не проверять»).
 *
 * Не потокобезопасен: писать из одного потока.
 */
class McapWriter(out: OutputStream, library: String = "hwlivetest-android") : AutoCloseable {
    private val out = CountingStream(out)

    private class Channel(val id: Int, val schemaId: Int, val topic: String, val type: Ros1Type)

    private val schemas = LinkedHashMap<Ros1Type, Int>()
    private val channels = ArrayList<Channel>()
    private val counts = HashMap<Int, Long>()
    private var sequence = 0L
    private var messages = 0L
    private var firstTime = Long.MAX_VALUE
    private var lastTime = 0L
    private var closed = false

    init {
        this.out.write(MAGIC)
        record(OP_HEADER) {
            str("ros1")
            str(library)
        }
    }

    /** Объявить канал; возвращает id для [write]. */
    fun addChannel(topic: String, type: Ros1Type): Int {
        val schemaId = schemas[type] ?: (schemas.size + 1).also { id ->
            schemas[type] = id
            record(OP_SCHEMA) { schemaBody(id, type) }
        }
        val ch = Channel(channels.size, schemaId, topic, type)
        channels += ch
        record(OP_CHANNEL) { channelBody(ch) }
        return ch.id
    }

    /** Записать значение в канал [channel]; время — наносекунды Unix. */
    fun write(channel: Int, timeNs: Long, value: Double) {
        check(!closed) { "файл закрыт" }
        val ch = channels[channel]
        val payload = ch.type.encode(value)
        record(OP_MESSAGE) {
            u16(ch.id)
            u32(sequence++)
            u64(timeNs)
            u64(timeNs)
            bytes(payload)
        }
        messages++
        counts[ch.id] = (counts[ch.id] ?: 0L) + 1
        if (timeNs < firstTime) firstTime = timeNs
        if (timeNs > lastTime) lastTime = timeNs
    }

    val messageCount: Long get() = messages

    val bytesWritten: Long get() = out.count

    override fun close() {
        if (closed) return
        closed = true
        record(OP_DATA_END) { u32(0) }
        // Сводка: схемы, каналы, статистика, затем смещения групп.
        val summaryStart = out.count
        val summary = ByteArrayOutputStream()
        val groups = ArrayList<Triple<Int, Long, Long>>()
        fun group(op: Int, records: List<ByteArray>) {
            if (records.isEmpty()) return
            val start = summaryStart + summary.size()
            records.forEach { summary.write(it, 0, it.size) }
            groups += Triple(op, start, summaryStart + summary.size() - start)
        }
        group(OP_SCHEMA, schemas.map { (t, id) -> recordBytes(OP_SCHEMA) { schemaBody(id, t) } })
        group(OP_CHANNEL, channels.map { ch -> recordBytes(OP_CHANNEL) { channelBody(ch) } })
        group(OP_STATISTICS, listOf(recordBytes(OP_STATISTICS) { statisticsBody() }))
        val summaryOffsetStart = summaryStart + summary.size()
        for ((op, start, len) in groups) {
            val r = recordBytes(OP_SUMMARY_OFFSET) {
                u8(op)
                u64(start)
                u64(len)
            }
            summary.write(r, 0, r.size)
        }
        val footerHead = Body().apply {
            u8(OP_FOOTER)
            u64(8 + 8 + 4)
            u64(summaryStart)
            u64(summaryOffsetStart)
        }.toByteArray()
        val summaryBytes = summary.toByteArray()
        val crc = CRC32().apply {
            update(summaryBytes)
            update(footerHead)
        }.value
        out.write(summaryBytes)
        out.write(footerHead)
        out.write(Body().apply { u32(crc) }.toByteArray())
        out.write(MAGIC)
        out.flush()
        out.close()
    }

    // ---------- Кодирование ----------

    private class Body : ByteArrayOutputStream() {
        fun u8(v: Int) = write(v and 0xFF)

        fun u16(v: Int) {
            write(v and 0xFF)
            write((v ushr 8) and 0xFF)
        }

        fun u32(v: Long) {
            for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun u64(v: Long) {
            for (i in 0 until 8) write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun bytes(b: ByteArray) = write(b, 0, b.size)

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size.toLong())
            bytes(b)
        }
    }

    private fun Body.schemaBody(id: Int, t: Ros1Type) {
        u16(id)
        str(t.rosName)
        str("ros1msg")
        val d = t.definition.toByteArray(Charsets.UTF_8)
        u32(d.size.toLong())
        bytes(d)
    }

    private fun Body.channelBody(ch: Channel) {
        u16(ch.id)
        u16(ch.schemaId)
        str(ch.topic)
        str("ros1")
        u32(0) // пустые метаданные
    }

    private fun Body.statisticsBody() {
        u64(messages)
        u16(schemas.size)
        u32(channels.size.toLong())
        u32(0) // вложения
        u32(0) // метаданные
        u32(0) // чанки
        u64(if (messages == 0L) 0 else firstTime)
        u64(if (messages == 0L) 0 else lastTime)
        val map = Body()
        for ((id, n) in counts.toSortedMap()) {
            map.u16(id)
            map.u64(n)
        }
        u32(map.size().toLong())
        bytes(map.toByteArray())
    }

    private fun recordBytes(op: Int, fill: Body.() -> Unit): ByteArray {
        val body = Body().apply(fill).toByteArray()
        return Body().apply {
            u8(op)
            u64(body.size.toLong())
            bytes(body)
        }.toByteArray()
    }

    private fun record(op: Int, fill: Body.() -> Unit) = out.write(recordBytes(op, fill))

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()

        override fun close() = inner.close()
    }

    companion object {
        val MAGIC = byteArrayOf(0x89.toByte(), 0x4D, 0x43, 0x41, 0x50, 0x30, 0x0D, 0x0A)
        const val OP_HEADER = 0x01
        const val OP_FOOTER = 0x02
        const val OP_SCHEMA = 0x03
        const val OP_CHANNEL = 0x04
        const val OP_MESSAGE = 0x05
        const val OP_STATISTICS = 0x0B
        const val OP_SUMMARY_OFFSET = 0x0E
        const val OP_DATA_END = 0x0F
    }
}

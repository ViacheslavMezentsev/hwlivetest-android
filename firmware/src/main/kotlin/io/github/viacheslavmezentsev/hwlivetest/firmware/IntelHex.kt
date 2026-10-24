package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Непрерывный участок образа. */
class Segment(val address: Long, val data: ByteArray) {
    val end: Long get() = address + data.size
}

/** Intel HEX: разбор (типы 00, 01, 02, 04, 03, 05) и запись (00, 04, 05, 01). */
object IntelHex {
    class FormatException(message: String) : IllegalArgumentException(message)

    /** Разобрать HEX в участки, слитые по смежности и упорядоченные по адресу. */
    fun parse(text: String): List<Segment> {
        val bytes = sortedMapOf<Long, Byte>()
        var upper = 0L
        var eof = false
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || eof) return@forEachIndexed
            val n = index + 1
            if (!line.startsWith(":")) throw FormatException("строка $n: нет ':'")
            val rec = try {
                line.substring(1).chunked(2).map { it.toInt(16) }
            } catch (_: NumberFormatException) {
                throw FormatException("строка $n: не шестнадцатеричные символы")
            }
            if (rec.size < 5 || rec.size != rec[0] + 5) throw FormatException("строка $n: неверная длина")
            if (rec.sum() and 0xFF != 0) throw FormatException("строка $n: неверная контрольная сумма")
            val len = rec[0]
            val offset = (rec[1] shl 8) or rec[2]
            val data = rec.subList(4, 4 + len)
            when (rec[3]) {
                0x00 -> data.forEachIndexed { i, b -> bytes[upper + offset + i] = b.toByte() }
                0x01 -> eof = true
                0x02 -> upper = (((data[0] shl 8) or data[1]).toLong()) shl 4
                0x04 -> upper = (((data[0] shl 8) or data[1]).toLong()) shl 16
                0x03, 0x05 -> Unit // адрес запуска не нужен
                else -> throw FormatException("строка $n: неизвестный тип записи ${rec[3]}")
            }
        }
        if (bytes.isEmpty()) throw FormatException("в файле нет данных")
        val out = ArrayList<Segment>()
        var start = -1L
        var prev = -2L
        val buf = ByteArrayOutputStream()
        for ((a, b) in bytes) {
            if (a != prev + 1) {
                if (start >= 0) out += Segment(start, buf.toByteArray())
                buf.reset()
                start = a
            }
            buf.write(b.toInt())
            prev = a
        }
        out += Segment(start, buf.toByteArray())
        return out
    }

    /** Записать участок в Intel HEX по 16 байт в строке. */
    fun write(segment: Segment, startAddress: Long? = null): String = write(listOf(segment), startAddress)

    /** Записать несколько участков (по возрастанию адреса) в один файл. */
    fun write(segments: List<Segment>, startAddress: Long? = null): String = buildString {
        var upper = -1L
        for (segment in segments) {
            var pos = 0
            while (pos < segment.data.size) {
                val a = segment.address + pos
                val hi = a ushr 16
                if (hi != upper) {
                    record(0x04, 0, byteArrayOf((hi ushr 8).toByte(), hi.toByte()))
                    upper = hi
                }
                val lineLen = minOf(16, segment.data.size - pos, (0x10000 - (a and 0xFFFF)).toInt())
                record(0x00, (a and 0xFFFF).toInt(), segment.data.copyOfRange(pos, pos + lineLen))
                pos += lineLen
            }
        }
        startAddress?.let {
            record(0x05, 0, ByteArray(4) { i -> (it ushr (24 - 8 * i)).toByte() })
        }
        record(0x01, 0, ByteArray(0))
    }

    private fun StringBuilder.record(type: Int, offset: Int, data: ByteArray) {
        val rec = ArrayList<Int>()
        rec += data.size
        rec += (offset ushr 8) and 0xFF
        rec += offset and 0xFF
        rec += type
        data.forEach { rec += it.toInt() and 0xFF }
        val sum = (0x100 - (rec.sum() and 0xFF)) and 0xFF
        append(':')
        rec.forEach { append("%02X".format(it)) }
        append("%02X".format(sum))
        append("\r\n")
    }

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

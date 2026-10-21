package io.github.viacheslavmezentsev.hwlivetest.fx2la

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Имитатор анализатора: записывает управляющие запросы и выдаёт заданный поток (ТЗ ЛА 7.1.2). */
class FakeFx2(
    override val packetSize: Int = 512,
    var firmware: Fx2Protocol.Version? = Fx2Protocol.Version(1, 3),
    /** Генератор отсчёта по номеру. */
    val sample: (Long) -> Int = { 0 },
    /** После скольких отсчётов поток обрывается; null — не обрывается. */
    val stallAfter: Long? = null,
    /** Время на байт, нс: 1e9 / частота — поток успевает. */
    val nsPerByte: Double = 1e9 / 24e6,
) : Fx2Transport {
    override val maxBufferBytes = 262_144
    val out = mutableListOf<Triple<Int, Int, ByteArray>>()
    var streams = mutableListOf<Pair<Int, Int>>()

    override fun controlOut(request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int) {
        out += Triple(request, value, data.copyOf())
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? = when (request) {
        Fx2Protocol.CMD_GET_FW_VERSION -> firmware?.let { byteArrayOf(it.major.toByte(), it.minor.toByte()) }
        Fx2Protocol.CMD_GET_REVID_VERSION -> if (firmware != null) byteArrayOf(4) else null
        else -> null
    }

    override fun stream(bufferBytes: Int, transfers: Int, stallMs: Long, sink: (ByteArray, Int, Long) -> Boolean) {
        streams += bufferBytes to transfers
        val buf = ByteArray(bufferBytes)
        var n = 0L
        while (true) {
            var len = bufferBytes
            if (stallAfter != null) {
                if (n >= stallAfter) throw StallException("нет данных $stallMs мс")
                len = minOf(len.toLong(), stallAfter - n).toInt()
            }
            for (i in 0 until len) buf[i] = sample(n + i).toByte()
            n += len
            if (!sink(buf, len, (n * nsPerByte).toLong())) return
        }
    }

    override fun close() = Unit
}

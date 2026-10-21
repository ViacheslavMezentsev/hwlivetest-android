package io.github.viacheslavmezentsev.hwlivetest.fx2la

import io.github.viacheslavmezentsev.hwlivetest.firmware.IntelHex

/** Порция загрузки: адрес в ОЗУ FX2 и данные (не больше [Fx2Firmware.CHUNK_BYTES]). */
class Fx2Chunk(val address: Int, val data: ByteArray)

/** Проверенный образ прошивки для ОЗУ FX2. */
class Fx2Image(val chunks: List<Fx2Chunk>, val size: Int, val sha256: String) {
    val sha256Short: String get() = sha256.take(16)
}

/**
 * Образ прошивки FX2: двоичный `.fw` (с адреса 0) или Intel HEX (ТЗ ЛА 3.2.2, 3.2.3).
 * Сам образ fx2lafw в приложение не входит: файл выбирает пользователь (ТЗ ЛА 6.1.2).
 */
object Fx2Firmware {
    /** ОЗУ кода FX2 — 16 КБ. */
    const val RAM_BYTES = 0x4000
    const val CHUNK_BYTES = 4096

    class BadImage(message: String) : IllegalArgumentException(message)

    fun parse(bytes: ByteArray): Fx2Image {
        if (bytes.isEmpty()) throw BadImage("файл пуст")
        val segments: List<Pair<Int, ByteArray>> = if (looksLikeHex(bytes)) {
            try {
                IntelHex.parse(bytes.toString(Charsets.US_ASCII)).map { it.address.toInt() to it.data }
            } catch (e: IntelHex.FormatException) {
                throw BadImage("Intel HEX: ${e.message}")
            }
        } else {
            listOf(0 to bytes)
        }
        for ((address, data) in segments) {
            if (address < 0 || address + data.size > RAM_BYTES) {
                throw BadImage(
                    "образ не помещается в ОЗУ FX2: 0x%04X…0x%04X при пределе 0x%04X"
                        .format(address, address + data.size - 1, RAM_BYTES - 1),
                )
            }
        }
        val chunks = segments.flatMap { (address, data) ->
            (data.indices step CHUNK_BYTES).map { off ->
                Fx2Chunk(address + off, data.copyOfRange(off, minOf(off + CHUNK_BYTES, data.size)))
            }
        }
        return Fx2Image(chunks, segments.sumOf { it.second.size }, IntelHex.sha256(bytes))
    }

    private fun looksLikeHex(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull { it != ' '.code.toByte() && it != '\r'.code.toByte() && it != '\n'.code.toByte() }
        if (first != ':'.code.toByte()) return false
        // Двоичный образ fx2lafw начинается с команды LJMP (0x02), а не с ':'.
        return bytes.all { b -> b == '\r'.code.toByte() || b == '\n'.code.toByte() || b in 0x20..0x7E }
    }
}

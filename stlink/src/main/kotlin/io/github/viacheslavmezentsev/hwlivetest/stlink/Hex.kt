package io.github.viacheslavmezentsev.hwlivetest.stlink

/** Вспомогательные функции для журналов и разбора ответов. */
object Hex {
    fun bytes(data: ByteArray, length: Int = data.size): String =
        (0 until minOf(length, data.size)).joinToString(" ") { "%02x".format(data[it].toInt() and 0xFF) }

    /** Разобрать строку вида "29 47 83 04" (пробелы и запятые игнорируются). */
    fun parse(text: String): ByteArray {
        val clean = text.replace(Regex("[\\s,]"), "").removePrefix("0x")
        require(clean.length % 2 == 0) { "Нечётное число шестнадцатеричных цифр: $text" }
        return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    fun u16le(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)

    fun u32le(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8) or (u8(data, offset + 2) shl 16) or (u8(data, offset + 3) shl 24)

    fun putU16le(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    fun putU32le(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) data[offset + i] = (value ushr (8 * i)).toByte()
    }
}

/** 0x1BA01477 */
fun Int.hex32(): String = "0x%08X".format(this)

/** 0xE000ED00 */
fun Long.hex32(): String = "0x%08X".format(this and 0xFFFFFFFFL)

fun Int.hex16(): String = "0x%04X".format(this and 0xFFFF)

fun Int.hex12(): String = "0x%03X".format(this and 0xFFF)

fun Int.u32(): Long = toLong() and 0xFFFFFFFFL

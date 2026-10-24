package io.github.viacheslavmezentsev.hwlivetest.stlink

/**
 * Команды ST-Link, меняющие состояние цели (ТЗ ST-Link 2.0, п. 3.2.4). Отдельный объект, чтобы
 * список команд чтения ([StLinkProtocol]) оставался только для чтения; отправляются эти команды
 * только через [TargetWrite] с разрешением (п. 5.11.1).
 */
object StLinkWriteProtocol {
    const val DEBUG_WRITEMEM_32BIT = 0x08
    const val DEBUG_WRITEMEM_8BIT = 0x0D
    const val DEBUG_APIV2_WRITEDEBUGREG = 0x35
    const val DEBUG_APIV2_DRIVE_NRST = 0x3C
    const val DEBUG_APIV2_WRITEMEM_16BIT = 0x48

    const val NRST_LOW = 0x00
    const val NRST_HIGH = 0x01
    const val NRST_PULSE = 0x02

    /** Наибольший блок WRITEMEM_8BIT (ТЗ ST-Link 2.0, п. 3.2.6). */
    const val MAX_BYTE_BLOCK = 64

    private fun cmd(vararg bytes: Int): ByteArray {
        val out = ByteArray(StLinkProtocol.CMD_SIZE)
        bytes.forEachIndexed { i, b -> out[i] = b.toByte() }
        return out
    }

    /** Запись одного слова (DHCSR, DEMCR, AIRCR, регистры Flash); ответ 2 байта. */
    fun writeDebugReg(address: Long, value: Int): ByteArray {
        require(address and 3L == 0L && address in 0..0xFFFFFFFFL) { "Адрес должен быть выровнен на 4: ${address.hex32()}" }
        val out = cmd(StLinkProtocol.DEBUG_COMMAND, DEBUG_APIV2_WRITEDEBUGREG)
        Hex.putU32le(out, 2, address)
        Hex.putU32le(out, 6, value.toLong() and 0xFFFFFFFFL)
        return out
    }

    /** Заголовок записи блока; данные идут следующей bulk-передачей, ответа нет (п. 3.2.4, 3.2.6). */
    fun writeMem(width: Int, address: Long, length: Int): ByteArray {
        val (code, max) = when (width) {
            4 -> DEBUG_WRITEMEM_32BIT to StLinkProtocol.MAX_BLOCK_BYTES
            2 -> DEBUG_APIV2_WRITEMEM_16BIT to StLinkProtocol.MAX_BLOCK_BYTES
            1 -> DEBUG_WRITEMEM_8BIT to MAX_BYTE_BLOCK
            else -> throw IllegalArgumentException("ширина $width")
        }
        require(address % width == 0L && length % width == 0 && length in width..max) {
            "Блок записи: адрес ${address.hex32()}, длина $length, ширина $width"
        }
        require((address and 0x3FFL) + length <= 0x400L) { "Блок пересекает границу 1 КБ: ${address.hex32()} + $length" }
        val out = cmd(StLinkProtocol.DEBUG_COMMAND, code)
        Hex.putU32le(out, 2, address)
        Hex.putU16le(out, 6, length)
        return out
    }

    fun driveNrst(mode: Int): ByteArray = cmd(StLinkProtocol.DEBUG_COMMAND, DEBUG_APIV2_DRIVE_NRST, mode)
}

package io.github.viacheslavmezentsev.hwlivetest.stlink

/**
 * Кодирование команд ST-Link V2/V2-1/V3 (API v2) и разбор ответов.
 *
 * Пакет команды — 16 байт, ответ читается отдельной bulk-транзакцией фиксированной длины.
 * Коды команд соответствуют драйверу `stlink_usb.c` из OpenOCD.
 */
object StLinkProtocol {
    const val CMD_SIZE = 16

    // Верхний уровень
    const val GET_VERSION = 0xF1
    const val DEBUG_COMMAND = 0xF2
    const val DFU_COMMAND = 0xF3
    const val SWIM_COMMAND = 0xF4
    const val GET_CURRENT_MODE = 0xF5
    const val GET_TARGET_VOLTAGE = 0xF7
    const val GET_VERSION_APIV3 = 0xFB

    // Подкоманды
    const val DFU_EXIT = 0x07
    const val SWIM_EXIT = 0x01
    const val DEBUG_EXIT = 0x21
    const val DEBUG_APIV2_ENTER = 0x30
    const val DEBUG_APIV2_READ_IDCODES = 0x31
    const val DEBUG_APIV2_READDEBUGREG = 0x36
    const val DEBUG_APIV2_READ_DAP_REG = 0x45
    const val DEBUG_ENTER_SWD = 0xA3

    /** DP-регистры адресуются «портом» 0xFFFF в команде READ_DAP_REG. */
    const val DP_PORT = 0xFFFF

    // Длины ответов
    const val VERSION_LEN = 6
    const val VERSION_APIV3_LEN = 12
    const val MODE_LEN = 2
    const val VOLTAGE_LEN = 8
    const val STATUS_LEN = 2
    const val IDCODES_LEN = 12
    const val READ_REG_LEN = 8

    // Коды состояния (первый байт ответа)
    const val STATUS_OK = 0x80
    const val STATUS_FAULT = 0x81

    private fun cmd(vararg bytes: Int): ByteArray {
        val out = ByteArray(CMD_SIZE)
        bytes.forEachIndexed { i, b -> out[i] = b.toByte() }
        return out
    }

    fun getVersion(): ByteArray = cmd(GET_VERSION, 0x80)
    fun getVersionApiV3(): ByteArray = cmd(GET_VERSION_APIV3, 0x80)
    fun getCurrentMode(): ByteArray = cmd(GET_CURRENT_MODE)
    fun dfuExit(): ByteArray = cmd(DFU_COMMAND, DFU_EXIT)
    fun swimExit(): ByteArray = cmd(SWIM_COMMAND, SWIM_EXIT)
    fun debugExit(): ByteArray = cmd(DEBUG_COMMAND, DEBUG_EXIT)
    fun getTargetVoltage(): ByteArray = cmd(GET_TARGET_VOLTAGE)
    fun enterSwd(): ByteArray = cmd(DEBUG_COMMAND, DEBUG_APIV2_ENTER, DEBUG_ENTER_SWD)
    fun readIdCodes(): ByteArray = cmd(DEBUG_COMMAND, DEBUG_APIV2_READ_IDCODES)

    /** Чтение одного 32-битного слова через MEM-AP 0 (адрес выровнен на 4). */
    fun readDebugReg(address: Long): ByteArray {
        require(address and 3L == 0L) { "Адрес должен быть выровнен на 4: ${address.hex32()}" }
        require(address in 0..0xFFFFFFFFL) { "Адрес вне 32-битного диапазона" }
        val out = cmd(DEBUG_COMMAND, DEBUG_APIV2_READDEBUGREG)
        Hex.putU32le(out, 2, address)
        return out
    }

    /** Чтение регистра DP ([port] = [DP_PORT]) или AP ([port] = номер AP). */
    fun readDapReg(port: Int, register: Int): ByteArray {
        val out = cmd(DEBUG_COMMAND, DEBUG_APIV2_READ_DAP_REG)
        Hex.putU16le(out, 2, port)
        Hex.putU16le(out, 4, register)
        return out
    }

    // ---------- Разбор ответов ----------

    /** Ответ на [getVersion] (6 байт). Для V3 номера версий надо уточнить командой [getVersionApiV3]. */
    fun parseVersion(r: ByteArray): StLinkVersion {
        checkLen(r, VERSION_LEN, "GET_VERSION")
        val v = (Hex.u8(r, 0) shl 8) or Hex.u8(r, 1)
        val hw = (v ushr 12) and 0x0F
        val jtag = (v ushr 6) and 0x3F
        val low = v and 0x3F
        val vid = Hex.u16le(r, 2)
        val pid = Hex.u16le(r, 4)
        // У V2-1 младшее поле — версия накопителя (MSD), у V2 — версия SWIM.
        val isV21 = StLinkIds.find(vid, pid)?.generation == StLinkGeneration.V2_1
        return StLinkVersion(
            hw = hw, jtag = jtag,
            swim = if (isV21) null else low,
            msd = if (isV21) low else null,
            bridge = null, vid = vid, pid = pid,
        )
    }

    /** Ответ на [getVersionApiV3] (12 байт). */
    fun parseVersionApiV3(r: ByteArray): StLinkVersion {
        checkLen(r, VERSION_APIV3_LEN, "GET_VERSION_APIV3")
        return StLinkVersion(
            hw = Hex.u8(r, 0),
            swim = Hex.u8(r, 1),
            jtag = Hex.u8(r, 2),
            msd = Hex.u8(r, 3),
            bridge = Hex.u8(r, 4),
            vid = Hex.u16le(r, 8),
            pid = Hex.u16le(r, 10),
        )
    }

    fun parseMode(r: ByteArray): StLinkMode {
        checkLen(r, MODE_LEN, "GET_CURRENT_MODE")
        return StLinkMode.fromCode(Hex.u8(r, 0))
    }

    /** Напряжение цели в вольтах; null, если адаптер не измеряет (опорный канал равен 0). */
    fun parseVoltage(r: ByteArray): Double? {
        checkLen(r, VOLTAGE_LEN, "GET_TARGET_VOLTAGE")
        val adc0 = Hex.u32le(r, 0).u32()
        val adc1 = Hex.u32le(r, 4).u32()
        if (adc0 == 0L) return null
        return 2.0 * adc1 * 1.2 / adc0
    }

    fun status(r: ByteArray): Int {
        if (r.isEmpty()) throw StLinkException(StLinkErrorKind.PROTOCOL, "Пустой ответ адаптера")
        return Hex.u8(r, 0)
    }

    /** Ответ READ_IDCODES: состояние в байте 0, IDCODE — слово по смещению 4. */
    fun parseIdCode(r: ByteArray): Int {
        checkLen(r, IDCODES_LEN, "READ_IDCODES")
        return Hex.u32le(r, 4)
    }

    /** Ответ READDEBUGREG/READ_DAP_REG: состояние в байте 0, значение — по смещению 4. */
    fun parseRegValue(r: ByteArray): Int {
        checkLen(r, READ_REG_LEN, "READ_REG")
        return Hex.u32le(r, 4)
    }

    fun statusText(code: Int): String = when (code) {
        STATUS_OK -> "OK"
        STATUS_FAULT -> "ошибка отладки (FAULT)"
        0x09 -> "не удалось прочитать IDCODE (цель не отвечает)"
        0x0C -> "ошибка записи JTAG"
        0x0D -> "ошибка проверки записи JTAG"
        0x10 -> "AP WAIT"
        0x11 -> "AP FAULT"
        0x12 -> "AP ERROR"
        0x13 -> "AP: ошибка чётности"
        0x14 -> "DP WAIT"
        0x15 -> "DP FAULT"
        0x16 -> "DP ERROR"
        0x17 -> "DP: ошибка чётности"
        0x18 -> "AP: ошибка данных записи"
        0x19 -> "AP: залипший флаг ошибки (STICKYERR)"
        0x1A -> "AP: переполнение (STICKYORUN)"
        0x1D -> "неверный номер AP"
        else -> "неизвестный код 0x%02X".format(code)
    }

    /** Коды, означающие, что по SWD никто не ответил на уровне DP. */
    fun isNoTargetStatus(code: Int): Boolean = code == 0x09 || code in 0x14..0x17

    private fun checkLen(r: ByteArray, expected: Int, what: String) {
        if (r.size < expected) {
            throw StLinkException(
                StLinkErrorKind.PROTOCOL,
                "$what: ожидалось $expected байт, получено ${r.size}",
            )
        }
    }
}

enum class StLinkMode(val code: Int, val title: String) {
    DFU(0x00, "DFU"),
    MASS(0x01, "накопитель"),
    DEBUG(0x02, "отладка"),
    SWIM(0x03, "SWIM"),
    BOOTLOADER(0x04, "загрузчик"),
    UNKNOWN(-1, "неизвестный");

    companion object {
        fun fromCode(code: Int): StLinkMode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class StLinkVersion(
    /** Поколение: 2 или 3. */
    val hw: Int,
    /** Версия JTAG/SWD-части прошивки (буква J). */
    val jtag: Int,
    val swim: Int?,
    val msd: Int?,
    val bridge: Int?,
    val vid: Int,
    val pid: Int,
) {
    /** Строка в стиле ST: V2J37S7, V2J37M26, V3J7M3B5S1. */
    val label: String
        get() = buildString {
            append("V").append(hw).append("J").append(jtag)
            if (hw >= 3) {
                msd?.let { append("M").append(it) }
                bridge?.let { append("B").append(it) }
                swim?.let { append("S").append(it) }
            } else {
                msd?.let { append("M").append(it) }
                swim?.let { append("S").append(it) }
            }
        }

    val vidPid: String get() = "%04X:%04X".format(vid, pid)

    /** Требуется для чтения напряжения и API v2 на V2. */
    val meetsMinimum: Boolean get() = hw >= 3 || jtag >= MIN_V2_JTAG

    /** Чтение регистров DP/AP командой READ_DAP_REG: V2 с J24, любой V3. */
    val supportsDapRegisterRead: Boolean get() = hw >= 3 || jtag >= 24

    companion object {
        const val MIN_V2_JTAG = 13
    }
}

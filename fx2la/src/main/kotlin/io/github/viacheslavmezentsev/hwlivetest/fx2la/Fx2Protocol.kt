package io.github.viacheslavmezentsev.hwlivetest.fx2la

/**
 * Протокол прошивки fx2lafw и загрузчика FX2 (ТЗ ЛА 3.2, 3.3). Константы сверены с
 * общедоступными исходниками libsigrok и sigrok-firmware-fx2lafw; код оттуда не переносился.
 */
object Fx2Protocol {
    // Загрузчик FX2 в ПЗУ
    const val REQ_FIRMWARE_LOAD = 0xA0
    const val CPUCS = 0xE600
    const val LOAD_TIMEOUT_MS = 100

    // fx2lafw
    const val CMD_GET_FW_VERSION = 0xB0
    const val CMD_START = 0xB1
    const val CMD_GET_REVID_VERSION = 0xB2
    const val FLAG_SAMPLE_16BIT = 1 shl 5
    const val FLAG_CLK_48MHZ = 1 shl 6
    const val MAX_DELAY = 6 * 256
    const val REQUIRED_MAJOR = 1
    const val CONTROL_TIMEOUT_MS = 100

    /** Точка данных: bulk IN EP2. */
    const val DATA_ENDPOINT = 0x82

    /** Пакет bulk на высокой скорости USB; на полной — 64. */
    const val HS_PACKET = 512

    /** Предел частоты на полной скорости USB (ТЗ ЛА 3.4.2). */
    const val FS_MAX_RATE = 500_000L

    /** Частоты 8-битного режима (ТЗ ЛА 3.4.3, приложение C); 48 МГц не предлагается. */
    val SAMPLE_RATES: List<Long> = listOf(
        20_000L, 25_000L, 50_000L, 100_000L, 200_000L, 250_000L, 500_000L,
        1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 6_000_000L, 8_000_000L,
        12_000_000L, 16_000_000L, 24_000_000L,
    )

    data class Start(val flags: Int, val delay: Int) {
        val clockMhz: Int get() = if (flags and FLAG_CLK_48MHZ != 0) 48 else 30

        /** Данные запроса START: флаги, делитель старшим и младшим байтом. */
        fun bytes(): ByteArray = byteArrayOf(flags.toByte(), (delay ushr 8).toByte(), delay.toByte())
    }

    /** Параметры START для частоты; null — частота недостижима (ТЗ ЛА 3.3.4). */
    fun start(rate: Long): Start? {
        if (rate <= 0) return null
        for ((clock, flags) in listOf(48_000_000L to FLAG_CLK_48MHZ, 30_000_000L to 0)) {
            if (clock % rate != 0L) continue
            val delay = clock / rate - 1
            if (delay in 1..MAX_DELAY.toLong()) return Start(flags, delay.toInt())
        }
        return null
    }

    data class Version(val major: Int, val minor: Int) {
        override fun toString() = "$major.$minor"
        val supported: Boolean get() = major == REQUIRED_MAJOR
    }

    fun parseVersion(r: ByteArray): Version? = if (r.size < 2) null else Version(r[0].toInt() and 0xFF, r[1].toInt() and 0xFF)

    /** Высокая скорость USB по размеру пакета bulk-точки (ТЗ ЛА 3.4.1). */
    fun isHighSpeed(packetSize: Int): Boolean = packetSize >= HS_PACKET

    fun allowedRates(highSpeed: Boolean): List<Long> =
        if (highSpeed) SAMPLE_RATES else SAMPLE_RATES.filter { it <= FS_MAX_RATE }

    /**
     * Размер буфера одной передачи: данные за 10 мс, кратно 512 байт, в пределах
     * [maxBuffer] (ТЗ ЛА 4.1.2, р.1.1).
     */
    fun bufferSize(rate: Long, maxBuffer: Int): Int {
        val bytes = rate / 100
        val rounded = ((bytes + HS_PACKET - 1) / HS_PACKET * HS_PACKET).toInt()
        return rounded.coerceIn(HS_PACKET, maxBuffer / HS_PACKET * HS_PACKET)
    }

    /** Число передач в очереди: не меньше 500 мс потока, но не больше [MAX_TRANSFERS] (ТЗ ЛА 4.1.1, 4.1.3). */
    fun transferCount(rate: Long, bufferSize: Int): Int {
        val n = ((rate / 2 + bufferSize - 1) / bufferSize).toInt()
        return n.coerceIn(MIN_TRANSFERS, MAX_TRANSFERS)
    }

    const val MIN_TRANSFERS = 4
    const val MAX_TRANSFERS = 32
}

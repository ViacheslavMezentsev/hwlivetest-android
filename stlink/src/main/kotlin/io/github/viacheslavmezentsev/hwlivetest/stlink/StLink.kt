package io.github.viacheslavmezentsev.hwlivetest.stlink

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory
import java.io.IOException

/** Что известно после подключения к адаптеру и входа в SWD. */
data class StLinkConnection(
    val version: StLinkVersion,
    val initialMode: StLinkMode,
    /** Напряжение цели, В; null — адаптер не измеряет. */
    val targetVoltage: Double?,
    val dpIdCode: Int,
    val warnings: List<String>,
)

/**
 * Сеанс работы с ST-Link поверх [StLinkTransport]. Только чтение: ядро не останавливается,
 * в память ничего не пишется.
 *
 * [log] получает строки обмена в формате журнала захвата: `> f1 80` и `< 29 47 83 04 48 37`.
 */
class StLink(
    private val transport: StLinkTransport,
    private val log: (String) -> Unit = {},
    /** Сколько раз повторять чтение при ответе WAIT (цель занята или спит); 0 — не повторять. */
    private val waitRetries: Int = DEFAULT_WAIT_RETRIES,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : DebugMemory {

    var version: StLinkVersion? = null
        private set

    private var inSwd = false

    /** Замер обмена: сколько времени уходит на USB и на журнал (для отчёта о скорости). */
    val stats = ExchangeStats()

    private fun logTimed(line: String) {
        val t = System.nanoTime()
        log(line)
        stats.log(System.nanoTime() - t)
    }

    private fun usbWrite(data: ByteArray) {
        val t = System.nanoTime()
        transport.write(data)
        stats.write(System.nanoTime() - t, data.size)
    }

    private fun usbRead(length: Int): ByteArray {
        val t = System.nanoTime()
        val r = transport.read(length)
        stats.read(System.nanoTime() - t, r.size)
        return r
    }

    fun exchange(command: ByteArray, responseLength: Int): ByteArray {
        logTimed("> " + Hex.bytes(command, trimmedLength(command)))
        try {
            usbWrite(command)
            if (responseLength == 0) return ByteArray(0)
            val r = usbRead(responseLength)
            // Длинные ответы (блоки памяти) в журнале сокращаются: они не нужны для записей.
            logTimed(if (r.size <= LOG_MAX_BYTES) "< " + Hex.bytes(r) else "# < ${r.size} байт: " + Hex.bytes(r, LOG_MAX_BYTES) + " …")
            if (r.size < responseLength) {
                throw StLinkException(
                    StLinkErrorKind.PROTOCOL,
                    "Короткий ответ: ${r.size} из $responseLength байт",
                )
            }
            return r
        } catch (e: StLinkException) {
            throw e
        } catch (e: IOException) {
            throw StLinkException(StLinkErrorKind.USB_IO, "Ошибка USB: ${e.message}", cause = e)
        }
    }

    /**
     * Команда записи блока: заголовок, затем данные отдельной bulk-передачей; ответа нет
     * (ТЗ ST-Link 2.0, п. 3.2.4). Только для [StLinkTargetWrite].
     */
    internal fun sendWithData(command: ByteArray, data: ByteArray) {
        logTimed("> " + Hex.bytes(command, trimmedLength(command)))
        logTimed("# > ${data.size} байт: " + Hex.bytes(data, minOf(data.size, LOG_MAX_BYTES)) + if (data.size > LOG_MAX_BYTES) " …" else "")
        try {
            usbWrite(command)
            usbWrite(data)
        } catch (e: IOException) {
            throw StLinkException(StLinkErrorKind.USB_IO, "Ошибка USB: ${e.message}", cause = e)
        }
    }

    /** Состояние последней записи или чтения блока (п. 3.4.6). */
    internal fun lastRwStatus(): Int {
        val extended = version?.hasLastRwStatus2 ?: true
        return StLinkProtocol.status(exchange(StLinkProtocol.getLastRwStatus(extended), if (extended) 12 else 2))
    }

    internal val logLine: (String) -> Unit get() = log

    /**
     * Доступ к изменению цели (ТЗ ST-Link 2.0, п. 5.11.1, 6.2.3): только с разрешением, которое
     * приложение выдаёт после включения режима «Изменение цели».
     */
    fun targetWrite(permit: WritePermit): TargetWrite = StLinkTargetWrite(this, permit, sleep)

    fun readVersion(): StLinkVersion {
        var v = StLinkProtocol.parseVersion(exchange(StLinkProtocol.getVersion(), StLinkProtocol.VERSION_LEN))
        if (v.hw == 1) {
            throw StLinkException(StLinkErrorKind.UNSUPPORTED_ADAPTER, "ST-Link/V1 не поддерживается")
        }
        if (v.hw >= 3) {
            v = StLinkProtocol.parseVersionApiV3(
                exchange(StLinkProtocol.getVersionApiV3(), StLinkProtocol.VERSION_APIV3_LEN),
            )
        }
        version = v
        return v
    }

    fun currentMode(): StLinkMode =
        StLinkProtocol.parseMode(exchange(StLinkProtocol.getCurrentMode(), StLinkProtocol.MODE_LEN))

    /** Выйти из DFU/SWIM/отладки, чтобы начать с чистого состояния (как OpenOCD). */
    fun leaveMode(mode: StLinkMode) {
        when (mode) {
            StLinkMode.DFU -> exchange(StLinkProtocol.dfuExit(), 0)
            StLinkMode.SWIM -> exchange(StLinkProtocol.swimExit(), 0)
            StLinkMode.DEBUG -> exchange(StLinkProtocol.debugExit(), 0)
            else -> Unit
        }
        inSwd = false
    }

    fun targetVoltage(): Double? =
        StLinkProtocol.parseVoltage(exchange(StLinkProtocol.getTargetVoltage(), StLinkProtocol.VOLTAGE_LEN))

    fun enterSwd() {
        val r = exchange(StLinkProtocol.enterSwd(), StLinkProtocol.STATUS_LEN)
        val s = StLinkProtocol.status(r)
        if (s != StLinkProtocol.STATUS_OK) throw connectError(s)
        inSwd = true
    }

    fun readDpIdCode(): Int {
        val r = exchange(StLinkProtocol.readIdCodes(), StLinkProtocol.IDCODES_LEN)
        val s = StLinkProtocol.status(r)
        if (s != StLinkProtocol.STATUS_OK) throw connectError(s)
        val id = StLinkProtocol.parseIdCode(r)
        if (id == 0 || id == -1) {
            throw StLinkException(StLinkErrorKind.NO_TARGET, "IDCODE ${id.hex32()}: цель не отвечает по SWD")
        }
        return id
    }

    override fun read32(address: Long): Int {
        if (address and 3L != 0L) {
            throw StLinkException(StLinkErrorKind.BAD_ARGUMENT, "Адрес должен быть выровнен на 4: ${address.hex32()}")
        }
        return readWithWaitRetry(StLinkProtocol.readDebugReg(address), "Чтение ${address.hex32()}")
    }

    /**
     * Чтение [words] слов подряд командой READMEM_32BIT, блоками до 1 КБ, не пересекая
     * границ 1 КБ. После каждого блока проверяется состояние (GETLASTRWSTATUS);
     * при WAIT блок читается заново.
     */
    override fun readBlock(address: Long, words: Int): IntArray {
        if (address and 3L != 0L) {
            throw StLinkException(StLinkErrorKind.BAD_ARGUMENT, "Адрес должен быть выровнен на 4: ${address.hex32()}")
        }
        val out = IntArray(words)
        var done = 0
        while (done < words) {
            val a = address + 4L * done
            val room = ((0x400L - (a and 0x3FFL)) / 4).toInt()
            val n = minOf(words - done, room, StLinkProtocol.MAX_BLOCK_BYTES / 4)
            val data = readBlockOnce(a, n)
            for (k in 0 until n) out[done + k] = Hex.u32le(data, 4 * k)
            done += n
        }
        return out
    }

    private fun readBlockOnce(address: Long, words: Int): ByteArray {
        val extended = version?.hasLastRwStatus2 ?: true
        val statusLen = if (extended) 12 else 2
        var attempt = 0
        while (true) {
            val data = exchange(StLinkProtocol.readMem32(address, 4 * words), 4 * words)
            val s = StLinkProtocol.status(exchange(StLinkProtocol.getLastRwStatus(extended), statusLen))
            if (s == StLinkProtocol.STATUS_OK) return data
            if (StLinkProtocol.isWaitStatus(s) && attempt < waitRetries) {
                val delayMs = 1L shl minOf(attempt, 4)
                attempt++
                log("# Блок ${address.hex32()}: ${StLinkProtocol.statusText(s)}, повтор $attempt через $delayMs мс")
                sleep(delayMs)
                continue
            }
            throw StLinkException(
                StLinkErrorKind.SWD_FAULT,
                "Блок ${address.hex32()}+${4 * words}: ${StLinkProtocol.statusText(s)}",
                status = s,
            )
        }
    }

    /** Регистр AP; null, если прошивка не умеет READ_DAP_REG. */
    override fun readApRegister(ap: Int, register: Int): Int? {
        if (version?.supportsDapRegisterRead != true) return null
        return readWithWaitRetry(StLinkProtocol.readDapReg(ap, register), "AP$ap[0x%02X]".format(register))
    }

    /**
     * Чтение регистра с повтором при WAIT. Цель отвечает WAIT, когда шина занята или ядро
     * в режиме сна, а отладочные часы в DBGMCU_CR не включены (записывать туда мы не можем).
     * Задержка удваивается: 1, 2, 4, 8, 16 мс.
     */
    private fun readWithWaitRetry(command: ByteArray, what: String): Int {
        var attempt = 0
        while (true) {
            val r = exchange(command, StLinkProtocol.READ_REG_LEN)
            val s = StLinkProtocol.status(r)
            if (s == StLinkProtocol.STATUS_OK) return StLinkProtocol.parseRegValue(r)
            if (StLinkProtocol.isWaitStatus(s) && attempt < waitRetries) {
                val delayMs = 1L shl minOf(attempt, 4)
                attempt++
                log("# $what: ${StLinkProtocol.statusText(s)}, повтор $attempt через $delayMs мс")
                sleep(delayMs)
                continue
            }
            val suffix = if (StLinkProtocol.isWaitStatus(s) && attempt > 0) " (после $attempt повторов)" else ""
            throw StLinkException(
                StLinkErrorKind.SWD_FAULT,
                "$what: ${StLinkProtocol.statusText(s)}$suffix",
                status = s,
            )
        }
    }

    /** Полная последовательность подключения: версия, режим, напряжение, SWD, IDCODE. */
    fun connect(): StLinkConnection {
        val warnings = mutableListOf<String>()
        val v = readVersion()
        if (!v.meetsMinimum) {
            throw StLinkException(
                StLinkErrorKind.OLD_FIRMWARE,
                "Прошивка адаптера ${v.label} слишком старая (нужна J${StLinkVersion.MIN_V2_JTAG} или новее). " +
                    "Обновите её программой STM32CubeProgrammer или ST-LinkUpgrade.",
            )
        }
        if (!v.supportsDapRegisterRead) {
            warnings += "Прошивка ${v.label} старше J24: адрес ROM-таблицы берётся по умолчанию."
        }
        val mode = currentMode()
        leaveMode(mode)
        val voltage = targetVoltage()
        when {
            voltage == null -> warnings += "Адаптер не измеряет напряжение цели."
            voltage < 1.5 -> warnings += "Напряжение цели %.2f В: плата не запитана или не подключён вывод VREF/3V3.".format(voltage)
        }
        enterSwd()
        val id = readDpIdCode()
        return StLinkConnection(v, mode, voltage, id, warnings)
    }

    /** Выйти из режима отладки адаптера. Ядро продолжает работать. */
    fun disconnect() {
        if (inSwd) {
            try {
                exchange(StLinkProtocol.debugExit(), 0)
            } catch (_: StLinkException) {
            }
            inSwd = false
        }
    }

    companion object {
        const val DEFAULT_WAIT_RETRIES = 5
        private const val LOG_MAX_BYTES = 16
    }

    private fun connectError(status: Int): StLinkException =
        if (StLinkProtocol.isNoTargetStatus(status)) {
            StLinkException(
                StLinkErrorKind.NO_TARGET,
                "Цель не отвечает по SWD (${StLinkProtocol.statusText(status)}). " +
                    "Проверьте питание платы и провода SWDIO, SWCLK, GND.",
                status = status,
            )
        } else {
            StLinkException(
                StLinkErrorKind.SWD_FAULT,
                "SWD недоступен: ${StLinkProtocol.statusText(status)}",
                status = status,
            )
        }

    private fun trimmedLength(command: ByteArray): Int {
        var n = command.size
        while (n > 1 && command[n - 1].toInt() == 0) n--
        return n
    }
}

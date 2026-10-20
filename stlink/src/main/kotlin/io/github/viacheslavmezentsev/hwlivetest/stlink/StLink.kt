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

    fun exchange(command: ByteArray, responseLength: Int): ByteArray {
        log("> " + Hex.bytes(command, trimmedLength(command)))
        try {
            transport.write(command)
            if (responseLength == 0) return ByteArray(0)
            val r = transport.read(responseLength)
            log("< " + Hex.bytes(r))
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

package io.github.viacheslavmezentsev.hwlivetest.stlink

/**
 * Разрешение на изменение цели (ТЗ ST-Link 2.0, п. 5.11.1). Приложение создаёт его, когда
 * пользователь включает режим «Изменение цели», и отзывает при выключении режима: после
 * [revoke] любая команда записи отклоняется без обмена с адаптером.
 */
class WritePermit private constructor(val reason: String) {
    @Volatile
    var active: Boolean = true
        private set

    fun revoke() {
        active = false
    }

    companion object {
        fun grant(reason: String): WritePermit = WritePermit(reason)
    }
}

/** Состояние ядра по DHCSR (ТЗ ST-Link 2.0, п. 5.11.7). */
data class CoreState(val dhcsr: Int) {
    val halted: Boolean get() = dhcsr and S_HALT != 0
    val sleeping: Boolean get() = dhcsr and S_SLEEP != 0
    val lockup: Boolean get() = dhcsr and S_LOCKUP != 0
    val wasReset: Boolean get() = dhcsr and S_RESET_ST != 0
    val debugEnabled: Boolean get() = dhcsr and C_DEBUGEN != 0

    val title: String
        get() = when {
            lockup -> "lockup (зависание после сбоя)"
            halted -> "остановлено"
            sleeping -> "сон (WFI/WFE)"
            else -> "работает"
        } + if (wasReset) ", был сброс" else ""

    companion object {
        const val C_DEBUGEN = 1 shl 0
        const val S_HALT = 1 shl 17
        const val S_SLEEP = 1 shl 18
        const val S_LOCKUP = 1 shl 19
        const val S_RESET_ST = 1 shl 25
    }
}

/**
 * Изменение цели: запись памяти и регистров, управление ядром (ТЗ ST-Link 2.0, п. 6.2.3).
 * Запись не повторяется при WAIT: повтор записи во Flash или в ключевой регистр опасен
 * (ТЗ прошивки, п. 5.5.4).
 */
interface TargetWrite {
    /** Experimental RTT teardown only; implementations must reject unsupported operations. */
    fun readProgramCounter(): Long = throw UnsupportedOperationException("Чтение PC не поддерживается")
    fun step(): CoreState = throw UnsupportedOperationException("Шаг ядра не поддерживается")
    fun write32(address: Long, value: Int)
    fun writeBlock32(address: Long, words: IntArray)
    fun writeBlock16(address: Long, halves: ShortArray)
    fun writeBytes(address: Long, bytes: ByteArray)
    fun coreState(): CoreState
    fun halt(): CoreState
    fun go(): CoreState
    fun reset(): CoreState
    fun resetHalt(): CoreState
    fun pulseNrst()

    /** Запустить ядро, если оно остановлено, и снять C_DEBUGEN (п. 5.11.8). */
    fun detach(): CoreState
}

object CortexDebugRegs {
    const val DHCSR = 0xE000EDF0L
    const val DEMCR = 0xE000EDFCL
    const val AIRCR = 0xE000ED0CL

    const val DHCSR_HALT = 0xA05F0003.toInt()
    const val DHCSR_GO = 0xA05F0001.toInt()
    const val DHCSR_DETACH = 0xA05F0000.toInt()
    const val AIRCR_SYSRESETREQ = 0x05FA0004
    const val DEMCR_VC_CORERESET = 1

    const val HALT_TIMEOUT_MS = 500L
    const val POLL_MS = 10L
    const val RESET_SETTLE_MS = 50L
}

internal class StLinkTargetWrite(
    private val st: StLink,
    private val permit: WritePermit,
    private val sleep: (Long) -> Unit,
) : TargetWrite {

    // ТЗ RTT 2.5: DCRSR transfer changes debug state, therefore requires WritePermit.
    override fun readProgramCounter(): Long {
        check()
        require(coreState().halted) { "Для чтения PC ядро должно быть остановлено" }
        write32(0xE000EDF4, 15)
        waitFor("чтения PC") { it.halted && it.dhcsr and (1 shl 16) != 0 }
        return st.read32(0xE000EDF8).toLong() and 0xFFFFFFFFL
    }

    // ТЗ RTT 2.5: mask interrupts while halted, step, wait for halt, restore masking.
    override fun step(): CoreState {
        check()
        val before = coreState()
        require(before.halted && !before.lockup) { "Шаг допустим только при остановленном ядре без lockup" }
        val mask = before.dhcsr and 8
        try {
            write32(CortexDebugRegs.DHCSR, 0xA05F000B.toInt())
            write32(CortexDebugRegs.DHCSR, 0xA05F000D.toInt())
            val after = waitFor("остановки после шага") { it.halted }
            check(!after.lockup) { "Lockup после шага" }
        } finally {
            // On timeout, explicitly halt; never leave C_STEP set or resume implicitly.
            write32(CortexDebugRegs.DHCSR, 0xA05F0003.toInt() or mask)
        }
        return waitFor("остановки после шага") { it.halted }
    }

    private fun check() {
        if (!permit.active) {
            throw StLinkException(StLinkErrorKind.NOT_PERMITTED, "Режим «Изменение цели» выключен: команда записи отклонена.")
        }
    }

    // ТЗ ST-Link 2.0, п. 3.2.4: WRITEDEBUGREG, ответ — 2 байта состояния; без повторов при WAIT.
    override fun write32(address: Long, value: Int) {
        check()
        if (address and 3L != 0L) throw StLinkException(StLinkErrorKind.BAD_ARGUMENT, "Адрес должен быть выровнен на 4: ${address.hex32()}")
        val r = st.exchange(StLinkWriteProtocol.writeDebugReg(address, value), StLinkProtocol.STATUS_LEN)
        val s = StLinkProtocol.status(r)
        if (s != StLinkProtocol.STATUS_OK) {
            throw StLinkException(StLinkErrorKind.SWD_FAULT, "Запись ${address.hex32()}: ${StLinkProtocol.statusText(s)}", status = s)
        }
    }

    override fun writeBlock32(address: Long, words: IntArray) {
        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { i, w -> Hex.putU32le(bytes, 4 * i, w.toLong() and 0xFFFFFFFFL) }
        writeChunks(address, bytes, 4, StLinkProtocol.MAX_BLOCK_BYTES)
    }

    override fun writeBlock16(address: Long, halves: ShortArray) {
        val v = st.version
        if (v != null && v.hw < 3 && v.jtag < MIN_JTAG_WRITE16) {
            throw StLinkException(
                StLinkErrorKind.OLD_FIRMWARE,
                "Запись полусловами нужна прошивка адаптера J$MIN_JTAG_WRITE16 или новее (сейчас ${v.label}).",
            )
        }
        val bytes = ByteArray(halves.size * 2)
        halves.forEachIndexed { i, h -> Hex.putU16le(bytes, 2 * i, h.toInt() and 0xFFFF) }
        writeChunks(address, bytes, 2, StLinkProtocol.MAX_BLOCK_BYTES)
    }

    override fun writeBytes(address: Long, bytes: ByteArray) =
        writeChunks(address, bytes, 1, StLinkWriteProtocol.MAX_BYTE_BLOCK)

    /** ТЗ ST-Link 2.0, п. 3.2.5, 3.2.6: блоки не больше [max], без пересечения 1 КБ, состояние после каждого. */
    private fun writeChunks(address: Long, data: ByteArray, width: Int, max: Int) {
        check()
        if (address % width != 0L || data.size % width != 0) {
            throw StLinkException(StLinkErrorKind.BAD_ARGUMENT, "Блок записи: адрес ${address.hex32()}, длина ${data.size}, ширина $width")
        }
        var done = 0
        while (done < data.size) {
            val a = address + done
            val room = (0x400L - (a and 0x3FFL)).toInt()
            val n = minOf(data.size - done, room, max)
            check()
            st.sendWithData(StLinkWriteProtocol.writeMem(width, a, n), data.copyOfRange(done, done + n))
            val s = st.lastRwStatus()
            if (s != StLinkProtocol.STATUS_OK) {
                throw StLinkException(StLinkErrorKind.SWD_FAULT, "Запись блока ${a.hex32()}+$n: ${StLinkProtocol.statusText(s)}", status = s)
            }
            done += n
        }
    }

    override fun coreState(): CoreState = CoreState(st.read32(CortexDebugRegs.DHCSR))

    // ТЗ ST-Link 2.0, п. 5.11.2
    override fun halt(): CoreState {
        write32(CortexDebugRegs.DHCSR, CortexDebugRegs.DHCSR_HALT)
        return waitFor("остановки ядра") { it.halted }
    }

    // ТЗ ST-Link 2.0, п. 5.11.3
    override fun go(): CoreState {
        write32(CortexDebugRegs.DHCSR, CortexDebugRegs.DHCSR_GO)
        return waitFor("запуска ядра") { !it.halted }
    }

    // ТЗ ST-Link 2.0, п. 5.11.4: сброс может разорвать сеанс SWD — IDCODE читается заново.
    override fun reset(): CoreState {
        write32(CortexDebugRegs.AIRCR, CortexDebugRegs.AIRCR_SYSRESETREQ)
        sleep(CortexDebugRegs.RESET_SETTLE_MS)
        try {
            st.readDpIdCode()
        } catch (e: StLinkException) {
            if (e.kind == StLinkErrorKind.USB_IO) throw e
            st.logLine("# после сброса SWD переподключается: ${e.message}")
            st.enterSwd()
            st.readDpIdCode()
        }
        return coreState()
    }

    // ТЗ ST-Link 2.0, п. 5.11.5, 5.11.6
    override fun resetHalt(): CoreState {
        write32(CortexDebugRegs.DHCSR, CortexDebugRegs.DHCSR_GO)
        val demcr = st.read32(CortexDebugRegs.DEMCR)
        write32(CortexDebugRegs.DEMCR, demcr or CortexDebugRegs.DEMCR_VC_CORERESET)
        try {
            reset()
            return waitFor("остановки после сброса (NRST адаптера может помочь, если подключён)") { it.halted }
        } finally {
            write32(CortexDebugRegs.DEMCR, demcr)
        }
    }

    override fun pulseNrst() {
        check()
        val r = st.exchange(StLinkWriteProtocol.driveNrst(StLinkWriteProtocol.NRST_PULSE), StLinkProtocol.STATUS_LEN)
        val s = StLinkProtocol.status(r)
        if (s != StLinkProtocol.STATUS_OK) {
            throw StLinkException(StLinkErrorKind.SWD_FAULT, "Импульс NRST: ${StLinkProtocol.statusText(s)}", status = s)
        }
    }

    // ТЗ ST-Link 2.0, п. 5.11.8
    override fun detach(): CoreState {
        if (coreState().halted) go()
        write32(CortexDebugRegs.DHCSR, CortexDebugRegs.DHCSR_DETACH)
        return coreState()
    }

    private fun waitFor(what: String, done: (CoreState) -> Boolean): CoreState {
        var waited = 0L
        while (true) {
            val state = coreState()
            if (done(state)) return state
            if (waited >= CortexDebugRegs.HALT_TIMEOUT_MS) {
                throw StLinkException(
                    StLinkErrorKind.SWD_FAULT,
                    "Нет $what за ${CortexDebugRegs.HALT_TIMEOUT_MS} мс (DHCSR ${state.dhcsr.hex32()}: ${state.title}).",
                )
            }
            sleep(CortexDebugRegs.POLL_MS)
            waited += CortexDebugRegs.POLL_MS
        }
    }

    companion object {
        const val MIN_JTAG_WRITE16 = 26
    }
}

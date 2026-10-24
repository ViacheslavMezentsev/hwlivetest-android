package io.github.viacheslavmezentsev.hwlivetest.firmware

import io.github.viacheslavmezentsev.hwlivetest.stlink.CoreState
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory

/**
 * Модель цели для тестов прошивки (ТЗ прошивки 6.1.3): Flash, регистры контроллера F4 или F1
 * с BSY и флагами ошибок, ключи, option bytes (только чтение), DBGMCU, остановка ядра.
 */
class FlashSim(val layout: FlashLayout) : DebugMemory, TargetWrite {
    val f4 = layout.family == FlashFamily.F4
    private val base = if (f4) 0x40023C00L else 0x40022000L
    val flash = ByteArray(layout.size) { 0xFF.toByte() }
    val words = HashMap<Long, Int>()

    var now = 0L
    val clock = FlashClock(now = { now }, sleep = { now += it })

    var locked = true
    private var keyStage = 0
    var cr = 0
    var sr = 0
    var ar = 0
    private var busyUntil = 0L

    var halted = false
    var resetHalts = 0
    var runs = 0

    /** Регистровые записи по порядку (адрес, значение) — для проверки последовательностей. */
    val regWrites = mutableListOf<Pair<Long, Int>>()
    val erasedUnits = mutableListOf<Int>()
    var massErases = 0
    var blockWrites = 0

    /** Значение DBGMCU в момент каждого запуска стирания. */
    val dbgAtStart = mutableListOf<Int>()

    // Неисправности
    var stuckBusy = false
    var errorAtBlock: Int? = null
    var waitAtBlock: Int? = null
    var corruptAt: Long? = null

    val dbgmcu: Long = if (f4) 0xE0042008L else 0xE0042004L
    val forbidden: Set<Long> = if (f4) {
        setOf(0x40023C08L, 0x40023C14L, 0x40023C18L)
    } else {
        setOf(0x40022008L, 0x4002201CL, 0x40022020L)
    }
    val forbiddenWrites = mutableListOf<Long>()

    init {
        if (f4) {
            words[0x40023C14L] = 0x0FFFAAED
            words[0x40023C18L] = 0x0FFF0000
        } else {
            words[0x4002201CL] = 0x03FFFFFC
            words[0x40022020L] = -1
        }
        words[dbgmcu] = 0x00000001
    }

    private val bsy get() = if (f4) 1 shl 16 else 1
    private val lockBit get() = if (f4) 1 shl 31 else 1 shl 7
    private val pgErrFlag get() = if (f4) 1 shl 7 else 1 shl 2 // PGSERR / PGERR

    fun inFlash(a: Long) = a >= layout.start && a < layout.end

    // DebugMemory
    override fun read32(address: Long): Int = when {
        inFlash(address) -> {
            val o = (address - layout.start).toInt()
            (0 until 4).fold(0) { acc, i -> acc or ((flash[o + i].toInt() and 0xFF) shl (8 * i)) }
        }
        address == base + 0x0C -> sr or (if (stuckBusy || now < busyUntil) bsy else 0)
        address == base + 0x10 -> cr or (if (locked) lockBit else 0)
        else -> words[address] ?: 0
    }

    // TargetWrite
    override fun write32(address: Long, value: Int) {
        if (address in forbidden) forbiddenWrites += address
        regWrites += address to value
        when (address) {
            base + 0x04 -> keyStage = when {
                keyStage == 0 && value == FlashController.KEY1 -> 1
                keyStage == 1 && value == FlashController.KEY2 -> {
                    locked = false
                    0
                }
                else -> 0
            }
            base + 0x0C -> sr = sr and value.inv()
            base + 0x10 -> writeCr(value)
            base + 0x14 -> ar = value
            else -> words[address] = value
        }
    }

    private fun writeCr(value: Int) {
        if (value and lockBit != 0) {
            locked = true
            cr = 0
            return
        }
        if (locked) return
        cr = value
        val strt = if (f4) 1 shl 16 else 1 shl 6
        if (value and strt == 0) return
        dbgAtStart += words[dbgmcu] ?: 0
        if (value and (1 shl 2) != 0) { // MER
            val all = if (f4 && layout.dualBank && value and (1 shl 15) == 0) layout.units.filter { it.index < 12 } else layout.units
            all.forEach { erase(it) }
            massErases++
            busyUntil = now + 50
        } else if (value and (1 shl 1) != 0) { // SER / PER
            val unit = if (f4) {
                val snb = (value ushr 3) and 0x1F
                val n = if (snb >= 0x10) 12 + (snb - 0x10) else snb
                layout.units.first { it.index == n }
            } else {
                layout.units.first { it.address == ar.toLong() and 0xFFFFFFFFL }
            }
            erase(unit)
            erasedUnits += unit.index
            busyUntil = now + 20
        }
    }

    private fun erase(u: EraseUnit) {
        val o = (u.address - layout.start).toInt()
        for (i in 0 until u.size) flash[o + i] = 0xFF.toByte()
    }

    private fun program(address: Long, data: ByteArray, width: Int) {
        blockWrites++
        if (waitAtBlock == blockWrites) throw StLinkException(StLinkErrorKind.SWD_FAULT, "Запись блока: WAIT", status = 0x10)
        if (!inFlash(address)) {
            for (i in data.indices step 4) words[address + i] = (0 until 4).fold(0) { acc, k -> acc or ((data.getOrElse(i + k) { 0 }.toInt() and 0xFF) shl (8 * k)) }
            return
        }
        if (cr and 1 == 0 || locked) {
            sr = sr or pgErrFlag
            return
        }
        if (f4) {
            val ps = when ((cr ushr 8) and 3) {
                0 -> 1
                1 -> 2
                else -> 4
            }
            if (ps != width) {
                sr = sr or (1 shl 6) // PGPERR
                return
            }
        } else if (width != 2) {
            sr = sr or pgErrFlag
            return
        }
        if (errorAtBlock == blockWrites) {
            sr = sr or pgErrFlag
            return
        }
        val o = (address - layout.start).toInt()
        if (!f4) {
            for (i in data.indices step 2) {
                if (flash[o + i] != 0xFF.toByte() || flash[o + i + 1] != 0xFF.toByte()) {
                    sr = sr or pgErrFlag
                    return
                }
            }
        }
        for (i in data.indices) flash[o + i] = (flash[o + i].toInt() and data[i].toInt()).toByte()
        corruptAt?.let { c -> if (c >= address && c < address + data.size) flash[(c - layout.start).toInt()] = 0x00 }
    }

    override fun writeBlock32(address: Long, words: IntArray) {
        val b = ByteArray(words.size * 4)
        words.forEachIndexed { i, w -> for (k in 0 until 4) b[4 * i + k] = (w ushr (8 * k)).toByte() }
        program(address, b, 4)
    }

    override fun writeBlock16(address: Long, halves: ShortArray) {
        val b = ByteArray(halves.size * 2)
        halves.forEachIndexed { i, h ->
            b[2 * i] = h.toByte()
            b[2 * i + 1] = (h.toInt() ushr 8).toByte()
        }
        program(address, b, 2)
    }

    override fun writeBytes(address: Long, bytes: ByteArray) = program(address, bytes, 1)

    private fun state() = CoreState(if (halted) CoreState.S_HALT or CoreState.C_DEBUGEN else CoreState.C_DEBUGEN)

    override fun coreState(): CoreState = state()

    override fun halt(): CoreState {
        halted = true
        return state()
    }

    override fun go(): CoreState {
        halted = false
        return state()
    }

    override fun reset(): CoreState {
        halted = false
        locked = true
        runs++
        return state()
    }

    override fun resetHalt(): CoreState {
        halted = true
        locked = true
        resetHalts++
        return state()
    }

    override fun pulseNrst() = Unit

    override fun detach(): CoreState = go()
}

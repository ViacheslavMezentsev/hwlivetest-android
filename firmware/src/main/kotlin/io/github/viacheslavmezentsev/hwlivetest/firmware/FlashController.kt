package io.github.viacheslavmezentsev.hwlivetest.firmware

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory

/** Виды ошибок модуля прошивки (ТЗ прошивки 5.8.1). */
enum class FlashErrorKind(val title: String) {
    REFUSED("отказ"),
    LOCKED("контроллер Flash не разблокирован"),
    FLASH_ERROR("ошибка контроллера Flash"),
    FLASH_TIMEOUT("таймаут контроллера Flash"),
    VERIFY_FAILED("ошибка сверки"),
    INCOMPLETE("прошивка не завершена"),
    CANCELLED("отменено"),
}

class FlashException(
    val kind: FlashErrorKind,
    message: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Время и паузы: в тестах подменяются моделью. */
class FlashClock(
    val now: () -> Long = { System.currentTimeMillis() },
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
)

/**
 * Алгоритм контроллера Flash семейства (ТЗ прошивки 6.1.2). Чтение — через [DebugMemory],
 * запись — только через [TargetWrite] (п. 6.1.1). Ядро должно быть остановлено.
 */
interface FlashController {
    val layout: FlashLayout

    fun unlock()
    fun eraseUnit(unit: EraseUnit)
    fun massErase()

    /** Записать [data] с [address]; [progress] получает число записанных байт и может отменить (false). */
    fun program(address: Long, data: ByteArray, progress: (Int) -> Boolean = { true })
    fun lock()

    /** Номера единиц, защищённых WRP (чтение регистров option bytes, без записи — п. 3.1.6). */
    fun writeProtected(): Set<Int>

    /** Уровень RDP 0 (защиты чтения нет). */
    fun readoutUnprotected(): Boolean

    companion object {
        const val KEY1 = 0x45670123
        const val KEY2 = 0xCDEF89AB.toInt()

        fun of(layout: FlashLayout, mem: DebugMemory, tw: TargetWrite, psize: Int, clock: FlashClock = FlashClock()): FlashController =
            when (layout.family) {
                FlashFamily.F4 -> Stm32F4Flash(layout, mem, tw, psize, clock)
                FlashFamily.F1 -> Stm32F1Flash(layout, mem, tw, clock)
            }
    }
}

/** Общая часть: ожидание BSY с таймаутом, проверка флагов (ТЗ прошивки 4.1.3, 4.1.4). */
internal abstract class FlashControllerBase(
    final override val layout: FlashLayout,
    protected val mem: DebugMemory,
    protected val tw: TargetWrite,
    protected val clock: FlashClock,
) : FlashController {
    protected abstract val base: Long
    protected abstract val bsyMask: Int
    protected abstract val lockMask: Int
    protected abstract val errorFlags: List<Pair<Int, String>>
    protected abstract val clearMask: Int

    protected val keyr: Long get() = base + 0x04
    protected val sr: Long get() = base + 0x0C
    protected val cr: Long get() = base + 0x10

    // ТЗ прошивки 4.1.2
    override fun unlock() {
        if (mem.read32(cr) and lockMask == 0) return
        tw.write32(keyr, FlashController.KEY1)
        tw.write32(keyr, FlashController.KEY2)
        if (mem.read32(cr) and lockMask != 0) {
            throw FlashException(FlashErrorKind.LOCKED, "Контроллер Flash остался заблокирован после ввода ключей (CR LOCK = 1).")
        }
    }

    override fun lock() {
        tw.write32(cr, lockMask)
    }

    protected fun clearErrors() {
        tw.write32(sr, clearMask)
    }

    // ТЗ прошивки 4.1.3, 4.1.4
    protected fun waitReady(timeoutMs: Long, what: String) {
        val start = clock.now()
        while (true) {
            val s = mem.read32(sr)
            if (s and bsyMask == 0) {
                val bad = errorFlags.filter { s and it.first != 0 }.map { it.second }
                if (bad.isNotEmpty()) {
                    throw FlashException(FlashErrorKind.FLASH_ERROR, "$what: ${bad.joinToString(", ")} (SR 0x%08X)".format(s))
                }
                return
            }
            if (clock.now() - start >= timeoutMs) {
                throw FlashException(FlashErrorKind.FLASH_TIMEOUT, "$what: BSY не снят за $timeoutMs мс (SR 0x%08X)".format(s))
            }
            clock.sleep(1)
        }
    }
}

/** STM32F4 (ТЗ прошивки 4.2). */
internal class Stm32F4Flash(
    layout: FlashLayout,
    mem: DebugMemory,
    tw: TargetWrite,
    /** Ширина записи PSIZE: 8, 16 или 32 бит (ТЗ прошивки 4.2.2). */
    private val psize: Int,
    clock: FlashClock,
) : FlashControllerBase(layout, mem, tw, clock) {
    override val base = BASE
    override val bsyMask = 1 shl 16
    override val lockMask = 1 shl 31
    override val errorFlags = listOf(1 to "OPERR", (1 shl 4) to "WRPERR", (1 shl 5) to "PGAERR", (1 shl 6) to "PGPERR", (1 shl 7) to "PGSERR")
    override val clearMask = 0xF3 // EOP, OPERR, WRPERR, PGAERR, PGPERR, PGSERR

    private val psizeBits: Int = when (psize) {
        8 -> 0
        16 -> 1
        32 -> 2
        else -> throw IllegalArgumentException("PSIZE $psize")
    } shl 8

    // ТЗ прошивки 4.2.3: SNB второго банка F42x — 0x10 + (n − 12).
    private fun snb(n: Int): Int = if (n < 12) n else 0x10 + (n - 12)

    override fun eraseUnit(unit: EraseUnit) {
        clearErrors()
        val v = SER or (snb(unit.index) shl 3) or psizeBits
        tw.write32(cr, v)
        tw.write32(cr, v or STRT)
        try {
            waitReady(SECTOR_TIMEOUT_MS, "стирание сектора ${unit.index}")
        } finally {
            tw.write32(cr, 0)
        }
    }

    override fun massErase() {
        clearErrors()
        val v = MER or (if (layout.dualBank) MER1 else 0) or psizeBits
        tw.write32(cr, v)
        tw.write32(cr, v or STRT)
        try {
            waitReady(MASS_TIMEOUT_MS, "полное стирание")
        } finally {
            tw.write32(cr, 0)
        }
    }

    // ТЗ прошивки 4.2.4
    override fun program(address: Long, data: ByteArray, progress: (Int) -> Boolean) {
        val step = psize / 8
        val (start, padded) = pad(address, data, step)
        clearErrors()
        tw.write32(cr, PG or psizeBits)
        try {
            val chunk = if (psize == 8) 64 else CHUNK_BYTES
            var done = 0
            while (done < padded.size) {
                val a = start + done
                // Блок не пересекает границу 1 КБ (ТЗ ST-Link 3.2.6).
                val n = minOf(padded.size - done, chunk, (0x400 - (a and 0x3FF)).toInt())
                val part = padded.copyOfRange(done, done + n)
                when (psize) {
                    32 -> tw.writeBlock32(a, IntArray(n / 4) { le32(part, 4 * it) })
                    16 -> tw.writeBlock16(a, ShortArray(n / 2) { le16(part, 2 * it) })
                    else -> tw.writeBytes(a, part)
                }
                waitReady(WRITE_TIMEOUT_MS, "запись ${"0x%08X".format(a)}")
                done += n
                if (!progress(minOf(done, data.size))) throw FlashException(FlashErrorKind.INCOMPLETE, "Запись отменена пользователем.")
            }
        } finally {
            tw.write32(cr, 0)
        }
    }

    override fun writeProtected(): Set<Int> {
        val out = HashSet<Int>()
        val optcr = mem.read32(OPTCR)
        for (i in 0 until 12) if (optcr and (1 shl (16 + i)) == 0) out += i
        if (layout.dualBank) {
            val optcr1 = mem.read32(OPTCR1)
            for (i in 0 until 12) if (optcr1 and (1 shl (16 + i)) == 0) out += 12 + i
        }
        return out.filter { n -> layout.units.any { it.index == n } }.toSet()
    }

    override fun readoutUnprotected(): Boolean = (mem.read32(OPTCR) ushr 8) and 0xFF == 0xAA

    companion object {
        const val BASE = 0x40023C00L
        const val OPTCR = 0x40023C14L
        const val OPTCR1 = 0x40023C18L
        const val PG = 1
        const val SER = 1 shl 1
        const val MER = 1 shl 2
        const val MER1 = 1 shl 15
        const val STRT = 1 shl 16
        const val CHUNK_BYTES = 256
        const val SECTOR_TIMEOUT_MS = 4000L
        const val MASS_TIMEOUT_MS = 40000L
        const val WRITE_TIMEOUT_MS = 100L
    }
}

/** STM32F1 (ТЗ прошивки 4.3). */
internal class Stm32F1Flash(
    layout: FlashLayout,
    mem: DebugMemory,
    tw: TargetWrite,
    clock: FlashClock,
) : FlashControllerBase(layout, mem, tw, clock) {
    override val base = BASE
    override val bsyMask = 1
    override val lockMask = 1 shl 7
    override val errorFlags = listOf((1 shl 2) to "PGERR", (1 shl 4) to "WRPRTERR")
    override val clearMask = 0x34 // PGERR, WRPRTERR, EOP

    private val ar: Long get() = base + 0x14

    override fun eraseUnit(unit: EraseUnit) {
        clearErrors()
        tw.write32(cr, PER)
        tw.write32(ar, unit.address.toInt())
        tw.write32(cr, PER or STRT)
        try {
            waitReady(ERASE_TIMEOUT_MS, "стирание страницы ${unit.index}")
        } finally {
            tw.write32(cr, 0)
        }
    }

    override fun massErase() {
        clearErrors()
        tw.write32(cr, MER)
        tw.write32(cr, MER or STRT)
        try {
            waitReady(ERASE_TIMEOUT_MS, "полное стирание")
        } finally {
            tw.write32(cr, 0)
        }
    }

    // ТЗ прошивки 4.3.3: полусловами.
    override fun program(address: Long, data: ByteArray, progress: (Int) -> Boolean) {
        val (start, padded) = pad(address, data, 2)
        clearErrors()
        tw.write32(cr, PG)
        try {
            var done = 0
            while (done < padded.size) {
                val a = start + done
                val n = minOf(padded.size - done, CHUNK_BYTES, (0x400 - (a and 0x3FF)).toInt())
                val part = padded.copyOfRange(done, done + n)
                tw.writeBlock16(a, ShortArray(n / 2) { le16(part, 2 * it) })
                waitReady(WRITE_TIMEOUT_MS, "запись ${"0x%08X".format(a)}")
                done += n
                if (!progress(minOf(done, data.size))) throw FlashException(FlashErrorKind.INCOMPLETE, "Запись отменена пользователем.")
            }
        } finally {
            tw.write32(cr, 0)
        }
    }

    /** WRPR: бит = 0 — защищены страницы; low/medium — по 4 страницы на бит, high — по 2, бит 31 — остаток. */
    override fun writeProtected(): Set<Int> {
        val wrpr = mem.read32(WRPR)
        val perBit = if (layout.devId == 0x414) 2 else 4
        val out = HashSet<Int>()
        for (u in layout.units) {
            val bit = minOf(u.index / perBit, 31)
            if (wrpr and (1 shl bit) == 0) out += u.index
        }
        return out
    }

    override fun readoutUnprotected(): Boolean = mem.read32(OBR) and (1 shl 1) == 0

    companion object {
        const val BASE = 0x40022000L
        const val OBR = 0x4002201CL
        const val WRPR = 0x40022020L
        const val PG = 1
        const val PER = 1 shl 1
        const val MER = 1 shl 2
        const val STRT = 1 shl 6
        const val CHUNK_BYTES = 256
        const val ERASE_TIMEOUT_MS = 500L
        const val WRITE_TIMEOUT_MS = 100L
    }
}

/** Выровнять начало и конец по [step], дополнив 0xFF (ТЗ прошивки TC-08: остаток дополняется 0xFF). */
internal fun pad(address: Long, data: ByteArray, step: Int): Pair<Long, ByteArray> {
    val start = address - (address % step)
    val head = (address - start).toInt()
    val total = ((head + data.size + step - 1) / step) * step
    val out = ByteArray(total) { 0xFF.toByte() }
    data.copyInto(out, head)
    return start to out
}

internal fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun le16(b: ByteArray, o: Int): Short = ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort()

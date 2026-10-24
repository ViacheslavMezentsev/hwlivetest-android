package io.github.viacheslavmezentsev.hwlivetest.firmware

/** Семейство контроллера Flash (ТЗ прошивки 4.1.1). */
enum class FlashFamily(val title: String) {
    F1("STM32F1"),
    F4("STM32F4"),
}

/** Единица стирания: сектор F4 или страница F1. [index] — номер сектора (F4) или страницы (F1). */
data class EraseUnit(val index: Int, val address: Long, val size: Int) {
    val end: Long get() = address + size

    fun overlaps(start: Long, endExclusive: Long): Boolean = start < end && endExclusive > address

    val title: String get() = if (size >= 16 * 1024) "сектор $index (${size / 1024} КБ)" else "страница $index"
}

/** Карта Flash опознанной цели (ТЗ прошивки, приложение C). */
class FlashLayout(
    val family: FlashFamily,
    val devId: Int,
    val units: List<EraseUnit>,
) {
    val start: Long get() = FLASH_BASE
    val size: Int get() = units.sumOf { it.size }
    val end: Long get() = start + size

    /** Два банка F42x/F43x: полное стирание требует MER и MER1 (ТЗ прошивки 4.2.3). */
    val dualBank: Boolean get() = family == FlashFamily.F4 && units.any { it.index >= 12 }

    fun contains(address: Long, length: Int): Boolean = address >= start && address + length <= end

    fun unitsFor(start: Long, endExclusive: Long): List<EraseUnit> = units.filter { it.overlaps(start, endExclusive) }

    companion object {
        const val FLASH_BASE = 0x08000000L

        /** ТЗ прошивки 4.1.1: DEV_ID → семейство. */
        val F1_DEV_IDS = setOf(0x410, 0x412, 0x414)
        val F4_DEV_IDS = setOf(0x413, 0x419, 0x423, 0x431, 0x433)

        fun familyOf(devId: Int): FlashFamily? = when (devId) {
            in F1_DEV_IDS -> FlashFamily.F1
            in F4_DEV_IDS -> FlashFamily.F4
            else -> null
        }

        /**
         * Карта по DEV_ID и размеру Flash (F_SIZE, КБ). null — семейство не поддерживается
         * или размер не прочитан.
         */
        fun of(devId: Int, flashKb: Int?): FlashLayout? {
            val family = familyOf(devId) ?: return null
            if (flashKb == null || flashKb <= 0) return null
            val bytes = flashKb * 1024L
            return when (family) {
                FlashFamily.F1 -> {
                    // ТЗ прошивки, приложение C: страницы 1 КБ, у high density — 2 КБ.
                    val page = if (devId == 0x414) 2048 else 1024
                    val n = (bytes / page).toInt()
                    FlashLayout(family, devId, List(n) { EraseUnit(it, FLASH_BASE + it.toLong() * page, page) })
                }
                FlashFamily.F4 -> FlashLayout(family, devId, f4Sectors(devId, bytes))
            }
        }

        private val F4_BANK_KB = listOf(16, 16, 16, 16, 64, 128, 128, 128, 128, 128, 128, 128)

        /** ТЗ прошивки, приложение C: 4 × 16 КБ, 64 КБ, далее 128 КБ; F42x — второй банк с сектора 12. */
        private fun f4Sectors(devId: Int, bytes: Long): List<EraseUnit> {
            val out = ArrayList<EraseUnit>()
            var address = FLASH_BASE
            fun bank(first: Int, limit: Long) {
                for ((i, kb) in F4_BANK_KB.withIndex()) {
                    if (address - FLASH_BASE >= limit) return
                    val size = kb * 1024
                    out += EraseUnit(first + i, address, size)
                    address += size
                }
            }
            if (devId == 0x419 && bytes > 1024L * 1024) {
                bank(0, 1024L * 1024)
                bank(12, bytes)
            } else {
                bank(0, bytes)
            }
            return out
        }
    }
}

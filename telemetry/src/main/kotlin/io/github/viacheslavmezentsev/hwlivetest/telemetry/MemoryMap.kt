package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Проверка адресов полей по карте памяти Cortex-M. Адресам из addresses.json доверяем,
 * поэтому почти всё — предупреждения; запрещено только то, что нельзя прочитать
 * одной командой (невыровненный адрес).
 */
object MemoryMap {
    const val FLASH_BASE = 0x08000000L
    const val SRAM_BASE = 0x20000000L

    enum class Region(val title: String) {
        FLASH("Flash"),
        SRAM("SRAM"),
        SYSTEM("системная память / CCM"),
        PERIPHERAL("периферия"),
        PPB("регистры ядра"),
        OTHER("вне известных областей"),
    }

    data class Check(val region: Region, val readable: Boolean, val warning: String?)

    fun region(address: Long): Region = when (address) {
        in 0x08000000L..0x0FFFFFFFL -> Region.FLASH
        in 0x10000000L..0x1FFFFFFFL -> Region.SYSTEM
        in 0x20000000L..0x3FFFFFFFL -> Region.SRAM
        in 0x40000000L..0x5FFFFFFFL -> Region.PERIPHERAL
        in 0xE0000000L..0xE00FFFFFL -> Region.PPB
        else -> Region.OTHER
    }

    /** [flashKb] и [sramKb] — размеры, если известны после опознания MCU. */
    fun check(address: Long, flashKb: Int?, sramKb: Int?): Check {
        val r = region(address)
        if (address and 3L != 0L) {
            return Check(r, readable = false, warning = "адрес не выровнен на 4 — чтение не поддерживается")
        }
        val warning = when (r) {
            Region.FLASH -> flashKb?.let {
                if (address >= FLASH_BASE + it * 1024L) "за пределами Flash ($it КБ)" else null
            }
            Region.SRAM -> sramKb?.let {
                if (address >= SRAM_BASE + it * 1024L) "за пределами SRAM ($it КБ)" else null
            }
            Region.PERIPHERAL -> "регистр периферии: чтение некоторых регистров меняет их состояние"
            Region.OTHER -> "адрес вне Flash, SRAM и периферии"
            else -> null
        }
        return Check(r, readable = true, warning = warning)
    }
}

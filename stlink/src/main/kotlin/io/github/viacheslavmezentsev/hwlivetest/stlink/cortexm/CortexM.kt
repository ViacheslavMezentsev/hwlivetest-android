package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.hex12
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32

/** Доступ к памяти цели только на чтение (MEM-AP 0). */
interface DebugMemory {
    fun read32(address: Long): Int

    /** Регистр AP или null, если отладчик этого не умеет. */
    fun readApRegister(ap: Int, register: Int): Int? = null

    /** [words] слов подряд с выровненного адреса. По умолчанию — по одному слову. */
    fun readBlock(address: Long, words: Int): IntArray = IntArray(words) { read32(address + 4L * it) }
}

/** Читает 16-битное значение по произвольному чётному адресу через выровненное слово. */
fun DebugMemory.read16(address: Long): Int {
    require(address and 1L == 0L) { "Адрес должен быть чётным: ${address.hex32()}" }
    val word = read32(address and 3L.inv())
    return if (address and 2L == 0L) word and 0xFFFF else (word ushr 16) and 0xFFFF
}

/** SCB CPUID (0xE000ED00). */
data class CpuId(val raw: Int) {
    val implementer: Int get() = (raw ushr 24) and 0xFF
    val variant: Int get() = (raw ushr 20) and 0x0F
    val architecture: Int get() = (raw ushr 16) and 0x0F
    val partNo: Int get() = (raw ushr 4) and 0xFFF
    val revision: Int get() = raw and 0x0F

    val core: CortexCore? get() = CortexCore.find(implementer, partNo)

    val coreName: String
        get() = core?.title ?: "неизвестное ядро (implementer 0x%02X, part ${partNo.hex12()})".format(implementer)

    /** Ревизия в нотации ARM: r1p1. */
    val revisionLabel: String get() = "r${variant}p$revision"

    val implementerName: String
        get() = when (implementer) {
            0x41 -> "ARM"
            0x63 -> "Arm China"
            else -> "0x%02X".format(implementer)
        }

    companion object {
        const val ADDRESS = 0xE000ED00L
    }
}

enum class CortexCore(val implementer: Int, val partNo: Int, val title: String) {
    M0(0x41, 0xC20, "Cortex-M0"),
    M0_PLUS(0x41, 0xC60, "Cortex-M0+"),
    M1(0x41, 0xC21, "Cortex-M1"),
    M3(0x41, 0xC23, "Cortex-M3"),
    M4(0x41, 0xC24, "Cortex-M4"),
    M7(0x41, 0xC27, "Cortex-M7"),
    M23(0x41, 0xD20, "Cortex-M23"),
    M33(0x41, 0xD21, "Cortex-M33"),
    M35P(0x41, 0xD31, "Cortex-M35P"),
    M55(0x41, 0xD22, "Cortex-M55"),
    M85(0x41, 0xD23, "Cortex-M85"),
    STAR_MC1(0x63, 0x132, "STAR-MC1");

    /** Ядра ARMv6-M/ARMv8-M Baseline: у STM32 на них DBGMCU обычно по адресу 0x40015800. */
    val isBaseline: Boolean get() = this == M0 || this == M0_PLUS || this == M1 || this == M23

    companion object {
        fun find(implementer: Int, partNo: Int): CortexCore? =
            entries.firstOrNull { it.implementer == implementer && it.partNo == partNo }
    }
}

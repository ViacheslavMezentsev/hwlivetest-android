package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.hex12

/** Код производителя JEP106: число байтов продолжения (банк − 1) и 7-битный код. */
data class Jep106(val continuation: Int, val id: Int) {
    /** 11-битное поле DESIGNER в формате ADIv5: cont[10:7], id[6:0]. */
    val designer: Int get() = (continuation shl 7) or id

    val name: String? get() = NAMES[designer]

    val label: String
        get() = name?.let { "$it (JEP106 ${designer.hex12()})" }
            ?: "JEP106 банк ${continuation + 1}, код 0x%02X".format(id)

    companion object {
        fun fromDesigner(designer: Int) = Jep106((designer ushr 7) and 0x0F, designer and 0x7F)

        val ARM = Jep106(4, 0x3B)
        val ST = Jep106(0, 0x20)

        private val NAMES: Map<Int, String> = mapOf(
            ST.designer to "STMicroelectronics",
            ARM.designer to "ARM",
            Jep106(0, 0x0E).designer to "Freescale (NXP)",
            Jep106(0, 0x15).designer to "NXP (Philips)",
            Jep106(0, 0x17).designer to "Texas Instruments",
            Jep106(0, 0x1F).designer to "Atmel (Microchip)",
            Jep106(0, 0x29).designer to "Microchip",
            Jep106(2, 0x44).designer to "Nordic Semiconductor",
        )
    }
}

/** DP IDCODE / DPIDR (ADIv5). */
data class DpIdCode(val raw: Int) {
    val revision: Int get() = (raw ushr 28) and 0x0F
    val partNo: Int get() = (raw ushr 20) and 0xFF
    val version: Int get() = (raw ushr 12) and 0x0F
    val designer: Jep106 get() = Jep106.fromDesigner((raw ushr 1) and 0x7FF)

    /** Бит 0 всегда 1 у корректного IDCODE. */
    val looksValid: Boolean get() = raw and 1 == 1

    val description: String
        get() {
            val kind = when (partNo) {
                0xBA -> "SW-DP"
                0xBB -> "SW-DP (Cortex-M0/M0+)"
                0xBC -> "SW-DP (multidrop)"
                else -> "DP part 0x%02X".format(partNo)
            }
            return "$kind v$version r$revision, ${designer.name ?: designer.label}"
        }
}

/** Идентификационные регистры компонента CoreSight (PIDR0..4, CIDR0..3). */
data class ComponentId(val pidr: IntArray, val cidr: IntArray) {
    init {
        require(pidr.size == 5 && cidr.size == 4)
    }

    val partNo: Int get() = (pidr[0] and 0xFF) or ((pidr[1] and 0x0F) shl 8)
    val usesJep106: Boolean get() = pidr[2] and 0x08 != 0
    val revision: Int get() = (pidr[2] ushr 4) and 0x0F
    val designer: Jep106
        get() = Jep106(
            continuation = pidr[4] and 0x0F,
            id = ((pidr[1] ushr 4) and 0x0F) or ((pidr[2] and 0x07) shl 4),
        )

    /** Преамбула CIDR: 0x0D, 0x?0, 0x05, 0xB1. */
    val isValid: Boolean
        get() = (cidr[0] and 0xFF) == 0x0D && (cidr[1] and 0x0F) == 0 &&
            (cidr[2] and 0xFF) == 0x05 && (cidr[3] and 0xFF) == 0xB1

    /** Класс компонента: 1 — ROM-таблица, 9 — CoreSight. */
    val componentClass: Int get() = (cidr[1] ushr 4) and 0x0F

    override fun equals(other: Any?): Boolean =
        other is ComponentId && pidr.contentEquals(other.pidr) && cidr.contentEquals(other.cidr)

    override fun hashCode(): Int = 31 * pidr.contentHashCode() + cidr.contentHashCode()

    companion object {
        /** Прочитать идентификаторы компонента по базовому адресу (4 КБ блок). */
        fun read(mem: DebugMemory, base: Long): ComponentId {
            val pidr = IntArray(5)
            for (i in 0 until 4) pidr[i] = mem.read32(base + 0xFE0 + 4 * i) and 0xFF
            pidr[4] = mem.read32(base + 0xFD0) and 0xFF
            val cidr = IntArray(4) { mem.read32(base + 0xFF0 + 4 * it) and 0xFF }
            return ComponentId(pidr, cidr)
        }
    }
}

object RomTable {
    /** Типичный адрес ROM-таблицы Cortex-M, если BASE у MEM-AP прочитать не удалось. */
    const val DEFAULT_BASE = 0xE00FF000L

    /** Регистр BASE у MEM-AP. */
    const val AP_BASE_REGISTER = 0xF8

    /** Разбор регистра BASE: null, если отладочных компонентов нет. */
    fun baseFromApRegister(base: Int): Long? {
        if (base == -1) return null // 0xFFFFFFFF: устаревший формат «нет компонентов»
        val present = base and 1 == 1
        val adiv5Format = base and 2 == 2
        if (adiv5Format && !present) return null
        return base.toLong() and 0xFFFFF000L
    }
}

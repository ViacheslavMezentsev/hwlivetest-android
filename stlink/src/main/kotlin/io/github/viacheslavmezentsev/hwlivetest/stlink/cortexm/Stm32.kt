package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

/**
 * Справочник STM32: DEV_ID из DBGMCU_IDCODE и адрес регистра размера Flash (F_SIZE, КБ).
 * Значения сверены с драйверами flash/stm32*.c в OpenOCD и справочными руководствами ST.
 */
object Stm32 {
    /** DBGMCU_IDCODE у большинства серий. */
    const val DBGMCU_IDCODE = 0xE0042000L

    /** DBGMCU_IDCODE у F0, G0, L0, C0 (ядра Cortex-M0/M0+). */
    const val DBGMCU_IDCODE_M0 = 0x40015800L

    /** DBGMCU_IDCODE у H7. */
    const val DBGMCU_IDCODE_H7 = 0x5C001000L

    enum class Family(val title: String, val flashSizeAddress: Long?) {
        C0("STM32C0", 0x1FFF75A0L),
        F0("STM32F0", 0x1FFFF7CCL),
        F1("STM32F1", 0x1FFFF7E0L),
        F2("STM32F2", 0x1FFF7A22L),
        F3("STM32F3", 0x1FFFF7CCL),
        F4("STM32F4", 0x1FFF7A22L),
        F7("STM32F7", 0x1FF0F442L),
        G0("STM32G0", 0x1FFF75E0L),
        G4("STM32G4", 0x1FFF75E0L),
        H7("STM32H7", 0x1FF1E880L),
        L0("STM32L0", 0x1FF8007CL),
        L1("STM32L1", 0x1FF8004CL),
        L4("STM32L4", 0x1FFF75E0L),
        L5("STM32L5", 0x0BFA05E0L),
        U5("STM32U5", 0x0BFA07A0L),
        WB("STM32WB", 0x1FFF75E0L),
        WL("STM32WL", 0x1FFF75E0L),
    }

    data class Part(
        val devId: Int,
        val name: String,
        val family: Family,
        /** Если у линейки свой адрес F_SIZE. */
        val flashSizeAddressOverride: Long? = null,
    ) {
        val flashSizeAddress: Long? get() = flashSizeAddressOverride ?: family.flashSizeAddress
    }

    val parts: List<Part> = listOf(
        // F0
        Part(0x440, "STM32F030x8 / F05x", Family.F0),
        Part(0x442, "STM32F030xC / F09x", Family.F0),
        Part(0x444, "STM32F03x (x4/x6)", Family.F0),
        Part(0x445, "STM32F04x / F070x6", Family.F0),
        Part(0x448, "STM32F070xB / F07x", Family.F0),
        // F1
        Part(0x410, "STM32F10x medium-density", Family.F1),
        Part(0x412, "STM32F10x low-density", Family.F1),
        Part(0x414, "STM32F10x high-density", Family.F1),
        Part(0x418, "STM32F105/F107 connectivity line", Family.F1),
        Part(0x420, "STM32F100 value line LD/MD", Family.F1),
        Part(0x428, "STM32F100 value line HD", Family.F1),
        Part(0x430, "STM32F10x XL-density", Family.F1),
        // F2
        Part(0x411, "STM32F2xx", Family.F2),
        // F3
        Part(0x422, "STM32F302xB/C / F303xB/C / F358", Family.F3),
        Part(0x432, "STM32F37x", Family.F3),
        Part(0x438, "STM32F303x6/8 / F334 / F328", Family.F3),
        Part(0x439, "STM32F301 / F302x6/8 / F318", Family.F3),
        Part(0x446, "STM32F302xD/E / F303xD/E / F398", Family.F3),
        // F4
        Part(0x413, "STM32F405/407/415/417", Family.F4),
        Part(0x419, "STM32F42x/43x", Family.F4),
        Part(0x421, "STM32F446", Family.F4),
        Part(0x423, "STM32F401xB/C", Family.F4),
        Part(0x431, "STM32F411xC/E", Family.F4),
        Part(0x433, "STM32F401xD/E", Family.F4),
        Part(0x434, "STM32F469/479", Family.F4),
        Part(0x441, "STM32F412", Family.F4),
        Part(0x458, "STM32F410", Family.F4),
        Part(0x463, "STM32F413/423", Family.F4),
        // F7
        Part(0x449, "STM32F74x/75x", Family.F7),
        Part(0x451, "STM32F76x/77x", Family.F7),
        Part(0x452, "STM32F72x/73x", Family.F7, flashSizeAddressOverride = 0x1FF07A22L),
        // G0, G4
        Part(0x456, "STM32G05x/06x", Family.G0),
        Part(0x460, "STM32G07x/08x", Family.G0),
        Part(0x466, "STM32G03x/04x", Family.G0),
        Part(0x467, "STM32G0Bx/0Cx", Family.G0),
        Part(0x468, "STM32G431/441", Family.G4),
        Part(0x469, "STM32G47x/48x", Family.G4),
        Part(0x479, "STM32G491/4A1", Family.G4),
        // H7
        Part(0x450, "STM32H74x/75x", Family.H7),
        Part(0x480, "STM32H7A3/7B3", Family.H7, flashSizeAddressOverride = 0x08FFF80CL),
        Part(0x483, "STM32H72x/73x", Family.H7),
        // L0, L1
        Part(0x417, "STM32L05x/06x", Family.L0),
        Part(0x425, "STM32L03x/04x", Family.L0),
        Part(0x447, "STM32L07x/08x", Family.L0),
        Part(0x457, "STM32L01x/02x", Family.L0),
        Part(0x416, "STM32L1 Cat.1", Family.L1),
        Part(0x429, "STM32L1 Cat.2", Family.L1),
        Part(0x427, "STM32L1 Cat.3", Family.L1, flashSizeAddressOverride = 0x1FF800CCL),
        Part(0x436, "STM32L1 Cat.4 / Cat.3 (x8)", Family.L1, flashSizeAddressOverride = 0x1FF800CCL),
        Part(0x437, "STM32L1 Cat.5/6", Family.L1, flashSizeAddressOverride = 0x1FF800CCL),
        // L4, L5, U5
        Part(0x415, "STM32L47x/48x", Family.L4),
        Part(0x435, "STM32L43x/44x", Family.L4),
        Part(0x461, "STM32L496/4A6", Family.L4),
        Part(0x462, "STM32L45x/46x", Family.L4),
        Part(0x464, "STM32L41x/42x", Family.L4),
        Part(0x470, "STM32L4R/4S", Family.L4),
        Part(0x471, "STM32L4P5/4Q5", Family.L4),
        Part(0x472, "STM32L55x/56x", Family.L5),
        Part(0x482, "STM32U57x/58x", Family.U5),
        // C0, WB, WL
        Part(0x443, "STM32C011", Family.C0),
        Part(0x453, "STM32C031", Family.C0),
        Part(0x495, "STM32WB55/35", Family.WB),
        Part(0x497, "STM32WLE5/WL55", Family.WL),
    )

    fun find(devId: Int): Part? = parts.firstOrNull { it.devId == devId }

    /**
     * Адреса DBGMCU_IDCODE в порядке проверки для данного ядра. Адреса в области периферии
     * пробуем только там, где они ожидаются: чтение чужого регистра может иметь побочный эффект.
     */
    fun dbgmcuCandidates(core: CortexCore?): List<Long> = when {
        core?.isBaseline == true -> listOf(DBGMCU_IDCODE_M0, DBGMCU_IDCODE)
        core == CortexCore.M7 -> listOf(DBGMCU_IDCODE, DBGMCU_IDCODE_H7)
        else -> listOf(DBGMCU_IDCODE)
    }

    /**
     * Разбор DBGMCU_IDCODE: DEV_ID[11:0], REV_ID[31:16]. Биты 15:12 зарезервированы и
     * бывают ненулевыми (у STM32F103 читается 0x20036410), поэтому не проверяются.
     */
    data class IdCode(val raw: Int) {
        val devId: Int get() = raw and 0xFFF
        val revId: Int get() = (raw ushr 16) and 0xFFFF
        val part: Part? get() = find(devId)

        /** Похоже ли значение на настоящий DBGMCU_IDCODE STM32. */
        val looksLikeStm32: Boolean
            get() = raw != 0 && raw != -1 && part != null && revId < 0x4000
    }

    /** F_SIZE: 0 и 0xFFFF означают «не записано». */
    fun flashSizeKb(raw16: Int): Int? = if (raw16 == 0 || raw16 == 0xFFFF) null else raw16
}

package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

/** ТЗ ST-Link 5.13: фиксированные профили, без догадок о памяти неизвестного MCU. */
data class McuProfile(
    val id: String,
    val title: String,
    val stm32Layout: Boolean,
    val peripheralDiagnostics: Boolean,
    val diagnosticFamily: Stm32.Family? = null,
) {
    companion object {
        val GENERIC = McuProfile("cortex-m", "Cortex-M · общая диагностика", false, false)
        val AT32 = McuProfile("at32", "AT32 · общая диагностика", false, false)

        val STM32_F1 = McuProfile("manual-stm32f1", "STM32F1-совместимый", true, true, Stm32.Family.F1)
        val STM32_F4 = McuProfile("manual-stm32f4", "STM32F4-совместимый", true, true, Stm32.Family.F4)
        val STM32_G4 = McuProfile("manual-stm32g4", "STM32G4-совместимый", true, true, Stm32.Family.G4)
        val selectable = listOf(GENERIC, STM32_F1, STM32_F4, STM32_G4)

        fun forTarget(target: TargetInfo): McuProfile {
            val part = target.stm32Part
            return when {
                target.vendor == McuVendor.STM32 && part != null ->
                    McuProfile("stm32-${part.devId.toString(16)}", part.name, true, true, part.family)
                target.vendor == McuVendor.STM32_COMPATIBLE && part != null ->
                    McuProfile("compatible-${part.devId.toString(16)}",
                        "${part.name} · совместимый, карта не подтверждена", false, false)
                target.vendor == McuVendor.AT32 -> AT32
                else -> GENERIC
            }
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

/** ТЗ RTT 2.14. RM0008/RM0041: F1 APB1; RM0090/RM0368/RM0383: F4 APB1.
 * SRAM is the contiguous bank at 0x20000000, never the separate CCM bank.
 * Device IDs can cover multiple densities: the ELF stack further bounds placement.
 */
data class RttDeviceProfile(
    val deviceId: Int, val name: String, val ramEnd: Long,
    val rccEnable: Long, val cfgr: Long, val ppreShift: Int,
    val timpreRegister: Long?, val maxHclk: Long, val timers: List<RttDoctor.Timer>,
) {
    fun timerClockHz(hclk: Long, cfgrValue: Int, timpreValue: Int = 0): Long {
        kotlin.require(hclk in 1_000_000L..maxHclk) { "Недостоверный SystemCoreClock для $name" }
        val ppre = (cfgrValue ushr ppreShift) and 7
        val divisor = if (ppre < 4) 1 else 1 shl (ppre - 3)
        return if (timpreRegister != null && timpreValue and (1 shl 24) != 0) {
            if (divisor <= 4) hclk else hclk * 4 / divisor
        } else if (divisor == 1) hclk else hclk * 2 / divisor
    }
    fun timerClockHz(memory: DebugMemory, hclk: Long): Long =
        timerClockHz(hclk, memory.read32(cfgr), timpreRegister?.let { memory.read32(it) } ?: 0)

    companion object {
        fun find(id: Int): RttDeviceProfile? {
            val part = Stm32.parts.firstOrNull { it.devId == id } ?: return null
            val f1 = part.family == Stm32.Family.F1
            if (!f1 && part.family != Stm32.Family.F4) return null
            // F410 uses a separate TIM5 clock mux; no guessed RCC configuration.
            if (id == 0x458) return null
            val kb = if (id == 0x434) 320 else part.sramKb ?: return null
            return RttDeviceProfile(id, part.name, 0x20000000L + kb * 1024L,
                if (f1) 0x4002101C else 0x40023840,
                if (f1) 0x40021004 else 0x40023808, if (f1) 8 else 10,
                if (f1 || id == 0x413) null else 0x4002388C,
                if (f1) (if (id in listOf(0x420, 0x428)) 24_000_000 else 72_000_000)
                else if (id in listOf(0x423, 0x433)) 84_000_000
                else if (id in listOf(0x431, 0x441, 0x463)) 100_000_000
                else if (id == 0x413) 168_000_000 else 180_000_000,
                if (f1) RttDoctor.timers.take(1) else RttDoctor.timers)
        }
        fun require(id: Int): RttDeviceProfile = find(id)
            ?: error("Для этого MCU нет профиля записи RTT STM32F1/F4")
    }
}

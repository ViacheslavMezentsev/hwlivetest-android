package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

/** ТЗ RTT 2.3. Register layout: stm32-diag-record 1.8.0 boards.toml, revision 376225b. */
object RttDoctor {
    data class Timer(val name: String, val base: Long, val irq: Int, val clockBit: Int)
    data class Observation(val timer: Timer, val clockEnabled: Boolean, val cr1: Int,
        val dier: Int, val enabled: Boolean, val pending: Boolean, val active: Boolean) {
        // A clocked peripheral may be configured for later use, even with CEN clear.
        val candidate: Boolean get() = !clockEnabled && cr1 and 1 == 0 && dier == 0 && !enabled && !pending && !active
    }
    val timers = listOf(Timer("TIM2", 0x40000000, 28, 1), Timer("TIM3", 0x40000400, 29, 2),
        Timer("TIM4", 0x40000800, 30, 4), Timer("TIM5", 0x40000C00, 50, 8))

    fun inspectF411(memory: DebugMemory, deviceId: Int): List<Observation> {
        require(deviceId == 0x431) { "Проверка таймеров доступна только для опознанного STM32F411" }
        val clocks = memory.read32(0x40023840)
        return timers.map { timer ->
            val bank = 4L * (timer.irq / 32)
            val bit = 1 shl (timer.irq % 32)
            Observation(timer, clocks and timer.clockBit != 0,
                memory.read32(timer.base), memory.read32(timer.base + 0x0C),
                memory.read32(0xE000E100 + bank) and bit != 0,
                memory.read32(0xE000E200 + bank) and bit != 0,
                memory.read32(0xE000E300 + bank) and bit != 0)
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite

/** ТЗ RTT 2.1, 2.5. Not connected to UI until installation and recovery are implemented. */
class RttTeardown(private val memory: DebugMemory, private val write: TargetWrite,
    private val sleep: (Long) -> Unit = Thread::sleep) {
    data class Plan(val timer: RttDoctor.Timer, val stubStart: Long, val stubEnd: Long,
        val tick: Long, val originalVtor: Int, val originalRcc: Int,
        val rccEnable: Long = 0x40023840, val ramEnd: Long = 0x20020000)
    data class Counters(val produced: Long, val dropped: Long, val firstTick: Long, val lastTick: Long) {
        companion object {
            fun parse(words: IntArray): Counters {
                require(words.size == 4) { "Short RTT counters" }
                val n = words.map { it.toUInt().toLong() }
                require(n[1] <= n[0]) { "Invalid RTT counters" }
                return Counters(n[0], n[1], n[2], n[3])
            }
        }
    }
    data class Result(val ok: Boolean, val stage: String, val steps: Int, val error: String? = null,
        val captureError: String? = null, val counters: Counters? = null)

    fun run(plan: Plan, maxSteps: Int = 512, onQuiescent: (() -> Unit)? = null): Result {
        var captureError: String? = null
        var captureInterrupted = false
        require(plan.timer in RttDoctor.timers && maxSteps in 1..512)
        require(plan.stubStart >= 0x20000000 && plan.stubEnd <= plan.ramEnd && plan.stubEnd > plan.stubStart)
        require(plan.tick in 0x20000000L..(plan.ramEnd - 4) && plan.tick % 4 == 0L)
        val t = plan.timer
        val bank = 4L * (t.irq / 32)
        val bit = 1 shl (t.irq % 32)
        val iser = 0xE000E100L + bank
        val ispr = 0xE000E200L + bank
        val active = 0xE000E300L + bank
        var stage = "halt"
        var steps = 0
        fun verify(address: Long, expected: Int, mask: Int = -1) {
            check(memory.read32(address) and mask == expected and mask) { "Readback mismatch at 0x${address.toString(16)}" }
        }
        fun quiet() {
            verify(t.base, 0, 1); verify(t.base + 0x0C, 0); verify(t.base + 0x10, 0, 1)
            verify(iser, 0, bit); verify(ispr, 0, bit); verify(active, 0, bit)
        }
        fun drain() {
            while (memory.read32(active) and bit != 0) {
                val pc = write.readProgramCounter() and 1L.inv()
                check(pc >= plan.stubStart && pc < plan.stubEnd) { "Активный IRQ вне заглушки" }
                check(steps < maxSteps) { "Превышен лимит шагов RTT" }
                write.step(); steps++
            }
        }
        fun tick() = memory.read32(plan.tick).toLong() and 0xFFFFFFFFL
        fun advanced(before: Long) { check(((tick() - before) and 0xFFFFFFFFL) in 1..0x7FFFFFFFL) { "Tick не продвинулся" } }
        return try {
            check(write.halt().halted)
            stage = "disable_source"
            write.write32(t.base, 0); write.write32(t.base + 0x0C, 0); write.write32(t.base + 0x10, 0)
            write.write32(0xE000E180 + bank, bit); write.write32(0xE000E280 + bank, bit)
            verify(t.base, 0, 1); verify(t.base + 0x0C, 0); verify(iser, 0, bit); verify(ispr, 0, bit)
            stage = "drain_handler"
            drain()
            stage = "quiesce"
            val before = tick()
            write.go(); sleep(250); check(write.halt().halted)
            drain(); advanced(before); quiet()
            // ТЗ RTT 2.12: producer is stopped and IRQ drained; capture failures cannot skip recovery.
            try { onQuiescent?.invoke() } catch (error: Exception) {
                captureInterrupted = Thread.interrupted() || error is InterruptedException
                captureError = error.message ?: error.javaClass.simpleName
            }
            stage = "restore"
            write.write32(0xE000ED08, plan.originalVtor); verify(0xE000ED08, plan.originalVtor)
            val rcc = (memory.read32(plan.rccEnable) and t.clockBit.inv()) or (plan.originalRcc and t.clockBit)
            write.write32(plan.rccEnable, rcc); verify(plan.rccEnable, rcc)
            stage = "resume"
            write.go()
            stage = "liveness"
            val liveTick = tick()
            sleep(250); advanced(liveTick)
            val state = write.coreState()
            check(!state.halted && !state.lockup)
            verify(active, 0, bit); verify(iser, 0, bit); verify(ispr, 0, bit)
            verify(0xE000ED08, plan.originalVtor)
            Result(true, "complete", steps, captureError = captureError)
        } catch (error: Exception) {
            runCatching { write.halt() }
            Result(false, stage, steps, error.message ?: error.javaClass.simpleName, captureError)
        } finally {
            if (captureInterrupted) Thread.currentThread().interrupt()
        }
    }
}

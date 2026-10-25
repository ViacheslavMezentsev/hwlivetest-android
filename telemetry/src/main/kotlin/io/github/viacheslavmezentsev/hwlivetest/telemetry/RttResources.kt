package io.github.viacheslavmezentsev.hwlivetest.telemetry

/** Half-open physical address range; no device access. */
data class RttMemoryRange(val start: Long, val end: Long) {
    init { require(start >= 0 && start < end && end <= 0x100000000L) }
    fun contains(other: RttMemoryRange) = other.start >= start && other.end <= end
    fun overlaps(other: RttMemoryRange) = start < other.end && other.start < end
}

/** Caller must obtain reservations from firmware configuration, never infer them from zero bytes. */
data class RttResourceDeclaration(
    val sram: RttMemoryRange,
    val reserved: RttMemoryRange,
    val image: RttMemoryRange,
    val occupied: List<RttMemoryRange>,
    val memoryReservationConfirmed: Boolean,
    val timerReservationConfirmed: Boolean,
    val timerEnabled: Boolean,
    val timerInterruptEnabled: Boolean,
    val irqEnabled: Boolean,
    val irqPending: Boolean,
    val irqActive: Boolean,
)

// ТЗ RTT 2.2. An empty error list validates declarations, NOT permission to install a stub.
object RttResources {
    fun validate(resources: RttResourceDeclaration): List<String> = buildList {
        with(resources) {
            if (!memoryReservationConfirmed) add("Область SRAM не зарезервирована прошивкой")
            if (!timerReservationConfirmed) add("Таймер не зарезервирован для RTT")
            if (!sram.contains(reserved)) add("Резерв выходит за границы SRAM")
            if (!reserved.contains(image)) add("Образ не помещается в резерв SRAM")
            if (image.start % 4 != 0L) add("Начало образа не выровнено на слово")
            if (occupied.any { it.overlaps(reserved) }) add("Резерв пересекается с кодом, данными, стеком или кучей")
            if (timerEnabled || timerInterruptEnabled) add("Таймер или источник прерывания уже включён")
            if (irqEnabled || irqPending || irqActive) add("IRQ таймера занят")
        }
    }
}

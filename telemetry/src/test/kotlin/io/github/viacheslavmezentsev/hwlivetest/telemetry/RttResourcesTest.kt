package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class RttResourcesTest {
    private val valid = RttResourceDeclaration(
        RttMemoryRange(0x20000000, 0x20020000),
        RttMemoryRange(0x2001C000, 0x2001E000),
        RttMemoryRange(0x2001C000, 0x2001D800),
        listOf(RttMemoryRange(0x20000000, 0x2001C000), RttMemoryRange(0x2001E000, 0x20020000)),
        true, true, false, false, false, false, false)

    @Test fun acceptsAdjacentButNotOverlappingRanges() {
        assertTrue(RttResources.validate(valid).isEmpty())
        assertFalse(RttResources.validate(valid.copy(occupied = listOf(RttMemoryRange(0x2001D000, 0x20020000)))).isEmpty())
    }

    @Test fun requiresBothExplicitReservations() {
        assertFalse(RttResources.validate(valid.copy(memoryReservationConfirmed = false)).isEmpty())
        assertFalse(RttResources.validate(valid.copy(timerReservationConfirmed = false)).isEmpty())
    }

    @Test fun rejectsEveryBusyTimerState() {
        listOf(valid.copy(timerEnabled = true), valid.copy(timerInterruptEnabled = true),
            valid.copy(irqEnabled = true), valid.copy(irqPending = true), valid.copy(irqActive = true))
            .forEach { assertFalse(RttResources.validate(it).isEmpty()) }
    }

    @Test fun rejectsOverflowAndUnalignedImage() {
        assertFalse(RttResources.validate(valid.copy(image = RttMemoryRange(0x2001C000, 0x2001E004))).isEmpty())
        assertFalse(RttResources.validate(valid.copy(image = RttMemoryRange(0x2001C001, 0x2001D800))).isEmpty())
        assertFalse(RttResources.validate(valid.copy(sram = RttMemoryRange(0x20000000, 0x2001D000))).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsWrappedAddressRange() {
        RttMemoryRange(0xFFFFFFFCL, 0x100000004L)
    }
}

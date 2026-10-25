package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm
import org.junit.Assert.*
import org.junit.Test

class RttDeviceProfileTest {
    @Test fun f1UsesItsOwnRccAndOnlyCommonTimer() {
        for (id in listOf(0x410,0x412,0x414,0x418,0x420,0x428,0x430)) {
            val p = RttDeviceProfile.require(id)
            assertEquals(0x4002101CL, p.rccEnable)
            assertNull(p.timpreRegister)
            assertEquals(listOf("TIM2"), p.timers.map { it.name })
            assertEquals(24_000_000L, p.timerClockHz(24_000_000, 4 shl 8))
            assertEquals(12_000_000L, p.timerClockHz(24_000_000, 5 shl 8))
        }
    }
    @Test fun f4ClockAndRamVariantsExcludeCcm() {
        val f407 = RttDeviceProfile.require(0x413)
        assertNull(f407.timpreRegister)
        assertEquals(84_000_000L, f407.timerClockHz(168_000_000, 5 shl 10))
        val f429 = RttDeviceProfile.require(0x419)
        assertEquals(180_000_000L, f429.timerClockHz(180_000_000, 5 shl 10, 1 shl 24))
        assertEquals(90_000_000L, f429.timerClockHz(180_000_000, 6 shl 10, 1 shl 24))
        assertEquals(0x20050000L, RttDeviceProfile.require(0x434).ramEnd)
        assertEquals(0x20005000L, RttDeviceProfile.require(0x410).ramEnd)
        assertNull(RttDeviceProfile.find(0x458)) // TIM5 mux requires a separate profile.
        assertNull(RttDeviceProfile.find(0x411)) // F2 is not implicitly accepted.
    }
}

package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.*
import org.junit.Test

class LongWindowTest {
    // TC-44: ТЗ прошивки 5.17 — пять минут при 20 Гц после оборота кольца.
    @Test fun fiveMinutesRemainOrderedAfterRingWrap() {
        val series = TimeSeries(FieldSampler.DEFAULT_CAPACITY)
        for (i in 0..9000) series.add(i * 50L, 32.0 + (i % 10) * 0.01)
        val window = series.window(150_000, 450_000)
        assertEquals(6001, window.size)
        assertEquals(150_000L, window.times.first())
        assertEquals(450_000L, window.times.last())
        assertTrue(window.times.toList().zipWithNext().all { (a, b) -> b - a == 50L })
        assertEquals(32.0, window.min!!, 0.0001)
        assertEquals(32.09, window.max!!, 0.0001)
    }

    @Test fun newSessionRemovesOldExtremesFromEveryChannel() {
        val fields = AddressesFile.parse("""{"fields":[
          {"topic":"/a","kind":"float32","address_hex":"0x20000000"},
          {"topic":"/b","kind":"float32","address_hex":"0x20000004"}
        ]}""").fields
        val sampler = FieldSampler(fields)
        sampler.sample(0, read = { if (it == 0x20000000L) (-127861064f).toRawBits() else (4.72e12f).toRawBits() })
        val references = fields.map { sampler.series(it.topic)!! }
        sampler.clear()
        for (i in 1..3000) sampler.sample(i * 100L, read = { 32.2f.toRawBits() })
        references.forEach { series ->
            val window = series.window(0, 300_000)
            assertEquals(3000, window.size)
            assertEquals(32.2, window.min!!, 0.0001)
            assertEquals(32.2, window.max!!, 0.0001)
        }
    }
}

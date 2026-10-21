package io.github.viacheslavmezentsev.hwlivetest.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    // Пример по формату addresses.json (schema 2); пути и хэш вымышленные.
    private val sample = """
        {
          "schema": 2,
          "elf": "build/f411ce-debug/firmware.elf",
          "elf_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "svd": null,
          "fields": [
            {"topic": "/diag/sensor/batt_v", "message": "std_msgs/Float32", "kind": "float32",
             "address": 536874076, "address_hex": "0x20000C5C",
             "expr": "(char*)&sensors + (long)&((SensorManager*)0)->cached_data.voltage_batt"},
            {"topic": "/diag/sensor/task_count", "message": "std_msgs/UInt32", "kind": "uint32",
             "address": 536874096, "address_hex": "0x20000C70", "expr": "x"},
            {"topic": "/diag/uptime_s", "message": "std_msgs/UInt32", "kind": "uint32_seconds",
             "address_hex": "0x20014930", "expr": "&uwTick", "min": 0, "max": 100000},
            {"topic": "/diag/gpioa_pin5", "message": "std_msgs/UInt32", "kind": "uint32",
             "address": 1073872912, "expr": "svd:GPIOA.IDR.IDR5", "shift": 5, "mask": 32, "size_bits": 32},
            {"topic": "/diag/new_kind", "message": "std_msgs/Int32", "kind": "int16",
             "address": 536874100, "expr": "y", "future_key": [1, 2, {"a": null}]}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFieldsAndIgnoresUnknownKeys() {
        val f = AddressesFile.parse(sample)
        assertEquals(5, f.fields.size)
        val batt = f.fields[0]
        assertEquals("/diag/sensor/batt_v", batt.topic)
        assertEquals("batt_v", batt.shortName)
        assertEquals(FieldKind.FLOAT32, batt.kind)
        assertEquals(0x20000C5CL, batt.address)
        assertEquals("std_msgs/Float32", batt.message)
        assertEquals(0x20014930L, f.fields[2].address) // только address_hex
        assertEquals(100000.0, f.fields[2].max!!, 0.0)
        assertEquals(5, f.fields[3].shift)
        assertEquals(FieldKind.UINT32, f.fields[4].kind)
        assertTrue(f.warnings.single().contains("int16"))
    }

    @Test
    fun rejectsBrokenFiles() {
        for (bad in listOf("[]", "{}", "{\"fields\": 1}", "{\"fields\": [", "nope")) {
            try {
                AddressesFile.parse(bad)
                throw AssertionError("ожидалась ошибка для $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun skipsFieldsWithoutTopicOrAddressAndDuplicates() {
        val f = AddressesFile.parse(
            """{"fields":[{"topic":"/a","address":4},{"address":8},{"topic":"/b"},{"topic":"/a","address":12}]}""",
        )
        assertEquals(listOf("/a"), f.fields.map { it.topic })
        assertEquals(3, f.warnings.size)
    }

    @Test
    fun jsonEscapesAndNumbers() {
        val v = Json.parse("""{"s":"a\"b\\cA\n","n":-12,"d":1.5e3,"t":true,"z":null}""") as Map<*, *>
        assertEquals("a\"b\\cA\n", v["s"])
        assertEquals(-12L, v["n"])
        assertEquals(1500.0, v["d"])
        assertEquals(true, v["t"])
        assertTrue(v.containsKey("z") && v["z"] == null)
    }

    @Test
    fun decodesKinds() {
        val fl = Field("/f", FieldKind.FLOAT32, 0)
        assertEquals(12.5, fl.decode(java.lang.Float.floatToIntBits(12.5f)), 0.0)
        assertEquals("12.5", fl.format(12.5))
        assertEquals("3.3", fl.format(3.3f.toDouble()))
        assertEquals("0.0123", fl.format(0.0123))
        assertEquals(4294967295.0, Field("/u", FieldKind.UINT32, 0).decode(-1), 0.0)
        assertEquals(-1.0, Field("/i", FieldKind.INT32, 0).decode(-1), 0.0)
        assertEquals("3725 с (1:02:05)", Field("/s", FieldKind.UINT32_SECONDS, 0).format(3725.0))
        val bit = Field("/b", FieldKind.UINT32, 0, shift = 5, mask = 32)
        assertEquals(1.0, bit.decode(0x20), 0.0)
        assertEquals(0.0, bit.decode(0x1F), 0.0)
    }

    @Test
    fun memoryMapChecks() {
        assertNull(MemoryMap.check(0x20000C5CL, 512, 128).warning)
        assertTrue(MemoryMap.check(0x20020000L, 512, 128).warning!!.contains("SRAM"))
        assertTrue(MemoryMap.check(0x08080000L, 512, 128).warning!!.contains("Flash"))
        assertNull(MemoryMap.check(0x0807FFFCL, 512, 128).warning)
        assertNull(MemoryMap.check(0x20020000L, null, null).warning)
        assertFalse(MemoryMap.check(0x20000002L, null, null).readable)
        assertEquals(MemoryMap.Region.PERIPHERAL, MemoryMap.check(0x40020010L, null, null).region)
        assertTrue(MemoryMap.check(0x40020010L, null, null).warning != null)
    }

    @Test
    fun samplerReadsSharedAddressOnceAndAppliesRange() {
        val fields = listOf(
            Field("/ms", FieldKind.UINT32, 0x20000000),
            Field("/s", FieldKind.UINT32_SECONDS, 0x20000000, max = 10.0),
            Field("/bad", FieldKind.FLOAT32, 0x20000004),
        )
        val sampler = FieldSampler(fields, capacity = 4)
        var reads = 0
        val readings = sampler.sample(100, read = { a ->
            reads++
            if (a == 0x20000004L) throw IllegalStateException("AP FAULT") else 42
        })
        assertEquals(4, reads) // блок из 2 слов не прочитался → по одному слову
        assertEquals(42.0, readings[0].value!!, 0.0)
        assertTrue(readings[1].outOfRange)
        assertEquals("AP FAULT", readings[2].error)
        assertEquals(1, sampler.series("/ms")!!.count())
        assertEquals(0, sampler.series("/s")!!.count()) // вне max — в ряд не попало
        sampler.sample(200, read = { 7 }, only = listOf("/ms"))
        assertEquals(2, sampler.series("/ms")!!.count())
    }

    @Test
    fun timeSeriesRingAndWindow() {
        val ts = TimeSeries(3)
        for (k in 1..5) ts.add(k * 1000L, k.toDouble())
        assertEquals(3, ts.count())
        val w = ts.window(4000)
        assertEquals(listOf(4000L, 5000L), w.times.toList())
        assertEquals(4.5, w.mean!!, 0.0)
        assertEquals(5.0, ts.lastValue()!!, 0.0)
    }

    @Test
    fun niceAxis() {
        val a = ChartScale.yAxis(3.21, 3.29)
        assertTrue(a.min <= 3.21 && a.max >= 3.29)
        assertTrue(a.ticks.size in 3..12)
        val flat = ChartScale.yAxis(5.0, 5.0)
        assertTrue(flat.min < 5.0 && flat.max > 5.0)
        val zero = ChartScale.yAxis(0.0, 0.0)
        assertTrue(zero.min <= -1.0 && zero.max >= 1.0)
        assertEquals(0.0, ChartScale.yAxis(-0.5, 0.5).ticks.first { it >= 0 }, 0.0)
        assertEquals(5_000L, ChartScale.timeStepMs(30_000))
        assertEquals(60_000L, ChartScale.timeStepMs(300_000))
        assertEquals("3.25", ChartScale.label(3.25, 0.05))
        assertEquals("100", ChartScale.label(100.0, 20.0))
        assertEquals("0.0", ChartScale.label(0.0, 0.5))
    }

    @Test
    fun readPlanMergesNeighboursOnlyInMemory() {
        // Поля из примера addresses.json: 0x20000C54..0x20000C8C — один блок.
        val addrs = (0x20000C54L..0x20000C8CL step 4).toList()
        assertEquals(listOf(ReadPlan.Block(0x20000C54L, 15)), ReadPlan.blocks(addrs))
        // Разрыв больше 4 слов — два блока; периферия — по одному слову.
        val b = ReadPlan.blocks(listOf(0x20000000L, 0x20000100L, 0x40020010L, 0x40020014L))
        assertEquals(4, b.size)
        // Граница 1 КБ не пересекается.
        assertEquals(2, ReadPlan.blocks(listOf(0x200003FCL, 0x20000400L)).size)
        // Невыровненные адреса не читаются.
        assertTrue(ReadPlan.blocks(listOf(0x20000002L)).isEmpty())
    }

    @Test
    fun samplerUsesBlocksAndFallsBackToWords() {
        val fields = listOf(
            Field("/a", FieldKind.UINT32, 0x20000000),
            Field("/b", FieldKind.UINT32, 0x20000008),
            Field("/c", FieldKind.UINT32, 0x2000000C),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val s = FieldSampler(fields)
        val r = s.sample(0, readBlock = { a, n ->
            calls += a to n
            IntArray(n) { (a + 4L * it).toInt() and 0xFF }
        })
        assertEquals(listOf(0x20000000L to 4), calls)
        assertEquals(listOf(0.0, 8.0, 12.0), r.map { it.value })

        calls.clear()
        val r2 = s.sample(1, readBlock = { a, n ->
            calls += a to n
            if (n > 1 || a == 0x20000008L) throw IllegalStateException("AP FAULT") else intArrayOf(7)
        })
        assertEquals(listOf(0x20000000L to 4, 0x20000000L to 1, 0x20000008L to 1, 0x2000000CL to 1), calls)
        assertEquals(7.0, r2[0].value!!, 0.0)
        assertEquals("AP FAULT", r2[1].error)
    }

    @Test
    fun axisTrackerKeepsAxisWhileDataFits() {
        val t = AxisTracker()
        val a1 = t.update(3.2916, 3.2925)
        // Новое окно чуть уже — ось та же (раньше она пересчитывалась и линия прыгала).
        assertEquals(a1, t.update(3.2918, 3.2924))
        // Выход за край — ось расширяется.
        val a2 = t.update(3.2910, 3.2925)
        assertTrue(a2.min <= 3.2910)
        // Данные сжались сильно — ось сужается.
        val a3 = t.update(3.29200, 3.29202)
        assertTrue(a3.max - a3.min < a2.max - a2.min)
    }

    @Test
    fun ownScaleOnlyForDifferentMagnitudes() {
        assertFalse(needsOwnScale(listOf(3.292, 3.291)))
        assertTrue(needsOwnScale(listOf(31.9, 0.0075)))
        assertFalse(needsOwnScale(listOf(31.9)))
    }
}


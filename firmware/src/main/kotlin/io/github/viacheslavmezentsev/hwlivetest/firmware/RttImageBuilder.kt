package io.github.viacheslavmezentsev.hwlivetest.firmware

import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttDoctor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttInstaller

/** ТЗ RTT 2.9, 2.11. F411 short trial, selectable sample rate; only aligned SRAM words. */
object RttImageBuilder {
    val sampleRates = listOf(100, 500, 1000)
    data class Built(val image: RttInstaller.Image, val control: Long, val buffer: Long,
        val bufferBytes: Int, val counters: Long)

    // timer_hook.S from stm32-diag-record 1.8.0 (MIT), revision 376225b.
    // Thumb-2 code, params at +120; the original source and license are in tools/rtt/.
    private val code = ("F0B51DA73868002101603C692068FE69366800B9A66001302060E6607A68556A916AFB68" +
        "491B013900D5C918B869814203DA6068013060601BE0B86828444DF2A61101802168418046600830" +
        "7B691A68043332B119680968016004300433013AF7E7B8692D18FB689D4200DBED1A7A685562BDE8F0407047")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun build(base: Long, available: Long, fields: List<Long>, tick: Long, vectorBytes: Int,
        timer: RttDoctor.Timer, clockHz: Long, sampleHz: Int = 100): Built {
        require(sampleHz in sampleRates) { "Выберите 100, 500 или 1000 Гц" }
        require(fields.size in 1..6 && timer in RttDoctor.timers)
        require(base % 16 == 0L && available > 0 && base >= 0x20000000 && base + available <= 0x20020000)
        require(clockHz in 200..100_000_000 && clockHz % sampleHz == 0L)
        val layout = RttReferenceLayout.calculate(base, fields.size, vectorBytes)
        require(layout.bytes <= available) { "Образ RTT не помещается до запаса стека" }
        val end = base + layout.bytes
        (fields + tick).forEach { address ->
            require(address in 0x20000000L..0x2001FFFCL && address % 4 == 0L && address !in base until end) {
                "RTT допускает только выровненные слова SRAM вне образа"
            }
        }
        val divider = clockHz / sampleHz
        val psc = (1L..65536).firstOrNull { divider % it == 0L && divider / it in 2..65536 }
            ?: error("Нельзя получить ровно $sampleHz Гц")
        val buffer = base + 48
        val counters = buffer + layout.bufferBytes
        val table = counters + 16
        val stub = table + 4 + 4 * fields.size
        val data = ByteArray(layout.bytes.toInt())
        fun word(address: Long, value: Long) {
            repeat(4) { data[(address - base).toInt() + it] = (value ushr (8 * it)).toByte() }
        }
        "DIAG RTT".toByteArray(Charsets.US_ASCII).copyInto(data)
        word(base + 16, 1)
        word(base + 28, buffer); word(base + 32, layout.bufferBytes.toLong())
        word(table, fields.size.toLong())
        fields.forEachIndexed { i, address -> word(table + 4 + i * 4, address) }
        check(code.size == 120)
        code.copyInto(data, (stub - base).toInt())
        listOf(timer.base + 0x10, base, buffer, layout.bufferBytes.toLong(), counters, table,
            (8 + fields.size * 4).toLong(), tick).forEachIndexed { i, value -> word(stub + 120 + i * 4, value) }
        // Installer copies live vectors, then replaces only the selected timer entry.
        return Built(RttInstaller.Image(base, data, layout.vectors, vectorBytes, stub, stub + 120,
            tick, timer, (psc - 1).toInt(), (divider / psc - 1).toInt()), base, buffer, layout.bufferBytes, counters)
    }
}

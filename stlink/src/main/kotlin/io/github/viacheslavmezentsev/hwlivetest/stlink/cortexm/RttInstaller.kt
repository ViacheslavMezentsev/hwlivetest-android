package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite

/** ТЗ RTT 2.7. Internal engine, not exposed in the phone UI. Caller confirms reservations. */
class RttInstaller(private val memory: DebugMemory, private val write: TargetWrite) {
    data class Image(val base: Long, val bytes: ByteArray, val vectors: Long, val vectorBytes: Int,
        val stubStart: Long, val stubEnd: Long, val tick: Long, val timer: RttDoctor.Timer,
        val prescaler: Int, val reload: Int)
    data class Result(val started: Boolean, val recovery: RttTeardown.Plan?, val stage: String, val error: String? = null)

    fun install(image: Image, deviceId: Int): Result {
        val data = image.bytes.copyOf()
        val end = image.base + data.size
        require(deviceId == 0x431 && image.timer in RttDoctor.timers)
        require(data.isNotEmpty() && data.size % 4 == 0 && image.base % 4 == 0L)
        require(image.base >= 0x20000000 && end <= 0x20020000)
        require(image.stubStart >= image.base && image.stubEnd <= end && image.stubEnd > image.stubStart)
        require(image.stubStart % 2 == 0L)
        require(image.tick in 0x20000000L..0x2001FFFCL && image.tick % 4 == 0L && image.tick !in image.base until end)
        require(image.vectorBytes in 268..1024 && image.vectorBytes % 4 == 0)
        var alignment = 512
        while (alignment < image.vectorBytes) alignment *= 2
        require(image.vectors % alignment == 0L && image.vectors >= image.base && image.vectors + image.vectorBytes <= end)
        require(image.stubEnd <= image.vectors || image.vectors + image.vectorBytes <= image.stubStart)
        require(image.prescaler in 0..65535 && image.reload in 1..65535)
        var recovery: RttTeardown.Plan? = null
        var changed = false
        var stage = "preflight"
        return try {
            val state = write.coreState()
            check(!state.halted && !state.lockup) { "RTT requires running firmware" }
            stage = "halt"
            changed = true
            check(write.halt().halted)
            stage = "resources"
            val observation = RttDoctor.inspectF411(memory, deviceId).first { it.timer == image.timer }
            check(observation.candidate) { "Timer became busy before installation" }
            val vtor = memory.read32(0xE000ED08)
            val source = vtor.toLong() and 0xFFFFFFFFL
            check(source == 0L || (source >= 0x08000000 && source + image.vectorBytes <= 0x08080000) ||
                (source >= 0x20000000 && source + image.vectorBytes <= 0x20020000)) { "Unknown vector source" }
            check(source + image.vectorBytes <= image.base || source >= end) { "Image overlaps current vectors" }
            val rcc = memory.read32(0x40023840)
            recovery = RttTeardown.Plan(image.timer, image.stubStart, image.stubEnd, image.tick, vtor, rcc)
            // Copy the current live table; only replace our IRQ, never trust an ELF vector snapshot.
            val vectors = memory.readBlock(source, image.vectorBytes / 4)
            check(vectors.size == image.vectorBytes / 4)
            vectors[16 + image.timer.irq] = (image.stubStart or 1).toInt()
            vectors.forEachIndexed { i, word -> repeat(4) { j -> data[(image.vectors - image.base).toInt() + i * 4 + j] = (word ushr (8 * j)).toByte() } }
            stage = "upload"
            write.writeBytes(image.base, data)
            var offset = 0
            while (offset < data.size) {
                val n = minOf(1024, data.size - offset)
                val words = memory.readBlock(image.base + offset, n / 4)
                check(words.size == n / 4)
                words.forEachIndexed { i, word -> repeat(4) { j -> check(data[offset + i * 4 + j] == (word ushr (j * 8)).toByte()) { "RTT image readback failed" } } }
                offset += n
            }
            stage = "configure"
            val timer = image.timer
            write.write32(0x40023840, rcc or timer.clockBit)
            check(memory.read32(0x40023840) and timer.clockBit != 0)
            write.write32(timer.base, 0); write.write32(timer.base + 0x0C, 0)
            write.write32(timer.base + 0x28, image.prescaler); write.write32(timer.base + 0x2C, image.reload)
            write.write32(timer.base + 0x14, 1); write.write32(timer.base + 0x10, 0)
            write.write32(0xE000ED08, image.vectors.toInt())
            check(memory.read32(0xE000ED08) == image.vectors.toInt())
            val bank = 4L * (timer.irq / 32); val bit = 1 shl (timer.irq % 32)
            write.write32(0xE000E280 + bank, bit)
            write.write32(timer.base + 0x0C, 1); write.write32(0xE000E100 + bank, bit)
            write.write32(timer.base, 1)
            stage = "start"
            check(!write.go().halted)
            Result(true, recovery, "running")
        } catch (error: Exception) {
            if (changed) runCatching { write.halt() }
            // Retain recovery even after a partial upload; the session controller must act on it.
            Result(false, recovery, stage, error.message ?: error.javaClass.simpleName)
        }
    }
}

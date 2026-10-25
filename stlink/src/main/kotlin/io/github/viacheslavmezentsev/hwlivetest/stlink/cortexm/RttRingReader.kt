package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite

/** ТЗ RTT 2.6. One consumer, non-overwriting producer; only the host owns RdOff. */
class RttRingReader(private val memory: DebugMemory, private val write: TargetWrite,
    private val control: Long, private val buffer: Long, private val size: Int) {
    init {
        require(control >= 0x20000000 && control + 48 <= 0x20020000 && control % 4 == 0L)
        require(size in 16..65536 && size % 4 == 0 && buffer % 4 == 0L)
        require(buffer >= 0x20000000 && buffer + size <= 0x20020000)
        require(control + 48 <= buffer || buffer + size <= control)
    }
    private var expectedRead: Int? = null
    private var failed = false

    fun poll(maxBytes: Int = 4096): ByteArray {
        check(!failed) { "RTT reader failed; start a new session" }
        require(maxBytes in 4..65536 && maxBytes % 4 == 0)
        try {
            val header = memory.readBlock(control, 12)
            check(header.size == 12)
            check(header[0] == 0x47414944 && header[1] == 0x54545220 && header[2] == 0 && header[3] == 0) { "RTT ID changed" }
            check(header[4] == 1 && header[5] == 0 && header[6] == 0 && header[11] == 0) { "Unsupported RTT descriptor" }
            check(header[7].toLong() and 0xFFFFFFFFL == buffer && header[8] == size) { "RTT buffer changed" }
            val wr = header[9]; val rd = header[10]
            check(wr in 0 until size && rd in 0 until size && wr % 4 == 0 && rd % 4 == 0) { "Invalid RTT offsets" }
            check(expectedRead == null || expectedRead == rd) { "RTT reader position changed externally" }
            val count = minOf((wr - rd + size) % size, maxBytes)
            if (count == 0) { expectedRead = rd; return byteArrayOf() }
            val result = ByteArray(count)
            var copied = 0
            while (copied < count) {
                val offset = (rd + copied) % size
                val n = minOf(count - copied, size - offset, 1024)
                val words = memory.readBlock(buffer + offset, n / 4)
                check(words.size == n / 4) { "Short RTT read" }
                words.forEachIndexed { index, word ->
                    repeat(4) { byte -> result[copied + index * 4 + byte] = (word ushr (byte * 8)).toByte() }
                }
                copied += n
            }
            // Commit only after the whole snapshot was copied; do not chase the producer.
            val next = (rd + count) % size
            write.write32(control + 40, next)
            check(memory.read32(control + 40) == next) { "RTT read-position acknowledgement failed" }
            expectedRead = next
            return result
        } catch (error: Exception) {
            failed = true // an acknowledgement may have reached MCU even if USB returned an error
            throw error
        }
    }
}

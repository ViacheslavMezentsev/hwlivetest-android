package io.github.viacheslavmezentsev.hwlivetest.telemetry

/** Experimental timer stream, not a SEGGER RTT control-block reader. */
data class RttFrame(val sequence: Int, val tickMs: Long, val words: List<Long>)

data class RttStreamStats(
    val frames: Long = 0,
    val discardedBytes: Long = 0,
    val missingEstimate: Long = 0,
    val duplicates: Long = 0,
    val sequenceDiscontinuities: Long = 0,
    val repeatedTicks: Long = 0,
    val tickWraps: Long = 0,
    val tickBackwards: Long = 0,
)

// ТЗ RTT 1.1–1.3. Wire facts: stm32-diag-record 1.8.0, revision 376225b.
class RttFrameDecoder(fieldCount: Int) {
    init { require(fieldCount in 1..64) }
    private val buffer = ByteArray(8 + 4 * fieldCount)
    private var used = 0
    private var previous: RttFrame? = null
    var stats = RttStreamStats()
        private set
    val pendingBytes: Int get() = used

    /** Call on one reader thread. Internal storage is bounded to one frame. */
    fun feed(bytes: ByteArray): List<RttFrame> {
        val result = mutableListOf<RttFrame>()
        for (byte in bytes) {
            if (used == 0 && byte != 0xA6.toByte()) {
                stats = stats.copy(discardedBytes = stats.discardedBytes + 1)
                continue
            }
            if (used == 1 && byte != 0xD1.toByte()) {
                val keep = byte == 0xA6.toByte()
                stats = stats.copy(discardedBytes = stats.discardedBytes + if (keep) 1 else 2)
                used = if (keep) 1 else 0
                continue
            }
            buffer[used++] = byte
            if (used == buffer.size) {
                val frame = RttFrame(unsigned(2, 2).toInt(), unsigned(4, 4),
                    (8 until buffer.size step 4).map { unsigned(it, 4) })
                account(frame)
                result.add(frame)
                used = 0
            }
        }
        return result
    }

    /** Start a new acquisition explicitly; never compare counters across sessions. */
    fun reset() {
        used = 0
        previous = null
        stats = RttStreamStats()
    }

    private fun unsigned(offset: Int, size: Int): Long =
        (0 until size).fold(0L) { n, i -> n or ((buffer[offset + i].toLong() and 255) shl (8 * i)) }

    private fun account(frame: RttFrame) {
        var next = stats.copy(frames = stats.frames + 1)
        previous?.let { old ->
            val sequenceStep = (frame.sequence - old.sequence) and 0xFFFF
            next = when {
                sequenceStep == 0 -> next.copy(duplicates = next.duplicates + 1)
                sequenceStep >= 0x8000 -> next.copy(sequenceDiscontinuities = next.sequenceDiscontinuities + 1)
                else -> next.copy(missingEstimate = next.missingEstimate + sequenceStep - 1)
            }
            val tickStep = (frame.tickMs - old.tickMs) and 0xFFFFFFFFL
            next = when {
                tickStep == 0L -> next.copy(repeatedTicks = next.repeatedTicks + 1)
                tickStep >= 0x80000000L -> next.copy(tickBackwards = next.tickBackwards + 1)
                frame.tickMs < old.tickMs -> next.copy(tickWraps = next.tickWraps + 1)
                else -> next
            }
        }
        stats = next
        previous = frame
    }
}

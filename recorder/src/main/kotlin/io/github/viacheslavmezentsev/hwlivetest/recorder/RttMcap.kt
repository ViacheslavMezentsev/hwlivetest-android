package io.github.viacheslavmezentsev.hwlivetest.recorder

import io.github.viacheslavmezentsev.hwlivetest.telemetry.*
import java.io.InputStream
import java.io.OutputStream

/** ТЗ RTT 2.10. Convert after teardown; MCU tick spacing, approximate host epoch. */
object RttMcap {
    const val SEQ = "/_diag/rtt/seq"
    const val TICK = "/_diag/rtt/tick_ms"
    data class Result(val frames: Long, val messages: Long, val pendingBytes: Int)

    fun convert(input: InputStream, output: OutputStream, fields: List<Field>, firstFrameWallMs: Long, onProgress: (Long) -> Unit = {}): Result {
        require(fields.size in 1..6 && fields.map { it.topic }.distinct().size == fields.size)
        require(fields.none { it.topic == SEQ || it.topic == TICK }) { "Reserved RTT topic" }
        require(firstFrameWallMs in 0..Long.MAX_VALUE / 1_000_000)
        val decoder = RttFrameDecoder(fields.size)
        var convertedFrames = 0L
        var previousTick: Long? = null
        var elapsed = 0L
        var result: Result? = null
        McapWriter(output, "hwlivetest RTT").use { writer ->
            val channels = fields.map { field -> writer.addChannel(field.topic,
                Ros1Type.byName(field.message) ?: when (field.kind) {
                    FieldKind.FLOAT32 -> Ros1Type.FLOAT32
                    FieldKind.INT32 -> Ros1Type.INT32
                    else -> Ros1Type.UINT32
                }) }
            val seq = writer.addChannel(SEQ, Ros1Type.UINT32)
            val tick = writer.addChannel(TICK, Ros1Type.UINT32)
            val bytes = ByteArray(4096)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                for (frame in decoder.feed(bytes.copyOf(n))) {
                    convertedFrames++
                    if (convertedFrames % 4096 == 0L) onProgress(convertedFrames)
                    previousTick?.let {
                        val delta = (frame.tickMs - it) and 0xFFFFFFFFL
                        check(delta < 0x80000000L) { "RTT tick moved backwards" }
                        elapsed = Math.addExact(elapsed, delta)
                    }
                    previousTick = frame.tickMs
                    val time = Math.multiplyExact(Math.addExact(firstFrameWallMs, elapsed), 1_000_000L)
                    // Always preserve evidence, even when every user value is filtered.
                    writer.write(seq, time, frame.sequence.toDouble())
                    writer.write(tick, time, frame.tickMs.toDouble())
                    fields.forEachIndexed { i, field ->
                        val value = field.decode(frame.words[i].toInt())
                        if (value.isFinite() && field.inRange(value)) writer.write(channels[i], time, value)
                    }
                }
            }
            check(decoder.stats.discardedBytes == 0L) { "RTT stream contains invalid bytes" }
            result = Result(decoder.stats.frames, writer.messageCount, decoder.pendingBytes)
        }
        return result!!
    }
}

package io.github.viacheslavmezentsev.hwlivetest.recorder

import io.github.viacheslavmezentsev.hwlivetest.telemetry.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RttMcapTest {
    private val fields = listOf(Field("/test/value", FieldKind.FLOAT32, 0x20000000))
    private fun frame(seq: Int, tick: Long, value: Float) = ByteBuffer.allocate(12)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(0xD1A6.toShort()).putShort(seq.toShort())
        .putInt(tick.toInt()).putFloat(value).array()

    @Test fun rolloverRepeatedTickAndFilteredValue() {
        val data = frame(65535, 0xFFFFFFFA, Float.NaN) + frame(0, 4, 1.5f) + frame(1, 4, 2.5f)
        val output = ByteArrayOutputStream()
        val result = RttMcap.convert(data.inputStream(), output, fields, 1_700_000_000_000)
        assertEquals(3L, result.frames); assertEquals(8L, result.messages)
        assertEquals(0, result.pendingBytes)
        // Read by the independent official Python MCAP reader during local verification.
        File("build/rtt-test.mcap").apply { parentFile.mkdirs(); writeBytes(output.toByteArray()) }
    }

    @Test fun partialTailKeepsCompleteFrames() {
        val data = frame(1, 1, 2f) + byteArrayOf(0xA6.toByte())
        val result = RttMcap.convert(data.inputStream(), ByteArrayOutputStream(), fields, 1000)
        assertEquals(1L, result.frames); assertEquals(1, result.pendingBytes)
    }

    @Test fun backwardsTickFailsInsteadOfInventingTime() {
        val data = frame(1, 100, 1f) + frame(2, 90, 1f)
        try {
            RttMcap.convert(data.inputStream(), ByteArrayOutputStream(), fields, 1000)
            fail("Expected backward tick rejection")
        } catch (_: IllegalStateException) { }
    }

    @Test fun streamingAcrossSequenceAndTickWrapWithoutRetainingRecording() {
        var count = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count == 70_000) return -1
                val data = frame(count, (0xFFFFFF00L + count) and 0xFFFFFFFFL, 1.5f)
                data.copyInto(b, off); count++; return data.size
            }
        }
        var outputBytes = 0L; var progress = 0L
        val output = object : java.io.OutputStream() {
            override fun write(b: Int) { outputBytes++ }
            override fun write(b: ByteArray, off: Int, len: Int) { outputBytes += len }
        }
        val result = RttMcap.convert(input, output, fields, 1000) { progress = it }
        assertEquals(70_000L, result.frames); assertEquals(210_000L, result.messages)
        assertEquals(0, result.pendingBytes); assertTrue(outputBytes > 7_000_000)
        assertTrue(progress > 65_536)
    }

    @Test fun reservedTopicRejected() {
        try {
            RttMcap.convert(byteArrayOf().inputStream(), ByteArrayOutputStream(),
                listOf(fields.first().copy(topic = RttMcap.SEQ)), 1000)
            fail("Expected reserved topic rejection")
        } catch (_: IllegalArgumentException) { }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.fx2la

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Fx2FirmwareTest {
    /** TC-02: двоичный образ режется на порции по 4096 байт с адреса 0. */
    @Test
    fun binaryImageIsChunked() {
        val bin = ByteArray(6000) { (it % 251).toByte() }.also { it[0] = 0x02 }
        val img = Fx2Firmware.parse(bin)
        assertEquals(listOf(0, 4096), img.chunks.map { it.address })
        assertEquals(listOf(4096, 1904), img.chunks.map { it.data.size })
        assertEquals(6000, img.size)
        assertEquals(64, img.sha256.length)
    }

    /** TC-03: Intel HEX с разрывом адресов. */
    @Test
    fun hexImageKeepsSegments() {
        val hex = ":0400000002000102F7\n:0201000055AAFE\n:00000001FF\n"
        val img = Fx2Firmware.parse(hex.toByteArray())
        assertEquals(listOf(0, 0x100), img.chunks.map { it.address })
        assertArrayEquals(byteArrayOf(0x55, 0xAA.toByte()), img.chunks[1].data)
    }

    /** TC-04: образ больше ОЗУ FX2 отвергается. */
    @Test
    fun oversizedImageIsRejected() {
        try {
            Fx2Firmware.parse(ByteArray(20_000) { 2 })
            fail()
        } catch (e: Fx2Firmware.BadImage) {
            assertTrue(e.message!!.contains("ОЗУ"))
        }
        try {
            Fx2Firmware.parse(":020000044000BA\n:0100000000FF\n:00000001FF\n".toByteArray())
            fail()
        } catch (_: Fx2Firmware.BadImage) {
        }
    }
}

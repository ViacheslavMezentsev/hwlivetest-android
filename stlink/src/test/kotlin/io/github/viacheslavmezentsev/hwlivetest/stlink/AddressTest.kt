package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressTest {
    @Test
    fun parsesHexForms() {
        assertEquals(0xE000ED00L, Address.parse("E000ED00"))
        assertEquals(0xE000ED00L, Address.parse("0xe000ed00"))
        assertEquals(0xE000ED00L, Address.parse(" E000_ED00 "))
        assertEquals(0x20000000L, Address.parse("20000000"))
        assertEquals(0xFFFFFFFFL, Address.parse("FFFFFFFF"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Address.parse(""))
        assertNull(Address.parse("0x"))
        assertNull(Address.parse("123456789"))
        assertNull(Address.parse("G0000000"))
    }
}

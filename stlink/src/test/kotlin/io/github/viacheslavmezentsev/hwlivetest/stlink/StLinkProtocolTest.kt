package io.github.viacheslavmezentsev.hwlivetest.stlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StLinkProtocolTest {
    private fun padded(hex: String) = Hex.parse(hex).copyOf(StLinkProtocol.CMD_SIZE)

    @Test
    fun commandsAre16BytesWithExpectedPrefix() {
        assertArrayEquals(padded("f1 80"), StLinkProtocol.getVersion())
        assertArrayEquals(padded("fb 80"), StLinkProtocol.getVersionApiV3())
        assertArrayEquals(padded("f5"), StLinkProtocol.getCurrentMode())
        assertArrayEquals(padded("f7"), StLinkProtocol.getTargetVoltage())
        assertArrayEquals(padded("f3 07"), StLinkProtocol.dfuExit())
        assertArrayEquals(padded("f4 01"), StLinkProtocol.swimExit())
        assertArrayEquals(padded("f2 21"), StLinkProtocol.debugExit())
        assertArrayEquals(padded("f2 30 a3"), StLinkProtocol.enterSwd())
        assertArrayEquals(padded("f2 31"), StLinkProtocol.readIdCodes())
    }

    @Test
    fun readDebugRegEncodesAddressLittleEndian() {
        assertArrayEquals(padded("f2 36 00 ed 00 e0"), StLinkProtocol.readDebugReg(0xE000ED00L))
        assertArrayEquals(padded("f2 36 e0 f7 ff 1f"), StLinkProtocol.readDebugReg(0x1FFFF7E0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readDebugRegRejectsUnalignedAddress() {
        StLinkProtocol.readDebugReg(0x20000002L)
    }

    @Test
    fun readDapRegEncodesPortAndRegister() {
        assertArrayEquals(padded("f2 45 00 00 f8 00"), StLinkProtocol.readDapReg(0, 0xF8))
        assertArrayEquals(padded("f2 45 ff ff 00 00"), StLinkProtocol.readDapReg(StLinkProtocol.DP_PORT, 0))
    }

    @Test
    fun parsesV2Version() {
        val v = StLinkProtocol.parseVersion(Hex.parse("29 47 83 04 48 37"))
        assertEquals(2, v.hw)
        assertEquals(37, v.jtag)
        assertEquals(7, v.swim)
        assertNull(v.msd)
        assertEquals("V2J37S7", v.label)
        assertEquals("0483:3748", v.vidPid)
        assertTrue(v.meetsMinimum)
        assertTrue(v.supportsDapRegisterRead)
    }

    @Test
    fun parsesV21VersionWithMassStorageField() {
        val v = StLinkProtocol.parseVersion(Hex.parse("29 5a 83 04 52 37"))
        assertEquals("V2J37M26", v.label)
        assertEquals(26, v.msd)
        assertNull(v.swim)
    }

    @Test
    fun parsesV3ExtendedVersion() {
        val v = StLinkProtocol.parseVersionApiV3(Hex.parse("03 01 07 03 05 00 00 00 83 04 4f 37"))
        assertEquals(3, v.hw)
        assertEquals(7, v.jtag)
        assertEquals("V3J7M3B5S1", v.label)
        assertEquals("0483:374F", v.vidPid)
    }

    @Test
    fun oldFirmwareIsDetected() {
        val v = StLinkProtocol.parseVersion(Hex.parse("22 c0 83 04 48 37"))
        assertEquals("V2J11S0", v.label)
        assertTrue(!v.meetsMinimum)
    }

    @Test
    fun parsesVoltage() {
        val volts = StLinkProtocol.parseVoltage(Hex.parse("2c 06 00 00 70 08 00 00"))!!
        assertEquals(3.28, volts, 0.01)
        assertNull(StLinkProtocol.parseVoltage(Hex.parse("00 00 00 00 70 08 00 00")))
    }

    @Test
    fun parsesModeAndValues() {
        assertEquals(StLinkMode.DFU, StLinkProtocol.parseMode(Hex.parse("00 00")))
        assertEquals(StLinkMode.MASS, StLinkProtocol.parseMode(Hex.parse("01 00")))
        assertEquals(StLinkMode.DEBUG, StLinkProtocol.parseMode(Hex.parse("02 00")))
        assertEquals(StLinkMode.UNKNOWN, StLinkProtocol.parseMode(Hex.parse("7f 00")))
        assertEquals(0x1BA01477, StLinkProtocol.parseIdCode(Hex.parse("80 00 00 00 77 14 a0 1b 00 00 00 00")))
        assertEquals(0x411FC231, StLinkProtocol.parseRegValue(Hex.parse("80 00 00 00 31 c2 1f 41")))
    }

    @Test(expected = StLinkException::class)
    fun shortResponseIsProtocolError() {
        StLinkProtocol.parseVersion(Hex.parse("29 47 83"))
    }

    @Test
    fun knownAdapters() {
        assertEquals(StLinkGeneration.V2, StLinkIds.find(0x0483, 0x3748)?.generation)
        assertEquals(StLinkGeneration.V2_1, StLinkIds.find(0x0483, 0x374B)?.generation)
        assertEquals(StLinkGeneration.V2_1, StLinkIds.find(0x0483, 0x3752)?.generation)
        assertEquals(StLinkGeneration.V3, StLinkIds.find(0x0483, 0x374E)?.generation)
        assertEquals(StLinkGeneration.V3, StLinkIds.find(0x0483, 0x374F)?.generation)
        assertEquals(StLinkGeneration.V3, StLinkIds.find(0x0483, 0x3753)?.generation)
        assertNull(StLinkIds.find(0x0483, 0x3744)) // V1
        assertNull(StLinkIds.find(0x1234, 0x3748))
    }
}

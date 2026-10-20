package io.github.viacheslavmezentsev.hwlivetest.diag

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.CpuId
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.Stm32
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Память цели: по адресу — последовательность значений (последнее повторяется). */
private class Mem(private val seq: Map<Long, IntArray>) : DebugMemory {
    private val pos = HashMap<Long, Int>()

    override fun read32(address: Long): Int {
        val s = seq[address] ?: throw StLinkException(StLinkErrorKind.SWD_FAULT, "AP FAULT @ $address", status = 0x11)
        val i = pos.getOrDefault(address, 0)
        pos[address] = i + 1
        return s[minOf(i, s.size - 1)]
    }
}

private fun mem(vararg pairs: Pair<Long, Any>): Mem = Mem(
    pairs.associate { (a, v) ->
        a to when (v) {
            is Int -> intArrayOf(v)
            is IntArray -> v
            is Long -> intArrayOf(v.toInt())
            else -> error("тип $v")
        }
    },
)

private fun target(devId: Int, cpuid: Int, flashKb: Int?): TargetInfo {
    val part = Stm32.find(devId)
    return TargetInfo(
        cpuId = CpuId(cpuid), romBase = null, romBaseSource = "", rom = null,
        idRegisterAddress = 0xE0042000L, idRegister = devId, vendor = McuVendor.STM32,
        stm32Part = part, flashSizeAddress = null, flashSizeKb = flashKb, notes = emptyList(),
    )
}


private fun DiagReport.section(title: String) = sections.first { it.title == title }

private fun DiagReport.has(level: Level, text: String) =
    sections.flatMap { it.findings }.any { it.level == level && (text in it.title || text in it.detail) }

class DiagnosticsTest {
    private val running = 0x01000001 // S_RETIRE_ST + C_DEBUGEN

    @Test
    fun f411RunningFromFlashOnPllFromHse25() {
        val pllcfgr = 25 or (200 shl 6) or (1 shl 22) // M=25, N=200, P=2, HSE
        val m = mem(
            Diagnostics.DHCSR to running,
            Diagnostics.ICSR to intArrayOf(0, 0, 15, 0, 0),
            Diagnostics.CFSR to 0, Diagnostics.HFSR to 0,
            Diagnostics.DEMCR to 0,
            0x08000000L to 0x20020000, 0x08000004L to 0x08000199,
            0L to 0x20020000, 4L to 0x08000199,
            0x1FFF0000L to 0x20003000, 0x1FFF0004L to 0x1FFF3E01,
            Diagnostics.VTOR to 0x08000000,
            0x40023C14L to 0x0FFFAAED, 0xE0042004L to 0,
            0x40023800L to 0x03030003, 0x40023804L to pllcfgr, 0x40023808L to 0x0000000A,
            Diagnostics.SYST_CSR to 7, Diagnostics.SYST_RVR to 99999, Diagnostics.SYST_CVR to intArrayOf(50000, 40000),
        )
        val r = Diagnostics(m, target(0x431, 0x410FC241, 512), AdapterFacts("ST-Link/V2-1", "V2J45M31", 3.23), sleep = {}).run()
        assertEquals(r.text(), Level.INFO, r.level)
        assertTrue(r.summary, r.summary.contains("ядро работает"))
        assertTrue(r.summary, r.summary.contains("прошивка есть"))
        assertTrue(r.summary, r.summary.contains("PLL от HSE 100 МГц"))
        assertTrue(r.has(Level.OK, "Загрузка из Flash"))
        assertTrue(r.has(Level.OK, "HSE ≈ 25 МГц"))
        assertTrue(r.has(Level.INFO, "DEMCR.TRCENA = 0"))
    }

    @Test
    fun blankF103InLockupOnHsi() {
        val m = mem(
            Diagnostics.DHCSR to 0x00080001,
            Diagnostics.ICSR to 3, Diagnostics.CFSR to 0, Diagnostics.HFSR to 0x40000000,
            Diagnostics.DEMCR to 0,
            0x08000000L to -1, 0x08000004L to -1, 0L to -1, 4L to -1,
            0x1FFFF000L to 0x20000400, 0x1FFFF004L to 0x1FFFF021,
            Diagnostics.VTOR to 0,
            0x4002201CL to 0x03FFFFFC, 0xE0042004L to 0,
            0x40021000L to 0x00000083, 0x40021004L to 0,
            Diagnostics.SYST_CSR to 0, Diagnostics.SYST_RVR to 0, Diagnostics.SYST_CVR to 0,
        )
        val r = Diagnostics(m, target(0x410, 0x411FC231, 128), sleep = {}).run()
        assertEquals(Level.BAD, r.level)
        assertTrue(r.has(Level.BAD, "LOCKUP"))
        assertTrue(r.has(Level.WARN, "Flash стёрта"))
        assertTrue(r.has(Level.OK, "HSI = 8 МГц"))
        assertTrue(r.has(Level.INFO, "Работает от внутреннего HSI"))
        assertTrue(r.has(Level.OK, "Защиты чтения нет"))
        assertTrue(r.summary, r.summary.contains("Flash чистая"))
    }

    @Test
    fun g4StuckInHardFaultWithRdp1() {
        val pllcfgr = 2 or (3 shl 4) or (85 shl 8) or (1 shl 24) // HSI16 / 4 × 85 / 2 = 170 МГц
        val m = mem(
            Diagnostics.DHCSR to running,
            Diagnostics.ICSR to 3,
            Diagnostics.CFSR to ((1 shl 9) or (1 shl 15)), Diagnostics.HFSR to (1 shl 30),
            Diagnostics.BFAR to 0x60000000,
            Diagnostics.DEMCR to (1 shl 24),
            Diagnostics.DWT_PCSR to 0x08000400,
            // Flash не читается: RDP уровень 1
            0L to 0x20008000, 4L to 0x08000201,
            0x1FFF0000L to 0x20004000, 0x1FFF0004L to 0x1FFF1001,
            Diagnostics.VTOR to 0x08000000,
            0x40022020L to 0xFFFEF8BB.toInt(), 0xE0042004L to 0,
            0x40021000L to 0x03000500, 0x40021008L to 0x0000000F, 0x4002100CL to pllcfgr,
            Diagnostics.SYST_CSR to 7, Diagnostics.SYST_RVR to 169999, Diagnostics.SYST_CVR to intArrayOf(1, 2),
        )
        val r = Diagnostics(m, target(0x468, 0x410FC241, 128), sleep = {}).run()
        assertTrue(r.has(Level.BAD, "HardFault"))
        assertTrue(r.has(Level.WARN, "PRECISERR"))
        assertTrue(r.has(Level.WARN, "BFAR = 0x60000000"))
        assertTrue(r.has(Level.OK, "PC в Flash"))
        assertTrue(r.has(Level.BAD, "RDP уровень 1"))
        assertTrue(r.has(Level.WARN, "Flash не читается"))
        assertTrue(r.has(Level.WARN, "IWDG запускается аппаратно"))
        assertTrue(r.has(Level.OK, "PLL от HSI = 170 МГц"))
        assertTrue(r.summary, r.summary.contains("обработчике HardFault"))
    }

    @Test
    fun haltedAndResetLoop() {
        val halted = mem(Diagnostics.DHCSR to 0x00030001)
        val r1 = Diagnostics(halted, target(0x410, 0x411FC231, 128), sleep = {}).run()
        assertTrue(r1.has(Level.WARN, "Ядро остановлено"))

        val looping = mem(Diagnostics.DHCSR to intArrayOf(0x01000001, 0x03000001, 0x01000001, 0x03000001))
        val r2 = Diagnostics(looping, target(0x410, 0x411FC231, 128), sleep = {}).run()
        assertTrue(r2.text(), r2.has(Level.BAD, "сбрасывается снова и снова"))
    }

    @Test
    fun unknownFamilyKeepsGenericChecks() {
        val m = mem(Diagnostics.DHCSR to running, Diagnostics.SYST_CSR to 0, Diagnostics.SYST_RVR to 0, Diagnostics.SYST_CVR to 0)
        val t = target(0x440, 0x410CC200, 64) // F030: M0, без CFSR/PCSR
        val r = Diagnostics(m, t, sleep = {}).run()
        assertTrue(r.has(Level.SKIP, "CFSR/HFSR"))
        assertTrue(r.has(Level.SKIP, "Разбор RCC"))
        assertTrue(r.has(Level.OK, "Ядро выполняет инструкции"))
        assertEquals(7, r.sections.size)
    }

    @Test
    fun clocksDecodeF1Pll72() {
        val cfgr = (2 shl 2) or (1 shl 16) or (7 shl 18) or (0x8 shl 4) // PLL от HSE × 9, AHB/2
        val t = Stm32Clocks.decode(Stm32Clocks.Family.F1, 0x03030083, cfgr, null)
        assertEquals("PLL от HSE", t.source)
        assertEquals(9.0, t.hseFactor!!, 0.0)
        assertEquals(2, t.ahbDiv)
        assertEquals(72e6, t.sysclkFor(8e6)!!, 1.0)
        assertEquals(25e6, Stm32Clocks.roundHse(25.3e6)!!, 0.0)
        assertEquals(null, Stm32Clocks.roundHse(13e6))
    }

    @Test
    fun faultFlagNames() {
        assertEquals(listOf("PRECISERR", "UNDEFINSTR"), Diagnostics.cfsrFlags((1 shl 9) or (1 shl 16)))
        assertEquals(listOf("FORCED"), Diagnostics.hfsrFlags(1 shl 30))
        assertEquals("IRQ 21", Diagnostics.exceptionName(37))
    }
}

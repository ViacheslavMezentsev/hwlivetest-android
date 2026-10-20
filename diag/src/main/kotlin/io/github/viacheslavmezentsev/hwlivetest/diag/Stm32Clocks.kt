package io.github.viacheslavmezentsev.hwlivetest.diag

import java.util.Locale
import kotlin.math.abs

/**
 * Разбор регистров RCC STM32 F1, F4, G4 без записи в них. Частота HSE из регистров
 * не узнать, поэтому дерево частот выражено через неё: SYSCLK = HSE × [hseFactor].
 */
object Stm32Clocks {
    enum class Family(val title: String) { F1("STM32F1"), F4("STM32F4"), G4("STM32G4") }

    data class Tree(
        /** HSI, HSE или PLL (от HSI/HSE). */
        val source: String,
        val hsiOn: Boolean,
        val hsiReady: Boolean,
        val hseOn: Boolean,
        val hseReady: Boolean,
        val pllOn: Boolean,
        val pllReady: Boolean,
        /** SYSCLK, Гц, если не зависит от HSE. */
        val sysclkHz: Double?,
        /** Если SYSCLK зависит от HSE: SYSCLK = HSE × hseFactor. */
        val hseFactor: Double?,
        val ahbDiv: Int,
        /** Как получена частота, для подробностей. */
        val formula: String,
    ) {
        val usesHse: Boolean get() = hseFactor != null

        fun sysclkFor(hseHz: Double?): Double? = sysclkHz ?: hseFactor?.let { f -> hseHz?.let { it * f } }
    }

    /** Адреса регистров RCC: CR, CFGR и PLLCFGR (у F1 нет — null). */
    data class Regs(val cr: Long, val cfgr: Long, val pllcfgr: Long?)

    fun regs(f: Family): Regs = when (f) {
        Family.F1 -> Regs(0x40021000L, 0x40021004L, null)
        Family.F4 -> Regs(0x40023800L, 0x40023808L, 0x40023804L)
        Family.G4 -> Regs(0x40021000L, 0x40021008L, 0x4002100CL)
    }

    fun ahbDiv(hpre: Int): Int = if (hpre < 8) 1 else intArrayOf(2, 4, 8, 16, 64, 128, 256, 512)[hpre - 8]

    private fun bit(v: Int, n: Int) = (v ushr n) and 1 == 1

    fun decode(f: Family, cr: Int, cfgr: Int, pllcfgr: Int?): Tree {
        val hpre = (cfgr ushr 4) and 0xF
        val div = ahbDiv(hpre)
        return when (f) {
            Family.F1 -> {
                val hsi = 8e6
                val sws = (cfgr ushr 2) and 3
                val pllSrcHse = bit(cfgr, 16)
                val xtpre = bit(cfgr, 17)
                val mul = minOf(((cfgr ushr 18) and 0xF) + 2, 16)
                when (sws) {
                    0 -> tree("HSI", cr, f, hsi, null, div, "HSI 8 МГц")
                    1 -> tree("HSE", cr, f, null, 1.0, div, "HSE")
                    else -> if (pllSrcHse) {
                        val pre = if (xtpre) 2 else 1
                        tree("PLL от HSE", cr, f, null, mul.toDouble() / pre, div, "HSE / $pre × $mul")
                    } else {
                        tree("PLL от HSI", cr, f, hsi / 2 * mul, null, div, "HSI/2 × $mul")
                    }
                }
            }
            Family.F4 -> {
                val hsi = 16e6
                val sws = (cfgr ushr 2) and 3
                val p = pllcfgr ?: 0
                val m = p and 0x3F
                val n = (p ushr 6) and 0x1FF
                val pp = (((p ushr 16) and 3) + 1) * 2
                val srcHse = bit(p, 22)
                when (sws) {
                    0 -> tree("HSI", cr, f, hsi, null, div, "HSI 16 МГц")
                    1 -> tree("HSE", cr, f, null, 1.0, div, "HSE")
                    else -> {
                        val k = if (m == 0) 0.0 else n.toDouble() / m / pp
                        if (srcHse) tree("PLL от HSE", cr, f, null, k, div, "HSE / $m × $n / $pp")
                        else tree("PLL от HSI", cr, f, hsi * k, null, div, "HSI 16 МГц / $m × $n / $pp")
                    }
                }
            }
            Family.G4 -> {
                val hsi = 16e6
                val sws = (cfgr ushr 2) and 3
                val p = pllcfgr ?: 0
                val src = p and 3
                val m = ((p ushr 4) and 0xF) + 1
                val n = (p ushr 8) and 0x7F
                val r = (((p ushr 25) and 3) + 1) * 2
                when (sws) {
                    1 -> tree("HSI", cr, f, hsi, null, div, "HSI16")
                    2 -> tree("HSE", cr, f, null, 1.0, div, "HSE")
                    3 -> {
                        val k = n.toDouble() / m / r
                        when (src) {
                            3 -> tree("PLL от HSE", cr, f, null, k, div, "HSE / $m × $n / $r")
                            2 -> tree("PLL от HSI", cr, f, hsi * k, null, div, "HSI16 / $m × $n / $r")
                            else -> tree("PLL без источника", cr, f, null, null, div, "PLLSRC = $src")
                        }
                    }
                    else -> tree("неизвестно (SWS = 0)", cr, f, null, null, div, "SWS = 0")
                }
            }
        }
    }

    private fun tree(source: String, cr: Int, f: Family, sys: Double?, hseFactor: Double?, div: Int, formula: String): Tree {
        val hsiOnBit = if (f == Family.G4) 8 else 0
        val hsiRdyBit = if (f == Family.G4) 10 else 1
        return Tree(
            source = source,
            hsiOn = bit(cr, hsiOnBit),
            hsiReady = bit(cr, hsiRdyBit),
            hseOn = bit(cr, 16),
            hseReady = bit(cr, 17),
            pllOn = bit(cr, 24),
            pllReady = bit(cr, 25),
            sysclkHz = sys,
            hseFactor = hseFactor,
            ahbDiv = div,
            formula = formula,
        )
    }

    /** Частоты кварцев, к которым округляется оценка HSE (±3 %). */
    private val commonHse = doubleArrayOf(4e6, 8e6, 12e6, 16e6, 20e6, 24e6, 25e6, 26e6, 32e6, 48e6)

    fun roundHse(hz: Double): Double? = commonHse.firstOrNull { abs(hz - it) / it < 0.03 }

    fun mhz(hz: Double): String {
        val m = hz / 1e6
        return if (abs(m - Math.round(m)) < 0.05) "${Math.round(m)} МГц" else "%.2f МГц".format(Locale.ROOT, m)
    }
}

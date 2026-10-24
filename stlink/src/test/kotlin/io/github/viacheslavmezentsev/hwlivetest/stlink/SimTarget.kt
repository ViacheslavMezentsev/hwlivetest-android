package io.github.viacheslavmezentsev.hwlivetest.stlink

import java.io.IOException

/**
 * Модель адаптера с целью Cortex-M для тестов изменения цели: память, DHCSR, DEMCR, AIRCR,
 * сброс с перехватом вектора, запись блоков. Отвечает на команды ST-Link так, как настоящий адаптер.
 */
class SimTarget(
    private val jtag: Int = 37,
    /** false — сброс не останавливает ядро даже с VC_CORERESET (проверка таймаута). */
    var vectorCatchWorks: Boolean = true,
) : StLinkTransport {
    val mem = HashMap<Long, Byte>()
    var halted = false
    var debugEnabled = false
    var demcr = 0
    var resetCount = 0
    var nrstPulses = 0
    private var resetFlag = false
    private var pendingWrite: Pair<Long, Int>? = null
    private val responses = ArrayDeque<ByteArray>()
    val commands = mutableListOf<ByteArray>()

    fun word(address: Long): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((mem[address + i]?.toInt() ?: 0) and 0xFF shl (8 * i)) }

    private fun putWord(address: Long, value: Int) {
        for (i in 0 until 4) mem[address + i] = (value ushr (8 * i)).toByte()
    }

    private fun readWord(address: Long): Int = when (address) {
        CortexDebugRegs.DHCSR -> {
            var v = 0
            if (debugEnabled) v = v or CoreState.C_DEBUGEN
            if (halted) v = v or CoreState.S_HALT
            if (resetFlag) v = v or CoreState.S_RESET_ST
            resetFlag = false
            v
        }
        CortexDebugRegs.DEMCR -> demcr
        else -> word(address)
    }

    private fun writeWord(address: Long, value: Int) {
        when (address) {
            CortexDebugRegs.DHCSR -> if (value ushr 16 == 0xA05F) {
                debugEnabled = value and 1 != 0
                halted = debugEnabled && value and 2 != 0
            }
            CortexDebugRegs.DEMCR -> demcr = value
            CortexDebugRegs.AIRCR -> if (value == CortexDebugRegs.AIRCR_SYSRESETREQ) {
                resetCount++
                resetFlag = true
                halted = vectorCatchWorks && debugEnabled && demcr and 1 != 0
            }
            else -> putWord(address, value)
        }
    }

    private fun u32(c: ByteArray, o: Int): Long = Hex.u32le(c, o).toLong() and 0xFFFFFFFFL

    private fun ok(n: Int, value: Int? = null): ByteArray {
        val r = ByteArray(n)
        r[0] = 0x80.toByte()
        if (value != null) Hex.putU32le(r, 4, value.toLong() and 0xFFFFFFFFL)
        return r
    }

    override fun write(data: ByteArray) {
        pendingWrite?.let { (addr, width) ->
            pendingWrite = null
            if (width == 4 || width == 2 || width == 1) data.forEachIndexed { i, b -> mem[addr + i] = b }
            return
        }
        commands += data.copyOf()
        val c = data
        when (c[0].toInt() and 0xFF) {
            0xF1 -> {
                val v = (2 shl 12) or (jtag shl 6) or 7
                responses += byteArrayOf((v ushr 8).toByte(), v.toByte(), 0x83.toByte(), 0x04, 0x48, 0x37)
            }
            0xF2 -> when (c[1].toInt() and 0xFF) {
                0x31 -> responses += ok(12, 0x2BA01477)
                0x36 -> responses += ok(8, readWord(u32(c, 2)))
                0x35 -> {
                    writeWord(u32(c, 2), Hex.u32le(c, 6))
                    responses += ok(2)
                }
                0x07 -> {
                    val a = u32(c, 2)
                    val n = Hex.u16le(c, 6)
                    responses += ByteArray(n) { mem[a + it] ?: 0 }
                }
                0x08 -> pendingWrite = u32(c, 2) to 4
                0x48 -> pendingWrite = u32(c, 2) to 2
                0x0D -> pendingWrite = u32(c, 2) to 1
                0x3E -> responses += ok(12)
                0x3B -> responses += ok(2)
                0x3C -> {
                    nrstPulses++
                    responses += ok(2)
                }
                0x30 -> responses += ok(2)
                else -> throw IOException("SimTarget: команда ${Hex.bytes(c, 2)} не поддержана")
            }
            else -> throw IOException("SimTarget: команда ${Hex.bytes(c, 1)} не поддержана")
        }
    }

    override fun read(length: Int): ByteArray {
        val r = responses.removeFirstOrNull() ?: throw IOException("SimTarget: нет ответа")
        return r.copyOf(length)
    }
}

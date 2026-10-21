package io.github.viacheslavmezentsev.hwlivetest.fx2la

import java.io.IOException

enum class Fx2ErrorKind { USB_IO, NO_FIRMWARE, BAD_FIRMWARE, FIRMWARE_VERSION, RENUMERATION, BAD_CONFIG, STREAM_STALL }

class Fx2Exception(val kind: Fx2ErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Статистика канала пробного захвата. */
data class ChannelStats(val index: Int, val ones: Long, val transitions: Long, val total: Long) {
    /** Доля отсчётов с единицей. */
    val duty: Double get() = if (total == 0L) 0.0 else ones.toDouble() / total

    /** Частота по числу периодов (два перепада на период) за время захвата [seconds]. */
    fun frequencyHz(seconds: Double): Double = if (seconds <= 0) 0.0 else transitions / 2.0 / seconds

    val level: String
        get() = when {
            transitions > 0 -> "перепады"
            ones == total -> "1"
            else -> "0"
        }
}

/** Итог пробного захвата (ТЗ ЛА 6.6, р.1.1). */
data class ProbeResult(
    val rate: Long,
    val requestedSamples: Long,
    val receivedSamples: Long,
    /** Время от завершения первой передачи до последней, нс. */
    val wallNs: Long,
    /** Отсчёты после первой передачи: по ним считается, успевает ли поток. */
    val samplesAfterFirst: Long,
    val bufferBytes: Int,
    val transfers: Int,
    val channels: List<ChannelStats>,
    val stalled: Boolean,
    val stallMessage: String?,
) {
    /** Длительность захвата по частоте анализатора, с. */
    val captureSeconds: Double get() = receivedSamples.toDouble() / rate

    /** Скорость потока, байт/с (по часам телефона). */
    val throughput: Double get() = if (wallNs <= 0) 0.0 else samplesAfterFirst * 1e9 / wallNs

    /** Отношение полученного к ожидаемому по времени; < 0,95 — поток не успевает (ТЗ ЛА 4.2.1). */
    val keepUpRatio: Double get() = if (wallNs <= 0) 1.0 else throughput / rate

    val keepsUp: Boolean get() = !stalled && keepUpRatio >= 1.0 - GAP_TOLERANCE

    companion object {
        const val GAP_TOLERANCE = 0.05
    }
}

/**
 * Анализатор на FX2 с прошивкой fx2lafw. Анализатор только наблюдает: его входы на цель не
 * влияют; загрузка прошивки пишет в ОЗУ анализатора, а не в цель (ТЗ ЛА 3.2.6).
 */
class Fx2LogicAnalyzer(
    private val transport: Fx2Transport,
    private val log: (String) -> Unit = {},
) {
    val highSpeed: Boolean get() = Fx2Protocol.isHighSpeed(transport.packetSize)

    /** Версия fx2lafw; null — прошивка не загружена (загрузчик в ПЗУ не знает команду). ТЗ ЛА 3.1.3. */
    fun firmwareVersion(): Fx2Protocol.Version? {
        log("> c0 b0 0000 0000 len 2")
        val r = transport.controlIn(Fx2Protocol.CMD_GET_FW_VERSION, 0, 0, 2, Fx2Protocol.CONTROL_TIMEOUT_MS)
        log(if (r == null) "< нет ответа" else "< " + r.joinToString(" ") { "%02x".format(it) })
        return r?.let { Fx2Protocol.parseVersion(it) }
    }

    fun revId(): Int? =
        transport.controlIn(Fx2Protocol.CMD_GET_REVID_VERSION, 0, 0, 1, Fx2Protocol.CONTROL_TIMEOUT_MS)
            ?.firstOrNull()?.toInt()?.and(0xFF)

    /** Проверить версию; иная старшая версия — ошибка (ТЗ ЛА 3.3.1). */
    fun requireFirmware(): Fx2Protocol.Version {
        val v = firmwareVersion()
            ?: throw Fx2Exception(Fx2ErrorKind.NO_FIRMWARE, "Прошивка fx2lafw не загружена.")
        if (!v.supported) {
            throw Fx2Exception(
                Fx2ErrorKind.FIRMWARE_VERSION,
                "Версия fx2lafw $v не поддерживается: нужна ${Fx2Protocol.REQUIRED_MAJOR}.x.",
            )
        }
        return v
    }

    /**
     * Загрузка образа в ОЗУ FX2: ядро в сброс, порции, запуск (ТЗ ЛА 3.2.1). После запуска FX2
     * отключается от шины и появляется снова — устройство нужно открыть заново.
     */
    fun uploadFirmware(image: Fx2Image) {
        log("# ЛА: загрузка прошивки ${image.size} байт, sha256 ${image.sha256Short}…, порций ${image.chunks.size}")
        try {
            cpuReset(true)
            for (c in image.chunks) {
                transport.controlOut(Fx2Protocol.REQ_FIRMWARE_LOAD, c.address, 0, c.data, Fx2Protocol.LOAD_TIMEOUT_MS)
            }
            cpuReset(false)
        } catch (e: IOException) {
            throw Fx2Exception(Fx2ErrorKind.USB_IO, "Не удалось загрузить прошивку: ${e.message}", e)
        }
        log("# ЛА: прошивка загружена, ждём переподключения анализатора")
    }

    private fun cpuReset(hold: Boolean) {
        val v = if (hold) 1 else 0
        log("> 40 a0 e600 0000 len 1: %02x".format(v))
        transport.controlOut(Fx2Protocol.REQ_FIRMWARE_LOAD, Fx2Protocol.CPUCS, 0, byteArrayOf(v.toByte()), Fx2Protocol.LOAD_TIMEOUT_MS)
    }

    /**
     * Пробный захват фиксированной длительности со статистикой по каналам, без хранения
     * отсчётов (ТЗ ЛА 6.6, р.1.1).
     */
    fun probeCapture(rate: Long, durationMs: Long, stallMs: Long = STALL_MS): ProbeResult {
        if (rate !in Fx2Protocol.allowedRates(highSpeed)) {
            val why = if (!highSpeed && rate in Fx2Protocol.SAMPLE_RATES) {
                "анализатор работает на полной скорости USB (12 Мбит/с): подключите его без хаба или через хаб USB 2.0"
            } else {
                "частота не поддерживается"
            }
            throw Fx2Exception(Fx2ErrorKind.BAD_CONFIG, "${Format.rate(rate)}: $why.")
        }
        if (durationMs !in 1..MAX_PROBE_MS) {
            throw Fx2Exception(Fx2ErrorKind.BAD_CONFIG, "Длительность $durationMs мс вне 1…$MAX_PROBE_MS мс.")
        }
        val start = Fx2Protocol.start(rate)
            ?: throw Fx2Exception(Fx2ErrorKind.BAD_CONFIG, "${Format.rate(rate)}: делитель не подобрать.")
        val buffer = Fx2Protocol.bufferSize(rate, transport.maxBufferBytes)
        val transfers = Fx2Protocol.transferCount(rate, buffer)
        val wanted = rate * durationMs / 1000

        val counter = ChannelCounter()
        var received = 0L
        var firstNs = 0L
        var lastNs = 0L
        var afterFirst = 0L
        var stallMessage: String? = null

        val startBytes = start.bytes()
        log(
            "> 40 b1 0000 0000 len 3: %s  # %s, опора %d МГц, делитель %d, буфер %d × %d"
                .format(startBytes.joinToString(" ") { "%02x".format(it) }, Format.rate(rate), start.clockMhz, start.delay, buffer, transfers),
        )
        try {
            transport.controlOut(Fx2Protocol.CMD_START, 0, 0, startBytes, Fx2Protocol.CONTROL_TIMEOUT_MS)
            transport.stream(buffer, transfers, stallMs) { data, length, timeNs ->
                val n = minOf(length.toLong(), wanted - received).toInt()
                counter.add(data, n)
                if (received == 0L) firstNs = timeNs else afterFirst += length
                lastNs = timeNs
                received += n
                received < wanted
            }
        } catch (e: StallException) {
            stallMessage = e.message
        } catch (e: IOException) {
            if (received == 0L) throw Fx2Exception(Fx2ErrorKind.USB_IO, "Ошибка USB: ${e.message}", e)
            stallMessage = "ошибка USB: ${e.message}"
        }
        val result = ProbeResult(
            rate = rate,
            requestedSamples = wanted,
            receivedSamples = received,
            wallNs = lastNs - firstNs,
            samplesAfterFirst = afterFirst,
            bufferBytes = buffer,
            transfers = transfers,
            channels = counter.stats(),
            stalled = stallMessage != null,
            stallMessage = stallMessage,
        )
        log(
            "# ЛА: получено %d из %d отсчётов, поток %.2f МБ/с (%.0f %% от нужного)%s"
                .format(received, wanted, result.throughput / 1e6, result.keepUpRatio * 100, stallMessage?.let { ", обрыв: $it" } ?: ""),
        )
        return result
    }

    companion object {
        const val STALL_MS = 500L
        const val MAX_PROBE_MS = 10_000L
    }
}

/**
 * Счётчик уровней и перепадов 8 каналов. На каждый отсчёт — два обращения к гистограммам
 * по 256 значений, поэтому успевает за 24 МБ/с; по гистограммам потом считаются каналы.
 */
class ChannelCounter {
    private val values = LongArray(256)
    private val changes = LongArray(256)
    private var prev = -1
    private var total = 0L

    fun add(data: ByteArray, length: Int) {
        var p = prev
        for (i in 0 until length) {
            val v = data[i].toInt() and 0xFF
            values[v]++
            if (p >= 0) changes[p xor v]++
            p = v
        }
        prev = p
        total += length
    }

    fun stats(): List<ChannelStats> = (0 until 8).map { bit ->
        val mask = 1 shl bit
        var ones = 0L
        var transitions = 0L
        for (v in 0 until 256) {
            if (v and mask != 0) {
                ones += values[v]
                transitions += changes[v]
            }
        }
        ChannelStats(bit, ones, transitions, total)
    }
}

object Format {
    fun rate(hz: Long): String = when {
        hz >= 1_000_000 && hz % 1_000_000 == 0L -> "${hz / 1_000_000} МГц"
        hz >= 1_000 && hz % 1_000 == 0L -> "${hz / 1_000} кГц"
        else -> "$hz Гц"
    }

    fun frequency(hz: Double): String = when {
        hz >= 1e6 -> "%.3f МГц".format(hz / 1e6)
        hz >= 1e3 -> "%.3f кГц".format(hz / 1e3)
        else -> "%.1f Гц".format(hz)
    }
}

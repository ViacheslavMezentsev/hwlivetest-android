package io.github.viacheslavmezentsev.hwlivetest.stlink

import java.util.Locale

/**
 * Счётчики обмена с адаптером: передачи USB в обе стороны и время журнала. Нужны, чтобы по
 * отчёту понять, где теряется время при чтении и записи блоков.
 */
class ExchangeStats {
    private var writes = 0L
    private var writeNs = 0L
    private var writeMaxNs = 0L
    private var reads = 0L
    private var readNs = 0L
    private var readMaxNs = 0L
    private var bytesIn = 0L
    private var bytesOut = 0L
    private var logNs = 0L

    @Synchronized
    fun reset() {
        writes = 0
        writeNs = 0
        writeMaxNs = 0
        reads = 0
        readNs = 0
        readMaxNs = 0
        bytesIn = 0
        bytesOut = 0
        logNs = 0
    }

    @Synchronized
    internal fun write(ns: Long, bytes: Int) {
        writes++
        writeNs += ns
        writeMaxNs = maxOf(writeMaxNs, ns)
        bytesOut += bytes
    }

    @Synchronized
    internal fun read(ns: Long, bytes: Int) {
        reads++
        readNs += ns
        readMaxNs = maxOf(readMaxNs, ns)
        bytesIn += bytes
    }

    @Synchronized
    internal fun log(ns: Long) {
        logNs += ns
    }

    /** Строки для отчёта: число передач, среднее и максимальное время, объём, время журнала. */
    @Synchronized
    fun lines(): List<String> {
        fun ms(ns: Long) = String.format(Locale.ROOT, "%.2f", ns / 1e6)
        return listOf(
            "USB OUT: $writes передач, $bytesOut байт, среднее ${ms(if (writes > 0) writeNs / writes else 0)} мс, макс. ${ms(writeMaxNs)} мс, всего ${ms(writeNs)} мс",
            "USB IN: $reads передач, $bytesIn байт, среднее ${ms(if (reads > 0) readNs / reads else 0)} мс, макс. ${ms(readMaxNs)} мс, всего ${ms(readNs)} мс",
            "Журнал: ${ms(logNs)} мс",
        )
    }
}

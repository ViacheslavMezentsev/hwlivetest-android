package io.github.viacheslavmezentsev.hwlivetest

import android.os.SystemClock
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLink
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.telemetry.MemoryMap
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbStLinkTransport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Общее состояние приложения: подключение к адаптеру, загруженные поля и их ряды.
 * Живёт, пока жив процесс, и переживает поворот экрана и переход на экран графика.
 *
 * Весь обмен с адаптером идёт в одном потоке [worker], поэтому команды ST-Link
 * никогда не перемешиваются.
 */
object Session {
    val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stlink-worker").apply { isDaemon = true }
    }

    val log = SessionLog()

    /** Вызывается после каждой новой строки журнала (из любого потока). */
    @Volatile
    var onLog: (() -> Unit)? = null

    @Volatile
    var device: StLinkDevice? = null

    // Меняются только в потоке worker.
    @Volatile
    var transport: UsbStLinkTransport? = null
        private set

    @Volatile
    var stlink: StLink? = null
        private set

    @Volatile
    var target: TargetInfo? = null
        private set

    /** Тексты блоков главного экрана, чтобы восстановить их после поворота. */
    @Volatile
    var adapterText: String = ""

    @Volatile
    var targetText: String = ""

    // ---------- Поля ----------

    @Volatile
    var fieldsFile: AddressesFile? = null
        private set

    @Volatile
    var fieldsName: String = ""
        private set

    @Volatile
    var sampler: FieldSampler? = null
        private set

    private val t0 = SystemClock.elapsedRealtime()

    /** Время сеанса в мс: общая ось X для всех рядов. */
    fun nowMs(): Long = SystemClock.elapsedRealtime() - t0

    fun logLine(line: String) {
        log.add(line)
        onLog?.invoke()
    }

    fun setFields(file: AddressesFile, name: String) {
        stopPolling()
        fieldsFile = file
        fieldsName = name
        sampler = FieldSampler(file.fields)
    }

    fun field(topic: String): Field? = fieldsFile?.fields?.firstOrNull { it.topic == topic }

    /** Проверка адреса поля по размерам Flash/SRAM опознанного MCU. */
    fun check(field: Field): MemoryMap.Check =
        MemoryMap.check(field.address, target?.flashSizeKb, target?.sramKb)

    // ---------- Подключение (только в потоке worker) ----------

    fun attach(t: UsbStLinkTransport, st: StLink) {
        transport = t
        stlink = st
    }

    fun identified(info: TargetInfo) {
        target = info
    }

    fun closeConnection() {
        stopPolling()
        stlink?.disconnect()
        stlink = null
        target = null
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
    }

    // ---------- Опрос ----------

    private var poll: ScheduledFuture<*>? = null

    /**
     * Периодически читать поля [only] (null — все) каждые [periodMs] мс в потоке worker.
     * [onReadings] и [onStopped] вызываются в потоке worker.
     */
    @Synchronized
    fun startPolling(
        periodMs: Long,
        only: Set<String>?,
        onReadings: (List<FieldSampler.Reading>) -> Unit,
        onStopped: (String) -> Unit,
    ) {
        stopPolling()
        val s = sampler ?: return
        poll = worker.scheduleWithFixedDelay({
            val st = stlink
            if (st == null) {
                stopPolling()
                onStopped("нет подключения")
                return@scheduleWithFixedDelay
            }
            val readable = s.fields.filter { only == null || it.topic in only }
                .filter { check(it).readable }.map { it.topic }
            try {
                onReadings(s.sample(nowMs(), read = { st.read32(it) }, only = readable, isFatal = ::isFatal))
            } catch (e: Exception) {
                logLine("# опрос остановлен: ${e.message}")
                stopPolling()
                onStopped(ErrorMessages.describe(e))
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stopPolling() {
        poll?.cancel(false)
        poll = null
    }

    /** Ошибки, после которых опрос бессмысленен: обрыв USB, сбой протокола. */
    private fun isFatal(e: Exception): Boolean =
        e !is StLinkException || (e.kind != StLinkErrorKind.SWD_FAULT && e.kind != StLinkErrorKind.BAD_ARGUMENT)
}

package io.github.viacheslavmezentsev.hwlivetest

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLink
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkConnection
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.WritePermit
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.telemetry.MemoryMap
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkUsb
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbStLinkTransport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Общее состояние приложения: подключение к адаптеру, загруженные поля и их ряды.
 * Живёт, пока жив процесс, и переживает поворот экрана и смену экранов.
 *
 * Весь обмен с адаптером идёт в одном потоке [worker], поэтому команды ST-Link
 * никогда не перемешиваются.
 */
object Session {
    val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stlink-worker").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    val log = SessionLog()

    /** Вызывается после новой строки журнала (из любого потока). */
    @Volatile
    var onLog: (() -> Unit)? = null

    /** Писать ли в журнал обмен во время опроса полей: на 20 Гц это тысячи строк в минуту. */
    @Volatile
    var logPolling: Boolean = false

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
    var connection: StLinkConnection? = null
        private set

    @Volatile
    var target: TargetInfo? = null
        private set

    /** Тексты блоков экрана эксперта, чтобы восстановить их после поворота. */
    @Volatile
    var adapterText: String = ""

    @Volatile
    var targetText: String = ""

    /** Последний отчёт кнопки «ПУСК». */
    @Volatile
    var lastReport: io.github.viacheslavmezentsev.hwlivetest.diag.DiagReport? = null

    /** Слушатели изменения подключения; вызываются в главном потоке. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

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

    // ---------- Диалог: действия пользователя и ответы программы ----------
    // Журнал записывает «разговор» с прибором: что сделал пользователь и как ответила программа.
    // Из этих строк потом вырастет экран-диалог (тема с историей действий).

    fun userSaid(text: String) = logLine("$USER_PREFIX$text")

    fun programSaid(text: String) = logLine("$PROGRAM_PREFIX$text")

    /** Последние [n] строк диалога (без обмена с адаптером). */
    fun dialogTail(n: Int): List<String> = log.text().lines()
        .filter { it.startsWith(USER_PREFIX) || it.startsWith(PROGRAM_PREFIX) }
        .takeLast(n)

    const val USER_PREFIX = "# пользователь: "
    const val PROGRAM_PREFIX = "# программа: "

    /** Строки от [StLink]: обмен во время опроса не пишется, если это не включено. */
    private fun protocolLog(line: String) {
        if (pollingActive && !logPolling && SessionLog.isProtocol(line)) return
        logLine(line)
    }

    fun setFields(file: AddressesFile, name: String) {
        stopPolling()
        fieldsFile = file
        fieldsName = name
        sampler = FieldSampler(file.fields)
        notifyChanged()
    }

    fun field(topic: String): Field? = fieldsFile?.fields?.firstOrNull { it.topic == topic }

    /** Проверка адреса поля по размерам Flash/SRAM опознанного MCU. */
    fun check(field: Field): MemoryMap.Check =
        MemoryMap.check(field.address, target?.flashSizeKb, target?.sramKb)

    // ---------- Подключение (только в потоке worker) ----------

    /** Открыть адаптер, войти в SWD и опознать MCU. Вызывать в потоке [worker]. */
    fun connectBlocking(usb: StLinkUsb, d: StLinkDevice): TargetInfo {
        closeConnection()
        try {
            val t = usb.open(d)
            logLine("# ${d.title}: ${t.endpointsLabel}")
            val st = StLink(t, ::protocolLog)
            transport = t
            stlink = st
            val c = st.connect()
            connection = c
            adapterText = Format.adapter(d, c)
            c.warnings.forEach { logLine("# предупреждение: $it") }
            val info = TargetIdentifier(st).identify()
            info.notes.forEach { logLine("# $it") }
            target = info
            targetText = Format.target(info)
            return info
        } catch (e: Exception) {
            logLine("# ошибка: ${e.javaClass.simpleName}: ${e.message}")
            closeConnection()
            throw e
        } finally {
            notifyChanged()
        }
    }

    // ---------- Изменение цели (ТЗ ST-Link 2.0, п. 5.11.1; ТЗ прошивки, п. 3.1.1) ----------

    /**
     * Разрешение на изменение цели. Живёт только в памяти процесса: при каждом запуске
     * приложения режим «Изменение цели» выключен.
     */
    @Volatile
    var writePermit: WritePermit? = null
        private set

    val writeMode: Boolean get() = writePermit?.active == true

    fun enableWriteMode() {
        if (writeMode) return
        writePermit = WritePermit.grant("режим «Изменение цели» включён пользователем")
        userSaid("включил режим «Изменение цели»")
        notifyChanged()
    }

    fun disableWriteMode() {
        writePermit?.revoke()
        if (writePermit != null) userSaid("выключил режим «Изменение цели»")
        writePermit = null
        notifyChanged()
    }

    /** Доступ к записи и управлению ядром; вызывать в потоке [worker]. */
    fun targetWrite(): TargetWrite {
        val st = stlink ?: throw IllegalStateException("Сначала подключитесь к ST-Link.")
        val permit = writePermit
            ?: throw StLinkException(StLinkErrorKind.NOT_PERMITTED, "Включите режим «Изменение цели».")
        return st.targetWrite(permit)
    }

    fun closeConnection() {
        stopPolling()
        stlink?.disconnect()
        stlink = null
        connection = null
        target = null
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
    }

    /** Адаптер выдернули. Вызывается в главном потоке. */
    fun detached(d: UsbDevice?) {
        if (device?.sameAs(d) != true) return
        device = null
        adapterText = ""
        targetText = ""
        logLine("# адаптер отключён")
        worker.execute {
            closeConnection()
            notifyChanged()
        }
    }

    // ---------- Опрос ----------

    private var poll: ScheduledFuture<*>? = null

    @Volatile
    private var pollingActive = false

    /** Длительность последнего прохода опроса, мс. */
    @Volatile
    var lastPollMs: Long = 0
        private set

    /** Идущая запись MCAP; пишется в потоке worker после каждого прохода опроса. */
    @Volatile
    var recording: Recording? = null

    /**
     * Читать поля [only] (null — все) с периодом [periodMs] мс (fixed rate: 100 мс — ровно
     * 10 Гц, если проход быстрее периода) в потоке worker. [onReadings] и [onStopped]
     * вызываются в потоке worker.
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
        pollingActive = true
        poll = worker.scheduleAtFixedRate({
            val st = stlink
            if (st == null) {
                stopPolling()
                onStopped("нет подключения")
                return@scheduleAtFixedRate
            }
            val readable = s.fields.filter { only == null || it.topic in only }
                .filter { check(it).readable }.map { it.topic }
            try {
                val t = SystemClock.elapsedRealtime()
                val r = s.sample(nowMs(), readBlock = { a, n -> st.readBlock(a, n) }, only = readable, isFatal = ::isFatal)
                lastPollMs = SystemClock.elapsedRealtime() - t
                recording?.write(System.currentTimeMillis() * 1_000_000L, r)
                onReadings(r)
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
        pollingActive = false
    }

    /** Ошибки, после которых опрос бессмысленен: обрыв USB, сбой протокола. */
    private fun isFatal(e: Exception): Boolean =
        e !is StLinkException || (e.kind != StLinkErrorKind.SWD_FAULT && e.kind != StLinkErrorKind.BAD_ARGUMENT)
}

package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.firmware.*
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.*
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.RttFrameDecoder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** ТЗ RTT 2.9. Run only on Session.worker; cancellation never skips teardown. */
class RttTrial {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running: RttSession? = null
    fun cancel() { cancelled.set(true); running?.cancel() }

    fun run(elf: ElfFile, fields: List<Field>, directory: File): String {
        val report = JSONObject().put("version", BuildConfig.VERSION_NAME).put("sample_hz", 100)
            .put("fields", JSONArray(fields.map { JSONObject().put("topic", it.topic).put("address", it.address).put("kind", it.kind.key) }))
        var reportFile: File? = null
        var message: String
        var summary: String
        try {
            check(!cancelled.get()) { "Отменено до установки" }
            check(Session.recording == null) { "Сначала остановите текущую запись" }
            val adapter = Session.stlink ?: error("Адаптер отключён")
            val target = Session.target ?: error("Устройство отключено")
            check(target.vendor == McuVendor.STM32 && target.profileOverride == null &&
                (target.idRegister ?: 0) and 0xFFF == 0x431) { "Нужен автоматически опознанный F411" }
            val write = Session.targetWrite()
            val a = RttElfAdvice.inspect(elf)
            val names = RttElfSymbols.inspect(elf)
            val tick = names.tick?.second ?: error("Tick не найден")
            val clock = names.coreClock ?: error("SystemCoreClock не найден")
            val before = adapter.read32(tick).toUInt().toLong()
            val started = android.os.SystemClock.elapsedRealtime()
            Thread.sleep(250)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val delta = (adapter.read32(tick).toUInt().toLong() - before) and 0xFFFFFFFFL
            check(delta in elapsed / 2..elapsed * 2) { "Tick не похож на миллисекундный: $delta за $elapsed мс" }
            val hz = RttDoctor.timerClockHz(adapter.read32(clock).toUInt().toLong(),
                adapter.read32(0x40023808), adapter.read32(0x4002388C))
            val timer = RttDoctor.inspectF411(adapter, 0x431).firstOrNull { it.candidate }?.timer
                ?: error("Свободного таймера нет")
            val vector = listOf(".isr_vector", ".vectors", ".vector_table", ".intvec", ".vectors_flash")
                .mapNotNull { elf.section(it) }.firstOrNull { it.address == 0x08000000L }
                ?: error("Таблица векторов не найдена")
            val built = RttImageBuilder.build(a.base, a.available, fields.map { it.address }, tick, vector.size, timer, hz)
            check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку отчётов" }
            val stem = "trial-${System.currentTimeMillis()}"
            reportFile = File(directory, "$stem.json")
            report.put("timer", timer.name).put("base", a.base).put("image_bytes", built.image.bytes.size)
                .put("timer_clock_hz", hz).put("tick_address", tick).put("status", "prepared")
                .put("resources_assumed_free", true).put("elf_matches_firmware", JSONObject.NULL)
            reportFile.writeText(report.toString(2))
            val decoder = RttFrameDecoder(fields.size)
            var firstTick: Long? = null
            var firstFrameWallMs: Long? = null
            var lastTick: Long? = null
            val session = RttSession.forDevice(adapter, write, built.image, 0x431,
                built.control, built.buffer, built.bufferBytes)
            val output = File(directory, "$stem.rtt.bin").outputStream()
            running = session
            if (cancelled.get()) session.cancel()
            val result = session.run(5000, object : RttSession.Sink {
                override fun accept(bytes: ByteArray) {
                    output.write(bytes)
                    decoder.feed(bytes).forEach { frame ->
                        if (firstTick == null) { firstTick = frame.tickMs; firstFrameWallMs = System.currentTimeMillis() }
                        lastTick = frame.tickMs
                    }
                }
                override fun close() = output.close()
            })
            val stats = decoder.stats
            val span = if (firstTick != null && lastTick != null) (lastTick!! - firstTick!!) and 0xFFFFFFFFL else 0L
            val measuredHz = if (span in 1..0x7FFFFFFFL && stats.frames > 1 && stats.tickBackwards == 0L)
                (stats.frames - 1) * 1000.0 / span else null
            report.put("status", result.reason).put("bytes", result.bytes).put("frames", stats.frames)
                .put("missing_estimate", stats.missingEstimate).put("duplicates", stats.duplicates)
                .put("discarded_bytes", stats.discardedBytes).put("pending_bytes", decoder.pendingBytes)
                .put("tick_backwards", stats.tickBackwards).put("sequence_discontinuities", stats.sequenceDiscontinuities)
                .put("target_dropped", JSONObject.NULL)
                .put("first_tick", firstTick ?: JSONObject.NULL).put("last_tick", lastTick ?: JSONObject.NULL)
                .put("received_hz_by_tick_ms", measuredHz ?: JSONObject.NULL)
                .put("repeated_ticks", stats.repeatedTicks).put("tick_wraps", stats.tickWraps)
                .put("restored", result.recovery?.ok ?: false).put("recovery_stage", result.recovery?.stage ?: "not_started")
                .put("recovery_error", result.recovery?.error ?: JSONObject.NULL)
                .put("error", result.error ?: JSONObject.NULL).put("close_error", result.closeError ?: JSONObject.NULL)
            summary = (if (result.reason == "cancelled") "Проба остановлена." else if (result.ok) "Проба завершена." else "Проба завершилась с ошибкой.") +
                " Получено ${stats.frames} кадров." +
                (measuredHz?.let { " %.1f Гц.".format(java.util.Locale.ROOT, it) } ?: "") +
                (if (result.recovery?.ok == true) " Восстановление подтверждено." else " Восстановление не подтверждено — проверьте устройство.")
            message = "Кадров: ${stats.frames}; пропуски seq: ${stats.missingEstimate}.\n" +
                (measuredHz?.let { "Частота по tick: %.1f Гц.\n".format(java.util.Locale.ROOT, it) } ?: "") +
                "Завершение: ${result.reason}.\nВосстановление: " +
                (if (result.recovery?.ok == true) "подтверждено" else "НЕ подтверждено; проверьте устройство") +
                listOfNotNull(result.error, result.recovery?.error, result.closeError).joinToString("\n", prefix = "\n")
            if (result.recovery?.ok != true && result.installStage != null) Session.disableWriteMode()
            // Disk conversion happens only after mandatory target recovery has returned.
            if (firstFrameWallMs != null) {
                val partial = File(directory, "$stem.mcap.part")
                try {
                    val converted = File(directory, "$stem.rtt.bin").inputStream().use { input ->
                        partial.outputStream().buffered().use { output ->
                            io.github.viacheslavmezentsev.hwlivetest.recorder.RttMcap.convert(input, output, fields, firstFrameWallMs!!)
                        }
                    }
                    check(partial.renameTo(File(directory, "$stem.mcap"))) { "Не удалось завершить файл MCAP" }
                    report.put("mcap_messages", converted.messages).put("mcap_frames", converted.frames)
                        .put("mcap_pending_bytes", converted.pendingBytes).put("first_frame_wall_ms", firstFrameWallMs)
                        .put("mcap_time_basis", "first host receipt + MCU tick deltas; approximate epoch")
                    message += "\nMCAP: ${converted.messages} сообщений."
                    summary += " Запись MCAP готова."
                } catch (error: Exception) {
                    summary += " Не удалось создать MCAP; исходный поток сохранён."
                    report.put("mcap_error", error.message ?: error.javaClass.simpleName)
                    message += "\nОшибка MCAP (исходный поток сохранён): ${error.message}"
                }
            }

        } catch (error: Exception) {
            message = "Проба не завершена: ${error.message}"
            summary = message
            report.put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            running = null
        }
        try { reportFile?.writeText(report.toString(2)) }
        catch (error: Exception) { message += "\nОшибка сохранения отчёта: ${error.message}"; summary += " Ошибка сохранения отчёта." }
        reportFile?.let { lastReport = it; message += "\nОтчёт: ${it.name}" }
        lastSummary = summary
        Session.programSaid("RTT: $summary")
        Session.debugSaid(message)
        return message
    }

    companion object {
        @Volatile var lastSummary: String = ""
            private set
        @Volatile var lastReport: File? = null
            private set
        fun fields() = Session.fieldsFile?.fields.orEmpty().filter {
            it.address in 0x20000000L..0x2001FFFCL && it.address % 4 == 0L &&
                it.mask == null && it.shift == null && (it.sizeBits == null || it.sizeBits == 32)
        }.take(6)
    }
}

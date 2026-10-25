package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import java.util.concurrent.atomic.AtomicBoolean

/** ТЗ RTT 2.8. Blocking, single-owner experiment; cancel() is callable from the UI thread. */
class RttSession internal constructor(
    private val install: () -> RttInstaller.Result,
    private val poll: () -> ByteArray,
    private val teardown: (RttTeardown.Plan) -> RttTeardown.Result,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    interface Sink {
        fun accept(bytes: ByteArray)
        fun close()
    }
    data class Result(val reason: String, val bytes: Long, val installStage: String?,
        val recovery: RttTeardown.Result?, val error: String?, val closeError: String?) {
        val ok: Boolean get() = reason == "duration" && bytes > 0 && recovery?.ok == true && error == null && closeError == null
    }
    private val cancelled = AtomicBoolean(false)
    private val used = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }

    fun run(durationMs: Long, sink: Sink): Result {
        require(durationMs in 1..60_000) { "Проба RTT ограничена 60 секундами" }
        check(used.compareAndSet(false, true)) { "Создайте новый RTT-сеанс" }
        var installed: RttInstaller.Result? = null
        var restored: RttTeardown.Result? = null
        var reason = "cancelled"
        var error: String? = null
        var closeError: String? = null
        var bytes = 0L
        var interrupted = false
        try {
            if (!cancelled.get()) {
                installed = install()
                if (!installed.started) {
                    reason = "installation_failed"
                    error = installed.error ?: "Установка RTT не подтверждена"
                } else {
                    check(installed.recovery != null) { "Установка не вернула план восстановления" }
                    val started = nowMs()
                    reason = "duration"
                    while (nowMs() - started < durationMs) {
                        if (cancelled.get()) { reason = "cancelled"; break }
                        val chunk = poll()
                        if (chunk.isNotEmpty()) { sink.accept(chunk); bytes += chunk.size }
                        val remaining = durationMs - (nowMs() - started)
                        if (remaining > 0) sleep(minOf(10, remaining))
                    }
                    if (reason == "duration" && bytes == 0L) {
                        reason = "no_data"
                        error = "RTT не передал данные за время пробы"
                    }
                }
            }
        } catch (failure: Exception) {
            interrupted = failure is InterruptedException
            reason = if (interrupted) "interrupted" else "error"
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            // Clear interrupt only while performing mandatory cleanup, then restore it.
            interrupted = Thread.interrupted() || interrupted
            try {
                installed?.recovery?.let { plan ->
                    restored = try { teardown(plan) } catch (failure: Exception) {
                        RttTeardown.Result(false, "exception", 0, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            } finally {
                try { sink.close() } catch (failure: Exception) { closeError = failure.message ?: failure.javaClass.simpleName }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        return Result(reason, bytes, installed?.stage, restored, error, closeError)
    }

    companion object {
        /** Caller supplies a confirmed image and exclusively owns the connection until run returns. */
        fun forDevice(memory: DebugMemory, write: TargetWrite, image: RttInstaller.Image,
            deviceId: Int, control: Long, buffer: Long, bufferBytes: Int): RttSession {
            val end = image.base + image.bytes.size
            require(control >= image.base && control + 48 <= end)
            require(buffer >= image.base && buffer + bufferBytes <= end)
            val ring = RttRingReader(memory, write, control, buffer, bufferBytes)
            return RttSession(
                install = { RttInstaller(memory, write).install(image, deviceId) },
                poll = { ring.poll() },
                teardown = { RttTeardown(memory, write).run(it) },
            )
        }
    }
}

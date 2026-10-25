package io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm

import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import java.util.concurrent.atomic.AtomicBoolean

/** ТЗ RTT 2.8. Blocking, single-owner experiment; cancel() is callable from the UI thread. */
class RttSession internal constructor(
    private val install: () -> RttInstaller.Result,
    private val poll: () -> ByteArray,
    private val teardown: (RttTeardown.Plan, (ByteArray) -> Unit) -> RttTeardown.Result,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    interface Sink {
        fun accept(bytes: ByteArray)
        fun progress(elapsedMs: Long) {}
        fun close()
    }
    data class Result(val reason: String, val bytes: Long, val installStage: String?,
        val recovery: RttTeardown.Result?, val error: String?, val closeError: String?) {
        val ok: Boolean get() = reason == "duration" && bytes > 0 && recovery?.ok == true && error == null && closeError == null && recovery.captureError == null
    }
    private val cancelled = AtomicBoolean(false)
    private val used = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }

    fun run(durationMs: Long?, sink: Sink): Result {
        require(durationMs == null || durationMs in 1..3_600_000) { "Для ограниченной пробы задайте 1–3600000 мс; null означает ручную остановку" }
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
                    var lastProgress = -1000L
                    while (durationMs == null || nowMs() - started < durationMs) {
                        if (cancelled.get()) { reason = "cancelled"; break }
                        val elapsed = nowMs() - started
                        if (elapsed - lastProgress >= 1000) { sink.progress(elapsed); lastProgress = elapsed }
                        val chunk = poll()
                        if (chunk.isNotEmpty()) { sink.accept(chunk); bytes += chunk.size }
                        val remaining = durationMs?.minus(nowMs() - started) ?: 10L
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
                    restored = try { teardown(plan) { chunk ->
                        if (chunk.isNotEmpty()) { sink.accept(chunk); bytes += chunk.size }
                    } } catch (failure: Exception) {
                        RttTeardown.Result(false, "exception", 0, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            } finally {
                try { sink.close() } catch (failure: Exception) { closeError = failure.message ?: failure.javaClass.simpleName }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        if (reason == "no_data" && bytes > 0) { reason = "duration"; error = null }
        return Result(reason, bytes, installed?.stage, restored, error, closeError)
    }

    companion object {
        /** Caller supplies a confirmed image and exclusively owns the connection until run returns. */
        fun forDevice(memory: DebugMemory, write: TargetWrite, image: RttInstaller.Image,
            deviceId: Int, control: Long, buffer: Long, bufferBytes: Int, counters: Long? = null): RttSession {
            val end = image.base + image.bytes.size
            require(control >= image.base && control + 48 <= end)
            require(buffer >= image.base && buffer + bufferBytes <= end)
            require(counters == null || (counters % 4 == 0L && counters >= image.base && counters + 16 <= end))
            var started = false
            val ring = RttRingReader(memory, write, control, buffer, bufferBytes)
            return RttSession(
                install = { RttInstaller(memory, write).install(image, deviceId).also { started = it.started } },
                poll = { ring.poll() },
                teardown = { plan, accept ->
                    var snapshot: RttTeardown.Counters? = null
                    RttTeardown(memory, write).run(plan, onQuiescent = {
                        if (started) {
                            // Snapshot while halted, before restoring VTOR and resuming the firmware.
                            // A full ring fits one bounded poll; the producer cannot add more data.
                            var counterError: Exception? = null
                            try { counters?.let { snapshot = RttTeardown.Counters.parse(memory.readBlock(it, 4)) } }
                            catch (error: Exception) { counterError = error }
                            accept(ring.poll(bufferBytes))
                            check(ring.poll(bufferBytes).isEmpty()) { "RTT producer changed while halted" }
                            counterError?.let { throw it }
                        }
                    }).copy(counters = snapshot)
                },
            )
        }
    }
}

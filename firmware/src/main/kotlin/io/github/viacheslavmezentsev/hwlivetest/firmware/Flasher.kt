package io.github.viacheslavmezentsev.hwlivetest.firmware

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory

/** Шаг операции для прогресса (ТЗ прошивки 5.5.2). */
enum class FlashStep(val title: String) {
    CHECK("проверка защиты"),
    BACKUP("резервная копия"),
    HALT("сброс с остановкой"),
    ERASE("стирание"),
    ERASE_CHECK("проверка стирания"),
    WRITE("запись"),
    VERIFY("сверка"),
    START("запуск"),
}

/** Прогресс: шаг, сделано и всего байт (или единиц). Вернуть false — отменить в ближайшей точке. */
typealias FlashProgress = (step: FlashStep, done: Long, total: Long) -> Boolean

/** Итог операции. */
data class FlashOutcome(
    val ok: Boolean,
    val kind: FlashErrorKind?,
    val message: String,
    val hint: String?,
    val durationMs: Long,
    /** Ядро после операции: true — запущено. */
    val running: Boolean,
    /** Резервная копия стираемых единиц (ТЗ прошивки 3.1.5, р.1.2). */
    val backup: List<Segment>?,
    val mismatches: List<FlashTools.Mismatch> = emptyList(),
    /** Длительность шагов — для отчёта и замера скорости (ТЗ прошивки 7.1.1). */
    val steps: List<StepTime> = emptyList(),
)

/** Время шага и объём, дошедший до прогресса (байты или единицы). */
data class StepTime(val step: FlashStep, val ms: Long, val done: Long) {
    val text: String
        get() {
            val speed = if (ms > 0 && done >= 1024 && step != FlashStep.ERASE_CHECK) {
                ", %.1f КБ/с".format(java.util.Locale.ROOT, done / 1024.0 / (ms / 1000.0))
            } else {
                ""
            }
            val amount = if (step == FlashStep.ERASE_CHECK || done < 1024) "" else " ${FlashPlan.kb(done.toInt())},"
            return "${step.title}:$amount ${"%.1f".format(java.util.Locale.ROOT, ms / 1000.0)} с$speed"
        }
}

/**
 * Стирание и прошивка (ТЗ прошивки 5.4, 5.5): защита, резервная копия, сброс с остановкой,
 * остановка сторожевых таймеров, разблокировка, стирание, запись, блокировка, сверка, запуск.
 */
class Flasher(
    private val mem: DebugMemory,
    private val tw: TargetWrite,
    private val layout: FlashLayout,
    private val psize: Int = 32,
    /** Адаптер умеет WRITEMEM_16BIT (J26+ или V3; ТЗ ST-Link 3.2.4). */
    private val canWrite16: Boolean = true,
    private val clock: FlashClock = FlashClock(),
) {
    private val ctl: FlashController = FlashController.of(layout, mem, tw, psize, clock)

    /** Изменения начаты: после этого отмена и сбои дают INCOMPLETE, а не CANCELLED. */
    private var changed = false

    /** Резервная копия текущей операции: возвращается и при неудаче, чтобы её можно было сохранить. */
    private var backupData: List<Segment>? = null

    private val stepTimes = LinkedHashMap<FlashStep, LongArray>()
    private var lastMark = 0L

    /** Учёт времени шагов: шаг начинается там, где закончился предыдущий. */
    private fun track(p: FlashProgress): FlashProgress = { step, done, total ->
        val now = clock.now()
        val e = stepTimes.getOrPut(step) { longArrayOf(lastMark, now, 0) }
        e[1] = now
        e[2] = maxOf(e[2], done)
        lastMark = now
        p(step, done, total)
    }

    /**
     * Прошивка по плану (ТЗ прошивки 5.5.1). [backup] — снимать ли резервную копию (п. 3.1.5);
     * [run] — сброс с запуском после успешной сверки (п. 5.6.1).
     */
    fun flash(plan: FlashPlan, backup: Boolean, run: Boolean, onProgress: FlashProgress = { _, _, _ -> true }): FlashOutcome =
        operation {
            val progress = track(onProgress)
            if (!plan.allowed) refuse(plan.refusals.joinToString("; "))
            // ТЗ прошивки 4.3.3, вопрос 10.2.1: без записи полусловами F1 не прошивается.
            if (!canWrite16 && (layout.family == FlashFamily.F1 || psize == 16)) {
                refuse("прошивке адаптера нужна запись полусловами (WRITEMEM_16BIT, J26 или новее) — обновите прошивку ST-Link")
            }
            checkProtection(plan.units)
            val copy = if (backup) readUnits(plan.units, progress).also { backupData = it } else null
            withTargetPrepared(progress) {
                eraseUnits(plan.units, progress)
                val total = plan.bytesToWrite.toLong()
                var base = 0L
                for (s in plan.segments) {
                    ctl.program(s.address, s.data) { n -> progress(FlashStep.WRITE, base + n, total) }
                    base += s.data.size
                }
            }
            // ТЗ прошивки 5.5.1 (к), 5.5.6
            val r = FlashTools.check(plan.segments, { a, n -> mem.readBlock(a, n) }) { d, t -> progress(FlashStep.VERIFY, d.toLong(), t.toLong()) }
            if (!r.ok) {
                return@operation Result(false, FlashErrorKind.VERIFY_FAILED, "Ошибка сверки: ${r.mismatches} байт не совпадают, первый — ${"0x%08X".format(r.first.first().address)}. Ядро не запущено.", copy, r.first)
            }
            val running = if (run) {
                progress(FlashStep.START, 0, 1)
                !tw.reset().halted
            } else {
                false
            }
            Result(true, null, "Прошито и сверено: ${FlashPlan.kb(plan.bytesToWrite)}, ${FlashPlan.unitsText(plan.units)}." + if (running) " Программа запущена." else " Ядро остановлено.", copy, running = running)
        }

    /** Стирание единиц или всей Flash (ТЗ прошивки 5.4.1); ядро остаётся остановленным (п. 5.4.3). */
    fun erase(units: List<EraseUnit>?, onProgress: FlashProgress = { _, _, _ -> true }): FlashOutcome =
        operation {
            val progress = track(onProgress)
            val list = units ?: layout.units
            checkProtection(list)
            withTargetPrepared(progress) {
                if (units == null) {
                    progress(FlashStep.ERASE, 0, 1)
                    ctl.massErase()
                    progress(FlashStep.ERASE, 1, 1)
                } else {
                    eraseUnits(units, progress)
                }
            }
            checkErased(list, progress)
            Result(true, null, if (units == null) "Flash стёрта полностью. Ядро остановлено." else "Стёрто: ${FlashPlan.unitsText(units)}. Ядро остановлено.", null)
        }

    private class Result(
        val ok: Boolean,
        val kind: FlashErrorKind?,
        val message: String,
        val backup: List<Segment>?,
        val mismatches: List<FlashTools.Mismatch> = emptyList(),
        val running: Boolean = false,
    )

    private fun refuse(reason: String): Nothing = throw FlashException(FlashErrorKind.REFUSED, "Отказ: $reason")

    private fun steps(): List<StepTime> = stepTimes.map { (k, v) -> StepTime(k, v[1] - v[0], v[2]) }

    private inline fun operation(body: () -> Result): FlashOutcome {
        val t0 = clock.now()
        changed = false
        backupData = null
        stepTimes.clear()
        lastMark = t0
        return try {
            val r = body()
            FlashOutcome(r.ok, r.kind, r.message, null, clock.now() - t0, r.running, r.backup, r.mismatches, steps())
        } catch (e: FlashException) {
            val kind = if (changed && e.kind == FlashErrorKind.CANCELLED) FlashErrorKind.INCOMPLETE else e.kind
            FlashOutcome(false, kind, e.message ?: kind.title, e.hint ?: hintFor(kind), clock.now() - t0, false, backupData, steps = steps())
        } catch (e: StLinkException) {
            // ТЗ прошивки 5.5.4, 5.5.5: WAIT и потеря связи не повторяются.
            val kind = if (changed) FlashErrorKind.INCOMPLETE else FlashErrorKind.CANCELLED
            FlashOutcome(false, kind, "${kind.title}: ${e.message}", hintFor(kind), clock.now() - t0, false, backupData, steps = steps())
        } catch (e: FlashTools.CancelledException) {
            val kind = if (changed) FlashErrorKind.INCOMPLETE else FlashErrorKind.CANCELLED
            FlashOutcome(false, kind, "Отменено пользователем.", hintFor(kind), clock.now() - t0, false, backupData, steps = steps())
        }
    }

    private fun hintFor(kind: FlashErrorKind): String? = when (kind) {
        FlashErrorKind.INCOMPLETE -> "Переподключите адаптер и прошейте заново. Если ядро недоступно — подключитесь под сбросом (NRST)."
        FlashErrorKind.FLASH_TIMEOUT -> "Проверьте питание платы и повторите."
        FlashErrorKind.VERIFY_FAILED -> "Повторите прошивку; при повторной ошибке проверьте питание и кабель SWD."
        else -> null
    }

    // ТЗ прошивки 3.1.4: RDP и WRP читаются до любых изменений.
    private fun checkProtection(units: List<EraseUnit>) {
        if (!ctl.readoutUnprotected()) refuse("включена защита чтения (RDP ≠ 0)")
        val wrp = ctl.writeProtected()
        val hit = units.filter { it.index in wrp }
        if (hit.isNotEmpty()) refuse("защита записи (WRP) на: ${FlashPlan.unitsText(hit)}")
    }

    /**
     * ТЗ прошивки 3.1.5 (р.1.2), 5.2.1: копия только тех единиц, что будут стёрты, блоками по 1 КБ;
     * смежные единицы читаются одним участком. Остальная Flash не меняется и в копии не нужна.
     */
    private fun readUnits(units: List<EraseUnit>, progress: FlashProgress): List<Segment> {
        val total = units.sumOf { it.size.toLong() }
        val ranges = ArrayList<Pair<Long, Int>>()
        for (u in units) {
            val last = ranges.lastOrNull()
            if (last != null && last.first + last.second == u.address) {
                ranges[ranges.size - 1] = last.first to last.second + u.size
            } else {
                ranges += u.address to u.size
            }
        }
        var base = 0L
        return ranges.map { (a, n) ->
            val data = FlashTools.read(a, n, { x, k -> mem.readBlock(x, k) }) { d -> progress(FlashStep.BACKUP, base + d, total) }
            base += n
            Segment(a, data)
        }
    }

    /**
     * Сброс с остановкой, остановка сторожевых таймеров, разблокировка; после — блокировка
     * при любом исходе и восстановление DBGMCU (ТЗ прошивки 3.1.7, 3.1.8, 5.4.1, 5.5.1).
     */
    private inline fun withTargetPrepared(progress: FlashProgress, body: () -> Unit) {
        if (!progress(FlashStep.HALT, 0, 1)) throw FlashException(FlashErrorKind.CANCELLED, "Отменено до начала изменений.")
        changed = true
        tw.resetHalt()
        val dbg = dbgmcu()
        val saved = mem.read32(dbg.first)
        tw.write32(dbg.first, saved or dbg.second)
        var failure: Throwable? = null
        try {
            ctl.unlock()
            body()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                ctl.lock()
                tw.write32(dbg.first, saved)
            } catch (e: Exception) {
                if (failure == null) throw e
            }
        }
    }

    /** DBGMCU и биты остановки IWDG/WWDG (ТЗ прошивки 3.1.7, приложение A). */
    private fun dbgmcu(): Pair<Long, Int> = when (layout.family) {
        FlashFamily.F4 -> DBGMCU_APB1_FZ_F4 to ((1 shl 11) or (1 shl 12))
        FlashFamily.F1 -> DBGMCU_CR_F1 to ((1 shl 8) or (1 shl 9))
    }

    // ТЗ прошивки 5.5.3: отмена только между единицами.
    private fun eraseUnits(units: List<EraseUnit>, progress: FlashProgress) {
        val total = units.sumOf { it.size.toLong() }
        var done = 0L
        if (!progress(FlashStep.ERASE, 0, total)) throw FlashException(FlashErrorKind.INCOMPLETE, "Стирание отменено пользователем.")
        for (u in units) {
            ctl.eraseUnit(u)
            done += u.size
            if (!progress(FlashStep.ERASE, done, total)) throw FlashException(FlashErrorKind.INCOMPLETE, "Стирание отменено пользователем после ${u.title}.")
        }
    }

    // ТЗ прошивки 5.4.2: начало и конец каждой единицы — 0xFF.
    private fun checkErased(units: List<EraseUnit>, progress: FlashProgress) {
        units.forEachIndexed { i, u ->
            val n = minOf(ERASE_SAMPLE_BYTES, u.size)
            val spots = if (u.size > n) listOf(u.address, u.end - n) else listOf(u.address)
            for (a in spots) {
                val data = FlashTools.read(a, n, { x, k -> mem.readBlock(x, k) })
                val bad = data.indexOfFirst { it != 0xFF.toByte() }
                if (bad >= 0) {
                    throw FlashException(FlashErrorKind.VERIFY_FAILED, "После стирания ${u.title} по адресу ${"0x%08X".format(a + bad)} не 0xFF.")
                }
            }
            progress(FlashStep.ERASE_CHECK, i + 1L, units.size.toLong())
        }
    }

    companion object {
        const val ERASE_SAMPLE_BYTES = 1024
        const val DBGMCU_APB1_FZ_F4 = 0xE0042008L
        const val DBGMCU_CR_F1 = 0xE0042004L
    }
}

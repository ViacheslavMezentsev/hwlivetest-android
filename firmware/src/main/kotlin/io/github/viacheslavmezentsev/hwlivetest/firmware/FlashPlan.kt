package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.util.Locale

/** Сведения о цели, нужные плану без обращения к её памяти (ТЗ прошивки 5.1.1). */
data class PlanTarget(
    /** DEV_ID опознания; null — цель не опознана. */
    val devId: Int?,
    val flashKb: Int?,
    /** Напряжение цели по адаптеру, В; null — не измеряется. */
    val voltage: Double?,
    val mcu: String,
)

/** План прошивки: что будет стёрто и записано, что потеряется, проверки (ТЗ прошивки 5.1). */
class FlashPlan(
    val layout: FlashLayout?,
    val segments: List<Segment>,
    val units: List<EraseUnit>,
    val bytesToWrite: Int,
    /** Байт в стираемых единицах вне образа: они будут потеряны (п. 5.1.2). */
    val bytesLost: Int,
    val imageSha256: String,
    val fileSha256: String?,
    val psize: Int?,
    val estimateMs: Long,
    /** Причины отказа (п. 3.1.4, 5.1.3); пусто — прошивка разрешена. */
    val refusals: List<String>,
    val warnings: List<String>,
) {
    val allowed: Boolean get() = refusals.isEmpty()

    fun lines(): List<String> = buildList {
        add("Образ: ${segments.size} участк., ${kb(bytesToWrite)}")
        segments.forEach { add("  ${hex(it.address)}–${hex(it.end - 1)} (${kb(it.data.size)})") }
        add("SHA-256 образа: $imageSha256")
        fileSha256?.let { add("SHA-256 файла: $it") }
        if (units.isNotEmpty()) {
            add("Стереть: ${unitsText(units)}, ${kb(units.sumOf { it.size })}")
            add(if (bytesLost > 0) "Будет потеряно вне образа: ${kb(bytesLost)}" else "Данных вне образа в этих единицах нет")
        }
        psize?.let { add("Ширина записи: $it бит") }
        add("Оценка времени: ${String.format(Locale.ROOT, "%.0f", estimateMs / 1000.0)} с")
        warnings.forEach { add("Внимание: $it") }
        refusals.forEach { add("Отказ: $it") }
    }

    companion object {
        const val MIN_VOLTAGE_V = 1.8

        // Оценка времени (типичные значения, уточняются замером TC-20).
        private const val F4_ERASE_MS_PER_KB = 16L // ~2 с на 128 КБ
        private const val F1_ERASE_MS_PER_PAGE = 25L
        private const val WRITE_BYTES_PER_S = 8_000L
        private const val READ_BYTES_PER_S = 40_000L

        /** ТЗ прошивки 4.2.2: PSIZE по напряжению; без измерения — 16 бит с предупреждением. */
        fun psizeFor(voltage: Double?): Int = when {
            voltage == null -> 16
            voltage >= 2.7 -> 32
            voltage >= 2.1 -> 16
            else -> 8
        }

        /**
         * Построить план без обращения к цели (ТЗ прошивки 5.1.1–5.1.3, 3.1.4). [expectedSha256] —
         * сумма из файла `*.sha256` или введённая пользователем; сравнивается с [fileSha256] (сумма
         * файла HEX), а если её нет — с суммой образа.
         */
        fun build(
            segments: List<Segment>,
            target: PlanTarget,
            fileSha256: String? = null,
            expectedSha256: String? = null,
        ): FlashPlan {
            val refusals = ArrayList<String>()
            val warnings = ArrayList<String>()
            val image = segments.fold(ByteArray(0)) { acc, s -> acc + s.data }
            val imageSha = IntelHex.sha256(image)
            val bytes = image.size

            val layout = when {
                target.devId == null -> {
                    refusals += "цель не опознана"
                    null
                }
                FlashLayout.familyOf(target.devId) == null -> {
                    refusals += "семейство (DEV_ID 0x%03X) не поддерживается: только STM32F1 и STM32F4".format(target.devId)
                    null
                }
                else -> FlashLayout.of(target.devId, target.flashKb).also {
                    if (it == null) refusals += "размер Flash не прочитан"
                }
            }

            var units = emptyList<EraseUnit>()
            var lost = 0
            if (layout != null) {
                val outside = segments.filter { !layout.contains(it.address, it.data.size) }
                if (outside.isNotEmpty()) {
                    val s = outside.first()
                    refusals += "образ выходит за Flash цели (${hex(layout.start)}–${hex(layout.end - 1)}): участок ${hex(s.address)}–${hex(s.end - 1)}"
                } else {
                    // ТЗ прошивки 5.1.2: только единицы, пересекающиеся с участками образа.
                    units = layout.units.filter { u -> segments.any { u.overlaps(it.address, it.end) } }
                    val covered = units.sumOf { u ->
                        segments.sumOf { s -> maxOf(0L, minOf(u.end, s.end) - maxOf(u.address, s.address)) }
                    }
                    lost = (units.sumOf { it.size.toLong() } - covered).toInt()
                }
            }

            // ТЗ прошивки 3.1.4: напряжение.
            target.voltage?.let { if (it < MIN_VOLTAGE_V) refusals += "напряжение цели ${"%.2f".format(Locale.ROOT, it)} В ниже $MIN_VOLTAGE_V В" }
            val psize = if (layout?.family == FlashFamily.F4) psizeFor(target.voltage) else null
            if (psize != null && target.voltage == null) warnings += "напряжение цели не измеряется — запись по 16 бит"

            // ТЗ прошивки 5.1.3: сверка суммы.
            if (expectedSha256 != null) {
                val want = expectedSha256.trim().lowercase(Locale.ROOT).substringBefore(' ')
                val have = fileSha256 ?: imageSha
                if (want != have) refusals += "SHA-256 не совпадает с ожидаемой ($want)"
            }

            val eraseMs = when (layout?.family) {
                FlashFamily.F4 -> units.sumOf { it.size / 1024 * F4_ERASE_MS_PER_KB }
                FlashFamily.F1 -> units.size * F1_ERASE_MS_PER_PAGE
                null -> 0L
            }
            val estimate = eraseMs + bytes * 1000L / WRITE_BYTES_PER_S + bytes * 1000L / READ_BYTES_PER_S
            return FlashPlan(layout, segments, units, bytes, lost, imageSha, fileSha256, psize, estimate, refusals, warnings)
        }

        fun unitsText(units: List<EraseUnit>): String {
            if (units.isEmpty()) return "—"
            val kind = if (units.first().size >= 16 * 1024) "секторы" else "страницы"
            val ranges = ArrayList<String>()
            var a = units.first().index
            var b = a
            for (u in units.drop(1)) {
                if (u.index == b + 1) {
                    b = u.index
                } else {
                    ranges += if (a == b) "$a" else "$a–$b"
                    a = u.index
                    b = a
                }
            }
            ranges += if (a == b) "$a" else "$a–$b"
            return "$kind ${ranges.joinToString(", ")}"
        }

        internal fun hex(a: Long): String = "0x%08X".format(a)

        fun kb(n: Int): String = if (n < 1024) "$n байт" else String.format(Locale.ROOT, "%.1f КБ", n / 1024.0)
    }
}

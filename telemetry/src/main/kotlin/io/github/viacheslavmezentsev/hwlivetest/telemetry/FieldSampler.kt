package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Опрос набора полей. Поля с одинаковым адресом за один проход читаются один раз,
 * соседние — одним блоком.
 * Значения вне min/max не попадают в ряд, но показываются в [Reading].
 */
class FieldSampler(fields: List<Field>, capacity: Int = DEFAULT_CAPACITY) {
    val fields: List<Field> = fields.toList()
    private val series: Map<String, TimeSeries> = this.fields.associate { it.topic to TimeSeries(capacity) }

    data class Reading(
        val spec: Field,
        val raw: Int?,
        val value: Double?,
        val error: String?,
    ) {
        val outOfRange: Boolean get() = value != null && !spec.inRange(value)
    }

    fun series(topic: String): TimeSeries? = series[topic]

    fun clear() = series.values.forEach { it.clear() }

    /**
     * Прочитать [only] (или все поля) в момент [timeMs]. Соседние адреса читаются одним
     * блоком через [readBlock] (см. [ReadPlan]); если блок не прочитался, его слова
     * читаются по одному. Исключение, для которого [isFatal] вернул true, пробрасывается
     * наружу (например, обрыв USB).
     */
    fun sample(
        timeMs: Long,
        readBlock: (address: Long, words: Int) -> IntArray,
        only: Collection<String>? = null,
        isFatal: (Exception) -> Boolean = { false },
    ): List<Reading> {
        val wanted = fields.filter { only == null || it.topic in only }
        val values = HashMap<Long, Result<Int>>()
        fun attempt(a: Long, words: Int): Result<IntArray> = try {
            Result.success(readBlock(a, words))
        } catch (e: Exception) {
            if (isFatal(e)) throw e
            Result.failure(e)
        }
        for (b in ReadPlan.blocks(wanted.map { it.address })) {
            val r = attempt(b.address, b.words)
            val words = r.getOrNull()
            for (f in wanted) {
                if (f.address !in b || values.containsKey(f.address)) continue
                values[f.address] = when {
                    words != null -> Result.success(words[((f.address - b.address) / 4).toInt()])
                    b.words == 1 -> Result.failure(r.exceptionOrNull()!!)
                    else -> attempt(f.address, 1).map { it[0] }
                }
            }
        }
        val out = ArrayList<Reading>()
        for (f in wanted) {
            val r = values[f.address]
            val raw = r?.getOrNull()
            if (raw == null) {
                out += Reading(f, null, null, r?.exceptionOrNull()?.message ?: "адрес не выровнен")
                continue
            }
            val v = f.decode(raw)
            if (v.isFinite() && f.inRange(v)) series[f.topic]?.add(timeMs, v)
            out += Reading(f, raw, v, null)
        }
        return out
    }

    /** Чтение по одному слову — для отладчиков без блочного чтения и тестов. */
    fun sample(
        timeMs: Long,
        read: (Long) -> Int,
        only: Collection<String>? = null,
        isFatal: (Exception) -> Boolean = { false },
    ): List<Reading> = sample(timeMs, { a, n -> IntArray(n) { read(a + 4L * it) } }, only, isFatal)

    companion object {
        /** 5 минут при 20 Гц с запасом. */
        const val DEFAULT_CAPACITY = 6500
    }
}

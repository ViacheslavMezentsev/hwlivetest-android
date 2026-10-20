package io.github.viacheslavmezentsev.hwlivetest.telemetry

/**
 * Опрос набора полей. Поля с одинаковым адресом за один проход читаются один раз.
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
     * Прочитать [only] (или все поля) в момент [timeMs]. Исключение, для которого
     * [isFatal] вернул true, пробрасывается наружу (например, обрыв USB).
     */
    fun sample(
        timeMs: Long,
        read: (Long) -> Int,
        only: Collection<String>? = null,
        isFatal: (Exception) -> Boolean = { false },
    ): List<Reading> {
        val cache = HashMap<Long, Result<Int>>()
        val out = ArrayList<Reading>()
        for (f in fields) {
            if (only != null && f.topic !in only) continue
            val r = cache.getOrPut(f.address) {
                try {
                    Result.success(read(f.address))
                } catch (e: Exception) {
                    if (isFatal(e)) throw e
                    Result.failure(e)
                }
            }
            val raw = r.getOrNull()
            if (raw == null) {
                out += Reading(f, null, null, r.exceptionOrNull()?.message ?: "ошибка чтения")
                continue
            }
            val v = f.decode(raw)
            if (v.isFinite() && f.inRange(v)) series[f.topic]?.add(timeMs, v)
            out += Reading(f, raw, v, null)
        }
        return out
    }

    companion object {
        /** 5 минут при 20 Гц с запасом. */
        const val DEFAULT_CAPACITY = 6500
    }
}

package io.github.viacheslavmezentsev.hwlivetest.telemetry

import java.util.Locale
import kotlin.math.abs

/** Как трактовать прочитанное 32-битное слово (ключ `kind` в addresses.json). */
enum class FieldKind(val key: String) {
    FLOAT32("float32"),
    UINT32("uint32"),
    INT32("int32"),
    UINT32_SECONDS("uint32_seconds");

    companion object {
        fun fromKey(key: String?): FieldKind? = entries.firstOrNull { it.key == key }
    }
}

/** Поле для чтения из памяти MCU. Ключ — [topic], адреса у разных полей могут совпадать. */
data class Field(
    val topic: String,
    val kind: FieldKind,
    val address: Long,
    val expr: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    /** Для полей регистров SVD: значение = (слово & mask) >> shift. */
    val shift: Int? = null,
    val mask: Long? = null,
    /** Тип сообщения ROS из ключа `message` (std_msgs/Float32 и т. п.). */
    val message: String? = null,
    /** Исходный тип и единица компиляции ELF; отсутствуют у старых таблиц. */
    val sourceType: String? = null,
    val sourceFile: String? = null,
    val sizeBits: Int? = null,
) {
    val typeLabel: String get() = sourceType?.takeIf { it.isNotBlank() } ?: kind.key
    /** Короткое имя для списка: последний сегмент topic. */
    val shortName: String get() = topic.trimEnd('/').substringAfterLast('/').ifEmpty { topic }

    /** Значение из прочитанного слова. */
    fun decode(raw: Int): Double {
        val word = applyMask(raw)
        return when (kind) {
            FieldKind.FLOAT32 -> Float.fromBits(word.toInt()).toDouble()
            // ТЗ прошивки 5.9.5: расширение знака после маски и сдвига.
            FieldKind.INT32 -> when (sizeBits) {
                8 -> word.toByte().toDouble()
                16 -> word.toShort().toDouble()
                else -> word.toInt().toDouble()
            }
            FieldKind.UINT32, FieldKind.UINT32_SECONDS -> word.toDouble()
        }
    }

    /** Попадает ли значение в границы min/max (если заданы). */
    fun inRange(v: Double): Boolean = (min == null || v >= min) && (max == null || v <= max)

    fun format(v: Double): String = when (kind) {
        FieldKind.FLOAT32 -> formatFloat(v)
        FieldKind.INT32, FieldKind.UINT32 -> v.toLong().toString()
        FieldKind.UINT32_SECONDS -> {
            val s = v.toLong()
            "%d с (%d:%02d:%02d)".format(Locale.ROOT, s, s / 3600, s / 60 % 60, s % 60)
        }
    }

    private fun applyMask(raw: Int): Long {
        var w = raw.toLong() and 0xFFFFFFFFL
        mask?.let { w = w and it }
        shift?.let { w = w ushr it }
        return w
    }

    companion object {
        /**
         * Два знака после запятой (просьба владельца, 24.10.2026): ширина числа не скачет, и его
         * удобно читать на ходу. Очень малые и очень большие — с экспонентой, тоже два знака.
         */
        fun formatFloat(v: Double): String = when {
            v.isNaN() -> "NaN"
            v.isInfinite() -> if (v > 0) "+∞" else "−∞"
            v == 0.0 -> "0.00"
            abs(v) >= 1e9 || abs(v) < 5e-3 -> "%.2e".format(Locale.ROOT, v)
            else -> "%.2f".format(Locale.ROOT, v)
        }
    }
}

/** Содержимое addresses.json: читаются только `fields`, остальным ключам доверяем. */
data class AddressesFile(val fields: List<Field>, val warnings: List<String>) {

    companion object {
        fun parse(text: String): AddressesFile {
            val root = Json.parse(text) as? Map<*, *>
                ?: throw IllegalArgumentException("addresses.json: ожидался объект JSON")
            val list = root["fields"] as? List<*>
                ?: throw IllegalArgumentException("addresses.json: нет массива fields")
            val warnings = mutableListOf<String>()
            val fields = mutableListOf<Field>()
            val seen = HashSet<String>()
            list.forEachIndexed { index, item ->
                val m = item as? Map<*, *>
                if (m == null) {
                    warnings += "fields[$index]: не объект, пропущено"
                    return@forEachIndexed
                }
                val topic = m["topic"] as? String
                if (topic.isNullOrEmpty()) {
                    warnings += "fields[$index]: нет topic, пропущено"
                    return@forEachIndexed
                }
                val address = addressOf(m)
                if (address == null) {
                    warnings += "$topic: нет address/address_hex, пропущено"
                    return@forEachIndexed
                }
                if (!seen.add(topic)) {
                    warnings += "$topic: повтор topic, пропущено"
                    return@forEachIndexed
                }
                val kindKey = m["kind"] as? String
                val kind = FieldKind.fromKey(kindKey) ?: FieldKind.UINT32.also {
                    if (kindKey != null) warnings += "$topic: неизвестный kind \"$kindKey\", читаю как uint32"
                }
                fields += Field(
                    topic = topic,
                    kind = kind,
                    address = address,
                    expr = m["expr"] as? String,
                    min = (m["min"] as? Number)?.toDouble(),
                    max = (m["max"] as? Number)?.toDouble(),
                    shift = (m["shift"] as? Number)?.toInt(),
                    mask = (m["mask"] as? Number)?.toLong(),
                    message = m["message"] as? String,
                    sourceType = (m["source_type"] ?: m["description"]) as? String,
                    sourceFile = m["source_file"] as? String,
                    sizeBits = (m["size_bits"] as? Number)?.toInt(),
                )
            }
            return AddressesFile(fields, warnings)
        }

        private fun addressOf(m: Map<*, *>): Long? {
            (m["address"] as? Long)?.let { return it and 0xFFFFFFFFL }
            val hex = (m["address_hex"] as? String)?.trim() ?: return null
            return hex.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        }
    }
}

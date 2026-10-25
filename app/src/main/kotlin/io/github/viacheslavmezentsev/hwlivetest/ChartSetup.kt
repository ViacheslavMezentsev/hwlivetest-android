package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import java.security.MessageDigest

/** ТЗ прошивки 5.21: настройки привязаны к составу и адресам таблицы, а не к её имени. */
object ChartSetup {
    fun key(fields: List<Field>): String {
        val description = fields.sortedBy { it.topic }.joinToString("\n") {
            "${it.topic}|${it.address}|${it.kind}|${it.mask}|${it.shift}|${it.sizeBits}"
        }
        return MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun topics(saved: List<String>, available: List<Field>, requested: String?): List<String> {
        val valid = available.map { it.topic }.toSet()
        val selected = saved.filter { it in valid }.distinct().take(6)
        return if (requested != null && requested in valid && requested !in selected) listOf(requested)
            else selected.ifEmpty { listOfNotNull(requested?.takeIf { it in valid }) }
    }

    fun label(field: Field, all: List<Field>): String =
        if (all.count { it.shortName == field.shortName } > 1)
            field.shortName + " · " + field.topic.trimEnd('/').substringBeforeLast('/', "").trimStart('/')
        else field.shortName


    // ТЗ прошивки 5.22: палитра владельца, красный второй; порядок — порядок отметок.
    val PALETTE = intArrayOf(
        0xFF005FAF.toInt(), 0xFFAF0000.toInt(), 0xFF007D57.toInt(), 0xFF907000.toInt(),
        0xFF77519B.toInt(), 0xFF007575.toInt(), 0xFFAF005F.toInt(), 0xFF5F8700.toInt(),
        0xFFD75F5F.toInt(), 0xFF00AFFF.toInt(), 0xFF00AF5F.toInt(), 0xFFAF5F00.toInt(),
        0xFFAF5FFF.toInt(), 0xFF5F5F87.toInt(), 0xFF875F00.toInt(), 0xFFD75FAF.toInt(),
    )

    // ТЗ прошивки 5.23: тот же порядок оттенков с контрастом для тёмного фона.
    private val DARK_PALETTE = intArrayOf(
        0xFF58A6FF.toInt(), 0xFFFF6B6B.toInt(), 0xFF3FBF91.toInt(), 0xFFD8B444.toInt(),
        0xFFB48AE5.toInt(), 0xFF47C5C5.toInt(), 0xFFE56CAD.toInt(), 0xFFA2BF42.toInt(),
        0xFFFF9191.toInt(), 0xFF46C8FF.toInt(), 0xFF39D98A.toInt(), 0xFFF4AA44.toInt(),
        0xFFCA8AFF.toInt(), 0xFF9999C5.toInt(), 0xFFC9AB43.toInt(), 0xFFF18ECD.toInt(),
    )

    fun color(index: Int, dark: Boolean): Int = (if (dark) DARK_PALETTE else PALETTE)[index]

    fun colors(topics: List<String>): Map<String, Int> =
        topics.distinct().take(PALETTE.size).withIndex().associate { it.value to it.index }
}

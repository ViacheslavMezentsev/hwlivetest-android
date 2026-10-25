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
        0xFF005FAF.toInt(), 0xFFAF0000.toInt(), 0xFF00875F.toInt(), 0xFFAF8700.toInt(),
        0xFF875FAF.toInt(), 0xFF008787.toInt(), 0xFFAF005F.toInt(), 0xFF5F8700.toInt(),
        0xFFD75F5F.toInt(), 0xFF00AFFF.toInt(), 0xFF00AF5F.toInt(), 0xFFAF5F00.toInt(),
        0xFFAF5FFF.toInt(), 0xFF5F5F87.toInt(), 0xFF875F00.toInt(), 0xFFD75FAF.toInt(),
    )

    fun colors(topics: List<String>): Map<String, Int> =
        topics.distinct().take(PALETTE.size).withIndex().associate { it.value to it.index }
}

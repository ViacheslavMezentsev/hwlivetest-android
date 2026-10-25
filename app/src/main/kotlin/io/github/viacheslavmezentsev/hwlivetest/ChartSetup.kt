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


    fun colors(topics: List<String>, all: List<Field>, previous: Map<String, Int>, size: Int): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (topic in topics) previous[topic]?.takeIf { it in 0 until size && it !in result.values }?.let { result[topic] = it }
        for (topic in topics.sorted()) if (topic !in result) {
            val preferred = colorIndex(topic, all) % size
            result[topic] = (0 until size).map { (preferred + it) % size }.first { it !in result.values }
        }
        return result
    }

    fun colorIndex(topic: String, all: List<Field>): Int =
        all.map { it.topic }.sorted().indexOf(topic).coerceAtLeast(0)
}

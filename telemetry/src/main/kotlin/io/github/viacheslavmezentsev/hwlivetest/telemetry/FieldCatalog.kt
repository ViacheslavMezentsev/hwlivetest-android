package io.github.viacheslavmezentsev.hwlivetest.telemetry

/** ТЗ прошивки 5.9.7: фильтры меняют представление, но не топики записи. */
object FieldCatalog {
    enum class Grouping(val title: String) { SOURCE("Исходник"), TOPIC("Топик"), TYPE("Тип"), NONE("Без групп") }

    fun filter(fields: List<Field>, query: String, type: String = ""): List<Field> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return fields.filter { f ->
            (type.isEmpty() || f.typeLabel == type) && words.all { word ->
                listOfNotNull(f.topic, f.expr, f.sourceFile, f.typeLabel, "0x%08X".format(f.address))
                    .any { it.contains(word, ignoreCase = true) }
            }
        }
    }

    fun group(fields: List<Field>, grouping: Grouping): Map<String, List<Field>> = fields.groupBy { f ->
        when (grouping) {
            Grouping.SOURCE -> f.sourceFile?.takeIf { it.isNotBlank() } ?: "Исходник не указан"
            Grouping.TOPIC -> f.topic.substringBeforeLast('/', "").ifEmpty { "/" }
            Grouping.TYPE -> f.typeLabel
            Grouping.NONE -> "Все сигналы"
        }
    }.toSortedMap()
}

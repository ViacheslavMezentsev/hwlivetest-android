package io.github.viacheslavmezentsev.hwlivetest

/**
 * Журнал сеанса. Строки обмена (`>`/`<`) совпадают с форматом записей в
 * `stlink/src/test/resources/captures`, служебные строки начинаются с `#`.
 */
class SessionLog(private val maxLines: Int = 3000) {
    data class Entry(val id: Long, val text: String, val debugOnly: Boolean = false)
    private var nextId = 0L
    private val lines = ArrayDeque<Entry>()

    @Synchronized
    fun add(line: String, debugOnly: Boolean = false) {
        lines.addLast(Entry(nextId++, line, debugOnly))
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun text(): String = lines.joinToString("\n") { it.text }

    /** Последние [n] строк — для показа на экране (весь журнал копируется кнопкой). */
    @Synchronized
    fun tail(n: Int): String {
        val from = maxOf(0, lines.size - n)
        val skipped = if (from > 0) "… ещё $from строк выше (полностью — «Копировать журнал»)\n" else ""
        return skipped + lines.subList(from, lines.size).joinToString("\n") { it.text }
    }

    @Synchronized
    fun snapshot(): List<Entry> = lines.toList()

    companion object {
        /** Обмен с адаптером: строки `>`/`<` и сокращённые длинные ответы. */
        fun isProtocol(line: String): Boolean = line.startsWith(">") || line.startsWith("<") || line.startsWith("# <")
    }
}

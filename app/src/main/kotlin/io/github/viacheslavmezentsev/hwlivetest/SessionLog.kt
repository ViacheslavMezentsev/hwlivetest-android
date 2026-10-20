package io.github.viacheslavmezentsev.hwlivetest

/**
 * Журнал сеанса. Строки обмена (`>`/`<`) совпадают с форматом записей в
 * `stlink/src/test/resources/captures`, служебные строки начинаются с `#`.
 */
class SessionLog(private val maxLines: Int = 3000) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** Последние [n] строк — для показа на экране (весь журнал копируется кнопкой). */
    @Synchronized
    fun tail(n: Int): String {
        val from = maxOf(0, lines.size - n)
        val skipped = if (from > 0) "… ещё $from строк выше (полностью — «Копировать журнал»)\n" else ""
        return skipped + lines.subList(from, lines.size).joinToString("\n")
    }

    companion object {
        /** Обмен с адаптером: строки `>`/`<` и сокращённые длинные ответы. */
        fun isProtocol(line: String): Boolean = line.startsWith(">") || line.startsWith("<") || line.startsWith("# <")
    }
}

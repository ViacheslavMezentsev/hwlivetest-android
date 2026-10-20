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
}

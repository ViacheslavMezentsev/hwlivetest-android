package io.github.viacheslavmezentsev.hwlivetest.firmware

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Запись истории операций (ТЗ прошивки 5.7.1): одна строка JSON в `history.jsonl`. Без UID
 * микроконтроллера и серийного номера адаптера.
 */
data class HistoryEntry(
    val timeMs: Long,
    val action: String,
    val adapter: String,
    val adapterFirmware: String,
    val mcu: String,
    val file: String?,
    val sha256: String?,
    val units: String?,
    val durationMs: Long,
    val result: String,
    val error: String?,
) {
    fun toJson(): String = buildString {
        append('{')
        field("time", iso(timeMs))
        append(',')
        field("action", action)
        append(',')
        field("adapter", adapter)
        append(',')
        field("adapterFirmware", adapterFirmware)
        append(',')
        field("mcu", mcu)
        append(',')
        field("file", file)
        append(',')
        field("sha256", sha256)
        append(',')
        field("units", units)
        append(",\"durationMs\":").append(durationMs).append(',')
        field("result", result)
        append(',')
        field("error", error)
        append('}')
    }

    /** Текст отчёта для отправки (ТЗ прошивки 5.7.2). */
    fun report(details: List<String> = emptyList()): String = buildString {
        appendLine("HW Live Test — ${action}")
        appendLine("Время: ${local(timeMs)}")
        appendLine("Результат: $result")
        error?.let { appendLine("Ошибка: $it") }
        appendLine("MCU: $mcu")
        appendLine("Адаптер: $adapter, прошивка $adapterFirmware")
        file?.let { appendLine("Файл: $it") }
        sha256?.let { appendLine("SHA-256: $it") }
        units?.let { appendLine("Стирание: $it") }
        appendLine("Длительность: ${"%.1f".format(Locale.ROOT, durationMs / 1000.0)} с")
        details.forEach { appendLine(it) }
    }

    private fun StringBuilder.field(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) append("null") else append('"').append(escape(value)).append('"')
    }

    companion object {
        fun escape(s: String): String = buildString {
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }

        fun iso(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun local(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date(ms))
    }
}

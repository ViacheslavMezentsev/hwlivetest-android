package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import io.github.viacheslavmezentsev.hwlivetest.firmware.HistoryEntry
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** История операций прошивки: `history.jsonl` в папке приложения (ТЗ прошивки 5.7.1, 5.7.3). */
object FlashHistory {
    private fun file(c: Context) = File(c.filesDir, "history.jsonl")

    fun append(c: Context, e: HistoryEntry) {
        file(c).appendText(e.toJson() + "\n")
    }

    /** Строки для показа, новые сверху. */
    fun lines(c: Context, max: Int = 30): List<String> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.takeLast(max).reversed().map { line ->
            try {
                val j = JSONObject(line)
                val time = localTime(j.optString("time"))
                val err = j.optString("error").takeIf { !j.isNull("error") && it.isNotEmpty() }
                buildString {
                    append("$time  ${j.optString("action")}: ${j.optString("result")}")
                    append("\n  ${j.optString("mcu")}")
                    if (!j.isNull("file")) append(", ${j.optString("file")}")
                    if (!j.isNull("units")) append(", ${j.optString("units")}")
                    append(", ${"%.1f".format(j.optLong("durationMs") / 1000.0)} с")
                    err?.let { append("\n  $it") }
                }
            } catch (_: Exception) {
                line
            }
        }
    }

    /** Время записи (UTC в файле) — в часовом поясе телефона. */
    private fun localTime(iso: String): String = try {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(utc.parse(iso)!!)
    } catch (_: Exception) {
        iso
    }

    fun clear(c: Context) {
        file(c).delete()
    }

    fun backupDir(c: Context): File = File(c.filesDir, "backups").apply { mkdirs() }
}

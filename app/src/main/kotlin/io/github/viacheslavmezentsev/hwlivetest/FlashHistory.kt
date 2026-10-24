package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import io.github.viacheslavmezentsev.hwlivetest.firmware.HistoryEntry
import org.json.JSONObject
import java.io.File

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
                val time = j.optString("time").replace('T', ' ').removeSuffix("Z")
                val err = j.optString("error").takeIf { !j.isNull("error") && it.isNotEmpty() }
                buildString {
                    append("$time UTC  ${j.optString("action")}: ${j.optString("result")}")
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

    fun clear(c: Context) {
        file(c).delete()
    }

    fun backupDir(c: Context): File = File(c.filesDir, "backups").apply { mkdirs() }
}

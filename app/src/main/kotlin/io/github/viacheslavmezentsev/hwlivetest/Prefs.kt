package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context

/** Настройки, которые переживают перезапуск приложения. */
object Prefs {
    fun recordRtt(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("record_rtt", false)
    fun setRecordRtt(c: Context, value: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("record_rtt", value).apply()

    fun rttHz(c: Context): Int = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("rtt_hz", 100)
        .takeIf { it in io.github.viacheslavmezentsev.hwlivetest.firmware.RttImageBuilder.sampleRates } ?: 100
    fun setRttHz(c: Context, hz: Int) {
        require(hz in io.github.viacheslavmezentsev.hwlivetest.firmware.RttImageBuilder.sampleRates)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("rtt_hz", hz).apply()
    }
    fun effectiveRtt(c: Context) = recordRtt(c) && Session.route.value.ready && runCatching { RttTrial.profile() }.isSuccess

    fun pollingHz(c: Context): Int = PollingRate.clamp(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("polling_hz", PollingRate.DEFAULT_HZ))
    fun setPollingHz(c: Context, hz: Int) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("polling_hz", PollingRate.clamp(hz)).apply()

    fun debugDetails(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("debug_details", false)
    fun setDebugDetails(c: Context, v: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("debug_details", v).apply()

    // ТЗ прошивки 5.20: ручная проверка SHA выключена по умолчанию.
    fun useSha(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("use_sha", false)
    fun setUseSha(c: Context, v: Boolean) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("use_sha", v).apply()


    // ТЗ прошивки 5.21: пользовательский набор каналов и вид графика для конкретной таблицы.
    fun chart(c: Context, key: String): Triple<List<String>, Long, Boolean?> {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val topics = p.getString("chart_topics_$key", "")!!.split('\n').filter { it.isNotEmpty() }
        val window = p.getLong("chart_window_$key", 30_000L).takeIf { it in listOf(30_000L, 60_000L, 300_000L) } ?: 30_000L
        val scale = p.getString("chart_scale_$key", null)?.toBooleanStrictOrNull()
        return Triple(topics, window, scale)
    }

    fun saveChart(c: Context, key: String, topics: List<String>, window: Long, scale: Boolean?) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("chart_topics_$key", topics.joinToString("\n"))
            .putLong("chart_window_$key", window)
            .putString("chart_scale_$key", scale?.toString()).apply()
    }

    fun firmwareUri(c: Context): String? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("firmware_uri", null)
    fun setFirmwareUri(c: Context, uri: String?) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("firmware_uri", uri).apply()
    }

    private const val FILE = "hwlivetest"
    private const val EXPERT = "expert_mode"
    private const val LOG_POLLING = "log_polling"
    private const val RECORD_FOLDER = "record_folder"
    private const val FLASH_BACKUP = "flash_backup"

    fun expert(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(EXPERT, false)

    fun setExpert(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(EXPERT, v).apply()

    fun logPolling(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(LOG_POLLING, false)

    fun setLogPolling(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(LOG_POLLING, v).apply()

    fun recordFolder(c: Context): String? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(RECORD_FOLDER, null)

    fun setRecordFolder(c: Context, v: String) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(RECORD_FOLDER, v).apply()

    /** Резервная копия Flash перед прошивкой (ТЗ прошивки 3.1.5), по умолчанию включена. */
    fun flashBackup(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(FLASH_BACKUP, true)

    fun setFlashBackup(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(FLASH_BACKUP, v).apply()
}

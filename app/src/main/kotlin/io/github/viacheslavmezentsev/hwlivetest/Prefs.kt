package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context

/** Настройки, которые переживают перезапуск приложения. */
object Prefs {
    private const val FILE = "hwlivetest"
    private const val EXPERT = "expert_mode"
    private const val LOG_POLLING = "log_polling"
    private const val RECORD_FOLDER = "record_folder"

    fun expert(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(EXPERT, false)

    fun setExpert(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(EXPERT, v).apply()

    fun logPolling(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(LOG_POLLING, false)

    fun setLogPolling(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(LOG_POLLING, v).apply()

    fun recordFolder(c: Context): String? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(RECORD_FOLDER, null)

    fun setRecordFolder(c: Context, v: String) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(RECORD_FOLDER, v).apply()
}

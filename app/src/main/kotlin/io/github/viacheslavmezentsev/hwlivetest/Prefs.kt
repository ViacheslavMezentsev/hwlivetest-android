package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context

/** Настройки, которые переживают перезапуск приложения. */
object Prefs {
    private const val FILE = "hwlivetest"
    private const val EXPERT = "expert_mode"
    private const val LOG_POLLING = "log_polling"

    fun expert(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(EXPERT, false)

    fun setExpert(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(EXPERT, v).apply()

    fun logPolling(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(LOG_POLLING, false)

    fun setLogPolling(c: Context, v: Boolean) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(LOG_POLLING, v).apply()
}

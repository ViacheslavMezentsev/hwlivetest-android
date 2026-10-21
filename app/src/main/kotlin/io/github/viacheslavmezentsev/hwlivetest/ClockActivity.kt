package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Базовый экран: в шапке узкой строкой — текущие дата и время до секунды, чтобы по
 * снимку экрана было видно, когда он сделан.
 */
abstract class ClockActivity : Activity() {
    private val clockHandler = Handler(Looper.getMainLooper())
    private val format = SimpleDateFormat("EEE dd.MM.yyyy HH:mm:ss", Locale("ru"))

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            actionBar?.subtitle = format.format(Date(now))
            clockHandler.postDelayed(this, 1000 - now % 1000 + 5)
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(tick)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(tick)
        super.onPause()
    }
}

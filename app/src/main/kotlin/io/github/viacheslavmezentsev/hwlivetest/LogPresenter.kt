package io.github.viacheslavmezentsev.hwlivetest

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Показывает хвост журнала в [view] не чаще раза в [periodMs] мс. Раньше каждая строка
 * журнала перерисовывала весь текст — при опросе полей это тормозило экран.
 */
class LogPresenter(private val view: TextView, private val lines: Int = 300, private val periodMs: Long = 300) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var pending = false

    private val update = Runnable {
        pending = false
        view.text = Session.log.tail(lines)
    }

    fun start() {
        Session.onLog = {
            if (!pending) {
                pending = true
                handler.postDelayed(update, periodMs)
            }
        }
        view.text = Session.log.tail(lines)
    }

    fun stop() {
        Session.onLog = null
        handler.removeCallbacks(update)
        pending = false
    }
}

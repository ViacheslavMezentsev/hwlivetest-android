package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field

/**
 * График одного поля. Опрос этого поля идёт с частотой [SAMPLE_PERIOD_MS], экран
 * перерисовывается каждые [REDRAW_PERIOD_MS]. Окно — 30 с, 1 мин или 5 мин; ряд хранит
 * 5 минут, поэтому окно можно переключать без потери данных.
 */
class ChartActivity : Activity() {

    private lateinit var field: Field
    private lateinit var chart: ChartView
    private lateinit var value: TextView
    private lateinit var stats: TextView
    private lateinit var pauseButton: Button
    private lateinit var windowButtons: List<Pair<Button, Long>>

    private val handler = Handler(Looper.getMainLooper())
    private var windowMs = 30_000L
    private var paused = false
    private var pausedAt = 0L

    @Volatile
    private var lastError: String? = null

    private val redraw = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REDRAW_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val topic = intent.getStringExtra(EXTRA_TOPIC)
        val f = topic?.let { Session.field(it) }
        if (f == null) {
            finish()
            return
        }
        field = f
        setContentView(R.layout.activity_chart)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = f.shortName

        findViewById<TextView>(R.id.chartTitle).text = f.topic
        findViewById<TextView>(R.id.chartInfo).text = buildString {
            append(f.address.hex32()).append("  ").append(f.kind.key)
            f.expr?.let { append('\n').append(it) }
            Session.check(f).warning?.let { append("\n⚠ ").append(it) }
        }
        value = findViewById(R.id.chartValue)
        stats = findViewById(R.id.chartStats)
        chart = findViewById(R.id.chart)
        chart.series = Session.sampler?.series(f.topic)

        windowButtons = listOf(
            findViewById<Button>(R.id.btnWindow30s) to 30_000L,
            findViewById<Button>(R.id.btnWindow1m) to 60_000L,
            findViewById<Button>(R.id.btnWindow5m) to 300_000L,
        )
        windowMs = savedInstanceState?.getLong(STATE_WINDOW) ?: 30_000L
        windowButtons.forEach { (b, ms) -> b.setOnClickListener { setWindow(ms) } }
        pauseButton = findViewById(R.id.btnPause)
        pauseButton.setOnClickListener { togglePause() }
        setWindow(windowMs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_WINDOW, windowMs)
    }

    override fun onResume() {
        super.onResume()
        if (!::field.isInitialized) return
        if (Session.stlink == null) {
            lastError = "нет подключения — вернитесь и нажмите «Подключить и опознать»"
        } else {
            lastError = null
            Session.startPolling(
                SAMPLE_PERIOD_MS,
                only = setOf(field.topic),
                onReadings = { r -> lastError = r.firstOrNull()?.error },
                onStopped = { msg -> lastError = "опрос остановлен: $msg" },
            )
        }
        handler.post(redraw)
    }

    override fun onPause() {
        handler.removeCallbacks(redraw)
        Session.stopPolling()
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setWindow(ms: Long) {
        windowMs = ms
        windowButtons.forEach { (b, w) -> b.isSelected = w == ms; b.alpha = if (w == ms) 1f else 0.6f }
        refresh()
    }

    private fun togglePause() {
        paused = !paused
        pausedAt = Session.nowMs()
        pauseButton.setText(if (paused) R.string.btn_resume else R.string.btn_pause)
        refresh()
    }

    private fun refresh() {
        val now = if (paused) pausedAt else Session.nowMs()
        chart.nowMs = now
        chart.windowMs = windowMs
        chart.invalidate()

        val s = chart.series
        val last = s?.lastValue()
        val err = lastError
        value.text = when {
            err != null -> err
            last != null -> field.format(last)
            else -> "—"
        }
        value.setTextColor(getColor(if (err != null) R.color.error else android.R.color.black))
        val w = s?.window(now - windowMs)
        stats.text = if (w == null || w.size == 0) {
            "нет данных в окне"
        } else {
            val rate = if (w.size > 1) (w.size - 1) * 1000.0 / (w.times[w.size - 1] - w.times[0]).coerceAtLeast(1) else 0.0
            "мин ${fmt(w.min)}  ср ${fmt(w.mean)}  макс ${fmt(w.max)}\n" +
                "%d точек, %.1f Гц".format(w.size, rate)
        }
    }

    private fun fmt(v: Double?): String = v?.let { field.format(it) } ?: "—"

    companion object {
        const val EXTRA_TOPIC = "topic"
        private const val STATE_WINDOW = "window"
        private const val SAMPLE_PERIOD_MS = 50L
        private const val REDRAW_PERIOD_MS = 100L
    }
}

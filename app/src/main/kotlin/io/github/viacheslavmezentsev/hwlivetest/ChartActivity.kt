package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field

/**
 * График одного или нескольких полей. Выбранные поля опрашиваются с паузой
 * [SAMPLE_PERIOD_MS] между проходами (соседние адреса — одним блоком), экран
 * перерисовывается каждые [REDRAW_PERIOD_MS]. Окно — 30 с, 1 мин или 5 мин; ряды хранят
 * 5 минут, поэтому окно можно переключать без потери данных.
 */
class ChartActivity : Activity() {

    private val topics = ArrayList<String>()
    private lateinit var chart: ChartView
    private lateinit var value: TextView
    private lateinit var stats: TextView
    private lateinit var info: TextView
    private lateinit var titleView: TextView
    private lateinit var pauseButton: Button
    private lateinit var scaleButton: Button
    private lateinit var windowButtons: List<Pair<Button, Long>>

    private val handler = Handler(Looper.getMainLooper())
    private var windowMs = 30_000L
    private var paused = false
    private var pausedAt = 0L
    private var ownScale = true

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
        val saved = savedInstanceState?.getStringArrayList(STATE_TOPICS)
        val initial = saved ?: listOfNotNull(intent.getStringExtra(EXTRA_TOPIC))
        topics += initial.filter { Session.field(it) != null }
        if (topics.isEmpty()) {
            finish()
            return
        }
        setContentView(R.layout.activity_chart)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        titleView = findViewById(R.id.chartTitle)
        info = findViewById(R.id.chartInfo)
        value = findViewById(R.id.chartValue)
        stats = findViewById(R.id.chartStats)
        chart = findViewById(R.id.chart)

        windowButtons = listOf(
            findViewById<Button>(R.id.btnWindow30s) to 30_000L,
            findViewById<Button>(R.id.btnWindow1m) to 60_000L,
            findViewById<Button>(R.id.btnWindow5m) to 300_000L,
        )
        windowMs = savedInstanceState?.getLong(STATE_WINDOW)?.takeIf { it > 0 } ?: 30_000L
        ownScale = savedInstanceState?.getBoolean(STATE_OWN, true) ?: true
        windowButtons.forEach { (b, ms) -> b.setOnClickListener { setWindow(ms) } }
        pauseButton = findViewById(R.id.btnPause)
        pauseButton.setOnClickListener { togglePause() }
        scaleButton = findViewById(R.id.btnScale)
        scaleButton.setOnClickListener {
            ownScale = !ownScale
            updateScaleButton()
            refresh()
        }
        findViewById<Button>(R.id.btnFields).setOnClickListener { chooseFields() }
        updateScaleButton()
        applyTopics()
        setWindow(windowMs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_WINDOW, windowMs)
        outState.putBoolean(STATE_OWN, ownScale)
        outState.putStringArrayList(STATE_TOPICS, topics)
    }

    override fun onResume() {
        super.onResume()
        if (topics.isEmpty()) return
        startPolling()
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

    private fun fields(): List<Field> = topics.mapNotNull { Session.field(it) }

    private fun startPolling() {
        if (Session.stlink == null) {
            lastError = "нет подключения — вернитесь и подключитесь"
            return
        }
        lastError = null
        Session.startPolling(
            SAMPLE_PERIOD_MS,
            only = topics.toSet(),
            onReadings = { r -> lastError = r.firstOrNull { it.error != null }?.let { "${it.spec.shortName}: ${it.error}" } },
            onStopped = { msg -> lastError = "опрос остановлен: $msg" },
        )
    }

    private fun applyTopics() {
        val fs = fields()
        val sampler = Session.sampler
        chart.lines = fs.mapIndexedNotNull { i, f ->
            sampler?.series(f.topic)?.let { ChartView.Line(f.shortName, it, ChartView.PALETTE[i % ChartView.PALETTE.size], f::format) }
        }
        setTitle(if (fs.size == 1) fs[0].shortName else "${fs.size} поля")
        titleView.text = fs.joinToString("\n") { it.topic }
        info.text = fs.joinToString("\n") { f ->
            "${f.shortName}: ${f.address.hex32()} ${f.kind.key}" + (Session.check(f).warning?.let { "  ⚠ $it" } ?: "") +
                if (fs.size == 1) (f.expr?.let { "\n$it" } ?: "") else ""
        }
        scaleButton.isEnabled = fs.size > 1
    }

    private fun chooseFields() {
        val all = Session.fieldsFile?.fields ?: return
        val names = all.map { it.topic }.toTypedArray<CharSequence>()
        val checked = BooleanArray(all.size) { all[it].topic in topics }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_fields)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = all.filterIndexed { i, _ -> checked[i] }.map { it.topic }
                if (chosen.isNotEmpty()) {
                    topics.clear()
                    topics += chosen.take(ChartView.PALETTE.size)
                    applyTopics()
                    startPolling()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setWindow(ms: Long) {
        windowMs = ms
        windowButtons.forEach { (b, w) -> b.alpha = if (w == ms) 1f else 0.55f }
        refresh()
    }

    private fun updateScaleButton() {
        scaleButton.setText(if (ownScale) R.string.scale_own else R.string.scale_shared)
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
        chart.ownScale = ownScale
        chart.invalidate()

        val first = chart.lines.firstOrNull()
        val err = lastError
        value.text = when {
            err != null -> err
            first != null -> first.series.lastValue()?.let(first.format) ?: "—"
            else -> "—"
        }
        value.setTextColor(getColor(if (err != null) R.color.error else android.R.color.black))

        val sb = SpannableStringBuilder()
        var rate = 0.0
        chart.lines.forEachIndexed { i, l ->
            val w = l.series.window(now - windowMs)
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append("● ").append(l.name).append(": ")
            if (w.size == 0) {
                sb.append("нет данных")
            } else {
                sb.append("мин ${fmt(l, w.min)}  ср ${fmt(l, w.mean)}  макс ${fmt(l, w.max)}")
                if (w.size > 1) rate = maxOf(rate, (w.size - 1) * 1000.0 / (w.times[w.size - 1] - w.times[0]).coerceAtLeast(1))
            }
            sb.setSpan(ForegroundColorSpan(l.color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("\n%.1f Гц, проход опроса %d мс".format(rate, Session.lastPollMs))
        stats.text = sb
    }

    private fun fmt(l: ChartView.Line, v: Double?): String = v?.let(l.format) ?: "—"

    companion object {
        const val EXTRA_TOPIC = "topic"
        private const val STATE_WINDOW = "window"
        private const val STATE_OWN = "own_scale"
        private const val STATE_TOPICS = "topics"
        private const val SAMPLE_PERIOD_MS = 20L
        private const val REDRAW_PERIOD_MS = 100L
    }
}

package io.github.viacheslavmezentsev.hwlivetest

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.needsOwnScale

/**
 * График одного или нескольких полей. Выбранные поля опрашиваются 10 раз в секунду
 * (соседние адреса — одним блоком), экран перерисовывается каждые [REDRAW_PERIOD_MS].
 * Окно — 30 с, 1 мин или 5 мин; ряды хранят 5 минут, поэтому окно можно переключать без
 * потери данных. Запись в MCAP идёт в папку, выбранную один раз.
 */
class ChartActivity : ClockActivity() {

    /** Порядок = порядок отметки галочек: последний рисуется поверх остальных. */
    private val topics = ArrayList<String>()
    private lateinit var chart: ChartView
    private lateinit var value: TextView
    private lateinit var stats: TextView
    private lateinit var info: TextView
    private lateinit var titleView: TextView
    private lateinit var pauseButton: Button
    private lateinit var scaleButton: Button
    private lateinit var fieldsButton: Button
    private lateinit var recordButton: Button
    private lateinit var windowButtons: List<Pair<Button, Long>>

    private val handler = Handler(Looper.getMainLooper())
    private var windowMs = 30_000L
    private var paused = false
    private var pausedAt = 0L

    /** null — выбирать автоматически по порядку величин полей. */
    private var ownScale: Boolean? = null

    /** Что сделать после выбора папки записей. */
    private var afterFolder: (() -> Unit)? = null

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
        ownScale = savedInstanceState?.let { if (it.getBoolean(STATE_OWN_SET, false)) it.getBoolean(STATE_OWN, false) else null }
        windowButtons.forEach { (b, ms) -> b.setOnClickListener { setWindow(ms) } }
        pauseButton = findViewById(R.id.btnPause)
        pauseButton.setOnClickListener { togglePause() }
        scaleButton = findViewById(R.id.btnScale)
        scaleButton.setOnClickListener {
            ownScale = !effectiveOwnScale()
            updateScaleButton()
            refresh()
        }
        fieldsButton = findViewById(R.id.btnFields)
        fieldsButton.setOnClickListener { chooseFields() }
        recordButton = findViewById(R.id.btnRecord)
        recordButton.setOnClickListener { toggleRecording() }
        applyTopics()
        setWindow(windowMs)
        updateRecordButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_WINDOW, windowMs)
        outState.putBoolean(STATE_OWN_SET, ownScale != null)
        outState.putBoolean(STATE_OWN, ownScale ?: false)
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
        // Запись переживает поворот экрана, но не уход с графика.
        if (isFinishing && Session.recording != null) stopRecording(showResult = false)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EXPORT, 0, R.string.menu_export_mcap)
        menu.add(0, MENU_FOLDER, 1, R.string.menu_record_folder)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            MENU_EXPORT -> {
                withFolder { exportBuffer() }
                return true
            }
            MENU_FOLDER -> {
                afterFolder = null
                startActivityForResult(Recording.pickFolderIntent(), REQUEST_FOLDER)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER) return
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) {
            afterFolder = null
            return
        }
        try {
            Recording.rememberFolder(this, tree)
            Toast.makeText(this, R.string.record_folder_saved, Toast.LENGTH_SHORT).show()
            afterFolder?.invoke()
        } catch (e: Exception) {
            stats.text = "Папка недоступна: ${e.message}"
        }
        afterFolder = null
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
        updateScaleButton()
    }

    /** Галочки в порядке отметки: снятые убираются, новые встают в конец (поверх). */
    private fun chooseFields() {
        val all = Session.fieldsFile?.fields ?: return
        val names = all.map { it.topic }.toTypedArray<CharSequence>()
        val checked = BooleanArray(all.size) { all[it].topic in topics }
        val order = ArrayList(topics)
        AlertDialog.Builder(this)
            .setTitle(R.string.fields_dialog_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
                order.remove(all[which].topic)
                if (isChecked) order.add(all[which].topic)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (order.isNotEmpty()) {
                    topics.clear()
                    topics += order.takeLast(ChartView.PALETTE.size)
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

    private fun effectiveOwnScale(): Boolean =
        ownScale ?: (chart.lines.size > 1 && needsOwnScale(chart.lines.mapNotNull { it.series.lastValue() }))

    private fun updateScaleButton() {
        scaleButton.setText(if (effectiveOwnScale()) R.string.scale_own else R.string.scale_shared)
    }

    private fun togglePause() {
        paused = !paused
        pausedAt = Session.nowMs()
        pauseButton.setText(if (paused) R.string.btn_resume else R.string.btn_pause)
        refresh()
    }

    // ---------- MCAP ----------

    private fun withFolder(action: () -> Unit) {
        if (Recording.folder(this) != null) {
            action()
        } else {
            afterFolder = action
            Toast.makeText(this, R.string.record_folder_pick, Toast.LENGTH_LONG).show()
            startActivityForResult(Recording.pickFolderIntent(), REQUEST_FOLDER)
        }
    }

    private fun toggleRecording() {
        if (Session.recording != null) {
            stopRecording(showResult = true)
            return
        }
        if (Session.stlink == null) {
            stats.text = "Запись невозможна: нет подключения."
            return
        }
        withFolder {
            val tree = Recording.folder(this) ?: return@withFolder
            val fs = fields()
            Session.worker.execute {
                try {
                    val rec = Recording.create(this, tree, Recording.fileName("hwlivetest"), fs)
                    Session.recording = rec
                    Session.logLine("# запись MCAP: ${rec.name}, полей ${fs.size}")
                } catch (e: Exception) {
                    Session.logLine("# запись MCAP: ${e.message}")
                    runOnUiThread { stats.text = "Не удалось начать запись: ${e.message}" }
                }
                runOnUiThread { updateRecordButton() }
            }
        }
    }

    private fun stopRecording(showResult: Boolean) {
        val rec = Session.recording ?: return
        Session.worker.execute {
            Session.recording = null
            val err = try {
                rec.close()
                null
            } catch (e: Exception) {
                e.message
            }
            Session.logLine(
                "# запись MCAP завершена: ${rec.name}, сообщений ${rec.messages}, ${rec.bytes / 1024} КБ" +
                    (err?.let { ", ошибка: $it" } ?: ""),
            )
            if (showResult) runOnUiThread { showSaved(rec.uri, rec.name, rec.messages, rec.bytes, err) }
            runOnUiThread { if (!isFinishing) updateRecordButton() }
        }
    }

    /** Сохранить последние 5 минут выбранных полей из буфера. */
    private fun exportBuffer() {
        val tree = Recording.folder(this) ?: return
        val fs = fields()
        val sampler = Session.sampler ?: return
        val wall0 = System.currentTimeMillis() - Session.nowMs()
        Session.worker.execute {
            try {
                val rec = Recording.create(this, tree, Recording.fileName("hwlivetest_export"), fs)
                rec.writeSeries(fs, { sampler.series(it) }, Session.nowMs() - 300_000L, wall0)
                rec.close()
                Session.logLine("# экспорт MCAP: ${rec.name}, сообщений ${rec.messages}")
                runOnUiThread { showSaved(rec.uri, rec.name, rec.messages, rec.bytes, null) }
            } catch (e: Exception) {
                runOnUiThread { stats.text = "Экспорт не удался: ${e.message}" }
            }
        }
    }

    private fun showSaved(uri: Uri, name: String, messages: Long, bytes: Long, err: String?) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Сообщений: $messages, ${bytes / 1024} КБ." + (err?.let { "\nОшибка: $it" } ?: ""))
            .setPositiveButton(R.string.share) { _, _ -> Recording.share(this, uri, name) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun updateRecordButton() {
        val on = Session.recording != null
        recordButton.setText(if (on) R.string.record_stop else R.string.record_start)
        recordButton.setTextColor(getColor(if (on) R.color.error else android.R.color.black))
        fieldsButton.isEnabled = !on
    }

    private fun refresh() {
        val now = if (paused) pausedAt else Session.nowMs()
        chart.nowMs = now
        chart.windowMs = windowMs
        chart.ownScale = effectiveOwnScale()
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
        Session.recording?.let { sb.append("\n● запись: ${it.name}, ${it.messages} сообщ.") }
        stats.text = sb
    }

    private fun fmt(l: ChartView.Line, v: Double?): String = v?.let(l.format) ?: "—"

    companion object {
        const val EXTRA_TOPIC = "topic"
        private const val STATE_WINDOW = "window"
        private const val STATE_OWN = "own_scale"
        private const val STATE_OWN_SET = "own_scale_set"
        private const val STATE_TOPICS = "topics"

        /** 10 Гц: этого хватает для просмотра и не грузит адаптер без нужды. */
        private const val SAMPLE_PERIOD_MS = 100L
        private const val REDRAW_PERIOD_MS = 100L
        private const val REQUEST_FOLDER = 20
        private const val MENU_EXPORT = 1
        private const val MENU_FOLDER = 2
    }
}

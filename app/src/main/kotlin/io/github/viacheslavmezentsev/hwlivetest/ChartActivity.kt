package io.github.viacheslavmezentsev.hwlivetest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.needsOwnScale
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme

data class SavedRecording(val uri: Uri, val name: String, val messages: Long, val bytes: Long, val error: String?)

class ChartUi : ViewModel() {
    var topics by mutableStateOf(emptyList<String>())
    var selectedTopic by mutableStateOf<String?>(null)
    var windowMs by mutableStateOf(30_000L)
    var ownScale by mutableStateOf<Boolean?>(null)
    var paused by mutableStateOf(false)
    var pausedAt = 0L
    var now by mutableStateOf(0L)
    var lines by mutableStateOf(emptyList<ChartRenderer.Line>())
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    var recording by mutableStateOf("")
    var recordBusy by mutableStateOf(false)
    var pollMs by mutableStateOf(0L)
    var connecting by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var setupKey = ""
    var colors = emptyMap<String, Int>()
    var afterFolder: String? = null
    var saved by mutableStateOf<SavedRecording?>(null)
}

/** ТЗ прошивки 5.11: Canvas Compose, существующие оси и MCAP. */
class ChartActivity : ComponentActivity() {
    private lateinit var ui: ChartUi
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var generation = 0
    private lateinit var connector: Connector
    private val onSessionChanged: () -> Unit = {
        applyTopics()
        if (!Session.route.value.ready && Session.recording != null && !ui.recordBusy) stopRecording(showResult = true)
        if (resumed) startPolling()
    }
    private val redraw = object : Runnable {
        override fun run() { refresh(); if (resumed) handler.postDelayed(this, 100L) }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = result.data?.data
        val action = ui.afterFolder
        ui.afterFolder = null
        if (result.resultCode == RESULT_OK && tree != null) {
            try { Recording.rememberFolder(this, tree); when (action) { "record" -> toggleRecording(); "export" -> exportBuffer() } }
            catch (e: Exception) { ui.message = "Папка недоступна: "+e.message }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ViewModelProvider(this)[ChartUi::class.java]
        connector = Connector(this) { text, tone -> if (tone == Connector.Tone.ERROR) ui.error = text }
        if (ui.topics.isEmpty()) {
            val available = Session.fieldsFile?.fields.orEmpty()
            ui.setupKey = ChartSetup.key(available)
            val (topics, window, scale) = Prefs.chart(this, ui.setupKey)
            ui.topics = (savedInstanceState?.getStringArrayList("topics")
                ?: intent.getStringArrayListExtra(EXTRA_TOPICS)
                ?: ChartSetup.topics(topics, available, intent.getStringExtra(EXTRA_TOPIC)))
                .filter { Session.field(it) != null }.distinct().take(6)
            ui.selectedTopic = savedInstanceState?.getString("selected") ?: intent.getStringExtra(EXTRA_TOPIC)
            ui.windowMs = savedInstanceState?.getLong("window", window) ?: window
            ui.ownScale = if (savedInstanceState != null) savedInstanceState.getString("scale")?.toBooleanStrictOrNull() else scale
        }
        if (ui.topics.isEmpty()) { finish(); return }
        if (ui.colors.isEmpty()) ui.colors = Prefs.chartColors(this, ui.setupKey, ui.topics)
        applyTopics()
        setContent { HwTheme { ChartScreen(ui, fields(), Session.fieldsFile?.fields.orEmpty(),
            onBack = ::finish,
            onTopics = { topics -> ui.topics = topics; applyTopics(); startPolling() },
            onPause = { ui.paused = !ui.paused; ui.pausedAt = Session.nowMs(); refresh() },
            onRecord = ::toggleRecording,
            onConnect = {
                ui.connecting = true
                connector.connect { info ->
                    ui.connecting = false
                    if (info != null) { ui.paused = false; applyTopics(); if (resumed) startPolling() }
                }
            },
            onExport = { withFolder("export", ::exportBuffer) },
            onClear = {
                if (!ui.recordBusy && Session.recording == null) Session.worker.execute {
                    Session.clearTelemetry("пользователь начал новый интервал")
                    runOnUiThread { ui.paused = false; ui.message = "История очищена. Накапливаются новые отсчёты."; refresh() }
                }
            },
            onShare = { saved -> Recording.share(this, saved.uri, saved.name) },
            onFolder = { ui.afterFolder = null; folderPicker.launch(Recording.pickFolderIntent()) },
        ) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("topics", ArrayList(ui.topics))
        outState.putString("selected", ui.selectedTopic)
        outState.putLong("window", ui.windowMs)
        outState.putString("scale", ui.ownScale?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (ui.topics.isEmpty()) return
        resumed = true
        Session.addListener(onSessionChanged)
        startPolling()
        handler.post(redraw)
    }

    override fun onPause() {
        resumed = false
        generation++
        handler.removeCallbacks(redraw)
        Session.removeListener(onSessionChanged)
        Prefs.saveChart(this, ui.setupKey, ui.topics, ui.windowMs, ui.ownScale)
        Prefs.saveChartColors(this, ui.setupKey, ui.colors)
        Session.stopPolling()
        super.onPause()
    }

    override fun onStop() {
        // ТЗ прошивки 5.21: закрыть файл в фоне; поворот сохраняет текущую запись.
        if (!isChangingConfigurations && (Session.recording != null || ui.recordBusy)) stopRecording(showResult = !isFinishing)
        super.onStop()
    }

    private fun fields(): List<Field> = ui.topics.mapNotNull { Session.field(it) }

    private fun applyTopics() {
        val fs = fields()
        ui.colors = ChartSetup.colors(fs.map { it.topic }, Session.fieldsFile?.fields.orEmpty(), ui.colors, ChartRenderer.PALETTE.size)
        ui.lines = fs.mapNotNull { f -> Session.sampler?.series(f.topic)?.let {
            ChartRenderer.Line(ChartSetup.label(f, fs), it, ChartRenderer.PALETTE[ui.colors.getValue(f.topic)], f::format)
        } }
        if (ui.selectedTopic !in ui.topics) ui.selectedTopic = ui.topics.firstOrNull()
        refresh()
    }

    private fun startPolling() {
        val current = ++generation
        if (!Session.route.value.ready) { ui.error = if (ui.connecting) "Подключение…" else "Нет связи. Ранее полученные данные сохранены."; return }
        ui.error = null
        Session.startPolling(100L, ui.topics.toSet(),
            onReadings = { readings ->
                val error = readings.firstOrNull { it.error != null }?.let { it.spec.shortName+": "+it.error }
                runOnUiThread { if (resumed && generation == current) ui.error = error }
            },
            onStopped = { error -> runOnUiThread {
                if (resumed && generation == current) {
                    ui.error = error
                    if (Session.recording != null && !ui.recordBusy) stopRecording(showResult = true)
                }
            } },
        )
    }

    private fun refresh() {
        if (ui.connected && !Session.route.value.ready) { ui.pausedAt = ui.now; ui.paused = true }
        ui.now = if (ui.paused) ui.pausedAt else Session.nowMs()
        ui.pollMs = Session.lastPollMs
        ui.connected = Session.route.value.ready
        ui.recording = Session.recording?.let { it.name+", "+it.messages+" сообщ." } ?: ""
    }

    // ---------- MCAP ----------

    private fun withFolder(kind: String, action: () -> Unit) {
        if (Recording.folder(this) != null) {
            action()
        } else {
            ui.afterFolder = kind
            Toast.makeText(this, R.string.record_folder_pick, Toast.LENGTH_LONG).show()
            folderPicker.launch(Recording.pickFolderIntent())
        }
    }

    private fun toggleRecording() {
        if (ui.recordBusy) return
        if (Session.recording != null) {
            stopRecording(showResult = true)
            return
        }
        if (Session.stlink == null) {
            ui.message = "Запись невозможна: нет подключения."
            return
        }
        withFolder("record") {
            val tree = Recording.folder(this) ?: return@withFolder
            ui.recordBusy = true
            val fs = fields()
            Session.worker.execute {
                try {
                    check(Session.route.value.ready) { "Устройство отключено" }
                    val rec = Recording.create(this, tree, Recording.fileName("hwlivetest"), fs)
                    Session.recording = rec
                    Session.logLine("# запись MCAP: ${rec.name}, полей ${fs.size}")
                } catch (e: Exception) {
                    Session.logLine("# запись MCAP: ${e.message}")
                    runOnUiThread { ui.message = "Не удалось начать запись: ${e.message}" }
                }
                runOnUiThread { ui.recordBusy = false; refresh() }
            }
        }
    }

    private fun stopRecording(showResult: Boolean) {
        ui.recordBusy = true
        Session.worker.execute {
            val rec = Session.recording
            if (rec == null) { runOnUiThread { ui.recordBusy = false }; return@execute }
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
            runOnUiThread { ui.recordBusy = false; if (!isFinishing) refresh() }
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
                runOnUiThread { ui.message = "Экспорт не удался: ${e.message}" }
            }
        }
    }

    private fun showSaved(uri: Uri, name: String, messages: Long, bytes: Long, err: String?) {
        if (isFinishing) return
        ui.saved = SavedRecording(uri, name, messages, bytes, err)
    }


    companion object {
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_TOPICS = "topics"
    }
}

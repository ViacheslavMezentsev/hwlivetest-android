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
import androidx.compose.material3.*
import android.provider.DocumentsContract
import java.io.File
import io.github.viacheslavmezentsev.hwlivetest.telemetry.RttChartFeed
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
    var recordRtt by mutableStateOf(false)
    var recordHz by mutableStateOf(10)
    var rttRunner: RttTrial? = null
    var resumePolling = false
    var recordBusy by mutableStateOf(false)
    var pollMs by mutableStateOf(0L)
    var pollingPeriodMs by mutableStateOf(100L)
    var pollingHz by mutableStateOf(PollingRate.DEFAULT_HZ)
    var connecting by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var setupKey = ""
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
        if (!Session.route.value.ready && Session.recording != null && !ui.recordBusy) stopRecording(showResult = true, reason = "потеря подключения")
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
        applyTopics()
        setContent { HwTheme {
            ChartScreen(ui, fields(), Session.fieldsFile?.fields.orEmpty(),
            onBack = ::finish,
            onPollingHz = { hz ->
                if (ui.recordRtt) {
                    if (ui.rttRunner == null) { Prefs.setRttHz(this, hz); ui.recordHz = hz }
                } else {
                    Prefs.setPollingHz(this, hz)
                    if (resumed) startPolling()
                }
                Session.userSaid("установил частоту ${if (ui.recordRtt) "RTT" else "SWD"} на графике: ${ui.recordHz} Гц")
            },
            onTopics = { topics -> Session.userSaid("применил каналы графика: " + topics.joinToString()); ui.topics = topics; applyTopics(); startPolling() },
            onPause = { Session.userSaid(if (ui.paused) "возобновил график" else "приостановил график (опрос и запись продолжаются)"); ui.paused = !ui.paused; ui.pausedAt = Session.nowMs(); refresh() },
            onRecord = { Session.userSaid(if (Session.recording == null && ui.rttRunner == null) "нажал «Запись»" else "нажал «Остановить» запись"); toggleRecording() },
            onConnect = {
                Session.userSaid("нажал «Подключить» на странице графика")
                ui.connecting = true
                connector.connect { info ->
                    ui.connecting = false
                    if (info != null) { ui.paused = false; applyTopics(); if (resumed) startPolling() }
                }
            },
            onExport = { Session.userSaid("нажал «Сохранить буфер»"); withFolder("export", ::exportBuffer) },
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
        Session.stopPolling()
        super.onPause()
    }

    override fun onStop() {
        // ТЗ прошивки 5.21: закрыть файл в фоне; поворот сохраняет текущую запись.
        if (!isChangingConfigurations && ui.rttRunner != null) { ui.recordBusy = true; ui.rttRunner?.cancel() }
        else if (!isChangingConfigurations && (Session.recording != null || ui.recordBusy)) stopRecording(showResult = !isFinishing, reason = "уход с экрана графика")
        super.onStop()
    }

    private fun fields(): List<Field> = ui.topics.mapNotNull { Session.field(it) }

    private fun applyTopics() {
        val fs = fields()
        val colors = ChartSetup.colors(fs.map { it.topic })
        ui.lines = fs.mapNotNull { f -> Session.sampler?.series(f.topic)?.let {
            ChartRenderer.Line(ChartSetup.label(f, fs), it, ChartSetup.color(colors.getValue(f.topic), resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES), f::format)
        } }
        if (ui.selectedTopic !in ui.topics) ui.selectedTopic = ui.topics.firstOrNull()
        refresh()
    }

    private fun startPolling() {
        if (Session.rttRecording != null) return
        ui.recordRtt = Prefs.effectiveRtt(this)
        ui.recordHz = if (ui.recordRtt) Prefs.rttHz(this) else Prefs.pollingHz(this)
        ui.pollingHz = Prefs.pollingHz(this)
        ui.pollingPeriodMs = PollingRate.periodMs(ui.pollingHz)
        val current = ++generation
        if (!Session.route.value.ready) { ui.error = if (ui.connecting) "Подключение…" else "Нет связи. Ранее полученные данные сохранены."; return }
        ui.error = null
        Session.startPolling(ui.pollingPeriodMs, ui.topics.toSet(),
            onReadings = { readings ->
                val error = readings.firstOrNull { it.error != null }?.let { it.spec.shortName+": "+it.error }
                runOnUiThread { if (resumed && generation == current) ui.error = error }
            },
            onStopped = { error -> runOnUiThread {
                if (resumed && generation == current) {
                    ui.error = error
                    if (Session.recording != null && !ui.recordBusy) stopRecording(showResult = true, reason = "остановка опроса: $error")
                }
            } },
        )
    }

    private fun refresh() {
        if (resumed && ui.resumePolling && Session.rttRecording == null) { ui.resumePolling = false; startPolling() }
        if (ui.connected && !Session.route.value.ready) { ui.pausedAt = ui.now; ui.paused = true }
        ui.now = if (ui.paused) ui.pausedAt else Session.nowMs()
        ui.pollMs = Session.lastPollMs
        ui.connected = Session.route.value.ready
        ui.recording = Session.recording?.let { it.name+", "+it.messages+" сообщ." } ?: ui.rttRunner?.progressText.orEmpty()
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
        if (ui.rttRunner != null) { ui.recordBusy = true; ui.rttRunner?.cancel(); return }
        if (Session.recording != null) {
            stopRecording(showResult = true)
            return
        }
        if (Session.stlink == null) {
            ui.message = "Запись невозможна: нет подключения."
            return
        }
        if (Prefs.effectiveRtt(this)) {
            when {
                !Session.writeAccess.value -> ui.message = "Включите «Доступ» в шапке графика."
                RttDoctorActivity.selectedElf == null -> ui.message = "Выберите ELF в настройках → Настройка RTT."
                fields().any { !RttTrial.eligible(it) } -> ui.message = "RTT поддерживает выровненные поля SRAM без битовых масок. Измените выбор каналов."
                else -> withFolder("record", ::startRtt)
            }
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
                    Session.programSaid("запись MCAP начата: ${rec.name}, каналов ${fs.size}")
                } catch (e: Exception) {
                    Session.programSaid("запись MCAP не начата: ${e.message}")
                    runOnUiThread { ui.message = "Не удалось начать запись: ${e.message}" }
                }
                runOnUiThread { ui.recordBusy = false; refresh() }
            }
        }
    }

    // ТЗ RTT 2.13: graph owns the recording; worker always restores before conversion/export.
    private fun startRtt() {
        if (!Session.writeAccess.value || !Prefs.effectiveRtt(this)) { ui.message = "Для RTT нужны совместимое устройство и включённый доступ."; return }
        val elf = RttDoctorActivity.selectedElf ?: return
        val tree = Recording.folder(this) ?: return
        if (ui.rttRunner != null || Session.recording != null || Session.rttRecording != null) return
        val fs = fields()
        val rate = Prefs.rttHz(this)
        val runner = RttTrial()
        ui.rttRunner = runner
        Session.rttRecording = runner
        ui.recordBusy = false
        ui.message = ""
        Session.userSaid("запустил запись RTT с графика при включённом доступе: $rate Гц, ${fs.size} каналов, до ручной остановки")
        Session.stopPolling()
        val directory = File(getExternalFilesDir(null) ?: filesDir, "rtt")
        val appContext = applicationContext
        Session.worker.execute {
            var saved: SavedRecording? = null
            var message: String
            try {
                Session.sampler?.clear()
                val feed = RttChartFeed(fs, { Session.sampler?.series(it) }, Session.nowMs())
                runner.run(elf, fs, directory, rate, feed::accept)
                feed.flush()
                message = RttTrial.lastSummary
                val report = RttTrial.lastReport
                val mcap = report?.let { File(it.parentFile, it.nameWithoutExtension + ".mcap") }
                if (mcap?.isFile == true) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val name = Recording.fileName("hwlivetest_rtt")
                    val uri = DocumentsContract.createDocument(appContext.contentResolver, parent, "application/octet-stream", name)
                        ?: error("Не удалось создать MCAP в выбранной папке")
                    try {
                        appContext.contentResolver.openOutputStream(uri, "w")!!.use { out -> mcap.inputStream().use { it.copyTo(out) } }
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                        throw e
                    }
                    val json = org.json.JSONObject(report.readText())
                    saved = SavedRecording(uri, name, json.optLong("mcap_messages"), mcap.length(),
                        if (json.optBoolean("restored") && json.optBoolean("capture_complete")) null else message)
                }
            } catch (e: Exception) {
                message = "${RttTrial.lastSummary} Ошибка сохранения: ${e.message}. Исходные файлы доступны в настройках RTT."
            } finally {
                Session.rttRecording = null
            }
            handler.post {
                ui.rttRunner = null
                ui.recordBusy = false
                ui.message = message
                ui.saved = saved
                ui.resumePolling = true
            }
        }
        refresh()
    }

    private fun stopRecording(showResult: Boolean, reason: String = "по запросу пользователя") {
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
            Session.programSaid(
                "запись MCAP завершена ($reason): ${rec.name}, сообщений ${rec.messages}, ${rec.bytes / 1024} КБ" +
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
                Session.programSaid("буфер сохранён в MCAP: ${rec.name}, сообщений ${rec.messages}")
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

package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.viacheslavmezentsev.hwlivetest.stlink.Address
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import java.io.File

class ExpertUi : androidx.lifecycle.ViewModel() {
    var status by mutableStateOf("")
    var tone by mutableStateOf(Connector.Tone.NORMAL)
    var adapter by mutableStateOf("")
    var target by mutableStateOf("")
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var fields by mutableStateOf(emptyList<Field>())
    var fieldsInfo by mutableStateOf("")
    var readings by mutableStateOf(emptyMap<String, FieldSampler.Reading>())
    var readResult by mutableStateOf("")
}

/** ТЗ прошивки 5.9.7–5.9.8: эксперт на Compose; обмен остаётся в Session.worker. */
class MainActivity : ComponentActivity(), WorkspaceHost {
    override val workspacePage = WorkspacePage.SIGNALS
    override fun navigateWorkspace(page: WorkspacePage, direction: Int) {
        if (!ui.busy && page != workspacePage) WorkspaceNavigation.open(this, page, direction)
    }
    private val ui by lazy { androidx.lifecycle.ViewModelProvider(this)[ExpertUi::class.java] }
    private lateinit var connector: Connector
    private var resumed = false
    private var pollGeneration = 0
    private var shownFile: AddressesFile? = null
    private val onSessionChanged: () -> Unit = {
        sync()
        if (resumed && !ui.busy) startFieldPolling()
    }
    private val pickFields = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFields(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connector = Connector(this) { text, tone ->
            ui.status = text
            ui.tone = tone
            // Отказ USB-разрешения тоже завершает ожидание подключения.
            if (tone == Connector.Tone.ERROR) ui.busy = false
        }
        if (Session.fieldsFile == null) loadSavedFields(this)
        if (Session.log.text().isEmpty()) logHeader()
        sync()
        if (Session.stlink == null && Session.device == null) connector.scan()
        setContent {
            HwTheme {
                ExpertScreen(ui,
                    onBack = { if (!ui.busy) WorkspaceNavigation.home(this) },
                    onConnect = ::connect,
                    onRead = ::readWord,
                    onOpen = { pickFields.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onChart = { f ->
                        Session.userSaid("открыть график: "+f.topic)
                        startActivity(Intent(this, ChartActivity::class.java).putExtra(ChartActivity.EXTRA_TOPIC, f.topic))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Session.addListener(onSessionChanged)
        sync()
        if (!ui.busy) startFieldPolling()
    }

    override fun onPause() {
        resumed = false
        stopFieldPolling()
        Session.removeListener(onSessionChanged)
        super.onPause()
    }

    private fun sync() {
        ui.adapter = Session.adapterText.ifEmpty { Session.device?.title ?: "" }
        ui.target = Session.targetText
        ui.connected = Session.route.value.ready
        if (!ui.busy) {
            ui.status = if (ui.connected) "Подключено: "+Session.target?.shortName
                else if (Session.device != null) "Найден ST-Link. Подключите и опознайте MCU."
                else "Адаптер отключён. Подключите ST-Link через OTG."
            ui.tone = if (ui.connected) Connector.Tone.OK else Connector.Tone.NORMAL
        }
        val file = Session.fieldsFile
        if (file !== shownFile) {
            shownFile = file
            ui.readings = emptyMap()
        }
        ui.fields = file?.fields ?: emptyList()
        ui.fieldsInfo = if (file == null) "Откройте addresses.json или подключите таблицу из ELF на экране «Прошивка»."
        else Session.fieldsName + ": " + file.fields.size + " сигналов" +
            if (file.warnings.isEmpty()) "" else ", предупреждений: " + file.warnings.size + " (см. журнал)"
    }

    private fun connect() {
        stopFieldPolling()
        ui.busy = true
        Session.userSaid("подключить и опознать ST-Link")
        connector.connect { info ->
            ui.busy = false
            sync()
            Session.programSaid(if (info == null) ui.status else "подключено: " + info.shortName)
            if (resumed && info != null) startFieldPolling()
        }
    }

    private fun readWord(text: String) {
        val addr = Address.parse(text)
        if (addr == null || addr and 3L != 0L) {
            ui.readResult = "Введите шестнадцатеричный адрес, выровненный на 4, например E000ED00."
            return
        }
        ui.busy = true
        Session.userSaid("прочитать слово " + addr.hex32())
        Session.worker.execute {
            val result = try {
                val st = Session.stlink ?: error("Сначала нажмите «Подключить и опознать».")
                val v = st.read32(addr)
                addr.hex32() + " = " + v.hex32() + "  (" + (v.toLong() and 0xFFFFFFFFL) + ")"
            } catch (e: Exception) { ErrorMessages.describe(e) }
            Session.programSaid(result)
            runOnUiThread { ui.readResult = result; ui.busy = false }
        }
    }

    private fun loadFields(uri: Uri) {
        stopFieldPolling()
        ui.busy = true
        Session.userSaid("открыть таблицу сигналов")
        Session.worker.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("файл не открылся")
                val file = AddressesFile.parse(text)
                val name = displayName(this, uri)
                File(filesDir, SAVED_FIELDS).writeText(text, Charsets.UTF_8)
                File(filesDir, SAVED_FIELDS_NAME).writeText(name, Charsets.UTF_8)
                Session.setFields(file, name)
                Session.programSaid("таблица подключена: " + name + ", " + file.fields.size + " сигналов; топики из файла сохранены")
                file.warnings.forEach { Session.logLine("# поля: " + it) }
                runOnUiThread { sync() }
            } catch (e: Exception) {
                val message = "Не удалось прочитать таблицу: " + e.message
                Session.programSaid(message)
                runOnUiThread { ui.fieldsInfo = message }
            } finally {
                runOnUiThread { ui.busy = false; if (resumed) startFieldPolling() }
            }
        }
    }

    private fun stopFieldPolling() {
        pollGeneration++
        Session.stopPolling()
    }

    private fun startFieldPolling() {
        stopFieldPolling()
        if (!resumed || Session.stlink == null || Session.sampler == null) return
        val generation = pollGeneration
        Session.startPolling(500L, only = null,
            onReadings = { readings -> runOnUiThread {
                if (resumed && generation == pollGeneration) ui.readings = readings.associateBy { it.spec.topic }
            } },
            onStopped = { message -> runOnUiThread {
                if (resumed && generation == pollGeneration) ui.fieldsInfo = "Опрос остановлен: " + message
            } },
        )
    }

    companion object {
        const val SAVED_FIELDS = "addresses.json"
        const val SAVED_FIELDS_NAME = "addresses.name"
        fun logHeader() {
            Session.logLine(
                "# HW Live Test ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }

        fun loadSavedFields(c: Context) {
            val f = File(c.filesDir, SAVED_FIELDS)
            if (!f.exists()) return
            try {
                val name = File(c.filesDir, SAVED_FIELDS_NAME).takeIf { it.exists() }?.readText() ?: SAVED_FIELDS
                Session.setFields(AddressesFile.parse(f.readText(Charsets.UTF_8)), name)
            } catch (e: Exception) {
                Session.logLine("# поля: сохранённый файл не читается: ${e.message}")
            }
        }

        fun displayName(c: Context, uri: Uri): String {
            try {
                c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0)?.let { return it }
                }
            } catch (_: Exception) {
            }
            return uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
        }
    }
}

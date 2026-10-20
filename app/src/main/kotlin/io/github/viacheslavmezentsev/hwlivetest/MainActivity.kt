package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.stlink.Address
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import java.io.File

/** Режим эксперта: адаптер, MCU, чтение слова, поля из addresses.json, журнал. */
class MainActivity : Activity() {

    private lateinit var connector: Connector
    private lateinit var logPresenter: LogPresenter

    private lateinit var status: TextView
    private lateinit var adapterInfo: TextView
    private lateinit var targetInfo: TextView
    private lateinit var address: EditText
    private lateinit var readResult: TextView
    private lateinit var fieldsInfo: TextView
    private lateinit var fieldsList: LinearLayout
    private lateinit var buttons: List<Button>

    /** Строки списка полей по topic. */
    private val fieldRows = LinkedHashMap<String, FieldRow>()

    private val onSessionChanged: () -> Unit = { showConnection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        connector = Connector(this) { text, tone -> showStatus(text, tone) }

        status = findViewById(R.id.status)
        adapterInfo = findViewById(R.id.adapterInfo)
        targetInfo = findViewById(R.id.targetInfo)
        address = findViewById(R.id.address)
        readResult = findViewById(R.id.readResult)
        fieldsInfo = findViewById(R.id.fieldsInfo)
        fieldsList = findViewById(R.id.fieldsList)
        logPresenter = LogPresenter(findViewById(R.id.log))
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnRead = findViewById<Button>(R.id.btnRead)
        buttons = listOf(btnScan, btnConnect, btnRead)

        btnScan.setOnClickListener { connector.scan()?.let { showConnection() } }
        btnConnect.setOnClickListener { connect() }
        btnRead.setOnClickListener { readWord() }
        findViewById<Button>(R.id.btnOpenFields).setOnClickListener { openFieldsFile() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Session.log.clear()
            logHeader()
        }
        findViewById<CheckBox>(R.id.chkLogPolling).apply {
            isChecked = Session.logPolling
            setOnCheckedChangeListener { _, checked ->
                Session.logPolling = checked
                Prefs.setLogPolling(this@MainActivity, checked)
            }
        }

        if (Session.fieldsFile == null) loadSavedFields(this)
        buildFieldRows()
        if (Session.log.text().isEmpty()) logHeader()
        showConnection()
        if (Session.stlink == null && Session.device == null) connector.scan()
    }

    override fun onResume() {
        super.onResume()
        Session.addListener(onSessionChanged)
        logPresenter.start()
        startFieldPolling()
    }

    override fun onPause() {
        Session.stopPolling()
        Session.removeListener(onSessionChanged)
        logPresenter.stop()
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SIMPLE, 0, R.string.menu_simple)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SIMPLE, android.R.id.home -> {
                Prefs.setExpert(this, false)
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FIELDS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadFields(uri)
    }

    private fun showConnection() {
        adapterInfo.text = Session.adapterText.ifEmpty { Session.device?.title ?: "" }
        targetInfo.text = Session.targetText
        if (Session.stlink != null) {
            showStatus("Подключено: ${Session.target?.shortName ?: Session.device?.model?.title ?: ""}.", Connector.Tone.OK)
        } else if (Session.device == null && adapterInfo.text.isEmpty()) {
            showStatus(getString(R.string.status_idle), Connector.Tone.NORMAL)
        }
        buildFieldRows()
    }

    private fun connect() {
        setBusy(true)
        connector.connect { info ->
            setBusy(false)
            showConnection()
            if (info != null) startFieldPolling()
        }
    }

    private fun readWord() {
        val addr = Address.parse(address.text.toString())
        if (addr == null) {
            readResult.text = "Введите шестнадцатеричный адрес, например E000ED00."
            return
        }
        if (addr and 3L != 0L) {
            readResult.text = "Адрес должен быть выровнен на 4 (оканчиваться на 0, 4, 8 или C)."
            return
        }
        setBusy(true)
        Session.worker.execute {
            try {
                val st = Session.stlink ?: throw IllegalStateException("Сначала нажмите «Подключить и опознать».")
                val v = st.read32(addr)
                val text = "${addr.hex32()} = ${v.hex32()}  (${v.toLong() and 0xFFFFFFFFL})"
                Session.logLine("# $text")
                runOnUiThread { readResult.text = text }
            } catch (e: Exception) {
                Session.logLine("# ошибка чтения: ${e.message}")
                runOnUiThread { readResult.text = ErrorMessages.describe(e) }
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    // ---------- Поля из addresses.json ----------

    private fun openFieldsFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_FIELDS)
    }

    private fun loadFields(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("файл не открылся")
            val file = AddressesFile.parse(text)
            val name = displayName(this, uri)
            File(filesDir, SAVED_FIELDS).writeText(text, Charsets.UTF_8)
            File(filesDir, SAVED_FIELDS_NAME).writeText(name, Charsets.UTF_8)
            Session.setFields(file, name)
            buildFieldRows()
            startFieldPolling()
            Session.logLine("# поля: $name, ${file.fields.size} шт.")
            file.warnings.forEach { Session.logLine("# поля: $it") }
        } catch (e: Exception) {
            fieldsInfo.text = "Не удалось прочитать файл: ${e.message}"
            Session.logLine("# поля: ошибка ${e.message}")
        }
    }

    private fun buildFieldRows() {
        fieldsList.removeAllViews()
        fieldRows.clear()
        val file = Session.fieldsFile
        if (file == null) {
            fieldsInfo.text = "Файл не загружен. Нажмите «Открыть addresses.json»."
            return
        }
        val warn = if (file.warnings.isEmpty()) "" else ", предупреждений: ${file.warnings.size} (см. журнал)"
        fieldsInfo.text = "${Session.fieldsName}: ${file.fields.size} полей$warn. Нажмите на поле, чтобы открыть график."
        for (f in file.fields) {
            val row = FieldRow(this, f, Session.check(f))
            row.view.setOnClickListener {
                startActivity(Intent(this, ChartActivity::class.java).putExtra(ChartActivity.EXTRA_TOPIC, f.topic))
            }
            fieldsList.addView(row.view)
            fieldRows[f.topic] = row
        }
    }

    private fun startFieldPolling() {
        if (Session.stlink == null || Session.sampler == null) return
        Session.startPolling(
            LIST_PERIOD_MS,
            only = null,
            onReadings = { r -> runOnUiThread { showReadings(r) } },
            onStopped = { msg -> runOnUiThread { fieldsInfo.text = "Опрос остановлен: $msg" } },
        )
    }

    private fun showReadings(readings: List<FieldSampler.Reading>) {
        for (r in readings) fieldRows[r.spec.topic]?.show(r)
    }

    // ---------- Оформление ----------

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HW Live Test log", Session.log.text()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showStatus(text: String, tone: Connector.Tone) {
        status.text = text
        status.setTextColor(
            getColor(
                when (tone) {
                    Connector.Tone.ERROR -> R.color.error
                    Connector.Tone.OK -> R.color.ok
                    Connector.Tone.NORMAL -> android.R.color.black
                },
            ),
        )
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
    }

    companion object {
        private const val REQUEST_FIELDS = 1
        const val SAVED_FIELDS = "addresses.json"
        const val SAVED_FIELDS_NAME = "addresses.name"
        private const val LIST_PERIOD_MS = 500L
        private const val MENU_SIMPLE = 1

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

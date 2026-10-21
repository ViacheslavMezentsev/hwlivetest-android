package io.github.viacheslavmezentsev.hwlivetest


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.diag.AdapterFacts
import io.github.viacheslavmezentsev.hwlivetest.diag.DiagReport
import io.github.viacheslavmezentsev.hwlivetest.diag.Diagnostics
import io.github.viacheslavmezentsev.hwlivetest.diag.Level
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashTools
import io.github.viacheslavmezentsev.hwlivetest.firmware.IntelHex
import io.github.viacheslavmezentsev.hwlivetest.firmware.Segment
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Простой режим: одна кнопка «ПУСК». Подключается к ST-Link, опознаёт MCU и запускает
 * эвристики [Diagnostics]; результат — список разделов. Ниже — инструменты, которые
 * только читают Flash.
 */
class SimpleActivity : ClockActivity() {

    private lateinit var connector: Connector
    private lateinit var status: TextView
    private lateinit var start: StartButton
    private lateinit var step: TextView
    private lateinit var summary: TextView
    private lateinit var sections: LinearLayout
    private lateinit var toolResult: TextView
    private lateinit var tools: List<Button>

    @Volatile
    private var running = false

    @Volatile
    private var cancel = false

    private val onSessionChanged: () -> Unit = { if (!running) showIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)
        connector = Connector(this) { text, tone -> showStatus(text, tone) }
        status = findViewById(R.id.simpleStatus)
        start = findViewById(R.id.startButton)
        step = findViewById(R.id.stepText)
        summary = findViewById(R.id.summary)
        sections = findViewById(R.id.sections)
        toolResult = findViewById(R.id.toolResult)
        val backup = findViewById<Button>(R.id.toolBackup)
        val check = findViewById<Button>(R.id.toolCheck)
        val fields = findViewById<Button>(R.id.toolFields)
        tools = listOf(backup, check)

        start.setOnClickListener { onStartPressed() }
        backup.setOnClickListener { askBackupFile() }
        check.setOnClickListener { askHexFile() }
        fields.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        if (Session.log.text().isEmpty()) MainActivity.logHeader()
        if (Session.fieldsFile == null) MainActivity.loadSavedFields(this)
        connector.fromIntent(intent)?.let {
            Session.logLine("# подключён ${it.title}")
            connector.select(it)
        }
        Session.lastReport?.let { showReport(it) }
        showIdle()
        if (savedInstanceState == null && Prefs.expert(this)) startActivity(Intent(this, MainActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connector.fromIntent(intent)?.let {
            Session.logLine("# подключён ${it.title}")
            connector.select(it)
            showIdle()
        }
    }

    override fun onResume() {
        super.onResume()
        Session.addListener(onSessionChanged)
        showIdle()
    }

    override fun onPause() {
        Session.removeListener(onSessionChanged)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EXPERT, 0, R.string.menu_expert)
        menu.add(0, MENU_COPY, 1, R.string.menu_copy_report)
        menu.add(0, MENU_LOGIC, 2, R.string.menu_logic)
        menu.add(0, MENU_ABOUT, 3, R.string.menu_about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_EXPERT -> {
                Prefs.setExpert(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                return true
            }
            MENU_LOGIC -> {
                startActivity(Intent(this, LogicActivity::class.java))
                return true
            }
            MENU_ABOUT -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
            MENU_COPY -> {
                val text = (Session.lastReport?.text() ?: "") + "\n\n" + Session.log.text()
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("HW Live Test report", text))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_BACKUP -> backup(uri)
            REQUEST_CHECK -> checkHex(uri)
        }
    }

    // ---------- ПУСК ----------

    private fun showIdle() {
        if (running) return
        start.progress = -1f
        start.label = getString(if (Session.lastReport == null) R.string.start else R.string.again)
        start.sublabel = ""
        start.fillColor = Session.lastReport?.let { SectionRow.colorOf(this, it.level.takeIf { l -> l != Level.INFO } ?: Level.OK) }
            ?: getColor(R.color.brand)
        val d = Session.device
        when {
            Session.stlink != null -> showStatus("Подключено: ${Session.target?.shortName ?: d?.model?.title}.", Connector.Tone.OK)
            d != null -> showStatus("Найден ${d.model.title}. Нажмите ПУСК.", Connector.Tone.NORMAL)
            Session.lastReport == null -> showStatus(getString(R.string.simple_hint), Connector.Tone.NORMAL)
        }
        tools.forEach { it.isEnabled = Session.stlink != null }
    }

    private fun onStartPressed() {
        if (running) {
            cancel = true
            step.text = "Останавливаю…"
            return
        }
        running = true
        cancel = false
        Session.stopPolling()
        start.label = getString(R.string.stop)
        start.fillColor = getColor(R.color.brand)
        start.progress = 0f
        start.sublabel = "0 %"
        tools.forEach { it.isEnabled = false }
        if (Session.stlink != null && Session.target != null) {
            runDiagnostics()
        } else {
            step.text = "Подключение к ST-Link и опознание MCU…"
            connector.connect { info ->
                if (info == null || cancel) finishRun(null) else runDiagnostics()
            }
        }
    }

    private fun runDiagnostics() {
        val c = Session.connection
        val d = Session.device
        val adapter = if (c != null && d != null) AdapterFacts(d.model.title, c.version.label, c.targetVoltage) else null
        Session.worker.execute {
            val report = try {
                val st = Session.stlink ?: throw IllegalStateException("нет подключения")
                val info = Session.target ?: throw IllegalStateException("MCU не опознан")
                Diagnostics(st, info, adapter, progress = { done, total, title ->
                    if (cancel) throw CancellationException()
                    val p = 0.1f + 0.9f * done / total
                    runOnUiThread {
                        start.progress = p
                        start.sublabel = "${(p * 100).toInt()} %"
                        step.text = title
                    }
                }).run()
            } catch (e: CancellationException) {
                Session.logLine("# проверка остановлена")
                null
            } catch (e: Exception) {
                Session.logLine("# проверка: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { showStatus(ErrorMessages.describe(e), Connector.Tone.ERROR) }
                null
            }
            report?.let {
                Session.lastReport = it
                Session.logLine(it.text())
            }
            runOnUiThread { finishRun(report) }
        }
    }

    private fun finishRun(report: DiagReport?) {
        running = false
        step.text = if (report == null) "" else "Нажмите на раздел, чтобы увидеть подробности."
        report?.let { showReport(it) }
        showIdle()
        if (report != null) start.sublabel = when (report.level) {
            Level.OK, Level.INFO, Level.SKIP -> "всё в порядке"
            Level.WARN -> "есть замечания"
            Level.BAD -> "есть проблемы"
        }
    }

    private fun showReport(r: DiagReport) {
        summary.text = r.summary
        sections.removeAllViews()
        r.sections.forEach { sections.addView(SectionRow(this, it).view) }
    }

    // ---------- Инструменты ----------

    private fun askBackupFile() {
        val part = Session.target?.shortName?.replace(Regex("[^A-Za-z0-9]+"), "_")?.trim('_') ?: "mcu"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "backup_${part}_$stamp.hex")
        }
        startActivityForResult(intent, REQUEST_BACKUP)
    }

    private fun askHexFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CHECK)
    }

    private fun backup(uri: Uri) {
        val kb = Session.target?.flashSizeKb
        if (kb == null) {
            toolResult.text = "Размер Flash неизвестен — резервную копию сделать нельзя."
            return
        }
        runTool("Резервная копия") { st ->
            val total = kb * 1024
            val data = FlashTools.read(FLASH_BASE, total, { a, n -> st.readBlock(a, n) }) { done -> toolProgress(done, total) }
            val used = FlashTools.trimErased(data)
            if (used.isEmpty()) return@runTool "Flash пустая (все байты 0xFF) — сохранять нечего."
            val hex = IntelHex.write(Segment(FLASH_BASE, used))
            contentResolver.openOutputStream(uri)?.use { it.write(hex.toByteArray(Charsets.US_ASCII)) }
                ?: throw IllegalStateException("файл не открылся для записи")
            "Сохранено ${MainActivity.displayName(this, uri)}: ${used.size} байт с ${FLASH_BASE.hex32()} " +
                "(Flash $kb КБ, хвост 0xFF отброшен).\nSHA-256 образа: ${IntelHex.sha256(used)}"
        }
    }

    private fun checkHex(uri: Uri) {
        val name = MainActivity.displayName(this, uri)
        val segments = try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.US_ASCII) }
                ?: throw IllegalStateException("файл не открылся")
            IntelHex.parse(text)
        } catch (e: Exception) {
            toolResult.text = "$name: не Intel HEX (${e.message})"
            return
        }
        runTool("Сверка") { st ->
            val r = FlashTools.check(segments, { a, n -> st.readBlock(a, n) }) { done, total -> toolProgress(done, total) }
            if (r.ok) {
                "$name: совпадает, ${r.bytes} байт в ${segments.size} участках."
            } else {
                val m = r.first.first()
                "$name: расхождений ${r.mismatches} из ${r.bytes} байт. Первое: ${m.address.hex32()} — в файле " +
                    "%02X, в MCU %02X.".format(m.expected.toInt() and 0xFF, m.actual.toInt() and 0xFF)
            }
        }
    }

    /** Выполнить инструмент в потоке worker с прогрессом на кнопке. */
    private fun runTool(title: String, body: (io.github.viacheslavmezentsev.hwlivetest.stlink.StLink) -> String) {
        val st = Session.stlink
        if (st == null) {
            toolResult.text = "Сначала нажмите ПУСК: нужно подключение к MCU."
            return
        }
        running = true
        cancel = false
        Session.stopPolling()
        tools.forEach { it.isEnabled = false }
        start.label = getString(R.string.stop)
        start.progress = 0f
        step.text = title
        Session.worker.execute {
            val text = try {
                body(st)
            } catch (e: FlashTools.CancelledException) {
                "$title: отменено."
            } catch (e: Exception) {
                "$title: ${ErrorMessages.describe(e)}"
            }
            Session.logLine("# $text")
            runOnUiThread {
                running = false
                toolResult.text = text
                step.text = ""
                showIdle()
            }
        }
    }

    private fun toolProgress(done: Int, total: Int): Boolean {
        val p = if (total == 0) 1f else done.toFloat() / total
        runOnUiThread {
            start.progress = p
            start.sublabel = "${(p * 100).toInt()} %"
        }
        return !cancel
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

    companion object {
        private const val REQUEST_BACKUP = 10
        private const val REQUEST_CHECK = 11
        private const val MENU_EXPERT = 1
        private const val MENU_COPY = 2
        private const val MENU_LOGIC = 3
        private const val MENU_ABOUT = 4
        private const val FLASH_BASE = 0x08000000L
    }
}

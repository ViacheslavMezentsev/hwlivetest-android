package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.stlink.Address
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLink
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkConnection
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DpIdCode
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkUsb
import java.io.File

class MainActivity : Activity() {

    private lateinit var usb: StLinkUsb

    private lateinit var status: TextView
    private lateinit var adapterInfo: TextView
    private lateinit var targetInfo: TextView
    private lateinit var address: EditText
    private lateinit var readResult: TextView
    private lateinit var fieldsInfo: TextView
    private lateinit var fieldsList: LinearLayout
    private lateinit var logView: TextView
    private lateinit var buttons: List<Button>

    /** Строки списка полей по topic. */
    private val fieldRows = LinkedHashMap<String, FieldRow>()

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.usbDevice()
            if (Session.device?.sameAs(detached) == true) {
                Session.worker.execute { Session.closeConnection() }
                Session.device = null
                Session.adapterText = ""
                Session.targetText = ""
                logLine("# адаптер отключён")
                showStatus("Адаптер отключён.", error = true)
                adapterInfo.text = ""
                targetInfo.text = ""
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = StLinkUsb(this)

        status = findViewById(R.id.status)
        adapterInfo = findViewById(R.id.adapterInfo)
        targetInfo = findViewById(R.id.targetInfo)
        address = findViewById(R.id.address)
        readResult = findViewById(R.id.readResult)
        fieldsInfo = findViewById(R.id.fieldsInfo)
        fieldsList = findViewById(R.id.fieldsList)
        logView = findViewById(R.id.log)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnRead = findViewById<Button>(R.id.btnRead)
        buttons = listOf(btnScan, btnConnect, btnRead)

        btnScan.setOnClickListener { scan() }
        btnConnect.setOnClickListener { connect() }
        btnRead.setOnClickListener { readWord() }
        findViewById<Button>(R.id.btnOpenFields).setOnClickListener { openFieldsFile() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Session.log.clear()
            logHeader()
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(detachReceiver, filter)
        }

        if (Session.fieldsFile == null) loadSavedFields()
        buildFieldRows()

        if (Session.stlink != null) {
            // Пересоздание экрана (поворот, возврат): подключение живо, восстанавливаем вид.
            adapterInfo.text = Session.adapterText
            targetInfo.text = Session.targetText
            showStatus("Подключено: ${Session.target?.shortName ?: Session.device?.model?.title ?: ""}.", ok = true)
        } else {
            if (Session.log.text().isEmpty()) logHeader()
            handleIntent(intent)
        }
        logView.text = Session.log.text()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Session.onLog = { ui { logView.text = Session.log.text() } }
        logView.text = Session.log.text()
        startFieldPolling()
    }

    override fun onPause() {
        Session.stopPolling()
        Session.onLog = null
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(detachReceiver)
        if (isFinishing) Session.worker.execute { Session.closeConnection() }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FIELDS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadFields(uri)
    }

    /** Запуск по подключению адаптера: система уже выдала разрешение на это устройство. */
    private fun handleIntent(intent: Intent?) {
        val attached = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usb.wrap(intent.usbDevice()) else null
        if (attached != null) {
            logLine("# подключён ${attached.title}")
            selectDevice(attached)
        } else if (Session.stlink == null) {
            scan()
        }
    }

    private fun scan() {
        if (!usb.hasUsbHost) {
            showStatus("Телефон не поддерживает USB Host (OTG).", error = true)
            return
        }
        val found = usb.findDevices()
        logLine("# поиск: найдено ${found.size} ST-Link")
        if (found.isEmpty()) {
            Session.device = null
            adapterInfo.text = ""
            showStatus(
                "ST-Link не найден. Подключите адаптер через OTG-переходник. " +
                    "На некоторых телефонах (в том числе Huawei) OTG нужно включить в настройках.",
                error = true,
            )
            return
        }
        if (found.size > 1) logLine("# несколько адаптеров, выбран первый")
        selectDevice(found.first())
    }

    private fun selectDevice(d: StLinkDevice) {
        if (Session.device?.sameAs(d.device) == true && Session.stlink != null) return
        Session.worker.execute { Session.closeConnection() }
        Session.device = d
        adapterInfo.text = d.title
        targetInfo.text = ""
        if (usb.hasPermission(d)) {
            showStatus("Найден ${d.model.title}. Нажмите «Подключить и опознать».")
        } else {
            showStatus("Найден ${d.model.title}. Нужно разрешение на доступ к USB.")
            requestPermission(d) {}
        }
    }

    private fun requestPermission(d: StLinkDevice, then: () -> Unit) {
        usb.requestPermission(d) { granted ->
            logLine("# разрешение USB: ${if (granted) "выдано" else "отклонено"}")
            if (granted) {
                showStatus("Разрешение получено.")
                then()
            } else {
                showStatus(
                    "Нет разрешения на доступ к USB. Нажмите «Подключить и опознать» и разрешите доступ.",
                    error = true,
                )
            }
        }
    }

    private fun connect() {
        val d = Session.device ?: run {
            scan()
            Session.device
        } ?: return
        if (!usb.hasPermission(d)) {
            requestPermission(d) { connect() }
            return
        }
        setBusy(true)
        showStatus("Подключение к ${d.model.title}…")
        Session.worker.execute {
            try {
                Session.closeConnection()
                val t = usb.open(d)
                logLine("# ${d.title}: ${t.endpointsLabel}")
                val st = StLink(t, ::logLine)
                Session.attach(t, st)
                val c = st.connect()
                Session.adapterText = formatAdapter(d, c)
                ui { adapterInfo.text = Session.adapterText }
                c.warnings.forEach { logLine("# предупреждение: $it") }
                val info = TargetIdentifier(st).identify()
                info.notes.forEach { logLine("# $it") }
                Session.identified(info)
                Session.targetText = formatTarget(info)
                ui {
                    targetInfo.text = Session.targetText
                    showStatus("Готово: ${info.shortName}, ${info.cpuId?.coreName ?: "ядро не опознано"}.", ok = true)
                    buildFieldRows()
                    startFieldPolling()
                }
            } catch (e: Exception) {
                logLine("# ошибка: ${e.javaClass.simpleName}: ${e.message}")
                Session.closeConnection()
                ui { showStatus(ErrorMessages.describe(e), error = true) }
            } finally {
                ui { setBusy(false) }
            }
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
                logLine("# $text")
                ui { readResult.text = text }
            } catch (e: Exception) {
                logLine("# ошибка чтения: ${e.message}")
                ui { readResult.text = ErrorMessages.describe(e) }
            } finally {
                ui { setBusy(false) }
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
            val name = displayName(uri)
            File(filesDir, SAVED_FIELDS).writeText(text, Charsets.UTF_8)
            File(filesDir, SAVED_FIELDS_NAME).writeText(name, Charsets.UTF_8)
            applyFields(file, name)
            logLine("# поля: $name, ${file.fields.size} шт.")
            file.warnings.forEach { logLine("# поля: $it") }
        } catch (e: Exception) {
            fieldsInfo.text = "Не удалось прочитать файл: ${e.message}"
            logLine("# поля: ошибка ${e.message}")
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: SAVED_FIELDS
    }

    private fun loadSavedFields() {
        val f = File(filesDir, SAVED_FIELDS)
        if (!f.exists()) return
        try {
            val name = File(filesDir, SAVED_FIELDS_NAME).takeIf { it.exists() }?.readText() ?: SAVED_FIELDS
            Session.setFields(AddressesFile.parse(f.readText(Charsets.UTF_8)), name)
        } catch (e: Exception) {
            logLine("# поля: сохранённый файл не читается: ${e.message}")
        }
    }

    private fun applyFields(file: AddressesFile, name: String) {
        Session.setFields(file, name)
        buildFieldRows()
        startFieldPolling()
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
            onReadings = { r -> ui { showReadings(r) } },
            onStopped = { msg -> ui { fieldsInfo.text = "Опрос остановлен: $msg" } },
        )
    }

    private fun showReadings(readings: List<FieldSampler.Reading>) {
        for (r in readings) fieldRows[r.spec.topic]?.show(r)
    }

    // ---------- Оформление ----------

    private fun formatAdapter(d: StLinkDevice, c: StLinkConnection): String = buildString {
        appendLine("Модель      ${d.model.title}")
        appendLine("VID:PID     ${d.vidPid}")
        appendLine("Прошивка    ${c.version.label}")
        appendLine("Режим       ${c.initialMode.title} → SWD")
        appendLine("Напряжение  " + (c.targetVoltage?.let { "%.2f В".format(it) } ?: "не измеряется"))
        appendLine("DP IDCODE   ${c.dpIdCode.hex32()}")
        append("DP          ${DpIdCode(c.dpIdCode).description}")
        c.warnings.forEach { append("\n⚠ ").append(it) }
    }

    private fun formatTarget(info: TargetInfo): String = buildString {
        val rows = info.summary()
        val width = rows.maxOfOrNull { it.first.length } ?: 0
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) append('\n')
            append(k.padEnd(width + 1)).append(' ').append(v)
        }
        info.sramKb?.let { append("\n").append("SRAM".padEnd(width + 1)).append(" до $it КБ") }
        info.notes.forEach { append("\n• ").append(it) }
    }

    private fun logHeader() {
        logLine("# HW Live Test ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun logLine(line: String) = Session.logLine(line)

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HW Live Test log", Session.log.text()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showStatus(text: String, error: Boolean = false, ok: Boolean = false) {
        ui {
            status.text = text
            status.setTextColor(
                getColor(
                    when {
                        error -> R.color.error
                        ok -> R.color.ok
                        else -> android.R.color.black
                    },
                ),
            )
        }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    companion object {
        private const val REQUEST_FIELDS = 1
        private const val SAVED_FIELDS = "addresses.json"
        private const val SAVED_FIELDS_NAME = "addresses.name"
        private const val LIST_PERIOD_MS = 500L
    }
}

private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

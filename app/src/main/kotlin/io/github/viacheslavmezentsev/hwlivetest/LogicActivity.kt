package io.github.viacheslavmezentsev.hwlivetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Format
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Protocol
import io.github.viacheslavmezentsev.hwlivetest.fx2la.ProbeResult
import io.github.viacheslavmezentsev.hwlivetest.usb.Fx2Device
import io.github.viacheslavmezentsev.hwlivetest.usb.Fx2Usb

/**
 * Логический анализатор на FX2: подключение, загрузка fx2lafw и пробный захват со
 * статистикой по каналам (ТЗ ЛА 6.1, 6.6). Первый шаг: проверить, что телефон держит поток.
 */
class LogicActivity : ClockActivity() {

    private lateinit var usb: Fx2Usb
    private lateinit var logPresenter: LogPresenter
    private lateinit var status: TextView
    private lateinit var firmwareInfo: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var result: TextView
    private lateinit var rateSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var buttons: List<Button>
    private lateinit var btnCapture: Button

    private var rates: List<Long> = Fx2Protocol.SAMPLE_RATES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logic)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        usb = Fx2Usb(this)
        status = findViewById(R.id.laStatus)
        firmwareInfo = findViewById(R.id.laFirmware)
        deviceInfo = findViewById(R.id.laDevice)
        result = findViewById(R.id.laResult)
        rateSpinner = findViewById(R.id.laRate)
        durationSpinner = findViewById(R.id.laDuration)
        logPresenter = LogPresenter(findViewById(R.id.log))
        val btnFirmware = findViewById<Button>(R.id.btnLaFirmware)
        val btnConnect = findViewById<Button>(R.id.btnLaConnect)
        btnCapture = findViewById(R.id.btnLaCapture)
        buttons = listOf(btnFirmware, btnConnect, btnCapture)

        btnFirmware.setOnClickListener { pickFirmware() }
        btnConnect.setOnClickListener { connect() }
        btnCapture.setOnClickListener { capture() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }

        durationSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, DURATIONS.map { "%.1f с".format(it / 1000.0) })
        durationSpinner.setSelection(DURATIONS.indexOf(1000L))
        showRates(true)
        showFirmware()
        showDevice()
        LogicSession.lastResult?.let { result.text = describe(it) }
        if (LogicSession.analyzer == null) {
            val found = usb.findDevices()
            setStatus(if (found.isEmpty()) "Подключите анализатор через OTG." else "Найден: ${found.first().title}. Нажмите «Подключить».")
        }
    }

    override fun onResume() {
        super.onResume()
        logPresenter.start()
    }

    override fun onPause() {
        logPresenter.stop()
        super.onPause()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FIRMWARE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("файл не открылся")
            LogicSession.saveFirmware(this, bytes, MainActivity.displayName(this, uri))
            showFirmware()
        } catch (e: Exception) {
            firmwareInfo.text = ErrorMessages.describe(e)
        }
    }

    // ---------- Действия ----------

    private fun pickFirmware() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_FIRMWARE)
    }

    private fun connect() {
        val d = usb.findDevices().firstOrNull()
        if (d == null) {
            setStatus("Анализатор не найден: телефон его не видит. Список устройств USB — в журнале.")
            UsbInventory.log(this, "анализатор не найден")
            return
        }
        setBusy(true)
        setStatus("Разрешение USB для ${d.title}…")
        usb.requestPermission(d) { ok ->
            if (!ok) {
                setBusy(false)
                setStatus("Доступ к USB не выдан.")
                return@requestPermission
            }
            LogicSession.worker.execute { openOnWorker(d, allowUpload = true) }
        }
    }

    /** Открыть; если прошивки нет — загрузить, дождаться переподключения и открыть снова. */
    private fun openOnWorker(d: Fx2Device, allowUpload: Boolean) {
        try {
            val image = if (allowUpload) LogicSession.loadFirmware(this) else null
            if (allowUpload) ui { setStatus("Подключение…") }
            if (LogicSession.open(usb, d, image, allowUpload)) {
                ui {
                    setBusy(false)
                    setStatus("Анализатор готов.")
                    showRates(LogicSession.analyzer?.highSpeed ?: true)
                    showDevice()
                }
                return
            }
            ui { setStatus("Прошивка загружена, анализатор переподключается…") }
            val next = LogicSession.waitRenumeration(usb, d)
            ui {
                setStatus("Разрешение USB после загрузки прошивки…")
                usb.requestPermission(next) { ok ->
                    if (!ok) {
                        setBusy(false)
                        setStatus("Доступ к USB не выдан.")
                    } else {
                        LogicSession.worker.execute { openOnWorker(next, allowUpload = false) }
                    }
                }
            }
        } catch (e: Exception) {
            LogicSession.log("# ЛА: ошибка ${e.message}")
            ui {
                setBusy(false)
                setStatus(ErrorMessages.describe(e))
                showDevice()
            }
        }
    }

    private fun capture() {
        val la = LogicSession.analyzer
        if (la == null) {
            setStatus("Сначала нажмите «Подключить».")
            return
        }
        val rate = rates[rateSpinner.selectedItemPosition]
        val ms = DURATIONS[durationSpinner.selectedItemPosition]
        setBusy(true)
        setStatus("Захват ${Format.rate(rate)}, ${ms / 1000.0} с…")
        LogicSession.worker.execute {
            try {
                val r = la.probeCapture(rate, ms)
                LogicSession.lastResult = r
                ui {
                    result.text = describe(r)
                    setStatus(if (r.keepsUp) "Захват завершён: поток успевает." else "Захват завершён: поток НЕ успевает.")
                }
            } catch (e: Exception) {
                LogicSession.log("# ЛА: ошибка захвата ${e.message}")
                ui { setStatus(ErrorMessages.describe(e)) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    // ---------- Показ ----------

    private fun showRates(highSpeed: Boolean) {
        val old = if (::rateSpinner.isInitialized && rateSpinner.adapter != null) rates.getOrNull(rateSpinner.selectedItemPosition) else null
        rates = Fx2Protocol.allowedRates(highSpeed)
        rateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rates.map { Format.rate(it) })
        val want = old ?: DEFAULT_RATE
        rateSpinner.setSelection(rates.indexOf(want).takeIf { it >= 0 } ?: (rates.size - 1))
    }

    private fun showFirmware() {
        val name = LogicSession.firmwareName(this)
        firmwareInfo.text = if (name == null) {
            "Файл не выбран. Нужен fx2lafw-saleae-logic.fw из установки sigrok-cli (папка share\\sigrok-firmware)."
        } else {
            val img = try {
                LogicSession.loadFirmware(this)
            } catch (_: Exception) {
                null
            }
            "$name: ${img?.size ?: "?"} байт, sha256 ${img?.sha256Short ?: "?"}…"
        }
    }

    private fun showDevice() {
        deviceInfo.text = LogicSession.infoText.ifEmpty { "не подключён" }
        btnCapture.isEnabled = LogicSession.analyzer != null
    }

    private fun describe(r: ProbeResult): String = buildString {
        appendLine("Частота ${Format.rate(r.rate)}, отсчётов %,d из %,d (%.3f с)".format(r.receivedSamples, r.requestedSamples, r.captureSeconds))
        appendLine(
            "Поток %.2f МБ/с — %.0f %% от нужного: %s".format(
                r.throughput / 1e6, r.keepUpRatio * 100, if (r.keepsUp) "успевает" else "НЕ успевает",
            ),
        )
        appendLine("Буфер %,d байт × %d передач".format(r.bufferBytes, r.transfers))
        r.stallMessage?.let { appendLine("Обрыв: $it") }
        appendLine()
        appendLine("Канал  Уровень    Частота         Скважность")
        for (c in r.channels) {
            val f = if (c.transitions > 1) Format.frequency(c.frequencyHz(r.captureSeconds)) else "—"
            val duty = if (c.transitions > 1) "%.1f %%".format(c.duty * 100) else "—"
            appendLine("D%-5d %-10s %-15s %s".format(c.index, c.level, f, duty))
        }
    }.trimEnd()

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = buildString {
            LogicSession.lastResult?.let { appendLine(describe(it)).appendLine() }
            append(Session.log.text())
        }
        cm.setPrimaryClip(ClipData.newPlainText("HW Live Test LA", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
        if (!busy) btnCapture.isEnabled = LogicSession.analyzer != null
    }

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isDestroyed) block()
    }

    companion object {
        private const val REQUEST_FIRMWARE = 7
        private const val DEFAULT_RATE = 1_000_000L
        private val DURATIONS = listOf(500L, 1000L, 2000L, 5000L)
    }
}

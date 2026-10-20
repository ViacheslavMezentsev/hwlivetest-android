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
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.stlink.Address
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLink
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkConnection
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DpIdCode
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetIdentifier
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkUsb
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbStLinkTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var usb: StLinkUsb
    private lateinit var worker: ExecutorService

    private lateinit var status: TextView
    private lateinit var adapterInfo: TextView
    private lateinit var targetInfo: TextView
    private lateinit var address: EditText
    private lateinit var readResult: TextView
    private lateinit var logView: TextView
    private lateinit var buttons: List<Button>

    private val log = SessionLog()

    // Состояние сеанса меняется только в потоке worker.
    @Volatile
    private var device: StLinkDevice? = null
    private var transport: UsbStLinkTransport? = null
    private var stlink: StLink? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.usbDevice()
            if (device?.sameAs(detached) == true) {
                worker.execute { closeSession() }
                device = null
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
        worker = Executors.newSingleThreadExecutor()

        status = findViewById(R.id.status)
        adapterInfo = findViewById(R.id.adapterInfo)
        targetInfo = findViewById(R.id.targetInfo)
        address = findViewById(R.id.address)
        readResult = findViewById(R.id.readResult)
        logView = findViewById(R.id.log)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnRead = findViewById<Button>(R.id.btnRead)
        buttons = listOf(btnScan, btnConnect, btnRead)

        btnScan.setOnClickListener { scan() }
        btnConnect.setOnClickListener { connect() }
        btnRead.setOnClickListener { readWord() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            log.clear()
            logHeader()
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(detachReceiver, filter)
        }

        logHeader()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(detachReceiver)
        worker.execute { closeSession() }
        worker.shutdown()
        super.onDestroy()
    }

    /** Запуск по подключению адаптера: система уже выдала разрешение на это устройство. */
    private fun handleIntent(intent: Intent?) {
        val attached = if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usb.wrap(intent.usbDevice()) else null
        if (attached != null) {
            logLine("# подключён ${attached.title}")
            selectDevice(attached)
        } else {
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
            device = null
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
        if (device?.sameAs(d.device) != true) worker.execute { closeSession() }
        device = d
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
        val d = device ?: run {
            scan()
            device
        } ?: return
        if (!usb.hasPermission(d)) {
            requestPermission(d) { connect() }
            return
        }
        setBusy(true)
        showStatus("Подключение к ${d.model.title}…")
        worker.execute {
            try {
                closeSession()
                val t = usb.open(d)
                transport = t
                logLine("# ${d.title}: ${t.endpointsLabel}")
                val st = StLink(t, ::logLine)
                stlink = st
                val c = st.connect()
                ui { adapterInfo.text = formatAdapter(d, c) }
                c.warnings.forEach { logLine("# предупреждение: $it") }
                val info = TargetIdentifier(st).identify()
                info.notes.forEach { logLine("# $it") }
                ui {
                    targetInfo.text = formatTarget(info)
                    showStatus("Готово: ${info.shortName}, ${info.cpuId?.coreName ?: "ядро не опознано"}.", ok = true)
                }
            } catch (e: Exception) {
                logLine("# ошибка: ${e.javaClass.simpleName}: ${e.message}")
                closeSession()
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
        worker.execute {
            try {
                val st = stlink ?: throw IllegalStateException("Сначала нажмите «Подключить и опознать».")
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

    /** Вызывать только в потоке worker. */
    private fun closeSession() {
        stlink?.disconnect()
        stlink = null
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
    }

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
        info.notes.forEach { append("\n• ").append(it) }
    }

    private fun logHeader() {
        logLine("# HW Live Test ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun logLine(line: String) {
        log.add(line)
        ui { logView.text = log.text() }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HW Live Test log", log.text()))
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
}

private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

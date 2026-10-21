package io.github.viacheslavmezentsev.hwlivetest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Что телефон видит на шине USB: для диагностики, когда прибор «не находится». Серийные номера
 * не читаются; имена производителя и изделия видны только при разрешении на устройство.
 */
object UsbInventory {
    fun lines(c: Context): List<String> {
        val pm = c.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) return listOf("телефон не поддерживает USB Host (OTG)")
        val manager = c.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = manager.deviceList.values.sortedBy { it.deviceName }
        if (list.isEmpty()) return listOf("устройств USB нет (система ничего не видит на порту)")
        return list.map { describe(manager, it) }
    }

    fun describe(manager: UsbManager, d: UsbDevice): String {
        val names = if (manager.hasPermission(d)) {
            try {
                listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifEmpty { null }
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }
        val ifaces = (0 until d.interfaceCount).joinToString(",") { "%02X".format(d.getInterface(it).interfaceClass) }
        return "%04X:%04X %s, интерфейсы [%s]%s".format(
            d.vendorId, d.productId, d.deviceName, ifaces, names?.let { " — $it" } ?: "",
        )
    }

    fun log(c: Context, title: String) {
        val l = lines(c)
        Session.logLine("# USB, $title: ${l.size} шт.")
        l.forEach { Session.logLine("#   $it") }
    }

    private val USB_LINE = Regex(
        "usb|hub|xhci|ehci|dwc|otg|typec|type-c|UsbHost|UsbDescriptor|UsbPort|enumerat|descriptor|0925|3881",
        RegexOption.IGNORE_CASE,
    )
    private val SERIAL = Regex("\\b[0-9A-Za-z]{12,}\\b")

    /**
     * Узлы `/dev/bus/usb`: что опознало ядро, даже если Android не показал устройство приложению.
     * Список может быть недоступен приложению (SELinux) — тогда так и пишется.
     */
    fun kernelNodes(): List<String> {
        val root = File("/dev/bus/usb")
        val buses = root.listFiles() ?: return listOf("/dev/bus/usb: нет доступа")
        return buses.sortedBy { it.name }.map { bus ->
            val nodes = bus.listFiles()?.map { it.name }?.sorted()
            "/dev/bus/usb/${bus.name}: ${nodes?.joinToString(" ")?.ifEmpty { "пусто" } ?: "нет доступа"}"
        }
    }

    /**
     * Строки системного журнала про USB (последние [max]). Нужно разрешение READ_LOGS, которое
     * выдаётся только через adb; без него приложение видит лишь свои строки. Серийные номера
     * маскируются.
     */
    fun systemLog(c: Context, max: Int = 80): List<String> {
        if (c.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            return listOf(
                "нет разрешения READ_LOGS; выдать: adb shell pm grant ${c.packageName} android.permission.READ_LOGS",
            )
        }
        return try {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-b", "main", "-b", "system", "-t", "5000")
                .redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor(5, TimeUnit.SECONDS)
            lines.filter { USB_LINE.containsMatchIn(it) && !it.contains("hwlivetest") }
                .map { SERIAL.replace(it, "<id>") }
                .takeLast(max)
        } catch (e: Exception) {
            listOf("logcat не запустился: ${e.message}")
        }
    }

    /** Полная диагностика в журнал приложения: список Android, узлы ядра, системный журнал. */
    fun logDiagnostics(c: Context, title: String) {
        log(c, title)
        Session.logLine("# USB, узлы ядра:")
        kernelNodes().forEach { Session.logLine("#   $it") }
        val sys = systemLog(c)
        Session.logLine("# USB, системный журнал: ${sys.size} строк")
        sys.forEach { Session.logLine("#   $it") }
    }
}

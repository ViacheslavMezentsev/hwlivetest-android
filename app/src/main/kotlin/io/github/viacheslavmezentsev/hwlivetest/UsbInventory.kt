package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

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
}

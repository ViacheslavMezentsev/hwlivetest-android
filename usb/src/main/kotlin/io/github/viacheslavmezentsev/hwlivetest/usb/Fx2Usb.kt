package io.github.viacheslavmezentsev.hwlivetest.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Ids
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Profile
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Protocol

/** Найденный анализатор на FX2. Серийный номер не читается. */
class Fx2Device(val device: UsbDevice, val profile: Fx2Profile) {
    val title: String get() = "${profile.title} (${profile.vidPid})"

    fun sameAs(other: UsbDevice?): Boolean = other != null && other.deviceName == device.deviceName

    /** Строки производителя и изделия (нужно разрешение USB); fx2lafw выставляет «sigrok» и «fx2lafw». */
    fun strings(): Pair<String?, String?> = try {
        device.manufacturerName to device.productName
    } catch (_: SecurityException) {
        null to null
    }
}

/** Поиск анализаторов на FX2, разрешение и открытие (ТЗ ЛА 3.1, 6.1). */
class Fx2Usb(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    val hasUsbHost: Boolean
        get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun findDevices(): List<Fx2Device> =
        manager.deviceList.values
            .mapNotNull { d -> Fx2Ids.find(d.vendorId, d.productId)?.let { Fx2Device(d, it) } }
            .sortedBy { it.device.deviceName }

    fun hasPermission(device: Fx2Device): Boolean = manager.hasPermission(device.device)

    fun requestPermission(device: Fx2Device, onResult: (Boolean) -> Unit) =
        UsbPermissions.request(appContext, device.device, onResult)

    /** Открыть устройство и захватить интерфейс 0. Точка данных `0x82` может появиться только после загрузки прошивки. */
    fun open(device: Fx2Device): UsbFx2Transport {
        if (!hasUsbHost) {
            throw UsbAccessException(UsbAccessException.Reason.NO_USB_HOST, "Телефон не поддерживает USB Host (OTG)")
        }
        if (!manager.hasPermission(device.device)) {
            throw UsbAccessException(UsbAccessException.Reason.NO_PERMISSION, "Нет разрешения на доступ к ${device.title}")
        }
        if (device.device.interfaceCount == 0) {
            throw UsbAccessException(UsbAccessException.Reason.NO_INTERFACE, "У ${device.title} нет интерфейсов")
        }
        val iface = device.device.getInterface(0)
        val connection = manager.openDevice(device.device)
            ?: throw UsbAccessException(UsbAccessException.Reason.OPEN_FAILED, "Не удалось открыть ${device.title}")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw UsbAccessException(UsbAccessException.Reason.CLAIM_FAILED, "Не удалось захватить интерфейс 0 у ${device.title}")
        }
        val data = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.address == Fx2Protocol.DATA_ENDPOINT
        }
        return UsbFx2Transport(connection, iface, data)
    }

    /** Описание точек интерфейса 0 для журнала. */
    fun describe(device: Fx2Device): String {
        val d = device.device
        if (d.interfaceCount == 0) return "интерфейсов нет"
        val iface = d.getInterface(0)
        val eps = (0 until iface.endpointCount).joinToString(", ") {
            val e = iface.getEndpoint(it)
            val type = when (e.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
                else -> "ctrl"
            }
            "0x%02X %s %d".format(e.address, type, e.maxPacketSize)
        }
        return "интерфейсов ${d.interfaceCount}, интерфейс 0: класс 0x%02X, точки: %s".format(iface.interfaceClass, eps.ifEmpty { "нет" })
    }
}

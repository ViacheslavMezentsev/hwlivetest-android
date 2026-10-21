package io.github.viacheslavmezentsev.hwlivetest.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkIds

/** Поиск ST-Link, запрос разрешения и открытие bulk-канала. */
class StLinkUsb(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    val hasUsbHost: Boolean
        get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun findDevices(): List<StLinkDevice> =
        manager.deviceList.values
            .mapNotNull { d -> StLinkIds.find(d.vendorId, d.productId)?.let { StLinkDevice(d, it) } }
            .sortedBy { it.device.deviceName }

    fun wrap(device: UsbDevice?): StLinkDevice? =
        device?.let { d -> StLinkIds.find(d.vendorId, d.productId)?.let { StLinkDevice(d, it) } }

    fun hasPermission(device: StLinkDevice): Boolean = manager.hasPermission(device.device)

    /** Показать системный запрос разрешения; [onResult] вызывается в главном потоке. */
    fun requestPermission(device: StLinkDevice, onResult: (Boolean) -> Unit) =
        UsbPermissions.request(appContext, device.device, onResult)

    /** Открыть устройство и захватить интерфейс отладки. */
    fun open(device: StLinkDevice): UsbStLinkTransport {
        if (!hasUsbHost) {
            throw UsbAccessException(UsbAccessException.Reason.NO_USB_HOST, "Телефон не поддерживает USB Host (OTG)")
        }
        if (!manager.hasPermission(device.device)) {
            throw UsbAccessException(UsbAccessException.Reason.NO_PERMISSION, "Нет разрешения на доступ к ${device.title}")
        }
        val (iface, out, inp) = findDebugInterface(device.device)
            ?: throw UsbAccessException(
                UsbAccessException.Reason.NO_INTERFACE,
                "У ${device.title} не найден интерфейс отладки с bulk-точками",
            )
        val connection = manager.openDevice(device.device)
            ?: throw UsbAccessException(UsbAccessException.Reason.OPEN_FAILED, "Не удалось открыть ${device.title}")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw UsbAccessException(
                UsbAccessException.Reason.CLAIM_FAILED,
                "Не удалось захватить интерфейс ${iface.id} у ${device.title}",
            )
        }
        return UsbStLinkTransport(connection, iface, out, inp)
    }

    private data class DebugInterface(val iface: UsbInterface, val out: UsbEndpoint, val inp: UsbEndpoint)

    /**
     * Интерфейс отладки — vendor-specific (0xFF) с bulk OUT и bulk IN. Из IN-точек берём
     * с меньшим адресом (0x81): вторая (0x82/0x83) — трассировка SWO.
     */
    private fun findDebugInterface(device: UsbDevice): DebugInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            val bulk = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val out = bulk.filter { it.direction == UsbConstants.USB_DIR_OUT }.minByOrNull { it.address }
            val inp = bulk.filter { it.direction == UsbConstants.USB_DIR_IN }.minByOrNull { it.address }
            if (out != null && inp != null) return DebugInterface(iface, out, inp)
        }
        return null
    }
}

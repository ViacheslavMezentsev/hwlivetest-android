package io.github.viacheslavmezentsev.hwlivetest.usb

import android.hardware.usb.UsbDevice
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkModel

/** Найденный адаптер. Серийный номер намеренно не читается и не показывается. */
class StLinkDevice(val device: UsbDevice, val model: StLinkModel) {
    val vidPid: String get() = "%04X:%04X".format(device.vendorId, device.productId)

    val title: String get() = "${model.title} ($vidPid)"

    fun sameAs(other: UsbDevice?): Boolean = other != null && other.deviceName == device.deviceName
}

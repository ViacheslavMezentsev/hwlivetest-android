package io.github.viacheslavmezentsev.hwlivetest

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** Следит за отключением адаптера для всего приложения, а не для отдельного экрана. */
class HwApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.beginTrafficMonitoring()
        Session.debugDetails.value = Prefs.debugDetails(this)
        Session.logPolling = Prefs.logPolling(this)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val d = intent.usbDevice()
                val m = context.getSystemService(Context.USB_SERVICE) as UsbManager
                when (intent.action) {
                    // Каждое подключение и отключение — в журнал: видно, замечает ли телефон прибор.
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        d?.let { Session.logLine("# USB подключено: ${UsbInventory.describe(m, it)}") }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        d?.let { Session.logLine("# USB отключено: %04X:%04X %s".format(it.vendorId, it.productId, it.deviceName)) }
                        Session.detached(d)
                        LogicSession.detached(d)
                    }
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}

fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

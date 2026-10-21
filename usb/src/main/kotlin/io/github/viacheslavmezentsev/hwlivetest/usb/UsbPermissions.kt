package io.github.viacheslavmezentsev.hwlivetest.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запрос разрешения USB для любого прибора. Ответ сопоставляется с устройством по
 * `EXTRA_DEVICE`: при нескольких запросах подряд ответ одного устройства не завершает
 * ожидание другого (ТЗ ST-Link 5.2.3).
 */
object UsbPermissions {
    const val ACTION_USB_PERMISSION = "io.github.viacheslavmezentsev.hwlivetest.USB_PERMISSION"

    private val nextCode = AtomicInteger(1)

    /** Показать системный запрос; [onResult] вызывается в главном потоке. */
    fun request(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val answered = intent.usbDeviceExtra() ?: return
                if (answered.deviceName != device.deviceName) return
                app.unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        // Система дописывает в intent extras, поэтому PendingIntent должен быть изменяемым.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        // Свой requestCode у каждого запроса: иначе система переиспользует PendingIntent.
        val pending = PendingIntent.getBroadcast(app, nextCode.getAndIncrement(), intent, flags)
        manager.requestPermission(device, pending)
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

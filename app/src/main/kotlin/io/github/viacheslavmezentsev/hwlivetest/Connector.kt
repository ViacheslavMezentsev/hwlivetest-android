package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkUsb

/**
 * Поиск адаптера, разрешение USB и подключение — общие для простого режима и режима
 * эксперта. [status] вызывается в главном потоке.
 */
class Connector(private val activity: Activity, private val status: (String, Tone) -> Unit) {
    enum class Tone { NORMAL, OK, ERROR }

    val usb = StLinkUsb(activity)

    /** Адаптер из intent подключения (система уже выдала на него разрешение). */
    fun fromIntent(intent: Intent?): StLinkDevice? =
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usb.wrap(intent.usbDevice()) else null

    fun scan(): StLinkDevice? {
        if (!usb.hasUsbHost) {
            status("Телефон не поддерживает USB Host (OTG).", Tone.ERROR)
            return null
        }
        val found = usb.findDevices()
        Session.logLine("# поиск: найдено ${found.size} ST-Link")
        if (found.isEmpty()) {
            Session.device = null
            status(
                "ST-Link не найден. Подключите адаптер через OTG-переходник. " +
                    "На некоторых телефонах (в том числе Huawei) OTG нужно включить в настройках.",
                Tone.ERROR,
            )
            return null
        }
        if (found.size > 1) Session.logLine("# несколько адаптеров, выбран первый")
        select(found.first())
        return found.first()
    }

    fun select(d: StLinkDevice) {
        if (Session.device?.sameAs(d.device) == true && Session.stlink != null) return
        Session.device = d
        Session.worker.execute {
            Session.closeConnection()
            Session.notifyChanged()
        }
        status("Найден ${d.model.title}.", Tone.NORMAL)
    }

    fun withPermission(d: StLinkDevice, then: () -> Unit) {
        if (usb.hasPermission(d)) {
            then()
            return
        }
        status("Нужно разрешение на доступ к USB.", Tone.NORMAL)
        usb.requestPermission(d) { granted ->
            Session.logLine("# разрешение USB: ${if (granted) "выдано" else "отклонено"}")
            if (granted) {
                then()
            } else {
                status("Нет разрешения на доступ к USB. Повторите и разрешите доступ.", Tone.ERROR)
            }
        }
    }

    /**
     * Найти адаптер (если ещё не выбран), получить разрешение и подключиться.
     * [onDone] вызывается в главном потоке: с [TargetInfo] или null при ошибке.
     */
    fun connect(onDone: (TargetInfo?) -> Unit) {
        val d = Session.device ?: scan()
        if (d == null) {
            onDone(null)
            return
        }
        withPermission(d) {
            status("Подключение к ${d.model.title}…", Tone.NORMAL)
            Session.worker.execute {
                val info = try {
                    Session.connectBlocking(usb, d)
                } catch (e: Exception) {
                    activity.runOnUiThread { status(ErrorMessages.describe(e), Tone.ERROR) }
                    null
                }
                activity.runOnUiThread {
                    if (info != null) status("Готово: ${info.shortName}, ${info.cpuId?.coreName ?: "ядро не опознано"}.", Tone.OK)
                    onDone(info)
                }
            }
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.usb

import java.io.IOException

class UsbAccessException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason {
        /** Телефон не поддерживает USB Host. */
        NO_USB_HOST,

        /** Пользователь не выдал разрешение на устройство. */
        NO_PERMISSION,

        /** Не удалось открыть устройство (отключено, занято другим приложением). */
        OPEN_FAILED,

        /** У устройства нет интерфейса с bulk-точками ST-Link. */
        NO_INTERFACE,

        /** Интерфейс не удалось захватить. */
        CLAIM_FAILED,
    }
}

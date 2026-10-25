package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Exception
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Firmware
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbAccessException

/** Понятные пользователю сообщения об ошибках. */
object ErrorMessages {
    fun describe(e: Throwable): String = when (e) {
        is UsbAccessException -> when (e.reason) {
            UsbAccessException.Reason.NO_USB_HOST -> "Телефон не поддерживает USB Host (OTG)."
            UsbAccessException.Reason.NO_PERMISSION ->
                "Нет разрешения на доступ к USB. Нажмите «Подключить и опознать» и разрешите доступ."
            UsbAccessException.Reason.OPEN_FAILED ->
                "Не удалось открыть адаптер. Переподключите его; если он занят другим приложением, закройте его."
            UsbAccessException.Reason.NO_INTERFACE,
            UsbAccessException.Reason.CLAIM_FAILED,
            -> "${e.message}. Переподключите адаптер."
        }
        is StLinkException -> when (e.kind) {
            StLinkErrorKind.NO_TARGET -> "Нет устройства. ${e.message}"
            StLinkErrorKind.SWD_FAULT ->
                "SWD недоступен: ${e.message}. Возможно, прошивка переназначила выводы SWD " +
                    "или MCU в режиме пониженного потребления."
            StLinkErrorKind.OLD_FIRMWARE -> "Старая прошивка адаптера. ${e.message}"
            StLinkErrorKind.UNSUPPORTED_ADAPTER -> "Адаптер не поддерживается: ${e.message}"
            StLinkErrorKind.USB_IO -> "Обмен по USB прервался (${e.message}). Проверьте OTG-кабель и переподключите адаптер."
            StLinkErrorKind.PROTOCOL -> "Неожиданный ответ адаптера: ${e.message}"
            StLinkErrorKind.BAD_ARGUMENT -> e.message ?: "Неверный аргумент"
            StLinkErrorKind.NOT_PERMITTED -> e.message ?: "Доступ выключен."
        }
        is Fx2Exception -> e.message ?: e.kind.name
        is Fx2Firmware.BadImage -> "Файл прошивки не подходит: ${e.message}."
        is IllegalStateException -> e.message ?: "Неверное состояние"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}

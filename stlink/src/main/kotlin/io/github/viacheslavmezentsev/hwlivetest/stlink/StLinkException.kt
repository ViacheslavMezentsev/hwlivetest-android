package io.github.viacheslavmezentsev.hwlivetest.stlink

import java.io.IOException

enum class StLinkErrorKind {
    /** Ошибка обмена по USB (адаптер отключён, таймаут). */
    USB_IO,

    /** Ответ неожиданной длины или формата. */
    PROTOCOL,

    /** Цель не отвечает: нет питания, не подключены SWDIO/SWCLK/GND. */
    NO_TARGET,

    /** SWD-доступ не удался: ошибка AP/DP, адрес не отображён, ядро спит. */
    SWD_FAULT,

    /** Прошивка адаптера слишком старая для нужной команды. */
    OLD_FIRMWARE,

    /** Адаптер не поддерживается (например, ST-Link/V1). */
    UNSUPPORTED_ADAPTER,

    /** Неверный аргумент (невыровненный адрес и т. п.). */
    BAD_ARGUMENT,

    /** Изменение цели без действующего разрешения (ТЗ ST-Link 2.0, п. 5.11.1). */
    NOT_PERMITTED,
}

class StLinkException(
    val kind: StLinkErrorKind,
    message: String,
    /** Код состояния ST-Link (первый байт ответа), если был. */
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

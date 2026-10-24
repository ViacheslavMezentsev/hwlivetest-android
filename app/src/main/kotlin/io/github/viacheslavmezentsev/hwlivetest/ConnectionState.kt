package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.14: общий снимок маршрута, без USB-дескрипторов и уникальных ID. */
data class ConnectionState(
    val stage: Stage = Stage.DISCONNECTED,
    val adapter: String = "",
    val mcu: String = "",
    val error: String? = null,
) {
    enum class Stage { DISCONNECTED, USB_FOUND, PERMISSION_REQUIRED, CONNECTING, SWD, READY, ERROR }
    val ready: Boolean get() = stage == Stage.READY
    val label: String get() = when (stage) {
        Stage.DISCONNECTED -> "USB: адаптер не подключён"
        Stage.USB_FOUND -> "USB: адаптер найден · SWD не подключён"
        Stage.PERMISSION_REQUIRED -> "USB: ожидается разрешение"
        Stage.CONNECTING -> "USB → SWD: подключение…"
        Stage.SWD -> "USB → SWD: опознание MCU…"
        Stage.READY -> "USB → SWD → "+mcu
        Stage.ERROR -> "Подключение: "+(error ?: "ошибка")
    }
}

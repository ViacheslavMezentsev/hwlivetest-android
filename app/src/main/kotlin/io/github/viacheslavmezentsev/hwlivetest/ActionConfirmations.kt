package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.18: быстрый режим допускает только перечисленные команды ядра. */
object ActionConfirmations {
    private val quick = setOf("Остановить ядро", "Запустить ядро", "Сброс с запуском", "Сброс с остановкой")
    fun required(action: String, fast: Boolean, writeMode: Boolean): Boolean =
        !(fast && writeMode && action in quick)
}

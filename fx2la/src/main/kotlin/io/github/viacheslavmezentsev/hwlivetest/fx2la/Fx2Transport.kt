package io.github.viacheslavmezentsev.hwlivetest.fx2la

import java.io.IOException

/**
 * Доступ к анализатору на FX2 (ТЗ ЛА 7.1.1). Реализация поверх Android `UsbManager` живёт в
 * модуле `usb`, в тестах — имитатор.
 */
interface Fx2Transport : AutoCloseable {
    /** Размер пакета bulk-точки данных: 512 — высокая скорость USB, 64 — полная. */
    val packetSize: Int

    /** Наибольший буфер одной передачи, который допускает платформа. */
    val maxBufferBytes: Int

    /** Управляющий запрос vendor OUT; ошибка — [IOException]. */
    @Throws(IOException::class)
    fun controlOut(request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int)

    /** Управляющий запрос vendor IN; null — устройство отказало (STALL) или не ответило. */
    fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray?

    /**
     * Непрерывное чтение точки данных очередью из [transfers] передач по [bufferBytes] байт.
     * [sink] получает данные, их длину и время завершения передачи (нс, общая шкала) и
     * возвращает false, чтобы остановить чтение. Если данных нет дольше [stallMs], бросается
     * [StallException] (ТЗ ЛА 4.2.2); отказ USB — [IOException].
     */
    @Throws(IOException::class)
    fun stream(bufferBytes: Int, transfers: Int, stallMs: Long, sink: (ByteArray, Int, Long) -> Boolean)

    override fun close()
}

class StallException(message: String) : IOException(message)

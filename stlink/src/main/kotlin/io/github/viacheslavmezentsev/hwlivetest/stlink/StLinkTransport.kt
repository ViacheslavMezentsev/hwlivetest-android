package io.github.viacheslavmezentsev.hwlivetest.stlink

import java.io.IOException

/**
 * Bulk-канал к адаптеру. Реализация на Android — в модуле `usb`, в тестах — проигрывание
 * записанного обмена.
 */
interface StLinkTransport : AutoCloseable {
    /** Отправить пакет команды (16 байт) в конечную точку OUT. */
    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** Прочитать ровно [length] байт из конечной точки IN. */
    @Throws(IOException::class)
    fun read(length: Int): ByteArray

    override fun close() {}
}

package io.github.viacheslavmezentsev.hwlivetest.recorder

/**
 * Задел под запись измерений в mcap/rosbag.
 *
 * Источник (чтение памяти MCU, логический анализатор) отдаёт отсчёты с отметкой времени,
 * а конкретный формат файла реализует этот интерфейс.
 */
interface SampleSink : AutoCloseable {
    /** Объявить канал (переменную или линию) до первой записи. */
    fun defineChannel(id: Int, name: String, unit: String = "")

    /** Записать отсчёт: время в наносекундах от начала записи. */
    fun write(channelId: Int, timestampNs: Long, value: Double)
}

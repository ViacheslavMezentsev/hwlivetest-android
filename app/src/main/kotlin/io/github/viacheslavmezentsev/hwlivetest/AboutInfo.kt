package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.os.Build
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Ids
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkIds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Сведения для окна «О программе», разбитые на разделы (заголовок → строки). */
object AboutInfo {
    data class Section(val title: String, val lines: List<String>, val mono: Boolean = false)

    private val DATE = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru"))

    /** Модули сборки и что они дают (архитектура, раздел 3). */
    val MODULES = listOf(
        "stlink" to "ST-Link V2/V2-1/V3: SWD, чтение памяти, опознание Cortex-M, STM32, AT32",
        "usb" to "поиск приборов, разрешения USB, транспорт ST-Link и FX2",
        "fx2la" to "логический анализатор на FX2 (fx2lafw): загрузка прошивки, пробный захват до 24 МГц",
        "diag" to "эвристики кнопки «Проверить плату»",
        "telemetry" to "поля addresses.json, опрос, ряды и оси графиков",
        "recorder" to "запись MCAP (ROS 1 std_msgs)",
        "firmware" to "Intel HEX, резервная копия и сверка Flash",
    )

    fun build(c: Context): List<Section> = listOf(
        Section(
            "Сборка",
            listOf(
                "HW Live Test ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                "Сборка: ${origin()}, ${BuildConfig.BUILD_TYPE}",
                "Коммит: ${BuildConfig.GIT_SHA}",
                "Собрано: ${buildTimeLocal()}",
                "Установлено: ${installTime(c)}",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            ),
        ),
        Section("Модули", MODULES.map { "${it.first} — ${it.second}" }),
        Section(
            "Приборы",
            StLinkIds.models.map { "${it.title}: ${"%04X:%04X".format(StLinkIds.ST_VID, it.pid)}" } +
                Fx2Ids.profiles.map { "${it.title}: ${it.vidPid}" } +
                "USB-UART (CDC-ACM, CH340, CP210x, FTDI, PL2303): в разработке",
        ),
        Section(
            "Устройства USB сейчас",
            UsbInventory.lines(c) + UsbInventory.kernelNodes().map { "ядро: $it" },
            mono = true,
        ),
    )

    /** Размеры: APK, внутренняя папка по подпапкам, внешняя папка приложения. Долго — не в главном потоке. */
    fun storage(c: Context): Section {
        val lines = mutableListOf<String>()
        lines += "APK: ${size(File(c.applicationInfo.sourceDir).length())}"
        val parts = File(c.applicationInfo.dataDir).listFiles().orEmpty()
            .map { it.name to sizeOf(it) }.sortedByDescending { it.second }
        lines += "Внутренняя папка: ${size(parts.sumOf { it.second })}"
        for ((name, bytes) in parts) if (bytes > 0) lines += "  $name: ${size(bytes)}"
        val files = c.filesDir.listFiles().orEmpty().sortedByDescending { sizeOf(it) }
        if (files.isNotEmpty()) {
            lines += "  в files:"
            for (f in files.take(10)) lines += "    ${f.name}: ${size(sizeOf(f))}"
        }
        val ext = c.getExternalFilesDir(null)?.parentFile
        lines += "Внешняя папка (Android/data): ${ext?.let { size(sizeOf(it)) } ?: "нет"}"
        lines += "Свободно во внутренней памяти: ${size(c.filesDir.usableSpace)}"
        lines += "Записи MCAP хранятся в выбранной вами папке и сюда не входят."
        return Section("Данные приложения", lines, mono = true)
    }

    fun text(sections: List<Section>): String =
        sections.joinToString("\n\n") { s -> s.title.uppercase() + "\n" + s.lines.joinToString("\n") }

    private fun origin(): String {
        val o = BuildConfig.BUILD_ORIGIN
        return if (o.startsWith("ci-")) "GitHub Actions, запуск ${o.removePrefix("ci-")}" else "локальная"
    }

    private fun buildTimeLocal(): String = try {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        DATE.format(utc.parse(BuildConfig.BUILD_TIME)!!)
    } catch (_: Exception) {
        BuildConfig.BUILD_TIME
    }

    private fun installTime(c: Context): String = try {
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        DATE.format(Date(info.lastUpdateTime))
    } catch (_: Exception) {
        "?"
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) f.listFiles().orEmpty().sumOf { sizeOf(it) } else f.length()

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f КБ".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes байт"
    }
}

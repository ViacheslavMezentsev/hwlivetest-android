package io.github.viacheslavmezentsev.hwlivetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Ids
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkIds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * О программе: версия и сборка (чтобы сразу видеть, установлен ли новый APK), поддерживаемые
 * модули и приборы, место, которое занимают данные приложения.
 */
class AboutActivity : ClockActivity() {

    private lateinit var text: TextView
    private var storageText = "Подсчёт…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        text = findViewById(R.id.aboutText)
        findViewById<Button>(R.id.btnAboutCopy).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("HW Live Test about", text.text))
            Toast.makeText(this, R.string.about_copied, Toast.LENGTH_SHORT).show()
        }
        show()
        // Обход папок может занять заметное время — не в главном потоке.
        Thread {
            val s = storage()
            runOnUiThread {
                if (!isDestroyed) {
                    storageText = s
                    show()
                }
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun show() {
        text.text = buildString {
            appendLine("HW Live Test ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})")
            appendLine("Сборка: ${origin()}, ${BuildConfig.BUILD_TYPE}")
            appendLine("Коммит: ${BuildConfig.GIT_SHA}")
            appendLine("Собрано: ${buildTimeLocal()}")
            appendLine("Установлено: ${installTime()}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("МОДУЛИ")
            for (m in MODULES) appendLine("• ${m.first} — ${m.second}")
            appendLine()
            appendLine("ПРИБОРЫ")
            for (m in StLinkIds.models) appendLine("• ${m.title}: ${"%04X:%04X".format(StLinkIds.ST_VID, m.pid)}")
            for (p in Fx2Ids.profiles) appendLine("• ${p.title}: ${p.vidPid}")
            appendLine("• USB-UART (CDC-ACM, CH340, CP210x, FTDI, PL2303): в разработке")
            appendLine()
            appendLine("УСТРОЙСТВА USB СЕЙЧАС")
            for (l in UsbInventory.lines(this@AboutActivity)) appendLine("• $l")
            appendLine()
            appendLine("ДАННЫЕ ПРИЛОЖЕНИЯ")
            append(storageText)
        }
    }

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

    private fun installTime(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        DATE.format(Date(info.lastUpdateTime))
    } catch (_: Exception) {
        "?"
    }

    /** Размеры: APK, внутренняя папка по подпапкам, внешняя папка приложения. */
    private fun storage(): String = buildString {
        val apk = File(applicationInfo.sourceDir).length()
        appendLine("APK: ${size(apk)}")
        val data = File(applicationInfo.dataDir)
        val parts = data.listFiles().orEmpty().map { it.name to sizeOf(it) }.sortedByDescending { it.second }
        appendLine("Внутренняя папка: ${size(parts.sumOf { it.second })}")
        for ((name, bytes) in parts) if (bytes > 0) appendLine("  $name: ${size(bytes)}")
        val files = filesDir.listFiles().orEmpty().sortedByDescending { sizeOf(it) }
        if (files.isNotEmpty()) {
            appendLine("  в files:")
            for (f in files.take(10)) appendLine("    ${f.name}: ${size(sizeOf(f))}")
        }
        val ext = getExternalFilesDir(null)?.parentFile
        appendLine("Внешняя папка (Android/data): ${ext?.let { size(sizeOf(it)) } ?: "нет"}")
        appendLine("Свободно во внутренней памяти: ${size(filesDir.usableSpace)}")
        append("Записи MCAP хранятся в выбранной вами папке и сюда не входят.")
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) f.listFiles().orEmpty().sumOf { sizeOf(it) } else f.length()

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f КБ".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes байт"
    }

    companion object {
        private val DATE = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru"))

        /** Модули сборки и что они дают (архитектура, раздел 3). */
        val MODULES = listOf(
            "stlink" to "ST-Link V2/V2-1/V3: SWD, чтение памяти, опознание Cortex-M, STM32, AT32",
            "usb" to "поиск приборов, разрешения USB, транспорт ST-Link и FX2",
            "fx2la" to "логический анализатор на FX2 (fx2lafw): загрузка прошивки, пробный захват до 24 МГц",
            "diag" to "эвристики кнопки «ПУСК»",
            "telemetry" to "поля addresses.json, опрос, ряды и оси графиков",
            "recorder" to "запись MCAP (ROS 1 std_msgs)",
            "firmware" to "Intel HEX, резервная копия и сверка Flash",
        )
    }
}

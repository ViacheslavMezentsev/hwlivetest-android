package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttImageBuilder
import androidx.compose.runtime.*
import io.github.viacheslavmezentsev.hwlivetest.firmware.ElfFile
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttElfAdvice
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttElfSymbols
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttReferenceLayout
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttDoctor
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ RTT 2.13: recording configuration; launch belongs to the chart. */
class RttDoctorActivity : ComponentActivity() {
    companion object {
        // ТЗ RTT 3.2: выбор ELF принадлежит сеансу, а не экземпляру Activity.
        internal var selectedElf by mutableStateOf<ElfFile?>(null)
        @Volatile private var symbols: RttElfSymbols.Symbols? = null
        private var memory by mutableStateOf("")
        private var busy by mutableStateOf(false)
        internal var sampleHz by mutableStateOf(100)
    }
    private var exportMessage by mutableStateOf("")
    private var timers by mutableStateOf("")
    private var observedAdapter: Any? = null
    private var timing by mutableStateOf("")
    private val saveTrial = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val report = RttTrial.lastReport
        if (uri != null && report != null) Session.worker.execute {
            val result = runCatching {
                val raw = java.io.File(report.parentFile, report.nameWithoutExtension + ".rtt.bin")
                contentResolver.openOutputStream(uri)!!.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        listOf(report, raw, java.io.File(report.parentFile, report.nameWithoutExtension + ".mcap")).filter { it.isFile }.forEach { file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                "Отчёт RTT сохранён"
            }.getOrElse { "Ошибка сохранения RTT: ${it.message}" }
            Session.programSaid(result)
            runOnUiThread { exportMessage = result }
        }
    }
    private val openElf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            symbols = null
            selectedElf = null
            timing = ""
            Session.userSaid("выбрал ELF для подготовки RTT")
            Session.worker.execute {
                val result = runCatching {
                    val bytes = contentResolver.openInputStream(uri)!!.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val block = ByteArray(8192)
                        while (true) {
                            val n = stream.read(block)
                            if (n < 0) break
                            require(out.size() + n <= 32 * 1024 * 1024) { "ELF больше 32 МиБ" }
                            out.write(block, 0, n)
                        }
                        out.toByteArray()
                    }
                    val elf = ElfFile.parse(bytes)
                    val a = RttElfAdvice.inspect(elf)
                    val names = RttElfSymbols.inspect(elf)
                    symbols = names
                    selectedElf = elf
                    val vector = listOf(".isr_vector", ".vectors", ".vector_table", ".intvec", ".vectors_flash")
                        .mapNotNull { elf.section(it) }.firstOrNull { it.address == 0x08000000L }
                    val layoutText = if (vector != null) runCatching {
                        // Six channels: conservative footprint for the first phone experiment.
                        val layout = RttReferenceLayout.calculate(a.base, 6, vector.size)
                        "\nЭталонный образ для 6 полей: ${layout.bytes} байт; запас: ${a.available - layout.bytes} байт. " +
                            "Это расчёт размещения, не готовый образ для загрузки."
                    }.getOrElse { "\nРазмер эталонного образа не определён: ${it.message}" }
                    else "\nНе найдена именованная таблица векторов; точный размер не определён."
                    "Конец данных: 0x%08X\nНачальный SP: 0x%08X\nКандидат SRAM: 0x%08X\nДо запаса стека: %d байт\n".format(a.dataEnd, a.initialStack, a.base, a.available) +
                        (if (a.available >= 6144) "Помещается ориентировочный образ 6 КиБ." else "Для ориентировочного образа 6 КиБ места недостаточно.") +
                        layoutText + "\nTick: " + (names.tick?.let { "%s @ 0x%08X".format(it.first, it.second) } ?: "не найден однозначный символ uwTick/platform_tick") +
                        "\nSystemCoreClock: " + (names.coreClock?.let { "0x%08X".format(it) } ?: "символ не найден")
                }.getOrElse { "Оценка недоступна: ${it.message}" }
                runOnUiThread { memory = result; busy = false }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HwTheme {
            val route by Session.route.collectAsState()
            val debug by Session.debugDetails.collectAsState()

            LaunchedEffect(route) {
                if (!route.ready || Session.stlink !== observedAdapter) { timers = ""; timing = "" }
            }
            HwScreen("Настройка RTT", onBack = ::finish,
                navigationEnabled = !busy) {
                SectionCard("Запись сигналов · $sampleHz Гц") {
                    Text("Выберите ELF текущей прошивки и частоту. Запись запускается кнопкой «Запись» на графике; каналы выбираются там же.")
                    if (!route.ready) Text("Сначала подключите плату на странице «Устройство».")
                    OutlinedButton(enabled = !busy, onClick = { openElf.launch(arrayOf("*/*")) }) {
                        Text(if (selectedElf == null) "Выбрать ELF прошивки" else "Заменить ELF прошивки")
                    }
                    if (selectedElf != null) Text("ELF выбран")
                    else if (memory.isNotEmpty()) Text(memory)
                    Row {
                        RttImageBuilder.sampleRates.forEach { rate ->
                            FilterChip(selected = sampleHz == rate, enabled = !busy,
                                onClick = {
                                    if (sampleHz != rate) {
                                        sampleHz = rate
                                        Session.userSaid("выбрал частоту записи RTT: $rate Гц")
                                    }
                                }, label = { Text("$rate Гц") })
                        }
                    }
                    Text("Запись идёт до нажатия «Остановить». При уходе с экрана запись остановится с восстановлением устройства.", style = MaterialTheme.typography.bodySmall)
                    if (sampleHz > 100) Text("Экспериментальная частота. После пробы проверьте пропуски и восстановление.", style = MaterialTheme.typography.bodySmall)
                    Text("Эксперимент · STM32F411", style = MaterialTheme.typography.bodySmall)
                }
                if (debug) {
                SectionCard("Предлагаемые параметры пробы") {
                    Text("Выборка: $sampleHz Гц · буфер: 4 КиБ")
                    Text("Чтение буфера телефоном: каждые 10 мс")
                    Text("Таймер: первый кандидат из TIM2–TIM5. SRAM: окно по выбранному ELF.", style = MaterialTheme.typography.bodySmall)
                    Text("Предполагаемые свободные ресурсы. Запись работает отдельно от обычного SWD-опроса.", style = MaterialTheme.typography.bodySmall)
                    Text("Успешная короткая проба не исключает более позднего использования памяти или таймера прошивкой.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Память по ELF") {
                    OutlinedButton(enabled = !busy, onClick = { openElf.launch(arrayOf("*/*")) }) { Text("Выбрать ELF прошивки") }
                    if (memory.isNotEmpty()) Text(memory)
                    Text("Оценка для 128 КиБ SRAM F411: запас стека 2 КиБ, буфер 4 КиБ и около 2 КиБ служебных данных. " +
                        "Динамическая куча, стеки задач и соответствие ELF прошивке устройства не проверены. Резерв нужно подтвердить в прошивке.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Таймеры устройства") {
                    OutlinedButton(enabled = !busy && route.ready, onClick = ::inspectTimers) { Text("Проверить TIM2–TIM5") }
                    if (!route.ready) Text("Сначала подключите устройство на странице «Устройство».")
                    if (timers.isNotEmpty()) Text(timers)
                    Text("Кандидат: тактирование и прерывания выключены. Прошивка может включить их позже; выбор требует проверки её конфигурации.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Частота и tick") {
                    OutlinedButton(enabled = !busy && route.ready && memory.isNotEmpty(), onClick = ::inspectTiming) { Text("Проверить частоту и tick") }
                    Text("Адреса из выбранного ELF. Соответствие прошивке не проверено. Частота берётся из SystemCoreClock с учётом APB1 и TIMPRE; переменная может быть устаревшей.", style = MaterialTheme.typography.bodySmall)
                    if (timing.isNotEmpty()) Text(timing)
                }
                }
                if (RttTrial.lastSummary.isNotEmpty()) SectionCard("Последняя запись") {
                    Text(RttTrial.lastSummary)
                    if (RttTrial.lastReport != null) OutlinedButton(onClick = { saveTrial.launch("rtt-recording.zip") }) {
                        Text("Сохранить запись и отчёт…")
                    }
                }
                if (exportMessage.isNotEmpty()) Text(exportMessage)
                if (busy) LinearProgressIndicator()
            }
        } }
    }
    private fun inspectTiming() {
        busy = true
        timing = ""
        val selected = symbols
        Session.userSaid("запросил проверку частоты и tick для RTT")
        Session.worker.execute {
            val adapter = Session.stlink
            val result = runCatching {
                val target = Session.target ?: error("Устройство отключено")
                require(target.vendor == McuVendor.STM32 && target.profileOverride == null && (target.idRegister ?: 0) and 0xFFF == 0x431) { "Нужен автоматически опознанный STM32F411" }
                val st = adapter ?: error("Адаптер отключён")
                val s = selected ?: error("Сначала выберите ELF с таблицей символов")
                val parts = mutableListOf<String>()
                s.coreClock?.let { address ->
                    val hclk = st.read32(address).toLong() and 0xFFFFFFFFL
                    val hz = RttDoctor.timerClockHz(hclk, st.read32(0x40023808), st.read32(0x4002388C))
                    parts += "HCLK по SystemCoreClock: $hclk Гц\nОценка частоты TIM2–TIM5: $hz Гц"
                } ?: parts.add("SystemCoreClock не найден; частота не определена.")
                s.tick?.let { (name, address) ->
                    val before = st.read32(address).toLong() and 0xFFFFFFFFL
                    val start = android.os.SystemClock.elapsedRealtime()
                    Thread.sleep(250)
                    val after = st.read32(address).toLong() and 0xFFFFFFFFL
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    val delta = (after - before) and 0xFFFFFFFFL
                    parts += "$name: $before → $after; Δ=$delta за $elapsed мс. " +
                        if (delta in 1..0x7FFFFFFFL) "Счётчик продвигается; единицы времени требуют подтверждения." else "Продвижение не подтверждено."
                } ?: parts.add("Tick не найден однозначно; проверка пропущена.")
                parts.joinToString("\n\n")
            }.getOrElse { "Проверка недоступна: ${it.message}" }
            Session.debugSaid(result)
            runOnUiThread {
                observedAdapter = adapter
                timing = if (Session.stlink === adapter && Session.route.value.ready) result else "Устройство отключилось; повторите проверку."
                busy = false
            }
        }
    }
    private fun inspectTimers() {
        busy = true
        timers = ""
        Session.userSaid("запросил проверку таймеров для RTT")
        Session.worker.execute {
            val adapter = Session.stlink
            val result = runCatching {
                val target = Session.target ?: error("Устройство отключено")
                require(target.vendor == McuVendor.STM32 && target.profileOverride == null) { "Нужен автоматически опознанный STM32F411" }
                val observations = RttDoctor.inspectF411(adapter ?: error("Адаптер отключён"), (target.idRegister ?: 0) and 0xFFF)
                val suggested = observations.firstOrNull { it.candidate }?.timer?.name
                (suggested?.let { "Предложение для пробы: $it" } ?: "Подходящих кандидатов нет") + "\n\n" +
                    observations.joinToString("\n\n") { o ->
                        "${o.timer.name}: ${if (o.candidate) "кандидат" else "занят или требует проверки"}\n" +
                            "Тактирование: ${o.clockEnabled}; CEN: ${o.cr1 and 1}; DIER: 0x${o.dier.toUInt().toString(16)}\n" +
                            "IRQ: enabled=${o.enabled}, pending=${o.pending}, active=${o.active}"
                    }
            }.getOrElse { "Проверка недоступна: ${it.message}" }
            Session.debugSaid(result)
            runOnUiThread {
                observedAdapter = adapter
                timers = if (Session.stlink === adapter && Session.route.value.ready) result else "Устройство отключилось; повторите проверку."
                busy = false
            }
        }
    }
}

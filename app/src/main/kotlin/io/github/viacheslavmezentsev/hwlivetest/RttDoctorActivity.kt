package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.viacheslavmezentsev.hwlivetest.firmware.ElfFile
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttElfAdvice
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttElfSymbols
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttReferenceLayout
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttDoctor
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ RTT 2.3: explicit read-only inspection, no install action. */
class RttDoctorActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var memory by mutableStateOf("")
    private var timers by mutableStateOf("")
    private var observedAdapter: Any? = null
    @Volatile private var symbols: RttElfSymbols.Symbols? = null
    private var timing by mutableStateOf("")
    private val openElf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            symbols = null
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
            LaunchedEffect(route) {
                if (!route.ready || Session.stlink !== observedAdapter) { timers = ""; timing = "" }
            }
            HwScreen("Подготовка RTT", onBack = ::finish) {
                SectionCard("Эксперимент · STM32F411") {
                    Text("Проверка только читает сведения. Заглушка не загружается, ядро не останавливается.")
                    Text("Результат — снимок состояния и рекомендации, а не разрешение запуска RTT.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Предлагаемые параметры пробы") {
                    Text("Выборка: 100 Гц · буфер: 4 КиБ")
                    Text("Чтение буфера телефоном: каждые 10 мс")
                    Text("Таймер: первый кандидат из TIM2–TIM5. SRAM: окно по выбранному ELF.", style = MaterialTheme.typography.bodySmall)
                    Text("Предполагаемые свободные ресурсы. Проба ещё недоступна; параметры не меняют текущий SWD-опрос.", style = MaterialTheme.typography.bodySmall)
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
            Session.programSaid("RTT: $result")
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
            Session.programSaid("RTT: $result")
            runOnUiThread {
                observedAdapter = adapter
                timers = if (Session.stlink === adapter && Session.route.value.ready) result else "Устройство отключилось; повторите проверку."
                busy = false
            }
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.viacheslavmezentsev.hwlivetest.firmware.ElfFile
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttElfAdvice
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.RttDoctor
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ RTT 2.3: explicit read-only inspection, no install action. */
class RttDoctorActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var memory by mutableStateOf("")
    private var timers by mutableStateOf("")
    private var observedAdapter: Any? = null
    private val openElf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
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
                    val a = RttElfAdvice.inspect(ElfFile.parse(bytes))
                    "Конец данных: 0x%08X\nНачальный SP: 0x%08X\nКандидат SRAM: 0x%08X\nДо запаса стека: %d байт\n".format(a.dataEnd, a.initialStack, a.base, a.available) +
                        if (a.available >= 6144) "Помещается ориентировочный образ 6 КиБ." else "Для ориентировочного образа 6 КиБ места недостаточно."
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
                if (!route.ready || Session.stlink !== observedAdapter) timers = ""
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
                if (busy) LinearProgressIndicator()
            }
        } }
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

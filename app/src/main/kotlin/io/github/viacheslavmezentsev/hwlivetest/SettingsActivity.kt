package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ прошивки 5.18: настройки отображения не предоставляют доступ к записи. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HwTheme {
            val debug by Session.debugDetails.collectAsState()
            val fast by Session.fastActions.collectAsState()
            val route by Session.route.collectAsState()
            val access by Session.writeAccess.collectAsState()
            var confirmFast by rememberSaveable { mutableStateOf(false) }
            var pollingHz by rememberSaveable { mutableStateOf(Prefs.pollingHz(this@SettingsActivity)) }
            var rtt by rememberSaveable { mutableStateOf(Prefs.recordRtt(this@SettingsActivity)) }
            var backup by rememberSaveable { mutableStateOf(Prefs.flashBackup(this@SettingsActivity)) }
            var useSha by rememberSaveable { mutableStateOf(Prefs.useSha(this@SettingsActivity)) }
            HwScreen("Настройки", onBack = ::finish) {
                SectionCard("Отображение") {
                    SettingSwitch("Отладочные сведения", debug) {
                        Prefs.setDebugDetails(this@SettingsActivity, it); Session.debugDetails.value = it
                    }
                    Text("Адреса, регистры, символы ELF, чтение памяти по адресу и подробности обмена.",
                        style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Опрос устройства") {
                    // ТЗ RTT 3.1: подготовка RTT не переключает действующий SWD-опрос.
                    Text("Прямое чтение SWD · используется", style = MaterialTheme.typography.titleMedium)
                    Text("Телефон читает выбранные поля памяти. Загрузка кода и остановка ядра не требуются.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Частота чтения: $pollingHz Гц")
                    Slider(value = pollingHz.toFloat(), onValueChange = { pollingHz = it.toInt() },
                        valueRange = PollingRate.MIN_HZ.toFloat()..PollingRate.MAX_HZ.toFloat(), steps = 18,
                        onValueChangeFinished = {
                            Prefs.setPollingHz(this@SettingsActivity, pollingHz)
                            Session.userSaid("установил частоту SWD-опроса: $pollingHz Гц")
                        })
                    Text("От 1 до 20 Гц. Общая настройка для сигналов, графиков и страницы модуля. " +
                        "Фактическая частота зависит от числа полей и скорости соединения; медленные опросы не догоняются.",
                        style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Запись") {
                    listOf(false to "Прямое чтение SWD", true to "RTT с таймером · F411").forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = rtt == value, onClick = {
                                rtt = value; Prefs.setRecordRtt(this@SettingsActivity, value)
                                Session.userSaid("выбрал метод записи: $label")
                            })
                            TextButton(onClick = { rtt = value; Prefs.setRecordRtt(this@SettingsActivity, value) }) { Text(label) }
                        }
                    }
                    Text(if (rtt) "Кнопка «Запись» на графике запускает RTT для выбранных каналов. Нужны ELF текущей прошивки и включённый доступ."
                        else "Записываются результаты прямого чтения SWD с частотой опроса выше.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        startActivity(android.content.Intent(this@SettingsActivity, RttDoctorActivity::class.java))
                    }) { Text("Настройка RTT") }
                    Text("Формат: MCAP (ROS 1). Запись идёт до нажатия «Остановить». Экран остаётся включённым. Пауза графика не останавливает запись.", style = MaterialTheme.typography.bodySmall)
                    if (rtt) Text("RTT временно использует SRAM и таймер платы. При остановке восстанавливается состояние устройства, затем формируется MCAP. При уходе с графика запись останавливается. Не отключайте USB до завершения.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Действия с устройством") {
                    SettingSwitch("Быстрые действия", fast, route.ready && access) {
                        if (it) confirmFast = true else Session.setFastActions(false)
                    }
                    Text("Остановка, запуск и сброс ядра без повторного вопроса. Действует до отключения адаптера, выключения управления устройством или завершения приложения.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!access) Text("Включите «Доступ» в шапке экрана.")
                    Text("Прошивка, стирание и импульс NRST сохраняют подтверждения.", style = MaterialTheme.typography.bodySmall)
                    SettingSwitch("Использовать SHA-256", useSha) {
                        useSha = it; Prefs.setUseSha(this@SettingsActivity, it)
                    }
                    Text("Показывать хеши и проверять файл по введённой контрольной сумме. Сверка записанной прошивки выполняется всегда.", style = MaterialTheme.typography.bodySmall)
                    SettingSwitch("Резервная копия перед прошивкой", backup) {
                        backup = it; Prefs.setFlashBackup(this@SettingsActivity, it)
                    }
                }
            }
            if (confirmFast) AlertDialog(onDismissRequest = { confirmFast = false },
                title = { Text("Включить быстрые действия?") },
                text = { Text("Кнопки остановки, запуска и сброса будут сразу менять состояние подключённой платы. Используйте этот режим, только если готовы к немедленному выполнению этих команд.") },
                confirmButton = { TextButton(onClick = { Session.setFastActions(true); confirmFast = false }) { Text("Включить") } },
                dismissButton = { TextButton(onClick = { confirmFast = false }) { Text("Отмена") } })
        } }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

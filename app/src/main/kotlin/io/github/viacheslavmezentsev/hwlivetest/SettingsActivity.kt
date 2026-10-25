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
            var rttHz by rememberSaveable { mutableStateOf(Prefs.rttHz(this@SettingsActivity)) }
            val rttReason = if (!route.ready) "Подключите устройство для выбора RTT" else runCatching { RttTrial.profile() }.exceptionOrNull()?.message
            var rtt by rememberSaveable { mutableStateOf(Prefs.recordRtt(this@SettingsActivity)) }
            val useRtt = rtt && rttReason == null
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
                SectionCard("Запись") {
                    listOf(false to "Прямое чтение SWD", true to "RTT с таймером · STM32F1/F4").forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = useRtt == value, enabled = !value || rttReason == null, onClick = {
                                rtt = value; Prefs.setRecordRtt(this@SettingsActivity, value)
                                Session.userSaid("выбрал метод записи: $label")
                            })
                            TextButton(enabled = !value || rttReason == null, onClick = { rtt = value; Prefs.setRecordRtt(this@SettingsActivity, value); Session.userSaid("выбрал метод записи: $label") }) { Text(label) }
                        }
                    }
                    if (rttReason != null) Text("RTT недоступен: $rttReason. Используется SWD.", style = MaterialTheme.typography.bodySmall)
                    val hz = if (useRtt) rttHz else pollingHz
                    Text("${if (useRtt) "RTT" else "SWD"} · $hz Гц")
                    RecordingRateSlider(useRtt, hz, onChange = { if (useRtt) rttHz = it else pollingHz = it }, onFinished = {
                        if (useRtt) Prefs.setRttHz(this@SettingsActivity, rttHz) else Prefs.setPollingHz(this@SettingsActivity, pollingHz)
                        Session.userSaid("установил частоту ${if (useRtt) "RTT" else "SWD"}: ${if (useRtt) rttHz else pollingHz} Гц")
                    })
                    Text(if (useRtt) "Выборка на устройстве: 100, 500 или 1000 Гц. Нужны ELF прошивки и включённый доступ. До начала записи график обновляется через SWD."
                        else "Чтение и запись: от 1 до 20 Гц. Фактическая частота зависит от скорости соединения и числа каналов.", style = MaterialTheme.typography.bodySmall)
                    if (useRtt) OutlinedButton(onClick = {
                        startActivity(android.content.Intent(this@SettingsActivity, RttDoctorActivity::class.java))
                    }) { Text("Подготовка RTT · ELF") }
                    Text("Формат: MCAP (ROS 1). Запись идёт до нажатия «Остановить». Экран остаётся включённым. Пауза графика не останавливает запись.", style = MaterialTheme.typography.bodySmall)
                    if (useRtt) Text("RTT временно использует SRAM и таймер платы. При остановке восстанавливается состояние устройства, затем формируется MCAP. При уходе с графика запись останавливается. Не отключайте USB до завершения.", style = MaterialTheme.typography.bodySmall)
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

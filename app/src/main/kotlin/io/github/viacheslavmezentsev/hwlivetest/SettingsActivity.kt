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
            var confirmFast by rememberSaveable { mutableStateOf(false) }
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
                    Text("Формат записи: MCAP (ROS 1)")
                    Text("Экран остаётся включённым при просмотре графиков и во время записи. Пауза графика не останавливает запись.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Действия с устройством") {
                    SettingSwitch("Быстрые действия", fast, route.ready && Session.writeMode) {
                        if (it) confirmFast = true else Session.setFastActions(false)
                    }
                    Text("Остановка, запуск и сброс ядра без повторного вопроса. Действует до отключения адаптера, выключения управления устройством или завершения приложения.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!Session.writeMode) Text("Сначала включите «Доступ» в разделе «Управление» на странице «Устройство».")
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

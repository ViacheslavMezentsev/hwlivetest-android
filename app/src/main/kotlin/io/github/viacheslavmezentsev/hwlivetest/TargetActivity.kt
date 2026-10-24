package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.stlink.CoreState
import io.github.viacheslavmezentsev.hwlivetest.stlink.CortexDebugRegs
import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.ui.HwScreen
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import io.github.viacheslavmezentsev.hwlivetest.ui.InfoCard
import io.github.viacheslavmezentsev.hwlivetest.ui.LocalStatusColors
import io.github.viacheslavmezentsev.hwlivetest.ui.MonoText

/** Подтверждение действия, меняющего цель (ТЗ прошивки, п. 3.1.2). */
data class Confirm(val title: String, val text: String, val action: () -> Unit)

/** Состояние экрана «Цель». Меняется только в главном потоке. */
class TargetUi {
    var status by mutableStateOf("")
    var error by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var adapter by mutableStateOf("")
    var target by mutableStateOf("")
    var core by mutableStateOf<CoreState?>(null)
    var writeMode by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var log by mutableStateOf("")
    var confirm by mutableStateOf<Confirm?>(null)
    var askLeave by mutableStateOf(false)
}

/**
 * Экран «Цель» (docs/roadmap.md, этап 3): подключение, состояние ядра по DHCSR и управление ядром
 * в режиме «Изменение цели» (ТЗ ST-Link 2.0, п. 5.11).
 */
class TargetActivity : ComponentActivity() {
    private val ui = TargetUi()
    private lateinit var connector: Connector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connector = Connector(this) { text, tone ->
            ui.status = text
            ui.error = tone == Connector.Tone.ERROR
        }
        Session.stopPolling()
        sync()
        if (Session.stlink != null) refresh()
        setContent {
            HwTheme {
                TargetScreen(
                    ui = ui,
                    onBack = { leave() },
                    onConnect = { connect() },
                    onRefresh = { refresh() },
                    onWriteMode = { on -> setWriteMode(on) },
                    onAction = { name, op -> confirmChange(name, op) },
                    onLeave = { run ->
                        ui.askLeave = false
                        if (run) change("запуск перед уходом", { it.detach() }) { finish() } else finish()
                    },
                )
            }
        }
    }

    private fun sync() {
        ui.connected = Session.stlink != null
        ui.adapter = Session.adapterText
        ui.target = Session.targetText
        ui.writeMode = Session.writeMode
        ui.log = Session.log.tail(12)
    }

    private fun connect() {
        ui.busy = true
        connector.connect { info ->
            ui.busy = false
            sync()
            if (info != null) refresh()
        }
    }

    /** Чтение DHCSR — только чтение, разрешение не нужно. */
    private fun refresh() {
        val st = Session.stlink ?: return
        Session.worker.execute {
            val state = try {
                CoreState(st.read32(CortexDebugRegs.DHCSR))
            } catch (e: Exception) {
                runOnUiThread {
                    ui.status = ErrorMessages.describe(e)
                    ui.error = true
                }
                null
            }
            runOnUiThread {
                if (state != null) ui.core = state
                sync()
            }
        }
    }

    private fun setWriteMode(on: Boolean) {
        if (on) Session.enableWriteMode() else Session.disableWriteMode()
        sync()
    }

    private fun confirmChange(name: String, op: (TargetWrite) -> CoreState?) {
        val mcu = Session.target?.let { t -> listOfNotNull(t.shortName, t.flashSizeKb?.let { "Flash $it КБ" }).joinToString(", ") } ?: "не опознан"
        ui.confirm = Confirm(
            title = "$name?",
            text = "MCU: $mcu.\nАдаптер: ${Session.device?.title ?: "?"}.\nДействие меняет состояние цели.",
            action = { change(name, op) },
        )
    }

    /** Действие, меняющее цель, в потоке ST-Link; результат — в журнал (ТЗ ST-Link 2.0, п. 5.11.9). */
    private fun change(name: String, op: (TargetWrite) -> CoreState?, after: () -> Unit = {}) {
        ui.busy = true
        Session.worker.execute {
            try {
                val tw = Session.targetWrite()
                Session.logLine("# изменение цели: $name…")
                val state = op(tw) ?: tw.coreState()
                Session.logLine("# изменение цели: $name → ${state.title}")
                runOnUiThread {
                    ui.core = state
                    ui.status = "$name: ${state.title}."
                    ui.error = false
                }
            } catch (e: Exception) {
                Session.logLine("# изменение цели: $name — ошибка: ${e.message}")
                runOnUiThread {
                    ui.status = ErrorMessages.describe(e)
                    ui.error = true
                }
            } finally {
                runOnUiThread {
                    ui.busy = false
                    sync()
                    after()
                }
            }
        }
    }

    /** ТЗ ST-Link 2.0, п. 5.11.8: уходя с остановленным ядром — спросить, запустить ли его. */
    private fun leave() {
        if (ui.core?.halted == true && Session.writeMode && Session.stlink != null) ui.askLeave = true else finish()
    }
}

@Composable
fun TargetScreen(
    ui: TargetUi,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onWriteMode: (Boolean) -> Unit,
    onAction: (String, (TargetWrite) -> CoreState?) -> Unit,
    onLeave: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    val status = LocalStatusColors.current
    HwScreen(title = "Цель", onBack = onBack) {
        if (ui.status.isNotEmpty()) {
            Text(ui.status, color = if (ui.error) status.bad else MaterialTheme.colorScheme.onBackground)
        }
        if (!ui.connected) {
            Button(onClick = onConnect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Подключить и опознать") }
        } else {
            InfoCard("Адаптер", ui.adapter.lines().filter { it.isNotBlank() }, mono = true)
            InfoCard("Микроконтроллер", ui.target.lines().filter { it.isNotBlank() }, mono = true)
        }

        val core = ui.core
        val coreColor: Color = when {
            core == null -> status.muted
            core.lockup -> status.bad
            core.halted -> status.warn
            else -> status.ok
        }
        Section("Ядро") {
            Text(core?.title ?: "не прочитано", color = coreColor, style = MaterialTheme.typography.titleMedium)
            if (core != null) Text("DHCSR ${core.dhcsr.hex32()}", style = MonoText, color = status.muted)
            OutlinedButton(onClick = onRefresh, enabled = ui.connected && !ui.busy) { Text("Обновить") }
        }

        Section("Изменение цели") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.writeMode, onCheckedChange = onWriteMode)
                Text("Режим «Изменение цели»", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Выключен при каждом запуске приложения. Каждое действие подтверждается. " +
                    "Option bytes и защита Flash не меняются.",
                style = MaterialTheme.typography.bodySmall,
                color = status.muted,
            )
            val enabled = ui.connected && ui.writeMode && !ui.busy
            ActionRow(
                "Остановить" to { onAction("Остановить ядро") { it.halt() } },
                "Запустить" to { onAction("Запустить ядро") { it.go() } },
                enabled = enabled,
            )
            ActionRow(
                "Сброс" to { onAction("Сброс с запуском") { it.reset() } },
                "Сброс и стоп" to { onAction("Сброс с остановкой") { it.resetHalt() } },
                enabled = enabled,
            )
            ActionRow(
                "Импульс NRST" to { onAction("Импульс NRST (вывод NRST адаптера подключён к плате)") { it.pulseNrst(); null } },
                enabled = enabled,
            )
        }

        InfoCard("Журнал", ui.log.lines().takeLast(12), mono = true)
    }

    ui.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { ui.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton(onClick = {
                    ui.confirm = null
                    c.action()
                }) { Text("Выполнить") }
            },
            dismissButton = { TextButton(onClick = { ui.confirm = null }) { Text("Отмена") } },
        )
    }
    if (ui.askLeave) {
        AlertDialog(
            onDismissRequest = { ui.askLeave = false },
            title = { Text("Ядро остановлено") },
            text = { Text("Запустить ядро перед уходом с экрана? По умолчанию — запустить.") },
            confirmButton = { TextButton(onClick = { onLeave(true) }) { Text("Запустить") } },
            dismissButton = { TextButton(onClick = { onLeave(false) }) { Text("Оставить остановленным") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ActionRow(vararg actions: Pair<String, () -> Unit>, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for ((label, onClick) in actions) {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
        }
    }
}

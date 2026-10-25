package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuProfile
import io.github.viacheslavmezentsev.hwlivetest.stlink.CoreState
import io.github.viacheslavmezentsev.hwlivetest.stlink.CortexDebugRegs
import io.github.viacheslavmezentsev.hwlivetest.stlink.TargetWrite
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.ui.ButtonRow
import io.github.viacheslavmezentsev.hwlivetest.ui.HwScreen
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import io.github.viacheslavmezentsev.hwlivetest.ui.InfoCard
import io.github.viacheslavmezentsev.hwlivetest.ui.LocalStatusColors
import io.github.viacheslavmezentsev.hwlivetest.ui.MonoText
import io.github.viacheslavmezentsev.hwlivetest.ui.SectionCard

/** Подтверждение действия, меняющего устройство (ТЗ прошивки, п. 3.1.2). */
data class Confirm(val title: String, val text: String, val action: () -> Unit)

/** Состояние экрана «Устройство». Меняется только в главном потоке. */
class TargetUi : androidx.lifecycle.ViewModel() {
    var nextPage: WorkspacePage? = null
    var nextDirection: Int = 0
    var status by mutableStateOf("")
    var error by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var adapter by mutableStateOf("")
    var target by mutableStateOf("")
    var profileLabel by mutableStateOf("")
    var chooseProfile by mutableStateOf(false)
    var core by mutableStateOf<CoreState?>(null)
    var writeMode by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var confirm by mutableStateOf<Confirm?>(null)
    var askLeave by mutableStateOf(false)
}

/**
 * Экран «Устройство» (docs/roadmap.md, этап 3): подключение, состояние ядра по DHCSR и управление ядром
 * в режиме «Управление» (ТЗ ST-Link 2.0, п. 5.11).
 */
class TargetActivity : ComponentActivity(), WorkspaceHost {
    override val workspacePage = WorkspacePage.TARGET
    override fun navigateWorkspace(page: WorkspacePage, direction: Int) { if (page != workspacePage) leave(page, direction) }
    private val ui by lazy { androidx.lifecycle.ViewModelProvider(this)[TargetUi::class.java] }
    private val sessionChanged: () -> Unit = { sync() }
    override fun onResume() {
        super.onResume()
        Session.addListener(sessionChanged)
        sync()
        if (!ui.busy) refresh()
    }
    override fun onPause() { Session.removeListener(sessionChanged); super.onPause() }
    private lateinit var connector: Connector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connector = Connector(this) { text, tone ->
            ui.status = text
            ui.error = tone == Connector.Tone.ERROR
        }
        Session.stopPolling()
        sync()
        setContent {
            HwTheme {
                TargetScreen(
                    ui = ui,
                    onBack = { leave() },
                    onConnect = { connect() },
                    onRefresh = { refresh() },
                    onProfile = { profile ->
                        val connection = Session.stlink
                        ui.busy = true
                        Session.worker.execute {
                            if (connection != null && connection === Session.stlink) Session.applyProfile(profile)
                            runOnUiThread { ui.busy = false; sync() }
                        }
                    },
                    onWriteMode = { on -> setWriteMode(on) },
                    onAction = { name, op -> confirmChange(name, op) },
                    onLeave = { run ->
                        ui.askLeave = false
                        if (run) change("запуск перед уходом", { it.detach() }) { completeLeave() } else completeLeave()
                    },
                )
            }
        }
    }

    private fun sync() {
        ui.connected = Session.route.value.ready
        ui.core = Session.core
        ui.adapter = Session.adapterText
        ui.target = Session.targetText
        ui.writeMode = Session.writeMode
        ui.profileLabel = Session.target?.let { it.profile.title + if (it.profileOverride != null) " (вручную)" else " (автоматически)" } ?: "не определён"
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
                if (st === Session.stlink) Session.updateCore(state)
                sync()
            }
        }
    }

    private fun setWriteMode(on: Boolean) {
        if (on) Session.enableWriteMode() else Session.disableWriteMode()
        sync()
    }

    private fun confirmChange(name: String, op: (TargetWrite) -> CoreState?) {
        if (!ActionConfirmations.required(name, Session.fastActions.value, Session.writeMode)) {
            Session.userSaid("быстрое действие: $name")
            change(name, op)
            return
        }
        val mcu = Session.target?.let { t -> listOfNotNull(t.shortName, t.flashSizeKb?.let { "Flash $it КБ" }).joinToString(", ") } ?: "не опознан"
        ui.confirm = Confirm(
            title = "$name?",
            text = "MCU: $mcu.\nАдаптер: ${Session.device?.title ?: "?"}.\nДействие меняет состояние устройства.",
            action = {
                Session.userSaid("подтвердил: $name")
                change(name, op)
            },
        )
    }

    /** Действие, меняющее устройство, в потоке ST-Link; результат — в журнал (ТЗ ST-Link 2.0, п. 5.11.9). */
    private fun change(name: String, op: (TargetWrite) -> CoreState?, after: () -> Unit = {}) {
        ui.busy = true
        Session.worker.execute {
            var succeeded = false
            try {
                val tw = Session.targetWrite()
                Session.logLine("# управление устройством: $name…")
                Session.clearTelemetry(name)
                val state = op(tw) ?: tw.coreState()
                succeeded = true
                Session.logLine("# управление устройством: $name → ${state.title}")
                Session.programSaid("$name: ядро — ${state.title}")
                runOnUiThread {
                    Session.updateCore(state)
                    ui.status = "$name: ${state.title}."
                    ui.error = false
                }
            } catch (e: Exception) {
                Session.logLine("# управление устройством: $name — ошибка: ${e.message}")
                Session.programSaid("$name не выполнено: ${ErrorMessages.describe(e)}")
                runOnUiThread {
                    ui.status = ErrorMessages.describe(e)
                    ui.error = true
                }
            } finally {
                runOnUiThread {
                    ui.busy = false
                    sync()
                    if (succeeded) after()
                }
            }
        }
    }

    /** ТЗ ST-Link 2.0, п. 5.11.8: уходя с остановленным ядром — спросить, запустить ли его. */
    private fun leave(page: WorkspacePage? = null, direction: Int = 0) {
        if (ui.busy) return
        ui.nextPage = page
        ui.nextDirection = direction
        if (Session.core?.halted == true && Session.writeMode && Session.stlink != null) ui.askLeave = true else completeLeave()
    }
    private fun completeLeave() {
        val page = ui.nextPage
        ui.nextPage = null
        if (page == null) WorkspaceNavigation.home(this) else WorkspaceNavigation.open(this, page, ui.nextDirection)
    }
}

@Composable
fun TargetScreen(
    ui: TargetUi,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onProfile: (McuProfile?) -> Unit,
    onWriteMode: (Boolean) -> Unit,
    onAction: (String, (TargetWrite) -> CoreState?) -> Unit,
    onLeave: (Boolean) -> Unit,
) {
    if (ui.chooseProfile) AlertDialog(onDismissRequest = { ui.chooseProfile = false },
        title = { Text("Профиль MCU") },
        text = { androidx.compose.foundation.layout.Column {
            Text("Выбирайте совместимый профиль только по документации устройства: он читает регистры выбранного семейства. При сомнении используйте общий Cortex-M.")
            TextButton(onClick = { ui.chooseProfile = false; onProfile(null) }) { Text("Автоматический") }
            McuProfile.selectable.forEach { profile ->
                TextButton(onClick = { ui.chooseProfile = false; onProfile(profile) }) { Text(profile.title) }
            }
        } },
        confirmButton = { TextButton(onClick = { ui.chooseProfile = false }) { Text("Отмена") } })
    val context = androidx.compose.ui.platform.LocalContext.current
    val debug by Session.debugDetails.collectAsState()
    val status = LocalStatusColors.current
    HwScreen(title = "Устройство", onBack = onBack, navigationEnabled = !ui.busy) {
        if (ui.status.isNotEmpty()) {
            Text(ui.status, color = if (ui.error) status.bad else MaterialTheme.colorScheme.onBackground)
        }
        if (!ui.connected) {
            Button(onClick = onConnect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Подключить") }
        } else {
            if (debug) InfoCard("Адаптер", ui.adapter.lines().filter { it.isNotBlank() }, mono = true, collapsible = true)
            if (debug) InfoCard("Микроконтроллер", ui.target.lines().filter { it.isNotBlank() }, mono = true, collapsible = true)
            else Text(Session.route.value.mcu, style = MaterialTheme.typography.titleMedium)
        }

        val core = ui.core
        val coreColor: Color = when {
            core == null -> status.muted
            core.lockup -> status.bad
            core.halted -> status.warn
            else -> status.ok
        }
        OutlinedButton(onClick = { (context as android.app.Activity).startActivity(android.content.Intent(context, ModuleStatusActivity::class.java)) }, enabled = !ui.busy) { Text("Модуль ST-Link · состояние и трафик") }

        SectionCard("Ядро") {
            Text(core?.title ?: "не прочитано", color = coreColor, style = MaterialTheme.typography.titleMedium)
            if (debug && core != null) Text("DHCSR ${core.dhcsr.hex32()}", style = MonoText, color = status.muted)
            OutlinedButton(onClick = onRefresh, enabled = ui.connected && !ui.busy) { Text("Обновить") }
        }

        SectionCard("Профиль MCU") {
            Text(ui.profileLabel)
            OutlinedButton(onClick = { ui.chooseProfile = true }, enabled = ui.connected && !ui.busy) { Text("Выбрать профиль…") }
            Text("Ручной профиль задаёт проверки чтением, но не разрешает прошивку неизвестного MCU. Сбрасывается при отключении.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Управление") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Доступ", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = ui.writeMode, enabled = !ui.busy, onCheckedChange = onWriteMode)
            }
            Text(
                "Выключен при каждом запуске приложения. Подтверждения команд ядра настраиваются в «Быстрых действиях». " +
                    "Option bytes и защита Flash не меняются.",
                style = MaterialTheme.typography.bodySmall,
                color = status.muted,
            )
            if (!ui.connected) Text("Подключите устройство для управления.", style = MaterialTheme.typography.bodySmall)
            else if (!ui.writeMode) Text("Включите «Доступ» выше для команд ядра.", style = MaterialTheme.typography.bodySmall)
            val enabled = ui.connected && ui.writeMode && !ui.busy
            ButtonRow(
                "Остановить" to { onAction("Остановить ядро") { it.halt() } },
                "Запустить" to { onAction("Запустить ядро") { it.go() } },
                enabled = enabled,
            )
            ButtonRow(
                "Сброс" to { onAction("Сброс с запуском") { it.reset() } },
                "Сброс и стоп" to { onAction("Сброс с остановкой") { it.resetHalt() } },
                enabled = enabled,
            )
            ButtonRow(
                "Импульс NRST" to { onAction("Импульс NRST (вывод NRST адаптера подключён к плате)") { it.pulseNrst(); null } },
                enabled = enabled,
            )
        }

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

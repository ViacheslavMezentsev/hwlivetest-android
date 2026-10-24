package io.github.viacheslavmezentsev.hwlivetest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.diag.Level
import io.github.viacheslavmezentsev.hwlivetest.diag.Section
import io.github.viacheslavmezentsev.hwlivetest.ui.*

@Composable
fun SimpleScreen(ui: SimpleUi, onStart: () -> Unit, onExpert: () -> Unit, onFlash: () -> Unit,
                 onCopy: () -> Unit) {
    val colors = LocalStatusColors.current
    BackHandler(enabled = ui.running) { onStart() }
    HwScreen("HW Live Test", actions = {
        AppMenu(actions = listOf(
            MenuAction("Режим эксперта", !ui.running, onExpert),
            MenuAction("Копировать отчёт", run = onCopy),
        ), navigationEnabled = !ui.running)
    }) {
        Text(ui.status, color = when (ui.tone) {
            Connector.Tone.ERROR -> colors.bad
            Connector.Tone.OK -> colors.ok
            else -> MaterialTheme.colorScheme.onSurface
        })
        val report = ui.report
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.running) "Остановить проверку" else if (report == null) "Проверить плату" else "Повторить проверку")
        }
        if (ui.running) LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
        if (ui.step.isNotBlank()) Text(ui.step)
        OutlinedButton(onClick = onFlash, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
            Text("Прошивка: резервная копия и сверка")
        }
        OutlinedButton(onClick = onExpert, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) { Text("Сигналы · режим эксперта") }
        report?.let {
            Text(it.summary, style = MaterialTheme.typography.titleMedium)
            if (ui.running) Text("Предыдущий отчёт; проверка выполняется.", color = colors.muted)
            it.sections.forEach { section -> key(section.title) { DiagnosticCard(section) } }
        }
    }
}

@Composable
private fun DiagnosticCard(section: Section) {
    val debug by Session.debugDetails.collectAsState()
    var expanded by rememberSaveable(section.level) { mutableStateOf(section.level == Level.BAD || section.level == Level.WARN) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${section.level.mark} ${section.title}", style = MaterialTheme.typography.bodyMedium, color = levelColor(section.level))
            if (expanded) Text(section.headline, style = MaterialTheme.typography.bodySmall)
            if (expanded) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.findings.forEach { finding ->
                        Text("${finding.level.mark} ${finding.title}", color = levelColor(finding.level))
                        if ((debug || finding.level == Level.BAD || finding.level == Level.WARN) && finding.detail.isNotBlank())
                            Text(finding.detail, style = if (debug) MonoText else MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun levelColor(level: Level): Color = with(LocalStatusColors.current) {
    when (level) { Level.BAD -> bad; Level.WARN -> warn; Level.OK -> ok; Level.INFO, Level.SKIP -> muted }
}

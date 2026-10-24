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
        val buttonColor = if (ui.running || report == null) MaterialTheme.colorScheme.primary else levelColor(report.level)
        Button(onClick = onStart, shape = CircleShape, modifier = Modifier.size(220.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = Color.White)) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ui.running) "СТОП" else if (report == null) "ПУСК" else "ЕЩЁ РАЗ", style = MaterialTheme.typography.headlineLarge)
                Text(if (ui.running) "${(ui.progress * 100).toInt()} %" else when (report?.level) {
                    Level.BAD -> "есть проблемы"
                    Level.WARN -> "есть замечания"
                    null -> "проверить плату"
                    else -> "всё в порядке"
                })
            }
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${section.level.mark} ${section.title}", style = MaterialTheme.typography.titleMedium, color = levelColor(section.level))
            Text(section.headline)
            Text(if (expanded) "Скрыть подробности ▴" else "Подробности ▾", style = MaterialTheme.typography.labelMedium)
            if (expanded) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.findings.forEach { finding ->
                        Text("${finding.level.mark} ${finding.title}", color = levelColor(finding.level))
                        if (finding.detail.isNotBlank()) Text(finding.detail, style = MonoText)
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

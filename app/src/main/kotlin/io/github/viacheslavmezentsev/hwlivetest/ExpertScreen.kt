package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldCatalog
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ прошивки 5.9.7–5.9.8: список сигналов и журнал-диалог, этап 5.1. */
@Composable
fun ExpertScreen(
    ui: ExpertUi,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onRead: (String) -> Unit,
    onOpen: () -> Unit,
    onChart: (Field) -> Unit,
    onNavigate: (Class<out Activity>) -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onLogPolling: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    var address by rememberSaveable { mutableStateOf("E000ED00") }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var groupingName by rememberSaveable { mutableStateOf(FieldCatalog.Grouping.SOURCE.name) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val grouping = FieldCatalog.Grouping.valueOf(groupingName)
    val types = remember(ui.fields) { ui.fields.map { it.typeLabel }.distinct().sorted() }
    // Новый ELF/JSON может не содержать ранее выбранный тип.
    val activeType = type.takeIf { it in types } ?: ""
    val filtered = remember(ui.fields, query, activeType) { FieldCatalog.filter(ui.fields, query, activeType) }
    val groups = remember(filtered, grouping) { FieldCatalog.group(filtered, grouping) }
    val colors = LocalStatusColors.current

    HwScreen("Режим эксперта", onBack = onBack, scrolling = false) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "navigation") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showLog = true }) { Text("Журнал") }
                    Box {
                        TextButton(onClick = { menu = true }, enabled = !ui.busy) { Text("Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for ((label, screen) in listOf("Цель" to TargetActivity::class.java,
                                "Прошивка" to FlashActivity::class.java, "О программе" to AboutActivity::class.java)) {
                                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onNavigate(screen) })
                            }
                            DropdownMenuItem(text = { Text("Простой режим") }, onClick = { menu = false; onBack() })
                        }
                    }
                }
            }
            item(key = "connection") {
                SectionCard("Подключение") {
                    Text(ui.status.ifEmpty { if (ui.connected) "Подключено" else "Подключите ST-Link через OTG" },
                        color = when (ui.tone) {
                            Connector.Tone.ERROR -> colors.bad
                            Connector.Tone.OK -> colors.ok
                            else -> MaterialTheme.colorScheme.onSurface
                        })
                    ButtonRow("Найти" to onScan, "Подключить и опознать" to onConnect, enabled = !ui.busy)
                    if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (ui.adapter.isNotBlank()) item(key = "adapter") { InfoCard("Адаптер", ui.adapter.lines(), mono = true) }
            if (ui.target.isNotBlank()) item(key = "target") { InfoCard("Микроконтроллер", ui.target.lines(), mono = true) }
            item(key = "read") {
                SectionCard("Чтение слова") {
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Адрес (hex)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { onRead(address) }, enabled = !ui.busy && ui.connected) { Text("Прочитать 32 бита") }
                    if (ui.readResult.isNotBlank()) SelectionContainer { Text(ui.readResult, style = MonoText) }
                }
            }
            item(key = "filters") {
                SectionCard("Сигналы") {
                    Text(ui.fieldsInfo, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpen, enabled = !ui.busy) { Text("Открыть addresses.json") }
                    Text("Имена топиков из файла сохраняются для графиков и MCAP.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Поиск: имя, путь, тип, адрес") }, singleLine = true)
                    CatalogChoice("Группа: " + grouping.title,
                        FieldCatalog.Grouping.entries.map { it.name to it.title }) { groupingName = it; collapsed = emptyList() }
                    CatalogChoice("Тип: " + activeType.ifEmpty { "все" }, listOf("" to "Все типы") + types.map { it to it }) { type = it }
                    Text("Показано " + filtered.size + " из " + ui.fields.size + ". Нажмите сигнал для графика.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { query = ""; type = ""; collapsed = emptyList() }) { Text("Сбросить") }
                        TextButton(onClick = { collapsed = if (collapsed.isEmpty()) groups.keys.toList() else emptyList() }) {
                            Text(if (collapsed.isEmpty()) "Свернуть группы" else "Развернуть группы")
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item(key = "empty") { Text("Нет сигналов по выбранным условиям.", color = colors.muted) }
            for ((group, fields) in groups) {
                item(key = "group:$group") {
                    TextButton(onClick = { collapsed = if (group in collapsed) collapsed - group else collapsed + group }) {
                        Text((if (group in collapsed) "▸ " else "▾ ") + group + " (" + fields.size + ")")
                    }
                }
                if (group !in collapsed) items(fields, key = { "field:" + it.topic }) { field ->
                    SignalCard(field, ui.readings[field.topic], onClick = { onChart(field) })
                }
            }
        }
    }
    if (showLog) ExpertLogDialog(ui, onCopyLog, onClearLog, onLogPolling) { showLog = false }
}

@Composable
private fun CatalogChoice(label: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((key, title) in choices) DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(key) })
        }
    }
}

@Composable
private fun SignalCard(field: Field, reading: FieldSampler.Reading?, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val check = Session.check(field)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.shortName, style = MaterialTheme.typography.titleSmall)
            val value = reading?.value
            val error = reading?.error
            Text(when {
                error != null -> error
                value != null -> field.format(value) + if (reading.outOfRange) " (вне границ)" else ""
                else -> "—"
            }, style = MaterialTheme.typography.titleLarge,
                color = if (reading?.error != null || reading?.outOfRange == true) colors.bad else MaterialTheme.colorScheme.onSurface)
            Text(field.topic, style = MonoText, color = colors.muted)
            Text(field.address.hex32() + " · " + field.typeLabel + (field.sizeBits?.let { " · $it бит" } ?: ""), style = MonoText)
            field.expr?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            field.sourceFile?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            if (field.min != null || field.max != null) Text("Границы: ${field.min ?: "…"} … ${field.max ?: "…"}", style = MonoText)
            check.warning?.let { Text(it, color = colors.warn, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ExpertLogDialog(ui: ExpertUi, onCopy: () -> Unit, onClear: () -> Unit, onPolling: (Boolean) -> Unit, onClose: () -> Unit) {
    var protocol by rememberSaveable { mutableStateOf(false) }
    val lines = remember(ui.log, protocol) { ui.log.lines().filter { protocol || !SessionLog.isProtocol(it) }.takeLast(300) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Журнал: действия и ответы") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = protocol, onCheckedChange = { protocol = it })
                    Text("Показать обмен USB")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ui.logPolling, onCheckedChange = onPolling)
                    Text("Писать обмен при опросе")
                }
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lines.size) { index ->
                        val line = lines[index]
                        val user = line.startsWith(Session.USER_PREFIX)
                        val content = when {
                            user -> "Вы: " + line.removePrefix(Session.USER_PREFIX)
                            line.startsWith(Session.PROGRAM_PREFIX) -> "Программа: " + line.removePrefix(Session.PROGRAM_PREFIX)
                            else -> line
                        }
                        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small) {
                            SelectionContainer { Text(content, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text("Копировать") }
                TextButton(onClick = onClear) { Text("Очистить") }
            }
        },
    )
}

package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
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
) {
    var address by rememberSaveable { mutableStateOf("E000ED00") }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var groupingName by rememberSaveable { mutableStateOf(FieldCatalog.Grouping.SOURCE.name) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val grouping = FieldCatalog.Grouping.valueOf(groupingName)
    val types = remember(ui.fields) { ui.fields.map { it.typeLabel }.distinct().sorted() }
    // Новый ELF/JSON может не содержать ранее выбранный тип.
    val activeType = type.takeIf { it in types } ?: ""
    val filtered = remember(ui.fields, query, activeType) { FieldCatalog.filter(ui.fields, query, activeType) }
    val groups = remember(filtered, grouping) { FieldCatalog.group(filtered, grouping) }
    val colors = LocalStatusColors.current
    val listState = rememberLazyListState()

    // ТЗ прошивки 5.9.9: навигация не входит в обновляемый список показаний.
    HwScreen("Режим эксперта", onBack = onBack, scrolling = false, actions = {
        AppMenu(actions = listOf(
            MenuAction("Цель", !ui.busy) { onNavigate(TargetActivity::class.java) },
            MenuAction("Прошивка", !ui.busy) { onNavigate(FlashActivity::class.java) },
            MenuAction("Простой режим", !ui.busy, onBack),
        ), navigationEnabled = !ui.busy)
    }) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    SignalCard(field, reading = { ui.readings[field.topic] }, onClick = { onChart(field) })
                }
            }
        }
    }
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
private fun SignalCard(field: Field, reading: () -> FieldSampler.Reading?, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val check = Session.check(field)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.shortName, style = MaterialTheme.typography.titleSmall)
            val sample = reading()
            val value = sample?.value
            val error = sample?.error
            Text(when {
                error != null -> error
                value != null -> field.format(value) + if (sample.outOfRange) " (вне границ)" else ""
                else -> "—"
            }, style = MaterialTheme.typography.titleLarge, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (error != null || sample?.outOfRange == true) colors.bad else MaterialTheme.colorScheme.onSurface)
            Text(field.topic, style = MonoText, color = colors.muted)
            Text(field.address.hex32() + " · " + field.typeLabel + (field.sizeBits?.let { " · $it бит" } ?: ""), style = MonoText)
            field.expr?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            field.sourceFile?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            if (field.min != null || field.max != null) Text("Границы: ${field.min ?: "…"} … ${field.max ?: "…"}", style = MonoText)
            check.warning?.let { Text(it, color = colors.warn, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

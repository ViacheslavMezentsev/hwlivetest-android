package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
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
    onConnect: () -> Unit,
    onRead: (String) -> Unit,
    onOpen: () -> Unit,
    onChart: (Field) -> Unit,
) {
    val debug by Session.debugDetails.collectAsState()
    var address by rememberSaveable { mutableStateOf("E000ED00") }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var groupingName by rememberSaveable { mutableStateOf(FieldCatalog.Grouping.SOURCE.name) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val grouping = if (debug) FieldCatalog.Grouping.valueOf(groupingName) else FieldCatalog.Grouping.NONE
    val types = remember(ui.fields) { ui.fields.map { it.typeLabel }.distinct().sorted() }
    // Новый ELF/JSON может не содержать ранее выбранный тип.
    val activeType = if (debug) type.takeIf { it in types } ?: "" else ""
    val filtered = remember(ui.fields, query, activeType) { FieldCatalog.filter(ui.fields, query, activeType) }
    val groups = remember(filtered, grouping) { FieldCatalog.group(filtered, grouping) }
    val colors = LocalStatusColors.current
    val listState = rememberLazyListState()

    // ТЗ прошивки 5.9.9: навигация не входит в обновляемый список показаний.
    HwScreen("Сигналы", onBack = onBack, scrolling = false, navigationEnabled = !ui.busy, actions = {
        AppMenu(actions = listOf(
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
                    if (!ui.connected) ButtonRow("Подключить" to onConnect, enabled = !ui.busy)
                    if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (debug && ui.adapter.isNotBlank()) item(key = "adapter") { InfoCard("Адаптер", ui.adapter.lines(), mono = true, collapsible = true) }
            if (debug && ui.target.isNotBlank()) item(key = "target") { InfoCard("Микроконтроллер", ui.target.lines(), mono = true, collapsible = true) }
            if (debug) item(key = "read") {
                SectionCard("Чтение слова", collapsible = true) {
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
                    if (debug) Text("Имена топиков из файла сохраняются для графиков и MCAP.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (debug) "Поиск: имя, путь, тип, адрес" else "Поиск сигнала") }, singleLine = true)
                    if (debug) CatalogChoice("Группа: " + grouping.title,
                        FieldCatalog.Grouping.entries.map { it.name to it.title }) { groupingName = it; collapsed = emptyList() }
                    if (debug) CatalogChoice("Тип: " + activeType.ifEmpty { "все" }, listOf("" to "Все типы") + types.map { it to it }) { type = it }
                    Text("Показано " + filtered.size + " из " + ui.fields.size + ". Нажмите сигнал для графика.",
                        style = MaterialTheme.typography.bodySmall)
                    if (debug) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { query = ""; type = ""; collapsed = emptyList() }) { Text("Сбросить") }
                        TextButton(onClick = { collapsed = if (collapsed.isEmpty()) groups.keys.toList() else emptyList() }) {
                            Text(if (collapsed.isEmpty()) "Свернуть группы" else "Развернуть группы")
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item(key = "empty") { Text("Нет сигналов по выбранным условиям.", color = colors.muted) }
            for ((group, fields) in groups) {
                if (debug) item(key = "group:$group") {
                    TextButton(onClick = { collapsed = if (group in collapsed) collapsed - group else collapsed + group }) {
                        Text((if (group in collapsed) "▸ " else "▾ ") + group + " (" + fields.size + ")")
                    }
                }
                if (!debug || group !in collapsed) items(fields, key = { "field:" + it.topic }) { field ->
                    SignalCard(field, debug, ui.fields.count { it.shortName == field.shortName } > 1, ui.connected && ui.polling, ui.readAt[field.topic], reading = { ui.readings[field.topic] }, onClick = { onChart(field) })
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
private fun SignalCard(field: Field, debug: Boolean, duplicateName: Boolean, polling: Boolean, readAt: Long?, reading: () -> FieldSampler.Reading?, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val check = Session.check(field)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalDot(polling, readAt)
                Text(field.shortName, style = MaterialTheme.typography.titleSmall)
            }
            val sample = reading()
            val value = sample?.value
            val error = sample?.error
            Text(when {
                error != null -> error
                value != null -> field.format(value) + if (sample.outOfRange) " (вне границ)" else ""
                else -> "—"
            }, style = MaterialTheme.typography.titleLarge, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (error != null || sample?.outOfRange == true) colors.bad else MaterialTheme.colorScheme.onSurface)
            if (debug || duplicateName) Text(field.topic, style = MonoText, color = colors.muted)
            if (debug) Text(field.address.hex32() + " · " + field.typeLabel + (field.sizeBits?.let { " · $it бит" } ?: ""), style = MonoText)
            if (debug) field.expr?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (debug) field.sourceFile?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            if (field.min != null || field.max != null) Text("Границы: ${field.min ?: "…"} … ${field.max ?: "…"}", style = MonoText)
            check.warning?.let { Text(it, color = colors.warn, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SignalDot(polling: Boolean, readAt: Long?) {
    val now by produceState(android.os.SystemClock.elapsedRealtime(), polling, readAt) {
        do { value = android.os.SystemClock.elapsedRealtime(); delay(250) } while (polling)
    }
    val active = SignalFreshness.active(true, polling, now, readAt)
    val colors = LocalStatusColors.current
    val color = if (active) colors.ok else colors.muted
    Canvas(Modifier.size(10.dp).semantics { contentDescription = if (active) "Сигнал читается" else "Нет свежих показаний" }) {
        drawCircle(color)
    }
}

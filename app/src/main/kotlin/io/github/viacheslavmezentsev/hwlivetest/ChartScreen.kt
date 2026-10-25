package io.github.viacheslavmezentsev.hwlivetest

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldCatalog
import io.github.viacheslavmezentsev.hwlivetest.telemetry.needsOwnScale
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ прошивки 5.11: Canvas заменяет View; оси с гистерезисом сохраняются между кадрами. */
@Composable
fun ChartScreen(ui: ChartUi, fields: List<Field>, all: List<Field>, onBack: () -> Unit,
    onTopics: (List<String>) -> Unit, onPause: () -> Unit, onRecord: () -> Unit, onConnect: () -> Unit,
    onExport: () -> Unit, onClear: () -> Unit, onFolder: () -> Unit, onShare: (SavedRecording) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var channelQuery by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val matching = remember(all, channelQuery, selectedOnly, chosen) {
        FieldCatalog.filter(all, channelQuery).filter { !selectedOnly || it.topic in chosen }
    }
    val selected = ui.topics.indexOf(ui.selectedTopic).coerceAtLeast(0)
    ui.now // Автоматическая шкала зависит от последнего отсчёта каждого кадра.
    val own = ui.ownScale ?: (ui.lines.size > 1 && needsOwnScale(ui.lines.mapNotNull { it.series.lastValue() }))
    val recording = ui.recording.isNotEmpty()
    HwScreen(if (fields.size == 1) fields[0].shortName else "График: "+fields.size+" каналов", onBack, scrolling = false, keepAwake = true, actions = {
        AppMenu(actions = listOf(
            MenuAction("Сохранить буфер", enabled = !recording && !ui.recordBusy, run = onExport),
            MenuAction("Папка записей", enabled = !recording && !ui.recordBusy, run = onFolder),
            MenuAction("Очистить график", enabled = !recording && !ui.recordBusy, run = onClear),
        ), navigationEnabled = !recording && !ui.recordBusy)
    }) {
        if (!ui.connected) OutlinedButton(onClick = onConnect, enabled = !ui.recordBusy && !ui.connecting) { Text(if (ui.connecting) "Подключение…" else "Подключить") }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth > maxHeight
            val plotHeight = (maxHeight * 0.46f).coerceIn(180.dp, 420.dp)
            val choose = { chosen = ui.topics; choosing = true }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartSummary(ui, selected)
                        ChartControls(ui, own, recording, onPause, onRecord, choose)
                        ChartStatistics(ui, fields, selected)
                    }
                    ChartPlot(ui, own, selected, Modifier.weight(0.64f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChartSummary(ui, selected)
                    ChartPlot(ui, own, selected, Modifier.fillMaxWidth().height(plotHeight))
                    ChartControls(ui, own, recording, onPause, onRecord, choose)
                    ChartStatistics(ui, fields, selected)
                }
            }
        }
    }
    ui.saved?.let { saved ->
        AlertDialog(onDismissRequest = { ui.saved = null }, title = { Text(if (saved.error == null) "Запись сохранена" else "Ошибка сохранения") },
            text = { Text(saved.name+"\nСообщений: "+saved.messages+", "+saved.bytes/1024+" КБ."+(saved.error?.let { " Ошибка: "+it } ?: "")) },
            confirmButton = { TextButton(onClick = { onShare(saved) }) { Text("Поделиться") } },
            dismissButton = { TextButton(onClick = { ui.saved = null }) { Text("Закрыть") } },
        )
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Каналы · ${chosen.size}/6") },
        text = { Column(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = channelQuery, onValueChange = { channelQuery = it },
                label = { Text("Поиск сигнала или топика") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (channelQuery.isNotEmpty()) TextButton(onClick = { channelQuery = "" }) { Text("Сброс") } })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text("Выбранные") })
                Text("Показано ${matching.size} из ${all.size}", style = MaterialTheme.typography.bodySmall)
            }
            if (matching.isEmpty()) Text("Нет сигналов по выбранным условиям.")
            LazyColumn(Modifier.weight(1f, fill = false)) {
            items(matching, key = { it.topic }) { f ->
                Row(Modifier.fillMaxWidth().clickable {
                    chosen = if (f.topic in chosen) chosen-f.topic else if (chosen.size < 6) chosen+f.topic else chosen
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f.topic in chosen, enabled = f.topic in chosen || chosen.size < 6, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = chosen.indexOf(f.topic).takeIf { it >= 0 }?.let { Color(ChartSetup.color(it, androidx.compose.foundation.isSystemInDarkTheme())) } ?: MaterialTheme.colorScheme.primary))
                    Text(f.topic, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            }
        } },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onTopics(chosen); choosing = false }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Отмена") } },
    )
}

@Composable
private fun ChartSummary(ui: ChartUi, selected: Int) {
    ui.now // TimeSeries потокобезопасен, но не является Compose State.
        val line = ui.lines.getOrNull(selected)
        Text(ui.error ?: ((if (ui.lines.size > 1) line?.name+": " else "") +
            (line?.series?.lastValue()?.let(line.format) ?: "—")),
            style = MaterialTheme.typography.headlineMedium,
            color = if (ui.error != null) MaterialTheme.colorScheme.error else line?.let { Color(it.color) } ?: MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, ms) in listOf("30 с" to 30_000L, "1 мин" to 60_000L, "5 мин" to 300_000L)) {
                FilterChip(selected = ui.windowMs == ms, onClick = { ui.windowMs = ms }, label = { Text(label) })
            }
        }
}

@Composable
private fun ChartControls(ui: ChartUi, own: Boolean, recording: Boolean,
    onPause: () -> Unit, onRecord: () -> Unit, onChoose: () -> Unit,
) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(if (ui.paused) "Продолжить график" else "Пауза графика") }
            OutlinedButton(onClick = { ui.ownScale = !own }, enabled = ui.lines.size > 1, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(if (own) "Свои шкалы" else "Общая шкала")
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChoose() }, enabled = !recording && !ui.recordBusy,
                modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Каналы · ${ui.topics.size}/6") }
            Button(onClick = onRecord, enabled = !ui.recordBusy && (recording || ui.connected), modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(if (recording) "■ Остановить" else "● Запись")
            }
        }
}

@Composable
private fun ChartStatistics(ui: ChartUi, fields: List<Field>, selected: Int) {
    val now = ui.now
    val debug by Session.debugDetails.collectAsState()
    val recording = ui.recording.isNotEmpty()
        var rate = 0.0
        ui.lines.forEachIndexed { index, l ->
            val w = l.series.window(now - ui.windowMs, now)
            if (w.size > 1) rate = maxOf(rate, (w.size - 1)*1000.0/(w.times.last()-w.times.first()).coerceAtLeast(1))
            val text = if (w.size == 0) "нет данных" else
                "мин "+w.min?.let(l.format)+"  ср "+w.mean?.let(l.format)+"  макс "+w.max?.let(l.format)
            Text((if (index == selected) "● " else "○ ")+l.name+": "+text,
                modifier = Modifier.fillMaxWidth().clickable { ui.selectedTopic = ui.topics[index] }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = Color(l.color))
        }
        if (debug) Text("%.1f Гц, проход опроса %d мс".format(rate, ui.pollMs), style = MaterialTheme.typography.bodySmall)
        if (ui.paused && ui.connected) Text(if (recording) "График на паузе. Опрос и запись продолжаются." else "График на паузе. Опрос продолжается.", style = MaterialTheme.typography.bodySmall)
        if (ui.message.isNotEmpty()) Text(ui.message)
        if (!debug) fields.filter { f -> fields.count { it.shortName == f.shortName } > 1 }.forEach { Text(it.topic, style = MaterialTheme.typography.bodySmall) }
        if (debug) fields.forEach { f -> Text(f.topic+"\n"+f.address.hex32()+" "+f.typeLabel+
            (Session.check(f).warning?.let { " ⚠ "+it } ?: "")+(f.expr?.let { "\n"+it } ?: ""), style = MonoText) }
}

/** ТЗ прошивки 5.11.4: nativeCanvas не получает право рисовать за границами графика. */
@Composable
private fun ChartPlot(ui: ChartUi, own: Boolean, selected: Int, modifier: Modifier) {
    val density = LocalDensity.current.density
    val renderer = remember(density) { ChartRenderer(density) }
    val scheme = MaterialTheme.colorScheme
    SideEffect { renderer.onSelect = { i -> ui.selectedTopic = ui.topics.getOrNull(i) } }
    Canvas(modifier.clipToBounds().pointerInput(renderer) {
        detectTapGestures { renderer.selectAt(it.x, it.y) }
    }) {
        if (renderer.lines !== ui.lines) renderer.lines = ui.lines
        renderer.nowMs = ui.now
        renderer.windowMs = ui.windowMs
        renderer.ownScale = own
        renderer.selected = selected
        renderer.setColors(scheme.surface.toArgb(), scheme.onSurfaceVariant.toArgb(), scheme.outlineVariant.toArgb())
        clipRect { renderer.draw(drawContext.canvas.nativeCanvas, size.width, size.height) }
    }
}

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
import io.github.viacheslavmezentsev.hwlivetest.telemetry.needsOwnScale
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ прошивки 5.11: Canvas заменяет View; оси с гистерезисом сохраняются между кадрами. */
@Composable
fun ChartScreen(ui: ChartUi, fields: List<Field>, all: List<Field>, onBack: () -> Unit,
    onTopics: (List<String>) -> Unit, onPause: () -> Unit, onRecord: () -> Unit,
    onExport: () -> Unit, onFolder: () -> Unit, onShare: (SavedRecording) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selected = ui.topics.indexOf(ui.selectedTopic).coerceAtLeast(0)
    ui.now // Автоматическая шкала зависит от последнего отсчёта каждого кадра.
    val own = ui.ownScale ?: (ui.lines.size > 1 && needsOwnScale(ui.lines.mapNotNull { it.series.lastValue() }))
    val recording = ui.recording.isNotEmpty()
    HwScreen(if (fields.size == 1) fields[0].shortName else "График: "+fields.size+" каналов", onBack, scrolling = false, actions = {
        AppMenu(actions = listOf(
            MenuAction("Сохранить буфер в MCAP", run = onExport),
            MenuAction("Папка записей", run = onFolder),
        ), navigationEnabled = !recording && !ui.recordBusy)
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
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
        AlertDialog(onDismissRequest = { ui.saved = null }, title = { Text(saved.name) },
            text = { Text("Сообщений: "+saved.messages+", "+saved.bytes/1024+" КБ."+(saved.error?.let { " Ошибка: "+it } ?: "")) },
            confirmButton = { TextButton(onClick = { onShare(saved) }) { Text("Поделиться") } },
            dismissButton = { TextButton(onClick = { ui.saved = null }) { Text("Закрыть") } },
        )
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Каналы (до 6)") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(all, key = { it.topic }) { f ->
                Row(Modifier.fillMaxWidth().clickable {
                    chosen = if (f.topic in chosen) chosen-f.topic else (chosen+f.topic).takeLast(6)
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f.topic in chosen, onCheckedChange = null)
                    Text(f.topic, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
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
            OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(if (ui.paused) "Продолжить" else "Пауза") }
            OutlinedButton(onClick = { ui.ownScale = !own }, enabled = ui.lines.size > 1, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(if (own) "Свои шкалы" else "Общая шкала")
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChoose() }, enabled = !recording && !ui.recordBusy,
                modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Каналы") }
            Button(onClick = onRecord, enabled = !ui.recordBusy, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(if (recording) "Остановить запись" else "Запись MCAP")
            }
        }
}

@Composable
private fun ChartStatistics(ui: ChartUi, fields: List<Field>, selected: Int) {
    val now = ui.now
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
        Text("%.1f Гц, проход опроса %d мс".format(rate, ui.pollMs), style = MaterialTheme.typography.bodySmall)
        if (recording) Text("● Запись: "+ui.recording, color = MaterialTheme.colorScheme.error)
        if (ui.message.isNotEmpty()) Text(ui.message)
        fields.forEach { f -> Text(f.topic+"\n"+f.address.hex32()+" "+f.typeLabel+
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

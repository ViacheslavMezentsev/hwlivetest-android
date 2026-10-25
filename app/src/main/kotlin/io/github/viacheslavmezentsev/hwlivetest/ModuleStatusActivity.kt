package io.github.viacheslavmezentsev.hwlivetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.ui.*
import java.util.Locale

/** ТЗ прошивки 5.19: отдельная страница модуля, вне цикла основных вкладок. */
class ModuleStatusActivity : ComponentActivity() {
    private var polling by mutableStateOf(false)
    private var error by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HwTheme {
            val route by Session.route.collectAsState()
            val traffic by Session.traffic.collectAsState()
            LaunchedEffect(route.ready) { if (!route.ready) stopPolling() }
            HwScreen("Модуль ST-Link", onBack = ::finish) {
                InfoCard("Подключение", listOf(
                    "Модель: " + (Session.device?.model?.title ?: "не подключён"),
                    "Прошивка адаптера: " + (Session.connection?.version?.label ?: "не прочитана"),
                    "Интерфейс: USB bulk → SWD",
                    "Устройство: " + route.mcu.ifEmpty { "не опознано" },
                    Session.connection?.targetVoltage?.let { "Напряжение: %.2f В".format(Locale.ROOT, it) } ?: "Напряжение: не прочитано",
                ))
                val latest = traffic.points.lastOrNull()
                SectionCard("Трафик") {
                    Text("От адаптера: " + rate(latest?.receivedPerSecond ?: 0.0))
                    Text("К адаптеру: " + rate(latest?.sentPerSecond ?: 0.0))
                    TrafficPlot(traffic.points)
                    Text("Последние 60 отсчётов · шаг около 1 с", style = MaterialTheme.typography.bodySmall)
                    Text("Всего принято: " + traffic.received + " байт; отправлено: " + traffic.sent + " байт.")
                    Text("Измеренный пик IN + OUT: " + rate(traffic.peakPerSecond))
                    Text("Максимально возможная скорость: не определена. Измеренный пик не является пределом USB или ST-Link.", style = MaterialTheme.typography.bodySmall)
                    Text("Считаются байты успешного протокольного обмена, без служебных пакетов USB и повторов на шине.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Наблюдение") {
                    Text("График сохраняет обмен при работе других экранов. Для наблюдения текущего трафика можно включить чтение загруженных сигналов.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = route.ready && Session.fieldsFile?.fields?.isNotEmpty() == true,
                        onClick = { if (polling) stopPolling() else startPolling() }) {
                        Text(if (polling) "Остановить чтение сигналов" else "Читать сигналы")
                    }
                    if (!route.ready) Text("Сначала подключите устройство.")
                    else if (Session.fieldsFile?.fields.isNullOrEmpty()) Text("Загрузите таблицу на странице «Сигналы».")
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        } }
    }
    private fun startPolling() {
        error = ""
        polling = true
        Session.startPolling(500L, null, onReadings = {}, onStopped = { message ->
            runOnUiThread { polling = false; error = message }
        })
    }
    private fun stopPolling() { if (polling) Session.stopPolling(); polling = false }
    override fun onPause() { stopPolling(); super.onPause() }
}

private fun rate(bytes: Double) = String.format(Locale.ROOT, "%.2f КиБ/с", bytes / 1024)

@Composable
private fun TrafficPlot(points: List<TrafficHistory.Point>) {
    val incoming = MaterialTheme.colorScheme.primary
    val outgoing = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maximum = maxOf(1.0, points.maxOfOrNull { maxOf(it.receivedPerSecond, it.sentPerSecond) } ?: 0.0)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("IN · от адаптера", color = incoming, style = MaterialTheme.typography.labelSmall)
        Text("OUT · к адаптеру", color = outgoing, style = MaterialTheme.typography.labelSmall)
    }
    Text("Верх шкалы: " + rate(maximum), style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        for (i in 0..4) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
        val end = points.lastOrNull()?.timeMs ?: 0L
        val start = end - 60_000L
        fun position(p: TrafficHistory.Point, value: Double) = Offset(
            ((p.timeMs - start) / 60_000.0 * size.width).toFloat().coerceIn(0f, size.width),
            (size.height * (1.0 - value / maximum)).toFloat())
        points.zipWithNext().forEach { (a, b) ->
            drawLine(incoming, position(a, a.receivedPerSecond), position(b, b.receivedPerSecond), strokeWidth = 2.dp.toPx())
            drawLine(outgoing, position(a, a.sentPerSecond), position(b, b.sentPerSecond), strokeWidth = 2.dp.toPx())
        }
    }
    Text("−60 с                                      сейчас", style = MaterialTheme.typography.labelSmall)
}

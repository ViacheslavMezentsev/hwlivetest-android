package io.github.viacheslavmezentsev.hwlivetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import io.github.viacheslavmezentsev.hwlivetest.ui.*

/** ТЗ прошивки 5.13: общий журнал-диалог; обновления работают только на видимом экране. */
class JournalActivity : ComponentActivity() {
    private var entries by mutableStateOf(emptyList<SessionLog.Entry>())
    private var logPolling by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { entries = Session.log.snapshot(); handler.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HwTheme {
            JournalScreen(entries, logPolling, ::finish, onCopy = {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("HW Live Test log", Session.log.text()))
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }, onClear = {
                Session.log.clear(); MainActivity.logHeader(); entries = Session.log.snapshot()
            }, onPolling = { enabled ->
                logPolling = enabled; Session.logPolling = enabled; Prefs.setLogPolling(this, enabled)
            })
        } }
    }
    override fun onResume() {
        super.onResume()
        logPolling = Session.logPolling
        handler.post(refresh)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}

@Composable
private fun JournalScreen(entries: List<SessionLog.Entry>, polling: Boolean, onBack: () -> Unit,
    onCopy: () -> Unit, onClear: () -> Unit, onPolling: (Boolean) -> Unit,
) {
    var protocol by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val rows = remember(entries, protocol) { entries.flatMap { entry ->
        entry.text.lines().mapIndexed { i, text -> (entry.id.toString()+":"+i) to text }
    }.filter { protocol || !SessionLog.isProtocol(it.second) }.takeLast(300).asReversed() }
    HwScreen("Журнал", onBack, scrolling = false, actions = {
        AppMenu(actions = listOf(MenuAction("Копировать весь журнал", run = onCopy),
            MenuAction("Очистить журнал") { confirmClear = true }))
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = protocol, onCheckedChange = { protocol = it }); Text("Показать обмен USB")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = polling, onCheckedChange = onPolling); Text("Писать обмен при опросе")
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.first }) { (_, text) ->
                val user = text.startsWith(Session.USER_PREFIX)
                val label = when {
                    user -> "Вы: "+text.removePrefix(Session.USER_PREFIX)
                    text.startsWith(Session.PROGRAM_PREFIX) -> "Программа: "+text.removePrefix(Session.PROGRAM_PREFIX)
                    else -> text
                }
                Surface(Modifier.fillMaxWidth(), color = if (user) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    SelectionContainer { Text(label, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (rows.isEmpty()) Text("Журнал пуст.")
        Text("Последние 300 строк. Копирование — весь журнал сеанса.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Очистить журнал?") }, text = { Text("Строки текущего сеанса будут удалены.") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}

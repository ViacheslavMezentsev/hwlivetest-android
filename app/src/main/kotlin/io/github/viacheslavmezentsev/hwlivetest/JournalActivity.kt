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
class JournalActivity : ComponentActivity(), WorkspaceHost {
    private var workspace by mutableStateOf(false)
    override val workspacePage get() = if (workspace) WorkspacePage.JOURNAL else null
    override fun navigateWorkspace(page: WorkspacePage, direction: Int) { if (workspacePage != null && page != workspacePage) WorkspaceNavigation.open(this, page, direction) }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); workspace = intent.getBooleanExtra(WorkspaceNavigation.EXTRA_WORKSPACE, false) }
    private fun back() { if (workspacePage == null) finish() else WorkspaceNavigation.home(this) }
    private var history by mutableStateOf(emptyList<String>())
    private var entries by mutableStateOf(emptyList<SessionLog.Entry>())
    private var logPolling by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { entries = Session.log.snapshot(); handler.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workspace = intent.getBooleanExtra(WorkspaceNavigation.EXTRA_WORKSPACE, false)
        setContent { HwTheme {
            JournalScreen(entries, logPolling, ::back, history = history, onClearHistory = { FlashHistory.clear(this); history = FlashHistory.lines(this) }, onCopy = {
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
        history = FlashHistory.lines(this)
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
    history: List<String>, onClearHistory: () -> Unit,
    onCopy: () -> Unit, onClear: () -> Unit, onPolling: (Boolean) -> Unit,
) {
    val debug by Session.debugDetails.collectAsState()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var confirmHistory by rememberSaveable { mutableStateOf(false) }
    var protocol by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val rows = remember(entries, protocol, debug) { entries.flatMap { entry ->
        entry.text.lines().mapIndexed { i, text -> (entry.id.toString()+":"+i) to text }
    }.filter { (debug && protocol) || !SessionLog.isProtocol(it.second) }.takeLast(300).asReversed() }
    HwScreen("Журнал", onBack, scrolling = false, actions = {
        AppMenu(actions = listOf(MenuAction("Копировать весь журнал", run = onCopy),
            MenuAction("Очистить журнал") { confirmClear = true }))
    }) {
        if (debug && !showHistory) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = protocol, onCheckedChange = { protocol = it }); Text("Показать обмен USB")
        }
        if (debug && !showHistory) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = polling, onCheckedChange = onPolling); Text("Писать обмен при опросе")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showHistory, onClick = { showHistory = false }, label = { Text("Сеанс") })
            FilterChip(selected = showHistory, onClick = { showHistory = true }, label = { Text("История операций") })
        }
        if (showHistory) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { Text(it, style = MonoText) }
            }
            if (history.isEmpty()) Text("История пуста.")
            else TextButton(onClick = { confirmHistory = true }) { Text("Очистить историю") }
        } else {
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
    }
    if (confirmHistory) AlertDialog(onDismissRequest = { confirmHistory = false },
        title = { Text("Очистить историю операций?") }, text = { Text("Резервные копии сохранятся.") },
        confirmButton = { TextButton(onClick = { onClearHistory(); confirmHistory = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text("Отмена") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Очистить журнал?") }, text = { Text("Строки текущего сеанса будут удалены.") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}

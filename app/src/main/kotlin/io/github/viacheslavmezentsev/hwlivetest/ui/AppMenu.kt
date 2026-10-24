package io.github.viacheslavmezentsev.hwlivetest.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.viacheslavmezentsev.hwlivetest.AboutActivity
import io.github.viacheslavmezentsev.hwlivetest.JournalActivity

class MenuAction(val title: String, val enabled: Boolean = true, val run: () -> Unit)

/** ТЗ прошивки 5.13: одно меню, без повторных экземпляров информационных экранов. */
@Composable
fun AppMenu(actions: List<MenuAction> = emptyList(), navigationEnabled: Boolean = true) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val common = listOf("Журнал" to JournalActivity::class.java, "О программе" to AboutActivity::class.java)
        .filter { (_, screen) -> context.javaClass != screen }
        .map { (title, screen) -> MenuAction(title, navigationEnabled) {
            context.startActivity(Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } }
    val entries = actions + common
    val enabled = entries.any { it.enabled }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "Меню") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.title) }, enabled = entry.enabled,
                    onClick = { expanded = false; entry.run() })
            }
        }
    }
}

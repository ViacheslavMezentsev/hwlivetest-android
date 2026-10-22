package io.github.viacheslavmezentsev.hwlivetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.viacheslavmezentsev.hwlivetest.ui.HwScreen
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import io.github.viacheslavmezentsev.hwlivetest.ui.InfoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * О программе — первый экран на Compose (docs/roadmap.md, этап 2): версия и сборка, модули,
 * приборы, устройства USB и место, которое занимают данные приложения.
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HwTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sections = remember { AboutInfo.build(context) }
    var storage by remember { mutableStateOf(AboutInfo.Section("Данные приложения", listOf("Подсчёт…"))) }
    LaunchedEffect(Unit) {
        // Обход папок может занять заметное время — не в главном потоке.
        storage = withContext(Dispatchers.IO) { AboutInfo.storage(context) }
    }
    HwScreen(title = "О программе", onBack = onBack) {
        for (s in sections + storage) InfoCard(s.title, s.lines, mono = s.mono)
        Button(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("HW Live Test about", AboutInfo.text(sections + storage)))
                Toast.makeText(context, R.string.about_copied, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Копировать")
        }
    }
}

package io.github.viacheslavmezentsev.hwlivetest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.viacheslavmezentsev.hwlivetest.Session
import io.github.viacheslavmezentsev.hwlivetest.WorkspaceHost
import io.github.viacheslavmezentsev.hwlivetest.WorkspacePage
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import io.github.viacheslavmezentsev.hwlivetest.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.viacheslavmezentsev.hwlivetest.BuildConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ClockFormat = SimpleDateFormat("EEE dd.MM.yyyy HH:mm:ss", Locale("ru"))

/** Текущее время, обновляется в начале каждой секунды (часы в шапке — для снимков экрана). */
@Composable
fun rememberClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000 + 5)
        }
    }
    return now
}

/**
 * Экран: шапка с названием, часами до секунды и кнопкой «Назад», прокручиваемое содержимое
 * с отступом 16 dp и промежутком 12 dp между блоками.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HwScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    scrolling: Boolean = true,
    navigationEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = { AppMenu(navigationEnabled = navigationEnabled) },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val host = LocalContext.current as? WorkspaceHost
    val page = host?.workspacePage
    val route by Session.route.collectAsState()
    val fast by Session.fastActions.collectAsState()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val swipe = if (page != null && navigationEnabled) Modifier.pointerInput(page, navigationEnabled) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onHorizontalDrag = { change, dx -> change.consume(); distance += dx },
            onDragEnd = {
                if (kotlin.math.abs(distance) >= threshold) host.navigateWorkspace(page.next(if (distance < 0) 1 else -1), if (distance < 0) 1 else -1)
            },
        )
    } else Modifier
    // Светлая тема: шапка цвета бренда; тёмная: тёмно-синяя, в цвет строки состояния (brand_dark).
    val dark = isSystemInDarkTheme()
    val barColor = if (dark) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
    val barContent = if (dark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_hardware), contentDescription = null, modifier = Modifier.size(24.dp))
                            Text(title, style = MaterialTheme.typography.titleLarge)
                        }
                        HeaderClock(barContent)
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    titleContentColor = barContent,
                    navigationIconContentColor = barContent,
                    actionIconContentColor = barContent,
                ),
                actions = actions,
            )
        },
        bottomBar = {
            if (page != null) TabRow(selectedTabIndex = page.ordinal, modifier = Modifier.navigationBarsPadding()) {
                WorkspacePage.entries.forEach { tab ->
                    Tab(selected = tab == page, enabled = navigationEnabled,
                        onClick = { host.navigateWorkspace(tab) },
                        text = { Text(tab.title, fontSize = 12.sp, maxLines = 1) })
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(swipe)
                .then(if (scrolling) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(route.label + if (fast) " · Быстрые действия включены" else "", style = MaterialTheme.typography.labelSmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }

    }
}

/** Часы инвалидируют только свою строку, не содержимое экрана и всплывающее меню. */
@Composable
private fun HeaderClock(color: Color) {
    val now = rememberClock()
    Text(ClockFormat.format(Date(now)) + "  ·  v" + BuildConfig.VERSION_NAME,
        style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.8f))
}

/** Блок сведений: заголовок цветом бренда и строки; [mono] — моноширинный выделяемый текст. */
@Composable
fun InfoCard(title: String, lines: List<String>, mono: Boolean = false, accent: Color? = null,
    collapsible: Boolean = false,
) {
    SectionCard(title, collapsible, accent) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in lines) Text(line,
                    style = if (mono) MonoText else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** ТЗ прошивки 5.16: технические сведения по умолчанию свёрнуты, выбор переживает поворот. */
@Composable
fun SectionCard(title: String, collapsible: Boolean = false, accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (collapsible) (if (expanded) "▾  " else "▸  ") else "") + title,
                modifier = if (collapsible) Modifier.fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                    .clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 8.dp)
                    else Modifier,
                style = MaterialTheme.typography.titleSmall,
                color = accent ?: MaterialTheme.colorScheme.primary)
            if (!collapsible || expanded) content()
        }
    }
}

/** Ряд кнопок равной ширины. */
@Composable
fun ButtonRow(vararg actions: Pair<String, () -> Unit>, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        for ((label, onClick) in actions) {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(label) }
        }
    }
}

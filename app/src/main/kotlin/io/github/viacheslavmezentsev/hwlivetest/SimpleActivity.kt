package io.github.viacheslavmezentsev.hwlivetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.viacheslavmezentsev.hwlivetest.diag.AdapterFacts
import io.github.viacheslavmezentsev.hwlivetest.diag.DiagReport
import io.github.viacheslavmezentsev.hwlivetest.diag.Diagnostics
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import java.util.concurrent.CancellationException

/** Состояние диагностики переживает поворот; повторный ПУСК не создаёт вторую операцию. */
class SimpleUi : ViewModel() {
    var status by mutableStateOf("")
    var tone by mutableStateOf(Connector.Tone.NORMAL)
    var running by mutableStateOf(false)
    var step by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var report by mutableStateOf<DiagReport?>(null)
    @Volatile var cancel = false
    override fun onCleared() { cancel = true }
}

/** ТЗ прошивки 5.10: простой режим — диагностика; копия и сверка на экране «Прошивка». */
class SimpleActivity : ComponentActivity() {
    private lateinit var ui: SimpleUi
    private lateinit var connector: Connector
    private val onSessionChanged: () -> Unit = { if (!ui.running) showIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ViewModelProvider(this)[SimpleUi::class.java]
        connector = Connector(this) { text, tone ->
            ui.status = text
            ui.tone = tone
            if (tone == Connector.Tone.ERROR && ui.running) finishRun(null)
        }
        if (Session.log.text().isEmpty()) MainActivity.logHeader()
        if (Session.fieldsFile == null) MainActivity.loadSavedFields(this)
        if (!ui.running) ui.report = Session.lastReport
        setContent { HwTheme { SimpleScreen(ui,
            onStart = ::onStartPressed,
            onExpert = {
                Prefs.setExpert(this, true)
                startActivity(Intent(this, MainActivity::class.java))
            },
            onFlash = {
                startActivity(Intent(this, FlashActivity::class.java).putExtra(FlashActivity.EXTRA_READ_ONLY, true))
            },
            onAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
            onCopy = ::copyReport,
        ) } }
        showIdle()
        if (savedInstanceState == null && Prefs.expert(this)) startActivity(Intent(this, MainActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        Session.addListener(onSessionChanged)
        showIdle()
    }

    override fun onPause() {
        Session.removeListener(onSessionChanged)
        super.onPause()
    }

    private fun showIdle() {
        if (ui.running) return
        ui.report = Session.lastReport
        // Ошибку завершившейся проверки не затираем надписью «Подключено».
        if (ui.tone == Connector.Tone.ERROR) return
        ui.status = when {
            Session.stlink != null -> "Подключено: " + Session.target?.shortName
            Session.device != null -> "Найден ST-Link. Нажмите ПУСК."
            else -> "Подключите ST-Link через OTG и нажмите ПУСК."
        }
        ui.tone = if (Session.stlink != null) Connector.Tone.OK else Connector.Tone.NORMAL
    }

    private fun onStartPressed() {
        if (ui.running) {
            ui.cancel = true
            ui.step = "Останавливаю…"
            return
        }
        ui.running = true
        ui.cancel = false
        ui.tone = Connector.Tone.NORMAL
        ui.progress = 0f
        ui.step = "Подключение и опознание MCU…"
        Session.userSaid("нажал ПУСК")
        Session.stopPolling()
        if (Session.stlink != null && Session.target != null) runDiagnostics()
        else connector.connect { info ->
            if (info == null || ui.cancel) finishRun(null) else runDiagnostics()
        }
    }

    private fun runDiagnostics() {
        val c = Session.connection
        val d = Session.device
        val adapter = if (c != null && d != null) AdapterFacts(d.model.title, c.version.label, c.targetVoltage) else null
        Session.worker.execute {
            val report = try {
                if (ui.cancel) throw CancellationException()
                val st = Session.stlink ?: error("нет подключения")
                val target = Session.target ?: error("MCU не опознан")
                Diagnostics(st, target, adapter, progress = { done, total, title ->
                    if (ui.cancel) throw CancellationException()
                    runOnUiThread {
                        ui.progress = if (total > 0) done.toFloat() / total else 0f
                        ui.step = title
                    }
                }).run()
            } catch (_: CancellationException) {
                Session.programSaid("диагностика отменена")
                null
            } catch (e: Exception) {
                val text = ErrorMessages.describe(e)
                Session.programSaid("диагностика: " + text)
                runOnUiThread { ui.status = text; ui.tone = Connector.Tone.ERROR }
                null
            }
            report?.let { Session.lastReport = it; Session.logLine(it.text()); Session.programSaid(it.summary) }
            runOnUiThread { finishRun(report) }
        }
    }

    private fun finishRun(report: DiagReport?) {
        ui.running = false
        ui.step = if (ui.cancel) "Проверка отменена. Предыдущий отчёт сохранён." else ""
        if (report != null) ui.report = report
        showIdle()
    }

    private fun copyReport() {
        val text = (ui.report?.text() ?: "Диагностика ещё не выполнена.") + "\n\n" + Session.log.text()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("HW Live Test report", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }
}

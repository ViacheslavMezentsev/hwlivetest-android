package io.github.viacheslavmezentsev.hwlivetest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.viacheslavmezentsev.hwlivetest.firmware.ElfFile
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashErrorKind
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashLayout
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashOutcome
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashPlan
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashStep
import io.github.viacheslavmezentsev.hwlivetest.firmware.FlashTools
import io.github.viacheslavmezentsev.hwlivetest.firmware.Flasher
import io.github.viacheslavmezentsev.hwlivetest.firmware.HistoryEntry
import io.github.viacheslavmezentsev.hwlivetest.firmware.IntelHex
import io.github.viacheslavmezentsev.hwlivetest.firmware.PlanTarget
import io.github.viacheslavmezentsev.hwlivetest.firmware.Segment
import io.github.viacheslavmezentsev.hwlivetest.firmware.SignalTable
import io.github.viacheslavmezentsev.hwlivetest.stlink.CortexDebugRegs
import io.github.viacheslavmezentsev.hwlivetest.stlink.CoreState
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AddressesFile
import io.github.viacheslavmezentsev.hwlivetest.ui.ButtonRow
import io.github.viacheslavmezentsev.hwlivetest.ui.HwScreen
import io.github.viacheslavmezentsev.hwlivetest.ui.HwTheme
import io.github.viacheslavmezentsev.hwlivetest.ui.InfoCard
import io.github.viacheslavmezentsev.hwlivetest.ui.LocalStatusColors
import io.github.viacheslavmezentsev.hwlivetest.ui.MonoText
import io.github.viacheslavmezentsev.hwlivetest.ui.SectionCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Состояние экрана «Прошивка». Меняется только в главном потоке. */
class FlashUi : ViewModel() {
    var nextPage: WorkspacePage? = null
    var segments: List<Segment>? = null
    var fileBytes = 0
    var fileSha: String? = null
    var voltage: Double? = null
    var flashPlan: FlashPlan? = null
    var signals: SignalTable? = null
    @Volatile var cancel = false
    override fun onCleared() { cancel = true }
    var readOnly by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var adapter by mutableStateOf("")
    var target by mutableStateOf("")
    var busy by mutableStateOf(false)
    var writeMode by mutableStateOf(false)
    var backup by mutableStateOf(true)
    var run by mutableStateOf(true)
    var file by mutableStateOf<String?>(null)
    var expectedSha by mutableStateOf("")
    var plan by mutableStateOf<List<String>>(emptyList())
    var planAllowed by mutableStateOf(false)
    var hasUnits by mutableStateOf(false)
    var step by mutableStateOf("")
    var progress by mutableStateOf<Float?>(null)
    var result by mutableStateOf<String?>(null)
    var resultOk by mutableStateOf(false)
    var report by mutableStateOf<String?>(null)
    var history by mutableStateOf<List<String>>(emptyList())
    var confirm by mutableStateOf<Confirm?>(null)
    var massWord by mutableStateOf<String?>(null)
    var askClear by mutableStateOf(false)
    var askLeave by mutableStateOf(false)
    var halted by mutableStateOf(false)
    var dialog by mutableStateOf<List<String>>(emptyList())
}

/**
 * Экран «Прошивка» (docs/roadmap.md, этап 4; ТЗ прошивки): файл HEX, план, сверка, стирание,
 * прошивка с резервной копией и сверкой, история и отчёт.
 */
class FlashActivity : ComponentActivity(), WorkspaceHost {
    override val workspacePage: WorkspacePage? get() = if (ui.readOnly) null else WorkspacePage.FLASH
    override fun navigateWorkspace(page: WorkspacePage) { if (!ui.readOnly && page != workspacePage) leave(page) }
    private val sessionChanged: () -> Unit = { sync(); if (!ui.busy) rebuildPlan() }
    override fun onResume() {
        super.onResume()
        Session.addListener(sessionChanged)
        sync()
        if (!ui.busy) {
            rebuildPlan()
            if (Session.route.value.ready) readVoltage()
        }
    }
    override fun onPause() { Session.removeListener(sessionChanged); super.onPause() }

    private val ui by lazy { ViewModelProvider(this)[FlashUi::class.java] }
    private lateinit var connector: Connector

    private var segments: List<Segment>?
        get() = ui.segments
        set(value) { ui.segments = value }
    private var fileBytes: Int
        get() = ui.fileBytes
        set(value) { ui.fileBytes = value }
    private var fileSha: String?
        get() = ui.fileSha
        set(value) { ui.fileSha = value }
    private var voltage: Double?
        get() = ui.voltage
        set(value) { ui.voltage = value }
    private var plan: FlashPlan?
        get() = ui.flashPlan
        set(value) { ui.flashPlan = value }

    /** Таблица сигналов из ELF; подключается к цели, только когда Flash совпала с образом. */
    private var signals: SignalTable?
        get() = ui.signals
        set(value) { ui.signals = value }

    private var cancel: Boolean
        get() = ui.cancel
        set(value) { ui.cancel = value }

    private val openHex = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadHex(it) } }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { backupAll(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
        connector = Connector(this) { text, tone ->
            ui.status = text
            ui.error = tone == Connector.Tone.ERROR
            if (ui.error) ui.busy = false
        }
        Session.stopPolling()
        ui.backup = Prefs.flashBackup(this)
        sync()
        setContent {
            HwTheme {
                FlashScreen(
                    ui = ui,
                    actions = FlashActions(
                        back = { leave() },
                        connect = { connect() },
                        pickFile = { openHex.launch(arrayOf("*/*")) },
                        backupAll = { askBackupFile() },
                        expectedSha = { ui.expectedSha = it; rebuildPlan() },
                        writeMode = { on ->
                            if (!ui.readOnly) { if (on) Session.enableWriteMode() else Session.disableWriteMode(); sync() }
                        },
                        backup = { on -> ui.backup = on; Prefs.setFlashBackup(this, on); rebuildPlan() },
                        run = { on -> ui.run = on },
                        check = { check() },
                        flash = { confirmFlash() },
                        eraseUnits = { confirmEraseUnits() },
                        massErase = { ui.confirm = massConfirm() },
                        massEraseConfirmed = { massErase() },
                        cancel = { cancel = true },
                        share = { share() },
                        clearHistory = { FlashHistory.clear(this); sync() },
                        leave = { runCore ->
                            ui.askLeave = false
                            if (runCore) leaveRunning() else completeLeave()
                        },
                    ),
                )
            }
        }
    }

    private fun sync() {
        ui.connected = Session.route.value.ready
        ui.halted = Session.core?.halted == true
        ui.adapter = Session.adapterText
        ui.target = Session.targetText
        ui.writeMode = Session.writeMode
        ui.history = FlashHistory.lines(this)
        ui.dialog = Session.dialogTail(14)
    }

    private fun connect() {
        ui.busy = true
        connector.connect { info ->
            ui.busy = false
            sync()
            if (info != null) readVoltage()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ui.readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
    }

    private fun readVoltage() {
        val st = Session.stlink ?: return
        Session.worker.execute {
            val measured = try {
                st.targetVoltage()
            } catch (_: Exception) {
                null
            }
            val state = runCatching { io.github.viacheslavmezentsev.hwlivetest.stlink.CoreState(st.read32(io.github.viacheslavmezentsev.hwlivetest.stlink.CortexDebugRegs.DHCSR)) }.getOrNull()
            runOnUiThread {
                if (st === Session.stlink) { voltage = measured; Session.updateCore(state); rebuildPlan() }
            }
        }
    }

    private fun devId(): Int? {
        val t = Session.target ?: return null
        if (t.vendor != McuVendor.STM32 && t.vendor != McuVendor.STM32_COMPATIBLE) return null
        return t.idRegister?.and(0xFFF)
    }

    private fun mcu(): String = Session.target?.let { t ->
        listOfNotNull(t.shortName, t.flashSizeKb?.let { "Flash $it КБ" }).joinToString(", ")
    } ?: "не опознан"

    private fun loadHex(uri: Uri) {
        ui.busy = true
        Session.worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: uri.lastPathSegment ?: "файл"
                Session.userSaid("выбрал файл $name (${bytes.size} байт)")
                // ТЗ прошивки 5.9 (р.1.4): ELF — образ по LMA и таблица сигналов из DWARF.
                val table = if (ElfFile.isElf(bytes)) SignalTable.fromElf(bytes, name) else null
                val parsed = table?.image ?: IntelHex.parse(String(bytes, Charsets.US_ASCII))
                table?.let { saveTable(it) }
                runOnUiThread {
                    signals = table
                    table?.let { t ->
                        Session.programSaid(
                            "ELF разобран: образ ${FlashPlan.kb(t.image.sumOf { it.data.size })}, получена таблица сигналов — ${t.entries.size} значений " +
                                "(SHA-256 ELF ${t.elfSha256.take(12)}…, образа ${t.imageSha256.take(12)}…). Адреса применимы к цели, " +
                                "только если её Flash совпадает с образом: проверю при сверке или прошивке.",
                        )
                        t.warnings.forEach { w -> Session.programSaid("ELF: $w") }
                    }
                    segments = parsed
                    fileBytes = bytes.size
                    fileSha = IntelHex.sha256(bytes)
                    ui.file = name
                    ui.result = null
                    ui.busy = false
                    rebuildPlan()
                }
            } catch (e: Exception) {
                Session.programSaid("файл не прочитан: ${e.message}")
                runOnUiThread {
                    ui.busy = false
                    ui.status = "Файл не прочитан: ${e.message}"
                    ui.error = true
                }
            }
        }
    }

    /** ТЗ прошивки 5.1: план без обращения к памяти цели. */
    private fun rebuildPlan() {
        val segs = segments
        if (segs == null) {
            ui.plan = emptyList()
            ui.planAllowed = false
            return
        }
        val t = Session.target
        val p = FlashPlan.build(
            segs,
            PlanTarget(devId(), t?.flashSizeKb, voltage, mcu()),
            fileSha256 = fileSha,
            expectedSha256 = ui.expectedSha.takeIf { it.isNotBlank() },
        )
        plan = p
        ui.plan = buildList {
            add("Файл: ${ui.file}, $fileBytes байт")
            addAll(p.lines())
            if (!ui.backup) {
                add("Резервная копия перед прошивкой отключена")
            } else if (p.units.isNotEmpty()) {
                add("Резервная копия: ${FlashPlan.unitsText(p.units)}, ${FlashPlan.kb(p.units.sumOf { it.size })}")
            }
            signals?.let { add("Таблица сигналов из ELF: ${it.entries.size} значений — подключится, когда Flash цели совпадёт с образом") }
        }
        ui.planAllowed = p.allowed && Session.stlink != null
        ui.hasUnits = p.units.isNotEmpty()
    }

    private fun canWrite16(): Boolean {
        val v = Session.stlink?.version ?: return true
        return v.hw >= 3 || v.jtag >= 26
    }

    /** ТЗ прошивки 3.1.2: что увидит пользователь перед изменением цели. */
    private fun confirmText(p: FlashPlan, flash: Boolean): String = buildString {
        appendLine("MCU: ${mcu()}")
        appendLine("Адаптер: ${Session.device?.title ?: "?"}, ${Session.stlink?.version?.label ?: ""}")
        if (flash) appendLine("Файл: ${ui.file}, $fileBytes байт\nSHA-256: ${fileSha?.take(16)}…")
        appendLine("Стереть: ${FlashPlan.unitsText(p.units)}")
        if (p.bytesLost > 0) appendLine("Потеряется вне образа: ${FlashPlan.kb(p.bytesLost)}")
        if (flash) {
            val units = "${FlashPlan.unitsText(p.units)}, ${FlashPlan.kb(p.units.sumOf { it.size })}"
            appendLine(if (ui.backup) "Резервная копия: $units, в папку приложения; ядро останавливается на время копии" else "Резервная копия: ОТКЛЮЧЕНА")
            append(if (ui.run) "После прошивки: сброс и запуск" else "После прошивки: ядро остановлено")
        } else {
            append("После стирания ядро остаётся остановленным")
        }
    }

    private fun confirmFlash() {
        val p = plan ?: return
        Session.userSaid("нажал «Прошить»")
        ui.confirm = Confirm("Прошить?", confirmText(p, true)) {
            Session.userSaid("подтвердил прошивку ${ui.file}")
            start("прошивка") { f, pr -> f.flash(p, ui.backup, ui.run, pr) }
        }
    }

    private fun confirmEraseUnits() {
        val p = plan ?: return
        Session.userSaid("нажал «Стереть секторы плана»")
        ui.confirm = Confirm("Стереть секторы плана?", confirmText(p, false)) {
            Session.userSaid("подтвердил стирание: ${FlashPlan.unitsText(p.units)}")
            start("стирание") { f, pr -> f.erase(p.units, pr) }
        }
    }

    // ТЗ прошивки 3.1.3: два подтверждения, второе — словом СТЕРЕТЬ.
    private fun massConfirm() = Confirm(
        "Стереть всю Flash?",
        "MCU: ${mcu()}\nБудет стёрта вся Flash, программа на плате пропадёт. Option bytes не меняются.",
    ) { ui.massWord = "" }

    private fun massErase() {
        Session.userSaid("подтвердил полное стирание словом СТЕРЕТЬ")
        ui.massWord = null
        start("полное стирание") { f, pr -> f.erase(null, pr) }
    }

    private fun layout(): FlashLayout? = devId()?.let { FlashLayout.of(it, Session.target?.flashSizeKb) }

    private fun start(action: String, op: (Flasher, (FlashStep, Long, Long) -> Boolean) -> FlashOutcome) {
        if (ui.readOnly) return
        val st = Session.stlink ?: return
        val layout = layout()
        if (layout == null) {
            ui.status = "Семейство или размер Flash не поддерживаются: только STM32F1 и STM32F4."
            ui.error = true
            return
        }
        cancel = false
        ui.busy = true
        ui.result = null
        ui.progress = 0f
        val psize = FlashPlan.psizeFor(voltage)
        val hexName = ui.file
        val sha = fileSha
        val units = plan?.let { FlashPlan.unitsText(it.units) }
        val t0 = System.currentTimeMillis()
        Session.worker.execute {
            var last = 0L
            val progress = { step: FlashStep, done: Long, total: Long ->
                val now = System.currentTimeMillis()
                if (now - last >= 100 || done == total) {
                    last = now
                    runOnUiThread {
                        ui.step = step.title
                        ui.progress = if (total > 0) done.toFloat() / total else null
                    }
                }
                !cancel
            }
            Session.logLine("# изменение цели: $action…")
            st.stats.reset()
            val outcome = try {
                val f = Flasher(st, Session.targetWrite(), layout, psize, canWrite16())
                op(f, progress)
            } catch (e: Exception) {
                FlashOutcome(false, FlashErrorKind.CANCELLED, ErrorMessages.describe(e), null, 0, false, null)
            }
            val backupFile = outcome.backup?.let { saveBackup(it) }
            val state = try {
                CoreState(st.read32(CortexDebugRegs.DHCSR))
            } catch (_: Exception) {
                null
            }
            Session.logLine("# изменение цели: $action → ${if (outcome.ok) "успешно" else outcome.kind?.title}: ${outcome.message}")
            val entry = HistoryEntry(
                timeMs = t0,
                action = action,
                adapter = Session.device?.title ?: "?",
                adapterFirmware = st.version?.label ?: "?",
                mcu = mcu(),
                file = if (action == "прошивка") hexName else null,
                sha256 = if (action == "прошивка") sha else null,
                units = if (action == "полное стирание") "вся Flash" else units,
                durationMs = outcome.durationMs,
                result = if (outcome.ok) "успешно" else outcome.kind?.title ?: "ошибка",
                error = if (outcome.ok) null else outcome.message,
            )
            try {
                FlashHistory.append(this, entry)
            } catch (_: Exception) {
            }
            val details = buildList {
                add(outcome.message)
                outcome.hint?.let { add("Подсказка: $it") }
                outcome.mismatches.take(5).forEach { add("  %08X: ожидалось %02X, прочитано %02X".format(it.address, it.expected, it.actual)) }
                backupFile?.let { add("Резервная копия: ${it.name}") }
                if (outcome.ok && outcome.running) add("Проверьте работу программы кнопкой «ПУСК» в простом режиме.")
                if (outcome.steps.isNotEmpty()) add("Шаги:")
                outcome.steps.forEach { add("  ${it.text}") }
                st.stats.lines().forEach { add("  $it") }
            }
            Session.programSaid(
                "$action: ${if (outcome.ok) "успешно" else outcome.kind?.title}, ${"%.1f".format(Locale.ROOT, outcome.durationMs / 1000.0)} с. ${outcome.message}" +
                    (backupFile?.let { " Резервная копия: ${it.name}." } ?: ""),
            )
            outcome.steps.forEach { Session.programSaid("  ${it.text}") }
            runOnUiThread {
                if (outcome.ok && action == "прошивка") matchTable(plan?.imageSha256, "прошивки")
                ui.busy = false
                ui.progress = null
                ui.step = ""
                ui.result = details.joinToString("\n")
                ui.resultOk = outcome.ok
                ui.report = entry.report(details)
                Session.updateCore(state)
                sync()
            }
        }
    }

    // ТЗ прошивки 3.1.5, вопрос 10.2.2: резервная копия — в папку приложения.
    // Хвост 0xFF каждого участка отбрасывается: после стирания там и так 0xFF.
    private fun saveBackup(data: List<Segment>): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val name = (Session.target?.shortName ?: "mcu").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val used = data.map { Segment(it.address, FlashTools.trimErased(it.data)) }.filter { it.data.isNotEmpty() }
        File(FlashHistory.backupDir(this), "backup-$name-$stamp.hex").apply {
            writeText(IntelHex.write(used))
        }
    } catch (_: Exception) {
        null
    }

    /** ТЗ прошивки 5.3: сверка только чтением, без остановки ядра. */
    private fun check() {
        val st = Session.stlink ?: return
        val segs = segments ?: return
        cancel = false
        ui.busy = true
        ui.result = null
        ui.step = FlashStep.VERIFY.title
        ui.progress = 0f
        Session.userSaid("нажал «Сверить с MCU»")
        Session.worker.execute {
            st.stats.reset()
            val t0 = System.currentTimeMillis()
            val text = try {
                val r = FlashTools.check(segs, { a, n -> st.readBlock(a, n) }) { done, total ->
                    runOnUiThread {
                        ui.step = FlashStep.VERIFY.title
                        ui.progress = done.toFloat() / total
                    }
                    !cancel
                }
                if (r.ok) {
                    "Совпадает: ${r.bytes} байт."
                } else {
                    "Не совпадает: ${r.mismatches} из ${r.bytes} байт.\n" +
                        r.first.joinToString("\n") { "  %08X: в файле %02X, в MCU %02X".format(it.address, it.expected, it.actual) }
                }
            } catch (_: FlashTools.CancelledException) {
                "Сверка отменена."
            } catch (e: Exception) {
                ErrorMessages.describe(e)
            }
            val bytes = segs.sumOf { it.data.size }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val timing = listOf("Чтение: ${FlashPlan.kb(bytes)} за ${"%.1f".format(Locale.ROOT, sec)} с") + st.stats.lines()
            Session.programSaid("сверка: ${text.lines().first()} ${timing.first()}.")
            runOnUiThread {
                if (text.startsWith("Совпадает")) matchTable(plan?.imageSha256, "сверки") else if (signals != null) {
                    Session.programSaid("таблица сигналов не подключена: Flash цели отличается от образа ELF")
                }
                ui.busy = false
                ui.progress = null
                ui.result = text + "\n" + timing.joinToString("\n")
                ui.resultOk = text.startsWith("Совпадает")
                ui.report = null
                sync()
            }
        }
    }

    /** ТЗ прошивки 5.2.4: самостоятельная копия без остановки ядра и разрешения записи. */
    private fun askBackupFile() {
        if (Session.stlink == null || ui.busy) return
        if (Session.target?.flashSizeKb == null) {
            ui.status = "Размер Flash неизвестен — резервная копия недоступна."
            ui.error = true
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        createBackup.launch("backup_$stamp.hex")
    }

    private fun backupAll(uri: Uri) {
        val st = Session.stlink
        val kb = Session.target?.flashSizeKb
        if (st == null || kb == null || kb <= 0) {
            ui.status = "Для копии нужны подключение и известный размер Flash."
            ui.error = true
            return
        }
        Session.stopPolling()
        cancel = false
        ui.busy = true
        ui.step = "Резервная копия всей Flash"
        ui.progress = 0f
        ui.result = null
        ui.report = null
        Session.userSaid("сохранить резервную копию всей Flash ($kb КБ), без остановки ядра")
        Session.worker.execute {
            var ok = false
            val result = try {
                val total = kb * 1024
                val data = FlashTools.read(0x08000000L, total, { a, n -> st.readBlock(a, n) }) { done ->
                    runOnUiThread { ui.progress = done.toFloat() / total }
                    !cancel
                }
                val used = FlashTools.trimErased(data)
                val hex = IntelHex.write(if (used.isEmpty()) emptyList() else listOf(Segment(0x08000000L, used)))
                if (cancel) throw FlashTools.CancelledException()
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(hex.toByteArray(Charsets.US_ASCII)) }
                    ?: error("Файл не открылся для записи")
                ok = true
                "Сохранено ${MainActivity.displayName(this, uri)}: ${used.size} байт (Flash $kb КБ, хвост 0xFF отброшен).\n" +
                    "SHA-256 образа: ${IntelHex.sha256(used)}" +
                    if (used.isEmpty()) "\nFlash пустая: записан HEX с записью конца файла." else ""
            } catch (_: FlashTools.CancelledException) {
                "Резервная копия отменена. Файл назначения может остаться пустым."
            } catch (e: Exception) {
                "Резервная копия: ${ErrorMessages.describe(e)}"
            }
            Session.programSaid(result)
            runOnUiThread {
                ui.busy = false
                ui.progress = null
                ui.step = ""
                ui.result = result
                ui.resultOk = ok
                ui.report = result
                sync()
            }
        }
    }

    /** ТЗ прошивки 5.9.3 (р.1.4): таблица подключается, только если Flash цели совпала с образом ELF. */
    private fun matchTable(imageSha: String?, how: String) {
        val t = signals ?: return
        if (imageSha != t.imageSha256) return
        try {
            val json = t.toAddressesJson()
            val file = AddressesFile.parse(json)
            val name = "${t.elfName} (ELF, ${mcu()})"
            File(filesDir, MainActivity.SAVED_FIELDS).writeText(json, Charsets.UTF_8)
            File(filesDir, MainActivity.SAVED_FIELDS_NAME).writeText(name, Charsets.UTF_8)
            Session.setFields(file, name)
            Session.programSaid(
                "по итогам $how Flash цели совпадает с образом ELF (SHA-256 ${t.imageSha256.take(12)}…) — " +
                    "таблица сигналов подключена: ${file.fields.size} полей. Значения — в режиме эксперта, графики — по нажатию на поле.",
            )
            ui.status = "Таблица сигналов из ELF подключена: ${file.fields.size} полей."
            ui.error = false
        } catch (e: Exception) {
            Session.programSaid("таблица сигналов не подключена: ${e.message}")
        }
    }

    /** Таблица из ELF хранится по отпечатку образа: `signals/<sha>.json`. */
    private fun saveTable(t: SignalTable) {
        try {
            val dir = File(filesDir, "signals").apply { mkdirs() }
            File(dir, "${t.imageSha256.take(16)}.json").writeText(t.toAddressesJson(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // ТЗ прошивки 5.7.2
    private fun share() {
        val text = ui.report ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Отправить отчёт"))
    }

    private fun leave(page: WorkspacePage? = null) {
        if (ui.busy) {
            ui.status = "Дождитесь окончания операции или нажмите «Отмена»."
            return
        }
        ui.nextPage = page
        if (!ui.readOnly && Session.core?.halted == true && Session.writeMode && Session.stlink != null) ui.askLeave = true else completeLeave()
    }

    private fun leaveRunning() {
        if (ui.readOnly) { completeLeave(); return }
        ui.busy = true
        Session.worker.execute {
            try {
                Session.updateCore(Session.targetWrite().detach())
                Session.logLine("# изменение цели: запуск перед уходом")
                runOnUiThread { ui.busy = false; completeLeave() }
            } catch (e: Exception) {
                Session.logLine("# изменение цели: запуск перед уходом — ошибка: ${e.message}")
                runOnUiThread { ui.busy = false; ui.error = true; ui.status = ErrorMessages.describe(e) }
            }
        }
    }

    private fun completeLeave() {
        val page = ui.nextPage
        ui.nextPage = null
        if (ui.readOnly) finish()
        else if (page == null) WorkspaceNavigation.home(this)
        else WorkspaceNavigation.open(this, page)
    }

    companion object {
        const val EXTRA_READ_ONLY = "read_only_tools"
    }
}

/** Обработчики экрана «Прошивка». */
class FlashActions(
    val back: () -> Unit,
    val connect: () -> Unit,
    val pickFile: () -> Unit,
    val backupAll: () -> Unit,
    val expectedSha: (String) -> Unit,
    val writeMode: (Boolean) -> Unit,
    val backup: (Boolean) -> Unit,
    val run: (Boolean) -> Unit,
    val check: () -> Unit,
    val flash: () -> Unit,
    val eraseUnits: () -> Unit,
    val massErase: () -> Unit,
    val massEraseConfirmed: () -> Unit,
    val cancel: () -> Unit,
    val share: () -> Unit,
    val clearHistory: () -> Unit,
    val leave: (Boolean) -> Unit,
)

@Composable
fun FlashScreen(ui: FlashUi, actions: FlashActions) {
    val status = LocalStatusColors.current
    HwScreen(title = "Прошивка", onBack = actions.back, navigationEnabled = !ui.busy) {
        if (ui.status.isNotEmpty()) {
            Text(ui.status, color = if (ui.error) status.bad else MaterialTheme.colorScheme.onBackground)
        }
        if (!ui.connected) {
            Button(onClick = actions.connect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Подключить и опознать") }
        } else {
            InfoCard("Микроконтроллер", ui.target.lines().filter { it.isNotBlank() }, mono = true)
        }

        SectionCard("Резервная копия и сверка · только чтение") {
            Text("Ядро продолжает работать. Режим «Изменение цели» не требуется.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.backupAll, enabled = ui.connected && !ui.busy) { Text("Сохранить всю Flash в HEX…") }
            OutlinedButton(onClick = actions.pickFile, enabled = !ui.busy) { Text(if (ui.file == null) "Выбрать HEX или ELF…" else "Другой файл…") }
            OutlinedTextField(
                value = ui.expectedSha,
                onValueChange = actions.expectedSha,
                label = { Text("Ожидаемый SHA-256 (необязательно)") },
                singleLine = true,
                textStyle = MonoText,
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.plan.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (line in ui.plan) {
                        val bad = line.startsWith("Отказ") || line.startsWith("Внимание")
                        Text(line, style = MonoText, color = if (bad) status.bad else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            OutlinedButton(onClick = actions.check, enabled = ui.connected && ui.file != null && !ui.busy) { Text("Сверить с MCU") }
        }

        if (!ui.readOnly) SectionCard("Изменение цели") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = ui.writeMode, onCheckedChange = actions.writeMode, enabled = !ui.busy)
                Text("Режим «Изменение цели»", style = MaterialTheme.typography.bodyLarge)
            }
            CheckRow("Резервная копия перед прошивкой", ui.backup, actions.backup)
            CheckRow("Запустить после прошивки", ui.run, actions.run)
            val enabled = ui.connected && ui.writeMode && !ui.busy
            Button(onClick = actions.flash, enabled = enabled && ui.planAllowed, modifier = Modifier.fillMaxWidth()) { Text("Прошить") }
            ButtonRow(
                "Стереть секторы плана" to actions.eraseUnits,
                enabled = enabled && ui.planAllowed && ui.hasUnits,
            )
            OutlinedButton(onClick = actions.massErase, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Полное стирание…") }
        }

        if (ui.busy && ui.step.isNotEmpty()) {
            SectionCard("Выполняется: ${ui.step}") {
                val p = ui.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = actions.cancel) { Text("Отмена") }
            }
        }

        ui.result?.let { r ->
            InfoCard("Результат", r.lines(), mono = true, accent = if (ui.resultOk) status.ok else status.bad)
            if (ui.report != null) OutlinedButton(onClick = actions.share) { Text("Отправить отчёт") }
        }

        if (ui.dialog.isNotEmpty()) {
            InfoCard("Журнал: действия и ответы", ui.dialog.map { it.removePrefix("# ") }, mono = true)
        }

        SectionCard("История") {
            if (ui.history.isEmpty()) {
                Text("Пока пусто", color = status.muted)
            } else {
                for (h in ui.history) Text(h, style = MonoText)
                TextButton(onClick = { ui.askClear = true }) { Text("Очистить историю") }
            }
        }
    }

    ui.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { ui.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text, style = MonoText) },
            confirmButton = {
                TextButton(onClick = {
                    ui.confirm = null
                    c.action()
                }) { Text("Выполнить") }
            },
            dismissButton = { TextButton(onClick = { ui.confirm = null }) { Text("Отмена") } },
        )
    }
    ui.massWord?.let { word ->
        AlertDialog(
            onDismissRequest = { ui.massWord = null },
            title = { Text("Второе подтверждение") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Введите слово СТЕРЕТЬ, чтобы стереть всю Flash.")
                    OutlinedTextField(value = word, onValueChange = { ui.massWord = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = actions.massEraseConfirmed, enabled = word.trim() == "СТЕРЕТЬ") { Text("Стереть всё") }
            },
            dismissButton = { TextButton(onClick = { ui.massWord = null }) { Text("Отмена") } },
        )
    }
    if (ui.askClear) {
        AlertDialog(
            onDismissRequest = { ui.askClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Записи об операциях будут удалены. Резервные копии останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    ui.askClear = false
                    actions.clearHistory()
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { ui.askClear = false }) { Text("Отмена") } },
        )
    }
    if (ui.askLeave) {
        AlertDialog(
            onDismissRequest = { ui.askLeave = false },
            title = { Text("Ядро остановлено") },
            text = { Text("Запустить ядро перед уходом с экрана? По умолчанию — запустить.") },
            confirmButton = { TextButton(onClick = { actions.leave(true) }) { Text("Запустить") } },
            dismissButton = { TextButton(onClick = { actions.leave(false) }) { Text("Оставить остановленным") } },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

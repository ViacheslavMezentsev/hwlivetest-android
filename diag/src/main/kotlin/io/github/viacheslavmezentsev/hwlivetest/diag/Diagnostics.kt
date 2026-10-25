package io.github.viacheslavmezentsev.hwlivetest.diag

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkErrorKind
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkException
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.CortexCore
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DebugMemory
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.McuVendor
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.Stm32
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32

/** Что известно об адаптере к началу проверки. */
data class AdapterFacts(val title: String, val firmware: String, val voltage: Double?)

/**
 * Эвристики кнопки «Пуск»: жив ли MCU, выполняет ли ядро код, где оно находится, есть ли
 * прошивка, защищена ли Flash и от чего тактируется. Только чтение: ядро не
 * останавливается и регистры не меняются. Единственный побочный эффект — чтение DHCSR
 * сбрасывает его «липкие» флаги S_RETIRE_ST и S_RESET_ST, что видно только отладчику.
 *
 * Подробные эвристики тактирования и защиты — для STM32F1, F4 и G4; для остальных
 * Cortex-M выполняются общие проверки.
 */
class Diagnostics(
    private val mem: DebugMemory,
    private val target: TargetInfo,
    private val adapter: AdapterFacts? = null,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val progress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
) {
    private val core: CortexCore? = target.cpuId?.core
    private val profile = target.profile
    private val baseline: Boolean = core?.isBaseline ?: false

    val family: Stm32Clocks.Family? = when {
        !profile.peripheralDiagnostics -> null
        else -> when (profile.diagnosticFamily) {
            Stm32.Family.F1 -> Stm32Clocks.Family.F1
            Stm32.Family.F4 -> Stm32Clocks.Family.F4
            Stm32.Family.G4 -> Stm32Clocks.Family.G4
            else -> null
        }
    }

    /** Наблюдения для итоговой строки. */
    private var coreState = "?"
    private var firmwareState = "?"
    private var clockState = ""

    fun run(): DiagReport {
        val steps: List<Pair<String, () -> Section>> = listOf(
            "Связь" to ::link,
            "Ядро" to ::coreSection,
            "Исключения и сбои" to ::faults,
            "Где выполняется код" to ::pcSection,
            "Прошивка" to ::firmware,
            "Защита и опции" to ::protection,
            "Тактирование" to ::clocks,
        )
        val sections = ArrayList<Section>()
        steps.forEachIndexed { i, (title, check) ->
            progress(i, steps.size, title)
            sections += try {
                check()
            } catch (e: StLinkException) {
                if (e.kind == StLinkErrorKind.USB_IO || e.kind == StLinkErrorKind.PROTOCOL) throw e
                Section(title, listOf(Finding(Level.WARN, "Проверка прервана", e.message ?: "")))
            }
        }
        progress(steps.size, steps.size, "Готово")
        val summary = listOf("MCU отвечает", coreState, firmwareState, clockState)
            .filter { it.isNotEmpty() && it != "?" }.joinToString(" · ")
        return DiagReport(sections, summary)
    }

    // ---------- Чтение без исключений ----------

    private val errors = HashMap<Long, String>()

    private fun rd(a: Long): Int? = try {
        mem.read32(a)
    } catch (e: StLinkException) {
        if (e.kind == StLinkErrorKind.USB_IO || e.kind == StLinkErrorKind.PROTOCOL) throw e
        errors[a] = e.message ?: "ошибка"
        null
    }

    private fun bit(v: Int, n: Int) = (v ushr n) and 1 == 1

    // ---------- Разделы ----------

    private fun link(): Section {
        val f = mutableListOf<Finding>()
        adapter?.let {
            f += Finding(Level.OK, "${it.title}, прошивка ${it.firmware}")
            when (val v = it.voltage) {
                null -> f += Finding(Level.INFO, "Адаптер не измеряет напряжение цели")
                else -> f += if (v < 1.6) {
                    Finding(Level.WARN, "Напряжение цели %.2f В — мало".format(v), "Плата не запитана или не подключён VREF/3V3.")
                } else {
                    Finding(Level.OK, "Напряжение цели %.2f В".format(v))
                }
            }
        }
        f += Finding(Level.INFO, "Профиль: ${profile.title}",
            if (target.profileOverride != null) "Выбран пользователем; совместимость и размеры памяти не подтверждены автоматически." else "Выбран автоматически по результатам опознания.")
        val name = target.shortName
        f += if (target.cpuId != null) {
            Finding(Level.OK, "MCU отвечает по SWD: $name, ${target.cpuId?.coreName}")
        } else {
            Finding(Level.WARN, "MCU отвечает на уровне DP, но CPUID не прочитан", "Шина занята или ядро в режиме сна.")
        }
        target.flashSizeKb?.let { f += Finding(Level.INFO, "Flash $it КБ" + (target.sramKb?.let { s -> ", SRAM до $s КБ" } ?: "")) }
        return Section("Связь и микроконтроллер", f)
    }

    private fun coreSection(): Section {
        val f = mutableListOf<Finding>()
        if (rd(DHCSR) == null) {
            coreState = "состояние ядра неизвестно"
            return Section("Ядро", listOf(Finding(Level.WARN, "DHCSR не читается", errors[DHCSR] ?: "")))
        }
        val samples = ArrayList<Int>()
        repeat(CORE_SAMPLES) {
            sleep(CORE_SAMPLE_MS)
            rd(DHCSR)?.let { samples += it }
        }
        val retired = samples.any { bit(it, 24) }
        val resets = samples.count { bit(it, 25) }
        val halted = samples.any { bit(it, 17) }
        val lockup = samples.any { bit(it, 19) }
        val sleeping = samples.count { bit(it, 18) }
        val debugEn = samples.any { bit(it, 0) }
        val span = CORE_SAMPLES * CORE_SAMPLE_MS
        when {
            lockup -> {
                coreState = "ядро в LOCKUP"
                f += Finding(Level.BAD, "LOCKUP: ядро заблокировано", "Ошибка внутри обработчика HardFault или NMI. Нужен сброс.")
            }
            halted -> {
                coreState = "ядро остановлено"
                f += Finding(
                    Level.WARN, "Ядро остановлено (S_HALT)",
                    "Его остановил отладчик или точка останова. HW Live Test только читает и не запускает ядро.",
                )
            }
            resets >= 2 -> {
                coreState = "ядро постоянно сбрасывается"
                f += Finding(
                    Level.BAD, "Ядро сбрасывается снова и снова: $resets раз за $span мс",
                    "Watchdog, просадка питания (BOR) или сбой в самом начале прошивки.",
                )
            }
            retired -> {
                coreState = "ядро работает"
                f += Finding(Level.OK, "Ядро выполняет инструкции")
                if (sleeping * 2 > samples.size) f += Finding(Level.INFO, "Большую часть времени спит (WFI/WFE) — обычно для RTOS и прошивок с экономией энергии")
            }
            sleeping > 0 -> {
                coreState = "ядро спит"
                f += Finding(Level.INFO, "Ядро спит (WFI/WFE) и не просыпалось за $span мс")
            }
            else -> {
                coreState = "ядро не выполняет инструкции"
                f += Finding(Level.WARN, "За $span мс не выполнено ни одной инструкции", "Ядро не тактируется или застряло на шине.")
            }
        }
        if (resets == 1) f += Finding(Level.WARN, "Во время проверки был сброс")
        f += Finding(Level.INFO, if (debugEn) "Отладка ядра включена (C_DEBUGEN)" else "Отладка ядра выключена (C_DEBUGEN = 0)")
        f += Finding(Level.INFO, "DHCSR: " + samples.distinct().take(3).joinToString { it.hex32() })
        return Section("Ядро", f)
    }

    private fun faults(): Section {
        val f = mutableListOf<Finding>()
        val active = ArrayList<Int>()
        repeat(5) {
            rd(ICSR)?.let { active += it and 0x1FF }
            sleep(5)
        }
        if (active.isEmpty()) {
            f += Finding(Level.WARN, "ICSR не читается")
        } else {
            val stuck = active.distinct().singleOrNull()
            if (stuck != null && stuck in 2..6) {
                f += Finding(Level.BAD, "Ядро всё время в обработчике ${exceptionName(stuck)}", "Прошивка, скорее всего, зависла в обработчике сбоя.")
                coreState = "ядро в обработчике ${exceptionName(stuck)}"
            } else {
                f += Finding(Level.INFO, "Активные исключения: " + active.distinct().joinToString { exceptionName(it) })
            }
        }
        if (baseline || core == null) {
            f += Finding(Level.SKIP, "CFSR/HFSR не проверяются для ${core?.title ?: "неопознанного ядра"}")
            return Section("Исключения и сбои", f)
        }
        val cfsr = rd(CFSR)
        val hfsr = rd(HFSR)
        if (cfsr == null || hfsr == null) {
            f += Finding(Level.WARN, "CFSR/HFSR не читаются")
        } else if (cfsr == 0 && hfsr == 0) {
            f += Finding(Level.OK, "Флагов сбоев нет (CFSR = HFSR = 0)")
        } else {
            val names = cfsrFlags(cfsr) + hfsrFlags(hfsr)
            val addr = buildList {
                if (bit(cfsr, 7)) rd(MMFAR)?.let { add("MMFAR = ${it.hex32()}") }
                if (bit(cfsr, 15)) rd(BFAR)?.let { add("BFAR = ${it.hex32()}") }
            }
            f += Finding(
                Level.WARN, "Были сбои: " + names.joinToString(", "),
                "CFSR = ${cfsr.hex32()}, HFSR = ${hfsr.hex32()}" + (if (addr.isEmpty()) "" else "\n" + addr.joinToString()) +
                    "\nФлаги «липкие»: сбой мог случиться давно, если прошивка его обработала.",
            )
        }
        return Section("Исключения и сбои", f)
    }

    private fun pcSection(): Section {
        if (core !in listOf(CortexCore.M3, CortexCore.M4, CortexCore.M7)) return Section("Где выполняется код", listOf(Finding(Level.SKIP, "Выборка PC не поддержана для ${core?.title ?: "неопознанного ядра"}")))
        val demcr = rd(DEMCR) ?: return Section("Где выполняется код", listOf(Finding(Level.WARN, "DEMCR не читается")))
        if (!bit(demcr, 24)) {
            return Section(
                "Где выполняется код",
                listOf(Finding(Level.INFO, "Выборка PC недоступна: DEMCR.TRCENA = 0", "Включить её можно только записью в DEMCR.")),
            )
        }
        val pcs = ArrayList<Long>()
        repeat(PC_SAMPLES) {
            rd(DWT_PCSR)?.let { if (it != -1) pcs += it.toLong() and 0xFFFFFFFFL }
            sleep(PC_SAMPLE_MS)
        }
        if (pcs.isEmpty()) {
            return Section("Где выполняется код", listOf(Finding(Level.INFO, "DWT_PCSR не даёт выборок (ядро остановлено или PCSR нет)")))
        }
        val byRegion = pcs.groupBy { region(it) }
        val main = byRegion.maxByOrNull { it.value.size }!!.key
        val range = "${pcs.min().hex32()}…${pcs.max().hex32()}"
        val f = mutableListOf<Finding>()
        f += when (main) {
            "Flash" -> Finding(Level.OK, "PC в Flash: работает прошивка", "${pcs.size} выборок, $range")
            "системная память" -> Finding(Level.WARN, "PC в системной памяти: работает встроенный загрузчик ST", "Обычно это BOOT0 = 1. $range")
            "SRAM" -> Finding(Level.INFO, "PC в SRAM: код выполняется из ОЗУ", range)
            else -> Finding(if (profile.stm32Layout) Level.WARN else Level.INFO, "PC в области «$main»", range)
        }
        if (pcs.distinct().size == 1) f += Finding(Level.INFO, "PC не меняется (${pcs[0].hex32()}): цикл ожидания или WFI")
        return Section("Где выполняется код", f)
    }

    private fun firmware(): Section {
        // ТЗ ST-Link 5.13: неизвестная карта не означает пустую Flash.
        if (!profile.stm32Layout) return Section("Прошивка", listOf(Finding(Level.SKIP,
            "Карта Flash не определена", "Общий профиль Cortex-M: начало Flash, размер и загрузочная схема не предполагаются. Доступно чтение сигналов по адресам из файла.")))
        val f = mutableListOf<Finding>()
        val sp = rd(FLASH_BASE)
        val reset = rd(FLASH_BASE + 4)
        if (sp == null || reset == null) {
            firmwareState = "Flash не читается"
            f += Finding(Level.WARN, "Flash не читается", (errors[FLASH_BASE] ?: errors[FLASH_BASE + 4] ?: "") + "\nВозможно, включена защита чтения (RDP).")
            return Section("Прошивка", f)
        }
        val erased = sp == -1 && reset == -1
        when {
            erased -> {
                firmwareState = "Flash чистая"
                f += Finding(Level.WARN, "Flash стёрта: прошивки нет", "Первые слова 0xFFFFFFFF.")
            }
            looksLikeVectors(sp, reset) -> {
                firmwareState = "прошивка есть"
                f += Finding(Level.OK, "Таблица векторов на месте", "SP = ${sp.hex32()}, Reset = ${reset.hex32()}")
            }
            else -> {
                firmwareState = "в Flash не таблица векторов"
                f += Finding(Level.WARN, "В начале Flash нет похожей таблицы векторов", "SP = ${sp.hex32()}, Reset = ${reset.hex32()}")
            }
        }
        // Что отображено на адрес 0 — по нему видно, откуда загрузился MCU.
        val a0 = rd(0L)
        val a4 = rd(4L)
        val sys = family?.let { systemMemory(it) }
        val s0 = sys?.let { rd(it) }
        val s4 = sys?.let { rd(it + 4) }
        if (a0 != null && a4 != null) {
            f += when {
                a0 == sp && a4 == reset && !erased -> Finding(Level.OK, "Загрузка из Flash (BOOT0 = 0)")
                s0 != null && a0 == s0 && a4 == s4 -> Finding(Level.WARN, "Загрузка из системной памяти (BOOT0 = 1)", "Запущен встроенный загрузчик ST, а не прошивка.")
                else -> Finding(Level.INFO, "По адресу 0: ${a0.hex32()} ${a4.hex32()}", "Не совпадает ни с Flash, ни с системной памятью: загрузка из SRAM или другая схема.")
            }
        }
        if (!baseline) {
            rd(VTOR)?.let { vtor ->
                val v = vtor.toLong() and 0xFFFFFFFFL
                f += when {
                    v == 0L || v == FLASH_BASE -> Finding(Level.INFO, "VTOR = ${v.hex32()}")
                    region(v) == "Flash" -> {
                        val r = rd(v + 4)
                        Finding(Level.INFO, "Таблица векторов перенесена: VTOR = ${v.hex32()}", "Похоже на загрузчик + приложение. Reset приложения = ${r?.hex32() ?: "?"}")
                    }
                    else -> Finding(Level.INFO, "VTOR = ${v.hex32()} (${region(v)})")
                }
            }
        }
        return Section("Прошивка", f)
    }

    private fun protection(): Section {
        val fam = family ?: return Section("Защита и опции", listOf(Finding(Level.SKIP, "Нет эвристик для ${target.shortName}")))
        val f = mutableListOf<Finding>()
        when (fam) {
            Stm32Clocks.Family.F1 -> rd(0x4002201CL)?.let { obr ->
                f += if (bit(obr, 1)) Finding(Level.BAD, "Защита чтения Flash включена (RDPRT)", "FLASH_OBR = ${obr.hex32()}")
                else Finding(Level.OK, "Защиты чтения нет", "FLASH_OBR = ${obr.hex32()}")
                if (!bit(obr, 2)) f += watchdogHw()
            }
            Stm32Clocks.Family.F4 -> rd(0x40023C14L)?.let { optcr ->
                f += rdp((optcr ushr 8) and 0xFF, "FLASH_OPTCR = ${optcr.hex32()}")
                if (!bit(optcr, 5)) f += watchdogHw()
            }
            Stm32Clocks.Family.G4 -> rd(0x40022020L)?.let { optr ->
                f += rdp(optr and 0xFF, "FLASH_OPTR = ${optr.hex32()}")
                if (!bit(optr, 16)) f += watchdogHw()
            }
        }
        if (f.isEmpty()) f += Finding(Level.WARN, "Регистры опций не читаются")
        // DBGMCU_CR: держит ли MCU отладку во сне. Без этого бывают ответы WAIT.
        rd(0xE0042004L)?.let { cr ->
            val bits = listOfNotNull(
                "SLEEP".takeIf { bit(cr, 0) }, "STOP".takeIf { bit(cr, 1) }, "STANDBY".takeIf { bit(cr, 2) },
            )
            f += Finding(
                Level.INFO,
                if (bits.isEmpty()) "Отладка в режимах сна не включена (DBGMCU_CR = 0)" else "Отладка в режимах сна: ${bits.joinToString()}",
                if (bits.isEmpty()) "Пока ядро спит, чтение может отвечать WAIT — HW Live Test повторяет запрос." else "",
            )
        }
        return Section("Защита и опции", f)
    }

    private fun rdp(v: Int, detail: String) = when (v) {
        0xAA -> Finding(Level.OK, "Защиты чтения нет (RDP уровень 0)", detail)
        0xCC -> Finding(Level.BAD, "RDP уровень 2: отладка навсегда закрыта", detail)
        else -> Finding(Level.BAD, "Защита чтения Flash включена (RDP уровень 1)", detail)
    }

    private fun watchdogHw() = Finding(
        Level.WARN, "IWDG запускается аппаратно (опция WDG_SW = 0)",
        "Сторожевой таймер работает сразу после сброса; при остановке ядра MCU сбросится.",
    )

    private fun clocks(): Section {
        val f = mutableListOf<Finding>()
        val systick = sysTick(f)
        val fam = family
        if (fam == null) {
            f += Finding(Level.SKIP, "Разбор RCC — только для STM32F1, F4, G4")
            systick?.let { clockState = "HCLK ≈ ${Stm32Clocks.mhz(it)}" }
            return Section("Тактирование", f)
        }
        val r = Stm32Clocks.regs(fam)
        val cr = rd(r.cr)
        val cfgr = rd(r.cfgr)
        val pll = r.pllcfgr?.let { rd(it) }
        if (cr == null || cfgr == null || (r.pllcfgr != null && pll == null)) {
            f += Finding(Level.WARN, "Регистры RCC не читаются")
            return Section("Тактирование", f)
        }
        val t = Stm32Clocks.decode(fam, cr, cfgr, pll)
        // HSE из SysTick: при тике 1 кГц HCLK = (RVR + 1) × 1000.
        val hseEst = if (t.usesHse && systick != null && t.hseFactor!! > 0) {
            val raw = systick * t.ahbDiv / t.hseFactor
            Stm32Clocks.roundHse(raw)
        } else {
            null
        }
        val sys = t.sysclkFor(hseEst)
        val hclk = sys?.let { it / t.ahbDiv }
        f += Finding(
            Level.OK, "SYSCLK: ${t.source}" + (sys?.let { " = ${Stm32Clocks.mhz(it)}" } ?: ""),
            t.formula + (if (t.usesHse) (hseEst?.let { "\nHSE ≈ ${Stm32Clocks.mhz(it)} (оценка по SysTick)" } ?: "\nЧастота HSE неизвестна") else "") +
                (hclk?.let { "\nHCLK = ${Stm32Clocks.mhz(it)} (AHB / ${t.ahbDiv})" } ?: ""),
        )
        if (t.hseOn && !t.hseReady) f += Finding(Level.BAD, "HSE включён, но не готов: кварц не запустился")
        if (t.pllOn && !t.pllReady) f += Finding(Level.BAD, "PLL включён, но не захватил частоту")
        if (t.source == "HSI" && !t.hseOn && !t.pllOn) {
            f += Finding(Level.INFO, "Работает от внутреннего HSI", "Так бывает у чистого MCU и у прошивки, которая не настраивает тактирование.")
        }
        if (t.hseReady && !t.usesHse) f += Finding(Level.INFO, "HSE готов, но SYSCLK от него не берётся")
        if (systick != null && hclk != null && t.sysclkHz != null && kotlin.math.abs(hclk - systick) / hclk > 0.05) {
            f += Finding(Level.INFO, "SysTick не 1 кГц: оценка ${Stm32Clocks.mhz(systick)} против HCLK ${Stm32Clocks.mhz(hclk)}")
        }
        clockState = t.source + (sys?.let { " ${Stm32Clocks.mhz(it)}" } ?: "")
        return Section("Тактирование", f)
    }

    /** SysTick: идёт ли и какой HCLK даёт при тике 1 кГц. Возвращает оценку HCLK, Гц. */
    private fun sysTick(f: MutableList<Finding>): Double? {
        val csr = rd(SYST_CSR) ?: return null.also { f += Finding(Level.WARN, "SysTick не читается") }
        val rvr = rd(SYST_RVR) ?: return null
        val c1 = rd(SYST_CVR)
        sleep(3)
        val c2 = rd(SYST_CVR)
        if (!bit(csr, 0)) {
            f += Finding(Level.INFO, "SysTick выключен")
            return null
        }
        val ticking = c1 != null && c2 != null && c1 != c2
        val reload = (rvr.toLong() and 0xFFFFFF) + 1
        if (!profile.peripheralDiagnostics) {
            f += Finding(if (ticking) Level.OK else Level.INFO,
                if (ticking) "SysTick идёт" else "Движение SysTick не обнаружено",
                "RVR + 1 = $reload; источник: ${if (bit(csr, 2)) "тактовая частота ядра" else "внешний опорный сигнал"}. Период тика и частота источника неизвестны.")
            return null
        }
        val est = reload * 1000.0 * if (bit(csr, 2)) 1 else 8
        f += Finding(
            if (ticking) Level.OK else Level.WARN,
            if (ticking) "SysTick идёт" else "SysTick включён, но счётчик стоит",
            "RVR + 1 = $reload, источник ${if (bit(csr, 2)) "HCLK" else "HCLK/8"}; при тике 1 кГц HCLK ≈ ${Stm32Clocks.mhz(est)}",
        )
        return if (ticking) est else null
    }

    // ---------- Вспомогательное ----------

    private fun looksLikeVectors(sp: Int, reset: Int): Boolean {
        val s = sp.toLong() and 0xFFFFFFFFL
        val r = reset.toLong() and 0xFFFFFFFFL
        val spOk = region(s) == "SRAM" || (s in 0x10000000L..0x1000FFFFL) || region(s) == "SRAM (выше конца)"
        val resetOk = (r and 1L) == 1L && (region(r) == "Flash" || r < 0x08000000L)
        return spOk && resetOk
    }

    private fun region(a: Long): String {
        if (!profile.stm32Layout) return when (a) {
            in 0L..0x1FFFFFFFL -> "область кода (тип памяти неизвестен)"
            in 0x20000000L..0x3FFFFFFFL -> "область SRAM (размер неизвестен)"
            else -> a.hex32()
        }
        val flashEnd = FLASH_BASE + (target.flashSizeKb ?: 2048) * 1024L
        val sramEnd = 0x20000000L + (target.sramKb?.let { it * 1024L + 4 } ?: 0x00100000L)
        val sys = family?.let { systemMemory(it) }
        return when {
            a in FLASH_BASE until flashEnd -> "Flash"
            sys != null && a in sys until sys + 0x8000L -> "системная память"
            a in 0x1FFF0000L..0x1FFFFFFFL -> "системная память"
            a in 0x20000000L..sramEnd -> "SRAM"
            a in 0x20000000L..0x3FFFFFFFL -> "SRAM (выше конца)"
            a in 0x10000000L..0x1000FFFFL -> "CCM"
            a < 0x08000000L -> "алиас загрузки"
            else -> a.hex32()
        }
    }

    private fun systemMemory(f: Stm32Clocks.Family): Long = when (f) {
        Stm32Clocks.Family.F1 -> 0x1FFFF000L
        Stm32Clocks.Family.F4 -> 0x1FFF0000L
        Stm32Clocks.Family.G4 -> 0x1FFF0000L
    }

    companion object {
        const val FLASH_BASE = 0x08000000L
        const val ICSR = 0xE000ED04L
        const val VTOR = 0xE000ED08L
        const val CFSR = 0xE000ED28L
        const val HFSR = 0xE000ED2CL
        const val MMFAR = 0xE000ED34L
        const val BFAR = 0xE000ED38L
        const val DHCSR = 0xE000EDF0L
        const val DEMCR = 0xE000EDFCL
        const val DWT_PCSR = 0xE000101CL
        const val SYST_CSR = 0xE000E010L
        const val SYST_RVR = 0xE000E014L
        const val SYST_CVR = 0xE000E018L

        const val CORE_SAMPLES = 8
        const val CORE_SAMPLE_MS = 15L
        const val PC_SAMPLES = 12
        const val PC_SAMPLE_MS = 7L

        fun exceptionName(n: Int): String = when (n) {
            0 -> "поток (нет исключения)"
            1 -> "Reset"
            2 -> "NMI"
            3 -> "HardFault"
            4 -> "MemManage"
            5 -> "BusFault"
            6 -> "UsageFault"
            11 -> "SVCall"
            12 -> "DebugMonitor"
            14 -> "PendSV"
            15 -> "SysTick"
            in 16..511 -> "IRQ ${n - 16}"
            else -> "исключение $n"
        }

        fun cfsrFlags(v: Int): List<String> = listOf(
            0 to "IACCVIOL", 1 to "DACCVIOL", 3 to "MUNSTKERR", 4 to "MSTKERR", 5 to "MLSPERR",
            8 to "IBUSERR", 9 to "PRECISERR", 10 to "IMPRECISERR", 11 to "UNSTKERR", 12 to "STKERR", 13 to "LSPERR",
            16 to "UNDEFINSTR", 17 to "INVSTATE", 18 to "INVPC", 19 to "NOCP", 24 to "UNALIGNED", 25 to "DIVBYZERO",
        ).filter { (b, _) -> (v ushr b) and 1 == 1 }.map { it.second }

        fun hfsrFlags(v: Int): List<String> = listOf(1 to "VECTTBL", 30 to "FORCED", 31 to "DEBUGEVT")
            .filter { (b, _) -> (v ushr b) and 1 == 1 }.map { it.second }
    }
}

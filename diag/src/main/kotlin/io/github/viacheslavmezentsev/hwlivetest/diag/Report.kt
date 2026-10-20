package io.github.viacheslavmezentsev.hwlivetest.diag

/** Оценка отдельного наблюдения. Порядок — от лучшего к худшему. */
enum class Level(val mark: String) {
    OK("✓"),
    INFO("i"),
    SKIP("–"),
    WARN("!"),
    BAD("✗"),
}

data class Finding(val level: Level, val title: String, val detail: String = "")

/** Раздел отчёта, как пункт списка в AIDA64: заголовок, итог и подробности. */
data class Section(val title: String, val findings: List<Finding>) {
    /**
     * Итог раздела: худшее из WARN/BAD, иначе OK, если есть хоть одно OK, иначе INFO.
     * Справочные INFO не должны «перебивать» положительный результат.
     */
    val level: Level get() = summarize(findings.map { it.level })

    /** Главное наблюдение для строки списка: первое с уровнем итога. */
    val headline: String
        get() = findings.firstOrNull { it.level == level }?.title ?: findings.firstOrNull()?.title ?: ""

    companion object {
        fun summarize(levels: List<Level>): Level = when {
            Level.BAD in levels -> Level.BAD
            Level.WARN in levels -> Level.WARN
            Level.OK in levels -> Level.OK
            Level.INFO in levels -> Level.INFO
            else -> Level.SKIP
        }
    }
}

data class DiagReport(val sections: List<Section>, val summary: String) {
    val level: Level get() = Section.summarize(sections.map { it.level })

    /** Текст для журнала и «Копировать». */
    fun text(): String = buildString {
        appendLine("# Итог: $summary")
        for (s in sections) {
            appendLine("# [${s.level.mark}] ${s.title}")
            for (f in s.findings) {
                appendLine("#   ${f.level.mark} ${f.title}")
                if (f.detail.isNotEmpty()) f.detail.lines().forEach { appendLine("#       $it") }
            }
        }
    }.trimEnd()
}

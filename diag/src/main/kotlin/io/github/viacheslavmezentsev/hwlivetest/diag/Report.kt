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
    val level: Level
        get() = findings.map { it.level }.filter { it != Level.SKIP }.maxOrNull()
            ?: if (findings.isEmpty()) Level.SKIP else Level.INFO

    /** Самое важное наблюдение раздела для строки списка. */
    val headline: String
        get() = findings.filter { it.level == level }.firstOrNull()?.title ?: findings.firstOrNull()?.title ?: ""
}

data class DiagReport(val sections: List<Section>, val summary: String) {
    val level: Level get() = sections.map { it.level }.filter { it != Level.SKIP }.maxOrNull() ?: Level.INFO

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

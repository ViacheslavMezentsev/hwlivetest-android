package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.viacheslavmezentsev.hwlivetest.diag.Level
import io.github.viacheslavmezentsev.hwlivetest.diag.Section

/** Пункт отчёта в стиле списков AIDA64: значок, раздел, главное; по нажатию — подробности. */
class SectionRow(context: Context, section: Section) {
    private val details = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        visibility = View.GONE
        text = section.findings.joinToString("\n") { f ->
            "${f.level.mark} ${f.title}" + if (f.detail.isEmpty()) "" else "\n" + f.detail.lines().joinToString("\n") { "    $it" }
        }
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
        isClickable = true
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(context).apply {
                text = section.level.mark
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorOf(context, section.level))
                minWidth = (32 * context.resources.displayMetrics.density).toInt()
            },
        )
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply { text = section.title; textSize = 17f })
        texts.addView(
            TextView(context).apply {
                text = section.headline
                textSize = 13f
                setTextColor(context.getColor(R.color.muted))
            },
        )
        head.addView(texts)
        addView(head)
        addView(details)
        setOnClickListener { details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE }
    }

    companion object {
        fun colorOf(c: Context, l: Level): Int = c.getColor(
            when (l) {
                Level.OK -> R.color.ok
                Level.INFO, Level.SKIP -> R.color.muted
                Level.WARN -> R.color.warn
                Level.BAD -> R.color.error
            },
        )
    }
}

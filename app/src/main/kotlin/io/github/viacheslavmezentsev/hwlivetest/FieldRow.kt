package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.telemetry.MemoryMap

/** Строка списка полей: имя и значение, под ними адрес, тип и предупреждение. */
class FieldRow(context: Context, private val field: Field, check: MemoryMap.Check) {
    private val title = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f
        text = field.shortName
    }

    private val details = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(context.getColor(R.color.muted))
        text = buildString {
            append(field.topic).append('\n')
            append(field.address.hex32()).append("  ").append(field.kind.key)
            if (field.min != null || field.max != null) append("  [${field.min ?: "…"}; ${field.max ?: "…"}]")
            check.warning?.let { append("\n⚠ ").append(it) }
        }
    }

    private val okColor = context.getColor(android.R.color.black)
    private val errorColor = context.getColor(R.color.error)

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        addView(title)
        addView(details)
    }

    fun show(r: FieldSampler.Reading) {
        val value = r.value
        title.text = when {
            r.error != null -> "${field.shortName}  — ${r.error}"
            value != null && r.outOfRange -> "${field.shortName}  ${field.format(value)} (вне границ)"
            value != null -> "${field.shortName}  ${field.format(value)}"
            else -> field.shortName
        }
        title.setTextColor(if (r.error != null || r.outOfRange) errorColor else okColor)
    }
}

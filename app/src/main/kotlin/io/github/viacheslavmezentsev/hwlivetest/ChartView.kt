package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import io.github.viacheslavmezentsev.hwlivetest.telemetry.ChartScale
import io.github.viacheslavmezentsev.hwlivetest.telemetry.TimeSeries

/**
 * График нескольких рядов за последние [windowMs] мс. Ось X — время назад от [nowMs].
 *
 * Шкала Y общая (все ряды в одних единицах) или своя у каждого ряда: тогда каждый ряд
 * растянут на всю высоту, подписи оси — для первого ряда, диапазоны остальных — в легенде.
 * Разрывы больше [GAP_MS] (ошибки чтения, остановка опроса) не соединяются линией.
 */
class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Line(val name: String, val series: TimeSeries, val color: Int, val format: (Double) -> String)

    var lines: List<Line> = emptyList()
    var windowMs: Long = 30_000
    var nowMs: Long = 0
    var ownScale: Boolean = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val grid = Paint().apply {
        color = Color.rgb(0xD0, 0xD7, 0xDE)
        strokeWidth = dp(1f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x57, 0x60, 0x6A)
        textSize = dp(11f)
        typeface = Typeface.MONOSPACE
    }
    private val legendBg = Paint().apply { color = Color.argb(0xD0, 0xFF, 0xFF, 0xFF) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val from = nowMs - windowMs
        val windows = lines.map { it.series.window(from) }
        val axes = if (ownScale) {
            windows.map { ChartScale.yAxis(it.min, it.max) }
        } else {
            val all = ChartScale.yAxis(windows.mapNotNull { it.min }.minOrNull(), windows.mapNotNull { it.max }.maxOrNull())
            windows.map { all }
        }
        val labelAxis = axes.firstOrNull() ?: ChartScale.yAxis(null, null)
        val labels = labelAxis.ticks.map { ChartScale.label(it, labelAxis.step) }

        val left = (labels.maxOfOrNull { axisText.measureText(it) } ?: 0f) + dp(8f)
        val right = width - dp(8f)
        val top = dp(8f)
        val bottom = height - axisText.textSize - dp(10f)
        if (right <= left || bottom <= top) return
        fun x(t: Long) = left + (t - from).toFloat() / windowMs * (right - left)

        // Сетка и подписи Y (по первому ряду)
        val labelSpan = (labelAxis.max - labelAxis.min).takeIf { it > 0 } ?: 1.0
        axisText.color = if (ownScale && lines.size > 1) lines[0].color else Color.rgb(0x57, 0x60, 0x6A)
        labelAxis.ticks.forEachIndexed { i, v ->
            val yy = (bottom - (v - labelAxis.min) / labelSpan * (bottom - top)).toFloat()
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(labels[i], dp(2f), yy + axisText.textSize / 3, axisText)
        }
        axisText.color = Color.rgb(0x57, 0x60, 0x6A)
        // Сетка и подписи X
        val step = ChartScale.timeStepMs(windowMs)
        var back = 0L
        while (back <= windowMs) {
            val xx = x(nowMs - back)
            canvas.drawLine(xx, top, xx, bottom, grid)
            val label = if (back == 0L) "0" else "−${formatBack(back)}"
            val tw = axisText.measureText(label)
            canvas.drawText(label, (xx - tw / 2).coerceIn(left, right - tw), height - dp(4f), axisText)
            back += step
        }

        if (windows.all { it.size == 0 }) {
            val msg = "нет данных"
            canvas.drawText(msg, (left + right - axisText.measureText(msg)) / 2, (top + bottom) / 2, axisText)
            return
        }
        // Линии
        lines.forEachIndexed { li, line ->
            val w = windows[li]
            val a = axes[li]
            val span = (a.max - a.min).takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = (bottom - (v - a.min) / span * (bottom - top)).toFloat()
            path.reset()
            var started = false
            var prevT = Long.MIN_VALUE
            for (k in 0 until w.size) {
                val v = w.values[k]
                if (!v.isFinite()) {
                    started = false
                    continue
                }
                val t = w.times[k]
                if (!started || t - prevT > GAP_MS) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                started = true
                prevT = t
            }
            stroke.color = line.color
            canvas.drawPath(path, stroke)
            if (w.size > 0 && w.values[w.size - 1].isFinite()) {
                dot.color = line.color
                canvas.drawCircle(x(w.times[w.size - 1]), y(w.values[w.size - 1]), dp(3f), dot)
            }
        }
        // Легенда: имя, последнее значение, диапазон в окне
        if (lines.size > 1) {
            val rowH = axisText.textSize * 1.35f
            val texts = lines.mapIndexed { i, l ->
                val w = windows[i]
                val last = l.series.lastValue()?.let(l.format) ?: "—"
                val range = if (w.min != null && w.max != null) "  [${l.format(w.min!!)} … ${l.format(w.max!!)}]" else ""
                "${l.name} $last$range"
            }
            val boxW = (texts.maxOfOrNull { axisText.measureText(it) } ?: 0f) + dp(22f)
            canvas.drawRect(left + dp(4f), top + dp(2f), left + dp(4f) + boxW, top + dp(6f) + rowH * texts.size, legendBg)
            texts.forEachIndexed { i, t ->
                val yy = top + dp(4f) + rowH * (i + 0.8f)
                dot.color = lines[i].color
                canvas.drawCircle(left + dp(12f), yy - axisText.textSize / 3, dp(4f), dot)
                axisText.color = lines[i].color
                canvas.drawText(t, left + dp(20f), yy, axisText)
            }
            axisText.color = Color.rgb(0x57, 0x60, 0x6A)
        }
    }

    private fun formatBack(ms: Long): String =
        if (ms % 60_000 == 0L) "${ms / 60_000} мин" else "${ms / 1000} с"

    companion object {
        const val GAP_MS = 1_000L

        /** Цвета рядов: различимы на белом и для дальтоников (Okabe–Ito). */
        val PALETTE = intArrayOf(
            Color.rgb(0x1F, 0x6F, 0xEB), Color.rgb(0xD5, 0x5E, 0x00), Color.rgb(0x00, 0x9E, 0x73),
            Color.rgb(0xCC, 0x79, 0xA7), Color.rgb(0xE6, 0x9F, 0x00), Color.rgb(0x56, 0xB4, 0xE9),
        )
    }
}

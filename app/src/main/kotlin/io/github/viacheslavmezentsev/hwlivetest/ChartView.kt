package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import io.github.viacheslavmezentsev.hwlivetest.telemetry.AxisTracker
import io.github.viacheslavmezentsev.hwlivetest.telemetry.ChartScale
import io.github.viacheslavmezentsev.hwlivetest.telemetry.TimeSeries

/**
 * График нескольких рядов за последние [windowMs] мс. Ось X — время назад от [nowMs].
 *
 * Шкала Y общая (одни единицы) или своя у каждого ряда. При своей шкале подписи слева —
 * для первого ряда, справа — для второго, диапазоны остальных — в легенде. Оси держатся
 * с гистерезисом ([AxisTracker]), чтобы линии не прыгали при сдвиге окна.
 *
 * У оси для каждого ряда — треугольник и число: среднее за окно.
 * Ряды рисуются в порядке списка: последний — поверх остальных.
 */
class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Line(val name: String, val series: TimeSeries, val color: Int, val format: (Double) -> String)

    var lines: List<Line> = emptyList()
        set(v) {
            field = v
            trackers.clear()
            shared.reset()
        }
    var windowMs: Long = 30_000
        set(v) {
            if (v != field) resetAxes()
            field = v
        }
    var nowMs: Long = 0
    var ownScale: Boolean = false
        set(v) {
            if (v != field) resetAxes()
            field = v
        }

    private val trackers = HashMap<String, AxisTracker>()
    private val shared = AxisTracker()

    private fun resetAxes() {
        trackers.values.forEach { it.reset() }
        shared.reset()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val muted = Color.rgb(0x57, 0x60, 0x6A)
    private val grid = Paint().apply {
        color = Color.rgb(0xD0, 0xD7, 0xDE)
        strokeWidth = dp(1f)
    }
    private val axisLine = Paint().apply {
        color = Color.rgb(0x8C, 0x95, 0x9F)
        strokeWidth = dp(1f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textSize = dp(11f)
        typeface = Typeface.MONOSPACE
    }
    private val bg = Paint().apply { color = Color.argb(0xE0, 0xFF, 0xFF, 0xFF) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val from = nowMs - windowMs
        val windows = lines.map { it.series.window(from) }
        val axes: List<ChartScale.Axis> = if (ownScale) {
            lines.mapIndexed { i, l -> trackers.getOrPut(l.name + "#" + i) { AxisTracker() }.update(windows[i].min, windows[i].max) }
        } else {
            val a = shared.update(windows.mapNotNull { it.min }.minOrNull(), windows.mapNotNull { it.max }.maxOrNull())
            lines.map { a }
        }
        val leftAxis = axes.firstOrNull() ?: ChartScale.yAxis(null, null)
        val rightAxis = if (ownScale && lines.size >= 2) axes[1] else null
        val leftLabels = leftAxis.ticks.map { ChartScale.label(it, leftAxis.step) }
        val rightLabels = rightAxis?.let { a -> a.ticks.map { ChartScale.label(it, a.step) } } ?: emptyList()
        val means = windows.map { it.mean }
        val meanLabels = lines.mapIndexed { i, l -> means[i]?.let { ChartScale.label(it, axes[i].step / 10) } }

        // Поля: подписи делений и средних слева (и справа для второй шкалы).
        val leftMeans = meanLabels.filterIndexed { i, _ -> rightAxis == null || i != 1 }.filterNotNull()
        val leftW = (leftLabels + leftMeans).maxOfOrNull { axisText.measureText(it) } ?: 0f
        val rightW = (rightLabels + listOfNotNull(if (rightAxis != null) meanLabels.getOrNull(1) else null))
            .maxOfOrNull { axisText.measureText(it) } ?: 0f
        val tri = dp(6f)
        val left = leftW + tri + dp(6f)
        val right = width - (if (rightAxis != null) rightW + tri + dp(6f) else dp(8f))
        val top = dp(8f)
        val bottom = height - axisText.textSize - dp(10f)
        if (right <= left || bottom <= top) return
        fun x(t: Long) = left + (t - from).toFloat() / windowMs * (right - left)
        fun y(v: Double, a: ChartScale.Axis): Float {
            val span = (a.max - a.min).takeIf { it > 0 } ?: 1.0
            return (bottom - (v - a.min) / span * (bottom - top)).toFloat()
        }

        // Сетка и подписи Y
        axisText.color = if (ownScale && lines.size > 1) lines[0].color else muted
        leftAxis.ticks.forEachIndexed { i, v ->
            val yy = y(v, leftAxis)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(leftLabels[i], left - tri - dp(3f) - axisText.measureText(leftLabels[i]), yy + axisText.textSize / 3, axisText)
        }
        if (rightAxis != null) {
            axisText.color = lines[1].color
            rightAxis.ticks.forEachIndexed { i, v ->
                canvas.drawText(rightLabels[i], right + tri + dp(3f), y(v, rightAxis) + axisText.textSize / 3, axisText)
            }
        }
        axisText.color = muted
        // Сетка и подписи X
        val step = ChartScale.timeStepMs(windowMs)
        var back = 0L
        var prevLeft = Float.MAX_VALUE // подписи идут справа налево; налезающие пропускаем
        while (back <= windowMs) {
            val xx = x(nowMs - back)
            canvas.drawLine(xx, top, xx, bottom, grid)
            val label = if (back == 0L) "0" else "−${formatBack(back)}"
            val tw = axisText.measureText(label)
            val lx = (xx - tw / 2).coerceIn(left, right - tw)
            if (lx + tw + dp(4f) <= prevLeft) {
                canvas.drawText(label, lx, height - dp(4f), axisText)
                prevLeft = lx
            }
            back += step
        }
        canvas.drawLine(left, top, left, bottom, axisLine)
        if (rightAxis != null) canvas.drawLine(right, top, right, bottom, axisLine)

        if (windows.all { it.size == 0 }) {
            val msg = "нет данных"
            canvas.drawText(msg, (left + right - axisText.measureText(msg)) / 2, (top + bottom) / 2, axisText)
            return
        }
        // Линии: по порядку списка, последний — сверху.
        lines.forEachIndexed { li, line ->
            val w = windows[li]
            val a = axes[li]
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
                if (!started || t - prevT > GAP_MS) path.moveTo(x(t), y(v, a)) else path.lineTo(x(t), y(v, a))
                started = true
                prevT = t
            }
            stroke.color = line.color
            canvas.drawPath(path, stroke)
            if (w.size > 0 && w.values[w.size - 1].isFinite()) {
                fill.color = line.color
                canvas.drawCircle(x(w.times[w.size - 1]), y(w.values[w.size - 1], a), dp(3f), fill)
            }
        }
        // Средние: треугольник у оси и число.
        lines.forEachIndexed { i, line ->
            val m = means[i] ?: return@forEachIndexed
            val label = meanLabels[i] ?: return@forEachIndexed
            val yy = y(m, axes[i]).coerceIn(top, bottom)
            val onRight = rightAxis != null && i == 1
            val ax = if (onRight) right else left
            val dir = if (onRight) 1f else -1f // треугольник снаружи оси, остриём к ней
            path.reset()
            path.moveTo(ax, yy)
            path.lineTo(ax + dir * tri, yy - tri * 0.7f)
            path.lineTo(ax + dir * tri, yy + tri * 0.7f)
            path.close()
            fill.color = line.color
            canvas.drawPath(path, fill)
            val tw = axisText.measureText(label)
            val tx = if (onRight) ax + tri + dp(3f) else ax - tri - dp(3f) - tw
            val th = axisText.textSize
            canvas.drawRect(tx - dp(1f), yy - th * 0.75f, tx + tw + dp(1f), yy + th * 0.35f, bg)
            axisText.color = line.color
            canvas.drawText(label, tx, yy + th / 3, axisText)
            axisText.color = muted
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
            canvas.drawRect(left + dp(4f), top + dp(2f), left + dp(4f) + boxW, top + dp(6f) + rowH * texts.size, bg)
            texts.forEachIndexed { i, t ->
                val yy = top + dp(4f) + rowH * (i + 0.8f)
                fill.color = lines[i].color
                canvas.drawCircle(left + dp(12f), yy - axisText.textSize / 3, dp(4f), fill)
                axisText.color = lines[i].color
                canvas.drawText(t, left + dp(20f), yy, axisText)
            }
            axisText.color = muted
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

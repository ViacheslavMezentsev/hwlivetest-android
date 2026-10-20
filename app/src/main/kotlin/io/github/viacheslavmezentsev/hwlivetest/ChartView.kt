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
 * График одного ряда за последние [windowMs] мс. Ось Y подстраивается под видимые
 * значения («красивые» деления), ось X — время назад от [nowMs]. Разрывы больше
 * [GAP_MS] (ошибки чтения, остановка опроса) не соединяются линией.
 */
class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var series: TimeSeries? = null
    var windowMs: Long = 30_000
    var nowMs: Long = 0

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
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brand)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brand)
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val from = nowMs - windowMs
        val w = series?.window(from)
        val axis = ChartScale.yAxis(w?.min, w?.max)
        val labels = axis.ticks.map { ChartScale.label(it, axis.step) }

        val left = (labels.maxOfOrNull { axisText.measureText(it) } ?: 0f) + dp(8f)
        val right = width - dp(8f)
        val top = dp(8f)
        val bottom = height - axisText.textSize - dp(10f)
        if (right <= left || bottom <= top) return
        val span = (axis.max - axis.min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (bottom - (v - axis.min) / span * (bottom - top)).toFloat()
        fun x(t: Long) = left + (t - from).toFloat() / windowMs * (right - left)

        // Сетка и подписи Y
        axis.ticks.forEachIndexed { i, v ->
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(labels[i], dp(2f), yy + axisText.textSize / 3, axisText)
        }
        // Сетка и подписи X: время назад, 0 — сейчас
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

        if (w == null || w.size == 0) {
            val msg = "нет данных"
            canvas.drawText(msg, (left + right - axisText.measureText(msg)) / 2, (top + bottom) / 2, axisText)
            return
        }
        // Линия
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
            val px = x(t)
            val py = y(v)
            if (!started || t - prevT > GAP_MS) path.moveTo(px, py) else path.lineTo(px, py)
            started = true
            prevT = t
        }
        canvas.drawPath(path, line)
        // Последняя точка
        val lastT = w.times[w.size - 1]
        val lastV = w.values[w.size - 1]
        if (lastV.isFinite()) canvas.drawCircle(x(lastT), y(lastV), dp(3f), dot)
    }

    private fun formatBack(ms: Long): String =
        if (ms % 60_000 == 0L) "${ms / 60_000} мин" else "${ms / 1000} с"

    companion object {
        const val GAP_MS = 1_000L
    }
}

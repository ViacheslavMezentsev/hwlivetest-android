package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Круглая кнопка «Проверить плату» с кольцом делений: во время проверки деления заполняются по
 * часовой стрелке, в центре — подпись и проценты. После проверки круг окрашивается
 * в цвет итога.
 */
class StartButton(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var label: String = "Проверить плату"
        set(v) {
            field = v
            invalidate()
        }

    /** Подпись под основной: проценты или итог. */
    var sublabel: String = ""
        set(v) {
            field = v
            invalidate()
        }

    /** 0..1; меньше 0 — кольцо не заполняется. */
    var progress: Float = -1f
        set(v) {
            field = v
            invalidate()
        }

    /** Цвет круга. */
    var fillColor: Int = context.getColor(R.color.brand)
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val tickOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xD0, 0xD7, 0xDE)
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tickOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brand)
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xEE, 0xF2, 0xF7) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        canvas.drawCircle(cx, cy, r, halo)
        // Кольцо делений
        val outer = r * 0.96f
        val inner = r * 0.84f
        val on = if (progress < 0) 0 else (progress.coerceIn(0f, 1f) * TICKS).toInt()
        for (i in 0 until TICKS) {
            val a = Math.toRadians(-90.0 + 360.0 * i / TICKS)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + inner * c, cy + inner * s, cx + outer * c, cy + outer * s, if (i < on) tickOn else tickOff)
        }
        fill.color = fillColor
        canvas.drawCircle(cx, cy, r * 0.74f, fill)
        text.textSize = r * 0.26f
        canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
        if (sublabel.isNotEmpty()) {
            small.textSize = r * 0.11f
            canvas.drawText(sublabel, cx, cy + r * 0.38f, small)
        }
    }

    companion object {
        private const val TICKS = 90
    }
}

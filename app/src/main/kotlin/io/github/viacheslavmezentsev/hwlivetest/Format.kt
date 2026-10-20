package io.github.viacheslavmezentsev.hwlivetest

import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkConnection
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.DpIdCode
import io.github.viacheslavmezentsev.hwlivetest.stlink.cortexm.TargetInfo
import io.github.viacheslavmezentsev.hwlivetest.stlink.hex32
import io.github.viacheslavmezentsev.hwlivetest.usb.StLinkDevice

/** Тексты блоков «Адаптер» и «Микроконтроллер». */
object Format {
    fun adapter(d: StLinkDevice, c: StLinkConnection): String = buildString {
        appendLine("Модель      ${d.model.title}")
        appendLine("VID:PID     ${d.vidPid}")
        appendLine("Прошивка    ${c.version.label}")
        appendLine("Режим       ${c.initialMode.title} → SWD")
        appendLine("Напряжение  " + (c.targetVoltage?.let { "%.2f В".format(it) } ?: "не измеряется"))
        appendLine("DP IDCODE   ${c.dpIdCode.hex32()}")
        append("DP          ${DpIdCode(c.dpIdCode).description}")
        c.warnings.forEach { append("\n⚠ ").append(it) }
    }

    fun target(info: TargetInfo): String = buildString {
        val rows = info.summary()
        val width = rows.maxOfOrNull { it.first.length } ?: 0
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) append('\n')
            append(k.padEnd(width + 1)).append(' ').append(v)
        }
        info.sramKb?.let { append("\n").append("SRAM".padEnd(width + 1)).append(" до $it КБ") }
        info.notes.forEach { append("\n• ").append(it) }
    }
}

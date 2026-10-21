package io.github.viacheslavmezentsev.hwlivetest

import android.content.Context
import android.hardware.usb.UsbDevice
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Exception
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2ErrorKind
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Firmware
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Image
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2LogicAnalyzer
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Protocol
import io.github.viacheslavmezentsev.hwlivetest.fx2la.ProbeResult
import io.github.viacheslavmezentsev.hwlivetest.usb.Fx2Device
import io.github.viacheslavmezentsev.hwlivetest.usb.Fx2Usb
import io.github.viacheslavmezentsev.hwlivetest.usb.UsbFx2Transport
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Состояние логического анализатора. У анализатора свой поток исполнения, поэтому он не
 * мешает опросу ST-Link (архитектура, AD-09; ТЗ ЛА 8.3.1). Журнал общий с ST-Link.
 */
object LogicSession {
    val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "la-worker").apply { isDaemon = true } }

    @Volatile
    var device: Fx2Device? = null
        private set
    private var transport: UsbFx2Transport? = null

    @Volatile
    var analyzer: Fx2LogicAnalyzer? = null
        private set

    var version: Fx2Protocol.Version? = null
        private set
    var infoText: String = ""
        private set
    var lastResult: ProbeResult? = null

    fun log(line: String) = Session.logLine(line)

    // ---------- Файл прошивки (ТЗ ЛА 6.1.2, 6.1.3) ----------

    private const val FIRMWARE_FILE = "fx2lafw.bin"
    private const val FIRMWARE_NAME = "fx2lafw.name"

    /** Проверить и сохранить выбранный файл; бросает [Fx2Firmware.BadImage]. */
    fun saveFirmware(c: Context, bytes: ByteArray, name: String): Fx2Image {
        val image = Fx2Firmware.parse(bytes)
        File(c.filesDir, FIRMWARE_FILE).writeBytes(bytes)
        File(c.filesDir, FIRMWARE_NAME).writeText(name)
        log("# ЛА: файл прошивки $name, ${image.size} байт, sha256 ${image.sha256Short}…")
        return image
    }

    fun loadFirmware(c: Context): Fx2Image? {
        val f = File(c.filesDir, FIRMWARE_FILE)
        return if (f.exists()) Fx2Firmware.parse(f.readBytes()) else null
    }

    fun firmwareName(c: Context): String? =
        File(c.filesDir, FIRMWARE_NAME).takeIf { it.exists() }?.readText()

    // ---------- Подключение (вызывать в worker) ----------

    /**
     * Открыть анализатор и спросить версию fx2lafw (ТЗ ЛА 6.1.1). Возвращает true, если
     * прошивка уже работает; false — прошивка загружена только что и анализатор
     * переподключается: его нужно найти заново ([waitRenumeration]).
     */
    fun open(usb: Fx2Usb, d: Fx2Device, image: Fx2Image?, allowUpload: Boolean): Boolean {
        close()
        val (manufacturer, product) = d.strings()
        log("# ЛА: ${d.title}, ${d.device.deviceName}; строки: ${manufacturer ?: "?"} / ${product ?: "?"}")
        log("# ЛА: ${usb.describe(d)}")
        val t = usb.open(d)
        val la = Fx2LogicAnalyzer(t, ::log)
        val v = la.firmwareVersion()
        if (v == null) {
            if (!allowUpload) {
                t.close()
                throw Fx2Exception(Fx2ErrorKind.RENUMERATION, "После загрузки прошивки анализатор не отвечает. Переподключите его.")
            }
            if (image == null) {
                t.close()
                throw Fx2Exception(
                    Fx2ErrorKind.NO_FIRMWARE,
                    "Нужна прошивка fx2lafw: выберите файл ${d.profile.firmwareName} (из установки sigrok-cli, папка share\\sigrok-firmware).",
                )
            }
            try {
                la.uploadFirmware(image)
            } finally {
                t.close()
            }
            return false
        }
        la.requireFirmware()
        val rev = la.revId()
        device = d
        transport = t
        analyzer = la
        version = v
        val speed = if (la.highSpeed) "высокая (480 Мбит/с)" else "полная (12 Мбит/с)"
        infoText = buildString {
            appendLine("${d.title}")
            appendLine("fx2lafw $v, ревизия кристалла ${rev?.let { "0x%02X".format(it) } ?: "?"}")
            append("Скорость USB: $speed, пакет ${t.packetSize} байт")
        }
        log("# ЛА: fx2lafw $v, скорость USB $speed")
        return true
    }

    /**
     * Дождаться ренумерации после загрузки (ТЗ ЛА 3.2.4): старое устройство исчезает и
     * появляется новое. Если старое не исчезло — оно и есть анализатор с прошивкой.
     */
    fun waitRenumeration(usb: Fx2Usb, old: Fx2Device, timeoutMs: Long = RENUM_TIMEOUT_MS): Fx2Device {
        val deadline = System.currentTimeMillis() + timeoutMs
        var gone = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            val list = usb.findDevices()
            if (list.none { it.sameAs(old.device) }) gone = true
            if (gone) list.firstOrNull()?.let {
                log("# ЛА: анализатор переподключился как ${it.device.deviceName}")
                return it
            }
        }
        if (!gone) usb.findDevices().firstOrNull { it.sameAs(old.device) }?.let { return it }
        throw Fx2Exception(Fx2ErrorKind.RENUMERATION, "Анализатор не появился за ${timeoutMs / 1000} с после загрузки прошивки.")
    }

    fun close() {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        analyzer = null
        device = null
        version = null
        infoText = ""
    }

    /** Отключение USB-устройства (главный поток). */
    fun detached(d: UsbDevice?) {
        if (device?.sameAs(d) != true) return
        log("# ЛА: анализатор отключён")
        worker.execute { close() }
    }

    const val RENUM_TIMEOUT_MS = 3000L
}

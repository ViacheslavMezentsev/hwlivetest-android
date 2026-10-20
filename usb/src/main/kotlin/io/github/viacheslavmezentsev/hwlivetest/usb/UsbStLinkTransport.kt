package io.github.viacheslavmezentsev.hwlivetest.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.viacheslavmezentsev.hwlivetest.stlink.StLinkTransport
import java.io.IOException

/** Bulk-обмен с ST-Link через Android USB Host API. */
class UsbStLinkTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : StLinkTransport {

    @Volatile
    private var closed = false

    val endpointsLabel: String
        get() = "OUT 0x%02X, IN 0x%02X, пакет %d байт".format(
            outEndpoint.address, inEndpoint.address, inEndpoint.maxPacketSize,
        )

    override fun write(data: ByteArray) {
        ensureOpen()
        val n = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMs)
        if (n != data.size) throw IOException("запись в 0x%02X: результат %d".format(outEndpoint.address, n))
    }

    override fun read(length: Int): ByteArray {
        ensureOpen()
        // Читаем блоками кратными размеру пакета, чтобы не получить переполнение (babble).
        val packet = inEndpoint.maxPacketSize.coerceAtLeast(64)
        val chunk = ((length + packet - 1) / packet) * packet
        val buffer = ByteArray(chunk)
        var got = 0
        while (got < length) {
            val n = connection.bulkTransfer(inEndpoint, buffer, got, chunk - got, timeoutMs)
            if (n < 0) throw IOException("чтение из 0x%02X: таймаут или ошибка (%d из %d байт)".format(inEndpoint.address, got, length))
            if (n == 0) break
            got += n
        }
        if (got < length) throw IOException("чтение из 0x%02X: получено %d из %d байт".format(inEndpoint.address, got, length))
        return buffer.copyOf(length)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("соединение закрыто")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1000
    }
}

package io.github.viacheslavmezentsev.hwlivetest.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.SystemClock
import io.github.viacheslavmezentsev.hwlivetest.fx2la.Fx2Transport
import io.github.viacheslavmezentsev.hwlivetest.fx2la.StallException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** Управляющие запросы и асинхронное чтение анализатора на FX2 (ТЗ ЛА 4.1, 7.1). */
class UsbFx2Transport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val dataEndpoint: UsbEndpoint?,
) : Fx2Transport {

    @Volatile
    private var closed = false

    override val packetSize: Int get() = dataEndpoint?.maxPacketSize ?: 0

    // ТЗ ЛА 4.1.2: до Android 9 одна передача UsbRequest ограничена 16 КБ.
    override val maxBufferBytes: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 262_144 else 16_384

    override fun controlOut(request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int) {
        ensureOpen()
        val n = connection.controlTransfer(VENDOR_OUT, request, value, index, data, data.size, timeoutMs)
        if (n != data.size) throw IOException("запрос 0x%02X: результат %d".format(request, n))
    }

    override fun controlIn(request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        ensureOpen()
        val buf = ByteArray(length)
        val n = connection.controlTransfer(VENDOR_IN, request, value, index, buf, length, timeoutMs)
        return if (n < 0) null else buf.copyOf(n)
    }

    override fun stream(bufferBytes: Int, transfers: Int, stallMs: Long, sink: (ByteArray, Int, Long) -> Boolean) {
        ensureOpen()
        val ep = dataEndpoint ?: throw IOException("нет точки данных 0x82: прошивка fx2lafw не загружена")
        val buffers = List(transfers) { ByteBuffer.allocateDirect(bufferBytes) }
        val requests = ArrayList<UsbRequest>(transfers)
        val chunk = ByteArray(bufferBytes)
        try {
            for (i in 0 until transfers) {
                val r = UsbRequest()
                if (!r.initialize(connection, ep)) throw IOException("UsbRequest не создан")
                r.clientData = i
                requests += r
                buffers[i].clear()
                if (!r.queue(buffers[i])) throw IOException("передача не поставлена в очередь")
            }
            var lastData = SystemClock.elapsedRealtimeNanos()
            while (!closed) {
                val done = try {
                    connection.requestWait(WAIT_STEP_MS)
                } catch (_: TimeoutException) {
                    null.also {
                        if (SystemClock.elapsedRealtimeNanos() - lastData > stallMs * 1_000_000) {
                            throw StallException("нет данных дольше $stallMs мс")
                        }
                    }
                } ?: continue
                val now = SystemClock.elapsedRealtimeNanos()
                val i = done.clientData as? Int ?: throw IOException("чужая передача")
                val buf = buffers[i]
                val n = buf.position()
                buf.flip()
                buf.get(chunk, 0, n)
                buf.clear()
                // ТЗ ЛА 4.1.1: передача возвращается в очередь до обработки данных.
                if (!done.queue(buf)) throw IOException("передача не поставлена в очередь повторно")
                if (n == 0) {
                    if (now - lastData > stallMs * 1_000_000) throw StallException("нет данных дольше $stallMs мс")
                    continue
                }
                lastData = now
                if (!sink(chunk, n, now)) return
            }
            throw IOException("соединение закрыто")
        } finally {
            requests.forEach { it.cancel() }
            // Дождаться отменённых передач, иначе их буферы освободятся раньше ядра.
            var left = requests.size
            val deadline = SystemClock.elapsedRealtime() + CANCEL_WAIT_MS
            while (left > 0 && SystemClock.elapsedRealtime() < deadline && !closed) {
                try {
                    connection.requestWait(WAIT_STEP_MS) ?: break
                    left--
                } catch (_: TimeoutException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
            requests.forEach { it.close() }
        }
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
        private const val VENDOR_OUT = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT
        private const val VENDOR_IN = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
        private const val WAIT_STEP_MS = 100L
        private const val CANCEL_WAIT_MS = 500L
    }
}

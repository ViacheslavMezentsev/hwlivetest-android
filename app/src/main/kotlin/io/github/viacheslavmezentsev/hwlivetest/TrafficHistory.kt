package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.19: скорость успешного протокольного обмена, без новых USB-команд. */
class TrafficHistory(private val capacity: Int = 60) {
    data class Point(val timeMs: Long, val receivedPerSecond: Double, val sentPerSecond: Double)
    data class Snapshot(val points: List<Point> = emptyList(), val received: Long = 0, val sent: Long = 0,
        val peakPerSecond: Double = 0.0)
    private var connection: Any? = null
    private var lastTime = 0L
    private var lastReceived = 0L
    private var lastSent = 0L
    private var peak = 0.0
    private val points = ArrayDeque<Point>()

    fun sample(nowMs: Long, key: Any?, received: Long, sent: Long): Snapshot {
        if (key == null || key !== connection || received < lastReceived || sent < lastSent) {
            connection = key
            points.clear()
            peak = 0.0
            lastTime = nowMs
            lastReceived = received
            lastSent = sent
            return Snapshot(received = received, sent = sent)
        }
        val elapsed = nowMs - lastTime
        if (elapsed > 0) {
            val rx = (received - lastReceived) * 1000.0 / elapsed
            val tx = (sent - lastSent) * 1000.0 / elapsed
            points.addLast(Point(nowMs, rx, tx))
            while (points.size > capacity) points.removeFirst()
            peak = maxOf(peak, rx + tx)
            lastTime = nowMs
            lastReceived = received
            lastSent = sent
        }
        return Snapshot(points.toList(), received, sent, peak)
    }
}

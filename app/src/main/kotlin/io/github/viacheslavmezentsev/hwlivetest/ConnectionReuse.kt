package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.14: принадлежит одному потоку обмена; не кеширует неудачное открытие. */
internal class ConnectionReuse<T : Any> {
    private var key: String? = null
    private var value: T? = null

    fun getOrOpen(deviceKey: String, reusable: Boolean, open: () -> T): T {
        val previous = value
        if (reusable && key == deviceKey && previous != null) return previous
        clear()
        val opened = open()
        key = deviceKey
        value = opened
        return opened
    }

    fun clear() { key = null; value = null }
}

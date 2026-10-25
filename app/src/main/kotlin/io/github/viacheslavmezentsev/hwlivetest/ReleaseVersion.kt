package io.github.viacheslavmezentsev.hwlivetest

/** ТЗ прошивки 5.24: сравниваем числовые версии, а не строки или даты публикации. */
object ReleaseVersion {
    fun compare(release: String, installed: String): Int? {
        fun parts(value: String): List<Int>? {
            val text = value.removePrefix("v")
            if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(text)) return null
            return text.split('.').map { it.toIntOrNull() ?: return null }
        }
        val remote = parts(release) ?: return null
        val local = parts(installed) ?: return null
        return remote.zip(local).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }
}

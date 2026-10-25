package io.github.viacheslavmezentsev.hwlivetest

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** ТЗ прошивки 5.24: публичный GitHub API, без токенов и сведений о плате. */
object ReleaseUpdates {
    const val RELEASES = "https://github.com/ViacheslavMezentsev/hwlivetest-android/releases"
    data class Result(val message: String, val updateUrl: String? = null)
    fun check(installed: String): Result {
        val connection = URL("https://api.github.com/repos/ViacheslavMezentsev/hwlivetest-android/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HW-Live-Test")
            return when (connection.responseCode) {
                404 -> Result("Опубликованных релизов пока нет.")
                403, 429 -> Result("GitHub временно ограничил запросы. Повторите позже.")
                200 -> {
                    val bytes = connection.inputStream.use { it.readBytesLimited() }
                    val release = JSONObject(bytes.toString(Charsets.UTF_8))
                    check(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
                    val tag = release.getString("tag_name")
                    when (ReleaseVersion.compare(tag, installed)) {
                        1 -> Result("Доступно обновление: " + tag.removePrefix("v"),
                            RELEASES + "/tag/" + tag)
                        0 -> Result("Установлена последняя версия: " + installed)
                        -1 -> Result("Установлена версия " + installed + ", новее опубликованного релиза " + tag.removePrefix("v"))
                        else -> Result("Не удалось сравнить версии. Последний релиз: " + tag)
                    }
                }
                else -> Result("Не удалось проверить обновления (HTTP " + connection.responseCode + ").")
            }
        } catch (_: Exception) {
            return Result("Не удалось проверить обновления. Проверьте подключение к интернету и повторите.")
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= 1024 * 1024) { "Ответ слишком большой" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.viacheslavmezentsev.hwlivetest.recorder.McapWriter
import io.github.viacheslavmezentsev.hwlivetest.recorder.Ros1Type
import io.github.viacheslavmezentsev.hwlivetest.telemetry.Field
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldKind
import io.github.viacheslavmezentsev.hwlivetest.telemetry.FieldSampler
import io.github.viacheslavmezentsev.hwlivetest.telemetry.TimeSeries
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись полей в MCAP. Все методы вызываются в потоке [Session.worker]. */
class Recording(val uri: Uri, val name: String, private val writer: McapWriter, fields: List<Field>) {
    private val channels: Map<String, Int> = fields.associate { it.topic to writer.addChannel(it.topic, typeOf(it)) }

    val messages: Long get() = writer.messageCount
    val bytes: Long get() = writer.bytesWritten

    fun write(timeNs: Long, readings: List<FieldSampler.Reading>) {
        for (r in readings) {
            val v = r.value ?: continue
            if (!v.isFinite() || r.outOfRange) continue
            val ch = channels[r.spec.topic] ?: continue
            writer.write(ch, timeNs, v)
        }
    }

    /** Записать готовые ряды (экспорт буфера); [wall0Ms] — время Unix для nowMs = 0. */
    fun writeSeries(fields: List<Field>, series: (String) -> TimeSeries?, fromMs: Long, wall0Ms: Long) {
        val all = ArrayList<Triple<Long, Int, Double>>()
        for (f in fields) {
            val ch = channels[f.topic] ?: continue
            val w = series(f.topic)?.window(fromMs) ?: continue
            for (k in 0 until w.size) if (w.values[k].isFinite()) all += Triple(w.times[k], ch, w.values[k])
        }
        all.sortBy { it.first }
        for ((t, ch, v) in all) writer.write(ch, (wall0Ms + t) * 1_000_000L, v)
    }

    fun close() = writer.close()

    companion object {
        fun typeOf(f: Field): Ros1Type = Ros1Type.byName(f.message) ?: when (f.kind) {
            FieldKind.FLOAT32 -> Ros1Type.FLOAT32
            FieldKind.INT32 -> Ros1Type.INT32
            FieldKind.UINT32, FieldKind.UINT32_SECONDS -> Ros1Type.UINT32
        }

        fun fileName(prefix: String): String =
            "${prefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mcap"

        /** Создать файл в выбранной папке и начать запись. */
        fun create(c: Context, tree: Uri, name: String, fields: List<Field>): Recording {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val uri = DocumentsContract.createDocument(c.contentResolver, parent, "application/octet-stream", name)
                ?: throw IllegalStateException("не удалось создать $name в папке записей")
            val out = c.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("файл $name не открылся")
            return Recording(uri, name, McapWriter(BufferedOutputStream(out, 64 * 1024), "hwlivetest-android ${BuildConfig.VERSION_NAME}"), fields)
        }

        /** Отправить файл в другое приложение (Telegram, почта и т. п.). */
        fun share(a: Activity, uri: Uri, name: String) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, name))
        }

        /** Выбор папки для записей (ACTION_OPEN_DOCUMENT_TREE). */
        fun pickFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

        fun rememberFolder(c: Context, tree: Uri) {
            c.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Prefs.setRecordFolder(c, tree.toString())
        }

        fun folder(c: Context): Uri? {
            val s = Prefs.recordFolder(c) ?: return null
            val u = Uri.parse(s)
            val ok = c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
            return if (ok) u else null
        }
    }
}

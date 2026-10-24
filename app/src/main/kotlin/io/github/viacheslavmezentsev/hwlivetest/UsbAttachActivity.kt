package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** ТЗ прошивки 5.12: USB re-enumeration не является командой навигации. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connector = Connector(this) { _, _ -> }
        connector.fromIntent(intent)?.let {
            Session.logLine("# USB: обновить адаптер, сохранить текущий экран")
            connector.select(it)
        }
        // При холодном запуске показать приложение. В существующей задаче вернуть
        // именно верхнюю Activity — без CLEAR_TOP и повторного запуска Simple/Main.
        if (isTaskRoot) startActivity(Intent(this, SimpleActivity::class.java))
        finish()
    }
}

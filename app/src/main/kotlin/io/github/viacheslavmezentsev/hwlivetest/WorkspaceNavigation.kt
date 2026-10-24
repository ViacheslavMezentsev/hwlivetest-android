package io.github.viacheslavmezentsev.hwlivetest

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent

/** ТЗ прошивки 5.15: страницы одного уровня, цикл не зависит от стека Activity. */
enum class WorkspacePage(val title: String) {
    SIGNALS("Сигналы"), TARGET("Цель"), FLASH("Прошивка"), JOURNAL("Журнал");
    fun next(delta: Int): WorkspacePage = entries[Math.floorMod(ordinal + delta, entries.size)]
}
interface WorkspaceHost {
    val workspacePage: WorkspacePage?
    fun navigateWorkspace(page: WorkspacePage, direction: Int = 0)
}
object WorkspaceNavigation {
    const val EXTRA_WORKSPACE = "workspace"
    fun open(activity: Activity, page: WorkspacePage, direction: Int = 0) {
        val screen = when (page) {
            WorkspacePage.SIGNALS -> MainActivity::class.java
            WorkspacePage.TARGET -> TargetActivity::class.java
            WorkspacePage.FLASH -> FlashActivity::class.java
            WorkspacePage.JOURNAL -> JournalActivity::class.java
        }
        val from = (activity as? WorkspaceHost)?.workspacePage
        val forward = if (direction == 0) page.ordinal >= (from?.ordinal ?: 0) else direction > 0
        val options = ActivityOptions.makeCustomAnimation(activity,
            if (forward) R.anim.workspace_enter_right else R.anim.workspace_enter_left,
            if (forward) R.anim.workspace_exit_left else R.anim.workspace_exit_right)
        activity.startActivity(Intent(activity, screen).putExtra(EXTRA_WORKSPACE, true)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), options.toBundle())
    }
    fun home(activity: Activity) {
        Prefs.setExpert(activity, false)
        activity.startActivity(Intent(activity, SimpleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

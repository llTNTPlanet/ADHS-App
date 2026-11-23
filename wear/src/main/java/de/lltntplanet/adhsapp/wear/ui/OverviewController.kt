package de.lltntplanet.adhsapp.wear.ui

import android.os.SystemClock
import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchTask
import de.lltntplanet.adhsapp.wear.WearContainer
import de.lltntplanet.adhsapp.wear.link.PhoneLink
import de.lltntplanet.adhsapp.wear.link.SnapshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Was die Oberfläche mit der Übersicht tun kann: auffrischen und Haken setzen. Gesendet wird im Scope der App –
 * so kommt ein Haken auch dann am Handy an, wenn der Bildschirm gleich danach ausgeht.
 */
class OverviewController(private val container: WearContainer) {

    /** Kurzer Hinweis, wenn etwas nicht geklappt hat. [text] bleibt beim Ausblenden stehen. */
    data class Notice(val text: String = "", val visible: Boolean = false)

    private val snapshots: SnapshotRepository get() = container.snapshots
    private val phone: PhoneLink get() = container.phone
    private val scope: CoroutineScope get() = container.scope

    /** Die Übersicht vom Handy. `null` = noch keine. */
    val snapshot: StateFlow<WatchSnapshot?> get() = snapshots.snapshot

    /** `true`, sobald die gemerkte Übersicht gelesen ist. */
    val loaded: StateFlow<Boolean> get() = snapshots.loaded

    private val _refreshing = MutableStateFlow(false)

    /** Läuft gerade „Aktualisieren“? */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _notice = MutableStateFlow(Notice())

    val notice: StateFlow<Notice> = _notice.asStateFlow()

    private val _checkedTasks = MutableStateFlow<List<WatchTask>>(emptyList())

    /**
     * Aufgaben, die zuletzt auf der Uhr abgehakt wurden. Sie bleiben als erledigt sichtbar – so fällt ein
     * versehentlicher Haken auf und lässt sich zurücknehmen.
     */
    val checkedTasks: StateFlow<List<WatchTask>> = _checkedTasks.asStateFlow()

    /** Seit wann die App nicht mehr zu sehen ist (`SystemClock.elapsedRealtime()`); 0 = sie ist zu sehen. */
    private var hiddenAt = 0L

    /** Die App ist wieder zu sehen. Nach einer längeren Pause beginnt die Liste der abgehakten Aufgaben neu. */
    fun onVisible() {
        val since = hiddenAt
        hiddenAt = 0L
        if (since != 0L && SystemClock.elapsedRealtime() - since > OverviewLogic.SESSION_MILLIS) {
            _checkedTasks.value = emptyList()
        }
    }

    /** Die App ist nicht mehr zu sehen. */
    fun onHidden() {
        hiddenAt = SystemClock.elapsedRealtime()
    }

    /**
     * Holt eine frische Übersicht, wenn keine da ist oder sie zu alt ist. Läuft still im Hintergrund: Klappt
     * es nicht, bleibt die alte stehen (mit „Stand: …“).
     */
    fun syncIfNeeded() {
        scope.launch {
            snapshots.loaded.first { it }
            if (!needsSync()) return@launch
            // Vielleicht liegt schon eine neuere bereit, die die Uhr nur noch nicht gelesen hat.
            snapshots.refresh()
            if (needsSync()) phone.requestSync()
        }
    }

    /** Knopf „Aktualisieren“: bittet das Handy um eine neue Übersicht und wartet kurz darauf. */
    fun refreshNow() {
        if (!_refreshing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val before = snapshots.snapshot.value?.generatedAt
                if (phone.requestSync()) {
                    val arrived = withTimeoutOrNull(OverviewLogic.ANSWER_MILLIS) {
                        snapshots.snapshot.first { it != null && it.generatedAt != before }
                    }
                    if (arrived == null && !snapshots.refresh()) showNotice(OverviewLogic.NO_ANSWER)
                } else if (!snapshots.refresh()) {
                    showNotice(OverviewLogic.PHONE_UNREACHABLE)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Hakt ein Vorkommen einer Routine ab (oder entfernt den Haken): sofort auf der Uhr, dann am Handy. */
    fun setRoutineDone(routine: WatchRoutine, done: Boolean) {
        if (!routine.canToggle) return
        snapshots.update { OverviewLogic.withRoutine(it, routine.key, done) }
        send(OverviewLogic.routineAction(routine, done), undo = {})
    }

    /** Hakt eine Aufgabe ab (oder entfernt den Haken wieder): sofort auf der Uhr, dann am Handy. */
    fun setTaskDone(task: WatchTask, done: Boolean) {
        if (done) {
            _checkedTasks.update { OverviewLogic.checked(it, task) }
            snapshots.update { OverviewLogic.withoutTask(it, task.id) }
            send(OverviewLogic.taskAction(task, done = true)) {
                _checkedTasks.update { OverviewLogic.unchecked(it, task.id) }
            }
        } else if (OverviewLogic.canUncheck(task)) {
            _checkedTasks.update { OverviewLogic.unchecked(it, task.id) }
            snapshots.update { OverviewLogic.withTask(it, task) }
            send(OverviewLogic.taskAction(task, done = false)) {
                _checkedTasks.update { OverviewLogic.checked(it, task) }
            }
        }
    }

    /** Zeigt kurz einen Hinweis, z. B. „Handy nicht erreichbar“. */
    fun showNotice(text: String) {
        _notice.value = Notice(text = text, visible = true)
    }

    /** Der Hinweis wurde gezeigt. */
    fun dismissNotice() {
        _notice.update { it.copy(visible = false) }
    }

    /**
     * Schickt die Änderung ans Handy. Klappt das nicht, wird sie auf der Uhr zurückgenommen ([undo] und zurück
     * zum letzten Stand vom Handy) und ein Hinweis erscheint.
     */
    private fun send(action: ItemAction, undo: () -> Unit) {
        scope.launch {
            if (phone.sendItemAction(action)) return@launch
            undo()
            snapshots.discardLocalChanges()
            // Erreichbar, aber abgelehnt oder zu langsam: Dann zeigt eine frische Übersicht, was wirklich gilt.
            val reachable = phone.isReachable()
            showNotice(if (reachable) OverviewLogic.ACTION_FAILED else OverviewLogic.PHONE_UNREACHABLE)
            if (reachable) phone.requestSync()
        }
    }

    private fun needsSync(): Boolean = OverviewLogic.needsSync(snapshots.snapshot.value, System.currentTimeMillis())
}

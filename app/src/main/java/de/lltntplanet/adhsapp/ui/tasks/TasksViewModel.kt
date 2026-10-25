package de.lltntplanet.adhsapp.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.google.NotAuthorizedException
import de.lltntplanet.adhsapp.data.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class TasksViewModel(private val c: AppContainer) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>?> =
        c.tasks.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _syncing = MutableStateFlow(false)
    /** Eigener Abgleich oder die zentrale Aktualisierung (alle 5 Minuten / beim Öffnen / Herunterziehen). */
    val syncing: StateFlow<Boolean> = combine(_syncing, c.refresher.refreshing) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var pendingSync: Job? = null

    fun toggle(task: TaskEntity) {
        viewModelScope.launch {
            c.tasks.setDone(task, !task.done)
            if (!task.done && task.repeat != Repeat.NONE) _messages.tryEmit("Super! Nächstes Mal: ${task.repeat.label.lowercase()}")
            scheduleSync()
        }
    }

    fun save(task: TaskEntity) {
        viewModelScope.launch {
            val s = settings.value
            val withGoogle = if (task.id == 0L && task.googleListId == null && s.tasksConnected &&
                s.saveNewTasksToGoogle && task.repeat == Repeat.NONE
            ) {
                task.copy(googleListId = "@default")
            } else {
                task
            }
            c.tasks.save(withGoogle)
            scheduleSync()
        }
    }

    fun quickAdd(title: String, forToday: Boolean) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        save(TaskEntity(title = trimmed, dueEpochDay = if (forToday) LocalDate.now().toEpochDay() else null))
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch {
            if (task.googleTaskId != null && !c.tasksSync.deleteRemote(task)) {
                _messages.tryEmit("Bei Google Tasks konnte die Aufgabe nicht gelöscht werden.")
            }
            c.tasks.delete(task)
        }
    }

    fun postpone(task: TaskEntity) {
        viewModelScope.launch {
            c.tasks.snoozeToTomorrow(task)
            _messages.tryEmit("Auf morgen verschoben – das ist völlig okay.")
            scheduleSync()
        }
    }

    /** Alles neu laden – Herunterziehen auf dem Aufgaben-Bildschirm. */
    fun refreshAll() {
        c.refresher.refreshLater(userInitiated = true)
    }

    fun sync(userInitiated: Boolean = true) {
        if (!settings.value.tasksConnected) return
        viewModelScope.launch {
            _syncing.value = true
            val result = runCatching { c.tasksSync.sync() }
            _syncing.value = false
            result.onSuccess {
                if (userInitiated) _messages.tryEmit("Mit Google Tasks abgeglichen")
            }.onFailure { e ->
                if (userInitiated || e is NotAuthorizedException) _messages.tryEmit(e.message ?: "Abgleich fehlgeschlagen")
            }
        }
    }

    /** Änderungen kurz sammeln und dann zu Google übertragen. */
    private fun scheduleSync() {
        if (!settings.value.tasksConnected) return
        pendingSync?.cancel()
        pendingSync = viewModelScope.launch {
            delay(1_500)
            sync(userInitiated = false)
        }
    }
}

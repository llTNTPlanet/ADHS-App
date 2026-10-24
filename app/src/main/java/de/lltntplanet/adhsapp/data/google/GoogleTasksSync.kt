package de.lltntplanet.adhsapp.data.google

import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskDao
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDate

data class TasksSyncResult(val pushed: Int, val imported: Int, val updated: Int, val removed: Int)

/**
 * Abgleich mit Google Tasks:
 * 1. Lokale Änderungen (abgehakt, bearbeitet, neu) werden zu Google übertragen.
 * 2. Danach werden alle Listen von Google geholt; lokale Kopien werden aktualisiert.
 * Uhrzeit, Erinnerung und "wichtig" bleiben lokal, weil Google Tasks dafür keine Felder hat.
 */
class GoogleTasksSync(
    private val api: GoogleTasksApi,
    private val dao: TaskDao,
    private val settings: SettingsRepository,
    private val onChanged: () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun sync(): TasksSyncResult = mutex.withLock {
        val pushed = push()
        val lists = api.lists()
        val remoteIds = mutableSetOf<String>()
        var imported = 0
        var updated = 0
        for (list in lists) {
            for (remote in api.tasks(list.id)) {
                val id = remote.id ?: continue
                if (remote.deleted == true || remote.title.isNullOrBlank() && remote.notes.isNullOrBlank()) continue
                remoteIds += id
                val local = dao.byGoogleId(id)
                if (local == null) {
                    dao.insert(fromRemote(remote, list))
                    imported++
                } else if (!local.dirty) {
                    val merged = mergeRemote(local, remote, list)
                    if (merged != local) {
                        dao.update(merged)
                        updated++
                    }
                }
            }
        }
        val stale = dao.googleLinked().filter { !it.dirty && it.googleTaskId !in remoteIds }.map { it.id }
        stale.chunked(500).forEach { dao.deleteIds(it) }
        settings.update { it.copy(lastTasksSync = System.currentTimeMillis()) }
        if (pushed + imported + updated + stale.size > 0) onChanged()
        TasksSyncResult(pushed, imported, updated, stale.size)
    }

    /** Löscht eine Aufgabe auch bei Google (Fehler werden ignoriert – beim nächsten Abgleich kommt sie sonst zurück). */
    suspend fun deleteRemote(task: TaskEntity): Boolean {
        val listId = task.googleListId ?: return true
        val taskId = task.googleTaskId ?: return true
        return runCatching { api.delete(listId, taskId) }.isSuccess
    }

    private suspend fun push(): Int {
        var count = 0
        for (task in dao.dirty()) {
            val listId = task.googleListId ?: run {
                dao.update(task.copy(dirty = false))
                null
            } ?: continue
            if (task.repeat != Repeat.NONE && task.googleTaskId == null) {
                // Google Tasks kennt über die API keine Wiederholungen – solche Aufgaben bleiben lokal.
                dao.update(task.copy(dirty = false, googleListId = null))
                continue
            }
            val due = task.dueEpochDay?.let { GTask.dueString(LocalDate.ofEpochDay(it)) }
            if (task.googleTaskId == null) {
                val created = api.insert(
                    listId,
                    GTask(
                        title = task.title,
                        notes = task.notes.ifBlank { null },
                        due = due,
                        status = if (task.done) "completed" else "needsAction",
                    ),
                )
                dao.update(task.copy(googleTaskId = created.id, dirty = false))
            } else {
                val patch = buildJsonObject {
                    put("title", JsonPrimitive(task.title))
                    put("notes", JsonPrimitive(task.notes))
                    put("due", due?.let(::JsonPrimitive) ?: JsonNull)
                    if (task.done) {
                        put("status", JsonPrimitive("completed"))
                        put("completed", JsonPrimitive(Instant.ofEpochMilli(task.doneAt ?: System.currentTimeMillis()).toString()))
                    } else {
                        put("status", JsonPrimitive("needsAction"))
                        put("completed", JsonNull)
                    }
                }
                try {
                    api.patch(listId, task.googleTaskId, patch)
                    dao.update(task.copy(dirty = false))
                } catch (e: GoogleApiException) {
                    if (e.code == 404) dao.update(task.copy(googleTaskId = null, googleListId = null, dirty = false))
                    else throw e
                }
            }
            count++
        }
        return count
    }

    companion object {
        fun fromRemote(remote: GTask, list: GTaskList): TaskEntity = TaskEntity(
            title = remote.title.orEmpty().ifBlank { "(ohne Titel)" },
            notes = remote.notes.orEmpty(),
            dueEpochDay = remote.dueDate?.toEpochDay(),
            remind = remote.dueDate != null,
            done = remote.isCompleted,
            doneAt = if (remote.isCompleted) remote.completed?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis() else null,
            googleTaskId = remote.id,
            googleListId = list.id,
            googleListTitle = list.title,
        )

        fun mergeRemote(local: TaskEntity, remote: GTask, list: GTaskList): TaskEntity {
            val remoteDue = remote.dueDate?.toEpochDay()
            return local.copy(
                title = remote.title.orEmpty().ifBlank { local.title },
                notes = remote.notes.orEmpty(),
                dueEpochDay = remoteDue,
                // Wird das Datum bei Google entfernt, ist auch die lokale Uhrzeit hinfällig.
                dueMinutes = if (remoteDue == null) null else local.dueMinutes,
                done = remote.isCompleted,
                doneAt = if (remote.isCompleted) (local.doneAt ?: System.currentTimeMillis()) else null,
                googleListId = list.id,
                googleListTitle = list.title,
            )
        }
    }
}

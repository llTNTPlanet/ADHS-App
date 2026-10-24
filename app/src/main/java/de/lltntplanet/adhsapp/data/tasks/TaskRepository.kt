package de.lltntplanet.adhsapp.data.tasks

import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskDao
import de.lltntplanet.adhsapp.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class TaskRepository(
    private val dao: TaskDao,
    private val onChanged: () -> Unit,
) {
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    suspend fun open(): List<TaskEntity> = dao.open()

    suspend fun byId(id: Long): TaskEntity? = dao.byId(id)

    suspend fun save(task: TaskEntity): Long {
        val id = if (task.id == 0L) {
            dao.insert(task.copy(dirty = task.googleListId != null))
        } else {
            dao.update(task.copy(dirty = task.dirty || task.googleTaskId != null || task.googleListId != null))
            task.id
        }
        onChanged()
        return id
    }

    /** Abhaken: wiederkehrende Aufgaben springen auf den nächsten Termin, statt erledigt zu werden. */
    suspend fun setDone(task: TaskEntity, done: Boolean, today: LocalDate = LocalDate.now()) {
        val due = TaskLogic.dueDate(task)
        val updated = if (done && task.repeat != Repeat.NONE && due != null) {
            task.copy(dueEpochDay = TaskLogic.nextDue(due, task.repeat, today).toEpochDay())
        } else {
            task.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null)
        }
        dao.update(updated.copy(dirty = updated.googleTaskId != null || updated.dirty))
        onChanged()
    }

    suspend fun setDone(taskId: Long, done: Boolean) {
        dao.byId(taskId)?.let { setDone(it, done) }
    }

    suspend fun snoozeToTomorrow(task: TaskEntity, today: LocalDate = LocalDate.now()) {
        dao.update(task.copy(dueEpochDay = today.plusDays(1).toEpochDay(), dirty = task.googleTaskId != null))
        onChanged()
    }

    suspend fun delete(task: TaskEntity) {
        dao.delete(task)
        onChanged()
    }

    suspend fun purgeOldDone(olderThanMillis: Long) = dao.purgeDone(System.currentTimeMillis() - olderThanMillis)
}

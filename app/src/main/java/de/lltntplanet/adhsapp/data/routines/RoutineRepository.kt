package de.lltntplanet.adhsapp.data.routines

import de.lltntplanet.adhsapp.data.db.RoutineDao
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.util.Dates
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class RoutineRepository(
    private val dao: RoutineDao,
    private val onChanged: () -> Unit,
) {
    fun observeAll(): Flow<List<RoutineEntity>> = dao.observeAll()

    fun observeLog(from: LocalDate, toInclusive: LocalDate): Flow<List<RoutineLogEntity>> =
        dao.observeLog(from.toEpochDay(), toInclusive.toEpochDay())

    suspend fun active(): List<RoutineEntity> = dao.active()

    suspend fun log(from: LocalDate, toInclusive: LocalDate): List<RoutineLogEntity> =
        dao.log(from.toEpochDay(), toInclusive.toEpochDay())

    suspend fun save(routine: RoutineEntity): Long {
        val id = dao.upsert(routine.copy(name = routine.name.trim(), dose = routine.dose.trim()))
        onChanged()
        return if (routine.id == 0L) id else routine.id
    }

    suspend fun delete(routine: RoutineEntity) {
        dao.deleteLogFor(routine.id)
        dao.delete(routine)
        onChanged()
    }

    /** Abhaken („Genommen“ / „Erledigt“) bzw. Haken wieder entfernen. */
    suspend fun setDone(routineId: Long, date: LocalDate, minutes: Int, done: Boolean) {
        if (done) {
            dao.insertLog(RoutineLogEntity(routineId = routineId, epochDay = date.toEpochDay(), minutes = minutes))
        } else {
            dao.deleteLog(routineId, date.toEpochDay(), minutes)
        }
        onChanged()
    }

    /** Überspringen: zählt als erledigt für die Erinnerung, wird aber als „übersprungen“ angezeigt. */
    suspend fun skip(routineId: Long, date: LocalDate, minutes: Int) {
        dao.insertLog(RoutineLogEntity(routineId = routineId, epochDay = date.toEpochDay(), minutes = minutes, skipped = true))
        onChanged()
    }

    /** Aus einer Erinnerung heraus abhaken: der Zeitpunkt steckt in [atMillis]. */
    suspend fun setDoneAt(routineId: Long, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val z = Dates.toZoned(atMillis, zone)
        setDone(routineId, z.toLocalDate(), z.hour * 60 + z.minute, true)
    }

    suspend fun purgeOldLog(days: Long) = dao.purgeLog(LocalDate.now().minusDays(days).toEpochDay())
}

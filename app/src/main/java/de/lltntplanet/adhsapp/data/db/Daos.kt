package de.lltntplanet.adhsapp.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, dueEpochDay IS NULL, dueEpochDay ASC, dueMinutes IS NULL, dueMinutes ASC, important DESC, createdAt ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0")
    suspend fun open(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE googleTaskId = :googleId")
    suspend fun byGoogleId(googleId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE dirty = 1")
    suspend fun dirty(): List<TaskEntity>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE googleTaskId IS NOT NULL")
    suspend fun googleLinked(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM tasks WHERE done = 1 AND doneAt < :before")
    suspend fun purgeDone(before: Long)
}

@Dao
interface BirthdayDao {
    @Query("SELECT * FROM birthdays ORDER BY month, day, name")
    fun observeAll(): Flow<List<BirthdayEntity>>

    @Query("SELECT * FROM birthdays")
    suspend fun all(): List<BirthdayEntity>

    @Upsert
    suspend fun upsert(birthday: BirthdayEntity): Long

    @Delete
    suspend fun delete(birthday: BirthdayEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT key FROM fired_reminders WHERE firedAt >= :since")
    suspend fun firedKeysSince(since: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markFired(items: List<FiredReminderEntity>)

    @Query("DELETE FROM fired_reminders WHERE firedAt < :before")
    suspend fun purgeFired(before: Long)

    @Query("SELECT * FROM snoozes ORDER BY triggerAt")
    suspend fun snoozes(): List<SnoozeEntity>

    @Insert
    suspend fun insertSnooze(snooze: SnoozeEntity): Long

    @Query("DELETE FROM snoozes WHERE id = :id")
    suspend fun deleteSnooze(id: Long)
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_entries WHERE epochDay = :epochDay ORDER BY createdAt")
    fun observeDay(epochDay: Long): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entries WHERE epochDay = :epochDay ORDER BY createdAt")
    suspend fun forDay(epochDay: Long): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay, createdAt")
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<FoodEntryEntity>>

    /** Zuletzt eingetragene Lebensmittel (je Name einmal) als Vorschläge. */
    @Query("SELECT * FROM food_entries WHERE id IN (SELECT MAX(id) FROM food_entries GROUP BY LOWER(name)) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<FoodEntryEntity>

    @Upsert
    suspend fun upsert(entry: FoodEntryEntity): Long

    @Delete
    suspend fun delete(entry: FoodEntryEntity)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY active DESC, times ASC, name ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE active = 1")
    suspend fun active(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun byId(id: Long): RoutineEntity?

    @Upsert
    suspend fun upsert(routine: RoutineEntity): Long

    @Delete
    suspend fun delete(routine: RoutineEntity)

    @Query("SELECT * FROM routine_log WHERE epochDay BETWEEN :fromDay AND :toDay")
    fun observeLog(fromDay: Long, toDay: Long): Flow<List<RoutineLogEntity>>

    @Query("SELECT * FROM routine_log WHERE epochDay BETWEEN :fromDay AND :toDay")
    suspend fun log(fromDay: Long, toDay: Long): List<RoutineLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: RoutineLogEntity): Long

    /** Nur eintragen, wenn die Einnahme noch nicht abgehakt oder übersprungen ist (sonst -1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLogIfAbsent(entry: RoutineLogEntity): Long

    @Query("DELETE FROM routine_log WHERE routineId = :routineId AND epochDay = :epochDay AND minutes = :minutes")
    suspend fun deleteLog(routineId: Long, epochDay: Long, minutes: Int)

    @Query("DELETE FROM routine_log WHERE routineId = :routineId")
    suspend fun deleteLogFor(routineId: Long)

    @Query("DELETE FROM routine_log WHERE epochDay < :beforeDay")
    suspend fun purgeLog(beforeDay: Long)
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM symptoms ORDER BY active DESC, sortOrder ASC, name ASC")
    fun observeSymptoms(): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptoms ORDER BY sortOrder ASC, name ASC")
    suspend fun symptoms(): List<SymptomEntity>

    @Query("SELECT COUNT(*) FROM symptoms")
    suspend fun symptomCount(): Int

    @Query("SELECT * FROM symptoms WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun symptomByName(name: String): SymptomEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM symptoms")
    suspend fun maxSortOrder(): Int

    @Upsert
    suspend fun upsertSymptom(symptom: SymptomEntity): Long

    @Upsert
    suspend fun upsertSymptoms(symptoms: List<SymptomEntity>)

    @Query("DELETE FROM symptoms WHERE id = :id")
    suspend fun deleteSymptom(id: Long)

    @Query("SELECT * FROM mood_entries WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay, minutes")
    fun observeEntries(fromDay: Long, toDay: Long): Flow<List<MoodEntryEntity>>

    @Query("SELECT * FROM mood_entries WHERE epochDay = :epochDay AND minutes = :minutes")
    fun observeCheckup(epochDay: Long, minutes: Int): Flow<List<MoodEntryEntity>>

    /** Der zuletzt erfasste Wert je Symptom – als Vorbelegung für den nächsten Checkup. */
    @Query("SELECT * FROM mood_entries WHERE id IN (SELECT MAX(id) FROM mood_entries GROUP BY symptomId)")
    suspend fun lastValues(): List<MoodEntryEntity>

    @Query("SELECT COUNT(*) FROM mood_entries WHERE symptomId = :symptomId")
    suspend fun entryCount(symptomId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<MoodEntryEntity>)

    @Query("DELETE FROM mood_entries WHERE epochDay = :epochDay AND minutes = :minutes")
    suspend fun deleteCheckup(epochDay: Long, minutes: Int)
}

@Dao
interface DispenserDao {
    @Query("SELECT * FROM dispenser_events ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DispenserEventEntity>>

    /** -1, wenn dieselbe Benachrichtigung schon gespeichert ist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: DispenserEventEntity): Long

    @Query("DELETE FROM dispenser_events WHERE postedAt < :before")
    suspend fun purge(before: Long)

    @Query("DELETE FROM dispenser_events")
    suspend fun clear()
}

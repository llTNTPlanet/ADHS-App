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

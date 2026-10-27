package de.lltntplanet.adhsapp.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, BirthdayEntity::class, FiredReminderEntity::class, SnoozeEntity::class, FoodEntryEntity::class, RoutineEntity::class, RoutineLogEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun birthdayDao(): BirthdayDao
    abstract fun reminderDao(): ReminderDao
    abstract fun foodDao(): FoodDao
    abstract fun routineDao(): RoutineDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "adhs.db").build()
    }
}

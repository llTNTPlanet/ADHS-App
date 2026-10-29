package de.lltntplanet.adhsapp.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, BirthdayEntity::class, FiredReminderEntity::class, SnoozeEntity::class, FoodEntryEntity::class, RoutineEntity::class, RoutineLogEntity::class, SymptomEntity::class, MoodEntryEntity::class, DispenserEventEntity::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun birthdayDao(): BirthdayDao
    abstract fun reminderDao(): ReminderDao
    abstract fun foodDao(): FoodDao
    abstract fun routineDao(): RoutineDao
    abstract fun moodDao(): MoodDao
    abstract fun dispenserDao(): DispenserDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "adhs.db").build()
    }
}

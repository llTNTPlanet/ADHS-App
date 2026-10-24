package de.lltntplanet.adhsapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, BirthdayEntity::class, FiredReminderEntity::class, SnoozeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun birthdayDao(): BirthdayDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "adhs.db").build()
    }
}

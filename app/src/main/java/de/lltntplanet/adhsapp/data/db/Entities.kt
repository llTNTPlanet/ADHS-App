package de.lltntplanet.adhsapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Repeat(val label: String) {
    NONE("Nie"),
    DAILY("Täglich"),
    WEEKDAYS("Werktags (Mo–Fr)"),
    WEEKLY("Wöchentlich"),
    MONTHLY("Monatlich"),
}

@Entity(
    tableName = "tasks",
    indices = [Index(value = ["googleTaskId"], unique = true)],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** Fälligkeitsdatum als epochDay (LocalDate.toEpochDay), null = ohne Datum. */
    val dueEpochDay: Long? = null,
    /** Uhrzeit in Minuten seit Mitternacht, null = ganztägig. */
    val dueMinutes: Int? = null,
    val remind: Boolean = true,
    val important: Boolean = false,
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Verknüpfung mit Google Tasks (null = nur lokal). */
    val googleTaskId: String? = null,
    val googleListId: String? = null,
    val googleListTitle: String? = null,
    /** Lokale Änderung, die noch zu Google übertragen werden muss. */
    val dirty: Boolean = false,
)

@Entity(tableName = "birthdays")
data class BirthdayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val month: Int,
    val day: Int,
    /** Geburtsjahr, falls bekannt (für das Alter). */
    val year: Int? = null,
    val note: String = "",
)

/** Bereits ausgelöste Erinnerungen, damit nichts doppelt kommt. */
@Entity(tableName = "fired_reminders")
data class FiredReminderEntity(
    @PrimaryKey val key: String,
    val firedAt: Long,
)

/** Geschlummerte Erinnerungen (Inhalt als JSON). */
@Entity(tableName = "snoozes")
data class SnoozeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerAt: Long,
    val payload: String,
)

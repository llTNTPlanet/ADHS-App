package de.lltntplanet.adhsapp.data.db

import androidx.room.ColumnInfo
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

enum class MealType(val label: String) {
    BREAKFAST("Morgens"),
    LUNCH("Mittags"),
    DINNER("Abends"),
    SNACK("Zwischendurch"),
}

/** Ein gegessenes Lebensmittel / eine Mahlzeit mit Kalorien. */
@Entity(tableName = "food_entries", indices = [Index(value = ["epochDay"])])
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Tag als epochDay (LocalDate.toEpochDay). */
    val epochDay: Long,
    val meal: MealType,
    val name: String,
    val kcal: Int,
    /** Optionale Menge, z. B. "200 g" oder "1 Stück". */
    val amount: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

enum class RoutineKind(val label: String) {
    MEDICATION("Medikament"),
    ROUTINE("Routine"),
    /** Gefühls-Checkup: statt Abhaken werden alle Symptome mit einem Schieberegler 1–10 abgefragt. */
    CHECKUP("Checkup"),
}

/**
 * Eine Routine oder ein Medikament mit festen Uhrzeiten (wie in MyTherapy):
 * z. B. „Methylphenidat 10 mg“ um 8:00 und 14:00 an allen Tagen.
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: RoutineKind = RoutineKind.MEDICATION,
    /** Dosis bzw. Hinweis, z. B. „1 Tablette“ oder „mit Wasser“. */
    val dose: String = "",
    /** Uhrzeiten in Minuten seit Mitternacht, aufsteigend, durch Komma getrennt (z. B. „480,840“). */
    val times: String,
    /** Wochentage als Bitmaske: Bit 0 = Montag … Bit 6 = Sonntag; 127 = täglich. Bei einmaligen Einträgen ohne Bedeutung. */
    val weekdays: Int = ALL_DAYS,
    /**
     * Einmaliges Ereignis: nur an diesem Tag (epochDay, LocalDate.toEpochDay) zu den Uhrzeiten, ohne Wiederholung.
     * null = regelmäßig an den gewählten Wochentagen.
     */
    val onceEpochDay: Long? = null,
    /** Pausiert = keine Erinnerungen, bleibt aber in der Liste. */
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Liegt im Medikamentenspender (Memio Go): Meldet die App iDispenser „genommen“, wird die Einnahme
     * automatisch abgehakt. Die eigene Erinnerung kommt dann erst später als Rückfall (Einstellungen).
     */
    @ColumnInfo(defaultValue = "0")
    val dispenser: Boolean = false,
) {
    companion object {
        const val ALL_DAYS = 0b1111111
    }

    val isOnce: Boolean get() = onceEpochDay != null
}

/** Eine abgehakte (oder übersprungene) Einnahme / Routine zu einem Zeitpunkt. */
@Entity(
    tableName = "routine_log",
    indices = [Index(value = ["routineId", "epochDay", "minutes"], unique = true), Index(value = ["epochDay"])],
)
data class RoutineLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val epochDay: Long,
    val minutes: Int,
    val skipped: Boolean = false,
    val doneAt: Long = System.currentTimeMillis(),
    /** Automatisch abgehakt, weil der Medikamentenspender die Entnahme gemeldet hat. */
    @ColumnInfo(defaultValue = "0")
    val byDispenser: Boolean = false,
)

/**
 * Ein Symptom, das beim Gefühls-Checkup abgefragt wird (z. B. „Müdigkeit“), wie in MyTherapy.
 * Entfernte Symptome bleiben mit [active] = false erhalten, damit die Statistik ihre alten Werte behält.
 */
@Entity(tableName = "symptoms", indices = [Index(value = ["name"], unique = true)])
data class SymptomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Ein Wert (1–10) eines Symptoms aus einem Checkup zu einem Zeitpunkt. */
@Entity(
    tableName = "mood_entries",
    indices = [Index(value = ["symptomId", "epochDay", "minutes"], unique = true), Index(value = ["epochDay"])],
)
data class MoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symptomId: Long,
    val epochDay: Long,
    /** Geplante Uhrzeit des Checkups in Minuten seit Mitternacht (wie in routine_log). */
    val minutes: Int,
    /** Stärke 1 (gar nicht) bis 10 (sehr stark). */
    val value: Int,
    val recordedAt: Long = System.currentTimeMillis(),
)

/** Art einer mitgelesenen Meldung des Medikamentenspenders. */
enum class DispenserEventKind(val label: String) {
    /** Fach geleert: Einnahme wird abgehakt. */
    TAKEN("Genommen"),
    MISSED("Verpasst"),
    /** Einnahmezeit ist da, das Fach ist offen. */
    DUE("Fällig"),
    REFILL("Nachfüllen"),
    /** Nicht erkannt – bleibt zur Ansicht gespeichert, damit die Erkennung angepasst werden kann. */
    OTHER("Unbekannt"),
}

/**
 * Eine Benachrichtigung der Spender-App (iDispenser für den Memio Go), mitgelesen über den
 * Benachrichtigungszugriff. Gespeichert wird nur, was in der Benachrichtigung steht.
 */
@Entity(
    tableName = "dispenser_events",
    indices = [Index(value = ["notificationKey", "postedAt"], unique = true), Index(value = ["postedAt"])],
)
data class DispenserEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Zeitpunkt der Benachrichtigung (StatusBarNotification.postTime). */
    val postedAt: Long,
    /** Schlüssel der Benachrichtigung – zusammen mit [postedAt] gegen doppelte Einträge. */
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val kind: DispenserEventKind,
    /** Bei „genommen“: Tag (epochDay) und Uhrzeit (Minuten) der abgehakten Einnahme, sonst null. */
    val matchedEpochDay: Long? = null,
    val matchedMinutes: Int? = null,
    /** Namen der dabei abgehakten Medikamente, für die Anzeige. */
    val matchedNames: String = "",
)

package de.lltntplanet.adhsapp.reminders

import kotlinx.serialization.Serializable

enum class ReminderKind(val label: String) {
    EVENT("Termin"),
    BIRTHDAY("Geburtstag"),
    TASK("Aufgabe"),
    MEDICATION("Medikament"),
    ROUTINE("Routine"),
    /** Nach einem Essens-Termin: ans Eintragen der Mahlzeit erinnern (nur Benachrichtigung, kein Vollbild). */
    MEAL("Mahlzeit"),
    ;

    /** Stille Erinnerung ohne Vollbild und Klingeln – nur eine normale Benachrichtigung. */
    val isQuiet: Boolean get() = this == MEAL

    /** Routinen und Medikamente werden gleich behandelt (abhaken statt erledigen). */
    val isRoutine: Boolean get() = this == MEDICATION || this == ROUTINE
}

/** Eine konkrete Erinnerung zu einem Zeitpunkt. [key] ist pro Vorkommen und Vorlaufzeit eindeutig. */
@Serializable
data class Reminder(
    val key: String,
    val kind: ReminderKind,
    val triggerAt: Long,
    val title: String,
    /** Ort, Notiz o. Ä. */
    val detail: String? = null,
    /** Beginn des Termins bzw. Fälligkeit der Aufgabe bzw. Tag des Geburtstags (Mitternacht). */
    val targetTime: Long? = null,
    val targetEnd: Long? = null,
    val allDay: Boolean = false,
    /** Termin-ID im Kalender bzw. Aufgaben-ID. */
    val refId: Long? = null,
    val color: Int? = null,
    val age: Int? = null,
    val daysBefore: Int? = null,
    /** true, wenn die Erinnerung aus "Schlummern" stammt. */
    val snoozed: Boolean = false,
    /** Bei geschlummerten Erinnerungen der Schlüssel der ursprünglichen Erinnerung. */
    val originKey: String? = null,
) {
    val notificationId: Int get() = key.hashCode()

    /** Gleiches Vorkommen (Termin/Geburtstag) unabhängig von der Vorlaufzeit. */
    val occurrenceKey: String get() = if (snoozed) key else key.substringBeforeLast(':')

    /** Vorkommen, das mit „Keine weiteren Erinnerungen“ stummgeschaltet wird – auch aus einer geschlummerten Erinnerung heraus. */
    val muteKey: String get() = (originKey ?: key).substringBeforeLast(':')

    /** Lässt sich dieses Vorkommen stummschalten? (Nicht bei Test-Erinnerungen.) */
    val canMute: Boolean get() = kind == ReminderKind.EVENT && refId != null && !(originKey ?: key).startsWith("test:")
}

package de.lltntplanet.adhsapp.reminders

import kotlinx.serialization.Serializable

enum class ReminderKind(val label: String) {
    EVENT("Termin"),
    BIRTHDAY("Geburtstag"),
    TASK("Aufgabe"),
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
) {
    val notificationId: Int get() = key.hashCode()

    /** Gleiches Vorkommen (Termin/Geburtstag) unabhängig von der Vorlaufzeit. */
    val occurrenceKey: String get() = if (snoozed) key else key.substringBeforeLast(':')
}

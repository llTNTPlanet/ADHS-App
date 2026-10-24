package de.lltntplanet.adhsapp.data.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String?,
    val color: Int,
    val canWrite: Boolean,
    val isPrimary: Boolean,
) {
    val isGoogle: Boolean get() = accountType == "com.google"

    /** Der automatisch erzeugte Google-Geburtstagskalender der Kontakte. */
    val isBirthdayCalendar: Boolean
        get() = ownerAccount?.contains("#contacts@group.v.calendar.google.com") == true ||
            ownerAccount?.contains("addressbook#contacts") == true
}

data class EventInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
    val description: String?,
    val calendarName: String,
    val recurring: Boolean,
    val declined: Boolean,
    val canceled: Boolean,
    val canEdit: Boolean,
) {
    /** Erster Tag des Termins. Ganztägige Termine sind in UTC gespeichert. */
    fun startDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** Letzter Tag (inklusive), an dem der Termin stattfindet. */
    fun endDateInclusive(zone: ZoneId): LocalDate {
        val z = if (allDay) ZoneOffset.UTC else zone
        val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(z).toLocalDate()
        val first = startDate(zone)
        return if (last.isBefore(first)) first else last
    }

    fun occursOn(date: LocalDate, zone: ZoneId): Boolean =
        !date.isBefore(startDate(zone)) && !date.isAfter(endDateInclusive(zone))

    val displayTitle: String get() = title.ifBlank { "(Kein Titel)" }
}

/** Daten für das Anlegen oder Bearbeiten eines Termins. */
data class EventDraft(
    val eventId: Long? = null,
    val calendarId: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    /** Lokale Start-/Endzeit (bei ganztägigen Terminen nur das Datum relevant). */
    val start: java.time.LocalDateTime,
    val end: java.time.LocalDateTime,
    /** Erinnerungen in Minuten vor Beginn. */
    val reminders: List<Int> = listOf(15),
)

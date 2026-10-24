package de.lltntplanet.adhsapp

import de.lltntplanet.adhsapp.data.calendar.EventInstance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val Berlin: ZoneId = ZoneId.of("Europe/Berlin")

fun millis(dateTime: String, zone: ZoneId = Berlin): Long =
    LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

fun event(
    id: Long,
    start: String,
    end: String,
    title: String = "Termin $id",
    calendarId: Long = 1,
    declined: Boolean = false,
    canceled: Boolean = false,
) = EventInstance(
    eventId = id,
    calendarId = calendarId,
    title = title,
    begin = millis(start),
    end = millis(end),
    allDay = false,
    color = 0xFF0B57D0.toInt(),
    location = null,
    description = null,
    calendarName = "Kalender",
    recurring = false,
    declined = declined,
    canceled = canceled,
    canEdit = true,
)

/** Ganztägiger Termin – wie im Kalender-Speicher in UTC gespeichert. */
fun allDayEvent(id: Long, date: LocalDate, days: Long = 1, title: String = "Ganztägig $id") = EventInstance(
    eventId = id,
    calendarId = 1,
    title = title,
    begin = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    end = date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    allDay = true,
    color = 0xFF33B679.toInt(),
    location = null,
    description = null,
    calendarName = "Kalender",
    recurring = false,
    declined = false,
    canceled = false,
    canEdit = true,
)

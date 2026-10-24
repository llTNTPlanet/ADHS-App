package de.lltntplanet.adhsapp.reminders

import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Reine Logik: Welche Erinnerungen ergeben sich aus Terminen, Geburtstagen und Aufgaben? */
object ReminderPlanner {

    /** Längste berücksichtigte Vorlaufzeit (eine Woche). */
    const val MAX_OFFSET_MINUTES = 7 * 24 * 60

    fun planEvents(
        events: List<EventInstance>,
        calendarReminders: Map<Long, List<Int>>,
        settings: AppSettings,
        excludedCalendars: Set<Long>,
        birthdays: List<Birthday>,
        zone: ZoneId,
    ): List<Reminder> = events.flatMap { e ->
        if (e.canceled || e.declined || e.calendarId in excludedCalendars) return@flatMap emptyList()
        if (e.allDay) {
            if (!settings.allDayReminders) return@flatMap emptyList()
            val date = e.startDate(zone)
            if (isBirthdayEvent(e, date, birthdays)) return@flatMap emptyList()
            listOf(
                Reminder(
                    key = "ev:${e.eventId}:${e.begin}:allday",
                    kind = ReminderKind.EVENT,
                    triggerAt = at(date, settings.allDayReminderMinutes, zone),
                    title = e.displayTitle,
                    detail = e.location,
                    targetTime = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    targetEnd = e.endDateInclusive(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    allDay = true,
                    refId = e.eventId,
                    color = e.color,
                ),
            )
        } else {
            val fromCalendar = if (settings.useCalendarReminders) calendarReminders[e.eventId].orEmpty() else emptyList()
            (settings.eventOffsets + fromCalendar)
                .filter { it in 0..MAX_OFFSET_MINUTES }
                .distinct()
                .map { offset ->
                    Reminder(
                        key = "ev:${e.eventId}:${e.begin}:$offset",
                        kind = ReminderKind.EVENT,
                        triggerAt = e.begin - offset * 60_000L,
                        title = e.displayTitle,
                        detail = e.location,
                        targetTime = e.begin,
                        targetEnd = e.end,
                        refId = e.eventId,
                        color = e.color,
                    )
                }
        }
    }

    /** Ganztägige Termine wie "Annas Geburtstag" werden schon von der Geburtstagserinnerung abgedeckt. */
    internal fun isBirthdayEvent(e: EventInstance, date: LocalDate, birthdays: List<Birthday>): Boolean {
        val title = e.title.trim().lowercase()
        val looksLikeBirthday = "geburtstag" in title || "birthday" in title || "🎂" in title
        return birthdays.any { b ->
            val name = b.name.trim().lowercase()
            val firstName = name.substringBefore(' ')
            BirthdayMath.occurrenceIn(date.year, b.month, b.day) == date &&
                firstName.length >= 2 && firstName in title &&
                (looksLikeBirthday || title == name)
        }
    }

    fun planBirthdays(
        birthdays: List<Birthday>,
        settings: AppSettings,
        from: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId,
    ): List<Reminder> {
        if (!settings.birthdayReminders) return emptyList()
        val result = mutableListOf<Reminder>()
        for (b in birthdays) {
            for (daysBefore in settings.birthdayDaysBefore) {
                for (year in from.year..toInclusive.year + 1) {
                    val occurrence = BirthdayMath.occurrenceIn(year, b.month, b.day)
                    val remindOn = occurrence.minusDays(daysBefore.toLong())
                    if (remindOn.isBefore(from) || remindOn.isAfter(toInclusive)) continue
                    result += Reminder(
                        key = "bd:${b.id}:$occurrence:$daysBefore",
                        kind = ReminderKind.BIRTHDAY,
                        triggerAt = at(remindOn, settings.birthdayReminderMinutes, zone),
                        title = b.name,
                        detail = b.note.ifBlank { null },
                        targetTime = occurrence.atStartOfDay(zone).toInstant().toEpochMilli(),
                        targetEnd = remindOn.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        allDay = true,
                        age = b.ageOn(occurrence),
                        daysBefore = daysBefore,
                    )
                }
            }
        }
        return result
    }

    fun planTasks(tasks: List<TaskEntity>, settings: AppSettings, zone: ZoneId): List<Reminder> =
        tasks.mapNotNull { t ->
            val at = TaskLogic.reminderAt(t, settings.taskDefaultMinutes, zone) ?: return@mapNotNull null
            Reminder(
                key = "task:${t.id}:${t.dueEpochDay}:${t.dueMinutes ?: -1}",
                kind = ReminderKind.TASK,
                triggerAt = at,
                title = t.title,
                detail = t.notes.ifBlank { null },
                targetTime = at,
                allDay = t.dueMinutes == null,
                refId = t.id,
            )
        }

    /**
     * Ist die Erinnerung jetzt fällig? Verpasste Erinnerungen (z. B. Handy war aus) kommen noch,
     * solange der Termin noch läuft bzw. der Geburtstag noch nicht vorbei ist.
     */
    fun isDue(r: Reminder, now: Long, graceMillis: Long = GRACE_MILLIS, earlyMillis: Long = EARLY_MILLIS): Boolean =
        r.triggerAt <= now + earlyMillis &&
            (r.snoozed || r.triggerAt >= now - graceMillis || (r.targetEnd != null && r.targetEnd > now))

    /** Mehrere fällige Vorwarnungen desselben Termins: nur die aktuellste zeigen. */
    fun collapse(due: List<Reminder>): List<Reminder> =
        due.groupBy { it.occurrenceKey }.values.map { group -> group.maxBy { it.triggerAt } }.sortedBy { it.triggerAt }

    const val GRACE_MILLIS = 15 * 60_000L
    const val EARLY_MILLIS = 30_000L

    private fun at(date: LocalDate, minutesOfDay: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of((minutesOfDay / 60).coerceIn(0, 23), minutesOfDay % 60)).atZone(zone).toInstant().toEpochMilli()
}

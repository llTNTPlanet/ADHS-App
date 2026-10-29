package de.lltntplanet.adhsapp.reminders

import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
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
        if (isIgnoredTitle(e.title, settings.ignoredEventTitles)) return@flatMap emptyList()
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
            // Essens-Termine bekommen genau ihre eigenen Vorwarnzeiten – ohne allgemeine Vorgabe und ohne Google-Kalender-Extras.
            val offsets = if (isMealTitle(e.title, settings.mealEventTitles)) {
                settings.mealOffsets
            } else {
                val fromCalendar = if (settings.useCalendarReminders) calendarReminders[e.eventId].orEmpty() else emptyList()
                settings.eventOffsets + fromCalendar
            }
            offsets
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

    /**
     * Essens-Termine (z. B. „Frühstück“): nach dem Ende eine stille Erinnerung, die Mahlzeit einzutragen.
     * Unabhängig von den ignorierten Titeln – auch Essens-Termine ohne Vorwarnung sollen hinterher ans
     * Eintragen erinnern. Die Vorwarnungen davor (eigene Zeiten aus mealOffsets) kommen aus [planEvents].
     */
    fun planMeals(
        events: List<EventInstance>,
        settings: AppSettings,
        excludedCalendars: Set<Long>,
    ): List<Reminder> {
        if (!settings.mealReminders) return emptyList()
        return events.mapNotNull { e ->
            if (e.canceled || e.declined || e.allDay || e.calendarId in excludedCalendars) return@mapNotNull null
            if (!isMealTitle(e.title, settings.mealEventTitles)) return@mapNotNull null
            val end = if (e.end > e.begin) e.end else e.begin
            Reminder(
                key = "meal:${e.eventId}:${e.begin}:${settings.mealReminderMinutesAfter}",
                kind = ReminderKind.MEAL,
                triggerAt = end + settings.mealReminderMinutesAfter.coerceAtLeast(0) * 60_000L,
                title = e.displayTitle,
                targetTime = e.begin,
                // Verpasste Erinnerungen (Handy aus) kommen bis zu drei Stunden nach dem Essen noch.
                targetEnd = end + MEAL_CATCH_UP_MILLIS,
                refId = e.eventId,
                color = e.color,
            )
        }
    }

    /** Enthält der Titel eines der Essens-Wörter (als Wort, Groß-/Kleinschreibung egal)? */
    internal fun isMealTitle(title: String, mealWords: Set<String>): Boolean {
        val t = title.trim().lowercase()
        if (t.isEmpty()) return false
        val words = t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return mealWords.any { w ->
            val needle = w.trim().lowercase()
            needle.isNotEmpty() && (t == needle || needle in words)
        }
    }

    /** Termine wie „Frühstück“ oder „Mittagessen“, an die der Nutzer nicht erinnert werden will. */
    internal fun isIgnoredTitle(title: String, ignored: Set<String>): Boolean {
        val t = title.trim()
        return ignored.any { it.trim().equals(t, ignoreCase = true) }
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
     * Routinen und Medikamente: eine Erinnerung je Uhrzeit und Tag. Schon abgehakte oder übersprungene
     * Einnahmen (z. B. vorab genommen) lösen nichts mehr aus. Verpasste kommen bis zu drei Stunden nach.
     * Medikamente im Spender klingeln dort selbst: Die eigene Erinnerung kommt erst [dispenserDelayMinutes]
     * später als Rückfall – meldet der Spender vorher „genommen“, ist sie abgehakt und fällt weg. null = keine.
     */
    fun planRoutines(
        routines: List<RoutineEntity>,
        log: List<RoutineLogEntity>,
        from: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId,
        dispenserDelayMinutes: Int? = 0,
        /** Liest die App den Spender überhaupt mit (Benachrichtigungszugriff)? Sonst wie jedes andere Medikament. */
        dispenserWatched: Boolean = true,
    ): List<Reminder> =
        RoutineLogic.occurrences(routines, log, from, toInclusive, zone)
            .filter { !it.done && !it.skipped }
            .mapNotNull { o ->
                val inDispenser = dispenserWatched && o.routine.dispenser && o.routine.kind == RoutineKind.MEDICATION
                val delayMinutes = if (inDispenser) dispenserDelayMinutes ?: return@mapNotNull null else 0
                val detail = listOfNotNull(
                    o.routine.dose.ifBlank { null },
                    if (inDispenser) "Der Spender hat noch keine Entnahme gemeldet." else null,
                ).joinToString(" · ").ifBlank { null }
                Reminder(
                    key = o.key,
                    kind = when (o.routine.kind) {
                        RoutineKind.MEDICATION -> ReminderKind.MEDICATION
                        RoutineKind.ROUTINE -> ReminderKind.ROUTINE
                        RoutineKind.CHECKUP -> ReminderKind.CHECKUP
                    },
                    triggerAt = o.at + delayMinutes * 60_000L,
                    title = o.routine.name,
                    detail = detail,
                    targetTime = o.at,
                    targetEnd = o.at + RoutineLogic.CATCH_UP_MILLIS,
                    refId = o.routine.id,
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
    const val MEAL_CATCH_UP_MILLIS = 3 * 60 * 60_000L
    const val EARLY_MILLIS = 30_000L

    private fun at(date: LocalDate, minutesOfDay: Int, zone: ZoneId): Long =
        date.atTime(LocalTime.of((minutesOfDay / 60).coerceIn(0, 23), minutesOfDay % 60)).atZone(zone).toInstant().toEpochMilli()
}

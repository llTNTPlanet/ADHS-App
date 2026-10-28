package de.lltntplanet.adhsapp.reminders

import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.LocalDate
import java.time.ZoneId

/** Texte für Benachrichtigung und Vollbild – kurz, klar, mit Zeitbezug. */
object ReminderText {

    fun headline(r: Reminder): String = when (r.kind) {
        ReminderKind.BIRTHDAY -> "🎂 ${r.title}"
        ReminderKind.MEDICATION -> "💊 ${r.title}"
        ReminderKind.MEAL -> "🍽️ ${r.title} eintragen"
        ReminderKind.CHECKUP -> "💭 ${r.title}"
        else -> r.title
    }

    fun notificationTitle(r: Reminder): String = when (r.kind) {
        ReminderKind.EVENT -> r.title
        ReminderKind.TASK -> "Aufgabe: ${r.title}"
        ReminderKind.BIRTHDAY -> "🎂 ${r.title}"
        ReminderKind.MEDICATION -> "💊 ${r.title}"
        ReminderKind.ROUTINE -> "Routine: ${r.title}"
        ReminderKind.MEAL -> "🍽️ ${r.title} eintragen?"
        ReminderKind.CHECKUP -> "💭 ${r.title}"
    }

    fun subtitle(r: Reminder, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Dates.toLocalDate(now, zone)
        return when (r.kind) {
            ReminderKind.EVENT -> eventSubtitle(r, now, today, zone)
            ReminderKind.TASK -> taskSubtitle(r, now, today, zone)
            ReminderKind.BIRTHDAY -> birthdaySubtitle(r, today, zone)
            ReminderKind.MEDICATION, ReminderKind.ROUTINE -> routineSubtitle(r, now, today, zone)
            ReminderKind.MEAL -> mealSubtitle(r, today, zone)
            ReminderKind.CHECKUP -> checkupSubtitle(r, now, today, zone)
        }
    }

    private fun mealSubtitle(r: Reminder, today: LocalDate, zone: ZoneId): String {
        val start = r.targetTime ?: return "Was gab es? Per Foto oder Chat eintragen."
        val day = Dates.toLocalDate(start, zone)
        val dayPrefix = if (day == today) "" else "${TimeFormat.relativeDay(day, today)}, "
        return "Was gab es um $dayPrefix${TimeFormat.time(start, zone)} Uhr? Per Foto oder Chat eintragen."
    }

    private fun checkupSubtitle(r: Reminder, now: Long, today: LocalDate, zone: ZoneId): String {
        val at = r.targetTime ?: return "Wie geht es dir gerade? Kurz die Symptome bewerten."
        val day = Dates.toLocalDate(at, zone)
        val dayPrefix = if (day == today) "" else "${TimeFormat.relativeDay(day, today)}, "
        return when {
            at - now >= 60_000L -> "Checkup ${TimeFormat.countdown(at, now)} · $dayPrefix${TimeFormat.time(at, zone)}"
            at < now - 60_000L -> "Checkup war um $dayPrefix${TimeFormat.time(at, zone)} · noch offen – wie geht es dir?"
            else -> "Wie geht es dir gerade? Kurz die Symptome bewerten."
        }
    }

    private fun eventSubtitle(r: Reminder, now: Long, today: LocalDate, zone: ZoneId): String {
        val start = r.targetTime ?: return "Termin"
        val day = Dates.toLocalDate(start, zone)
        if (r.allDay) return "${TimeFormat.relativeDay(day, today)} · ganztägig"
        val end = r.targetEnd
        val range = if (end != null && end > start) "${TimeFormat.time(start, zone)}–${TimeFormat.time(end, zone)}"
        else TimeFormat.time(start, zone)
        val dayPrefix = if (day == today) "" else "${TimeFormat.relativeDay(day, today)}, "
        return when {
            start - now >= 60_000L -> "Beginnt ${TimeFormat.countdown(start, now)} · $dayPrefix$range"
            end != null && end > now && start < now - 60_000L -> "Läuft ${TimeFormat.countdown(start, now)} · bis ${TimeFormat.time(end, zone)}"
            else -> "Beginnt jetzt · $range"
        }
    }

    private fun taskSubtitle(r: Reminder, now: Long, today: LocalDate, zone: ZoneId): String {
        val due = r.targetTime ?: return "Jetzt erledigen"
        val day = Dates.toLocalDate(due, zone)
        return if (r.allDay) "Fällig: ${TimeFormat.relativeDay(day, today)}"
        else if (due > now + 60_000L) "Fällig ${TimeFormat.countdown(due, now)} · ${TimeFormat.time(due, zone)}"
        else "Jetzt erledigen · fällig ${TimeFormat.relativeDay(day, today).lowercase()} ${TimeFormat.time(due, zone)}"
    }

    private fun routineSubtitle(r: Reminder, now: Long, today: LocalDate, zone: ZoneId): String {
        val verb = if (r.kind == ReminderKind.MEDICATION) "Einnahme" else "Geplant"
        val at = r.targetTime ?: return if (r.kind == ReminderKind.MEDICATION) "Jetzt nehmen" else "Jetzt dran"
        val day = Dates.toLocalDate(at, zone)
        val dayPrefix = if (day == today) "" else "${TimeFormat.relativeDay(day, today)}, "
        val dose = r.detail?.let { " · $it" }.orEmpty()
        return when {
            at - now >= 60_000L -> "$verb ${TimeFormat.countdown(at, now)} · $dayPrefix${TimeFormat.time(at, zone)}$dose"
            at < now - 60_000L -> "$verb war um $dayPrefix${TimeFormat.time(at, zone)} · noch offen$dose"
            else -> "Jetzt · ${TimeFormat.time(at, zone)}$dose"
        }
    }

    private fun birthdaySubtitle(r: Reminder, today: LocalDate, zone: ZoneId): String {
        val date = r.targetTime?.let { Dates.toLocalDate(it, zone) } ?: today
        val days = Dates.daysBetween(today, date)
        val agePart = r.age?.let { " und wird $it" }.orEmpty()
        return when {
            days <= 0L -> "Hat heute Geburtstag$agePart 🎉"
            days == 1L -> "Hat morgen Geburtstag$agePart"
            else -> "Hat in $days Tagen Geburtstag (${TimeFormat.relativeDay(date, today)})$agePart"
        }
    }

    /** Großer Countdown im Vollbild, null wenn nicht sinnvoll. */
    fun bigCountdown(r: Reminder, now: Long): String? {
        val target = r.targetTime ?: return null
        if (r.allDay || r.kind == ReminderKind.BIRTHDAY || r.kind.isRoutine || r.kind.isCheckup || r.kind.isQuiet) return null
        return TimeFormat.countdown(target, now).replaceFirstChar { it.titlecase(TimeFormat.locale) }
    }
}

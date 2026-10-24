package de.lltntplanet.adhsapp.data.tasks

import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class TaskSection(val label: String) {
    OVERDUE("Überfällig"),
    TODAY("Heute"),
    TOMORROW("Morgen"),
    UPCOMING("Demnächst"),
    NO_DATE("Ohne Datum"),
    DONE("Erledigt"),
}

object TaskLogic {

    fun dueDate(task: TaskEntity): LocalDate? = task.dueEpochDay?.let(LocalDate::ofEpochDay)

    /** Zeitpunkt der Erinnerung oder null, wenn keine nötig ist. */
    fun reminderAt(task: TaskEntity, defaultMinutes: Int, zone: ZoneId): Long? {
        if (!task.remind || task.done) return null
        val date = dueDate(task) ?: return null
        val minutes = task.dueMinutes ?: defaultMinutes
        return date.atTime(LocalTime.of((minutes / 60).coerceIn(0, 23), minutes % 60))
            .atZone(zone).toInstant().toEpochMilli()
    }

    fun advance(date: LocalDate, repeat: Repeat): LocalDate = when (repeat) {
        Repeat.NONE -> date
        Repeat.DAILY -> date.plusDays(1)
        Repeat.WEEKDAYS -> {
            var d = date.plusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            d
        }
        Repeat.WEEKLY -> date.plusWeeks(1)
        Repeat.MONTHLY -> date.plusMonths(1)
    }

    /** Nächstes Fälligkeitsdatum einer wiederkehrenden Aufgabe nach dem Abhaken (immer nach heute). */
    fun nextDue(date: LocalDate, repeat: Repeat, today: LocalDate): LocalDate {
        if (repeat == Repeat.NONE) return date
        var next = advance(date, repeat)
        while (!next.isAfter(today)) next = advance(next, repeat)
        return next
    }

    fun section(task: TaskEntity, today: LocalDate): TaskSection {
        if (task.done) return TaskSection.DONE
        val due = dueDate(task) ?: return TaskSection.NO_DATE
        return when {
            due.isBefore(today) -> TaskSection.OVERDUE
            due == today -> TaskSection.TODAY
            due == today.plusDays(1) -> TaskSection.TOMORROW
            else -> TaskSection.UPCOMING
        }
    }

    fun grouped(tasks: List<TaskEntity>, today: LocalDate): Map<TaskSection, List<TaskEntity>> =
        tasks.groupBy { section(it, today) }.toSortedMap(compareBy { it.ordinal })
}

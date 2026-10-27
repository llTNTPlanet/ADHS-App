package de.lltntplanet.adhsapp.data.routines

import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.util.Dates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Ein konkreter Zeitpunkt einer Routine / Einnahme an einem Tag. */
data class RoutineOccurrence(
    val routine: RoutineEntity,
    val date: LocalDate,
    val minutes: Int,
    /** Zeitpunkt in Millisekunden (Systemzeitzone bzw. übergebene Zone). */
    val at: Long,
    val done: Boolean = false,
    val skipped: Boolean = false,
) {
    val key: String get() = "rt:${routine.id}:${date.toEpochDay()}:$minutes"
    val epochDay: Long get() = date.toEpochDay()
}

/** Reine Logik rund um Routinen: Uhrzeiten, Wochentage, Vorkommen, Abhaken. */
object RoutineLogic {

    /** Wie lange nach der Uhrzeit eine verpasste Erinnerung noch nachgeholt wird. */
    const val CATCH_UP_MILLIS = 3L * 60 * 60 * 1000

    fun parseTimes(times: String): List<Int> =
        times.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until 24 * 60 }.distinct().sorted()

    fun encodeTimes(times: Collection<Int>): String = times.distinct().sorted().joinToString(",")

    fun times(routine: RoutineEntity): List<Int> = parseTimes(routine.times)

    fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun hasWeekday(mask: Int, day: DayOfWeek): Boolean = mask and weekdayBit(day) != 0

    fun toggleWeekday(mask: Int, day: DayOfWeek): Int = mask xor weekdayBit(day)

    fun weekdays(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { hasWeekday(mask, it) }

    fun occursOn(routine: RoutineEntity, date: LocalDate): Boolean =
        routine.active && hasWeekday(routine.weekdays, date.dayOfWeek)

    /** Kurztext für die Wochentage, z. B. „Täglich“, „Mo–Fr“, „Mo, Mi, Fr“. */
    fun weekdaysText(mask: Int): String {
        val days = weekdays(mask)
        return when {
            days.size == 7 -> "Täglich"
            days.isEmpty() -> "Nie"
            days == listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "Mo–Fr"
            days == listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Sa + So"
            else -> days.joinToString(", ") { shortDay(it) }
        }
    }

    fun shortDay(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Mo"
        DayOfWeek.TUESDAY -> "Di"
        DayOfWeek.WEDNESDAY -> "Mi"
        DayOfWeek.THURSDAY -> "Do"
        DayOfWeek.FRIDAY -> "Fr"
        DayOfWeek.SATURDAY -> "Sa"
        DayOfWeek.SUNDAY -> "So"
    }

    /**
     * Alle Vorkommen aktiver Routinen von [from] bis [toInclusive], zeitlich sortiert,
     * mit Abhak-Status aus [log].
     */
    fun occurrences(
        routines: List<RoutineEntity>,
        log: List<RoutineLogEntity>,
        from: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId,
    ): List<RoutineOccurrence> {
        if (routines.isEmpty() || toInclusive.isBefore(from)) return emptyList()
        val logByKey = log.associateBy { Triple(it.routineId, it.epochDay, it.minutes) }
        val result = mutableListOf<RoutineOccurrence>()
        var date = from
        while (!date.isAfter(toInclusive)) {
            for (r in routines) {
                if (!occursOn(r, date)) continue
                for (m in times(r)) {
                    val entry = logByKey[Triple(r.id, date.toEpochDay(), m)]
                    result += RoutineOccurrence(
                        routine = r,
                        date = date,
                        minutes = m,
                        at = Dates.atMinutes(date, m, zone),
                        done = entry != null && !entry.skipped,
                        skipped = entry?.skipped == true,
                    )
                }
            }
            date = date.plusDays(1)
        }
        return result.sortedWith(compareBy({ it.at }, { it.routine.name }))
    }

    /**
     * Die nächsten offenen Einträge für die Hauptseite: zuerst Überfälliges von heute (noch nicht abgehakt),
     * dann alles Kommende – heute und morgen.
     */
    fun upcoming(
        routines: List<RoutineEntity>,
        log: List<RoutineLogEntity>,
        now: Long,
        zone: ZoneId,
        limit: Int = 2,
    ): List<RoutineOccurrence> {
        val today = Dates.toLocalDate(now, zone)
        return occurrences(routines, log, today, today.plusDays(1), zone)
            .filter { !it.done && !it.skipped }
            .take(limit)
    }

    fun isOverdue(o: RoutineOccurrence, now: Long): Boolean = !o.done && !o.skipped && o.at < now - 60_000L
}

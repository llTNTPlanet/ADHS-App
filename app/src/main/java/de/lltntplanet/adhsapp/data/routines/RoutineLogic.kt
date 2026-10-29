package de.lltntplanet.adhsapp.data.routines

import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
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
    /** Vom Medikamentenspender als genommen gemeldet (automatisch abgehakt). */
    val byDispenser: Boolean = false,
) {
    val key: String get() = RoutineLogic.occurrenceKey(routine.id, date.toEpochDay(), minutes)
    val epochDay: Long get() = date.toEpochDay()
}

/** Reine Logik rund um Routinen: Uhrzeiten, Wochentage, Vorkommen, Abhaken. */
object RoutineLogic {

    /** Wie lange nach der Uhrzeit eine verpasste Erinnerung noch nachgeholt wird. */
    const val CATCH_UP_MILLIS = 3L * 60 * 60 * 1000

    /** Schlüssel eines Vorkommens, zugleich der Schlüssel seiner Erinnerung. */
    fun occurrenceKey(routineId: Long, epochDay: Long, minutes: Int): String = "rt:$routineId:$epochDay:$minutes"

    /** Schlüssel aller Vorkommen, die im Protokoll stehen, also abgehakt oder übersprungen sind. */
    fun doneKeys(log: List<RoutineLogEntity>): Set<String> = log.mapTo(HashSet()) { occurrenceKey(it.routineId, it.epochDay, it.minutes) }

    fun parseTimes(times: String): List<Int> =
        times.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until 24 * 60 }.distinct().sorted()

    fun encodeTimes(times: Collection<Int>): String = times.distinct().sorted().joinToString(",")

    fun times(routine: RoutineEntity): List<Int> = parseTimes(routine.times)

    fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun hasWeekday(mask: Int, day: DayOfWeek): Boolean = mask and weekdayBit(day) != 0

    fun toggleWeekday(mask: Int, day: DayOfWeek): Int = mask xor weekdayBit(day)

    fun weekdays(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { hasWeekday(mask, it) }

    fun occursOn(routine: RoutineEntity, date: LocalDate): Boolean = routine.active && when (val once = routine.onceEpochDay) {
        null -> hasWeekday(routine.weekdays, date.dayOfWeek)
        else -> date.toEpochDay() == once
    }

    fun onceDate(routine: RoutineEntity): LocalDate? = routine.onceEpochDay?.let { LocalDate.ofEpochDay(it) }

    /** Einmaliger Eintrag, dessen Tag schon vorbei ist (am Tag selbst gilt er noch als anstehend). */
    fun isPastOnce(routine: RoutineEntity, today: LocalDate): Boolean =
        routine.onceEpochDay?.let { it < today.toEpochDay() } == true

    /** Wie oft: „Täglich“, „Mo–Fr“ … bzw. „Einmalig am Do. 30. Okt.“ (Heute/Morgen statt Datum, wenn passend). */
    fun scheduleText(routine: RoutineEntity, today: LocalDate = LocalDate.now()): String =
        onceDate(routine)?.let { "Einmalig ${onceDayText(it, today)}" } ?: weekdaysText(routine.weekdays)

    private fun onceDayText(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "heute"
        today.plusDays(1) -> "morgen"
        today.minusDays(1) -> "gestern"
        else -> "am ${TimeFormat.weekdayShort(date.dayOfWeek)} ${TimeFormat.dayMonthShort(date)}" +
            if (date.year != today.year) " ${date.year}" else ""
    }

    /**
     * Vorschlag für das Datum eines neuen einmaligen Eintrags: heute, solange noch eine der Uhrzeiten
     * bevorsteht, sonst morgen.
     */
    fun suggestedOnceDate(times: List<Int>, now: Long, zone: ZoneId): LocalDate {
        val today = Dates.toLocalDate(now, zone)
        return if (times.any { Dates.atMinutes(today, it, zone) > now }) today else today.plusDays(1)
    }

    /** Uhrzeiten eines einmaligen Eintrags am [date], die schon vorbei sind (aufsteigend). */
    fun pastTimes(date: LocalDate, times: List<Int>, now: Long, zone: ZoneId): List<Int> =
        times.filter { Dates.atMinutes(date, it, zone) <= now }.distinct().sorted()

    /** Liegen alle Uhrzeiten eines einmaligen Eintrags am [date] schon in der Vergangenheit? */
    fun allTimesPast(date: LocalDate, times: List<Int>, now: Long, zone: ZoneId): Boolean =
        times.isNotEmpty() && pastTimes(date, times, now, zone).size == times.distinct().size

    /** Kommt für eine schon vergangene Uhrzeit noch die nachgeholte Erinnerung (bis [CATCH_UP_MILLIS] danach)? */
    fun catchesUp(date: LocalDate, minutes: Int, now: Long, zone: ZoneId): Boolean =
        now - Dates.atMinutes(date, minutes, zone) < CATCH_UP_MILLIS

    /**
     * Hinweis, wenn Uhrzeiten eines einmaligen Eintrags schon vorbei sind – und was dann passiert: bis zu drei Stunden
     * danach kommt die Erinnerung sofort nach dem Speichern (wie bei regelmäßigen Routinen), später gar nicht mehr.
     */
    fun pastTimesHint(date: LocalDate, times: List<Int>, now: Long, zone: ZoneId): String? {
        val past = pastTimes(date, times, now, zone)
        if (past.isEmpty()) return null
        fun list(minutes: List<Int>) = minutes.joinToString(", ") { TimeFormat.minutesOfDay(it) } + " Uhr"
        val all = past.size == times.distinct().size
        val subject = when {
            all && past.size == 1 -> "Die Uhrzeit ist schon vorbei"
            all -> "Die Uhrzeiten sind schon vorbei"
            past.size == 1 -> "${list(past)} ist schon vorbei"
            else -> "${list(past)} sind schon vorbei"
        }
        val catchUp = past.filter { catchesUp(date, it, now, zone) }
        val consequence = when {
            catchUp.isEmpty() -> "dazu kommt keine Erinnerung mehr"
            catchUp.size == past.size && past.size == 1 -> "die Erinnerung dazu kommt gleich nach dem Speichern"
            catchUp.size == past.size -> "die Erinnerungen dazu kommen gleich nach dem Speichern"
            else -> "nur für ${list(catchUp)} kommt die Erinnerung noch, gleich nach dem Speichern"
        }
        return "$subject – $consequence."
    }

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
                        byDispenser = entry?.byDispenser == true,
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

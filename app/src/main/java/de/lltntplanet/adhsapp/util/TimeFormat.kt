package de.lltntplanet.adhsapp.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/** Deutsche Formatierung von Datum und Uhrzeit. Alle Angaben in der Zeitzone des Geräts. */
object TimeFormat {
    val locale: Locale = Locale.GERMANY

    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d. MMMM", locale)
    private val dayMonthShort = DateTimeFormatter.ofPattern("d. MMM", locale)
    private val fullDate = DateTimeFormatter.ofPattern("EEEE, d. MMMM", locale)
    private val fullDateYear = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", locale)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    private val numericDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", locale)

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun time(epochMillis: Long, zone: ZoneId = zone()): String =
        time.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun time(t: LocalTime): String = time.format(t)

    fun minutesOfDay(minutes: Int): String = time(LocalTime.of((minutes / 60) % 24, minutes % 60))

    fun dayMonth(date: LocalDate): String = dayMonth.format(date)

    fun dayMonthShort(date: LocalDate): String = dayMonthShort.format(date)

    fun numeric(date: LocalDate): String = numericDate.format(date)

    fun fullDate(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        (if (date.year == today.year) fullDate else fullDateYear).format(date)

    fun monthYear(date: LocalDate): String = monthYear.format(date).replaceFirstChar { it.titlecase(locale) }

    fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)

    fun weekdayNarrow(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, locale)

    /** "Heute", "Morgen", "Gestern" oder z. B. "Mo., 26. Okt.". */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        today.minusDays(1) -> "Gestern"
        else -> "${weekdayShort(date.dayOfWeek)} ${dayMonthShort(date)}"
    }

    /** Menschliche Dauer, z. B. "2 Std. 15 Min." oder "45 Min.". */
    fun duration(minutes: Long): String {
        val m = abs(minutes)
        val days = m / (60 * 24)
        val hours = (m % (60 * 24)) / 60
        val mins = m % 60
        return when {
            days > 0 && hours > 0 -> "$days ${if (days == 1L) "Tag" else "Tage"} $hours Std."
            days > 0 -> "$days ${if (days == 1L) "Tag" else "Tage"}"
            hours > 0 && mins > 0 -> "$hours Std. $mins Min."
            hours > 0 -> "$hours Std."
            else -> "$mins Min."
        }
    }

    /** Countdown-Text relativ zu jetzt: "in 15 Min.", "jetzt", "seit 5 Min.". */
    fun countdown(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = targetMillis - nowMillis
        return when {
            diff >= 60_000L -> "in ${duration((diff + 59_999L) / 60_000L)}"
            diff > -60_000L -> "jetzt"
            else -> "seit ${duration(-diff / 60_000L)}"
        }
    }

    fun formatMinutesBefore(minutes: Int): String = when {
        minutes == 0 -> "Zu Beginn"
        minutes % (60 * 24 * 7) == 0 -> "${minutes / (60 * 24 * 7)} Wo. vorher"
        minutes % (60 * 24) == 0 -> "${minutes / (60 * 24)} ${if (minutes == 60 * 24) "Tag" else "Tage"} vorher"
        minutes % 60 == 0 -> "${minutes / 60} Std. vorher"
        else -> "$minutes Min. vorher"
    }
}

object Dates {
    fun startOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun toZoned(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone)

    fun atMinutes(date: LocalDate, minutesOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(LocalTime.of((minutesOfDay / 60).coerceIn(0, 23), minutesOfDay % 60))
            .atZone(zone).toInstant().toEpochMilli()

    /** Montag der Woche (deutsche Wochen beginnen montags). */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}

package de.lltntplanet.adhsapp.data.birthdays

import java.time.LocalDate
import java.time.Month
import java.time.Year

enum class BirthdaySource { CONTACT, MANUAL }

data class Birthday(
    /** "c:<contactId>" oder "m:<id>" */
    val id: String,
    val name: String,
    val month: Int,
    val day: Int,
    val year: Int?,
    val source: BirthdaySource,
    val manualId: Long? = null,
    val note: String = "",
) {
    fun nextOccurrence(from: LocalDate): LocalDate = BirthdayMath.nextOccurrence(month, day, from)

    fun ageOn(date: LocalDate): Int? = year?.let { date.year - it }?.takeIf { it in 0..150 }
}

data class UpcomingBirthday(val birthday: Birthday, val date: LocalDate, val daysUntil: Long, val age: Int?)

object BirthdayMath {

    private val isoFull = Regex("""^(\d{4})-?(\d{2})-?(\d{2})""")
    private val noYear = Regex("""^--(\d{2})-?(\d{2})""")
    private val dotted = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})?""")
    private val slashed = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})""")

    /**
     * Liest die verschiedenen Datumsformate der Kontakte-App:
     * "1990-05-14", "19900514", "--05-14", "1990-05-14T00:00:00Z", "14.05.1990", "05/14/1990".
     * Ergebnis: (Jahr oder null, Monat, Tag).
     */
    fun parseContactDate(raw: String?): Triple<Int?, Int, Int>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val result: Triple<Int?, Int, Int>? = noYear.find(s)?.let {
            Triple(null, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        } ?: isoFull.find(s)?.let {
            Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        } ?: dotted.find(s)?.let {
            Triple(it.groupValues[3].toIntOrNull(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        } ?: slashed.find(s)?.let {
            Triple(it.groupValues[3].toInt(), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return result?.let { (y, m, d) ->
            if (m !in 1..12 || d !in 1..Month.of(m).maxLength()) null
            // Manche Apps speichern "kein Jahr" als 1604 oder 0000.
            else Triple(y?.takeIf { it in 1800..2200 }, m, d)
        }
    }

    /** Datum des Geburtstags in [year]. Der 29. Februar wird in Nicht-Schaltjahren am 28. gefeiert. */
    fun occurrenceIn(year: Int, month: Int, day: Int): LocalDate =
        if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28)
        else LocalDate.of(year, month, day)

    fun nextOccurrence(month: Int, day: Int, from: LocalDate): LocalDate {
        val thisYear = occurrenceIn(from.year, month, day)
        return if (thisYear.isBefore(from)) occurrenceIn(from.year + 1, month, day) else thisYear
    }

    fun upcoming(birthdays: List<Birthday>, today: LocalDate): List<UpcomingBirthday> =
        birthdays.map {
            val date = it.nextOccurrence(today)
            UpcomingBirthday(it, date, java.time.temporal.ChronoUnit.DAYS.between(today, date), it.ageOn(date))
        }.sortedWith(compareBy<UpcomingBirthday> { it.daysUntil }.thenBy { it.birthday.name })

    /** Entfernt doppelte Einträge (gleicher Name am gleichen Tag), Kontakte haben Vorrang. */
    fun dedupe(birthdays: List<Birthday>): List<Birthday> =
        birthdays.sortedBy { it.source.ordinal }
            .distinctBy { Triple(it.name.trim().lowercase(), it.month, it.day) }
}

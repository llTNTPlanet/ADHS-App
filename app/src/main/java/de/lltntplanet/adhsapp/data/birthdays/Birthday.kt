package de.lltntplanet.adhsapp.data.birthdays

import de.lltntplanet.adhsapp.data.calendar.EventInstance
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.ZoneId

/** Reihenfolge = Vorrang bei Dubletten: Kontakt vor Kalender vor eigenem Eintrag. */
enum class BirthdaySource { CONTACT, CALENDAR, MANUAL }

data class Birthday(
    /** "c:<contactId>", "k:<eventId>" (Kalender) oder "m:<id>" */
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

    private val titlePatterns = listOf(
        // "Geburtstag von Anna", "Geburtstag: Anna", "Geburtstag Anna"
        Regex("""^geburtstag(?:\s+von|\s*[:\-–])?\s+(.+)$""", RegexOption.IGNORE_CASE),
        // "Anna – Geburtstag", "Lukas: Geburtstag" (mit Trennzeichen bleibt der Name unverändert)
        Regex("""^(.+?)\s*[:\-–]\s*(?:geburtstag|birthday)$""", RegexOption.IGNORE_CASE),
        // "Annas Geburtstag", "Anna's Geburtstag", "Lukas' Geburtstag"
        Regex("""^(.+?)(?:['’]s?|s)?\s+geburtstag$""", RegexOption.IGNORE_CASE),
        // "Anna's birthday", "Anna birthday"
        Regex("""^(.+?)(?:['’]s?)?\s+birthday$""", RegexOption.IGNORE_CASE),
        // "Birthday of Anna", "Birthday: Anna"
        Regex("""^birthday(?:\s+of|\s*[:\-–])?\s+(.+)$""", RegexOption.IGNORE_CASE),
    )
    private val yearInTitle = Regex("""[(\[]?\s*(?:\*\s*)?((?:18|19|20)\d{2})\s*[)\]]?\s*$""")
    private val decorations = Regex("""[🎂🎉🎈🎁🥳]""")

    /**
     * Liest Name und (falls im Titel) Geburtsjahr aus einem Kalendertermin wie
     * "Geburtstag von Anna Müller", "Annas Geburtstag (1990)", "🎂 Anna's birthday".
     * Ergebnis: (Name, Jahr oder null) – oder null, wenn der Titel nicht nach Geburtstag aussieht.
     */
    fun parseCalendarTitle(raw: String?): Pair<String, Int?>? {
        var title = raw.orEmpty().replace(decorations, " ").trim().replace(Regex("""\s+"""), " ")
        if (title.isEmpty()) return null
        var year: Int? = null
        yearInTitle.find(title)?.let { m ->
            year = m.groupValues[1].toInt()
            title = title.removeRange(m.range).trim()
        }
        for (pattern in titlePatterns) {
            val name = pattern.find(title)?.groupValues?.get(1)?.trim()?.trim('-', '–', ':', ',', '(', ')')?.trim()
            if (!name.isNullOrBlank()) return name to year
        }
        return null
    }

    /** Entfernt doppelte Einträge (gleicher Name am gleichen Tag), Kontakte haben Vorrang. */
    fun dedupe(birthdays: List<Birthday>): List<Birthday> =
        birthdays.sortedBy { it.source.ordinal }
            .distinctBy { Triple(it.name.trim().lowercase(), it.month, it.day) }

    /**
     * Macht aus Kalenderterminen Geburtstage: Einträge des Geburtstagskalenders (dort reicht auch ein bloßer
     * Name) und ganztägige, wiederkehrende Termine, deren Titel nach Geburtstag aussieht.
     */
    fun fromCalendar(instances: List<EventInstance>, birthdayCalendars: Set<Long>, zone: ZoneId): List<Birthday> =
        instances.asSequence()
            .filter { it.allDay && !it.canceled && (it.calendarId in birthdayCalendars || it.recurring) }
            .mapNotNull { e ->
                val parsed = parseCalendarTitle(e.title)
                    ?: e.title.trim().takeIf { e.calendarId in birthdayCalendars && it.isNotBlank() }?.let { it to null }
                    ?: return@mapNotNull null
                val date = e.startDate(zone)
                Birthday(
                    id = "k:${e.eventId}",
                    name = parsed.first,
                    month = date.monthValue,
                    day = date.dayOfMonth,
                    year = parsed.second,
                    source = BirthdaySource.CALENDAR,
                )
            }
            .distinctBy { Triple(it.name.lowercase(), it.month, it.day) }
            .toList()
}

package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.birthdays.BirthdaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BirthdayMathTest {

    @Test
    fun parsesContactFormats() {
        assertEquals(Triple(1990, 5, 14), BirthdayMath.parseContactDate("1990-05-14"))
        assertEquals(Triple(1990, 5, 14), BirthdayMath.parseContactDate("19900514"))
        assertEquals(Triple(null, 5, 14), BirthdayMath.parseContactDate("--05-14"))
        assertEquals(Triple(null, 5, 14), BirthdayMath.parseContactDate("--0514"))
        assertEquals(Triple(1990, 5, 14), BirthdayMath.parseContactDate("1990-05-14T00:00:00Z"))
        assertEquals(Triple(1990, 5, 14), BirthdayMath.parseContactDate("14.05.1990"))
        assertEquals(Triple(1990, 5, 14), BirthdayMath.parseContactDate("05/14/1990"))
        // "Kein Jahr" als Platzhalterjahr
        assertEquals(Triple(null, 5, 14), BirthdayMath.parseContactDate("1604-05-14"))
    }

    @Test
    fun rejectsInvalidDates() {
        assertNull(BirthdayMath.parseContactDate(""))
        assertNull(BirthdayMath.parseContactDate(null))
        assertNull(BirthdayMath.parseContactDate("1990-13-01"))
        assertNull(BirthdayMath.parseContactDate("1990-02-30"))
        assertNull(BirthdayMath.parseContactDate("irgendwann"))
    }

    @Test
    fun nextOccurrence() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals(LocalDate.of(2026, 10, 24), BirthdayMath.nextOccurrence(10, 24, today))
        assertEquals(LocalDate.of(2026, 12, 1), BirthdayMath.nextOccurrence(12, 1, today))
        assertEquals(LocalDate.of(2027, 1, 5), BirthdayMath.nextOccurrence(1, 5, today))
    }

    @Test
    fun leapDayBirthdayInNonLeapYear() {
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayMath.occurrenceIn(2027, 2, 29))
        assertEquals(LocalDate.of(2028, 2, 29), BirthdayMath.occurrenceIn(2028, 2, 29))
    }

    @Test
    fun upcomingSortedWithAge() {
        val today = LocalDate.of(2026, 10, 24)
        val list = listOf(
            Birthday("m:1", "Ben", 1, 2, 2000, BirthdaySource.MANUAL),
            Birthday("m:2", "Anna", 10, 24, 1992, BirthdaySource.MANUAL),
            Birthday("m:3", "Carla", 10, 25, null, BirthdaySource.MANUAL),
        )
        val upcoming = BirthdayMath.upcoming(list, today)
        assertEquals(listOf("Anna", "Carla", "Ben"), upcoming.map { it.birthday.name })
        assertEquals(34, upcoming[0].age)
        assertEquals(0L, upcoming[0].daysUntil)
        assertNull(upcoming[1].age)
        assertEquals(27, upcoming[2].age)
    }

    @Test
    fun dedupePrefersContacts() {
        val contact = Birthday("c:7", "Anna Müller", 3, 4, 1990, BirthdaySource.CONTACT)
        val manual = Birthday("m:1", "anna müller ", 3, 4, null, BirthdaySource.MANUAL)
        assertEquals(listOf(contact), BirthdayMath.dedupe(listOf(manual, contact)))
    }
}

class CalendarBirthdayTest {
    private val zone = java.time.ZoneId.of("Europe/Berlin")

    @Test
    fun parsesCalendarTitles() {
        assertEquals("Anna Müller" to null, BirthdayMath.parseCalendarTitle("Geburtstag von Anna Müller"))
        assertEquals("Anna" to 1990, BirthdayMath.parseCalendarTitle("Annas Geburtstag (1990)"))
        assertEquals("Ben Meier" to null, BirthdayMath.parseCalendarTitle("Ben Meier's Geburtstag"))
        assertEquals("Ben" to null, BirthdayMath.parseCalendarTitle("🎂 Ben's birthday"))
        assertEquals("Carla" to null, BirthdayMath.parseCalendarTitle("Birthday of Carla"))
        assertEquals("Carla" to 1988, BirthdayMath.parseCalendarTitle("Geburtstag: Carla *1988"))
        assertEquals("Lukas" to null, BirthdayMath.parseCalendarTitle("Lukas – Geburtstag"))
        assertEquals("Lukas" to null, BirthdayMath.parseCalendarTitle("Lukas' Geburtstag"))
        assertNull(BirthdayMath.parseCalendarTitle("Zahnarzt"))
        assertNull(BirthdayMath.parseCalendarTitle(""))
        assertNull(BirthdayMath.parseCalendarTitle("Geburtstag"))
    }

    private fun event(id: Long, title: String, date: LocalDate, calendarId: Long, allDay: Boolean = true, recurring: Boolean = true) =
        de.lltntplanet.adhsapp.data.calendar.EventInstance(
            eventId = id,
            begin = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            end = date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            title = title,
            allDay = allDay,
            color = 0,
            location = null,
            description = null,
            calendarId = calendarId,
            calendarName = "",
            recurring = recurring,
            declined = false,
            canceled = false,
            canEdit = false,
        )

    @Test
    fun buildsBirthdaysFromCalendarEvents() {
        val birthdayCal = 7L
        val events = listOf(
            event(1, "Anna Müller", LocalDate.of(2026, 11, 3), birthdayCal, recurring = false),
            event(2, "Bens Geburtstag (1995)", LocalDate.of(2027, 2, 14), 2L),
            event(3, "Zahnarzt", LocalDate.of(2026, 12, 1), 2L),
            event(4, "Carla's birthday", LocalDate.of(2026, 12, 24), 2L, recurring = false),
            event(5, "Geburtstag von Anna Müller", LocalDate.of(2026, 11, 3), 3L),
        )
        val result = BirthdayMath.fromCalendar(events, setOf(birthdayCal), zone)
        assertEquals(listOf("Anna Müller", "Ben"), result.map { it.name })
        assertEquals(BirthdaySource.CALENDAR, result[0].source)
        assertEquals("k:1", result[0].id)
        assertEquals(11 to 3, result[0].month to result[0].day)
        assertEquals(1995, result[1].year)
        assertEquals(2 to 14, result[1].month to result[1].day)
    }

    @Test
    fun dedupePrefersContactOverCalendarOverManual() {
        val contact = Birthday("c:1", "Anna", 3, 4, 1990, BirthdaySource.CONTACT)
        val calendar = Birthday("k:9", "Anna", 3, 4, null, BirthdaySource.CALENDAR)
        val manual = Birthday("m:1", "Anna", 3, 4, null, BirthdaySource.MANUAL)
        assertEquals(listOf(contact), BirthdayMath.dedupe(listOf(manual, calendar, contact)))
        assertEquals(listOf(calendar), BirthdayMath.dedupe(listOf(manual, calendar)))
    }
}

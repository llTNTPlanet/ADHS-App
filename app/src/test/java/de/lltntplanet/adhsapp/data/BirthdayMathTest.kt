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

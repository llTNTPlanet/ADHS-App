package de.lltntplanet.adhsapp.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimeFormatTest {

    @Test
    fun duration() {
        assertEquals("45 Min.", TimeFormat.duration(45))
        assertEquals("1 Std.", TimeFormat.duration(60))
        assertEquals("2 Std. 15 Min.", TimeFormat.duration(135))
        assertEquals("1 Tag", TimeFormat.duration(24 * 60))
        assertEquals("2 Tage 3 Std.", TimeFormat.duration(51 * 60))
    }

    @Test
    fun countdown() {
        val now = 1_000_000_000L
        assertEquals("in 15 Min.", TimeFormat.countdown(now + 15 * 60_000L, now))
        assertEquals("in 15 Min.", TimeFormat.countdown(now + 14 * 60_000L + 30_000L, now))
        assertEquals("jetzt", TimeFormat.countdown(now + 20_000L, now))
        assertEquals("jetzt", TimeFormat.countdown(now - 30_000L, now))
        assertEquals("seit 5 Min.", TimeFormat.countdown(now - 5 * 60_000L, now))
        assertEquals("in 2 Std.", TimeFormat.countdown(now + 120 * 60_000L, now))
    }

    @Test
    fun minutesBefore() {
        assertEquals("Zu Beginn", TimeFormat.formatMinutesBefore(0))
        assertEquals("10 Min. vorher", TimeFormat.formatMinutesBefore(10))
        assertEquals("2 Std. vorher", TimeFormat.formatMinutesBefore(120))
        assertEquals("1 Tag vorher", TimeFormat.formatMinutesBefore(1440))
        assertEquals("1 Wo. vorher", TimeFormat.formatMinutesBefore(10080))
    }

    @Test
    fun relativeDay() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("Heute", TimeFormat.relativeDay(today, today))
        assertEquals("Morgen", TimeFormat.relativeDay(today.plusDays(1), today))
        assertEquals("Gestern", TimeFormat.relativeDay(today.minusDays(1), today))
        assertEquals("Di. 27. Okt.", TimeFormat.relativeDay(today.plusDays(3), today))
    }

    @Test
    fun monthYearIsCapitalized() {
        assertEquals("Oktober 2026", TimeFormat.monthYear(LocalDate.of(2026, 10, 1)))
    }
}

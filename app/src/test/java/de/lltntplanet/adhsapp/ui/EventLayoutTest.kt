package de.lltntplanet.adhsapp.ui

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.allDayEvent
import de.lltntplanet.adhsapp.event
import de.lltntplanet.adhsapp.ui.calendar.EventLayout
import de.lltntplanet.adhsapp.ui.calendar.ScheduleBuilder
import de.lltntplanet.adhsapp.ui.calendar.ScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EventLayoutTest {
    private val day = LocalDate.of(2026, 10, 24)

    @Test
    fun overlappingEventsShareWidth() {
        val events = listOf(
            event(1, "2026-10-24T09:00", "2026-10-24T11:00"),
            event(2, "2026-10-24T10:00", "2026-10-24T10:30"),
            event(3, "2026-10-24T10:30", "2026-10-24T12:00"),
            event(4, "2026-10-24T14:00", "2026-10-24T15:00"),
        )
        val laid = EventLayout.layoutDay(events, day, Berlin).associateBy { it.event.eventId }
        assertEquals(0, laid.getValue(1).column)
        assertEquals(1, laid.getValue(2).column)
        // Termin 3 kann die Spalte von Termin 2 wiederverwenden
        assertEquals(1, laid.getValue(3).column)
        assertEquals(2, laid.getValue(1).columns)
        assertEquals(1, laid.getValue(4).columns)
        assertEquals(9 * 60, laid.getValue(1).startMinute)
        assertEquals(11 * 60, laid.getValue(1).endMinute)
    }

    @Test
    fun eventOverMidnightIsClipped() {
        val e = event(1, "2026-10-24T22:00", "2026-10-25T02:00")
        val first = EventLayout.layoutDay(listOf(e), day, Berlin).single()
        assertEquals(22 * 60, first.startMinute)
        assertEquals(24 * 60, first.endMinute)
        val second = EventLayout.layoutDay(listOf(e), day.plusDays(1), Berlin).single()
        assertEquals(0, second.startMinute)
        assertEquals(2 * 60, second.endMinute)
    }

    @Test
    fun shortEventsGetMinimumHeightAndAllDayIsIgnored() {
        val events = listOf(event(1, "2026-10-24T09:00", "2026-10-24T09:05"), allDayEvent(2, day))
        val laid = EventLayout.layoutDay(events, day, Berlin)
        assertEquals(1, laid.size)
        assertEquals(9 * 60 + EventLayout.MIN_DURATION_MINUTES, laid.single().endMinute)
    }

    @Test
    fun allDayEventsMapToTheirUtcDate() {
        val e = allDayEvent(1, day)
        assertTrue(e.occursOn(day, Berlin))
        assertTrue(!e.occursOn(day.plusDays(1), Berlin))
        assertTrue(!e.occursOn(day.minusDays(1), Berlin))
    }

    @Test
    fun scheduleHasMonthHeadersTodayAndEmptyWeeks() {
        val today = LocalDate.of(2026, 10, 28)
        val events = listOf(
            event(1, "2026-10-27T10:00", "2026-10-27T11:00"),
            allDayEvent(2, LocalDate.of(2026, 11, 2), days = 2),
        )
        val items = ScheduleBuilder.build(events, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 16), today, Berlin)
        val keys = items.map { it.key }
        assertEquals(
            listOf("m-2026-10", "d-2026-10-27", "d-2026-10-28", "m-2026-11", "d-2026-11-02", "d-2026-11-03", "w-2026-11-09"),
            keys,
        )
        assertTrue((items[2] as ScheduleItem.Day).events.isEmpty())
        assertEquals(2, ScheduleBuilder.indexFor(items, today))
    }
}

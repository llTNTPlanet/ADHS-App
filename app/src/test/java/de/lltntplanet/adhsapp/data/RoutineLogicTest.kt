package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.reminders.ReminderKind
import de.lltntplanet.adhsapp.reminders.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RoutineLogicTest {

    // 2026-10-26 ist ein Montag.
    private val monday: LocalDate = LocalDate.of(2026, 10, 26)
    private val med = RoutineEntity(id = 1, name = "Methylphenidat", dose = "10 mg", times = "480,840")
    private val weekdayRoutine = RoutineEntity(id = 2, name = "Sport", kind = RoutineKind.ROUTINE, times = "1080", weekdays = 0b0011111)

    @Test
    fun timesAreParsedSortedAndDeduplicated() {
        assertEquals(listOf(480, 840), RoutineLogic.parseTimes("840, 480,840,abc,2000"))
        assertEquals("480,840", RoutineLogic.encodeTimes(listOf(840, 480, 840)))
    }

    @Test
    fun weekdayMaskToggles() {
        var mask = RoutineEntity.ALL_DAYS
        assertTrue(RoutineLogic.hasWeekday(mask, DayOfWeek.SUNDAY))
        mask = RoutineLogic.toggleWeekday(mask, DayOfWeek.SUNDAY)
        assertFalse(RoutineLogic.hasWeekday(mask, DayOfWeek.SUNDAY))
        assertEquals("Mo–Fr", RoutineLogic.weekdaysText(0b0011111))
        assertEquals("Täglich", RoutineLogic.weekdaysText(RoutineEntity.ALL_DAYS))
        assertEquals("Mo, Mi", RoutineLogic.weekdaysText(0b0000101))
    }

    @Test
    fun occurrencesRespectWeekdaysAndLog() {
        val saturday = monday.plusDays(5)
        val log = listOf(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480))
        val result = RoutineLogic.occurrences(listOf(med, weekdayRoutine), log, monday, saturday, Berlin)
        // Montag: 3, Di–Fr: je 3, Samstag: nur 2 (Sport fällt weg)
        assertEquals(5 * 3 + 2, result.size)
        val first = result.first()
        assertEquals(millis("2026-10-26T08:00"), first.at)
        assertTrue(first.done)
        assertEquals("rt:1:${monday.toEpochDay()}:480", first.key)
        assertFalse(result.any { it.routine.id == 2L && it.date == saturday })
    }

    @Test
    fun pausedRoutinesHaveNoOccurrences() {
        val result = RoutineLogic.occurrences(listOf(med.copy(active = false)), emptyList(), monday, monday, Berlin)
        assertTrue(result.isEmpty())
    }

    @Test
    fun upcomingShowsOverdueFirstThenNextTwo() {
        val now = millis("2026-10-26T10:00")
        val next = RoutineLogic.upcoming(listOf(med, weekdayRoutine), emptyList(), now, Berlin, limit = 2)
        assertEquals(listOf(480, 840), next.map { it.minutes })
        assertTrue(RoutineLogic.isOverdue(next[0], now))
        assertFalse(RoutineLogic.isOverdue(next[1], now))

        val done = listOf(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480))
        val afterCheck = RoutineLogic.upcoming(listOf(med, weekdayRoutine), done, now, Berlin, limit = 2)
        assertEquals(listOf(840, 1080), afterCheck.map { it.minutes })
    }

    @Test
    fun plannerSkipsCheckedDosesAndCatchesUpForThreeHours() {
        val log = listOf(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480))
        val reminders = ReminderPlanner.planRoutines(listOf(med), log, monday, monday, Berlin)
        assertEquals(1, reminders.size)
        val r = reminders.single()
        assertEquals(ReminderKind.MEDICATION, r.kind)
        assertEquals(millis("2026-10-26T14:00"), r.triggerAt)
        assertEquals("10 mg", r.detail)
        assertEquals(1L, r.refId)
        assertTrue(ReminderPlanner.isDue(r, millis("2026-10-26T16:00")))
        assertFalse(ReminderPlanner.isDue(r, millis("2026-10-26T17:30")))
    }
}

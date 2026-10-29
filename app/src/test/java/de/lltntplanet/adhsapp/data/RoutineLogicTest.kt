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

    @Test
    fun onceEntryOccursOnlyOnItsDayIgnoringWeekdays() {
        val wednesday = monday.plusDays(2)
        // Wochentage absichtlich leer: bei einmaligen Einträgen zählt nur das Datum.
        val once = RoutineEntity(id = 5, name = "Ibuprofen 400 mg", times = "600,900", weekdays = 0, onceEpochDay = wednesday.toEpochDay())
        val result = RoutineLogic.occurrences(listOf(once), emptyList(), monday, monday.plusDays(6), Berlin)
        assertEquals(listOf(millis("2026-10-28T10:00"), millis("2026-10-28T15:00")), result.map { it.at })
        assertTrue(result.all { it.date == wednesday })
        assertTrue(RoutineLogic.occurrences(listOf(once.copy(active = false)), emptyList(), wednesday, wednesday, Berlin).isEmpty())

        val reminders = ReminderPlanner.planRoutines(listOf(once), emptyList(), monday, monday.plusDays(6), Berlin)
        assertEquals(listOf(millis("2026-10-28T10:00"), millis("2026-10-28T15:00")), reminders.map { it.triggerAt })
        assertTrue(reminders.all { it.kind == ReminderKind.MEDICATION && it.refId == 5L })
    }

    @Test
    fun onceCheckupBecomesCheckupReminderOnThatDay() {
        val checkup = RoutineEntity(id = 6, name = "Gefühls-Checkup", kind = RoutineKind.CHECKUP, times = "1140", onceEpochDay = monday.plusDays(1).toEpochDay())
        val reminders = ReminderPlanner.planRoutines(listOf(checkup), emptyList(), monday, monday.plusDays(3), Berlin)
        assertEquals(ReminderKind.CHECKUP, reminders.single().kind)
        assertEquals(millis("2026-10-27T19:00"), reminders.single().triggerAt)
    }

    @Test
    fun onceTextsPastStateAndSuggestedDate() {
        val tuesday = monday.plusDays(1)
        val once = RoutineEntity(id = 7, name = "Blutabnahme", kind = RoutineKind.ROUTINE, times = "450", onceEpochDay = tuesday.toEpochDay())
        assertTrue(once.isOnce)
        assertFalse(med.isOnce)
        assertEquals("Einmalig morgen", RoutineLogic.scheduleText(once, today = monday))
        assertEquals("Einmalig heute", RoutineLogic.scheduleText(once, today = tuesday))
        assertEquals("Einmalig am Fr. 30. Okt.", RoutineLogic.scheduleText(once.copy(onceEpochDay = monday.plusDays(4).toEpochDay()), today = monday))
        assertEquals("Täglich", RoutineLogic.scheduleText(med, today = monday))

        assertFalse(RoutineLogic.isPastOnce(once, tuesday))
        assertTrue(RoutineLogic.isPastOnce(once, tuesday.plusDays(1)))
        assertFalse(RoutineLogic.isPastOnce(med, tuesday.plusDays(1)))

        assertEquals(monday, RoutineLogic.suggestedOnceDate(listOf(480), millis("2026-10-26T07:00"), Berlin))
        assertEquals(tuesday, RoutineLogic.suggestedOnceDate(listOf(480), millis("2026-10-26T09:00"), Berlin))
        assertTrue(RoutineLogic.allTimesPast(monday, listOf(480, 600), millis("2026-10-26T10:00"), Berlin))
        assertFalse(RoutineLogic.allTimesPast(monday, listOf(480, 840), millis("2026-10-26T10:00"), Berlin))
        assertEquals(listOf(480), RoutineLogic.pastTimes(monday, listOf(840, 480), millis("2026-10-26T10:00"), Berlin))
        // Bis zu drei Stunden danach wird noch nachgeholt (wie bei regelmäßigen Routinen).
        assertTrue(RoutineLogic.catchesUp(monday, 480, millis("2026-10-26T10:59"), Berlin))
        assertFalse(RoutineLogic.catchesUp(monday, 480, millis("2026-10-26T11:00"), Berlin))
    }

    @Test
    fun pastTimesHintExplainsWhatHappensAfterSaving() {
        val at10 = millis("2026-10-26T10:00")
        assertEquals(null, RoutineLogic.pastTimesHint(monday, listOf(840), at10, Berlin))
        assertEquals(
            "08:00 Uhr ist schon vorbei – die Erinnerung dazu kommt gleich nach dem Speichern.",
            RoutineLogic.pastTimesHint(monday, listOf(480, 1200), at10, Berlin),
        )
        val at12 = millis("2026-10-26T12:00")
        assertEquals("Die Uhrzeit ist schon vorbei – dazu kommt keine Erinnerung mehr.", RoutineLogic.pastTimesHint(monday, listOf(480), at12, Berlin))
        assertEquals(
            "Die Uhrzeiten sind schon vorbei – nur für 10:00 Uhr kommt die Erinnerung noch, gleich nach dem Speichern.",
            RoutineLogic.pastTimesHint(monday, listOf(480, 600), at12, Berlin),
        )
        assertEquals(
            "Die Uhrzeiten sind schon vorbei – die Erinnerungen dazu kommen gleich nach dem Speichern.",
            RoutineLogic.pastTimesHint(monday, listOf(600, 660), at12, Berlin),
        )
    }

    @Test
    fun checkupBecomesCheckupReminderWithoutDose() {
        val checkup = RoutineEntity(id = 3, name = "Gefühls-Checkup", kind = RoutineKind.CHECKUP, times = "1200")
        val reminders = ReminderPlanner.planRoutines(listOf(checkup), emptyList(), monday, monday, Berlin)
        val r = reminders.single()
        assertEquals(ReminderKind.CHECKUP, r.kind)
        assertTrue(r.kind.isCheckup)
        assertFalse(r.kind.isRoutine)
        assertEquals(millis("2026-10-26T20:00"), r.triggerAt)
        assertEquals(3L, r.refId)
        assertEquals(null, r.detail)
    }
}

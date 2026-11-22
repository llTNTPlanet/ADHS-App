package de.lltntplanet.adhsapp.shared

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun ringRequestSurvivesJsonRoundTrip() {
        val request = RingRequest(
            requestId = "abc",
            sentAt = 1_000,
            onlyIfWorn = true,
            reminders = listOf(
                WatchReminder(
                    key = "rt:1:20000:480",
                    originKey = "rt:1:20000:480",
                    kind = "MEDICATION",
                    title = "💊 Medikament",
                    subtitle = "Jetzt · 08:00",
                    canDone = true,
                    doneLabel = "Genommen",
                    payload = """{"key":"rt:1:20000:480","kind":"MEDICATION"}""",
                ),
            ),
            sound = true,
            ringSeconds = 30,
        )
        val decoded = WatchJson.decodeFromString<RingRequest>(WatchJson.encodeToString(request))
        assertEquals(request, decoded)
    }

    @Test
    fun unknownFieldsAndMissingDefaultsAreTolerated() {
        // Neuere Gegenseite schickt ein Feld, das diese Version nicht kennt; ältere lässt neue Felder weg.
        val ack = WatchJson.decodeFromString<RingAck>("""{"requestId":"x","shown":true,"future":42}""")
        assertTrue(ack.shown)
        assertTrue(ack.displayed)
        assertNull(ack.worn)

        val snapshot = WatchJson.decodeFromString<WatchSnapshot>("""{"generatedAt":5,"somethingNew":[1,2]}""")
        assertEquals(5L, snapshot.generatedAt)
        assertEquals(WatchPage.entries.map { it.id }, snapshot.pages)
        assertNull(snapshot.health)
    }

    @Test
    fun visiblePagesKeepOrderAndIgnoreUnknownIds() {
        val snapshot = WatchSnapshot(pages = listOf("nutrition", "calendar", "brand-new-page"))
        assertEquals(listOf(WatchPage.CALENDAR, WatchPage.NUTRITION), WatchSnapshotLogic.visiblePages(snapshot))
        assertEquals(
            listOf(WatchPage.CALENDAR, WatchPage.ROUTINES, WatchPage.BIRTHDAYS, WatchPage.NUTRITION),
            WatchPage.visible(setOf("tasks", "health")),
        )
        assertEquals(WatchPage.TASKS, WatchPage.fromId("tasks"))
        assertNull(WatchPage.fromId("today"))
    }

    @Test
    fun nowNextPicksRunningAndUpcomingTimedEvents() {
        val events = listOf(
            WatchEvent(1, "Ganztägig", begin = 0, end = 24 * hour, allDay = true),
            WatchEvent(2, "Läuft", begin = 9 * hour, end = 11 * hour),
            WatchEvent(3, "Danach", begin = 12 * hour, end = 13 * hour),
            WatchEvent(4, "Später", begin = 15 * hour, end = 16 * hour),
        )
        val nn = WatchSnapshotLogic.nowNext(events, now = 10 * hour)
        assertEquals(2L, nn.current?.id)
        assertEquals(3L, nn.next?.id)

        val evening = WatchSnapshotLogic.nowNext(events, now = 20 * hour)
        assertNull(evening.current)
        assertNull(evening.next)
    }

    @Test
    fun eventsOnDayIncludeOverlapsAndPutAllDayFirst() {
        val day = 24 * hour
        val events = listOf(
            WatchEvent(1, "Spät gestern bis heute", begin = -hour, end = hour),
            WatchEvent(2, "Morgen", begin = day + hour, end = day + 2 * hour),
            WatchEvent(3, "Heute", begin = 9 * hour, end = 10 * hour),
            WatchEvent(4, "Ganztägig", begin = 0, end = day, allDay = true),
            WatchEvent(5, "Ohne Dauer", begin = 8 * hour, end = 8 * hour),
        )
        assertEquals(listOf(4L, 1L, 5L, 3L), WatchSnapshotLogic.eventsOn(events, 0, day).map { it.id })
        assertEquals(listOf(2L), WatchSnapshotLogic.eventsOn(events, day, 2 * day).map { it.id })
    }

    @Test
    fun routinesAndTasksKnowWhenTheyAreOverdue() {
        val now = 10 * hour
        fun routine(id: Long, at: Long, done: Boolean = false, skipped: Boolean = false, kind: String = WatchRoutine.KIND_MEDICATION) =
            WatchRoutine("rt:$id:0:${at / 60_000}", id, "R$id", kind = kind, epochDay = 0, minutes = (at / 60_000).toInt(), at = at, done = done, skipped = skipped)

        val routines = listOf(
            routine(1, 8 * hour, done = true),
            routine(2, 9 * hour),
            routine(3, 14 * hour),
            routine(4, 7 * hour, skipped = true),
            routine(5, 12 * hour, kind = WatchRoutine.KIND_CHECKUP),
        )
        assertEquals(listOf(2L, 5L), WatchSnapshotLogic.openRoutines(routines, limit = 2).map { it.routineId })
        assertTrue(WatchSnapshotLogic.isOverdue(routines[1], now))
        assertFalse(WatchSnapshotLogic.isOverdue(routines[0], now))
        assertFalse(WatchSnapshotLogic.isOverdue(routines[2], now))
        assertFalse(routines[4].canToggle)
        assertTrue(routines[1].canToggle)

        assertTrue(WatchSnapshotLogic.isOverdue(WatchTask(1, "Alt", dueEpochDay = 9), todayEpochDay = 10))
        assertFalse(WatchSnapshotLogic.isOverdue(WatchTask(2, "Heute", dueEpochDay = 10), todayEpochDay = 10))
        assertFalse(WatchSnapshotLogic.isOverdue(WatchTask(3, "Ohne Datum"), todayEpochDay = 10))
    }

    @Test
    fun progressValuesStayInRange() {
        assertEquals(0.5f, WatchSnapshotLogic.stepProgress(WatchHealth(epochDay = 1, steps = 4000, stepGoal = 8000)), 0.0001f)
        assertEquals(1f, WatchSnapshotLogic.stepProgress(WatchHealth(epochDay = 1, steps = 20000, stepGoal = 8000)), 0.0001f)
        assertEquals(0f, WatchSnapshotLogic.stepProgress(WatchHealth(epochDay = 1, steps = null)), 0.0001f)
        val n = WatchNutrition(epochDay = 1, eatenKcal = 2500, budgetKcal = 2000)
        assertEquals(1f, WatchSnapshotLogic.eatenFraction(n), 0.0001f)
        assertEquals(-500, n.remainingKcal)
        assertTrue(WatchSnapshotLogic.isStale(WatchSnapshot(generatedAt = 0), now = WatchSnapshotLogic.STALE_MILLIS + 1))
        assertFalse(WatchSnapshotLogic.isStale(WatchSnapshot(generatedAt = 0), now = WatchSnapshotLogic.STALE_MILLIS))
    }
}

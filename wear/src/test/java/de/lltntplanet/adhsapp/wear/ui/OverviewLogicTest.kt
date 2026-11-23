package de.lltntplanet.adhsapp.wear.ui

import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewLogicTest {

    private val utc = ZoneId.of("UTC")

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** 22.11.2026 (LocalDate.toEpochDay) und sein Beginn in UTC. */
    private val today = 20_779L
    private val dayStart = today * day

    /** 22.11.2026, 14:05 Uhr UTC. */
    private val now = dayStart + 14 * hour + 5 * minute

    private fun snapshotAt(generatedAt: Long, zoneId: String = "UTC") =
        WatchSnapshot(generatedAt = generatedAt, zoneId = zoneId)

    private fun routine(
        id: Long,
        kind: String = WatchRoutine.KIND_MEDICATION,
        done: Boolean = false,
        skipped: Boolean = false,
        byDispenser: Boolean = false,
    ) = WatchRoutine(
        key = "rt:$id:$today:485",
        routineId = id,
        name = "Routine $id",
        kind = kind,
        epochDay = today,
        minutes = 485,
        at = dayStart + 485 * minute,
        done = done,
        skipped = skipped,
        byDispenser = byDispenser,
    )

    private fun task(id: Long, repeating: Boolean = false) =
        WatchTask(id = id, title = "Aufgabe $id", dueEpochDay = today, repeating = repeating)

    private fun event(id: Long, begin: Long, end: Long, allDay: Boolean = false) =
        WatchEvent(id = id, title = "Termin $id", begin = begin, end = end, allDay = allDay)

    // ---- needsSync ----

    @Test
    fun needsSyncWithoutSnapshot() {
        assertTrue(OverviewLogic.needsSync(null, now))
    }

    @Test
    fun needsSyncOnlyWhenOlderThanTenMinutes() {
        assertFalse(OverviewLogic.needsSync(snapshotAt(now), now))
        assertFalse(OverviewLogic.needsSync(snapshotAt(now - 5 * minute), now))
        assertFalse(OverviewLogic.needsSync(snapshotAt(now - 10 * minute), now))
        assertTrue(OverviewLogic.needsSync(snapshotAt(now - 10 * minute - 1), now))
        assertTrue(OverviewLogic.needsSync(snapshotAt(now - 3 * hour), now))
    }

    @Test
    fun needsSyncWhenTimestampIsFarInTheFuture() {
        assertFalse(OverviewLogic.needsSync(snapshotAt(now + 5 * minute), now))
        assertTrue(OverviewLogic.needsSync(snapshotAt(now + 11 * minute), now))
    }

    @Test
    fun needsSyncAfterMidnightEvenWhenFresh() {
        // 23:57 Uhr erzeugt, jetzt 00:02 Uhr am nächsten Tag: „heute“ stimmt nicht mehr.
        val afterMidnight = dayStart + day + 2 * minute
        assertTrue(OverviewLogic.needsSync(snapshotAt(afterMidnight - 5 * minute), afterMidnight))
        assertFalse(OverviewLogic.needsSync(snapshotAt(afterMidnight - minute), afterMidnight))
    }

    @Test
    fun needsSyncUsesTheZoneOfThePhone() {
        // 22:58 und 23:03 Uhr UTC sind in Berlin 23:58 und 00:03 Uhr – dort liegt Mitternacht dazwischen.
        val generatedAt = dayStart + 22 * hour + 58 * minute
        val later = generatedAt + 5 * minute
        assertFalse(OverviewLogic.needsSync(snapshotAt(generatedAt, zoneId = "UTC"), later))
        assertTrue(OverviewLogic.needsSync(snapshotAt(generatedAt, zoneId = "Europe/Berlin"), later))
    }

    // ---- untilNextMinute ----

    @Test
    fun untilNextMinuteEndsJustAfterTheFullMinute() {
        assertEquals(59_050L, OverviewLogic.untilNextMinute(5 * minute + 1_000))
        assertEquals(60_050L, OverviewLogic.untilNextMinute(5 * minute))
        assertEquals(51L, OverviewLogic.untilNextMinute(5 * minute + 59_999))
    }

    // ---- Routinen abhaken ----

    @Test
    fun withRoutineChecksAndUnchecksOneOccurrence() {
        val open = routine(1)
        val other = routine(2)
        val snapshot = WatchSnapshot(routines = listOf(open, other))

        val checked = OverviewLogic.withRoutine(snapshot, open.key, done = true)
        assertEquals(listOf(open.copy(done = true), other), checked.routines)

        val unchecked = OverviewLogic.withRoutine(checked, open.key, done = false)
        assertEquals(snapshot, unchecked)
    }

    @Test
    fun withRoutineMakesSkippedDoneOrOpen() {
        val skipped = routine(1, skipped = true)
        val snapshot = WatchSnapshot(routines = listOf(skipped))

        val done = OverviewLogic.withRoutine(snapshot, skipped.key, done = true).routines.single()
        assertTrue(done.done)
        assertFalse(done.skipped)

        val open = OverviewLogic.withRoutine(snapshot, skipped.key, done = false).routines.single()
        assertTrue(open.open)
    }

    @Test
    fun withRoutineForgetsTheDispenserWhenUnchecked() {
        val byDispenser = routine(1, done = true, byDispenser = true)
        val snapshot = WatchSnapshot(routines = listOf(byDispenser))

        assertEquals(snapshot, OverviewLogic.withRoutine(snapshot, byDispenser.key, done = true))

        val unchecked = OverviewLogic.withRoutine(snapshot, byDispenser.key, done = false).routines.single()
        assertFalse(unchecked.done)
        assertFalse(unchecked.byDispenser)
    }

    @Test
    fun withRoutineLeavesCheckupsAndUnknownKeysAlone() {
        val checkup = routine(1, kind = WatchRoutine.KIND_CHECKUP)
        val snapshot = WatchSnapshot(routines = listOf(checkup))

        assertEquals(snapshot, OverviewLogic.withRoutine(snapshot, checkup.key, done = true))
        assertEquals(snapshot, OverviewLogic.withRoutine(snapshot, "rt:99:0:0", done = true))
    }

    @Test
    fun routineActionNamesTheOccurrence() {
        val occurrence = routine(7)
        assertEquals(
            ItemAction(type = ItemAction.TYPE_ROUTINE, id = 7, done = true, epochDay = today, minutes = 485),
            OverviewLogic.routineAction(occurrence, done = true),
        )
        assertEquals(
            ItemAction(type = ItemAction.TYPE_ROUTINE, id = 7, done = false, epochDay = today, minutes = 485),
            OverviewLogic.routineAction(occurrence, done = false),
        )
    }

    // ---- Aufgaben abhaken ----

    @Test
    fun withoutTaskAndWithTask() {
        val first = task(1)
        val second = task(2)
        val snapshot = WatchSnapshot(tasks = listOf(first, second))

        val without = OverviewLogic.withoutTask(snapshot, 1)
        assertEquals(listOf(second), without.tasks)
        assertEquals(snapshot, OverviewLogic.withoutTask(snapshot, 99))

        // Zurückgenommen: Die Aufgabe steht wieder in der Liste (am Ende) – aber nie doppelt.
        assertEquals(listOf(second, first), OverviewLogic.withTask(without, first).tasks)
        assertSame(snapshot, OverviewLogic.withTask(snapshot, first))
    }

    @Test
    fun taskActionCarriesIdStateAndTheDueDayShown() {
        assertEquals(
            ItemAction(type = ItemAction.TYPE_TASK, id = 5, done = true, epochDay = today),
            OverviewLogic.taskAction(task(5), done = true),
        )
        assertEquals(
            ItemAction(type = ItemAction.TYPE_TASK, id = 5, done = false, epochDay = today),
            OverviewLogic.taskAction(task(5), done = false),
        )
        // Wiederkehrende Aufgabe, die noch mit dem Termin von gestern auf der Uhr steht: Genau dieser Tag geht
        // mit – daran erkennt das Handy, ob sie inzwischen schon weitergerückt ist.
        val overdue = WatchTask(id = 6, title = "Täglich", dueEpochDay = today - 1, repeating = true)
        assertEquals(
            ItemAction(type = ItemAction.TYPE_TASK, id = 6, done = true, epochDay = today - 1),
            OverviewLogic.taskAction(overdue, done = true),
        )
    }

    @Test
    fun taskActionWithoutDueDayCarriesNoDay() {
        // Ohne Datum gibt es keinen Tag mitzuschicken; die Uhrzeit gehört nicht dazu.
        val undated = WatchTask(id = 7, title = "Irgendwann", dueMinutes = 600)
        assertEquals(
            ItemAction(type = ItemAction.TYPE_TASK, id = 7, done = true),
            OverviewLogic.taskAction(undated, done = true),
        )
    }

    @Test
    fun checkedListKeepsEachTaskOnce() {
        val first = task(1)
        val second = task(2)

        assertEquals(listOf(first), OverviewLogic.checked(emptyList(), first))
        assertEquals(listOf(second, first), OverviewLogic.checked(listOf(first, second), first))
        assertEquals(listOf(second), OverviewLogic.unchecked(listOf(first, second), 1))
        assertEquals(listOf(first), OverviewLogic.unchecked(listOf(first), 99))
    }

    @Test
    fun doneTasksHidesWhatIsOpenAgain() {
        val first = task(1)
        val second = task(2)
        val third = task(3)

        assertEquals(listOf(first), OverviewLogic.doneTasks(listOf(first, second), open = listOf(second, third)))
        assertEquals(emptyList<WatchTask>(), OverviewLogic.doneTasks(emptyList(), open = listOf(second)))
    }

    @Test
    fun repeatingTasksCannotBeUnchecked() {
        assertTrue(OverviewLogic.canUncheck(task(1)))
        assertFalse(OverviewLogic.canUncheck(task(1, repeating = true)))
    }

    // ---- Geburtstage ----

    @Test
    fun upcomingBirthdaysSortedWithoutPastOnes() {
        val zoe = WatchBirthday("Zoe", today + 5)
        val ben = WatchBirthday("Ben", today)
        val past = WatchBirthday("Alt", today - 1)
        val anna = WatchBirthday("Anna", today + 5)
        val cem = WatchBirthday("Cem", today + 2)
        val all = listOf(zoe, ben, past, anna, cem)

        assertEquals(listOf(ben, cem, anna, zoe), OverviewLogic.upcomingBirthdays(all, today))
        assertEquals(listOf(ben, cem, anna), OverviewLogic.upcomingBirthdays(all, today, limit = 3))
        assertEquals(emptyList<WatchBirthday>(), OverviewLogic.upcomingBirthdays(listOf(past), today))
    }

    // ---- Kalender ----

    @Test
    fun calendarDaysAlwaysStartWithToday() {
        assertEquals(
            listOf(OverviewLogic.Day(today, dayStart, dayStart + day, emptyList())),
            OverviewLogic.calendarDays(emptyList(), today, utc),
        )
    }

    @Test
    fun calendarDaysGroupEventsByDay() {
        val morning = event(1, dayStart + 10 * hour, dayStart + 11 * hour)
        val allDayTomorrow = event(2, dayStart + day, dayStart + 2 * day, allDay = true)
        val later = event(3, dayStart + 3 * day + 9 * hour, dayStart + 3 * day + 10 * hour)
        val overMidnight = event(4, dayStart + 23 * hour, dayStart + day + hour)

        val days = OverviewLogic.calendarDays(listOf(morning, allDayTomorrow, later, overMidnight), today, utc)

        assertEquals(
            listOf(
                OverviewLogic.Day(today, dayStart, dayStart + day, listOf(morning, overMidnight)),
                // Ganztägiges steht zuerst; der Termin über Mitternacht erscheint an beiden Tagen.
                OverviewLogic.Day(today + 1, dayStart + day, dayStart + 2 * day, listOf(allDayTomorrow, overMidnight)),
                // Der Tag dazwischen hat keine Termine und fällt weg.
                OverviewLogic.Day(today + 3, dayStart + 3 * day, dayStart + 4 * day, listOf(later)),
            ),
            days,
        )
    }

    @Test
    fun calendarDaysStopSevenDaysAfterToday() {
        // Das Handy liefert die Termine von heute bis heute + 7 Tage – weiter reicht auch die Seite nicht.
        val lastDay = event(1, dayStart + 7 * day + 9 * hour, dayStart + 7 * day + 10 * hour)
        val tooFar = event(2, dayStart + 8 * day + 9 * hour, dayStart + 8 * day + 10 * hour)
        // Reicht ein mehrtägiger Termin über das Ende hinaus, steht er nur an den Tagen, die vollständig da sind.
        val trip = event(3, dayStart + 6 * day, dayStart + 10 * day, allDay = true)

        val days = OverviewLogic.calendarDays(listOf(lastDay, tooFar, trip), today, utc)

        assertEquals(listOf(today, today + 6, today + 7), days.map { it.epochDay })
        assertEquals(listOf(trip), days[1].events)
        assertEquals(listOf(trip, lastDay), days.last().events)
    }

    // ---- Startseite ----

    @Test
    fun homeEventsLookOnlyAtTodayAndTomorrow() {
        val running = event(1, dayStart - hour, dayStart + hour)
        val afternoon = event(2, dayStart + 15 * hour, dayStart + 16 * hour)
        val tomorrowLate = event(3, dayStart + 2 * day - minute, dayStart + 2 * day + hour)
        val dayAfter = event(4, dayStart + 2 * day, dayStart + 2 * day + hour)
        val nextWeek = event(5, dayStart + 5 * day + 9 * hour, dayStart + 5 * day + 10 * hour)

        // Was vor übermorgen 00:00 Uhr beginnt, zählt – auch wenn es schon gestern begonnen hat.
        assertEquals(
            listOf(running, afternoon, tomorrowLate),
            OverviewLogic.homeEvents(listOf(running, afternoon, tomorrowLate, dayAfter, nextWeek), today, utc),
        )
    }

    @Test
    fun homeShowsNothingNextWhenTheNextEventIsDaysAway() {
        // Wie am Handy: Der Termin in fünf Tagen ist kein „Als Nächstes“ – egal, wie weit die Übersicht reicht.
        val nextWeek = event(5, dayStart + 5 * day + 9 * hour, dayStart + 5 * day + 10 * hour)
        assertEquals(
            WatchSnapshotLogic.NowNext(current = null, next = null),
            WatchSnapshotLogic.nowNext(OverviewLogic.homeEvents(listOf(nextWeek), today, utc), now),
        )

        // Der Termin morgen früh dagegen schon.
        val tomorrow = event(6, dayStart + day + 9 * hour, dayStart + day + 10 * hour)
        assertEquals(
            WatchSnapshotLogic.NowNext(current = null, next = tomorrow),
            WatchSnapshotLogic.nowNext(OverviewLogic.homeEvents(listOf(nextWeek, tomorrow), today, utc), now),
        )
    }

    @Test
    fun homeEventsUseTheZoneOfThePhone() {
        // 23:30 Uhr UTC am morgigen Tag ist in Berlin schon 00:30 Uhr übermorgen.
        val late = event(1, dayStart + 2 * day - 30 * minute, dayStart + 2 * day + hour)
        assertEquals(listOf(late), OverviewLogic.homeEvents(listOf(late), today, utc))
        assertEquals(
            emptyList<WatchEvent>(),
            OverviewLogic.homeEvents(listOf(late), today, ZoneId.of("Europe/Berlin")),
        )
    }

    // ---- Unterseiten ----

    @Test
    fun pageIsHiddenWhenThePhoneLeavesItOut() {
        val snapshot = WatchSnapshot(pages = listOf(WatchPage.CALENDAR.id, WatchPage.TASKS.id))
        assertFalse(OverviewLogic.isHidden(WatchPage.CALENDAR, snapshot))
        assertTrue(OverviewLogic.isHidden(WatchPage.NUTRITION, snapshot))
        // Vorgabe: Alle Seiten sind sichtbar.
        assertFalse(OverviewLogic.isHidden(WatchPage.NUTRITION, WatchSnapshot()))
        // Ohne Übersicht ist nichts bekannt: Die Seite bleibt offen.
        assertFalse(OverviewLogic.isHidden(WatchPage.NUTRITION, null))
    }

    // ---- Hinweise ----

    @Test
    fun noticesFitTheCurvedLineOfTheWatch() {
        // Der Hinweis steht als gebogene Zeile am Rand: Mehr als etwa 22 Zeichen passen dort nicht.
        val notices = listOf(OverviewLogic.PHONE_UNREACHABLE, OverviewLogic.NO_ANSWER, OverviewLogic.ACTION_FAILED)
        assertTrue(notices.all { it.isNotBlank() && it.length <= 22 })
    }
}

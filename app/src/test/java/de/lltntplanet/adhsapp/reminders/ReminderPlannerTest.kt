package de.lltntplanet.adhsapp.reminders

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.allDayEvent
import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdaySource
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.event
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.ui.settings.mealOffsetsSummary
import de.lltntplanet.adhsapp.ui.settings.offsetChipLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReminderPlannerTest {

    private val settings = AppSettings(eventOffsets = setOf(15, 0))

    @Test
    fun eventGetsAppOffsetsAndCalendarRemindersWithoutDuplicates() {
        val e = event(1, "2026-10-24T14:00", "2026-10-24T15:00", title = "Zahnarzt")
        val result = ReminderPlanner.planEvents(listOf(e), mapOf(1L to listOf(15, 60)), settings, emptySet(), emptyList(), Berlin)
        assertEquals(
            listOf(millis("2026-10-24T13:00"), millis("2026-10-24T13:45"), millis("2026-10-24T14:00")),
            result.map { it.triggerAt }.sorted(),
        )
        assertTrue(result.all { it.kind == ReminderKind.EVENT && it.title == "Zahnarzt" && it.targetTime == e.begin })
        assertEquals(3, result.map { it.key }.toSet().size)
    }

    @Test
    fun defaultOffsetsAreThirtyAndFifteenMinutesBefore() {
        val e = event(1, "2026-10-24T14:00", "2026-10-24T15:00")
        val result = ReminderPlanner.planEvents(listOf(e), emptyMap(), AppSettings(), emptySet(), emptyList(), Berlin)
        assertEquals(listOf(millis("2026-10-24T13:30"), millis("2026-10-24T13:45")), result.map { it.triggerAt }.sorted())
    }

    @Test
    fun mealEventsAreNotReminded() {
        val events = listOf(
            event(1, "2026-10-24T08:00", "2026-10-24T08:30", title = "Frühstück"),
            event(2, "2026-10-24T12:00", "2026-10-24T13:00", title = " mittagessen "),
            event(3, "2026-10-24T14:00", "2026-10-24T15:00", title = "Mittagessen mit Anna"),
            allDayEvent(4, LocalDate.of(2026, 10, 24), title = "Frühstück"),
        )
        val result = ReminderPlanner.planEvents(events, emptyMap(), AppSettings(), emptySet(), emptyList(), Berlin)
        assertEquals(setOf(3L), result.map { it.refId }.toSet())
        assertTrue(ReminderPlanner.isIgnoredTitle("FRÜHSTÜCK", setOf("Frühstück")))
        assertFalse(ReminderPlanner.isIgnoredTitle("Frühstück", emptySet()))
    }

    @Test
    fun mealEventsGetQuietReminderAfterTheirEnd() {
        val events = listOf(
            event(1, "2026-10-24T08:00", "2026-10-24T08:30", title = "Frühstück"),
            event(2, "2026-10-24T12:00", "2026-10-24T13:00", title = "Essen mit Anna"),
            event(3, "2026-10-24T14:00", "2026-10-24T15:00", title = "Zahnarzt"),
            event(4, "2026-10-24T18:00", "2026-10-24T18:30", title = "Abendessen", calendarId = 9),
            event(5, "2026-10-24T19:00", "2026-10-24T19:30", title = "Frühstück", declined = true),
            allDayEvent(6, LocalDate.of(2026, 10, 24), title = "Frühstück"),
        )
        val result = ReminderPlanner.planMeals(events, AppSettings(), excludedCalendars = setOf(9L))
        assertEquals(listOf(1L, 2L), result.map { it.refId })
        assertEquals(listOf(millis("2026-10-24T08:30"), millis("2026-10-24T13:00")), result.map { it.triggerAt })
        assertTrue(result.all { it.kind == ReminderKind.MEAL && it.kind.isQuiet && !it.canMute })
        assertEquals(millis("2026-10-24T08:00"), result[0].targetTime)
        assertEquals(millis("2026-10-24T08:30") + ReminderPlanner.MEAL_CATCH_UP_MILLIS, result[0].targetEnd)

        val later = ReminderPlanner.planMeals(events.take(1), AppSettings(mealReminderMinutesAfter = 10), emptySet())
        assertEquals(millis("2026-10-24T08:40"), later[0].triggerAt)
        assertFalse(later[0].key == result[0].key)
        assertTrue(ReminderPlanner.planMeals(events, AppSettings(mealReminders = false), emptySet()).isEmpty())
    }

    @Test
    fun mealEventsUseOnlyTheirOwnOffsetsWithoutCalendarReminders() {
        val dinner = event(1, "2026-10-24T18:00", "2026-10-24T18:45", title = "Abendessen")
        val dentist = event(2, "2026-10-24T14:00", "2026-10-24T15:00", title = "Zahnarzt")
        val calendar = mapOf(1L to listOf(15, 60), 2L to listOf(60))
        val result = ReminderPlanner.planEvents(listOf(dinner, dentist), calendar, AppSettings(), emptySet(), emptyList(), Berlin)

        // Vorgabe: genau 30 und 10 Minuten vor dem Essen – keine 15 Min. aus der allgemeinen Vorgabe, keine Google-Extras.
        assertEquals(
            listOf(millis("2026-10-24T17:30"), millis("2026-10-24T17:50")),
            result.filter { it.refId == 1L }.map { it.triggerAt }.sorted(),
        )
        // Andere Termine bleiben wie gehabt (30 + 15 Min. und die Erinnerung aus Google Kalender).
        assertEquals(
            listOf(millis("2026-10-24T13:00"), millis("2026-10-24T13:30"), millis("2026-10-24T13:45")),
            result.filter { it.refId == 2L }.map { it.triggerAt }.sorted(),
        )
    }

    @Test
    fun mealOffsetsAreFreelyChosenAndIgnoredTitlesStayQuiet() {
        val events = listOf(
            event(1, "2026-10-24T12:30", "2026-10-24T13:00", title = "Essen mit Anna"),
            event(2, "2026-10-24T08:00", "2026-10-24T08:30", title = "Frühstück"),
        )
        val custom = AppSettings(mealOffsets = setOf(45, 25, 5, 0))
        val result = ReminderPlanner.planEvents(events, emptyMap(), custom, emptySet(), emptyList(), Berlin)
        assertEquals(setOf(1L), result.map { it.refId }.toSet())
        assertEquals(
            listOf("11:45", "12:05", "12:25", "12:30").map { millis("2026-10-24T$it") },
            result.map { it.triggerAt }.sorted(),
        )
        assertEquals(4, result.map { it.key }.toSet().size)

        val none = ReminderPlanner.planEvents(events, mapOf(1L to listOf(10)), AppSettings(mealOffsets = emptySet()), emptySet(), emptyList(), Berlin)
        assertTrue(none.isEmpty())
    }

    @Test
    fun mealOffsetsSummaryDescribesWhatHappens() {
        assertEquals(
            "Erinnert 30 Min. vorher und 10 Min. vorher – statt der Vorwarnungen oben und ohne Erinnerungen aus Google Kalender. " +
                "Ohne Vorwarnung, weil oben bei „Keine Erinnerung“: „Frühstück“, „Mittagessen“.",
            mealOffsetsSummary(AppSettings()),
        )
        assertEquals(
            "Erinnert 1 Std. 30 Min. vorher, 45 Min. vorher und zu Beginn – statt der Vorwarnungen oben und ohne Erinnerungen aus Google Kalender.",
            mealOffsetsSummary(AppSettings(mealOffsets = setOf(0, 90, 45), ignoredEventTitles = setOf("Zahnarzt"))),
        )
        assertEquals(
            "Keine Vorwarnung vor Essens-Terminen – auch keine allgemeine und keine aus Google Kalender.",
            mealOffsetsSummary(AppSettings(mealOffsets = emptySet())),
        )
        assertEquals("1 Std. 30 Min.", offsetChipLabel(90))
        assertEquals("Zu Beginn", offsetChipLabel(0))
    }

    @Test
    fun mealTitleMatchesWholeWordsOnly() {
        val words = setOf("Frühstück", "Essen")
        assertTrue(ReminderPlanner.isMealTitle("FRÜHSTÜCK", words))
        assertTrue(ReminderPlanner.isMealTitle("Essen gehen mit Tom", words))
        assertTrue(ReminderPlanner.isMealTitle("Tabletten & Frühstück", words))
        assertFalse(ReminderPlanner.isMealTitle("Mittagessen", words))
        assertFalse(ReminderPlanner.isMealTitle("Essensplan schreiben", words))
        assertFalse(ReminderPlanner.isMealTitle("Zahnarzt", words))
        assertFalse(ReminderPlanner.isMealTitle("Frühstück", emptySet()))
    }

    @Test
    fun muteKeyCoversAllWarningsOfOneOccurrenceEvenWhenSnoozed() {
        val e = event(1, "2026-10-24T14:00", "2026-10-24T15:00")
        val warnings = ReminderPlanner.planEvents(listOf(e), emptyMap(), AppSettings(), emptySet(), emptyList(), Berlin)
        assertEquals(1, warnings.map { it.muteKey }.toSet().size)
        val snoozed = warnings[0].copy(key = "snooze:7", snoozed = true, originKey = warnings[0].key)
        assertEquals(warnings[1].muteKey, snoozed.muteKey)
        assertTrue(snoozed.canMute)
        val other = event(1, "2026-10-25T14:00", "2026-10-25T15:00")
        val next = ReminderPlanner.planEvents(listOf(other), emptyMap(), AppSettings(), emptySet(), emptyList(), Berlin)
        assertFalse(next[0].muteKey == warnings[0].muteKey)
        val test = Reminder(key = "test:1", kind = ReminderKind.EVENT, triggerAt = 0L, title = "Test")
        assertFalse(test.canMute)
    }

    @Test
    fun calendarRemindersCanBeIgnored() {
        val e = event(1, "2026-10-24T14:00", "2026-10-24T15:00")
        val result = ReminderPlanner.planEvents(
            listOf(e), mapOf(1L to listOf(60)), settings.copy(useCalendarReminders = false), emptySet(), emptyList(), Berlin,
        )
        assertEquals(2, result.size)
    }

    @Test
    fun declinedCanceledAndExcludedEventsAreSkipped() {
        val events = listOf(
            event(1, "2026-10-24T10:00", "2026-10-24T11:00", declined = true),
            event(2, "2026-10-24T10:00", "2026-10-24T11:00", canceled = true),
            event(3, "2026-10-24T10:00", "2026-10-24T11:00", calendarId = 9),
        )
        assertTrue(ReminderPlanner.planEvents(events, emptyMap(), settings, setOf(9L), emptyList(), Berlin).isEmpty())
    }

    @Test
    fun allDayEventRemindsAtConfiguredTimeOnLocalDay() {
        val e = allDayEvent(5, LocalDate.of(2026, 10, 24), title = "Urlaub")
        val result = ReminderPlanner.planEvents(listOf(e), emptyMap(), settings.copy(allDayReminderMinutes = 7 * 60 + 30), emptySet(), emptyList(), Berlin)
        assertEquals(listOf(millis("2026-10-24T07:30")), result.map { it.triggerAt })
        assertTrue(result.single().allDay)
        assertEquals(millis("2026-10-25T00:00"), result.single().targetEnd)
    }

    @Test
    fun birthdayEventsInCalendarAreNotDuplicated() {
        val anna = Birthday("c:1", "Anna Müller", 10, 24, 1990, BirthdaySource.CONTACT)
        val birthdayEvent = allDayEvent(5, LocalDate.of(2026, 10, 24), title = "Geburtstag von Anna")
        val otherEvent = allDayEvent(6, LocalDate.of(2026, 10, 24), title = "Anna Urlaub")
        val result = ReminderPlanner.planEvents(listOf(birthdayEvent, otherEvent), emptyMap(), settings, emptySet(), listOf(anna), Berlin)
        assertEquals(listOf(6L), result.map { it.refId })
    }

    @Test
    fun birthdaysOnTheDayAndDayBefore() {
        val anna = Birthday("c:1", "Anna", 10, 25, 1990, BirthdaySource.CONTACT)
        val result = ReminderPlanner.planBirthdays(
            listOf(anna),
            settings.copy(birthdayDaysBefore = setOf(0, 1), birthdayReminderMinutes = 9 * 60),
            LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 10, 27),
            Berlin,
        ).sortedBy { it.triggerAt }
        assertEquals(listOf(millis("2026-10-24T09:00"), millis("2026-10-25T09:00")), result.map { it.triggerAt })
        assertEquals(listOf(1, 0), result.map { it.daysBefore })
        assertTrue(result.all { it.age == 36 })
    }

    @Test
    fun birthdayAcrossNewYear() {
        val ben = Birthday("m:2", "Ben", 1, 1, null, BirthdaySource.MANUAL)
        val result = ReminderPlanner.planBirthdays(
            listOf(ben), settings.copy(birthdayDaysBefore = setOf(1)), LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2), Berlin,
        )
        assertEquals(listOf(millis("2026-12-31T09:00")), result.map { it.triggerAt })
    }

    @Test
    fun birthdaysCanBeDisabled() {
        val anna = Birthday("c:1", "Anna", 10, 25, 1990, BirthdaySource.CONTACT)
        assertTrue(
            ReminderPlanner.planBirthdays(listOf(anna), settings.copy(birthdayReminders = false), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27), Berlin).isEmpty(),
        )
    }

    @Test
    fun tasksWithAndWithoutTime() {
        val day = LocalDate.of(2026, 10, 24).toEpochDay()
        val tasks = listOf(
            TaskEntity(id = 1, title = "Tabletten", dueEpochDay = day, dueMinutes = 8 * 60),
            TaskEntity(id = 2, title = "Steuer", dueEpochDay = day),
            TaskEntity(id = 3, title = "Irgendwann"),
            TaskEntity(id = 4, title = "Erledigt", dueEpochDay = day, done = true),
        )
        val result = ReminderPlanner.planTasks(tasks, settings.copy(taskDefaultMinutes = 10 * 60), Berlin)
        assertEquals(listOf(1L, 2L), result.map { it.refId })
        assertEquals(millis("2026-10-24T08:00"), result[0].triggerAt)
        assertEquals(millis("2026-10-24T10:00"), result[1].triggerAt)
        assertTrue(result[1].allDay)
    }

    @Test
    fun dueRules() {
        val now = millis("2026-10-24T12:00")
        val base = Reminder("k", ReminderKind.TASK, triggerAt = now, title = "x")
        assertTrue(ReminderPlanner.isDue(base, now))
        assertTrue(ReminderPlanner.isDue(base.copy(triggerAt = now + 20_000), now))
        assertFalse(ReminderPlanner.isDue(base.copy(triggerAt = now + 60_000), now))
        // 10 Minuten verpasst: noch innerhalb der Kulanz
        assertTrue(ReminderPlanner.isDue(base.copy(triggerAt = now - 10 * 60_000), now))
        // 2 Stunden verpasst: nur wenn der Termin noch läuft
        val old = base.copy(triggerAt = now - 120 * 60_000)
        assertFalse(ReminderPlanner.isDue(old, now))
        assertTrue(ReminderPlanner.isDue(old.copy(targetEnd = now + 30 * 60_000), now))
        assertTrue(ReminderPlanner.isDue(old.copy(snoozed = true), now))
    }

    @Test
    fun collapseKeepsLatestWarningPerEvent() {
        val r15 = Reminder("ev:1:1000:15", ReminderKind.EVENT, 100, "A")
        val r0 = Reminder("ev:1:1000:0", ReminderKind.EVENT, 200, "A")
        val other = Reminder("ev:2:1000:0", ReminderKind.EVENT, 150, "B")
        assertEquals(listOf(other, r0), ReminderPlanner.collapse(listOf(r15, r0, other)))
    }
}

package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.allDayEvent
import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdaySource
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.health.HealthDay
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.health.HeartDay
import de.lltntplanet.adhsapp.data.health.SleepNight
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.Sex
import de.lltntplanet.adhsapp.event
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WATCH_PROTOCOL_VERSION
import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchFood
import de.lltntplanet.adhsapp.shared.WatchHealth
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchNutrition
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WatchSnapshotBuilderTest {

    /** Dienstag, 10. November 2026, 13:45 Uhr in Berlin. */
    private val today: LocalDate = LocalDate.of(2026, 11, 10)
    private val day: Long = today.toEpochDay()
    private val now: Long = millis("2026-11-10T13:45")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private val med = RoutineEntity(id = 1, name = "Methylphenidat", dose = "10 mg", times = "480,840", createdAt = 0)
    private val checkup =
        RoutineEntity(id = 2, name = "Gefühls-Checkup", kind = RoutineKind.CHECKUP, times = "1200", createdAt = 0)
    private val paused =
        RoutineEntity(id = 3, name = "Sport", kind = RoutineKind.ROUTINE, times = "600", active = false, createdAt = 0)

    private val anna = Birthday(id = "m:1", name = "Anna", month = 11, day = 10, year = 1992, source = BirthdaySource.MANUAL)

    private val healthData = HealthSnapshot(
        fetchedAt = 1234,
        days = listOf(
            HealthDay(epochDay = day - 1, steps = 9999, kcal = 2600.0, activeZoneMinutes = 40),
            HealthDay(epochDay = day, steps = 4321, kcal = 2300.0, activeZoneMinutes = 22),
        ),
        sleepNights = listOf(
            SleepNight(
                epochDay = day - 1,
                start = millis("2026-11-08T23:00"),
                end = millis("2026-11-09T07:00"),
                minutesAsleep = 440,
            ),
            SleepNight(
                epochDay = day,
                start = millis("2026-11-09T23:30"),
                end = millis("2026-11-10T06:30"),
                minutesAsleep = 395,
            ),
        ),
        restingHeartRate = listOf(HeartDay(day - 1, 58), HeartDay(day, 61)),
    )

    private val apple = FoodEntryEntity(id = 1, epochDay = day, meal = MealType.SNACK, name = "Apfel", kcal = 80, createdAt = 1000)

    private fun build(
        settings: AppSettings = AppSettings(),
        events: List<EventInstance> = emptyList(),
        routines: List<RoutineEntity> = emptyList(),
        routineLog: List<RoutineLogEntity> = emptyList(),
        tasks: List<TaskEntity> = emptyList(),
        birthdays: List<Birthday> = emptyList(),
        health: HealthSnapshot? = null,
        food: List<FoodEntryEntity> = emptyList(),
        zone: ZoneId = Berlin,
        calendarAvailable: Boolean = true,
    ): WatchSnapshot = WatchSnapshotBuilder.build(
        WatchSnapshotInput(
            now = now,
            zone = zone,
            settings = settings,
            calendarAvailable = calendarAvailable,
            events = events,
            routines = routines,
            routineLog = routineLog,
            tasks = tasks,
            birthdays = birthdays,
            health = health,
            food = food,
        ),
    )

    /** Ein Termin am 10. November, [minute] Minuten nach 8 Uhr, zehn Minuten lang. */
    private fun eventAtMinute(id: Long, minute: Long, title: String = "Termin $id", location: String? = null): EventInstance {
        val begin = millis("2026-11-10T08:00") + minute * 60_000
        return event(id, "2026-11-10T08:00", "2026-11-10T08:10", title = title)
            .copy(begin = begin, end = begin + 600_000, location = location)
    }

    // ---------- Kopf und Seitenauswahl ----------

    @Test
    fun emptyInputGivesHeaderWithAllPages() {
        val s = build()
        assertEquals(WATCH_PROTOCOL_VERSION, s.version)
        assertEquals(now, s.generatedAt)
        assertEquals("Europe/Berlin", s.zoneId)
        assertEquals("BOTH", s.ringTarget)
        assertEquals(listOf("calendar", "tasks", "routines", "birthdays", "health", "nutrition"), s.pages)
        assertTrue(s.calendarAvailable)
        assertTrue(s.events.isEmpty())
        assertFalse(s.hasRoutines)
        assertTrue(s.routines.isEmpty())
        assertTrue(s.tasks.isEmpty())
        assertEquals(0, s.moreTasks)
        assertTrue(s.birthdays.isEmpty())
        // Google Health ist nicht verbunden.
        assertNull(s.health)
        // Die Seite „Essen“ ist sichtbar: ohne Einträge und ohne Profil gilt der Standard-Grundumsatz.
        assertEquals(WatchNutrition(epochDay = day, eatenKcal = 0, budgetKcal = 2000), s.nutrition)
    }

    @Test
    fun hiddenPagesAndRingTargetComeFromTheSettings() {
        val s = build(
            settings = AppSettings(ringTarget = RingTarget.WATCH_IF_WORN, watchHiddenPages = setOf("nutrition", "tasks")),
        )
        assertEquals(listOf("calendar", "routines", "birthdays", "health"), s.pages)
        assertEquals(listOf(WatchPage.CALENDAR, WatchPage.ROUTINES, WatchPage.BIRTHDAYS, WatchPage.HEALTH), WatchSnapshotLogic.visiblePages(s))
        assertEquals("WATCH_IF_WORN", s.ringTarget)
    }

    @Test
    fun missingCalendarPermissionIsPassedOn() {
        assertFalse(build(calendarAvailable = false).calendarAvailable)
    }

    // ---------- Termine ----------

    @Test
    fun eventsUseTheFiltersOfTheHomeScreen() {
        val s = build(
            settings = AppSettings(hiddenCalendars = setOf(5L)),
            events = listOf(
                event(6, "2026-11-11T10:00", "2026-11-11T11:00"),
                event(1, "2026-11-10T09:00", "2026-11-10T10:00"),
                event(2, "2026-11-10T11:00", "2026-11-10T12:00", calendarId = 5),
                event(3, "2026-11-10T12:00", "2026-11-10T13:00", canceled = true),
                event(4, "2026-11-10T14:00", "2026-11-10T15:00", declined = true),
                event(5, "2026-11-09T09:00", "2026-11-09T10:00"),
            ),
        )
        // Übrig: der Termin von heute und der von morgen – ohne ausgeblendeten Kalender, Abgesagtes, Abgelehntes, Gestriges.
        assertEquals(listOf(1L, 6L), s.events.map { it.id })
        assertEquals(
            WatchEvent(
                id = 1,
                title = "Termin 1",
                begin = millis("2026-11-10T09:00"),
                end = millis("2026-11-10T10:00"),
                allDay = false,
                color = 0xFF0B57D0.toInt(),
                location = null,
            ),
            s.events.first(),
        )
    }

    @Test
    fun nowAndNextMatchTheHomeScreen() {
        val s = build(
            events = listOf(
                event(2, "2026-11-10T15:00", "2026-11-10T16:00"),
                // Abgelehnt: „Als Nächstes“ übergeht ihn.
                event(3, "2026-11-10T14:00", "2026-11-10T14:30", declined = true),
                event(1, "2026-11-10T13:00", "2026-11-10T14:30"),
            ),
        )
        val nowNext = WatchSnapshotLogic.nowNext(s.events, now)
        assertEquals(1L, nowNext.current?.id)
        assertEquals(2L, nowNext.next?.id)
    }

    @Test
    fun eventOverMidnightStaysUntilItEnds() {
        val s = build(
            events = listOf(
                // Endet genau um Mitternacht: gehört noch zu gestern.
                event(1, "2026-11-09T22:00", "2026-11-10T00:00"),
                event(2, "2026-11-09T23:00", "2026-11-10T01:00"),
            ),
        )
        assertEquals(listOf(2L), s.events.map { it.id })
    }

    @Test
    fun calendarPageDecidesHowFarEventsReach() {
        val events = listOf(
            event(1, "2026-11-10T10:00", "2026-11-10T11:00"),
            event(2, "2026-11-11T10:00", "2026-11-11T11:00"),
            event(3, "2026-11-12T10:00", "2026-11-12T11:00"),
            event(4, "2026-11-17T10:00", "2026-11-17T11:00"),
            event(5, "2026-11-18T10:00", "2026-11-18T11:00"),
        )
        // Mit der Seite „Kalender“: heute und die nächsten sieben Tage.
        assertEquals(listOf(1L, 2L, 3L, 4L), build(events = events).events.map { it.id })
        assertEquals(LocalDate.of(2026, 11, 17), WatchSnapshotBuilder.lastEventDay(today, emptySet()))
        // Ohne sie: nur heute und morgen, so weit schaut die Startseite.
        val homeOnly = build(settings = AppSettings(watchHiddenPages = setOf("calendar")), events = events)
        assertEquals(listOf(1L, 2L), homeOnly.events.map { it.id })
        assertEquals(LocalDate.of(2026, 11, 11), WatchSnapshotBuilder.lastEventDay(today, setOf("calendar")))
    }

    @Test
    fun allDayEventsBecomeLocalDaysAndComeFirst() {
        val s = build(
            events = listOf(
                event(1, "2026-11-10T09:00", "2026-11-10T10:00"),
                event(2, "2026-11-10T00:00", "2026-11-10T00:30"),
                allDayEvent(7, today),
                // Drei Tage: gestern, heute, morgen.
                allDayEvent(8, today.minusDays(1), days = 3),
                // Nur gestern: schon vorbei.
                allDayEvent(9, today.minusDays(1)),
            ),
        )
        // Nach Beginn sortiert; bei gleichem Beginn (Mitternacht) steht der ganztägige Termin vorn.
        assertEquals(listOf(8L, 7L, 2L, 1L), s.events.map { it.id })
        val oneDay = s.events[1]
        assertTrue(oneDay.allDay)
        assertEquals(millis("2026-11-10T00:00"), oneDay.begin)
        assertEquals(millis("2026-11-11T00:00"), oneDay.end)
        val threeDays = s.events[0]
        assertEquals(millis("2026-11-09T00:00"), threeDays.begin)
        assertEquals(millis("2026-11-12T00:00"), threeDays.end)

        // So sortiert die Uhr die Tage: heute alle vier, morgen nur noch der dreitägige, übermorgen keiner mehr.
        val todayStart = millis("2026-11-10T00:00")
        val tomorrowStart = millis("2026-11-11T00:00")
        val dayAfterStart = millis("2026-11-12T00:00")
        assertEquals(listOf(8L, 7L, 2L, 1L), WatchSnapshotLogic.eventsOn(s.events, todayStart, tomorrowStart).map { it.id })
        assertEquals(listOf(8L), WatchSnapshotLogic.eventsOn(s.events, tomorrowStart, dayAfterStart).map { it.id })
        assertTrue(WatchSnapshotLogic.eventsOn(s.events, dayAfterStart, millis("2026-11-13T00:00")).isEmpty())
    }

    @Test
    fun allDayEventsUseTheZoneOfThePhone() {
        // 13:45 Uhr in Berlin ist 07:45 Uhr in New York – dort ist auch der 10. November.
        val s = build(events = listOf(allDayEvent(7, today), allDayEvent(9, today.minusDays(1))), zone = newYork)
        assertEquals("America/New_York", s.zoneId)
        assertEquals(listOf(7L), s.events.map { it.id })
        assertEquals(millis("2026-11-10T00:00", newYork), s.events[0].begin)
        assertEquals(millis("2026-11-11T00:00", newYork), s.events[0].end)
    }

    @Test
    fun eventTextsAreShortened() {
        val s = build(
            events = listOf(
                eventAtMinute(1, 0, title = "   "),
                eventAtMinute(2, 1, title = "a".repeat(100), location = "b".repeat(100)),
                eventAtMinute(3, 2, title = "  Zahnarzt ", location = "  Praxis Dr. Weber "),
                eventAtMinute(4, 3, location = "   "),
            ),
        )
        assertEquals("(Kein Titel)", s.events[0].title)
        assertNull(s.events[0].location)
        assertEquals("a".repeat(79) + "…", s.events[1].title)
        assertEquals("b".repeat(79) + "…", s.events[1].location)
        assertEquals("Zahnarzt", s.events[2].title)
        assertEquals("Praxis Dr. Weber", s.events[2].location)
        assertNull(s.events[3].location)
    }

    @Test
    fun shortenKeepsShortTextsAndCutsLongOnes() {
        assertEquals("Zahnarzt", WatchSnapshotBuilder.shorten("  Zahnarzt  "))
        assertEquals("a".repeat(80), WatchSnapshotBuilder.shorten("a".repeat(80)))
        assertEquals("a".repeat(79) + "…", WatchSnapshotBuilder.shorten("a".repeat(81)))
        assertEquals(80, WatchSnapshotBuilder.shorten("a".repeat(500)).length)
        // Kein Leerzeichen vor den Auslassungspunkten.
        assertEquals("ab…", WatchSnapshotBuilder.shorten("ab cd", 4))
        assertEquals("Wort ".repeat(15) + "Wort…", WatchSnapshotBuilder.shorten("Wort ".repeat(20)))
        // Ein Emoji besteht aus zwei Zeichen – es wird nicht in der Mitte geteilt.
        assertEquals("a".repeat(78) + "…", WatchSnapshotBuilder.shorten("a".repeat(78) + "😀bbb"))
    }

    @Test
    fun atMostSixtyEventsTheEarliestOnes() {
        val events = (1L..70L).map { eventAtMinute(it, it) }.reversed()
        val s = build(events = events)
        assertEquals((1L..60L).toList(), s.events.map { it.id })
    }

    // ---------- Routinen und Medikamente ----------

    @Test
    fun routinesOfTodayAndTomorrowWithTheirState() {
        val log = listOf(
            RoutineLogEntity(routineId = 1, epochDay = day, minutes = 480, doneAt = 0, byDispenser = true),
            RoutineLogEntity(routineId = 1, epochDay = day + 1, minutes = 480, skipped = true, doneAt = 0),
        )
        val routines = listOf(checkup, paused, med)
        val s = build(routines = routines, routineLog = log)
        assertTrue(s.hasRoutines)
        assertEquals(
            listOf(
                "rt:1:$day:480",
                "rt:1:$day:840",
                "rt:2:$day:1200",
                "rt:1:${day + 1}:480",
                "rt:1:${day + 1}:840",
                "rt:2:${day + 1}:1200",
            ),
            s.routines.map { it.key },
        )
        assertEquals(
            WatchRoutine(
                key = "rt:1:$day:480",
                routineId = 1,
                name = "Methylphenidat",
                dose = "10 mg",
                kind = "MEDICATION",
                epochDay = day,
                minutes = 480,
                at = millis("2026-11-10T08:00"),
                done = true,
                skipped = false,
                byDispenser = true,
            ),
            s.routines[0],
        )
        // Der Checkup lässt sich auf der Uhr nicht abhaken.
        assertEquals("CHECKUP", s.routines[2].kind)
        assertFalse(s.routines[2].canToggle)
        assertEquals(millis("2026-11-10T20:00"), s.routines[2].at)
        assertEquals("", s.routines[2].dose)
        // Übersprungen gilt nicht mehr als offen.
        assertTrue(s.routines[3].skipped)
        assertFalse(s.routines[3].done)
        assertFalse(s.routines[3].open)

        // Die Startseite der Uhr zeigt dieselben nächsten Einträge wie die des Handys.
        val expected = listOf("rt:1:$day:840", "rt:2:$day:1200")
        assertEquals(expected, WatchSnapshotLogic.openRoutines(s.routines, limit = 2).map { it.key })
        assertEquals(expected, RoutineLogic.upcoming(routines, log, now, Berlin, limit = 2).map { it.key })
    }

    @Test
    fun pausedRoutinesStillCountAsExisting() {
        val s = build(routines = listOf(paused))
        assertTrue(s.hasRoutines)
        assertTrue(s.routines.isEmpty())
        assertFalse(build().hasRoutines)
    }

    @Test
    fun routineLimitKeepsTheNextOpenOnes() {
        // 25 Uhrzeiten am Tag: 06:00, 06:30 … 18:00 – heute und morgen zusammen 50 Vorkommen.
        val times = (0 until 25).map { 360 + it * 30 }
        val water = RoutineEntity(
            id = 1,
            name = "Wasser trinken",
            kind = RoutineKind.ROUTINE,
            times = times.joinToString(","),
            createdAt = 0,
        )
        // Heute ist alles abgehakt, morgen schon die ersten 14 – offen sind nur die letzten 11 von morgen.
        val log = times.map { RoutineLogEntity(routineId = 1, epochDay = day, minutes = it, doneAt = 0) } +
            times.take(14).map { RoutineLogEntity(routineId = 1, epochDay = day + 1, minutes = it, doneAt = 0) }
        val s = build(routines = listOf(water), routineLog = log)

        assertEquals(40, s.routines.size)
        assertEquals(s.routines.map { it.at }.sorted(), s.routines.map { it.at })
        // Alle 25 von heute bleiben, von morgen die ersten 9 abgehakten …
        assertEquals(25, s.routines.count { it.epochDay == day })
        assertEquals(times.subList(0, 9), s.routines.filter { it.epochDay == day + 1 && !it.open }.map { it.minutes })
        // … und die 6 nächsten offenen, obwohl sie ganz am Ende liegen.
        val open = s.routines.filter { it.open }
        assertEquals(times.subList(14, 20), open.map { it.minutes })
        assertTrue(open.all { it.epochDay == day + 1 })
        assertEquals(
            RoutineLogic.upcoming(listOf(water), log, now, Berlin, limit = 6).map { it.key },
            WatchSnapshotLogic.openRoutines(s.routines, limit = 6).map { it.key },
        )
    }

    // ---------- Aufgaben ----------

    @Test
    fun onlyDueOpenTasksInTheOrderOfThePhone() {
        val tasks = listOf(
            TaskEntity(id = 1, title = "Heute ohne Uhrzeit", dueEpochDay = day, createdAt = 100),
            TaskEntity(id = 2, title = "Heute 18 Uhr", dueEpochDay = day, dueMinutes = 1080, createdAt = 200),
            TaskEntity(
                id = 3,
                title = "Überfällig",
                dueEpochDay = day - 2,
                important = true,
                repeat = Repeat.DAILY,
                createdAt = 300,
            ),
            TaskEntity(id = 4, title = "Morgen", dueEpochDay = day + 1, createdAt = 400),
            TaskEntity(id = 5, title = "Ohne Datum", createdAt = 500),
            TaskEntity(id = 6, title = "Erledigt", dueEpochDay = day, done = true, createdAt = 600),
            TaskEntity(id = 7, title = "Heute wichtig", dueEpochDay = day, important = true, createdAt = 700),
        )
        val s = build(tasks = tasks)
        // Überfälliges zuerst, dann mit Uhrzeit, dann ohne Uhrzeit – dabei Wichtiges vorn.
        assertEquals(listOf(3L, 2L, 7L, 1L), s.tasks.map { it.id })
        assertEquals(0, s.moreTasks)
        assertEquals(
            WatchTask(id = 3, title = "Überfällig", dueEpochDay = day - 2, dueMinutes = null, important = true, repeating = true),
            s.tasks[0],
        )
        assertEquals(
            WatchTask(id = 2, title = "Heute 18 Uhr", dueEpochDay = day, dueMinutes = 1080, important = false, repeating = false),
            s.tasks[1],
        )
        assertTrue(WatchSnapshotLogic.isOverdue(s.tasks[0], day))
        assertFalse(WatchSnapshotLogic.isOverdue(s.tasks[1], day))
    }

    @Test
    fun atMostThirtyTasksAndTheRestIsCounted() {
        val tasks = (1L..35L).map { TaskEntity(id = it, title = "Aufgabe $it", dueEpochDay = day, createdAt = it) }.reversed()
        val s = build(tasks = tasks)
        assertEquals((1L..30L).toList(), s.tasks.map { it.id })
        assertEquals(5, s.moreTasks)
    }

    // ---------- Geburtstage ----------

    @Test
    fun birthdaysOfTheNextFourteenDays() {
        val s = build(
            birthdays = listOf(
                Birthday(id = "m:3", name = "Carla", month = 11, day = 25, year = 1990, source = BirthdaySource.MANUAL),
                Birthday(id = "c:2", name = "Ben", month = 11, day = 24, year = null, source = BirthdaySource.CONTACT),
                // Gestern gewesen: der nächste ist erst in einem Jahr.
                Birthday(id = "m:4", name = "Dora", month = 11, day = 9, year = 1980, source = BirthdaySource.MANUAL),
                anna,
            ),
        )
        assertEquals(
            listOf(
                WatchBirthday(name = "Anna", epochDay = day, age = 34),
                WatchBirthday(name = "Ben", epochDay = LocalDate.of(2026, 11, 24).toEpochDay(), age = null),
            ),
            s.birthdays,
        )
    }

    @Test
    fun atMostTwelveBirthdays() {
        val all = (1..15).map {
            Birthday(
                id = "m:$it",
                name = "P" + it.toString().padStart(2, '0'),
                month = 11,
                day = 12,
                year = null,
                source = BirthdaySource.MANUAL,
            )
        }.reversed()
        val s = build(birthdays = all)
        assertEquals((1..12).map { "P" + it.toString().padStart(2, '0') }, s.birthdays.map { it.name })
        assertTrue(s.birthdays.all { it.epochDay == day + 2 })
    }

    // ---------- Gesundheit ----------

    @Test
    fun noHealthWithoutGoogleHealth() {
        assertNull(build(health = healthData).health)
    }

    @Test
    fun healthOfTodayWithDetailsForTheHealthPage() {
        val s = build(settings = AppSettings(healthConnected = true, stepGoal = 9000), health = healthData)
        assertEquals(
            WatchHealth(
                epochDay = day,
                steps = 4321,
                stepGoal = 9000,
                sleepMinutes = 395,
                restingHeartRate = 61,
                activeZoneMinutes = 22,
                fetchedAt = 1234,
            ),
            s.health,
        )
    }

    @Test
    fun connectedButNothingLoadedYetGivesEmptyHealth() {
        val s = build(settings = AppSettings(healthConnected = true, stepGoal = 9000))
        assertEquals(WatchHealth(epochDay = day, stepGoal = 9000), s.health)
    }

    @Test
    fun hiddenHealthPageKeepsOnlyWhatTheHomeScreenShows() {
        val s = build(settings = AppSettings(healthConnected = true, watchHiddenPages = setOf("health")), health = healthData)
        assertFalse("health" in s.pages)
        assertEquals(
            WatchHealth(epochDay = day, steps = 4321, stepGoal = 8000, sleepMinutes = 395, fetchedAt = 1234),
            s.health,
        )
    }

    // ---------- Essen ----------

    @Test
    fun nutritionUsesTheSameMathAsThePhone() {
        val settings = AppSettings(
            sex = Sex.MALE,
            birthYear = 1994,
            heightCm = 180,
            weightKg = 78.0,
            calorieAdjustment = -300,
            healthConnected = true,
        )
        val food = listOf(
            FoodEntryEntity(id = 1, epochDay = day, meal = MealType.BREAKFAST, name = "Müsli", kcal = 400, createdAt = 1000),
            FoodEntryEntity(id = 2, epochDay = day, meal = MealType.LUNCH, name = "Nudeln", kcal = 500, createdAt = 2000),
            // Von gestern: zählt nicht.
            FoodEntryEntity(id = 3, epochDay = day - 1, meal = MealType.DINNER, name = "Pizza", kcal = 900, createdAt = 500),
        )
        val s = build(settings = settings, health = healthData, food = food)
        // Grundumsatz: 10·78 + 6,25·180 − 5·32 + 5 = 1750. Bewegung: 2300 − 1750 = 550. Budget: 1750 + 550 − 300 = 2000.
        assertEquals(
            WatchNutrition(
                epochDay = day,
                eatenKcal = 900,
                budgetKcal = 2000,
                // Das Neueste zuerst.
                entries = listOf(WatchFood("Nudeln", 500, "Mittags"), WatchFood("Müsli", 400, "Morgens")),
            ),
            s.nutrition,
        )
        assertEquals(1100, s.nutrition?.remainingKcal)
    }

    @Test
    fun nutritionWithoutProfileUsesTheDefaultBudget() {
        // Google Health ist nicht verbunden: Der Verbrauch aus alten Gesundheitsdaten zählt nicht.
        val s = build(health = healthData, food = listOf(apple))
        assertEquals(
            WatchNutrition(epochDay = day, eatenKcal = 80, budgetKcal = 2000, entries = listOf(WatchFood("Apfel", 80, "Zwischendurch"))),
            s.nutrition,
        )
    }

    @Test
    fun noNutritionWithoutTheNutritionPage() {
        val s = build(settings = AppSettings(watchHiddenPages = setOf("nutrition")), food = listOf(apple))
        assertNull(s.nutrition)
    }

    @Test
    fun atMostTwentyFoodEntriesTheNewestOnes() {
        val food = (1L..25L).map {
            FoodEntryEntity(id = it, epochDay = day, meal = MealType.SNACK, name = "E$it", kcal = 10, createdAt = it)
        }
        val s = build(food = food)
        // Gezählt wird alles, gezeigt werden die letzten 20.
        assertEquals(250, s.nutrition?.eatenKcal)
        assertEquals((25 downTo 6).map { "E$it" }, s.nutrition?.entries?.map { it.name })
    }

    // ---------- Seitenauswahl und Größe ----------

    @Test
    fun withoutSubpagesOnlyTheHomeScreenDataRemains() {
        val s = build(
            settings = AppSettings(watchHiddenPages = WatchPage.entries.map { it.id }.toSet(), healthConnected = true),
            events = listOf(
                event(1, "2026-11-10T15:00", "2026-11-10T16:00"),
                event(2, "2026-11-11T10:00", "2026-11-11T11:00"),
                event(3, "2026-11-12T10:00", "2026-11-12T11:00"),
            ),
            routines = listOf(med),
            tasks = listOf(TaskEntity(id = 1, title = "Heute", dueEpochDay = day, createdAt = 100)),
            birthdays = listOf(anna),
            health = healthData,
            food = listOf(apple),
        )
        assertTrue(s.pages.isEmpty())
        assertEquals(listOf(1L, 2L), s.events.map { it.id })
        assertTrue(s.hasRoutines)
        assertEquals(4, s.routines.size)
        assertEquals(listOf(1L), s.tasks.map { it.id })
        assertEquals(listOf("Anna"), s.birthdays.map { it.name })
        assertEquals(4321L, s.health?.steps)
        assertEquals(395L, s.health?.sleepMinutes)
        assertNull(s.health?.restingHeartRate)
        assertNull(s.health?.activeZoneMinutes)
        assertNull(s.nutrition)
    }

    @Test
    fun fullSnapshotStaysWithinAllLimits() {
        val times = (0 until 25).map { 360 + it * 30 }
        val s = build(
            settings = AppSettings(healthConnected = true),
            events = (1L..70L).map { eventAtMinute(it, it, title = "t".repeat(100), location = "o".repeat(100)) },
            routines = listOf(
                RoutineEntity(id = 1, name = "n".repeat(100), dose = "d".repeat(100), times = times.joinToString(","), createdAt = 0),
            ),
            tasks = (1L..35L).map { TaskEntity(id = it, title = "a".repeat(100), dueEpochDay = day, createdAt = it) },
            birthdays = (1..15).map {
                Birthday(id = "m:$it", name = "g".repeat(100), month = 11, day = 12, year = 1990, source = BirthdaySource.MANUAL)
            },
            health = healthData,
            food = (1L..25L).map {
                FoodEntryEntity(id = it, epochDay = day, meal = MealType.SNACK, name = "e".repeat(100), kcal = 10, createdAt = it)
            },
        )
        assertEquals(60, s.events.size)
        assertEquals(40, s.routines.size)
        assertEquals(30, s.tasks.size)
        assertEquals(5, s.moreTasks)
        assertEquals(12, s.birthdays.size)
        assertEquals(20, s.nutrition?.entries?.size)
        assertTrue(s.events.all { it.title.length == 80 && it.location?.length == 80 })
        assertTrue(s.routines.all { it.name.length == 80 && it.dose.length == 80 })
        assertTrue(s.tasks.all { it.title.length == 80 })
        assertTrue(s.birthdays.all { it.name.length == 80 })
        // Auch so voll passt die Übersicht in das Datenelement der Uhr – es muss nichts gekürzt werden.
        assertTrue(WatchSnapshotBuilder.encode(s).size < WatchSnapshotBuilder.MAX_BYTES)
        assertEquals(s, WatchSnapshotBuilder.fit(s))
    }

    /** Eine von Hand gebaute Übersicht mit 40 Terminen, 10 offenen Routinen, 20 Aufgaben (+3), 8 Geburtstagen, 10 Essen. */
    private fun bigSnapshot(): WatchSnapshot = WatchSnapshot(
        generatedAt = now,
        zoneId = "Europe/Berlin",
        events = (1L..40L).map {
            WatchEvent(id = it, title = "Termin $it", begin = now + it * 60_000, end = now + it * 60_000 + 1_800_000)
        },
        hasRoutines = true,
        routines = (0 until 10).map {
            WatchRoutine(
                key = "rt:1:$day:${480 + it}",
                routineId = 1,
                name = "Routine",
                epochDay = day,
                minutes = 480 + it,
                at = millis("2026-11-10T08:00") + it * 60_000L,
            )
        },
        tasks = (1L..20L).map { WatchTask(id = it, title = "Aufgabe $it", dueEpochDay = day) },
        moreTasks = 3,
        birthdays = (1..8).map { WatchBirthday(name = "Person $it", epochDay = day + it) },
        nutrition = WatchNutrition(
            epochDay = day,
            eatenKcal = 500,
            budgetKcal = 2000,
            entries = (1..10).map { WatchFood(name = "Essen $it", kcal = 50, meal = "Mittags") },
        ),
    )

    @Test
    fun fitLeavesSmallSnapshotsAlone() {
        val big = bigSnapshot()
        assertEquals(big, WatchSnapshotBuilder.fit(big))
    }

    @Test
    fun fitDropsTheLatestEntriesUntilItFits() {
        val big = bigSnapshot()
        val fitted = WatchSnapshotBuilder.fit(big, 2_500)
        assertTrue(WatchSnapshotBuilder.encode(fitted).size <= 2_500)
        // Es bleibt jeweils der Anfang der Liste stehen.
        assertTrue(fitted.events.isNotEmpty() && fitted.events.size < 40)
        assertEquals(big.events.take(fitted.events.size), fitted.events)
        assertTrue(fitted.routines.isNotEmpty())
        assertEquals(big.routines.take(fitted.routines.size), fitted.routines)
        assertTrue(fitted.tasks.isNotEmpty())
        assertEquals(big.tasks.take(fitted.tasks.size), fitted.tasks)
        assertTrue(fitted.birthdays.isNotEmpty())
        assertEquals(big.birthdays.take(fitted.birthdays.size), fitted.birthdays)
        // Gestrichene Aufgaben zählen weiter: 20 in der Liste + 3 weitere.
        assertEquals(23, fitted.tasks.size + fitted.moreTasks)
        // Die Summen ändern sich nicht, nur die Liste der Einträge wird kürzer.
        assertEquals(500, fitted.nutrition?.eatenKcal)
        assertEquals(2000, fitted.nutrition?.budgetKcal)
        assertEquals(big.generatedAt, fitted.generatedAt)
        assertEquals(big.pages, fitted.pages)
    }

    @Test
    fun fitStopsWithOneEntryPerList() {
        val fitted = WatchSnapshotBuilder.fit(bigSnapshot(), 10)
        assertEquals(listOf(1L), fitted.events.map { it.id })
        assertEquals(listOf("rt:1:$day:480"), fitted.routines.map { it.key })
        assertEquals(listOf(1L), fitted.tasks.map { it.id })
        assertEquals(22, fitted.moreTasks)
        assertEquals(listOf("Person 1"), fitted.birthdays.map { it.name })
        assertEquals(listOf("Essen 1"), fitted.nutrition?.entries?.map { it.name })
    }

    // ---------- Fingerabdruck und JSON ----------

    @Test
    fun contentHashIgnoresOnlyTheTimestamps() {
        val a = build(
            settings = AppSettings(healthConnected = true),
            tasks = listOf(TaskEntity(id = 1, title = "Heute", dueEpochDay = day, createdAt = 100)),
            health = healthData,
        )
        val hash = WatchSnapshotBuilder.contentHash(a)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })

        val later = a.copy(generatedAt = a.generatedAt + 60_000, health = a.health?.copy(fetchedAt = 9999))
        assertEquals(hash, WatchSnapshotBuilder.contentHash(later))

        val otherTitle = a.copy(tasks = a.tasks.map { it.copy(title = "Anders") })
        assertNotEquals(hash, WatchSnapshotBuilder.contentHash(otherTitle))
        val moreSteps = a.copy(health = a.health?.copy(steps = 5000))
        assertNotEquals(hash, WatchSnapshotBuilder.contentHash(moreSteps))
        val otherPages = a.copy(pages = listOf("calendar"))
        assertNotEquals(hash, WatchSnapshotBuilder.contentHash(otherPages))
    }

    @Test
    fun encodedSnapshotCanBeReadAgain() {
        val s = build(
            settings = AppSettings(healthConnected = true, watchHiddenPages = setOf("birthdays")),
            events = listOf(event(1, "2026-11-10T15:00", "2026-11-10T16:00"), allDayEvent(7, today)),
            routines = listOf(med, checkup),
            routineLog = listOf(RoutineLogEntity(routineId = 1, epochDay = day, minutes = 480, doneAt = 0)),
            tasks = listOf(TaskEntity(id = 1, title = "Heute", dueEpochDay = day, createdAt = 100)),
            birthdays = listOf(anna),
            health = healthData,
            food = listOf(apple),
        )
        val json = WatchSnapshotBuilder.encode(s).decodeToString()
        assertEquals(s, WatchJson.decodeFromString<WatchSnapshot>(json))
    }
}

package de.lltntplanet.adhsapp.wear.ui

import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchFood
import de.lltntplanet.adhsapp.shared.WatchNutrition
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchTask
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFormatTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("UTC")

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Sonntag, 22.11.2026 (LocalDate.toEpochDay). */
    private val today = 20_779L

    /** 22.11.2026, 00:00 Uhr in Berlin (= 21.11., 23:00 Uhr UTC). */
    private val dayStart = 1_795_302_000_000L

    /** 22.11.2026, 08:00 Uhr in Berlin. */
    private val now = dayStart + 8 * hour

    private fun event(begin: Long, end: Long, allDay: Boolean = false) =
        WatchEvent(id = 1, title = "Termin", begin = begin, end = end, allDay = allDay)

    private fun routine(
        minutes: Int,
        epochDay: Long = today,
        kind: String = WatchRoutine.KIND_MEDICATION,
        dose: String = "",
        done: Boolean = false,
        skipped: Boolean = false,
        byDispenser: Boolean = false,
    ) = WatchRoutine(
        key = "rt:1:$epochDay:$minutes",
        routineId = 1,
        name = "Routine",
        dose = dose,
        kind = kind,
        epochDay = epochDay,
        minutes = minutes,
        at = dayStart + (epochDay - today) * day + minutes * minute,
        done = done,
        skipped = skipped,
        byDispenser = byDispenser,
    )

    // ---- Zeitzone, Uhrzeit, Tag ----

    @Test
    fun zoneFallsBackWhenUnknown() {
        assertEquals(berlin, WatchFormat.zone("Europe/Berlin", utc))
        assertEquals(utc, WatchFormat.zone("", utc))
        assertEquals(utc, WatchFormat.zone("Mond/Krater", utc))
    }

    @Test
    fun timeUsesTheGivenZone() {
        assertEquals("14:05", WatchFormat.time(dayStart + 14 * hour + 5 * minute, berlin))
        assertEquals("13:05", WatchFormat.time(dayStart + 14 * hour + 5 * minute, utc))
        assertEquals("00:00", WatchFormat.time(dayStart, berlin))
    }

    @Test
    fun minutesOfDayPadsAndClamps() {
        assertEquals("08:05", WatchFormat.minutesOfDay(485))
        assertEquals("00:00", WatchFormat.minutesOfDay(0))
        assertEquals("23:59", WatchFormat.minutesOfDay(1439))
        assertEquals("00:00", WatchFormat.minutesOfDay(-5))
        assertEquals("23:59", WatchFormat.minutesOfDay(2000))
    }

    @Test
    fun epochDayAndDayStartFollowTheZone() {
        assertEquals(today, WatchFormat.epochDay(dayStart, berlin))
        assertEquals(today - 1, WatchFormat.epochDay(dayStart - 1, berlin))
        // In UTC ist es um Mitternacht in Berlin noch der Vortag.
        assertEquals(today - 1, WatchFormat.epochDay(dayStart, utc))
        assertEquals(dayStart, WatchFormat.dayStart(today, berlin))
        assertEquals(dayStart + day, WatchFormat.dayStart(today + 1, berlin))
    }

    @Test
    fun dayStartKnowsTheShortDayInSpring() {
        // 29.3.2026: Die Uhr wird vorgestellt, der Tag hat nur 23 Stunden.
        val spring = 20_541L
        assertEquals(1_774_738_800_000L, WatchFormat.dayStart(spring, berlin))
        assertEquals(23 * hour, WatchFormat.dayStart(spring + 1, berlin) - WatchFormat.dayStart(spring, berlin))
    }

    // ---- Dauer und Abstand ----

    @Test
    fun durationInWords() {
        assertEquals("0 Min.", WatchFormat.duration(0))
        assertEquals("45 Min.", WatchFormat.duration(45))
        assertEquals("2 Std.", WatchFormat.duration(120))
        assertEquals("2 Std. 15 Min.", WatchFormat.duration(135))
        assertEquals("1 Tag", WatchFormat.duration(1440))
        assertEquals("1 Tag", WatchFormat.duration(1470))
        assertEquals("1 Tag 3 Std.", WatchFormat.duration(1620))
        assertEquals("1 Tag 3 Std.", WatchFormat.duration(1640))
        assertEquals("2 Tage", WatchFormat.duration(2880))
        assertEquals("45 Min.", WatchFormat.duration(-45))
    }

    @Test
    fun durationInDativeChangesOnlyThePluralOfDays() {
        assertEquals("2 Tagen", WatchFormat.duration(2880, dative = true))
        assertEquals("1 Tag", WatchFormat.duration(1440, dative = true))
        assertEquals("2 Tagen 1 Std.", WatchFormat.duration(2940, dative = true))
        assertEquals("45 Min.", WatchFormat.duration(45, dative = true))
    }

    @Test
    fun countdownBeforeAtAndAfter() {
        assertEquals("in 25 Min.", WatchFormat.countdown(now + 25 * minute, now))
        // Angebrochene Minuten zählen voll: 24 Min. 1 Sek. sind „in 25 Min.“.
        assertEquals("in 25 Min.", WatchFormat.countdown(now + 24 * minute + 1_000, now))
        assertEquals("in 1 Min.", WatchFormat.countdown(now + minute, now))
        assertEquals("in 2 Std.", WatchFormat.countdown(now + 2 * hour, now))
        assertEquals("in 2 Tagen", WatchFormat.countdown(now + 2 * day, now))
        assertEquals("jetzt", WatchFormat.countdown(now + 30_000, now))
        assertEquals("jetzt", WatchFormat.countdown(now, now))
        assertEquals("jetzt", WatchFormat.countdown(now - 30_000, now))
        assertEquals("seit 1 Min.", WatchFormat.countdown(now - minute, now))
        assertEquals("seit 1 Min.", WatchFormat.countdown(now - 90_000, now))
        assertEquals("seit 5 Min.", WatchFormat.countdown(now - 5 * minute, now))
    }

    @Test
    fun remainingRoundsUpAndNeverShowsZero() {
        assertEquals("noch 25 Min.", WatchFormat.remaining(now + 25 * minute, now))
        assertEquals("noch 1 Std. 30 Min.", WatchFormat.remaining(now + 90 * minute, now))
        assertEquals("noch 1 Min.", WatchFormat.remaining(now + 10_000, now))
        assertEquals("noch 1 Min.", WatchFormat.remaining(now - 5 * minute, now))
    }

    // ---- Tage ----

    @Test
    fun shortDateWithWeekday() {
        assertEquals("So. 22.11.", WatchFormat.shortDate(today))
        assertEquals("Fr. 27.11.", WatchFormat.shortDate(20_784))
        assertEquals("Do. 1.1.", WatchFormat.shortDate(20_454))
    }

    @Test
    fun relativeDayNamesTheNearDays() {
        assertEquals("Heute", WatchFormat.relativeDay(today, today))
        assertEquals("Morgen", WatchFormat.relativeDay(today + 1, today))
        assertEquals("Gestern", WatchFormat.relativeDay(today - 1, today))
        assertEquals("Di. 24.11.", WatchFormat.relativeDay(today + 2, today))
        assertEquals("Fr. 20.11.", WatchFormat.relativeDay(today - 2, today))
    }

    @Test
    fun daysUntilCountsDays() {
        assertEquals("Heute", WatchFormat.daysUntil(today, today))
        assertEquals("Morgen", WatchFormat.daysUntil(today + 1, today))
        assertEquals("Gestern", WatchFormat.daysUntil(today - 1, today))
        assertEquals("in 5 Tagen", WatchFormat.daysUntil(today + 5, today))
        assertEquals("vor 3 Tagen", WatchFormat.daysUntil(today - 3, today))
    }

    // ---- Termine ----

    @Test
    fun startLineAddsTheDayWhenNotToday() {
        assertEquals("15:00", WatchFormat.startLine(event(dayStart + 15 * hour, dayStart + 16 * hour), today, berlin))
        assertEquals(
            "Morgen 09:00",
            WatchFormat.startLine(event(dayStart + day + 9 * hour, dayStart + day + 10 * hour), today, berlin),
        )
        assertEquals(
            "Mi. 25.11. 09:00",
            WatchFormat.startLine(event(dayStart + 3 * day + 9 * hour, dayStart + 3 * day + 10 * hour), today, berlin),
        )
    }

    @Test
    fun eventTimeWithinOneDay() {
        val dayEnd = dayStart + day
        assertEquals(
            "14:00–15:00",
            WatchFormat.eventTime(event(dayStart + 14 * hour, dayStart + 15 * hour), dayStart, dayEnd, berlin),
        )
        assertEquals(
            "00:00–01:00",
            WatchFormat.eventTime(event(dayStart, dayStart + hour), dayStart, dayEnd, berlin),
        )
        // Endet genau um Mitternacht: gehört noch ganz zu diesem Tag.
        assertEquals(
            "22:00–00:00",
            WatchFormat.eventTime(event(dayStart + 22 * hour, dayEnd), dayStart, dayEnd, berlin),
        )
        // Ohne Dauer: nur der Beginn.
        assertEquals(
            "08:00",
            WatchFormat.eventTime(event(dayStart + 8 * hour, dayStart + 8 * hour), dayStart, dayEnd, berlin),
        )
    }

    @Test
    fun eventTimeAcrossMidnightAndAllDay() {
        val dayEnd = dayStart + day
        assertEquals(
            "ganztägig",
            WatchFormat.eventTime(event(dayStart, dayEnd, allDay = true), dayStart, dayEnd, berlin),
        )
        val untilMorning = event(dayStart - 2 * hour, dayStart + 9 * hour + 30 * minute)
        assertEquals("bis 09:30", WatchFormat.eventTime(untilMorning, dayStart, dayEnd, berlin))
        assertEquals(
            "ab 22:00",
            WatchFormat.eventTime(event(dayStart + 22 * hour, dayEnd + 2 * hour), dayStart, dayEnd, berlin),
        )
        // Beginnt vorher und endet danach: füllt den ganzen Tag.
        assertEquals(
            "ganztägig",
            WatchFormat.eventTime(event(dayStart - hour, dayEnd + hour), dayStart, dayEnd, berlin),
        )
    }

    // ---- Routinen ----

    @Test
    fun doneWordPerKind() {
        assertEquals("genommen", WatchFormat.doneWord(WatchRoutine.KIND_MEDICATION))
        assertEquals("ausgefüllt", WatchFormat.doneWord(WatchRoutine.KIND_CHECKUP))
        assertEquals("erledigt", WatchFormat.doneWord(WatchRoutine.KIND_ROUTINE))
        assertEquals("erledigt", WatchFormat.doneWord("?"))
    }

    @Test
    fun routineLineForOpenOccurrences() {
        assertEquals(
            "08:25 · 1 Tablette · in 25 Min.",
            WatchFormat.routineLine(routine(minutes = 505, dose = "1 Tablette"), now, today),
        )
        assertEquals("08:25 · in 25 Min.", WatchFormat.routineLine(routine(minutes = 505), now, today))
        assertEquals("08:00 · jetzt", WatchFormat.routineLine(routine(minutes = 480), now, today))
        // Genau zwei Stunden entfernt zählt noch als „bald“ – weiter weg steht nur die Uhrzeit.
        assertEquals("10:00 · in 2 Std.", WatchFormat.routineLine(routine(minutes = 600), now, today))
        assertEquals("12:00", WatchFormat.routineLine(routine(minutes = 720), now, today))
        assertEquals("07:00 · noch offen", WatchFormat.routineLine(routine(minutes = 420), now, today))
    }

    @Test
    fun routineLineForCheckedOccurrences() {
        assertEquals(
            "07:00 · 1 Tablette · genommen",
            WatchFormat.routineLine(routine(minutes = 420, dose = "1 Tablette", done = true), now, today),
        )
        assertEquals(
            "07:00 · 1 Tablette · genommen (Spender)",
            WatchFormat.routineLine(
                routine(minutes = 420, dose = "1 Tablette", done = true, byDispenser = true),
                now,
                today,
            ),
        )
        assertEquals(
            "07:00 · erledigt",
            WatchFormat.routineLine(routine(minutes = 420, kind = WatchRoutine.KIND_ROUTINE, done = true), now, today),
        )
        assertEquals(
            "07:00 · übersprungen",
            WatchFormat.routineLine(routine(minutes = 420, skipped = true), now, today),
        )
    }

    @Test
    fun routineLineForCheckupsAndOtherDays() {
        assertEquals(
            "07:00 · am Handy ausfüllen",
            WatchFormat.routineLine(routine(minutes = 420, kind = WatchRoutine.KIND_CHECKUP), now, today),
        )
        assertEquals(
            "07:00 · ausgefüllt",
            WatchFormat.routineLine(routine(minutes = 420, kind = WatchRoutine.KIND_CHECKUP, done = true), now, today),
        )
        assertEquals(
            "Morgen 08:05 · 1 Tablette",
            WatchFormat.routineLine(routine(minutes = 485, epochDay = today + 1, dose = "1 Tablette"), now, today),
        )
    }

    @Test
    fun routineSummaryCountsOrCelebrates() {
        assertEquals("0 von 5 geschafft", WatchFormat.routineSummary(0, 5))
        assertEquals("3 von 5 geschafft", WatchFormat.routineSummary(3, 5))
        assertEquals("Alles geschafft für heute 🎉", WatchFormat.routineSummary(5, 5))
    }

    // ---- Aufgaben und Geburtstage ----

    @Test
    fun taskLineShowsOverdueBeforeTime() {
        assertEquals(
            "überfällig seit gestern",
            WatchFormat.taskLine(WatchTask(id = 1, title = "A", dueEpochDay = today - 1), today),
        )
        assertEquals(
            "überfällig seit 3 Tagen",
            WatchFormat.taskLine(WatchTask(id = 1, title = "A", dueEpochDay = today - 3, dueMinutes = 870), today),
        )
        assertEquals(
            "14:30",
            WatchFormat.taskLine(WatchTask(id = 1, title = "A", dueEpochDay = today, dueMinutes = 870), today),
        )
        assertNull(WatchFormat.taskLine(WatchTask(id = 1, title = "A", dueEpochDay = today), today))
        assertNull(WatchFormat.taskLine(WatchTask(id = 1, title = "A"), today))
    }

    @Test
    fun birthdayLineWithDistanceDateAndAge() {
        assertEquals("Heute 🎉 · wird 30", WatchFormat.birthdayLine(WatchBirthday("Ada", today, 30), today))
        assertEquals("Heute 🎉", WatchFormat.birthdayLine(WatchBirthday("Ada", today), today))
        assertEquals("Morgen · wird 31", WatchFormat.birthdayLine(WatchBirthday("Ada", today + 1, 31), today))
        assertEquals(
            "in 5 Tagen · Fr. 27.11. · wird 30",
            WatchFormat.birthdayLine(WatchBirthday("Ada", today + 5, 30), today),
        )
        assertEquals("in 5 Tagen · Fr. 27.11.", WatchFormat.birthdayLine(WatchBirthday("Ada", today + 5), today))
    }

    // ---- Zahlen, Essen, Stand ----

    @Test
    fun numberGroupsThousands() {
        assertEquals("0", WatchFormat.number(0))
        assertEquals("999", WatchFormat.number(999))
        assertEquals("1.000", WatchFormat.number(1000))
        assertEquals("6.230", WatchFormat.number(6230))
        assertEquals("1.234.567", WatchFormat.number(1_234_567))
        assertEquals("-1.500", WatchFormat.number(-1500))
    }

    @Test
    fun kcalLeftOrOver() {
        fun eaten(kcal: Int) = WatchNutrition(today, eatenKcal = kcal, budgetKcal = 2000)
        assertEquals("1.250 kcal frei", WatchFormat.kcalLeft(eaten(750)))
        assertEquals("0 kcal frei", WatchFormat.kcalLeft(eaten(2000)))
        assertEquals("150 kcal drüber", WatchFormat.kcalLeft(eaten(2150)))
    }

    @Test
    fun kcalEatenWithAndWithoutBudget() {
        assertEquals(
            "750 von 2.000 kcal gegessen",
            WatchFormat.kcalEaten(WatchNutrition(today, eatenKcal = 750, budgetKcal = 2000)),
        )
        assertEquals("1.200 kcal gegessen", WatchFormat.kcalEaten(WatchNutrition(today, eatenKcal = 1200)))
    }

    @Test
    fun foodLineWithAndWithoutMeal() {
        assertEquals("Frühstück · 320 kcal", WatchFormat.foodLine(WatchFood("Müsli", 320, "Frühstück")))
        assertEquals("1.200 kcal", WatchFormat.foodLine(WatchFood("Pizza", 1200)))
    }

    @Test
    fun everyPageHasItsOwnSymbol() {
        val symbols = WatchPage.entries.map { WatchFormat.symbol(it) }
        assertTrue(symbols.all { it.isNotBlank() })
        assertEquals(WatchPage.entries.size, symbols.toSet().size)
    }

    @Test
    fun asOfAddsTheDayWhenNotToday() {
        assertEquals("Stand: 14:05", WatchFormat.asOf(dayStart + 14 * hour + 5 * minute, dayStart + 15 * hour, berlin))
        assertEquals("Stand: Gestern 23:10", WatchFormat.asOf(dayStart - 50 * minute, dayStart + 7 * hour, berlin))
        assertEquals(
            "Stand: Do. 19.11. 09:00",
            WatchFormat.asOf(dayStart - 3 * day + 9 * hour, dayStart + 7 * hour, berlin),
        )
    }

    @Test
    fun remindersAndMore() {
        assertEquals("1 Erinnerung offen", WatchFormat.reminders(1))
        assertEquals("3 Erinnerungen offen", WatchFormat.reminders(3))
        assertEquals("+ 3 weitere", WatchFormat.more(3, onPhone = false))
        assertEquals("+ 3 weitere am Handy", WatchFormat.more(3, onPhone = true))
    }
}

package de.lltntplanet.adhsapp.wear.alert

import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.shared.WatchReminder
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertLogicTest {

    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** 22.11.2026, ein Sonntag (LocalDate.toEpochDay), und sein Beginn in UTC. */
    private val today = 20_779L
    private val dayStart = today * day

    /** Uhrzeit am 22.11.2026 in UTC, in Millisekunden. */
    private fun at(hours: Int, minutes: Int = 0, seconds: Int = 0): Long =
        dayStart + hours * hour + minutes * minute + seconds * 1_000L

    private fun reminder(
        origin: String,
        title: String = "Titel",
        subtitle: String = "Jetzt",
        detail: String? = null,
        canDone: Boolean = false,
        doneLabel: String = "Erledigt",
        canMute: Boolean = false,
        phoneHint: String? = null,
        kind: String = "EVENT",
        targetTime: Long? = null,
        targetEnd: Long? = null,
        allDay: Boolean = false,
    ) = WatchReminder(
        key = origin,
        originKey = origin,
        kind = kind,
        title = title,
        subtitle = subtitle,
        detail = detail,
        targetTime = targetTime,
        targetEnd = targetEnd,
        allDay = allDay,
        canDone = canDone,
        doneLabel = doneLabel,
        canMute = canMute,
        phoneHint = phoneHint,
        payload = "payload:$origin",
    )

    private val snooze = AlertButton(ReminderActions.SNOOZE, "10 Min. später", 10)
    private val ok = AlertButton(ReminderActions.DISMISS, "OK")

    // ---- merge ----

    @Test
    fun newRemindersComeFirst() {
        val a = reminder("a")
        val b = reminder("b")
        val c = reminder("c")
        assertEquals(listOf(c, a, b), AlertLogic.merge(listOf(a, b), listOf(c)))
    }

    @Test
    fun aReminderShownAgainReplacesTheOldOne() {
        val a = reminder("a")
        val b = reminder("b", title = "alt")
        val again = reminder("b", title = "neu")
        assertEquals(listOf(again, a), AlertLogic.merge(listOf(a, b), listOf(again)))
    }

    @Test
    fun duplicatesInOneRequestAreShownOnce() {
        val first = reminder("c", title = "eins")
        val second = reminder("c", title = "zwei")
        assertEquals(listOf(first), AlertLogic.merge(emptyList(), listOf(first, second)))
    }

    @Test
    fun nothingNewKeepsTheListAsItIs() {
        val a = reminder("a")
        assertEquals(listOf(a), AlertLogic.merge(listOf(a), emptyList()))
    }

    // ---- buttons ----

    @Test
    fun plainReminderOffersSnoozeAndOk() {
        assertEquals(listOf(snooze, ok), AlertLogic.buttons(reminder("a")))
    }

    @Test
    fun doneButtonComesFirstWithTheLabelFromThePhone() {
        val buttons = AlertLogic.buttons(reminder("a", canDone = true, doneLabel = "Genommen"))
        assertEquals(listOf(AlertButton(ReminderActions.DONE, "Genommen"), snooze, ok), buttons)
    }

    @Test
    fun emptyDoneLabelFallsBackToErledigt() {
        val buttons = AlertLogic.buttons(reminder("a", canDone = true, doneLabel = " "))
        assertEquals(AlertButton(ReminderActions.DONE, "Erledigt"), buttons.first())
    }

    @Test
    fun muteButtonComesLast() {
        val buttons = AlertLogic.buttons(reminder("a", canDone = true, canMute = true))
        assertEquals(
            listOf(
                AlertButton(ReminderActions.DONE, "Erledigt"),
                snooze,
                ok,
                AlertButton(ReminderActions.MUTE, "Keine weiteren"),
            ),
            buttons,
        )
    }

    // ---- notificationId ----

    @Test
    fun notificationIdIsTheHashOfTheKeyButNeverZero() {
        // "a" = 97; "ab" = 97 × 31 + 98 = 3105; der leere Text hätte 0.
        assertEquals(97, AlertLogic.notificationId("a"))
        assertEquals(3_105, AlertLogic.notificationId("ab"))
        assertEquals(1, AlertLogic.notificationId(""))
    }

    // ---- anchor ----

    @Test
    fun serviceCarriesTheFirstRingingReminder() {
        val active = listOf(reminder("a"), reminder("b"), reminder("c"))
        assertEquals("b", AlertLogic.anchor(active, setOf("b", "c"), current = null)?.originKey)
    }

    @Test
    fun serviceKeepsItsReminderWhileItStillRings() {
        val active = listOf(reminder("a"), reminder("b"), reminder("c"))
        assertEquals("c", AlertLogic.anchor(active, setOf("b", "c"), current = "c")?.originKey)
    }

    @Test
    fun serviceMovesOnWhenItsReminderIsGoneOrQuiet() {
        val active = listOf(reminder("a"), reminder("b"), reminder("c"))
        assertEquals("b", AlertLogic.anchor(active, setOf("b", "c"), current = "a")?.originKey)
        assertEquals("b", AlertLogic.anchor(active, setOf("b", "c"), current = "x")?.originKey)
    }

    @Test
    fun nothingToCarryWhenNothingRings() {
        val active = listOf(reminder("a"))
        assertNull(AlertLogic.anchor(active, emptySet(), current = "a"))
        assertNull(AlertLogic.anchor(active, setOf("z"), current = "z"))
    }

    // ---- style ----

    @Test
    fun quietWhenNothingRingsOrTheReminderIsNotRinging() {
        assertEquals(AlertStyle.QUIET, AlertLogic.style("a", setOf("a"), mode = null, fallbackKey = null))
        assertEquals(AlertStyle.QUIET, AlertLogic.style("a", setOf("b"), RingMode.SERVICE, fallbackKey = null))
        assertEquals(AlertStyle.QUIET, AlertLogic.style("a", emptySet(), RingMode.FALLBACK, fallbackKey = "a"))
    }

    @Test
    fun ringingWhileTheServiceStartsOrRuns() {
        assertEquals(AlertStyle.RINGING, AlertLogic.style("a", setOf("a", "b"), RingMode.STARTING, fallbackKey = null))
        assertEquals(AlertStyle.RINGING, AlertLogic.style("b", setOf("a", "b"), RingMode.SERVICE, fallbackKey = null))
    }

    @Test
    fun inFallbackOnlyOneNotificationVibrates() {
        assertEquals(AlertStyle.INSISTENT, AlertLogic.style("a", setOf("a", "b"), RingMode.FALLBACK, fallbackKey = "a"))
        assertEquals(AlertStyle.QUIET, AlertLogic.style("b", setOf("a", "b"), RingMode.FALLBACK, fallbackKey = "a"))
    }

    @Test
    fun fallbackStaysSilentWhenVibrationAndSoundAreOff() {
        assertEquals(
            AlertStyle.QUIET,
            AlertLogic.style("a", setOf("a", "b"), RingMode.FALLBACK, fallbackKey = "a", signal = false),
        )
        assertEquals(
            AlertStyle.RINGING,
            AlertLogic.style("a", setOf("a"), RingMode.SERVICE, fallbackKey = null, signal = false),
        )
    }

    // ---- text ----

    @Test
    fun textHasOneLinePerPart() {
        val r = reminder("a", subtitle = "Beginnt in 15 Min.", detail = "Raum 2", phoneHint = "Am Handy ausfüllen")
        assertEquals("Beginnt in 15 Min.\nRaum 2\nAm Handy ausfüllen", AlertLogic.text(r, at(9, 45), utc))
    }

    @Test
    fun textSkipsMissingAndEmptyParts() {
        assertEquals("Jetzt", AlertLogic.text(reminder("a", detail = null, phoneHint = "  "), at(9, 45), utc))
        assertEquals("", AlertLogic.text(reminder("a", subtitle = ""), at(9, 45), utc))
    }

    @Test
    fun textStartsWithTheLiveTimeLine() {
        val meeting = reminder(
            "a",
            subtitle = "Beginnt in 15 Min. · 10:00–11:00",
            detail = "Raum 2",
            targetTime = at(10),
            targetEnd = at(11),
        )
        assertEquals("Beginnt in 15 Min. · 10:00–11:00\nRaum 2", AlertLogic.text(meeting, at(9, 45), utc))
        assertEquals("Läuft seit 5 Min. · bis 11:00\nRaum 2", AlertLogic.text(meeting, at(10, 5), utc))
    }

    // ---- timeLine ----

    @Test
    fun withoutTargetTimeTheLineFromThePhoneStays() {
        val r = reminder("a", subtitle = "Termin")
        assertEquals("Termin", AlertLogic.timeLine(r, at(9, 45), utc))
        assertEquals("Termin", AlertLogic.timeLine(r, at(9, 45) + 3 * day, utc))
    }

    @Test
    fun eventLineCountsDownThenRunsThenShowsOnlyTheTime() {
        // Vom Handy um 09:45 Uhr geschickt – auf der Uhr bleibt die Erinnerung stehen.
        val meeting = reminder(
            "a",
            subtitle = "Beginnt in 15 Min. · 10:00–11:00",
            targetTime = at(10),
            targetEnd = at(11),
        )
        assertEquals("Beginnt in 15 Min. · 10:00–11:00", AlertLogic.timeLine(meeting, at(9, 45), utc))
        // 09:58:30 Uhr: noch 90 Sekunden, aufgerundet 2 Minuten.
        assertEquals("Beginnt in 2 Min. · 10:00–11:00", AlertLogic.timeLine(meeting, at(9, 58, 30), utc))
        assertEquals("Beginnt in 1 Min. · 10:00–11:00", AlertLogic.timeLine(meeting, at(9, 59), utc))
        // Eine Minute vor bis eine Minute nach dem Beginn: „jetzt“.
        assertEquals("Beginnt jetzt · 10:00–11:00", AlertLogic.timeLine(meeting, at(9, 59, 30), utc))
        assertEquals("Beginnt jetzt · 10:00–11:00", AlertLogic.timeLine(meeting, at(10, 1), utc))
        assertEquals("Läuft seit 1 Min. · bis 11:00", AlertLogic.timeLine(meeting, at(10, 1, 1), utc))
        assertEquals("Läuft seit 5 Min. · bis 11:00", AlertLogic.timeLine(meeting, at(10, 5), utc))
        assertEquals("Läuft seit 59 Min. · bis 11:00", AlertLogic.timeLine(meeting, at(10, 59, 59), utc))
        // Vorbei: nur noch, wann der Termin war – am nächsten Tag mit Tag.
        assertEquals("10:00–11:00", AlertLogic.timeLine(meeting, at(11), utc))
        assertEquals("10:00–11:00", AlertLogic.timeLine(meeting, at(13, 30), utc))
        assertEquals("Gestern, 10:00–11:00", AlertLogic.timeLine(meeting, at(8) + day, utc))
    }

    @Test
    fun eventLineNamesTheDayWhenItIsNotToday() {
        // Termin morgen 09:00–10:00 Uhr, jetzt heute 09:45 Uhr: noch 23 Stunden 15 Minuten.
        val tomorrow = reminder(
            "a",
            subtitle = "Beginnt in 1 Tag · Morgen, 09:00–10:00",
            targetTime = at(9) + day,
            targetEnd = at(10) + day,
        )
        assertEquals("Beginnt in 23 Std. 15 Min. · Morgen, 09:00–10:00", AlertLogic.timeLine(tomorrow, at(9, 45), utc))
        // Am Tag selbst fällt der Tag weg.
        assertEquals("Beginnt in 30 Min. · 09:00–10:00", AlertLogic.timeLine(tomorrow, at(8, 30) + day, utc))
    }

    @Test
    fun eventLineWithoutEndShowsOnlyTheStart() {
        val noEnd = reminder("a", subtitle = "Beginnt in 15 Min. · 10:00", targetTime = at(10))
        val sameEnd = reminder("b", subtitle = "Beginnt in 15 Min. · 10:00", targetTime = at(10), targetEnd = at(10))
        assertEquals("Beginnt in 15 Min. · 10:00", AlertLogic.timeLine(noEnd, at(9, 45), utc))
        assertEquals("Beginnt in 15 Min. · 10:00", AlertLogic.timeLine(sameEnd, at(9, 45), utc))
        assertEquals("Beginnt jetzt · 10:00", AlertLogic.timeLine(noEnd, at(10), utc))
        // Ohne Ende lässt sich „Läuft“ nicht sagen.
        assertEquals("10:00", AlertLogic.timeLine(noEnd, at(10, 5), utc))
        assertEquals("10:00", AlertLogic.timeLine(sameEnd, at(10, 5), utc))
    }

    @Test
    fun eventLineUsesTheGivenZoneForTimes() {
        // 10:00–11:00 Uhr UTC ist in Berlin (Winterzeit) 11:00–12:00 Uhr.
        val meeting = reminder("a", subtitle = "fest", targetTime = at(10), targetEnd = at(11))
        assertEquals("Beginnt in 15 Min. · 11:00–12:00", AlertLogic.timeLine(meeting, at(9, 45), berlin))
    }

    @Test
    fun allDayEventLineFollowsTheDay() {
        val allDay = reminder(
            "a",
            subtitle = "Morgen · ganztägig",
            targetTime = dayStart,
            targetEnd = dayStart + day,
            allDay = true,
        )
        assertEquals("Morgen · ganztägig", AlertLogic.timeLine(allDay, at(20) - day, utc))
        assertEquals("Heute · ganztägig", AlertLogic.timeLine(allDay, at(9, 45), utc))
        assertEquals("Gestern · ganztägig", AlertLogic.timeLine(allDay, at(0, 5) + day, utc))
        // Weiter weg steht das Datum: Der 22.11.2026 ist ein Sonntag.
        assertEquals("So. 22.11. · ganztägig", AlertLogic.timeLine(allDay, at(9, 45) - 6 * day, utc))
    }

    @Test
    fun allDayEventKeepsThePhoneLineWhenTheZoneDoesNotFit() {
        // Mitternacht in UTC ist in Berlin 01:00 Uhr: Die Uhr rechnet offenbar in einer anderen Zeitzone als das
        // Handy. Dann bleibt die Zeile vom Handy, statt den Tag zu raten.
        val allDay = reminder("a", subtitle = "Heute · ganztägig", targetTime = dayStart, allDay = true)
        assertEquals("Heute · ganztägig", AlertLogic.timeLine(allDay, at(9, 45), berlin))
        assertEquals("Heute · ganztägig", AlertLogic.timeLine(allDay, at(9, 45) + day, berlin))
    }

    @Test
    fun taskLineCountsDownAndThenAsksToDoItNow() {
        val task = reminder("a", kind = "TASK", subtitle = "Jetzt erledigen · fällig heute 14:00", targetTime = at(14))
        assertEquals("Fällig in 4 Std. 15 Min. · 14:00", AlertLogic.timeLine(task, at(9, 45), utc))
        assertEquals("Fällig in 2 Min. · 14:00", AlertLogic.timeLine(task, at(13, 58, 59), utc))
        // Genau eine Minute vorher beginnt wie am Handy schon „Jetzt erledigen“.
        assertEquals("Jetzt erledigen · fällig heute 14:00", AlertLogic.timeLine(task, at(13, 59), utc))
        assertEquals("Jetzt erledigen · fällig heute 14:00", AlertLogic.timeLine(task, at(16, 30), utc))
        assertEquals("Jetzt erledigen · fällig gestern 14:00", AlertLogic.timeLine(task, at(8) + day, utc))
    }

    @Test
    fun allDayTaskLineFollowsTheDay() {
        // Aufgabe ohne Uhrzeit: Das Handy erinnert zur eingestellten Zeit (hier 09:00 Uhr).
        val task = reminder("a", kind = "TASK", subtitle = "Fällig: Heute", targetTime = at(9), allDay = true)
        assertEquals("Fällig: Morgen", AlertLogic.timeLine(task, at(22) - day, utc))
        assertEquals("Fällig: Heute", AlertLogic.timeLine(task, at(9, 45), utc))
        assertEquals("Fällig: Gestern", AlertLogic.timeLine(task, at(7) + day, utc))
    }

    @Test
    fun medicationLineKeepsTheDoseFromThePhone() {
        // Vom Handy um 08:00 Uhr geschickt; die Dosis steht nur in dieser Zeile.
        val pill = reminder("a", kind = "MEDICATION", subtitle = "Jetzt · 08:00 · 1 Tablette", targetTime = at(8))
        assertEquals("Einnahme in 15 Min. · 08:00 · 1 Tablette", AlertLogic.timeLine(pill, at(7, 45), utc))
        assertEquals("Jetzt · 08:00 · 1 Tablette", AlertLogic.timeLine(pill, at(8, 0, 30), utc))
        assertEquals("Jetzt · 08:00 · 1 Tablette", AlertLogic.timeLine(pill, at(8, 1), utc))
        assertEquals("Einnahme war um 08:00 · noch offen · 1 Tablette", AlertLogic.timeLine(pill, at(8, 20), utc))
        assertEquals(
            "Einnahme war um Gestern, 08:00 · noch offen · 1 Tablette",
            AlertLogic.timeLine(pill, at(7) + day, utc),
        )
    }

    @Test
    fun medicationDoseIsFoundInEveryLineThePhoneSends() {
        val expected = "Einnahme war um 08:00 · noch offen · 1 Tablette"
        val lines = listOf(
            "Einnahme in 15 Min. · 08:00 · 1 Tablette",
            "Einnahme in 9 Std. · Morgen, 08:00 · 1 Tablette",
            "Jetzt · 08:00 · 1 Tablette",
            "Einnahme war um 08:00 · noch offen · 1 Tablette",
            "Einnahme war um Gestern, 08:00 · noch offen · 1 Tablette",
        )
        lines.forEach { line ->
            val pill = reminder("a", kind = "MEDICATION", subtitle = line, targetTime = at(8))
            assertEquals(line, expected, AlertLogic.timeLine(pill, at(8, 20), utc))
        }
    }

    @Test
    fun medicationLineWithoutDoseAndWithDispenserNote() {
        val plain = reminder("a", kind = "MEDICATION", subtitle = "Jetzt · 08:00", targetTime = at(8))
        assertEquals("Einnahme war um 08:00 · noch offen", AlertLogic.timeLine(plain, at(8, 20), utc))

        val note = "1 Tablette · Der Spender hat noch keine Entnahme gemeldet."
        val dispenser = reminder(
            "b",
            kind = "MEDICATION",
            subtitle = "Einnahme war um 08:00 · noch offen · $note",
            targetTime = at(8),
        )
        assertEquals("Einnahme war um 08:00 · noch offen · $note", AlertLogic.timeLine(dispenser, at(8, 40), utc))
    }

    @Test
    fun routineLineSaysGeplant() {
        val routine = reminder("a", kind = "ROUTINE", subtitle = "Jetzt · 08:00 · 10 Minuten", targetTime = at(8))
        assertEquals("Geplant in 1 Std. · 08:00 · 10 Minuten", AlertLogic.timeLine(routine, at(7), utc))
        assertEquals("Jetzt · 08:00 · 10 Minuten", AlertLogic.timeLine(routine, at(8), utc))
        assertEquals("Geplant war um 08:00 · noch offen · 10 Minuten", AlertLogic.timeLine(routine, at(9), utc))
    }

    @Test
    fun routineKeepsThePhoneLineWhenTheDoseIsNotCertain() {
        // Die Uhrzeit der Uhr (Berlin: 09:00 Uhr) steht nicht in der Zeile vom Handy (08:00 Uhr): andere Zeitzone.
        val pill = reminder("a", kind = "MEDICATION", subtitle = "Jetzt · 08:00 · 1 Tablette", targetTime = at(8))
        assertEquals("Jetzt · 08:00 · 1 Tablette", AlertLogic.timeLine(pill, at(8, 20), berlin))

        // Unbekannter Aufbau der Zeile (z. B. eine neuere Handy-App): lieber nichts ersetzen.
        val unknown = reminder("b", kind = "MEDICATION", subtitle = "Einnahme um 08:00 fällig", targetTime = at(8))
        assertEquals("Einnahme um 08:00 fällig", AlertLogic.timeLine(unknown, at(8, 20), utc))
    }

    @Test
    fun checkupLineCountsDownAndThenAsks() {
        val checkup = reminder(
            "a",
            kind = "CHECKUP",
            subtitle = "Wie geht es dir gerade? Kurz die Symptome bewerten.",
            targetTime = at(18),
        )
        assertEquals("Checkup in 15 Min. · 18:00", AlertLogic.timeLine(checkup, at(17, 45), utc))
        assertEquals("Wie geht es dir gerade? Kurz die Symptome bewerten.", AlertLogic.timeLine(checkup, at(18), utc))
        assertEquals(
            "Checkup war um 18:00 · noch offen – wie geht es dir?",
            AlertLogic.timeLine(checkup, at(18, 30), utc),
        )
    }

    @Test
    fun birthdayAndUnknownKindsKeepThePhoneLine() {
        // Das Alter kennt nur das Handy – die Zeile bleibt, auch am nächsten Tag.
        val birthday = reminder(
            "a",
            kind = "BIRTHDAY",
            subtitle = "Hat heute Geburtstag und wird 30 🎉",
            targetTime = dayStart,
            allDay = true,
        )
        assertEquals("Hat heute Geburtstag und wird 30 🎉", AlertLogic.timeLine(birthday, at(9, 45), utc))
        assertEquals("Hat heute Geburtstag und wird 30 🎉", AlertLogic.timeLine(birthday, at(9, 45) + day, utc))

        val unknown = reminder("b", kind = "NEU", subtitle = "Zeile vom Handy", targetTime = at(10))
        assertEquals("Zeile vom Handy", AlertLogic.timeLine(unknown, at(9, 45), utc))
    }
}

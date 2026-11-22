package de.lltntplanet.adhsapp.wear.alert

import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.shared.WatchReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertLogicTest {

    private fun reminder(
        origin: String,
        title: String = "Titel",
        subtitle: String = "Jetzt",
        detail: String? = null,
        canDone: Boolean = false,
        doneLabel: String = "Erledigt",
        canMute: Boolean = false,
        phoneHint: String? = null,
    ) = WatchReminder(
        key = origin,
        originKey = origin,
        kind = "EVENT",
        title = title,
        subtitle = subtitle,
        detail = detail,
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
        assertEquals("Beginnt in 15 Min.\nRaum 2\nAm Handy ausfüllen", AlertLogic.text(r))
    }

    @Test
    fun textSkipsMissingAndEmptyParts() {
        assertEquals("Jetzt", AlertLogic.text(reminder("a", detail = null, phoneHint = "  ")))
        assertEquals("", AlertLogic.text(reminder("a", subtitle = "")))
    }
}

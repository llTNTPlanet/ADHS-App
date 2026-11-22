package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderKind
import de.lltntplanet.adhsapp.reminders.ReminderPayload
import de.lltntplanet.adhsapp.reminders.ReminderText
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WATCH_PROTOCOL_VERSION
import de.lltntplanet.adhsapp.shared.WatchJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRemindersTest {

    /** Eine Viertelstunde vor dem Termin. */
    private val now = millis("2026-10-24T13:45")

    private val event = Reminder(
        key = "ev:7:20751:15",
        kind = ReminderKind.EVENT,
        triggerAt = now,
        title = "Zahnarzt",
        detail = "Praxis Dr. Weber",
        targetTime = millis("2026-10-24T14:00"),
        targetEnd = millis("2026-10-24T15:00"),
        refId = 7,
        color = 0xFF0B57D0.toInt(),
    )

    private val medication = Reminder(
        key = "rt:3:20750:480",
        kind = ReminderKind.MEDICATION,
        triggerAt = millis("2026-10-24T08:00"),
        title = "Methylphenidat",
        detail = "1 Tablette",
        targetTime = millis("2026-10-24T08:00"),
        targetEnd = millis("2026-10-24T11:00"),
        refId = 3,
    )

    private val meal = Reminder(key = "meal:7:20751", kind = ReminderKind.MEAL, triggerAt = now, title = "Mittagessen")

    @Test
    fun eventKeepsTextsTimesAndColor() {
        val w = WatchReminders.toWatch(event, now, Berlin)
        assertEquals(event.key, w.key)
        assertEquals(event.key, w.originKey)
        assertEquals("EVENT", w.kind)
        assertEquals("Zahnarzt", w.title)
        assertEquals("Beginnt in 15 Min. · 14:00–15:00", w.subtitle)
        assertEquals(ReminderText.subtitle(event, now, Berlin), w.subtitle)
        assertEquals("Praxis Dr. Weber", w.detail)
        assertEquals(event.targetTime, w.targetTime)
        assertEquals(event.targetEnd, w.targetEnd)
        assertFalse(w.allDay)
        assertEquals(event.color, w.color)
        assertNull(w.phoneHint)
    }

    @Test
    fun eventCanBeMutedButNotDone() {
        val w = WatchReminders.toWatch(event, now, Berlin)
        assertFalse(w.canDone)
        assertTrue(w.canMute)
    }

    @Test
    fun payloadIsTheReminderItself() {
        val w = WatchReminders.toWatch(event, now, Berlin)
        assertEquals(ReminderPayload.encode(event), w.payload)
        assertEquals(event, ReminderPayload.decode(w.payload))
    }

    @Test
    fun medicationGetsTakenButtonAndNoDoubleDose() {
        val at = millis("2026-10-24T08:00")
        val w = WatchReminders.toWatch(medication, at, Berlin)
        assertEquals("MEDICATION", w.kind)
        assertEquals(ReminderText.notificationTitle(medication), w.title)
        assertTrue(w.title.endsWith("Methylphenidat"))
        assertEquals("Jetzt · 08:00 · 1 Tablette", w.subtitle)
        // Die Dosis steht schon in der Zeile darüber.
        assertNull(w.detail)
        assertTrue(w.canDone)
        assertEquals("Genommen", w.doneLabel)
        assertFalse(w.canMute)
        assertEquals(medication.targetTime, w.targetTime)
        // Bei Routinen ist das Ende nur die Nachhol-Frist – nichts zum Anzeigen.
        assertNull(w.targetEnd)
    }

    @Test
    fun doseStaysAsDetailWhenTheSubtitleDoesNotShowIt() {
        val w = WatchReminders.toWatch(medication.copy(targetTime = null), now, Berlin)
        assertEquals("Jetzt nehmen", w.subtitle)
        assertEquals("1 Tablette", w.detail)
    }

    @Test
    fun blankDetailIsDropped() {
        assertNull(WatchReminders.toWatch(event.copy(detail = "  "), now, Berlin).detail)
        assertNull(WatchReminders.toWatch(event.copy(detail = null), now, Berlin).detail)
    }

    @Test
    fun routineAndTaskCanBeDone() {
        val routine = medication.copy(key = "rt:4:20750:480", kind = ReminderKind.ROUTINE, title = "Zähne putzen", detail = null, refId = 4)
        val r = WatchReminders.toWatch(routine, now, Berlin)
        assertEquals("ROUTINE", r.kind)
        assertEquals("Routine: Zähne putzen", r.title)
        assertTrue(r.canDone)
        assertEquals("Erledigt", r.doneLabel)

        val task = Reminder(key = "task:12:20751", kind = ReminderKind.TASK, triggerAt = now, title = "Müll rausbringen", refId = 12)
        val t = WatchReminders.toWatch(task, now, Berlin)
        assertEquals("TASK", t.kind)
        assertEquals("Aufgabe: Müll rausbringen", t.title)
        assertEquals("Jetzt erledigen", t.subtitle)
        assertTrue(t.canDone)
        assertEquals("Erledigt", t.doneLabel)
        assertFalse(t.canMute)
    }

    @Test
    fun withoutReferenceThereIsNoDoneButton() {
        val task = Reminder(key = "task:0:20751", kind = ReminderKind.TASK, triggerAt = now, title = "Ohne Bezug")
        assertFalse(WatchReminders.toWatch(task, now, Berlin).canDone)
    }

    @Test
    fun checkupPointsToThePhone() {
        val checkup = Reminder(key = "rt:9:20750:1200", kind = ReminderKind.CHECKUP, triggerAt = now, title = "Abend-Checkup", refId = 9)
        val w = WatchReminders.toWatch(checkup, now, Berlin)
        assertEquals("CHECKUP", w.kind)
        assertEquals(ReminderText.notificationTitle(checkup), w.title)
        assertEquals(ReminderText.subtitle(checkup, now, Berlin), w.subtitle)
        assertFalse(w.canDone)
        assertEquals("Am Handy ausfüllen", w.phoneHint)
    }

    @Test
    fun birthdayIsAllDayWithoutButtons() {
        val birthday = Reminder(
            key = "bd:4:2026-10-24:0",
            kind = ReminderKind.BIRTHDAY,
            triggerAt = millis("2026-10-24T09:00"),
            title = "Oma",
            targetTime = millis("2026-10-24T00:00"),
            targetEnd = millis("2026-10-25T00:00"),
            allDay = true,
            age = 80,
            daysBefore = 0,
        )
        val w = WatchReminders.toWatch(birthday, now, Berlin)
        assertEquals("BIRTHDAY", w.kind)
        assertEquals(ReminderText.notificationTitle(birthday), w.title)
        assertEquals(ReminderText.subtitle(birthday, now, Berlin), w.subtitle)
        assertTrue(w.subtitle.startsWith("Hat heute Geburtstag und wird 80"))
        assertTrue(w.allDay)
        assertNull(w.targetEnd)
        assertFalse(w.canDone)
        assertFalse(w.canMute)
    }

    @Test
    fun snoozedReminderKeepsItsOriginKey() {
        val snoozed = event.copy(key = "snooze:${event.key}", snoozed = true, originKey = event.key)
        val w = WatchReminders.toWatch(snoozed, now, Berlin)
        assertEquals("snooze:ev:7:20751:15", w.key)
        assertEquals("ev:7:20751:15", w.originKey)
        assertTrue(w.canMute)
    }

    @Test
    fun testReminderCannotBeMuted() {
        val test = event.copy(key = "test:1792848300000", title = "Test-Erinnerung", refId = null)
        assertFalse(WatchReminders.toWatch(test, now, Berlin).canMute)
        assertFalse(WatchReminders.toWatch(event.copy(key = "test:1792848300000"), now, Berlin).canMute)
    }

    @Test
    fun ringRequestLeavesQuietRemindersOnThePhone() {
        val request = WatchReminders.ringRequest(listOf(event, meal, medication), AppSettings(), requestId = "abc", now = now, zone = Berlin)
        assertEquals(listOf(event.key, medication.key), request.reminders.map { it.key })
        assertEquals(WatchReminders.toWatch(event, now, Berlin), request.reminders.first())
        assertTrue(WatchReminders.ringRequest(listOf(meal), AppSettings(), now = now, zone = Berlin).reminders.isEmpty())
    }

    @Test
    fun ringRequestTakesRingingFromSettings() {
        val settings = AppSettings(ringTarget = RingTarget.WATCH_IF_WORN, vibrate = false, watchSound = true, ringSeconds = 30)
        val request = WatchReminders.ringRequest(listOf(event), settings, requestId = "abc", now = now, zone = Berlin)
        assertEquals("abc", request.requestId)
        assertEquals(now, request.sentAt)
        assertTrue(request.onlyIfWorn)
        assertFalse(request.vibrate)
        assertTrue(request.sound)
        assertEquals(30, request.ringSeconds)
        assertEquals(WATCH_PROTOCOL_VERSION, request.version)
    }

    @Test
    fun onlyWornTargetAsksForWornCheck() {
        val expected = mapOf(
            RingTarget.PHONE to false,
            RingTarget.WATCH to false,
            RingTarget.BOTH to false,
            RingTarget.WATCH_IF_WORN to true,
        )
        for ((target, onlyIfWorn) in expected) {
            val request = WatchReminders.ringRequest(listOf(event), AppSettings(ringTarget = target), now = now, zone = Berlin)
            assertEquals(target.name, onlyIfWorn, request.onlyIfWorn)
        }
        assertEquals(RingTarget.entries.toSet(), expected.keys)
    }

    @Test
    fun defaultSettingsRingWithVibrationAndWithoutSound() {
        val request = WatchReminders.ringRequest(listOf(event), AppSettings(), now = now, zone = Berlin)
        assertFalse(request.onlyIfWorn)
        assertTrue(request.vibrate)
        assertFalse(request.sound)
        assertEquals(60, request.ringSeconds)
    }

    @Test
    fun everyRingRequestGetsItsOwnId() {
        val first = WatchReminders.ringRequest(listOf(event), AppSettings(), now = now, zone = Berlin)
        val second = WatchReminders.ringRequest(listOf(event), AppSettings(), now = now, zone = Berlin)
        assertTrue(first.requestId.isNotBlank())
        assertNotEquals(first.requestId, second.requestId)
    }

    @Test
    fun ringRequestSurvivesTheWayToTheWatch() {
        val request = WatchReminders.ringRequest(listOf(event, medication), AppSettings(), requestId = "abc", now = now, zone = Berlin)
        val bytes = WatchJson.encodeToString(request).encodeToByteArray()
        assertEquals(request, WatchJson.decodeFromString<RingRequest>(bytes.decodeToString()))
    }
}

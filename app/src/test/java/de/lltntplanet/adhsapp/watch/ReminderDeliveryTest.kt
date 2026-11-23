package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderKind
import de.lltntplanet.adhsapp.shared.RingAck
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderDeliveryTest {

    /** Ein Aufruf „am Handy anzeigen“. */
    private data class Shown(val due: List<Reminder>, val ring: Boolean, val localOnly: Boolean)

    /** Was bei einer Zustellung passiert ist: Anfragen an die Uhr (mit Wartezeit) und Anzeigen am Handy. */
    private class Outcome(val asked: List<Pair<RingRequest, Long>>, val shown: List<Shown>)

    private val now = millis("2026-10-24T13:45")

    private val event = Reminder(
        key = "ev:7:20751:15",
        kind = ReminderKind.EVENT,
        triggerAt = now,
        title = "Zahnarzt",
        targetTime = millis("2026-10-24T14:00"),
        refId = 7,
    )

    private val meal = Reminder(key = "meal:7:20751", kind = ReminderKind.MEAL, triggerAt = now, title = "Mittagessen")

    private val watchTargets = listOf(RingTarget.WATCH, RingTarget.BOTH, RingTarget.WATCH_IF_WORN)

    /** Stellt [due] mit dem Klingel-Ziel [target] zu; [watch] spielt die Uhr. */
    private suspend fun deliver(
        target: RingTarget,
        due: List<Reminder> = listOf(event),
        watch: suspend (RingRequest, Long) -> RingAck?,
    ): Outcome {
        val asked = mutableListOf<Pair<RingRequest, Long>>()
        val shown = mutableListOf<Shown>()
        val delivery = ReminderDelivery(
            ringWatch = { request, ackTimeoutMillis ->
                asked += request to ackTimeoutMillis
                watch(request, ackTimeoutMillis)
            },
            showOnPhone = { reminders, _, ring, localOnly -> shown += Shown(reminders, ring, localOnly) },
        )
        delivery.deliver(due, AppSettings(ringTarget = target))
        return Outcome(asked, shown)
    }

    private fun confirmed(request: RingRequest) = RingAck(requestId = request.requestId, shown = true)

    // --- Entscheidung für das Handy (reine Logik) ---

    @Test
    fun phoneOnlyAlwaysRingsAndNeverMirrors() {
        for (outcome in WatchOutcome.entries) {
            assertEquals(outcome.name, PhonePlan(ring = true, localOnly = true), ReminderDelivery.phonePlan(RingTarget.PHONE, outcome))
        }
    }

    @Test
    fun bothAlwaysRingsOnThePhone() {
        val plans = WatchOutcome.entries.associateWith { ReminderDelivery.phonePlan(RingTarget.BOTH, it) }
        assertEquals(
            mapOf(
                WatchOutcome.NOT_REQUESTED to PhonePlan(ring = true, localOnly = false),
                WatchOutcome.SHOWN to PhonePlan(ring = true, localOnly = true),
                WatchOutcome.SHOWN_UNSURE to PhonePlan(ring = true, localOnly = true),
                WatchOutcome.NOT_WORN to PhonePlan(ring = true, localOnly = false),
                WatchOutcome.UNREACHABLE to PhonePlan(ring = true, localOnly = false),
            ),
            plans,
        )
    }

    @Test
    fun watchTargetsKeepThePhoneSilentOnlyWhenTheWatchConfirmed() {
        for (target in listOf(RingTarget.WATCH, RingTarget.WATCH_IF_WORN)) {
            val plans = WatchOutcome.entries.associateWith { ReminderDelivery.phonePlan(target, it) }
            assertEquals(
                target.name,
                mapOf(
                    WatchOutcome.NOT_REQUESTED to PhonePlan(ring = true, localOnly = false),
                    WatchOutcome.SHOWN to PhonePlan(ring = false, localOnly = true),
                    WatchOutcome.SHOWN_UNSURE to PhonePlan(ring = true, localOnly = true),
                    WatchOutcome.NOT_WORN to PhonePlan(ring = true, localOnly = false),
                    WatchOutcome.UNREACHABLE to PhonePlan(ring = true, localOnly = false),
                ),
                plans,
            )
        }
    }

    @Test
    fun withoutConfirmationThePhoneRingsForEveryTarget() {
        for (target in RingTarget.entries) {
            for (outcome in WatchOutcome.entries) {
                if (outcome == WatchOutcome.SHOWN) continue
                assertTrue("$target/$outcome", ReminderDelivery.phonePlan(target, outcome).ring)
            }
        }
    }

    @Test
    fun watchIsOnlyAskedForLoudRemindersAndNeverForPhoneOnly() {
        assertFalse(ReminderDelivery.asksWatch(RingTarget.PHONE, listOf(event)))
        assertFalse(ReminderDelivery.asksWatch(RingTarget.PHONE, listOf(event, meal)))
        for (target in watchTargets) {
            assertTrue(target.name, ReminderDelivery.asksWatch(target, listOf(event)))
            assertTrue(target.name, ReminderDelivery.asksWatch(target, listOf(meal, event)))
            assertFalse(target.name, ReminderDelivery.asksWatch(target, listOf(meal)))
            assertFalse(target.name, ReminderDelivery.asksWatch(target, emptyList()))
        }
    }

    // --- Ablauf ---

    @Test
    fun nothingDueDoesNothing() = runTest {
        val result = deliver(RingTarget.BOTH, due = emptyList()) { request, _ -> confirmed(request) }
        assertTrue(result.asked.isEmpty())
        assertTrue(result.shown.isEmpty())
    }

    @Test
    fun phoneOnlyNeverAsksTheWatch() = runTest {
        val result = deliver(RingTarget.PHONE) { request, _ -> confirmed(request) }
        assertTrue(result.asked.isEmpty())
        assertEquals(listOf(Shown(listOf(event), ring = true, localOnly = true)), result.shown)
    }

    @Test
    fun quietRemindersAloneNeverAskTheWatch() = runTest {
        for (target in watchTargets) {
            val result = deliver(target, due = listOf(meal)) { request, _ -> confirmed(request) }
            assertTrue(target.name, result.asked.isEmpty())
            assertEquals(target.name, listOf(Shown(listOf(meal), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun confirmedByWatchKeepsThePhoneSilent() = runTest {
        val result = deliver(RingTarget.WATCH) { request, _ -> confirmed(request) }
        assertEquals(1, result.asked.size)
        assertEquals(listOf(Shown(listOf(event), ring = false, localOnly = true)), result.shown)
    }

    @Test
    fun bothRingsOnThePhoneEvenWhenTheWatchConfirmed() = runTest {
        val result = deliver(RingTarget.BOTH) { request, _ -> confirmed(request) }
        assertEquals(1, result.asked.size)
        assertEquals(listOf(Shown(listOf(event), ring = true, localOnly = true)), result.shown)
    }

    @Test
    fun watchGetsOnlyLoudRemindersButThePhoneShowsAll() = runTest {
        val due = listOf(meal, event)
        val result = deliver(RingTarget.WATCH, due = due) { request, _ -> confirmed(request) }
        assertEquals(listOf(event.key), result.asked.single().first.reminders.map { it.key })
        assertEquals(listOf(Shown(due, ring = false, localOnly = true)), result.shown)
    }

    @Test
    fun waitsFourSecondsAndFiveWithWornCheck() = runTest {
        val expected = mapOf(RingTarget.WATCH to 4_000L, RingTarget.BOTH to 4_000L, RingTarget.WATCH_IF_WORN to 5_000L)
        for ((target, expectedMillis) in expected) {
            val (request, ackTimeoutMillis) = deliver(target) { r, _ -> confirmed(r) }.asked.single()
            assertEquals(target.name, expectedMillis, ackTimeoutMillis)
            assertEquals(target.name, target == RingTarget.WATCH_IF_WORN, request.onlyIfWorn)
        }
    }

    @Test
    fun notWornWatchMakesThePhoneRing() = runTest {
        val result = deliver(RingTarget.WATCH_IF_WORN) { request, _ ->
            RingAck(requestId = request.requestId, shown = false, displayed = false, worn = false)
        }
        assertEquals(listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
    }

    @Test
    fun unsureWatchShowsButThePhoneRingsToo() = runTest {
        val result = deliver(RingTarget.WATCH_IF_WORN) { request, _ ->
            RingAck(requestId = request.requestId, shown = false, displayed = true, worn = null)
        }
        // Die Uhr zeigt selbst an: Die Handy-Benachrichtigung wird nicht zusätzlich gespiegelt.
        assertEquals(listOf(Shown(listOf(event), ring = true, localOnly = true)), result.shown)
    }

    @Test
    fun watchThatCannotShowMakesThePhoneRing() = runTest {
        for (target in watchTargets) {
            val result = deliver(target) { request, _ -> RingAck(requestId = request.requestId, shown = false, displayed = false) }
            assertEquals(target.name, listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun noAnswerMakesThePhoneRing() = runTest {
        for (target in watchTargets) {
            val result = deliver(target) { _, _ -> null }
            assertEquals(target.name, 1, result.asked.size)
            assertEquals(target.name, listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun errorOnTheWayToTheWatchMakesThePhoneRing() = runTest {
        for (target in watchTargets) {
            val result = deliver(target) { _, _ -> throw IllegalStateException("Uhr nicht erreichbar") }
            assertEquals(target.name, listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun cancelledWatchRequestMakesThePhoneRing() = runTest {
        for (target in watchTargets) {
            val result = deliver(target) { _, _ -> throw CancellationException("Anfrage abgebrochen") }
            assertEquals(target.name, listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun hangingWatchMakesThePhoneRingAfterTheTimeout() = runTest {
        for (target in watchTargets) {
            val result = deliver(target) { _, _ -> awaitCancellation() }
            assertEquals(target.name, listOf(Shown(listOf(event), ring = true, localOnly = false)), result.shown)
        }
    }

    @Test
    fun cancelledAlarmStillShowsOnThePhone() = runTest {
        val shown = mutableListOf<Shown>()
        val waiting = CompletableDeferred<Unit>()
        val delivery = ReminderDelivery(
            ringWatch = { _, _ ->
                waiting.complete(Unit)
                awaitCancellation()
            },
            showOnPhone = { reminders, _, ring, localOnly -> shown += Shown(reminders, ring, localOnly) },
        )
        val job = launch { delivery.deliver(listOf(event), AppSettings(ringTarget = RingTarget.WATCH)) }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(listOf(Shown(listOf(event), ring = true, localOnly = false)), shown)
    }
}

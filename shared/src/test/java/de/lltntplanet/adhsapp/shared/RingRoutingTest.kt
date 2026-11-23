package de.lltntplanet.adhsapp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingRoutingTest {

    @Test
    fun phoneOnlyNeverAsksTheWatch() {
        assertFalse(RingRouting.asksWatch(RingTarget.PHONE))
        assertTrue(RingRouting.phoneRings(RingTarget.PHONE, WatchOutcome.NOT_REQUESTED))
        RingTarget.entries.filter { it != RingTarget.PHONE }.forEach { assertTrue(it.name, RingRouting.asksWatch(it)) }
    }

    @Test
    fun bothRingsOnThePhoneWhateverTheWatchSays() {
        assertFalse(RingRouting.onlyIfWorn(RingTarget.BOTH))
        WatchOutcome.entries.forEach { assertTrue(it.name, RingRouting.phoneRings(RingTarget.BOTH, it)) }
    }

    @Test
    fun watchOnlyKeepsThePhoneQuietOnlyWhenTheWatchConfirmed() {
        assertFalse(RingRouting.onlyIfWorn(RingTarget.WATCH))
        assertFalse(RingRouting.phoneRings(RingTarget.WATCH, WatchOutcome.SHOWN))
        // Nie eine Erinnerung verlieren: ohne Bestätigung der Uhr klingelt das Handy.
        listOf(WatchOutcome.UNREACHABLE, WatchOutcome.NOT_WORN, WatchOutcome.SHOWN_UNSURE, WatchOutcome.NOT_REQUESTED).forEach {
            assertTrue(it.name, RingRouting.phoneRings(RingTarget.WATCH, it))
        }
    }

    @Test
    fun wornModeFallsBackToThePhone() {
        assertTrue(RingRouting.onlyIfWorn(RingTarget.WATCH_IF_WORN))
        assertTrue(RingRouting.ackTimeoutMillis(RingTarget.WATCH_IF_WORN) > RingRouting.ackTimeoutMillis(RingTarget.WATCH))
        assertFalse(RingRouting.phoneRings(RingTarget.WATCH_IF_WORN, WatchOutcome.SHOWN))
        assertTrue(RingRouting.phoneRings(RingTarget.WATCH_IF_WORN, WatchOutcome.NOT_WORN))
        assertTrue(RingRouting.phoneRings(RingTarget.WATCH_IF_WORN, WatchOutcome.UNREACHABLE))
        assertTrue(RingRouting.phoneRings(RingTarget.WATCH_IF_WORN, WatchOutcome.SHOWN_UNSURE))
    }

    @Test
    fun watchDecidesByWornState() {
        assertTrue(RingRouting.watchShouldShow(onlyIfWorn = false, worn = false))
        assertTrue(RingRouting.watchShouldShow(onlyIfWorn = false, worn = null))
        assertTrue(RingRouting.watchShouldShow(onlyIfWorn = true, worn = true))
        assertFalse(RingRouting.watchShouldShow(onlyIfWorn = true, worn = false))
        // Unbekannt: anzeigen – das Handy klingelt dann zusätzlich.
        assertTrue(RingRouting.watchShouldShow(onlyIfWorn = true, worn = null))
    }

    @Test
    fun ackAndOutcomeFitTogether() {
        fun outcome(onlyIfWorn: Boolean, worn: Boolean?, displayed: Boolean) =
            RingRouting.outcome(RingRouting.ackFor("r1", onlyIfWorn, worn, displayed))

        assertEquals(WatchOutcome.SHOWN, outcome(onlyIfWorn = false, worn = null, displayed = true))
        assertEquals(WatchOutcome.SHOWN, outcome(onlyIfWorn = false, worn = false, displayed = true))
        assertEquals(WatchOutcome.SHOWN, outcome(onlyIfWorn = true, worn = true, displayed = true))
        assertEquals(WatchOutcome.NOT_WORN, outcome(onlyIfWorn = true, worn = false, displayed = false))
        assertEquals(WatchOutcome.SHOWN_UNSURE, outcome(onlyIfWorn = true, worn = null, displayed = true))
        // Die Uhr konnte nichts anzeigen (z. B. Benachrichtigungen aus): wie nicht erreichbar.
        assertEquals(WatchOutcome.UNREACHABLE, outcome(onlyIfWorn = false, worn = true, displayed = false))
        assertEquals(WatchOutcome.UNREACHABLE, RingRouting.outcome(null))

        assertTrue(RingRouting.watchShows(WatchOutcome.SHOWN))
        assertTrue(RingRouting.watchShows(WatchOutcome.SHOWN_UNSURE))
        assertFalse(RingRouting.watchShows(WatchOutcome.NOT_WORN))
        assertFalse(RingRouting.watchShows(WatchOutcome.UNREACHABLE))
    }

    @Test
    fun unknownTargetNameFallsBackToDefault() {
        assertEquals(RingTarget.DEFAULT, RingTarget.fromName(null))
        assertEquals(RingTarget.DEFAULT, RingTarget.fromName("SOMETHING_NEW"))
        assertEquals(RingTarget.WATCH_IF_WORN, RingTarget.fromName("WATCH_IF_WORN"))
    }
}

package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann die Übersicht neu an die Uhr geht – die Entscheidung ist reine Logik. */
class WatchSyncTest {

    private val lastPut = millis("2026-11-10T13:45")

    @Test
    fun forcedSyncAlwaysWrites() {
        assertTrue(WatchSync.needsPut(force = true, hash = "a", lastHash = "a", now = lastPut, lastPutAt = lastPut))
    }

    @Test
    fun changedContentIsWritten() {
        assertTrue(WatchSync.needsPut(force = false, hash = "b", lastHash = "a", now = lastPut + 1, lastPutAt = lastPut))
    }

    @Test
    fun firstSnapshotIsWritten() {
        assertTrue(WatchSync.needsPut(force = false, hash = "a", lastHash = null, now = lastPut, lastPutAt = 0))
    }

    @Test
    fun unchangedContentIsNotWrittenAgainSoon() {
        assertFalse(WatchSync.needsPut(force = false, hash = "a", lastHash = "a", now = lastPut, lastPutAt = lastPut))
        val justBefore = lastPut + WatchSync.REFRESH_MILLIS - 1
        assertFalse(WatchSync.needsPut(force = false, hash = "a", lastHash = "a", now = justBefore, lastPutAt = lastPut))
    }

    @Test
    fun unchangedContentIsRefreshedAfterAnHour() {
        assertEquals(60L * 60 * 1000, WatchSync.REFRESH_MILLIS)
        val due = lastPut + WatchSync.REFRESH_MILLIS
        assertTrue(WatchSync.needsPut(force = false, hash = "a", lastHash = "a", now = due, lastPutAt = lastPut))
        // Zwei verpasste Auffrischungen dürfen noch dazwischenliegen, bevor die Uhr „veraltet“ anzeigt.
        assertTrue(WatchSync.REFRESH_MILLIS * 2 < WatchSnapshotLogic.STALE_MILLIS)
    }

    @Test
    fun clockSetBackCountsAsDue() {
        assertTrue(WatchSync.needsPut(force = false, hash = "a", lastHash = "a", now = lastPut - 1, lastPutAt = lastPut))
    }
}

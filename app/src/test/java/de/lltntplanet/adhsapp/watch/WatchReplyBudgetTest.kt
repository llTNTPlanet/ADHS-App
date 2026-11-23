package de.lltntplanet.adhsapp.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Antwort an die Uhr muss fertig sein, bevor die Uhr aufgibt – die Rechnung dazu ist reine Logik. */
class WatchReplyBudgetTest {

    /** So lange wartet die Uhr-App auf die Antwort des Handys. */
    private val watchWaitsMillis = 5_000L

    @Test
    fun fastActionLeavesTheFullWaitForTheOverview() {
        assertEquals(1_500L, WatchReplyBudget.overviewWaitMillis(0))
        assertEquals(1_500L, WatchReplyBudget.overviewWaitMillis(200))
        assertEquals(1_500L, WatchReplyBudget.overviewWaitMillis(2_500))
    }

    @Test
    fun slowActionLeavesOnlyTheRest() {
        assertEquals(1_000L, WatchReplyBudget.overviewWaitMillis(3_000))
        assertEquals(500L, WatchReplyBudget.overviewWaitMillis(3_500))
        assertEquals(0L, WatchReplyBudget.overviewWaitMillis(4_000))
        assertEquals(0L, WatchReplyBudget.overviewWaitMillis(9_000))
    }

    @Test
    fun oddClockNeverGivesMoreThanTheUsualWait() {
        assertEquals(WatchReplyBudget.OVERVIEW_WAIT_MILLIS, WatchReplyBudget.overviewWaitMillis(-50))
    }

    @Test
    fun coldStartCountsTowardsTheBudget() {
        // Der Prozess wurde eben erst gestartet – für diese Anfrage: So lange wartet die Uhr schon.
        assertEquals(0L, WatchReplyBudget.headStartMillis(0))
        assertEquals(800L, WatchReplyBudget.headStartMillis(800))
        assertEquals(3_999L, WatchReplyBudget.headStartMillis(3_999))
        // Die App läuft schon länger: Es zählt nichts mit.
        assertEquals(0L, WatchReplyBudget.headStartMillis(4_000))
        assertEquals(0L, WatchReplyBudget.headStartMillis(3_600_000))
        assertEquals(0L, WatchReplyBudget.headStartMillis(-5))
        // Kalter Start (0,8 s) und eine Aktion von 2 s: Für die Übersicht bleibt nur der Rest.
        assertEquals(1_200L, WatchReplyBudget.overviewWaitMillis(WatchReplyBudget.headStartMillis(800) + 2_000))
        // Ohne kalten Start bliebe bei derselben Aktion die volle Wartezeit.
        assertEquals(1_500L, WatchReplyBudget.overviewWaitMillis(WatchReplyBudget.headStartMillis(60_000) + 2_000))
    }

    @Test
    fun replyIsReadyBeforeTheWatchGivesUp() {
        for (elapsed in 0L..WatchReplyBudget.ACTION_WAIT_MILLIS step 100L) {
            val total = elapsed + WatchReplyBudget.overviewWaitMillis(elapsed)
            assertTrue("nach $elapsed ms", total <= WatchReplyBudget.TOTAL_MILLIS)
        }
        assertTrue(WatchReplyBudget.ACTION_WAIT_MILLIS < WatchReplyBudget.TOTAL_MILLIS)
        assertTrue(WatchReplyBudget.TOTAL_MILLIS < watchWaitsMillis)
        // Auch hinter einer Bitte um die Übersicht kommt das Ergebnis der Aktion noch rechtzeitig.
        assertTrue(WatchReplyBudget.SYNC_REQUEST_WAIT_MILLIS + WatchReplyBudget.ACTION_WAIT_MILLIS < watchWaitsMillis)
    }
}

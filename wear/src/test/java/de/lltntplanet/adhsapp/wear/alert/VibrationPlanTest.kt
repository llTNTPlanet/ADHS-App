package de.lltntplanet.adhsapp.wear.alert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationPlanTest {

    @Test
    fun burstAndCycleLengths() {
        // 500 + 250 + 500 + 250 + 800 = 2300; mit 1500 Pause dauert eine Runde 3800.
        assertEquals(2_300L, VibrationPlan.burstMillis)
        assertEquals(3_800L, VibrationPlan.cycleMillis)
    }

    @Test
    fun burstStartsAtOnceAndAlternatesPauseAndVibration() {
        val burst = VibrationPlan.burst()
        assertEquals(0L, burst.first())
        assertEquals(0, burst.size % 2)
    }

    @Test
    fun channelPatternIsTheBurstFollowedByThePause() {
        assertArrayEquals(longArrayOf(0, 500, 250, 500, 250, 800, 1_500), VibrationPlan.channelPattern())
    }

    @Test
    fun ringTimeIsLimitedToASensibleRange() {
        assertEquals(60_000L, VibrationPlan.ringMillis(60))
        assertEquals(5_000L, VibrationPlan.ringMillis(5))
        assertEquals(5_000L, VibrationPlan.ringMillis(0))
        assertEquals(5_000L, VibrationPlan.ringMillis(-3))
        assertEquals(600_000L, VibrationPlan.ringMillis(600))
        assertEquals(600_000L, VibrationPlan.ringMillis(100_000))
    }

    @Test
    fun remainingTimeIsNeverNegative() {
        assertEquals(1_000L, VibrationPlan.remaining(now = 4_000, until = 5_000))
        assertEquals(0L, VibrationPlan.remaining(now = 5_000, until = 5_000))
        assertEquals(0L, VibrationPlan.remaining(now = 9_000, until = 5_000))
    }

    @Test
    fun noNewBurstInTheLastHalfSecond() {
        assertTrue(VibrationPlan.shouldBurst(now = 0, until = 60_000))
        assertTrue(VibrationPlan.shouldBurst(now = 59_500, until = 60_000))
        assertFalse(VibrationPlan.shouldBurst(now = 59_501, until = 60_000))
        assertFalse(VibrationPlan.shouldBurst(now = 60_000, until = 60_000))
        assertFalse(VibrationPlan.shouldBurst(now = 61_000, until = 60_000))
    }

    @Test
    fun oneMinuteOfRingingIsSixteenBursts() {
        // Stöße bei 0, 3800, …, 57000 (15 × 3800); der nächste läge bei 60800 und damit nach dem Ende.
        var now = 0L
        var bursts = 0
        while (VibrationPlan.shouldBurst(now, 60_000)) {
            bursts++
            now += VibrationPlan.cycleMillis
        }
        assertEquals(16, bursts)
    }
}

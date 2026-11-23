package de.lltntplanet.adhsapp.wear.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WornLogicTest {

    @Test
    fun sensorReportsOneForOnBodyAndZeroForOffBody() {
        assertTrue(WornLogic.fromSensor(1f))
        assertTrue(WornLogic.fromSensor(0.5f))
        assertFalse(WornLogic.fromSensor(0.49f))
        assertFalse(WornLogic.fromSensor(0f))
    }

    @Test
    fun chargingWatchIsNeverWorn() {
        // Auch wenn der Sensor „am Körper“ meldet: Auf dem Ladegerät trägt sie niemand.
        assertEquals(false, WornLogic.decide(charging = true, sensorValue = 1f, secureAndLocked = false))
        assertEquals(false, WornLogic.decide(charging = true, sensorValue = null, secureAndLocked = false))
    }

    @Test
    fun sensorDecidesWhenItAnswers() {
        assertEquals(true, WornLogic.decide(charging = false, sensorValue = 1f, secureAndLocked = false))
        assertEquals(false, WornLogic.decide(charging = false, sensorValue = 0f, secureAndLocked = false))
        // Der Sensor zählt mehr als die Sperre.
        assertEquals(true, WornLogic.decide(charging = false, sensorValue = 1f, secureAndLocked = true))
    }

    @Test
    fun withoutSensorALockedSecureWatchIsOffBody() {
        assertEquals(false, WornLogic.decide(charging = false, sensorValue = null, secureAndLocked = true))
    }

    @Test
    fun withoutSensorAndWithoutLockTheStateIsUnknown() {
        assertNull(WornLogic.decide(charging = false, sensorValue = null, secureAndLocked = false))
    }
}

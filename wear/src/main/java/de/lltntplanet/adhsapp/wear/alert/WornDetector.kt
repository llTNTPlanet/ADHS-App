package de.lltntplanet.adhsapp.wear.alert

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reine Entscheidung „wird die Uhr getragen?“ – ohne Android, damit sie sich testen lässt. */
object WornLogic {

    /** Der Sensor meldet 1 (am Körper) oder 0 (abgelegt). */
    fun fromSensor(value: Float): Boolean = value >= 0.5f

    /**
     * `true` = getragen, `false` = sicher abgelegt, `null` = lässt sich nicht feststellen.
     *
     * - Lädt die Uhr, liegt sie auf dem Ladegerät.
     * - Sonst zählt der Sensor ([sensorValue], `null` = keine Antwort in der Wartezeit).
     * - Ohne Sensorwert: Eine gesicherte Uhr sperrt sich, sobald sie abgelegt wird.
     */
    fun decide(charging: Boolean, sensorValue: Float?, secureAndLocked: Boolean): Boolean? = when {
        charging -> false
        sensorValue != null -> fromSensor(sensorValue)
        secureAndLocked -> false
        else -> null
    }
}

/**
 * Stellt fest, ob die Uhr gerade getragen wird. Der Sensor dafür braucht keine Berechtigung, ist aber nicht
 * auf jeder Uhr vorhanden.
 */
class WornDetector(private val context: Context) {

    /** Hat die Uhr einen Sensor, der „am Körper / abgelegt“ meldet? */
    val hasSensor: Boolean
        get() = runCatching {
            context.getSystemService(SensorManager::class.java)?.let { sensor(it) } != null
        }.getOrDefault(false)

    /**
     * Wird die Uhr getragen? Wartet höchstens [timeoutMillis] auf den Sensor – deshalb nie auf dem
     * Haupt-Thread aufrufen. `null` = unbekannt.
     */
    fun isWorn(timeoutMillis: Long = SENSOR_TIMEOUT_MILLIS): Boolean? {
        if (isCharging()) return WornLogic.decide(charging = true, sensorValue = null, secureAndLocked = false)
        val value = readSensor(timeoutMillis)
        return WornLogic.decide(charging = false, sensorValue = value, secureAndLocked = value == null && isSecureAndLocked())
    }

    private fun sensor(manager: SensorManager): Sensor? =
        // getDefaultSensor liefert nur die „Aufweck“-Variante; manche Uhren haben nur die andere.
        manager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
            ?: manager.getSensorList(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT).firstOrNull()

    /** Liest den Sensor einmal. Der erste Wert ist der aktuelle Zustand, auch wenn er schon älter ist. */
    private fun readSensor(timeoutMillis: Long): Float? {
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = runCatching { sensor(manager) }.getOrNull() ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Float?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val value = event?.values?.firstOrNull() ?: return
                result.compareAndSet(null, value)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // Eigener Thread für die Antwort: Der Aufrufer wartet, und der Haupt-Thread kann gerade beschäftigt sein.
        val thread = HandlerThread("adhs-worn").apply { start() }
        return try {
            val registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
            if (registered && latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Trage-Sensor konnte nicht gelesen werden", e)
            null
        } finally {
            runCatching { manager.unregisterListener(listener) }
            thread.quitSafely()
        }
    }

    private fun isCharging(): Boolean = runCatching {
        val battery = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        plugged != 0 || context.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    private fun isSecureAndLocked(): Boolean = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        keyguard != null && keyguard.isDeviceSecure && keyguard.isDeviceLocked
    }.getOrDefault(false)

    companion object {
        private const val TAG = "WornDetector"

        /** Der Sensor darf laut Android bis zu 5 s brauchen; das Handy wartet aber nur etwa so lange. */
        const val SENSOR_TIMEOUT_MILLIS = 2_000L
    }
}

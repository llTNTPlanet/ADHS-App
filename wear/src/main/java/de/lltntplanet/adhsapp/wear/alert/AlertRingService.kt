package de.lltntplanet.adhsapp.wear.alert

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import de.lltntplanet.adhsapp.wear.WearApp

/**
 * Klingelt: vibriert in einzelnen Stößen (und spielt auf Wunsch den Weckton), bis der [AlertController] ihn
 * beendet. Als Vordergrunddienst läuft er weiter, auch wenn der Bildschirm der Uhr ausgeht.
 *
 * Der Dienst entscheidet nichts selbst – was klingelt und wie lange, bestimmt der [AlertController]. Er zeigt
 * auch kein Vollbild; das öffnet sich über die Benachrichtigung.
 */
class AlertRingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val cycle = Runnable { runCycle() }

    // Alles ab hier nur auf dem Haupt-Thread.
    private var vibrate = true
    private var until = 0L
    private var looping = false
    private var destroyed = false
    private var sound: AlertSound? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as WearApp).container.alerts.onServiceStarted(this)
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        stopEffects()
        (application as WearApp).container.alerts.onServiceDestroyed(this)
        super.onDestroy()
    }

    /** Meldet den Dienst mit dieser Benachrichtigung im Vordergrund an. `false`, wenn das System es nicht erlaubt. */
    fun foreground(id: Int, notification: Notification): Boolean = try {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Der Klingel-Dienst darf nicht in den Vordergrund", e)
        false
    }

    /**
     * Klingelt bis [until] (gezählt in `SystemClock.elapsedRealtime()`). Klingelt es schon, gelten ab jetzt
     * die neuen Angaben.
     */
    fun ring(vibrate: Boolean, withSound: Boolean, until: Long) {
        handler.post {
            if (destroyed) return@post
            this.vibrate = vibrate
            this.until = until
            holdWakeLock(VibrationPlan.remaining(SystemClock.elapsedRealtime(), until) + WAKE_MARGIN_MILLIS)
            if (withSound) {
                val player = sound ?: AlertSound(this).also { sound = it }
                player.start()
            } else {
                sound?.stop()
                sound = null
            }
            if (!looping) {
                looping = true
                runCycle()
            }
        }
    }

    /** Sofort still. */
    fun quiet() {
        handler.post { stopEffects() }
    }

    /**
     * Beendet den Dienst. Mit [keepNotification] bleibt seine Benachrichtigung stehen (sie gehört dann nicht
     * mehr zum Dienst), sonst verschwindet sie.
     */
    fun finish(keepNotification: Boolean) {
        runCatching {
            stopForeground(if (keepNotification) Service.STOP_FOREGROUND_DETACH else Service.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** Ein Stoß – und der nächste ist schon eingeplant, solange noch Klingelzeit übrig ist. */
    private fun runCycle() {
        if (!VibrationPlan.shouldBurst(SystemClock.elapsedRealtime(), until)) {
            looping = false
            return
        }
        if (vibrate) buzz()
        sound?.pulse()
        handler.postDelayed(cycle, VibrationPlan.cycleMillis)
    }

    private fun buzz() {
        val vibrator = vibrator() ?: return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(VibrationPlan.burst(), -1),
                // Als „Wecker“ eingestuft: vibriert auch bei „Bitte nicht stören“ und im Energiesparmodus.
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }.onFailure { Log.w(TAG, "Vibrieren nicht möglich", it) }
    }

    private fun vibrator(): Vibrator? =
        runCatching { getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()

    /** Hält die Uhr wach, solange es klingelt – höchstens [millis], dann lässt das System von selbst los. */
    private fun holdWakeLock(millis: Long) {
        val lock = wakeLock ?: runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it }
        runCatching { lock?.acquire(millis) }
    }

    private fun stopEffects() {
        looping = false
        handler.removeCallbacks(cycle)
        runCatching { vibrator()?.cancel() }
        sound?.stop()
        sound = null
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
    }

    private companion object {
        const val TAG = "AlertRingService"
        const val WAKE_TAG = "adhsapp:alert-ring"

        /** So viel länger als die Klingelzeit bleibt die Uhr höchstens wach. */
        const val WAKE_MARGIN_MILLIS = 3_000L
    }
}

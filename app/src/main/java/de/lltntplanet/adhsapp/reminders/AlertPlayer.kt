package de.lltntplanet.adhsapp.reminders

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Spielt Ton und Vibration in Schleife, bis die Erinnerung bestätigt wird oder die Zeit abläuft. */
class AlertPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stop() }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun start(sound: Boolean, vibrate: Boolean, seconds: Int) {
        stop()
        if (sound) startSound()
        if (vibrate) {
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 1200), 0)) }
        }
        if (sound || vibrate) handler.postDelayed(stopRunnable, seconds.coerceIn(5, 600) * 1000L)
    }

    private fun startSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w("AlertPlayer", "Ton konnte nicht abgespielt werden", e)
            player = null
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
        runCatching { vibrator?.cancel() }
    }
}

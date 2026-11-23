package de.lltntplanet.adhsapp.wear.alert

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log

/**
 * Weckton der Uhr (nur wenn in der Handy-App eingeschaltet und die Uhr einen Lautsprecher hat). Spielt den
 * Weckton der Uhr in Schleife; hat die Uhr keinen, piept sie stattdessen bei jedem Vibrationsstoß.
 * Wird nur vom Haupt-Thread aus benutzt.
 */
internal class AlertSound(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    /**
     * Ist der Ton gerade wirklich zu hören? Nein, wenn die Uhr keinen Lautsprecher hat, weder Weckton noch
     * Piepton gestartet sind oder die Wecker-Lautstärke auf 0 steht. Dann muss die Uhr vibrieren – sonst ginge
     * die Erinnerung unter.
     */
    val audible: Boolean
        get() = (player != null || tone != null) && runCatching {
            (context.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0) > 0
        }.getOrDefault(false)

    fun start() {
        if (player != null || tone != null) return
        if (!hasSpeaker()) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null || !startPlayer(uri)) startTone()
    }

    /** Einmal je Vibrationsstoß: Piepton, falls kein Weckton abgespielt werden kann. */
    fun pulse() {
        runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_MILLIS) }
    }

    fun stop() {
        releasePlayer()
        tone?.let { generator -> runCatching { generator.release() } }
        tone = null
    }

    private fun hasSpeaker(): Boolean = runCatching {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) return@runCatching false
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching false
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }.getOrDefault(false)

    private fun startPlayer(uri: Uri): Boolean {
        val created = MediaPlayer()
        player = created
        return try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            created.setDataSource(context, uri)
            created.isLooping = true
            created.setOnPreparedListener { it.start() }
            created.setOnErrorListener { _, _, _ ->
                // Der Weckton lässt sich nicht abspielen: auf den Piepton ausweichen.
                releasePlayer()
                startTone()
                true
            }
            created.prepareAsync()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Weckton konnte nicht abgespielt werden", e)
            releasePlayer()
            false
        }
    }

    private fun releasePlayer() {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.release() }
        }
        player = null
    }

    private fun startTone() {
        if (tone != null) return
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
        } catch (e: Exception) {
            Log.w(TAG, "Piepton nicht möglich", e)
            null
        }
    }

    private companion object {
        const val TAG = "AlertSound"
        const val TONE_VOLUME = 80
        const val TONE_MILLIS = 1_200
    }
}

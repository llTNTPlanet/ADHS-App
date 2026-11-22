package de.lltntplanet.adhsapp.wear.alert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchReminder
import de.lltntplanet.adhsapp.wear.R
import kotlinx.serialization.encodeToString

/**
 * Baut und zeigt die Benachrichtigungen der Erinnerungen. Je Erinnerung gibt es genau eine; ihre Nummer
 * ergibt sich aus [WatchReminder.originKey]. Die Erinnerung steckt als JSON in der Benachrichtigung und in
 * ihren Knöpfen – so funktioniert alles auch, nachdem das System die App zwischendurch beendet hat.
 */
internal class AlertNotifications(private val context: Context) {

    private val manager: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        // Als „Wecker“ eingestuft: Die Uhr lässt Wecker auch bei „Bitte nicht stören“ und im Energiesparmodus durch.
        val alarm = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Hauptkanal: Der Kanal selbst ist still – es vibriert der Klingel-Dienst.
        val ring = NotificationChannel(
            CHANNEL_RING,
            context.getString(R.string.channel_ring_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_ring_description)
            enableVibration(false)
            setSound(null, alarm)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Rückfall: Darf der Dienst nicht starten, vibriert das System über diesen Kanal.
        val fallback = NotificationChannel(
            CHANNEL_FALLBACK,
            context.getString(R.string.channel_fallback_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_fallback_description)
            enableVibration(true)
            vibrationPattern = VibrationPlan.channelPattern()
            setSound(null, alarm)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        system.createNotificationChannels(listOf(ring, fallback))
    }

    /** Darf die Uhr-App gerade Erinnerungen zeigen? (Benachrichtigungen erlaubt und der Kanal nicht abgeschaltet.) */
    fun canShow(): Boolean = runCatching {
        val current = manager
        current.areNotificationsEnabled() &&
            current.getNotificationChannel(CHANNEL_RING)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    /** [timeoutMillis] gilt nur für [AlertStyle.INSISTENT]: Danach beendet das System das Vibrieren von selbst. */
    fun build(reminder: WatchReminder, style: AlertStyle, timeoutMillis: Long = 0L): Notification {
        val json = WatchJson.encodeToString(reminder)
        val insistent = style == AlertStyle.INSISTENT
        val builder = NotificationCompat.Builder(context, if (insistent) CHANNEL_FALLBACK else CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(reminder.subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(AlertLogic.text(reminder)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(reminder.color ?: DEFAULT_COLOR)
            .setWhen(reminder.targetTime ?: System.currentTimeMillis())
            .setShowWhen(true)
            // Die Handy-App zeigt ihre eigene Benachrichtigung; diese hier gehört nur auf die Uhr.
            .setLocalOnly(true)
            // Solange es klingelt, lässt sich die Erinnerung nicht versehentlich wegwischen.
            .setOngoing(style != AlertStyle.QUIET)
            // Nur beim ersten Erscheinen aufblenden – außer im Rückfall, dort soll das System vibrieren.
            .setOnlyAlertOnce(!insistent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screenIntent())
            .setDeleteIntent(
                AlertActionReceiver.pendingIntent(
                    context,
                    if (insistent) AlertActionReceiver.ACTION_EXPIRED else AlertActionReceiver.ACTION_SWIPED,
                    reminder.originKey,
                    json,
                ),
            )
            .addExtras(
                Bundle().apply {
                    putString(EXTRA_REMINDER, json)
                    putString(EXTRA_STYLE, style.name)
                },
            )
        AlertLogic.buttons(reminder).forEach { button ->
            builder.addAction(
                0,
                button.label,
                AlertActionReceiver.pendingIntent(
                    context,
                    AlertActionReceiver.ACTION_PREFIX + button.action,
                    reminder.originKey,
                    json,
                    button.minutes,
                ),
            )
        }
        if (insistent && timeoutMillis > 0) builder.setTimeoutAfter(timeoutMillis)
        val notification = builder.build()
        // Das System wiederholt Vibration (und Ton) von selbst, bis die Benachrichtigung verschwindet.
        if (insistent) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    /** Zeigt oder ersetzt die Benachrichtigung einer Erinnerung. `false`, wenn das nicht möglich ist. */
    @SuppressLint("MissingPermission")
    fun post(reminder: WatchReminder, style: AlertStyle, timeoutMillis: Long = 0L): Boolean {
        if (!canShow()) return false
        return try {
            manager.notify(AlertLogic.notificationId(reminder.originKey), build(reminder, style, timeoutMillis))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Benachrichtigung konnte nicht gezeigt werden", e)
            false
        }
    }

    fun cancel(originKey: String) {
        runCatching { manager.cancel(AlertLogic.notificationId(originKey)) }
    }

    /** Hinweis, dass ein Knopfdruck das Handy nicht erreicht hat. */
    @SuppressLint("MissingPermission")
    fun postNotice() {
        if (!canShow()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AlertLogic.NOTICE_TITLE)
            .setContentText(AlertLogic.NOTICE_TEXT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(DEFAULT_COLOR)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setContentIntent(screenIntent())
            .setTimeoutAfter(NOTICE_MILLIS)
            .build()
        runCatching { manager.notify(TAG_NOTICE, NOTICE_ID, notification) }
            .onFailure { Log.w(TAG, "Hinweis konnte nicht gezeigt werden", it) }
    }

    fun cancelNotice() {
        runCatching { manager.cancel(TAG_NOTICE, NOTICE_ID) }
    }

    /**
     * Benachrichtigung, mit der sich der Klingel-Dienst kurz anmeldet, wenn es beim Start schon nichts mehr
     * zu klingeln gibt. Das System zeigt sie verzögert an – der Dienst ist vorher wieder weg.
     */
    fun placeholder(): Notification =
        NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setLocalOnly(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

    /**
     * Erinnerungen, die noch als Benachrichtigung auf der Uhr stehen – neueste zuerst. Damit findet die App
     * ihren Stand wieder, nachdem das System sie beendet hatte.
     */
    fun restore(): List<Pair<WatchReminder, AlertStyle>> {
        val shown = runCatching { context.getSystemService(NotificationManager::class.java)?.activeNotifications }
            .getOrNull() ?: return emptyList()
        return shown
            .filter { it.tag == null }
            .sortedByDescending { it.postTime }
            .mapNotNull { status ->
                val extras = status.notification?.extras ?: return@mapNotNull null
                val json = extras.getString(EXTRA_REMINDER) ?: return@mapNotNull null
                val reminder = runCatching { WatchJson.decodeFromString<WatchReminder>(json) }.getOrNull()
                    ?: return@mapNotNull null
                val style = AlertStyle.entries.find { it.name == extras.getString(EXTRA_STYLE) } ?: AlertStyle.QUIET
                reminder to style
            }
            .distinctBy { it.first.originKey }
    }

    /** Antippen der Benachrichtigung öffnet das Vollbild – das darf das System immer. */
    private fun screenIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SCREEN_REQUEST,
        AlertActivity.intent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "AlertNotifications"

        /** Die Kanäle lassen sich nach dem Anlegen nicht mehr ändern – bei Änderungen die Nummer erhöhen. */
        const val CHANNEL_RING = "alert_ring_v1"
        const val CHANNEL_FALLBACK = "alert_fallback_v1"

        /** Nummer der Platzhalter-Benachrichtigung des Klingel-Dienstes. */
        const val PLACEHOLDER_ID = 0x41444853

        private const val TAG_NOTICE = "notice"
        private const val NOTICE_ID = 1
        const val NOTICE_MILLIS = 60_000L
        private const val SCREEN_REQUEST = 1
        private const val DEFAULT_COLOR = 0xFF0B57D0.toInt()
        private const val EXTRA_REMINDER = "de.lltntplanet.adhsapp.wear.REMINDER"
        private const val EXTRA_STYLE = "de.lltntplanet.adhsapp.wear.STYLE"
    }
}

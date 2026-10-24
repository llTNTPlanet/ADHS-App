package de.lltntplanet.adhsapp.reminders

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.lltntplanet.adhsapp.R
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.util.Permissions

/**
 * Zeigt Erinnerungen an:
 * - als Benachrichtigung mit Full-Screen-Intent → bei gesperrtem/ausgeschaltetem Bildschirm erscheint
 *   die Erinnerung sofort im Vollbild (wie ein Wecker),
 * - bei entsperrtem Handy zusätzlich direkt im Vollbild, wenn "Über anderen Apps anzeigen" erlaubt ist.
 */
class ReminderNotifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_reminders_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        val info = NotificationChannel(
            CHANNEL_INFO,
            context.getString(R.string.channel_info_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_info_description) }
        nm.createNotificationChannels(listOf(alerts, info))
    }

    @SuppressLint("MissingPermission")
    fun show(reminders: List<Reminder>, settings: AppSettings) {
        if (reminders.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        val canNotify = Permissions.notificationsGranted(context)
        if (canNotify) {
            reminders.forEachIndexed { index, r ->
                val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ReminderText.notificationTitle(r))
                    .setContentText(ReminderText.subtitle(r))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(ReminderText.subtitle(r), r.detail).joinToString("\n")))
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setColor(r.color ?: DEFAULT_COLOR)
                    .setWhen(r.targetTime ?: r.triggerAt)
                    .setShowWhen(true)
                    .setContentIntent(activityIntent(listOf(r), r.notificationId))
                    .addAction(0, "10 Min. später", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_SNOOZE, r, 10))
                if (r.kind == ReminderKind.TASK && r.refId != null) {
                    builder.addAction(0, "Erledigt", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_DONE, r))
                }
                builder.addAction(0, "OK", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_DISMISS, r))
                if (index == 0) builder.setFullScreenIntent(activityIntent(reminders, FULL_SCREEN_REQUEST), true)
                nm.notify(r.notificationId, builder.build())
            }
        }
        // Entsperrt und in Benutzung: Full-Screen-Intents werden vom System nur als Banner gezeigt.
        // Mit "Über anderen Apps anzeigen" darf die App das Vollbild selbst starten.
        val unlockedAndOn = isInteractive() && !isLocked()
        if ((unlockedAndOn && settings.launchOverApps && Settings.canDrawOverlays(context)) || !canNotify) {
            try {
                context.startActivity(ReminderActivity.intent(context, reminders).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Vollbild konnte nicht direkt gestartet werden", e)
            }
        }
    }

    fun cancel(reminder: Reminder) {
        NotificationManagerCompat.from(context).cancel(reminder.notificationId)
    }

    private fun activityIntent(reminders: List<Reminder>, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            ReminderActivity.intent(context, reminders),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun isInteractive() = context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun isLocked() = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    companion object {
        private const val TAG = "ReminderNotifier"
        const val CHANNEL_ALERTS = "reminders_fullscreen"
        const val CHANNEL_INFO = "info"
        private const val FULL_SCREEN_REQUEST = 4711
        private const val DEFAULT_COLOR = 0xFF0B57D0.toInt()
    }
}

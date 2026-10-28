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
import de.lltntplanet.adhsapp.MainActivity
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
        val meals = NotificationChannel(
            CHANNEL_MEALS,
            context.getString(R.string.channel_meals_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_meals_description) }
        val info = NotificationChannel(
            CHANNEL_INFO,
            context.getString(R.string.channel_info_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_info_description) }
        nm.createNotificationChannels(listOf(alerts, meals, info))
    }

    @SuppressLint("MissingPermission")
    fun show(all: List<Reminder>, settings: AppSettings) {
        if (all.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        val canNotify = Permissions.notificationsGranted(context)
        // Stille Erinnerungen (Mahlzeit eintragen) nur als normale Benachrichtigung – nie Vollbild oder Klingeln.
        val (quiet, reminders) = all.partition { it.kind.isQuiet }
        if (canNotify) quiet.forEach { nm.notify(it.notificationId, quietNotification(it)) }
        if (reminders.isEmpty()) return
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
                if ((r.kind == ReminderKind.TASK || r.kind.isRoutine) && r.refId != null) {
                    val label = if (r.kind == ReminderKind.MEDICATION) "Genommen" else "Erledigt"
                    builder.addAction(0, label, ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_DONE, r))
                }
                if (r.kind.isCheckup && r.refId != null) {
                    builder.addAction(0, "Jetzt ausfüllen", checkupIntent(r))
                }
                if (r.canMute) {
                    builder.addAction(0, "Keine weiteren", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_MUTE, r))
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

    /** Benachrichtigung „Mahlzeit eintragen“ mit den Knöpfen „Per Foto“ und „Per Chat“. */
    private fun quietNotification(r: Reminder): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_MEALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ReminderText.notificationTitle(r))
            .setContentText(ReminderText.subtitle(r))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ReminderText.subtitle(r)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setColor(r.color ?: DEFAULT_COLOR)
            .setWhen(r.triggerAt)
            .setShowWhen(true)
            .setContentIntent(nutritionIntent(r, null))
            .addAction(0, "Per Foto", nutritionIntent(r, MainActivity.NUTRITION_ACTION_PHOTO))
            .addAction(0, "Per Chat", nutritionIntent(r, MainActivity.NUTRITION_ACTION_CHAT))
            .addAction(0, "Später", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_SNOOZE, r, 30))
            .build()

    /** Öffnet die Seite „Routinen“ direkt mit der Symptom-Abfrage dieses Checkups. */
    private fun checkupIntent(r: Reminder): PendingIntent {
        val intent = checkupLaunchIntent(context, r).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            ("checkup:${r.key}").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Öffnet die Seite „Essen“; mit [action] startet dort direkt Kamera bzw. Beschreibung. */
    private fun nutritionIntent(r: Reminder, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, ReminderKind.MEAL.name)
            .putExtra(MainActivity.EXTRA_NUTRITION_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            ("nutrition:${action ?: "open"}:${r.key}").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        const val CHANNEL_MEALS = "meals"
        const val CHANNEL_INFO = "info"
        private const val FULL_SCREEN_REQUEST = 4711
        private const val DEFAULT_COLOR = 0xFF0B57D0.toInt()
    }
}

/** Intent auf die Hauptseite, der den Checkup aus [r] direkt öffnet (Routinen-Tab + Symptom-Abfrage). */
fun checkupLaunchIntent(context: Context, r: Reminder): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_TAB, ReminderKind.CHECKUP.name)
        .putExtra(MainActivity.EXTRA_CHECKUP_ID, r.refId ?: -1L)
        .putExtra(MainActivity.EXTRA_CHECKUP_AT, r.targetTime ?: r.triggerAt)

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
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.lltntplanet.adhsapp.MainActivity
import de.lltntplanet.adhsapp.R
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.util.Permissions
import de.lltntplanet.adhsapp.watch.WatchLink
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Zeigt Erinnerungen an:
 * - als Benachrichtigung mit Full-Screen-Intent → bei gesperrtem/ausgeschaltetem Bildschirm erscheint
 *   die Erinnerung sofort im Vollbild (wie ein Wecker),
 * - bei entsperrtem Handy zusätzlich direkt im Vollbild, wenn "Über anderen Apps anzeigen" erlaubt ist,
 * - nur als stille Benachrichtigung, wenn die Uhr bestätigt hat, dass sie klingelt.
 */
class ReminderNotifier(
    private val context: Context,
    /**
     * Verbindung zur Uhr: Wird eine Erinnerung am Handy entfernt, verschwindet sie auch dort – egal, welches
     * Klingel-Ziel gerade eingestellt ist (es kann seit dem Klingeln geändert worden sein).
     */
    private val watch: WatchLink,
) {
    private val _removed = MutableSharedFlow<String>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Schlüssel der ursprünglichen Erinnerung (originKey), sobald sie am Handy entfernt wurde – per Knopf in der
     * Benachrichtigung, auf der Uhr oder vom Medikamentenspender. Ein offenes Vollbild schließt sie dann und hört
     * auf zu klingeln.
     */
    val removed: SharedFlow<String> = _removed.asSharedFlow()

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
        // Die Uhr klingelt schon: am Handy nur still anzeigen (kein Ton, keine Vibration, kein Aufpoppen).
        val watchRings = NotificationChannel(
            CHANNEL_WATCH,
            context.getString(R.string.channel_watch_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_watch_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(
            CHANNEL_INFO,
            context.getString(R.string.channel_info_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_info_description) }
        nm.createNotificationChannels(listOf(alerts, meals, watchRings, info))
    }

    /**
     * Zeigt die fälligen Erinnerungen am Handy.
     *
     * [ring] = false heißt: Die Uhr hat bestätigt, dass sie klingelt. Das Handy bleibt dann still und zeigt je
     * Erinnerung nur eine stille Benachrichtigung mit denselben Knöpfen – ohne Vollbild, Ton und Vibration.
     *
     * [localOnly] = true heißt: Die Benachrichtigungen werden nicht zusätzlich vom System auf die Uhr gespiegelt
     * (die Uhr-App zeigt sie selbst, oder die Uhr soll still bleiben).
     */
    @SuppressLint("MissingPermission")
    fun show(all: List<Reminder>, settings: AppSettings, ring: Boolean = true, localOnly: Boolean = false) {
        if (all.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        val canNotify = Permissions.notificationsGranted(context)
        // Stille Erinnerungen (Mahlzeit eintragen) nur als normale Benachrichtigung – nie Vollbild oder Klingeln.
        // Die Uhr-App bekommt sie nie; nur bei „Nur Handy“ spiegelt sie auch das System nicht auf die Uhr.
        val (quiet, reminders) = all.partition { it.kind.isQuiet }
        val quietLocalOnly = settings.ringTarget == RingTarget.PHONE
        if (canNotify) quiet.forEach { nm.notify(it.notificationId, quietNotification(it, quietLocalOnly)) }
        if (reminders.isEmpty()) return
        if (canNotify) {
            reminders.forEachIndexed { index, r ->
                val builder = NotificationCompat.Builder(context, if (ring) CHANNEL_ALERTS else CHANNEL_WATCH)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ReminderText.notificationTitle(r))
                    .setContentText(ReminderText.subtitle(r))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(ReminderText.subtitle(r), r.detail).joinToString("\n")))
                    .setCategory(if (ring) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(if (ring) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setColor(r.color ?: DEFAULT_COLOR)
                    .setWhen(r.targetTime ?: r.triggerAt)
                    .setShowWhen(true)
                    .setLocalOnly(localOnly || !ring)
                    // Antippen der stillen Benachrichtigung öffnet das Vollbild, ohne dass das Handy klingelt.
                    .setContentIntent(activityIntent(listOf(r), r.notificationId, silent = !ring))
                    .addExtras(Bundle().apply { putString(EXTRA_ORIGIN_KEY, r.originKey ?: r.key) })
                    .addAction(0, "10 Min. später", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_SNOOZE, r, 10))
                if (!ring) builder.setSubText("Klingelt auf der Uhr")
                if (ReminderButtons.canDone(r)) {
                    builder.addAction(0, ReminderButtons.doneLabel(r), ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_DONE, r))
                }
                if (r.kind.isCheckup && r.refId != null) {
                    builder.addAction(0, "Jetzt ausfüllen", checkupIntent(r))
                }
                if (r.canMute) {
                    builder.addAction(0, "Keine weiteren", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_MUTE, r))
                }
                builder.addAction(0, "OK", ReminderActionReceiver.pendingIntent(context, ReminderActionReceiver.ACTION_DISMISS, r))
                if (ring && index == 0) builder.setFullScreenIntent(activityIntent(reminders, FULL_SCREEN_REQUEST), true)
                nm.notify(r.notificationId, builder.build())
            }
        }
        // Die Uhr klingelt: Das Handy bleibt still und startet kein Vollbild.
        if (!ring) return
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
    private fun quietNotification(r: Reminder, localOnly: Boolean): android.app.Notification =
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
            .setLocalOnly(localOnly)
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

    /**
     * Die Erinnerung ist bestätigt (Knopf, Vollbild oder Uhr): Benachrichtigung entfernen. Ein offenes Vollbild und
     * die Uhr erfahren davon und räumen sie ebenfalls weg.
     */
    fun cancel(reminder: Reminder) {
        NotificationManagerCompat.from(context).cancel(reminder.notificationId)
        // Stille Erinnerungen (Mahlzeit) kennt die Uhr-App nicht.
        announceRemoved(reminder.originKey ?: reminder.key, tellWatch = !reminder.kind.isQuiet)
    }

    /**
     * Benachrichtigung zu einem Erinnerungs-Schlüssel entfernen (z. B. „rt:…“, wenn der Spender die Einnahme meldet) –
     * auch eine schon angezeigte geschlummerte Wiederholung davon. Vollbild und Uhr räumen sie ebenfalls weg.
     */
    fun cancelKey(key: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(key.hashCode())
        val active = runCatching { context.getSystemService(NotificationManager::class.java)?.activeNotifications }.getOrNull()
        active?.filter { it.notification.extras.getString(EXTRA_ORIGIN_KEY) == key }?.forEach { nm.cancel(it.tag, it.id) }
        announceRemoved(key, tellWatch = true)
    }

    /**
     * Sagt Bescheid, dass die Erinnerung weg ist: einem offenen Vollbild und der Uhr. Die Uhr erfährt es auch bei
     * „Nur Handy“ – dort kann noch eine Erinnerung von vor dem Umstellen stehen. Ohne Uhr-App passiert dabei nichts.
     */
    private fun announceRemoved(originKey: String, tellWatch: Boolean) {
        _removed.tryEmit(originKey)
        if (tellWatch) watch.cancel(listOf(originKey))
    }

    private fun activityIntent(reminders: List<Reminder>, requestCode: Int, silent: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            ReminderActivity.intent(context, reminders, silent),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun isInteractive() = context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun isLocked() = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    companion object {
        private const val TAG = "ReminderNotifier"
        const val CHANNEL_ALERTS = "reminders_fullscreen"
        /** Stiller Kanal: Die Uhr klingelt, das Handy zeigt die Erinnerung nur an. */
        const val CHANNEL_WATCH = "reminders_watch"
        const val CHANNEL_MEALS = "meals"
        const val CHANNEL_INFO = "info"
        private const val FULL_SCREEN_REQUEST = 4711
        private const val DEFAULT_COLOR = 0xFF0B57D0.toInt()
        /** Schlüssel der ursprünglichen Erinnerung – auch bei geschlummerten Wiederholungen. */
        private const val EXTRA_ORIGIN_KEY = "de.lltntplanet.adhsapp.ORIGIN_KEY"
    }
}

/** Intent auf die Hauptseite, der den Checkup aus [r] direkt öffnet (Routinen-Tab + Symptom-Abfrage). */
fun checkupLaunchIntent(context: Context, r: Reminder): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_TAB, ReminderKind.CHECKUP.name)
        .putExtra(MainActivity.EXTRA_CHECKUP_ID, r.refId ?: -1L)
        .putExtra(MainActivity.EXTRA_CHECKUP_AT, r.targetTime ?: r.triggerAt)

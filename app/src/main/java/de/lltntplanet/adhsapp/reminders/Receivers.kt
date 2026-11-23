package de.lltntplanet.adhsapp.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.lltntplanet.adhsapp.AdhsApp
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.update.ApkInstaller
import de.lltntplanet.adhsapp.update.UpdateWorker
import de.lltntplanet.adhsapp.watch.WatchSync
import kotlinx.coroutines.launch

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (AdhsApp) -> Unit) {
    val app = context.applicationContext as AdhsApp
    val pending = goAsync()
    app.container.scope.launch {
        try {
            block(app)
        } catch (e: Exception) {
            Log.e("Receivers", "Fehler im Hintergrund", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * Der exakte Alarm ist ausgelöst: fällige Erinnerungen zustellen. Die Klingel-Weiche fragt je nach Einstellung
 * zuerst die Uhr; danach klingelt das Handy oder zeigt die Erinnerung still an.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { app ->
        val c = app.container
        val due = c.reminderEngine.fireDue()
        c.reminderDelivery.deliver(due, c.settings.current())
    }

    companion object {
        const val ACTION_FIRE = "de.lltntplanet.adhsapp.action.FIRE_REMINDERS"
    }
}

/** Knöpfe in der Benachrichtigung: Schlummern, Erledigt, Keine weiteren, OK. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { app ->
        val reminder = intent.getStringExtra(EXTRA_REMINDER)?.let { ReminderPayload.decode(it) } ?: return@runAsync
        val action = when (intent.action) {
            ACTION_SNOOZE -> ReminderActionHandler.Action.SNOOZE
            ACTION_DONE -> ReminderActionHandler.Action.DONE
            ACTION_MUTE -> ReminderActionHandler.Action.MUTE
            else -> ReminderActionHandler.Action.DISMISS
        }
        // Dieselbe Logik wie bei den Knöpfen auf der Uhr.
        app.container.reminderActions.perform(
            action,
            reminder,
            intent.getIntExtra(EXTRA_MINUTES, ReminderActionHandler.DEFAULT_SNOOZE_MINUTES),
        )
        // Abgehakt: Die Übersicht auf der Uhr soll das gleich zeigen – auch wenn die App sonst gerade nicht läuft.
        if (action == ReminderActionHandler.Action.DONE) {
            app.container.watchSync.syncAndWait(timeoutMillis = WatchSync.BRIEF_WAIT_MILLIS)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "de.lltntplanet.adhsapp.action.SNOOZE"
        const val ACTION_DONE = "de.lltntplanet.adhsapp.action.DONE"
        const val ACTION_DISMISS = "de.lltntplanet.adhsapp.action.DISMISS"
        const val ACTION_MUTE = "de.lltntplanet.adhsapp.action.MUTE"
        private const val EXTRA_REMINDER = "reminder"
        private const val EXTRA_MINUTES = "minutes"

        fun pendingIntent(context: Context, action: String, reminder: Reminder, minutes: Int = 0): PendingIntent {
            val intent = Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_REMINDER, ReminderPayload.encode(reminder))
                .putExtra(EXTRA_MINUTES, minutes)
            return PendingIntent.getBroadcast(
                context,
                (action + reminder.key).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/** Nach Neustart, App-Update oder Zeitänderung müssen Alarme neu gestellt werden. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        reschedule(context, intent.action)
    }

    private fun reschedule(context: Context, action: String?) = runAsync(context) { app ->
        MaintenanceWorker.ensureScheduled(context)
        UpdateWorker.ensureScheduled(context)
        CalendarChangeWorker.enqueue(context)
        app.container.reminderEngine.reschedule()
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ApkInstaller.cleanup(context)
            app.container.updateNotifier.notifyUpdated(BuildConfig.VERSION_NAME)
        }
        // Neue Uhrzeit oder Zeitzone verschiebt auch die Übersicht auf der Uhr. Ohne Uhr passiert nichts.
        app.container.watchSync.syncAndWait(timeoutMillis = WatchSync.BRIEF_WAIT_MILLIS)
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

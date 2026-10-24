package de.lltntplanet.adhsapp.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.lltntplanet.adhsapp.AdhsApp
import de.lltntplanet.adhsapp.data.google.GoogleJson
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

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

/** Der exakte Alarm ist ausgelöst: fällige Erinnerungen anzeigen. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { app ->
        val c = app.container
        val due = c.reminderEngine.fireDue()
        c.notifier.show(due, c.settings.current())
    }

    companion object {
        const val ACTION_FIRE = "de.lltntplanet.adhsapp.action.FIRE_REMINDERS"
    }
}

/** Knöpfe in der Benachrichtigung: Schlummern, Erledigt, OK. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { app ->
        val c = app.container
        val reminder = intent.getStringExtra(EXTRA_REMINDER)
            ?.let { runCatching { GoogleJson.decodeFromString<Reminder>(it) }.getOrNull() } ?: return@runAsync
        c.notifier.cancel(reminder)
        when (intent.action) {
            ACTION_SNOOZE -> c.reminderEngine.snooze(reminder, intent.getIntExtra(EXTRA_MINUTES, 10))
            ACTION_DONE -> reminder.refId?.let { c.tasks.setDone(it, true) }
            ACTION_DISMISS -> Unit
        }
    }

    companion object {
        const val ACTION_SNOOZE = "de.lltntplanet.adhsapp.action.SNOOZE"
        const val ACTION_DONE = "de.lltntplanet.adhsapp.action.DONE"
        const val ACTION_DISMISS = "de.lltntplanet.adhsapp.action.DISMISS"
        private const val EXTRA_REMINDER = "reminder"
        private const val EXTRA_MINUTES = "minutes"

        fun pendingIntent(context: Context, action: String, reminder: Reminder, minutes: Int = 0): PendingIntent {
            val intent = Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_REMINDER, GoogleJson.encodeToString(reminder))
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
        reschedule(context)
    }

    private fun reschedule(context: Context) = runAsync(context) { app ->
        MaintenanceWorker.ensureScheduled(context)
        CalendarChangeWorker.enqueue(context)
        app.container.reminderEngine.reschedule()
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

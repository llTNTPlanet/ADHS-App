package de.lltntplanet.adhsapp.wear.alert

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchReminder
import de.lltntplanet.adhsapp.wear.WearApp
import kotlinx.coroutines.launch

/**
 * Knöpfe der Benachrichtigung einer Erinnerung – und was passiert, wenn sie weggewischt wird oder das System
 * sie nach der Klingelzeit entfernt. Die Erinnerung steckt als JSON im Intent; so funktioniert alles auch,
 * nachdem das System die App zwischendurch beendet hat.
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? WearApp ?: return
        val action = intent.action ?: return
        val json = intent.getStringExtra(EXTRA_REMINDER) ?: return
        val reminder = runCatching { WatchJson.decodeFromString<WatchReminder>(json) }.getOrNull() ?: return
        val alerts = app.container.alerts
        when {
            action == ACTION_SWIPED -> alerts.dismiss(listOf(reminder.originKey))
            action == ACTION_EXPIRED -> alerts.onInsistentExpired(reminder)
            action.startsWith(ACTION_PREFIX) -> {
                val name = action.removePrefix(ACTION_PREFIX)
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                // Das Senden ans Handy dauert einen Moment: Der Empfänger bleibt so lange am Leben.
                val pending = goAsync()
                app.container.scope.launch {
                    try {
                        alerts.perform(reminder, name, minutes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Knopf konnte nicht ausgeführt werden", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AlertActionReceiver"

        /** Die (stille) Benachrichtigung wurde weggewischt. */
        const val ACTION_SWIPED = "de.lltntplanet.adhsapp.wear.alert.SWIPED"

        /** Die im Rückfall vibrierende Benachrichtigung ist verschwunden (Klingelzeit vorbei oder weggewischt). */
        const val ACTION_EXPIRED = "de.lltntplanet.adhsapp.wear.alert.EXPIRED"

        /** Davor steht eine der Konstanten aus `ReminderActions`, z. B. „…action.done“. */
        const val ACTION_PREFIX = "de.lltntplanet.adhsapp.wear.alert.action."

        private const val EXTRA_REMINDER = "reminder"
        private const val EXTRA_MINUTES = "minutes"

        /**
         * Je Erinnerung und Knopf ein eigener PendingIntent: Aktion und Daten-Adresse unterscheiden sie, die
         * Erinnerung selbst reist als JSON mit.
         */
        fun pendingIntent(
            context: Context,
            action: String,
            originKey: String,
            json: String,
            minutes: Int = 0,
        ): PendingIntent {
            val intent = Intent(context, AlertActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.fromParts("adhs-alert", originKey, null))
                .putExtra(EXTRA_REMINDER, json)
                .putExtra(EXTRA_MINUTES, minutes)
            return PendingIntent.getBroadcast(
                context,
                (action + originKey).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

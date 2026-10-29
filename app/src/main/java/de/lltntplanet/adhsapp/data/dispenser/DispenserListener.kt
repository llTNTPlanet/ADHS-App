package de.lltntplanet.adhsapp.data.dispenser

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import de.lltntplanet.adhsapp.AdhsApp
import kotlinx.coroutines.launch

/**
 * Liest die Benachrichtigungen der Spender-App (iDispenser) mit – sonst nichts. Dafür muss der Nutzer den
 * Benachrichtigungszugriff erlauben (Einstellungen → Medikamentenspender).
 */
class DispenserListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Was während einer Pause (Neustart, Update) gekommen ist und noch in der Leiste steht, nachholen.
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        active.forEach(::handle)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in DispenserLogic.PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }?.filter { it.isNotBlank() }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = (bigText ?: lines?.joinToString("\n") ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty()
        // Sammel-Benachrichtigung: Ältere Zeilen („08:00 genommen“) dürfen eine neue Meldung nicht umdeuten.
        val latest = if (bigText == null && lines != null && lines.size > 1) lines.last() else null
        val c = (applicationContext as AdhsApp).container
        c.scope.launch {
            runCatching {
                c.dispenser.onNotification(DispenserNotification(sbn.packageName, sbn.key, sbn.postTime, title, text, latest))
            }.onFailure { Log.e(TAG, "Spender-Meldung konnte nicht verarbeitet werden", it) }
        }
    }

    companion object {
        private const val TAG = "DispenserListener"

        /** Hat der Nutzer den Benachrichtigungszugriff für die App erlaubt? */
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Einstellungsseite für den Benachrichtigungszugriff (ab Android 11 direkt für diese App). */
        fun settingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, DispenserListener::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

        /** Kann die App den Spender mitlesen? Nur Meldungen von iDispenser haken ab – ohne die App kommt nichts. */
        fun isWatching(context: Context): Boolean = isEnabled(context) && isDispenserAppInstalled(context)

        /** Ist die App iDispenser installiert? */
        fun isDispenserAppInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(DispenserLogic.IDISPENSER_PACKAGE, 0) }.isSuccess
    }
}

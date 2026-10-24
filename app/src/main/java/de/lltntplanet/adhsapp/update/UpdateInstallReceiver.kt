package de.lltntplanet.adhsapp.update

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import de.lltntplanet.adhsapp.AdhsApp

/** Ergebnis der Installation vom Android-Paketinstaller. */
class UpdateInstallReceiver : BroadcastReceiver() {

    // Der Bestätigungs-Intent kommt vom System-Paketinstaller über unseren eigenen PendingIntent
    // (Receiver ist nicht exportiert), daher ist das Starten unbedenklich.
    @SuppressLint("UnsafeIntentLaunch")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val c = (context.applicationContext as AdhsApp).container
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (c.updates.isAppVisible()) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { c.updateNotifier.notifyConfirm(versionName, confirm) }
                } else {
                    c.updateNotifier.notifyConfirm(versionName, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update auf $versionName installiert")
            else -> {
                val message = describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                Log.w(TAG, "Installation fehlgeschlagen: $status $message")
                c.updates.onInstallFailed(message)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_STATUS = "de.lltntplanet.adhsapp.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_NAME = "version_name"

        fun describe(status: Int, systemMessage: String?): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation abgebrochen. Du kannst es jederzeit erneut versuchen."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation wurde vom System blockiert."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Das Update passt nicht zur installierten App (andere Signatur). Einmalig deinstallieren und die " +
                    "neue Version aus GitHub (Releases) installieren."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Diese Version ist mit dem Gerät nicht kompatibel."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Die heruntergeladene Datei ist beschädigt."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Zu wenig Speicherplatz für das Update."
            else -> "Update fehlgeschlagen" + (systemMessage?.let { ": $it" } ?: ".")
        }
    }
}

package de.lltntplanet.adhsapp.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.lltntplanet.adhsapp.MainActivity
import de.lltntplanet.adhsapp.R
import de.lltntplanet.adhsapp.reminders.ReminderNotifier
import de.lltntplanet.adhsapp.util.Permissions

/** Benachrichtigungen rund um App-Updates. */
class UpdateNotifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "App-Updates", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Meldet neue Versionen der ADHS App."
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun notifyAvailable(info: UpdateInfo, problem: String?) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        notify(
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Update verfügbar: Version ${info.versionName}")
                .setContentText(problem ?: "Tippen, um die neue Version zu installieren.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(problem ?: "Tippen, um die neue Version zu installieren."))
                .setContentIntent(activity(open, REQUEST_OPEN)),
        )
    }

    /** Android möchte die Installation bestätigt haben. */
    fun notifyConfirm(versionName: String, confirm: Intent) {
        notify(
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Update bereit: Version $versionName")
                .setContentText("Tippen, um die Installation zu bestätigen.")
                .setContentIntent(activity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), REQUEST_CONFIRM)),
        )
    }

    fun notifyFailed(message: String) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        notify(
            NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Update fehlgeschlagen")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(activity(open, REQUEST_OPEN)),
        )
    }

    /** Nach einem erfolgreichen Update (leiser Hinweis). */
    fun notifyUpdated(versionName: String) {
        notify(
            NotificationCompat.Builder(context, ReminderNotifier.CHANNEL_INFO)
                .setContentTitle("ADHS App aktualisiert")
                .setContentText("Jetzt auf Version $versionName.")
                .setContentIntent(activity(Intent(context, MainActivity::class.java), REQUEST_OPEN)),
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    @SuppressLint("MissingPermission")
    private fun notify(builder: NotificationCompat.Builder) {
        if (!Permissions.notificationsGranted(context)) return
        builder.setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun activity(intent: Intent, request: Int): PendingIntent =
        PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 9001
        private const val REQUEST_OPEN = 9002
        private const val REQUEST_CONFIRM = 9003
    }
}

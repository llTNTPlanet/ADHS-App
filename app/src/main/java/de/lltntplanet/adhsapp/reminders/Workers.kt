package de.lltntplanet.adhsapp.reminders

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lltntplanet.adhsapp.AdhsApp
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Läuft etwa alle 30 Minuten: plant Erinnerungen neu (Sicherheitsnetz, falls ein Alarm verloren ging)
 * und gleicht Google Tasks ab.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as AdhsApp).container
        c.reminderEngine.reschedule()
        if (c.settings.current().tasksConnected) {
            runCatching { c.tasksSync.sync() }.onFailure { Log.w(TAG, "Google-Tasks-Abgleich fehlgeschlagen", it) }
        }
        c.tasks.purgeOldDone(30L * 24 * 60 * 60 * 1000)
        c.routines.purgeOldLog(90)
        return Result.success()
    }

    companion object {
        private const val TAG = "MaintenanceWorker"
        private const val NAME = "maintenance"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Reagiert auf Änderungen im Kalender oder in den Kontakten (z. B. neuer Termin aus Google Kalender). */
class CalendarChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as AdhsApp).container.reminderEngine.reschedule()
        // Inhalts-Trigger gelten nur einmal – für die nächste Änderung neu einreihen.
        enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "calendar-change"

        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .addContentUriTrigger(ContactsContract.Contacts.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                .setTriggerContentMaxDelay(Duration.ofMinutes(2))
                .build()
            val request = OneTimeWorkRequestBuilder<CalendarChangeWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}

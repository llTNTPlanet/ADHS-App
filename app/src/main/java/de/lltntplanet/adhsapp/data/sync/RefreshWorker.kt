package de.lltntplanet.adhsapp.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lltntplanet.adhsapp.AdhsApp
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Hintergrund-Aktualisierung alle 5 Minuten. WorkManager erlaubt periodische Jobs erst ab 15 Minuten,
 * deshalb reiht sich der Job nach jedem Lauf selbst mit 5 Minuten Verzögerung wieder ein.
 * Im Energiesparmodus (Doze) fasst Android die Läufe zusammen – dann kann es etwas länger dauern.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as AdhsApp).container
        runCatching { c.refresher.refreshAll() }
        // Nächsten Lauf planen – unabhängig davon, ob dieser geklappt hat.
        schedule(applicationContext, ExistingWorkPolicy.REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh-5min"

        /** Beim App-Start: nur einreihen, wenn noch kein Lauf ansteht. */
        fun ensureScheduled(context: Context) = schedule(context, ExistingWorkPolicy.KEEP)

        private fun schedule(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(Duration.ofMinutes(DataRefresher.INTERVAL_MINUTES))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}

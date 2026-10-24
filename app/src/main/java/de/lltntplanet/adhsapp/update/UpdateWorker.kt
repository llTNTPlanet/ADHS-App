package de.lltntplanet.adhsapp.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lltntplanet.adhsapp.AdhsApp
import java.util.concurrent.TimeUnit

/** Sucht alle paar Stunden im Hintergrund nach einer neuen Version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as AdhsApp).container.updates.backgroundCheck()
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

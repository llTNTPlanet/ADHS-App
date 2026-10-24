package de.lltntplanet.adhsapp

import android.app.Application
import android.util.Log
import de.lltntplanet.adhsapp.data.birthdays.BirthdayRepository
import de.lltntplanet.adhsapp.data.calendar.CalendarRepository
import de.lltntplanet.adhsapp.data.db.AppDatabase
import de.lltntplanet.adhsapp.data.google.GoogleAuth
import de.lltntplanet.adhsapp.data.google.GoogleAuthTokenProvider
import de.lltntplanet.adhsapp.data.google.GoogleHttp
import de.lltntplanet.adhsapp.data.google.GoogleTasksApi
import de.lltntplanet.adhsapp.data.google.GoogleTasksSync
import de.lltntplanet.adhsapp.data.google.HealthApi
import de.lltntplanet.adhsapp.data.health.HealthRepository
import de.lltntplanet.adhsapp.data.settings.SecretStore
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.data.tasks.TaskRepository
import de.lltntplanet.adhsapp.reminders.CalendarChangeWorker
import de.lltntplanet.adhsapp.reminders.MaintenanceWorker
import de.lltntplanet.adhsapp.reminders.ReminderEngine
import de.lltntplanet.adhsapp.reminders.ReminderNotifier
import de.lltntplanet.adhsapp.update.ApkInstaller
import de.lltntplanet.adhsapp.update.GitHubReleaseClient
import de.lltntplanet.adhsapp.update.UpdateManager
import de.lltntplanet.adhsapp.update.UpdateNotifier
import de.lltntplanet.adhsapp.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class AdhsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.updateNotifier.createChannel()
        MaintenanceWorker.ensureScheduled(this)
        UpdateWorker.ensureScheduled(this)
        CalendarChangeWorker.enqueue(this)
        container.requestReschedule()
    }
}

/** Einfache manuelle Abhängigkeitsverwaltung. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val db = AppDatabase.create(app)
    val calendar = CalendarRepository(app)
    val birthdays = BirthdayRepository(app, db.birthdayDao())
    val tasks = TaskRepository(db.taskDao()) { requestReschedule() }

    val auth = GoogleAuth(app, settings)
    private val http = GoogleHttp(GoogleAuthTokenProvider(auth))
    val health = HealthRepository(HealthApi(http), settings, auth)
    val tasksSync = GoogleTasksSync(GoogleTasksApi(http), db.taskDao(), settings) { requestReschedule() }

    val notifier = ReminderNotifier(app)
    val reminderEngine = ReminderEngine(app, calendar, birthdays, db.taskDao(), db.reminderDao(), settings)

    val secrets = SecretStore(app)
    val updateNotifier = UpdateNotifier(app)
    val updates = UpdateManager(app, settings, secrets, GitHubReleaseClient(), ApkInstaller(app), updateNotifier, scope)

    private var rescheduleJob: Job? = null

    /** Plant Erinnerungen neu; mehrere schnelle Aufrufe werden zusammengefasst. */
    @Synchronized
    fun requestReschedule() {
        rescheduleJob?.cancel()
        rescheduleJob = scope.launch {
            delay(300)
            runCatching { reminderEngine.reschedule() }
                .onFailure { Log.e("AppContainer", "Neuplanung fehlgeschlagen", it) }
        }
    }
}

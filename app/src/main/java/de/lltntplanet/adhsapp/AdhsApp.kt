package de.lltntplanet.adhsapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionClient
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionRouter
import de.lltntplanet.adhsapp.data.nutrition.GeminiFoodVisionClient
import de.lltntplanet.adhsapp.data.nutrition.HealthConnectNutrition
import de.lltntplanet.adhsapp.data.nutrition.NutritionRepository
import de.lltntplanet.adhsapp.data.routines.MedicationCatalog
import de.lltntplanet.adhsapp.data.routines.RoutineRepository
import de.lltntplanet.adhsapp.data.settings.SecretStore
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.data.sync.DataRefresher
import de.lltntplanet.adhsapp.data.sync.RefreshWorker
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
        RefreshWorker.ensureScheduled(this)
        container.requestReschedule()
        // Sichtbar: sofort und dann alle 5 Minuten aktualisieren; im Hintergrund übernimmt der RefreshWorker.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.refresher.startForegroundLoop()
                override fun onStop(owner: LifecycleOwner) = container.refresher.stopForegroundLoop()
            },
        )
    }
}

/** Einfache manuelle Abhängigkeitsverwaltung. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    /** Zuletzt bekannte Einstellungen ohne Warten (für synchrone Abfragen). */
    val settingsState: StateFlow<AppSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val db = AppDatabase.create(app)
    val calendar = CalendarRepository(app)
    val birthdays = BirthdayRepository(app, db.birthdayDao(), calendar, settings)
    val tasks = TaskRepository(db.taskDao()) { requestReschedule() }
    val routines = RoutineRepository(db.routineDao()) { requestReschedule() }
    /** Eingebaute Liste gängiger Medikamente in Deutschland für Vorschläge beim Eintippen. */
    val medications = MedicationCatalog(app)
    val healthConnect = HealthConnectNutrition(app)
    val nutrition = NutritionRepository(db.foodDao(), healthConnect, settings)

    val auth = GoogleAuth(app, settings)
    private val http = GoogleHttp(GoogleAuthTokenProvider(auth))
    val health = HealthRepository(HealthApi(http), settings, auth)
    val tasksSync = GoogleTasksSync(GoogleTasksApi(http), db.taskDao(), settings) { requestReschedule() }

    val notifier = ReminderNotifier(app)
    val reminderEngine = ReminderEngine(app, calendar, birthdays, db.taskDao(), db.routineDao(), db.reminderDao(), settings)
    val refresher = DataRefresher(settings, health, tasksSync, reminderEngine, scope)

    val secrets = SecretStore(app)
    val updateNotifier = UpdateNotifier(app)
    val foodVision = FoodVisionRouter(
        provider = { settingsState.value.foodVisionProvider },
        gemini = GeminiFoodVisionClient({ secrets.geminiApiKey.value }),
        claude = FoodVisionClient({ secrets.anthropicApiKey.value }),
    )
    /** Text, der per „Teilen“ aus einer Chat-App an die App geschickt wurde (Kalorien-Antwort); die Seite „Essen“ holt ihn ab. */
    val sharedChatAnswer = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
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

package de.lltntplanet.adhsapp.data.sync

import android.util.Log
import de.lltntplanet.adhsapp.data.google.GoogleTasksSync
import de.lltntplanet.adhsapp.data.health.HealthRepository
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.reminders.ReminderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ergebnis eines Aktualisierungslaufs; `null` bei den Fehlern heißt: hat geklappt oder war nicht nötig. */
data class RefreshResult(
    val finishedAt: Long,
    val userInitiated: Boolean,
    val healthError: Throwable? = null,
    val tasksError: Throwable? = null,
)

/**
 * Lädt alle Daten zentral neu: Google Health, Google Tasks, Termine/Geburtstage (über [changes]) und die
 * Erinnerungsplanung. Wird vom 5-Minuten-Hintergrundjob, beim Öffnen der App und beim Herunterziehen genutzt.
 * Läuft höchstens einmal gleichzeitig.
 */
class DataRefresher(
    private val settings: SettingsRepository,
    private val health: HealthRepository,
    private val tasksSync: GoogleTasksSync,
    private val reminderEngine: ReminderEngine,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Zähler, der nach jedem Lauf hochgeht – Bildschirme lesen dann Kalender und Kontakte neu. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _results = MutableSharedFlow<RefreshResult>(extraBufferCapacity = 8)
    val results: SharedFlow<RefreshResult> = _results.asSharedFlow()

    @Volatile
    var lastRun: Long = 0L
        private set

    private var foregroundLoop: Job? = null

    /** Alles aktualisieren. Läuft bereits eine Aktualisierung, wartet der Aufruf auf deren Ende. */
    suspend fun refreshAll(userInitiated: Boolean = false): RefreshResult = mutex.withLock {
        _refreshing.value = true
        try {
            val s = settings.current()
            coroutineScope {
                val healthJob = async {
                    if (!s.healthConnected) null else runCatching { health.refresh() }.exceptionOrNull()
                }
                val tasksJob = async {
                    if (!s.tasksConnected) null else runCatching { tasksSync.sync() }.exceptionOrNull()
                }
                val healthError = healthJob.await()?.also { Log.w(TAG, "Google Health konnte nicht geladen werden", it) }
                val tasksError = tasksJob.await()?.also { Log.w(TAG, "Google Tasks konnten nicht abgeglichen werden", it) }
                runCatching { reminderEngine.reschedule() }.onFailure { Log.e(TAG, "Neuplanung fehlgeschlagen", it) }
                lastRun = System.currentTimeMillis()
                _changes.value++
                RefreshResult(lastRun, userInitiated, healthError, tasksError).also { _results.tryEmit(it) }
            }
        } finally {
            _refreshing.value = false
        }
    }

    /** Im Hintergrund anstoßen (z. B. beim Öffnen der App). */
    fun refreshLater(userInitiated: Boolean = false) {
        scope.launch { runCatching { refreshAll(userInitiated) } }
    }

    /**
     * Solange die App sichtbar ist: sofort aktualisieren und danach alle [INTERVAL_MS] erneut.
     * Mehrfaches Starten ersetzt die laufende Schleife.
     */
    @Synchronized
    fun startForegroundLoop() {
        foregroundLoop?.cancel()
        foregroundLoop = scope.launch {
            while (isActive) {
                runCatching { refreshAll() }
                delay(INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stopForegroundLoop() {
        foregroundLoop?.cancel()
        foregroundLoop = null
    }

    companion object {
        private const val TAG = "DataRefresher"
        const val INTERVAL_MINUTES = 5L
        const val INTERVAL_MS = INTERVAL_MINUTES * 60_000L
    }
}

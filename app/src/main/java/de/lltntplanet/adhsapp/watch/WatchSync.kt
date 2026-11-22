package de.lltntplanet.adhsapp.watch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdayRepository
import de.lltntplanet.adhsapp.data.calendar.CalendarRepository
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.FoodDao
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.RoutineDao
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskDao
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.health.HealthRepository
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hält die Übersicht auf der Uhr aktuell: liest die Daten der App (nur lokal Gespeichertes – keine Netzabfragen),
 * baut daraus die kompakte Übersicht ([WatchSnapshotBuilder]) und legt sie für die Uhr ab. Geschrieben wird nur,
 * wenn sich der Inhalt geändert hat, wenn es ausdrücklich verlangt ist oder wenn die letzte Übergabe zu lange her ist.
 *
 * Grundsatz wie bei [WatchLink]: Nichts hier darf die App aufhalten oder abstürzen lassen. Auf Handys ohne Uhr tut
 * [requestSync] gar nichts.
 */
class WatchSync(
    context: Context,
    private val scope: CoroutineScope,
    private val watch: WatchLink,
    private val settings: SettingsRepository,
    private val calendar: CalendarRepository,
    private val birthdays: BirthdayRepository,
    private val health: HealthRepository,
    private val taskDao: TaskDao,
    private val routineDao: RoutineDao,
    private val foodDao: FoodDao,
) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Es läuft immer nur ein Abgleich gleichzeitig. */
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /** Der entprellte, noch wartende Abgleich. */
    private var pending: Job? = null

    /** Können sich die Geburtstage geändert haben? Sie zu lesen ist teuer (Kontakte und ein Jahr Kalender). */
    @Volatile
    private var birthdaysStale = true

    /** Zuletzt gelesene Geburtstage; nur innerhalb von [mutex] benutzt. */
    private var cachedBirthdays: List<Birthday>? = null

    /** Übersicht bald (entprellt) neu an die Uhr geben. */
    fun requestSync() {
        schedule(everything = true)
    }

    /**
     * Übersicht sofort neu erzeugen und übergeben (auch wenn unverändert) – für den Knopf in den Einstellungen;
     * kehrt sofort zurück.
     */
    fun syncNow() {
        launchSync(force = true)
    }

    /**
     * Gleicht sofort ab und wartet höchstens [timeoutMillis] auf das Ende – für Stellen im Hintergrund (Worker,
     * Empfänger, Listener-Dienst), nach deren Ende Android den Prozess schlafen legen darf. Der Abgleich selbst läuft
     * in jedem Fall zu Ende. Mit [force] wird auch Unverändertes neu übergeben; ohne [force] passiert ohne Uhr nichts.
     */
    suspend fun syncAndWait(force: Boolean = false, timeoutMillis: Long = WAIT_MILLIS) {
        if (!force && !watch.isWatchExpected()) return
        birthdaysStale = true
        val job = launchSync(force)
        withTimeoutOrNull(timeoutMillis) { job.join() }
    }

    /**
     * Beobachtet, was sich außerhalb der Erinnerungs-Planung ändert und auf der Uhr zu sehen ist: die Verbindung zur
     * Uhr, Einstellungen, Gesundheitsdaten und Essens-Einträge. Einmal beim App-Start aufrufen.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Die Uhr-App wurde zum ersten Mal gefunden oder die Uhr ist wieder verbunden.
        observe(watch.status.map { it.watchName != null && it.reachable }.distinctUntilChanged().filter { it }) {
            requestSync()
        }
        // Einstellungen, die in der Übersicht stecken (sichtbare Seiten, Klingel-Auswahl, ausgeblendete Kalender …).
        observe(settings.settings.map { relevant(it) }.distinctUntilChanged().drop(1)) { requestSync() }
        // Neue Gesundheitsdaten: Der Zwischenspeicher ändert sich nach jedem Laden.
        observe(settings.healthCache.distinctUntilChanged().drop(1)) { schedule(everything = false) }
        // Essen eingetragen, geändert oder gelöscht: Room meldet jede Änderung an der Tabelle – egal für welchen Tag.
        observe(foodDao.observeDay(LocalDate.now().toEpochDay()).drop(1)) { schedule(everything = false) }
    }

    private fun <T> observe(changes: Flow<T>, onChange: () -> Unit) {
        scope.launch {
            changes
                .catch { Log.w(TAG, "Änderungen für die Uhr lassen sich nicht mehr beobachten", it) }
                .collect { onChange() }
        }
    }

    /** Die Einstellungen, die in der Übersicht stecken – nur ihre Änderung stößt einen Abgleich an. */
    private fun relevant(s: AppSettings): List<Any?> = listOf(
        s.ringTarget,
        s.watchHiddenPages,
        s.hiddenCalendars,
        s.birthdaysFromCalendar,
        s.healthConnected,
        s.stepGoal,
        s.sex,
        s.birthYear,
        s.heightCm,
        s.weightKg,
        s.calorieAdjustment,
    )

    /**
     * Abgleich nach kurzer Wartezeit; schnelle Folgen werden zusammengefasst. [everything] = auch die Geburtstage
     * können sich geändert haben.
     */
    @Synchronized
    private fun schedule(everything: Boolean) {
        if (everything) birthdaysStale = true
        pending?.cancel()
        pending = scope.launch {
            // Ohne Uhr gibt es nichts abzugleichen: Handys ohne Uhr tun hier gar nichts.
            if (!watch.isWatchExpected()) return@launch
            delay(DEBOUNCE_MILLIS)
            launchSync(force = false)
        }
    }

    /** Ein Abgleich beginnt gerade und liest alles neu – der wartende ist damit überflüssig. */
    @Synchronized
    private fun dropPending() {
        pending?.cancel()
        pending = null
    }

    /** Startet den Abgleich im Hintergrund. Er hängt nicht am Aufrufer und läuft zu Ende. */
    private fun launchSync(force: Boolean): Job = scope.launch { publish(force) }

    /** Erzeugt die Übersicht und übergibt sie, falls nötig. true = übergeben. Wirft nichts außer einem Abbruch. */
    private suspend fun publish(force: Boolean): Boolean = mutex.withLock {
        try {
            dropPending()
            val snapshot = WatchSnapshotBuilder.fit(WatchSnapshotBuilder.build(load(force)))
            val now = snapshot.generatedAt
            val hash = WatchSnapshotBuilder.contentHash(snapshot)
            if (!needsPut(force, hash, prefs.getString(KEY_HASH, null), now, prefs.getLong(KEY_PUT_AT, 0L))) {
                return@withLock false
            }
            val bytes = WatchSnapshotBuilder.encode(snapshot)
            if (bytes.size > WatchSnapshotBuilder.MAX_BYTES) {
                Log.w(TAG, "Übersicht für die Uhr ist zu groß (${bytes.size} Bytes) – nicht übergeben")
                return@withLock false
            }
            if (!watch.putSnapshot(bytes)) return@withLock false
            prefs.edit { putString(KEY_HASH, hash).putLong(KEY_PUT_AT, now) }
            watch.markSynced(now)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Übersicht für die Uhr konnte nicht erzeugt werden", e)
            false
        }
    }

    /**
     * Liest alles, was in die Übersicht kommt. Fehlt eine Berechtigung oder scheitert eine Quelle, bleibt ihr Teil
     * leer – der Rest kommt trotzdem auf die Uhr.
     */
    private suspend fun load(reloadEverything: Boolean): WatchSnapshotInput {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Dates.toLocalDate(now, zone)
        val s = settings.current()
        val calendarAllowed = calendar.hasReadPermission()
        val lastEventDay = WatchSnapshotBuilder.lastEventDay(today, s.watchHiddenPages)
        val events: List<EventInstance> = if (calendarAllowed) {
            read("Termine", emptyList()) { calendar.instances(today, lastEventDay.plusDays(1), zone) }
        } else {
            emptyList()
        }
        val routines: List<RoutineEntity> = read("Routinen", emptyList()) { routineDao.observeAll().first() }
        val routineLog: List<RoutineLogEntity> = read("Abgehakte Routinen", emptyList()) {
            routineDao.log(today.toEpochDay(), today.plusDays(1).toEpochDay())
        }
        val tasks: List<TaskEntity> = read("Aufgaben", emptyList()) { taskDao.open() }
        val healthData: HealthSnapshot? = if (s.healthConnected) {
            read("Gesundheitsdaten", null) { health.snapshot.first() }
        } else {
            null
        }
        val food: List<FoodEntryEntity> = if (WatchPage.NUTRITION.id !in s.watchHiddenPages) {
            read("Essen", emptyList()) { foodDao.forDay(today.toEpochDay()) }
        } else {
            emptyList()
        }
        return WatchSnapshotInput(
            now = now,
            zone = zone,
            settings = s,
            calendarAvailable = calendarAllowed,
            events = events,
            routines = routines,
            routineLog = routineLog,
            tasks = tasks,
            birthdays = loadBirthdays(reloadEverything),
            health = healthData,
            food = food,
        )
    }

    /** Geburtstage nur neu lesen, wenn sie sich geändert haben können – sonst gilt die zuletzt gelesene Liste. */
    private suspend fun loadBirthdays(reload: Boolean): List<Birthday> {
        val cached = cachedBirthdays
        if (cached != null && !reload && !birthdaysStale) return cached
        birthdaysStale = false
        val fresh: List<Birthday> = read("Geburtstage", cached.orEmpty()) { birthdays.all() }
        cachedBirthdays = fresh
        return fresh
    }

    /** Liest eine Quelle. Schlägt das fehl, gilt [fallback] und der Abgleich läuft weiter. */
    private suspend fun <T> read(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what für die Uhr nicht lesbar", e)
        fallback
    }

    companion object {
        private const val TAG = "WatchSync"
        private const val PREFS = "watch_sync"
        private const val KEY_HASH = "content_hash"
        private const val KEY_PUT_AT = "put_at"

        /** So lange sammelt [requestSync] schnelle Folgen von Änderungen. */
        private const val DEBOUNCE_MILLIS = 2_000L

        /** So lange wartet [syncAndWait] normalerweise auf das Ende des Abgleichs (Worker). */
        const val WAIT_MILLIS = 10_000L

        /** Kurze Wartezeit für Empfänger und den Listener-Dienst, die selbst schnell fertig sein müssen. */
        const val BRIEF_WAIT_MILLIS = 3_000L

        /**
         * Nach dieser Zeit wird auch eine unveränderte Übersicht neu übergeben. Die Uhr zeigt eine Übersicht nach
         * [WatchSnapshotLogic.STALE_MILLIS] als veraltet an – so weit soll es nur kommen, wenn das Handy wirklich
         * nicht erreichbar ist.
         */
        const val REFRESH_MILLIS = WatchSnapshotLogic.STALE_MILLIS / 3

        /**
         * Muss die Übersicht neu an die Uhr? Ja, wenn es ausdrücklich verlangt ist ([force]), wenn sich der Inhalt
         * geändert hat ([hash] gegenüber [lastHash]) oder wenn die letzte Übergabe ([lastPutAt]) zu lange her ist.
         */
        fun needsPut(force: Boolean, hash: String, lastHash: String?, now: Long, lastPutAt: Long): Boolean {
            if (force || hash != lastHash) return true
            val age = now - lastPutAt
            // Eine Übergabe „in der Zukunft“ heißt: Die Uhrzeit des Handys wurde verstellt.
            return age < 0 || age >= REFRESH_MILLIS
        }
    }
}

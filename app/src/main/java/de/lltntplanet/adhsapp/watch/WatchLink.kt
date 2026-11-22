package de.lltntplanet.adhsapp.watch

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.lltntplanet.adhsapp.shared.CancelRequest
import de.lltntplanet.adhsapp.shared.RingAck
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.WatchInfo
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/** Was die Handy-App über die Uhr weiß – für die Anzeige in den Einstellungen. */
data class WatchStatus(
    /** Wurde schon geprüft? (Vorher zeigt die Oberfläche „Suche Uhr …“.) */
    val checked: Boolean = false,
    /** Ist der Wear-Dienst auf diesem Handy nutzbar (Google-Dienste vorhanden, Uhr gekoppelt)? */
    val available: Boolean = false,
    /** Name der Uhr, auf der die Uhr-App installiert ist; null = keine gefunden. */
    val watchName: String? = null,
    /** Ist diese Uhr gerade verbunden? */
    val reachable: Boolean = false,
    /** Zuletzt gemeldeter Zustand der Uhr-App (Version, Berechtigungen); null = noch nichts gemeldet. */
    val info: WatchInfo? = null,
    /** Wann die Übersicht zuletzt an die Uhr übergeben wurde (0 = noch nie). */
    val lastSyncAt: Long = 0,
)

/**
 * Der einzige Zugang der Handy-App zum Wearable Data Layer, also zur Uhr-App.
 *
 * Grundsatz: Nichts hier darf die App aufhalten oder abstürzen lassen – auch nicht auf Handys ohne Google-Dienste
 * oder ohne Uhr. Jeder Aufruf prüft zuerst, ob die Google-Dienste nutzbar sind, hat ein eigenes Zeitlimit und fängt
 * alle Fehler ab. Der Konstruktor selbst ruft die Wearable-API nicht auf.
 */
class WatchLink(context: Context, private val scope: CoroutineScope) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _status = MutableStateFlow(WatchStatus())

    /** Was über die Uhr bekannt ist. Vor dem ersten [refreshStatus] ist [WatchStatus.checked] false. */
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var playServices: Boolean? = null

    @Volatile
    private var playServicesCheckedAt = 0L

    @Volatile
    private var knownCache: Boolean? = null

    /**
     * Wurde irgendwann schon eine Uhr mit der Uhr-App gesehen? Bleibt dauerhaft gemerkt. Zweck: Handys ohne Uhr
     * sollen beim Alarm nicht spürbar auf die Suche warten.
     */
    private val watchAppKnown: Boolean
        get() = knownCache
            ?: runCatching { prefs.getBoolean(KEY_WATCH_APP_KNOWN, false) }.getOrDefault(false).also { knownCache = it }

    /** Wie lange beim Alarm höchstens nach der Uhr gesucht wird. */
    private val alarmSearchMillis: Long get() = if (watchAppKnown) SEARCH_MILLIS else SEARCH_UNKNOWN_MILLIS

    /** Eine Uhr mit der Uhr-App wurde gefunden oder hat sich gemeldet – dauerhaft merken. */
    fun noteWatchSeen() {
        if (watchAppKnown) return
        knownCache = true
        runCatching { prefs.edit { putBoolean(KEY_WATCH_APP_KNOWN, true) } }
    }

    /**
     * Sucht die verbundene Uhr mit installierter Uhr-App; eine direkt (per Bluetooth) verbundene wird bevorzugt.
     * `null`, wenn keine erreichbar ist oder die Suche länger als [timeoutMillis] dauert.
     */
    suspend fun watchNode(timeoutMillis: Long = SEARCH_MILLIS): Node? {
        val nodes = guarded(timeoutMillis, "Uhr suchen") {
            Wearable.getCapabilityClient(appContext)
                .getCapability(WatchPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .toList()
        } ?: return null
        if (nodes.isNotEmpty()) noteWatchSeen()
        return pickNode(nodes)
    }

    /**
     * Bittet die Uhr, die Erinnerungen jetzt anzuzeigen und zu klingeln, und wartet höchstens [timeoutMillis] auf
     * ihre Antwort. `null` bei jedem Fehler, jeder Zeitüberschreitung und jeder ungültigen Antwort – der Aufrufer
     * lässt dann das Handy klingeln. War eine Uhr gefunden, wird die Anfrage in diesem Fall vorsorglich
     * zurückgenommen, damit die Uhr nicht verspätet nachklingelt.
     */
    suspend fun ring(request: RingRequest, timeoutMillis: Long): RingAck? {
        if (request.reminders.isEmpty()) return null
        val node = watchNode(alarmSearchMillis) ?: return null
        var ack: RingAck? = null
        try {
            ack = guarded(timeoutMillis, "Erinnerung an die Uhr senden") {
                val bytes = WatchJson.encodeToString(request).encodeToByteArray()
                val reply: ByteArray? = Wearable.getMessageClient(appContext).sendRequest(node.id, WatchPaths.RING, bytes).await()
                reply?.let { WatchJson.decodeFromString<RingAck>(it.decodeToString()) }
            }?.takeIf { it.requestId == request.requestId }
        } finally {
            if (ack == null) {
                val originKeys = request.reminders.map { it.originKey }
                scope.launch { sendCancel(node.id, originKeys) }
            }
        }
        return ack
    }

    /**
     * Erinnerungen auf der Uhr wieder entfernen (am Handy bestätigt, abgehakt oder vom Spender gemeldet).
     * Arbeitet im Hintergrund und kehrt sofort zurück; ohne erreichbare Uhr passiert nichts.
     * Eine leere Liste entfernt – wie im Protokoll – alle Erinnerungen auf der Uhr.
     */
    fun cancel(originKeys: List<String>) {
        scope.launch {
            // Ohne je gesehene Uhr-App gibt es dort nichts zu entfernen: Handys ohne Uhr tun hier gar nichts.
            if (!watchAppKnown) return@launch
            val node = watchNode(SEARCH_MILLIS) ?: return@launch
            sendCancel(node.id, originKeys)
        }
    }

    /**
     * Legt die Übersicht für die Uhr als Datenelement ab ([WatchPaths.SNAPSHOT]). Das System liefert sie nach,
     * sobald die Uhr verbunden ist. true = übergeben.
     */
    suspend fun putSnapshot(bytes: ByteArray): Boolean = guarded(PUT_MILLIS, "Übersicht an die Uhr geben") {
        val request = PutDataRequest.create(WatchPaths.SNAPSHOT).setData(bytes).setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request).await()
        true
    } == true

    /** Merkt sich, wann die Übersicht zuletzt an die Uhr übergeben wurde (Anzeige in den Einstellungen). */
    fun markSynced(at: Long = System.currentTimeMillis()) {
        _status.update { it.copy(lastSyncAt = at) }
        runCatching { prefs.edit { putLong(KEY_LAST_SYNC_AT, at) } }
    }

    /** Status neu ermitteln (Capability abfragen, Uhr-Info lesen); kehrt sofort zurück. */
    fun refreshStatus() {
        scope.launch {
            runCatching { refreshMutex.withLock { loadStatus() } }
                .onFailure { Log.w(TAG, "Zustand der Uhr konnte nicht ermittelt werden", it) }
        }
    }

    /** Die Uhr-App hat ihren Zustand gemeldet (Datenelement [WatchPaths.WATCH_INFO]). */
    fun onWatchInfo(info: WatchInfo) {
        noteWatchSeen()
        _status.update { old ->
            val known = old.info
            if (known != null && known.updatedAt > info.updatedAt) old else old.copy(info = info)
        }
    }

    /** Eine Uhr mit der Uhr-App ist dazugekommen oder weggefallen (meldet der Listener-Dienst). */
    fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == WatchPaths.CAPABILITY_WATCH && info.nodes.isNotEmpty()) noteWatchSeen()
        refreshStatus()
    }

    private suspend fun loadStatus() {
        val lastSync = runCatching { prefs.getLong(KEY_LAST_SYNC_AT, 0L) }.getOrDefault(0L)
        if (!playServicesUsable(recheck = true)) {
            _status.update { WatchStatus(checked = true, lastSyncAt = maxOf(it.lastSyncAt, lastSync)) }
            return
        }
        // FILTER_ALL: jede Uhr mit der Uhr-App, auch wenn sie gerade nicht verbunden ist.
        val installed = capableNodes(CapabilityClient.FILTER_ALL)
        val reachable = if (installed == null) null else capableNodes(CapabilityClient.FILTER_REACHABLE)
        val connected = pickNode(reachable.orEmpty())
        val watch = connected ?: pickNode(installed.orEmpty())
        if (watch != null) noteWatchSeen()
        val infos = if (watch == null) emptyList() else readWatchInfos()
        val info = (infos.firstOrNull { it.first == watch?.id } ?: infos.maxByOrNull { it.second.updatedAt })?.second
        _status.update { old ->
            WatchStatus(
                checked = true,
                available = installed != null,
                watchName = watch?.displayName,
                reachable = connected != null,
                // Ohne Uhr-App gilt auch ihr zuletzt gemeldeter Zustand nicht mehr.
                info = if (watch == null) null else info ?: old.info,
                lastSyncAt = maxOf(old.lastSyncAt, lastSync),
            )
        }
    }

    /** Uhren mit der Uhr-App; `null`, wenn der Wear-Dienst auf diesem Handy nicht antwortet. */
    private suspend fun capableNodes(nodeFilter: Int): List<Node>? = guarded(STATUS_MILLIS, "Uhr-App suchen") {
        Wearable.getCapabilityClient(appContext)
            .getCapability(WatchPaths.CAPABILITY_WATCH, nodeFilter)
            .await()
            .nodes
            .toList()
    }

    /** Liest das Datenelement mit dem Zustand der Uhr-App – je Uhr eines (Knoten-ID der Uhr → Zustand). */
    private suspend fun readWatchInfos(): List<Pair<String?, WatchInfo>> = guarded(STATUS_MILLIS, "Zustand der Uhr-App lesen") {
        val uri = "wear://*${WatchPaths.WATCH_INFO}".toUri()
        // Den Puffer nach dem Lesen wieder freigeben (use); die dekodierten Werte hängen nicht an ihm.
        Wearable.getDataClient(appContext).getDataItems(uri, DataClient.FILTER_LITERAL).await().use { buffer ->
            buffer.mapNotNull { item ->
                val info = item.data?.let { bytes ->
                    runCatching { WatchJson.decodeFromString<WatchInfo>(bytes.decodeToString()) }.getOrNull()
                }
                info?.let { item.uri.host to it }
            }
        }
    }.orEmpty()

    private suspend fun sendCancel(nodeId: String, originKeys: List<String>) {
        guarded(CANCEL_MILLIS, "Erinnerung auf der Uhr entfernen") {
            val bytes = WatchJson.encodeToString(CancelRequest(originKeys)).encodeToByteArray()
            Wearable.getMessageClient(appContext).sendMessage(nodeId, WatchPaths.CANCEL, bytes).await()
        }
    }

    private fun pickNode(nodes: List<Node>): Node? = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

    /**
     * Sind die Google-Dienste nutzbar? Das Ergebnis wird gemerkt: „ja“ dauerhaft, „nein“ für einige Minuten (sie
     * könnten gerade aktualisiert werden). [recheck] fragt in jedem Fall neu.
     */
    private fun playServicesUsable(recheck: Boolean = false): Boolean {
        val known = playServices
        val now = SystemClock.elapsedRealtime()
        if (!recheck && known != null && (known || now - playServicesCheckedAt < PLAY_SERVICES_RECHECK_MILLIS)) return known
        val usable = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        playServices = usable
        playServicesCheckedAt = now
        return usable
    }

    /**
     * Führt einen Aufruf der Wearable-API abgesichert aus: nur mit nutzbaren Google-Diensten, höchstens
     * [timeoutMillis] lang, und jeder Fehler ergibt `null` – auch eine ApiException, etwa Status 17, wenn es den
     * Wear-Dienst auf diesem Handy nicht gibt. Nur ein Abbruch des Aufrufers selbst wird weitergereicht.
     */
    private suspend fun <T : Any> guarded(timeoutMillis: Long, what: String, block: suspend () -> T?): T? {
        if (!playServicesUsable()) return null
        return try {
            withTimeoutOrNull(timeoutMillis) { block() }
        } catch (e: CancellationException) {
            // Wurde der Aufrufer abgebrochen, geht das weiter; ein abgebrochener Aufruf der Google-Dienste ist nur ein Fehlschlag.
            currentCoroutineContext().ensureActive()
            null
        } catch (e: ApiException) {
            Log.i(TAG, "$what: nicht möglich (Status ${e.statusCode})")
            null
        } catch (e: Exception) {
            Log.w(TAG, "$what: fehlgeschlagen", e)
            null
        }
    }

    companion object {
        private const val TAG = "WatchLink"
        private const val PREFS = "watch_link"
        private const val KEY_WATCH_APP_KNOWN = "watch_app_known"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"

        /** Suche nach der Uhr, wenn die Uhr-App schon einmal gesehen wurde. */
        private const val SEARCH_MILLIS = 1_000L

        /** Suche beim Alarm, solange noch nie eine Uhr-App gesehen wurde – so kurz, dass man es nicht merkt. */
        private const val SEARCH_UNKNOWN_MILLIS = 400L
        private const val CANCEL_MILLIS = 3_000L
        private const val STATUS_MILLIS = 5_000L
        private const val PUT_MILLIS = 8_000L
        private const val PLAY_SERVICES_RECHECK_MILLIS = 10L * 60_000L
    }
}

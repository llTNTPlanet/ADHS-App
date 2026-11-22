package de.lltntplanet.adhsapp.wear.link

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchPaths
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Reine Regeln rund um die Übersicht – ohne Android, damit sie sich testen lassen. */
object SnapshotLogic {

    /** So weit darf der Zeitstempel einer Übersicht in der Zukunft liegen, bevor er als falsch gilt. */
    const val FUTURE_TOLERANCE_MILLIS = 10L * 60 * 1000

    /** Liest eine Übersicht aus den Bytes des Datenelements. `null`, wenn sie leer oder unlesbar sind. */
    fun decode(bytes: ByteArray?): WatchSnapshot? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching { WatchJson.decodeFromString<WatchSnapshot>(bytes.decodeToString()) }.getOrNull()
    }

    /**
     * Ersetzt [incoming] die bisherige Übersicht [current]? Die später erzeugte gewinnt. Ausnahme: Liegt die
     * bisherige unmöglich weit in der Zukunft (die Uhrzeit des Handys war falsch), wird sie von einer mit
     * glaubhaftem Zeitstempel ersetzt – sonst bliebe die Uhr auf dem falschen Stand hängen.
     */
    fun isNewer(current: WatchSnapshot?, incoming: WatchSnapshot, now: Long): Boolean {
        if (current == null) return true
        if (incoming.generatedAt > current.generatedAt) return true
        val limit = now + FUTURE_TOLERANCE_MILLIS
        return current.generatedAt > limit && incoming.generatedAt <= limit
    }

    /**
     * Die zuletzt erzeugte lesbare Übersicht aus mehreren Datenelementen (falls mehrere Handys eine abgelegt
     * haben) – zusammen mit ihren Bytes. `null`, wenn keine lesbar ist.
     */
    fun newest(candidates: List<ByteArray>): Pair<WatchSnapshot, ByteArray>? =
        candidates
            .mapNotNull { bytes -> decode(bytes)?.let { snapshot -> snapshot to bytes } }
            .maxByOrNull { it.first.generatedAt }
}

/**
 * Die Übersicht vom Handy ([WatchSnapshot]). Sie kommt als Datenelement über die Verbindung; die Uhr merkt sich
 * die letzte zusätzlich selbst, damit die App beim Öffnen sofort etwas zeigt – auch ohne Handy in der Nähe.
 */
class SnapshotRepository(appContext: Context, private val scope: CoroutineScope) {

    private val context: Context = appContext.applicationContext
    private val store = AtomicFile(File(context.filesDir, FILE_NAME))
    private val lock = Any()

    /** Der letzte Stand, den das Handy geschickt hat (ohne Änderungen von [update]). Nur unter [lock] ändern. */
    private var confirmed: WatchSnapshot? = null

    private val _snapshot = MutableStateFlow<WatchSnapshot?>(null)

    /** Die aktuelle Übersicht. `null`, solange noch keine vom Handy angekommen ist. */
    val snapshot: StateFlow<WatchSnapshot?> = _snapshot.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** `true`, sobald die gemerkte Übersicht gelesen ist. Ist [snapshot] dann noch `null`, gibt es noch keine. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            SnapshotLogic.decode(readStored())?.let { take(it, bytes = null) }
            _loaded.value = true
            refresh()
        }
    }

    /**
     * Neue Übersicht vom Handy (Bytes des Datenelements). `true`, wenn sie übernommen wurde – also lesbar und
     * neuer als die bisherige ist. Schreibt eine Datei: nicht auf dem Haupt-Thread aufrufen.
     */
    fun accept(bytes: ByteArray): Boolean {
        val incoming = SnapshotLogic.decode(bytes) ?: return false
        return take(incoming, bytes)
    }

    /**
     * Liest die Übersicht direkt aus dem Speicher der Verbindung – für den Fall, dass eine Änderung verpasst
     * wurde. `true`, wenn dabei eine neuere gefunden wurde.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val found = try {
            withTimeoutOrNull(REFRESH_MILLIS) {
                val uri = Uri.parse("wear://*" + WatchPaths.SNAPSHOT)
                // Den Puffer nach dem Lesen wieder freigeben (use); die kopierten Bytes hängen nicht an ihm.
                Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL).await().use { buffer ->
                    buffer.mapNotNull { item -> item.data?.copyOf() }
                }
            }
        } catch (e: CancellationException) {
            // Nur weiterreichen, wenn wirklich der Aufrufer abgebrochen wurde.
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Übersicht konnte nicht gelesen werden", e)
            null
        }
        val newest = found?.let { SnapshotLogic.newest(it) }
        if (newest == null) false else take(newest.first, newest.second)
    }

    /**
     * Ändert die angezeigte Übersicht sofort (z. B. einen Haken setzen), ohne auf das Handy zu warten. Die
     * nächste Übersicht vom Handy ersetzt die Änderung; schlägt das Senden fehl: [discardLocalChanges].
     */
    fun update(transform: (WatchSnapshot) -> WatchSnapshot) {
        synchronized(lock) {
            val current = _snapshot.value ?: return
            _snapshot.value = transform(current)
        }
    }

    /** Zurück zum letzten Stand vom Handy. */
    fun discardLocalChanges() {
        synchronized(lock) { _snapshot.value = confirmed }
    }

    private fun take(incoming: WatchSnapshot, bytes: ByteArray?): Boolean = synchronized(lock) {
        if (SnapshotLogic.isNewer(confirmed, incoming, System.currentTimeMillis())) {
            confirmed = incoming
            _snapshot.value = incoming
            if (bytes != null) persist(bytes)
            true
        } else {
            false
        }
    }

    private fun readStored(): ByteArray? = try {
        store.readFully()
    } catch (e: Exception) {
        // Beim ersten Start gibt es noch keine gemerkte Übersicht.
        null
    }

    private fun persist(bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = store.startWrite()
            stream.write(bytes)
            store.finishWrite(stream)
        } catch (e: Exception) {
            Log.w(TAG, "Übersicht konnte nicht gemerkt werden", e)
            stream?.let { store.failWrite(it) }
        }
    }

    private companion object {
        const val TAG = "SnapshotRepository"
        const val FILE_NAME = "snapshot.json"
        const val REFRESH_MILLIS = 5_000L
    }
}

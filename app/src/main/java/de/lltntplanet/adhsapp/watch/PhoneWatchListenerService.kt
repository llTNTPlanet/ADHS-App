package de.lltntplanet.adhsapp.watch

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.lltntplanet.adhsapp.AdhsApp
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.shared.WatchInfo
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchPaths
import de.lltntplanet.adhsapp.shared.WatchReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nimmt entgegen, was die Uhr-App schickt – auch wenn die Handy-App gerade nicht läuft:
 * - Knöpfe einer Erinnerung und Haken in der Übersicht (Anfragen mit Antwort „ausgeführt“ oder „fehlgeschlagen“),
 * - die Bitte, die Übersicht neu zu schicken,
 * - den Zustand der Uhr-App (Version, Berechtigungen) für die Einstellungen,
 * - dass eine Uhr mit der Uhr-App dazugekommen oder weggefallen ist.
 */
class PhoneWatchListenerService : WearableListenerService() {

    /** `null`, falls der Dienst ausnahmsweise ohne fertige App läuft – dann passiert nichts. */
    private val container: AppContainer?
        get() = runCatching { (application as AdhsApp).container }.getOrNull()

    /**
     * Anfrage der Uhr. Die Antwort muss sofort fertig sein; der Aufruf kommt auf einem Hintergrund-Thread des
     * Dienstes, dort darf kurz gewartet werden – aber sicher kürzer, als die Uhr auf die Antwort wartet
     * ([WatchReplyBudget]).
     */
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WatchPaths.REMINDER_ACTION && path != WatchPaths.ITEM_ACTION) return super.onRequest(nodeId, path, request)
        val c = container ?: return Tasks.forResult(WatchReply.FAILED)
        // Wer so fragt, ist die Uhr-App: Ab jetzt lohnt es sich, beim Alarm auf die Uhr zu warten.
        c.watch.noteWatchSeen()
        // Kalter Start: Seit dem Start des Prozesses wartet die Uhr schon – diese Zeit zählt zum Zeitrahmen mit.
        val now = SystemClock.elapsedRealtime()
        val startedAt = now - WatchReplyBudget.headStartMillis(now - Process.getStartElapsedRealtime())
        // Die Aktion läuft im Hintergrund der App immer zu Ende. Begrenzt ist nur das Warten auf ihr Ergebnis: Ein
        // Abbruch mittendrin hieße, dass die Erinnerung schon entfernt, das Schlummern aber nicht gespeichert ist.
        val work = c.scope.async {
            try {
                if (path == WatchPaths.REMINDER_ACTION) WatchActions.reminderAction(c, request)
                else WatchActions.itemAction(c, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Aktion von der Uhr fehlgeschlagen ($path)", e)
                false
            }
        }
        val done = try {
            runBlocking { withTimeoutOrNull(WatchReplyBudget.ACTION_WAIT_MILLIS) { work.await() } } == true
        } catch (e: Exception) {
            Log.w(TAG, "Auf die Aktion von der Uhr konnte nicht gewartet werden ($path)", e)
            false
        }
        // Die Übersicht auf der Uhr soll den neuen Stand gleich zeigen. Darauf wartet die Antwort nur so lange, wie
        // vom Zeitrahmen noch übrig ist – der Abgleich läuft danach im Hintergrund weiter.
        if (done) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            syncOverview(c, force = false, waitMillis = WatchReplyBudget.overviewWaitMillis(elapsed))
        }
        return Tasks.forResult(if (done) WatchReply.OK else WatchReply.FAILED)
    }

    /**
     * Die Uhr-App bittet darum, die Übersicht neu zu schicken ([WatchPaths.SYNC_REQUEST]) – z. B. beim Öffnen.
     * Gewartet wird nur kurz: Alle Rückrufe des Dienstes laufen nacheinander, ein Haken direkt danach soll nicht
     * dahinter warten.
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WatchPaths.SYNC_REQUEST) return
        val c = container ?: return
        c.watch.noteWatchSeen()
        syncOverview(c, force = true, waitMillis = WatchReplyBudget.SYNC_REQUEST_WAIT_MILLIS)
    }

    /** Die Uhr-App hat ihren Zustand neu abgelegt ([WatchPaths.WATCH_INFO]). */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val watch = container?.watch ?: return
        try {
            // Der Puffer gehört dem Dienst und wird von ihm freigegeben – hier nur lesen.
            for (event in dataEvents) watchInfoOf(event)?.let { watch.onWatchInfo(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Zustand der Uhr-App konnte nicht gelesen werden", e)
        }
    }

    /**
     * Eine Uhr mit der Uhr-App ist dazugekommen oder weggefallen. Ist sie (wieder) verbunden, gleicht [WatchSync]
     * die Übersicht von selbst ab.
     */
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        container?.watch?.onCapabilityChanged(capabilityInfo)
    }

    /**
     * Gleicht die Übersicht für die Uhr ab und wartet höchstens [waitMillis] darauf: Nur solange dieser Aufruf läuft,
     * bleibt der Prozess sicher wach. Dauert es länger, läuft der Abgleich im Hintergrund weiter.
     */
    private fun syncOverview(c: AppContainer, force: Boolean, waitMillis: Long) {
        try {
            runBlocking { c.watchSync.syncAndWait(force, waitMillis) }
        } catch (e: Exception) {
            Log.w(TAG, "Übersicht für die Uhr konnte nicht abgeglichen werden", e)
        }
    }

    private fun watchInfoOf(event: DataEvent): WatchInfo? {
        if (event.type != DataEvent.TYPE_CHANGED) return null
        val item = event.dataItem
        if (item.uri.path != WatchPaths.WATCH_INFO) return null
        val bytes = item.data ?: return null
        return runCatching { WatchJson.decodeFromString<WatchInfo>(bytes.decodeToString()) }.getOrNull()
    }

    private companion object {
        const val TAG = "PhoneWatchListener"
    }
}

/**
 * Zeitrahmen für die Antwort an die Uhr – reine Rechnung, damit sie sich testen lässt. Die Uhr wartet 5 s auf die
 * Antwort und meldet danach „Handy nicht erreichbar“. Die Antwort muss sicher vorher fertig sein; Funkweg und ein
 * kalter Start der App brauchen ebenfalls Zeit.
 */
internal object WatchReplyBudget {

    /** So lange wartet die Antwort höchstens auf das Ergebnis der Aktion. Die Aktion selbst läuft danach weiter. */
    const val ACTION_WAIT_MILLIS = 3_500L

    /** Spätestens dann geht die Antwort an die Uhr (Warten auf die Aktion plus Warten auf die neue Übersicht). */
    const val TOTAL_MILLIS = 4_000L

    /** So lange wartet die Antwort höchstens auf die neue Übersicht. */
    const val OVERVIEW_WAIT_MILLIS = 1_500L

    /** So lange hält die Bitte um eine neue Übersicht den Dienst höchstens auf. */
    const val SYNC_REQUEST_WAIT_MILLIS = 1_000L

    /**
     * Wie lange die Antwort noch auf die neue Übersicht warten darf, wenn seit dem Eintreffen der Anfrage schon
     * [elapsedMillis] vergangen sind: nur die Restzeit, höchstens [OVERVIEW_WAIT_MILLIS].
     */
    fun overviewWaitMillis(elapsedMillis: Long): Long =
        (TOTAL_MILLIS - elapsedMillis).coerceIn(0L, OVERVIEW_WAIT_MILLIS)

    /**
     * Kalter Start: Läuft der Prozess erst seit [processAgeMillis] und ist das kürzer als [TOTAL_MILLIS], wurde er
     * sehr wahrscheinlich für diese Anfrage gestartet – so lange wartet die Uhr also schon, und die Zeit zählt zum
     * Zeitrahmen mit. Läuft die App schon länger, zählt nichts mit.
     */
    fun headStartMillis(processAgeMillis: Long): Long =
        if (processAgeMillis in 0L until TOTAL_MILLIS) processAgeMillis else 0L
}

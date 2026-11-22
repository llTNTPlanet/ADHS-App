package de.lltntplanet.adhsapp.watch

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
     * Dienstes, dort darf kurz gewartet werden.
     */
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WatchPaths.REMINDER_ACTION && path != WatchPaths.ITEM_ACTION) return super.onRequest(nodeId, path, request)
        val c = container ?: return Tasks.forResult(WatchReply.FAILED)
        // Wer so fragt, ist die Uhr-App: Ab jetzt lohnt es sich, beim Alarm auf die Uhr zu warten.
        c.watch.noteWatchSeen()
        val done = try {
            runBlocking {
                withTimeoutOrNull(ACTION_MILLIS) {
                    if (path == WatchPaths.REMINDER_ACTION) WatchActions.reminderAction(c, request)
                    else WatchActions.itemAction(c, request)
                }
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "Aktion von der Uhr fehlgeschlagen ($path)", e)
            false
        }
        // Die Übersicht auf der Uhr soll den neuen Stand gleich zeigen. Kurz darauf warten, solange der Dienst noch
        // läuft – die Antwort an die Uhr hält das kaum auf.
        if (done) syncOverview(c, force = false, waitMillis = SYNC_AFTER_ACTION_MILLIS)
        return Tasks.forResult(if (done) WatchReply.OK else WatchReply.FAILED)
    }

    /** Die Uhr-App bittet darum, die Übersicht neu zu schicken ([WatchPaths.SYNC_REQUEST]) – z. B. beim Öffnen. */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WatchPaths.SYNC_REQUEST) return
        val c = container ?: return
        c.watch.noteWatchSeen()
        syncOverview(c, force = true, waitMillis = WatchSync.BRIEF_WAIT_MILLIS)
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

        /** So lange darf eine Aktion der Uhr höchstens dauern, bevor „fehlgeschlagen“ zurückgeht. */
        const val ACTION_MILLIS = 8_000L

        /** So lange wartet die Antwort an die Uhr höchstens auf die neue Übersicht. */
        const val SYNC_AFTER_ACTION_MILLIS = 1_500L
    }
}

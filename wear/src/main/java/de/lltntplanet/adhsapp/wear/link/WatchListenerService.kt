package de.lltntplanet.adhsapp.wear.link

import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.lltntplanet.adhsapp.shared.CancelRequest
import de.lltntplanet.adhsapp.shared.RingAck
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.RingRouting
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchPaths
import de.lltntplanet.adhsapp.wear.WearApp
import de.lltntplanet.adhsapp.wear.WearContainer
import de.lltntplanet.adhsapp.wear.alert.AlertActivity
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Nimmt entgegen, was das Handy schickt – auch wenn die Uhr-App gerade nicht läuft:
 * - „Jetzt klingeln“ (Anfrage mit Antwort, ob die Uhr die Erinnerung wirklich anzeigt),
 * - „Erinnerung entfernen“ (am Handy bestätigt),
 * - eine neue Übersicht.
 */
class WatchListenerService : WearableListenerService() {

    private val container: WearContainer get() = (application as WearApp).container

    /**
     * Anfrage des Handys. Die Antwort muss sofort fertig sein; der Aufruf kommt auf einem Hintergrund-Thread
     * des Dienstes, dort darf kurz gewartet werden (Trage-Sensor). Das Handy wartet nur wenige Sekunden.
     */
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WatchPaths.RING) return super.onRequest(nodeId, path, request)
        val ack = try {
            ring(request)
        } catch (e: Exception) {
            Log.w(TAG, "Erinnerung vom Handy konnte nicht gezeigt werden", e)
            RingRouting.ackFor(requestId = "", onlyIfWorn = false, worn = null, displayed = false)
        }
        return Tasks.forResult(WatchJson.encodeToString(ack).encodeToByteArray())
    }

    /** Nachricht ohne Antwort: Das Handy hat Erinnerungen entfernt. */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WatchPaths.CANCEL) {
            super.onMessageReceived(messageEvent)
            return
        }
        val cancel = runCatching { WatchJson.decodeFromString<CancelRequest>(messageEvent.data.decodeToString()) }
            .getOrNull() ?: return
        try {
            container.alerts.dismiss(cancel.originKeys)
        } catch (e: Exception) {
            Log.w(TAG, "Erinnerung konnte nicht entfernt werden", e)
        }
    }

    /** Das Handy hat eine neue Übersicht abgelegt. */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            // Der Puffer gehört dem Dienst und wird von ihm freigegeben – hier nur lesen und die Bytes kopieren.
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path != WatchPaths.SNAPSHOT) continue
                val bytes = item.data ?: continue
                container.snapshots.accept(bytes.copyOf())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Übersicht vom Handy konnte nicht gelesen werden", e)
        }
    }

    private fun ring(bytes: ByteArray): RingAck {
        val request = runCatching { WatchJson.decodeFromString<RingRequest>(bytes.decodeToString()) }.getOrNull()
            ?: return RingRouting.ackFor(requestId = "", onlyIfWorn = false, worn = null, displayed = false)
        val c = container
        // Zu spät angekommen: Das Handy hat längst selbst geklingelt.
        if (request.isStale(System.currentTimeMillis())) return notShown(request, worn = null)
        if (!c.alerts.canShow()) {
            // Benachrichtigungen sind auf der Uhr aus: Das Handy klingelt – und erfährt den Grund für die Einstellungen.
            c.scope.launch { c.phone.publishInfo() }
            return notShown(request, worn = null)
        }
        val worn = if (request.onlyIfWorn) c.wornDetector.isWorn() else null
        if (!RingRouting.watchShouldShow(request.onlyIfWorn, worn)) return notShown(request, worn)
        val displayed = c.alerts.show(request)
        if (displayed) openScreen()
        return RingRouting.ackFor(request.requestId, request.onlyIfWorn, worn, displayed)
    }

    private fun notShown(request: RingRequest, worn: Boolean?): RingAck =
        RingRouting.ackFor(request.requestId, request.onlyIfWorn, worn, displayed = false)

    /**
     * Versucht, das Vollbild von selbst zu öffnen. Das System darf das stillschweigend verhindern – geklingelt
     * wird trotzdem, und über die Benachrichtigung lässt sich das Vollbild immer öffnen.
     */
    private fun openScreen() {
        try {
            startActivity(AlertActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Vollbild konnte nicht von selbst geöffnet werden", e)
        }
    }

    private companion object {
        const val TAG = "WatchListenerService"
    }
}

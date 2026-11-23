package de.lltntplanet.adhsapp.wear.link

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.ReminderAction
import de.lltntplanet.adhsapp.shared.WATCH_PROTOCOL_VERSION
import de.lltntplanet.adhsapp.shared.WatchInfo
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchPaths
import de.lltntplanet.adhsapp.shared.WatchReply
import de.lltntplanet.adhsapp.wear.BuildConfig
import de.lltntplanet.adhsapp.wear.alert.AlertNotifications
import de.lltntplanet.adhsapp.wear.alert.WornDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/**
 * Alles, was die Uhr-App ans Handy schickt. Jede Funktion hat ein Zeitlimit und wirft nie einen Fehler:
 * Klappt etwas nicht (Handy weg, Handy-App fehlt), kommt `false` zurück.
 */
class PhoneLink(appContext: Context, private val wornDetector: WornDetector) {

    private val context: Context = appContext.applicationContext
    private val notifications = AlertNotifications(context)

    /** Ist ein Handy mit der Handy-App gerade erreichbar? */
    suspend fun isReachable(): Boolean = phoneNode() != null

    /** Knopf einer Erinnerung. `true`, wenn das Handy die Aktion ausgeführt hat. */
    suspend fun sendReminderAction(action: ReminderAction): Boolean =
        request(WatchPaths.REMINDER_ACTION, WatchJson.encodeToString(action).encodeToByteArray())

    /** Aufgabe oder Routine abhaken (oder den Haken entfernen). `true`, wenn das Handy es ausgeführt hat. */
    suspend fun sendItemAction(action: ItemAction): Boolean =
        request(WatchPaths.ITEM_ACTION, WatchJson.encodeToString(action).encodeToByteArray())

    /** Bittet das Handy, die Übersicht neu zu schicken. `true`, wenn die Bitte abgeschickt wurde. */
    suspend fun requestSync(): Boolean {
        val node = phoneNode() ?: return false
        return guarded(MESSAGE_MILLIS) {
            Wearable.getMessageClient(context).sendMessage(node, WatchPaths.SYNC_REQUEST, ByteArray(0)).await()
            true
        } ?: false
    }

    /**
     * Legt den Zustand der Uhr-App (Version, Berechtigungen) für die Einstellungen der Handy-App ab. Das Handy
     * bekommt ihn auch dann, wenn es gerade nicht erreichbar ist – sobald die Verbindung wieder steht.
     */
    suspend fun publishInfo(): Boolean = guarded(MESSAGE_MILLIS) {
        val info = WatchInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            protocol = WATCH_PROTOCOL_VERSION,
            model = Build.MODEL.orEmpty(),
            updatedAt = System.currentTimeMillis(),
            notificationsAllowed = notifications.canShow(),
            overlayAllowed = Settings.canDrawOverlays(context),
            wornDetection = wornDetector.hasSensor,
        )
        val item = PutDataRequest.create(WatchPaths.WATCH_INFO)
            .setData(WatchJson.encodeToString(info).encodeToByteArray())
            .setUrgent()
        Wearable.getDataClient(context).putDataItem(item).await()
        true
    } ?: false

    /** Anfrage mit Antwort: Erst danach steht fest, ob das Handy die Aktion ausgeführt hat. */
    private suspend fun request(path: String, bytes: ByteArray): Boolean {
        val node = phoneNode() ?: return false
        val reply = guarded(REQUEST_MILLIS) {
            Wearable.getMessageClient(context).sendRequest(node, path, bytes).await()
        }
        return WatchReply.isOk(reply)
    }

    /** Das Handy mit der Handy-App – am liebsten das direkt verbundene. `null`, wenn keines erreichbar ist. */
    private suspend fun phoneNode(): String? = guarded(NODE_MILLIS) {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(WatchPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }

    /** Führt [block] mit Zeitlimit aus. `null` bei Zeitüberschreitung oder Fehler. */
    private suspend fun <T : Any> guarded(timeoutMillis: Long, block: suspend () -> T?): T? = try {
        withTimeoutOrNull(timeoutMillis) { block() }
    } catch (e: CancellationException) {
        // Nur weiterreichen, wenn wirklich der Aufrufer abgebrochen wurde – nicht, wenn die Verbindung abbricht.
        currentCoroutineContext().ensureActive()
        null
    } catch (e: Exception) {
        Log.w(TAG, "Verbindung zum Handy fehlgeschlagen", e)
        null
    }

    private companion object {
        const val TAG = "PhoneLink"

        /** Zusammen bleiben Suche und Anfrage unter den 10 s, die ein Knopf der Benachrichtigung Zeit hat. */
        const val NODE_MILLIS = 2_000L
        const val REQUEST_MILLIS = 5_000L
        const val MESSAGE_MILLIS = 5_000L
    }
}

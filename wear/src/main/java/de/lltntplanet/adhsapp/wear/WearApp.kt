package de.lltntplanet.adhsapp.wear

import android.app.Application
import android.util.Log
import de.lltntplanet.adhsapp.wear.alert.AlertController
import de.lltntplanet.adhsapp.wear.alert.WornDetector
import de.lltntplanet.adhsapp.wear.link.PhoneLink
import de.lltntplanet.adhsapp.wear.link.SnapshotRepository
import de.lltntplanet.adhsapp.wear.ui.OverviewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Die Uhr-App. Sie hält die gemeinsamen Bausteine ([WearContainer]) – ohne Framework, wie die Handy-App. */
class WearApp : Application() {

    lateinit var container: WearContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val created = WearContainer(this)
        container = created
        // Kanäle anlegen und wiederfinden, was noch als Benachrichtigung auf der Uhr steht.
        runCatching { created.alerts.start() }
            .onFailure { Log.w(TAG, "Erinnerungen konnten nicht vorbereitet werden", it) }
        // Das Handy erfährt Version und Berechtigungen der Uhr-App, auch wenn sie nie von Hand geöffnet wird.
        created.scope.launch { created.phone.publishInfo() }
    }

    private companion object {
        const val TAG = "WearApp"
    }
}

/** Die Bausteine der Uhr-App. Zugriff überall mit `(applicationContext as WearApp).container`. */
class WearContainer(val app: Application) {

    /** Für Arbeit, die länger lebt als ein Bildschirm (z. B. einen Knopfdruck ans Handy senden). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val wornDetector = WornDetector(app)

    /** Alles, was die Uhr ans Handy schickt. */
    val phone = PhoneLink(app, wornDetector)

    /** Die Übersicht vom Handy. Wird erst beim ersten Zugriff geladen – beim Klingeln braucht sie niemand. */
    val snapshots: SnapshotRepository by lazy { SnapshotRepository(app, scope) }

    /** Die Erinnerungen, die gerade auf der Uhr stehen. */
    val alerts = AlertController(app, phone, scope)

    /**
     * Was die Oberfläche mit der Übersicht tun kann (auffrischen, abhaken). Lebt hier und nicht im Bildschirm –
     * so bleibt sichtbar, was gerade abgehakt wurde, auch wenn der Bildschirm neu aufgebaut wird.
     */
    val overview: OverviewController by lazy { OverviewController(this) }
}

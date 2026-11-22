package de.lltntplanet.adhsapp.wear

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.lltntplanet.adhsapp.wear.alert.AlertActivity
import de.lltntplanet.adhsapp.wear.ui.OverviewLogic
import de.lltntplanet.adhsapp.wear.ui.WatchRoot
import de.lltntplanet.adhsapp.wear.ui.theme.AdhsWearTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Die Uhr-App zum Öffnen: Startseite „Heute“ und die Unterseiten. Beim ersten Start fragt sie nach der Erlaubnis
 * für Benachrichtigungen (ohne sie kann die Uhr nicht klingeln). Solange sie zu sehen ist, holt sie eine frische
 * Übersicht vom Handy, wenn die gemerkte fehlt oder zu alt ist.
 */
class MainActivity : ComponentActivity() {

    private val container: WearContainer get() = (application as WearApp).container

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** Darf die Uhr-App gerade Erinnerungen zeigen? Sonst warnt die Startseite. */
    private var canRing by mutableStateOf(true)

    /** Zeigt vor der Frage des Systems die kurze Erklärung, wozu die Erlaubnis gebraucht wird. */
    private var showIntro by mutableStateOf(false)

    /** Hätte das System vor der letzten Frage eine Begründung verlangt? Daran zeigt sich „nicht mehr fragen“. */
    private var rationaleBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val overview = container.overview
        val alerts = container.alerts
        canRing = alerts.canShow()
        showIntro = !hasNotificationPermission() && !prefs.getBoolean(KEY_ASKED, false)

        // Jedes Mal, wenn die App nach vorn kommt, und danach regelmäßig: Ist die Übersicht zu alt?
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    overview.syncIfNeeded()
                    delay(OverviewLogic.SYNC_CHECK_MILLIS)
                }
            }
        }

        setContent {
            AdhsWearTheme {
                val permissionRequest =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        onPermissionAnswer(granted)
                    }
                WatchRoot(
                    overview = overview,
                    alerts = alerts,
                    canRing = canRing,
                    showIntro = showIntro,
                    onAllowNotifications = {
                        requestNotifications { permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    },
                    onOpenAlerts = { openAlerts() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.overview.onVisible()
    }

    override fun onResume() {
        super.onResume()
        // Die Erlaubnis kann sich in den Einstellungen geändert haben, während die App nicht zu sehen war.
        canRing = container.alerts.canShow()
        if (hasNotificationPermission()) showIntro = false
        // Das Handy zeigt in seinen Einstellungen an, ob die Uhr-App klingeln darf.
        val phone = container.phone
        container.scope.launch { phone.publishInfo() }
    }

    override fun onStop() {
        container.overview.onHidden()
        super.onStop()
    }

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Fragt mit [ask] nach der Erlaubnis für Benachrichtigungen – oder öffnet die Einstellungen, wenn Fragen
     * nicht hilft.
     */
    private fun requestNotifications(ask: () -> Unit) {
        if (hasNotificationPermission()) {
            // Die Erlaubnis ist da, aber die Benachrichtigungen oder ihr Kanal sind in den Einstellungen aus.
            openNotificationSettings()
            return
        }
        rationaleBefore = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (runCatching(ask).isFailure) {
            // Die Frage ließ sich nicht stellen: Dann bleibt nur der Weg über die Einstellungen.
            closeIntro()
            openNotificationSettings()
        }
    }

    private fun onPermissionAnswer(granted: Boolean) {
        val askedBefore = prefs.getBoolean(KEY_ASKED, false)
        closeIntro()
        canRing = container.alerts.canShow()
        // Nach mehrmaligem „Nein“ fragt das System nicht mehr und antwortet sofort selbst. Dann helfen nur noch
        // die Einstellungen. Beim allerersten Mal nicht: Da hat jemand die Frage vielleicht nur weggewischt.
        val rationaleNow = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (!granted && askedBefore && !rationaleBefore && !rationaleNow) openNotificationSettings()
    }

    /** Die Erklärung hat ihren Zweck erfüllt. Fehlt die Erlaubnis weiter, warnt ab jetzt die Startseite. */
    private fun closeIntro() {
        prefs.edit { putBoolean(KEY_ASKED, true) }
        showIntro = false
    }

    /** Öffnet die Benachrichtigungs-Einstellungen der App; gibt es die auf der Uhr nicht, die App-Details. */
    private fun openNotificationSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
        val opened = candidates.any { intent -> runCatching { startActivity(intent) }.isSuccess }
        if (!opened) container.overview.showNotice(OverviewLogic.ACTION_FAILED)
    }

    /** Öffnet das Vollbild mit den Erinnerungen, die noch auf der Uhr stehen. */
    private fun openAlerts() {
        runCatching { startActivity(AlertActivity.intent(this)) }
            .onFailure { container.overview.showNotice(OverviewLogic.ACTION_FAILED) }
    }

    private companion object {
        const val PREFS = "main"
        const val KEY_ASKED = "notifications_asked"
    }
}

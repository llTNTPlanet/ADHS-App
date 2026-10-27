package de.lltntplanet.adhsapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.lltntplanet.adhsapp.reminders.ReminderKind
import de.lltntplanet.adhsapp.ui.AppRoot
import de.lltntplanet.adhsapp.ui.Tab
import de.lltntplanet.adhsapp.ui.theme.AdhsTheme

class MainActivity : ComponentActivity() {

    private var requestedTab by mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            requestedTab = tabFrom(intent)
            showUpdateIfRequested(intent)
            takeSharedText(intent)
        }
        setContent {
            AdhsTheme {
                AppRoot(requestedTab = requestedTab, onTabHandled = { requestedTab = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFrom(intent)?.let { requestedTab = it }
        showUpdateIfRequested(intent)
        takeSharedText(intent)
    }

    /** Antwort einer Chat-App, die per „Teilen“ an die App geschickt wurde → Seite „Essen“ wertet sie aus. */
    private fun takeSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        (application as AdhsApp).container.sharedChatAnswer.value = text
        requestedTab = Tab.NUTRITION
    }

    private fun showUpdateIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) (application as AdhsApp).container.updates.showDialog()
    }

    override fun onResume() {
        super.onResume()
        val container = (application as AdhsApp).container
        container.requestReschedule()
        container.updates.onAppResumed()
    }

    private fun tabFrom(intent: Intent?): Tab? = when (intent?.getStringExtra(EXTRA_TAB)) {
        ReminderKind.TASK.name -> Tab.TASKS
        ReminderKind.BIRTHDAY.name -> Tab.BIRTHDAYS
        ReminderKind.EVENT.name -> Tab.CALENDAR
        ReminderKind.MEDICATION.name, ReminderKind.ROUTINE.name -> Tab.ROUTINES
        else -> null
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}

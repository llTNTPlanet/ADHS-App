package de.lltntplanet.adhsapp.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.wear.alert.AlertController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Route der Startseite. Die Unterseiten heißen wie ihre [WatchPage.id]. */
private const val HOME_ROUTE = "home"

/**
 * Die Oberfläche der Uhr-App: die Startseite „Heute“ und die Unterseiten – nach rechts wischen führt zurück.
 * Solange die App zu sehen ist, rechnen alle Zeitangaben jede Minute neu.
 *
 * @param canRing Darf die Uhr-App Erinnerungen zeigen? Sonst warnt die Startseite.
 * @param showIntro Zeigt statt der Übersicht die kurze Erklärung, wozu die Benachrichtigungen gebraucht werden.
 */
@Composable
fun WatchRoot(
    overview: OverviewController,
    alerts: AlertController,
    canRing: Boolean,
    showIntro: Boolean,
    onAllowNotifications: () -> Unit,
    onOpenAlerts: () -> Unit,
) {
    val snapshot by overview.snapshot.collectAsStateWithLifecycle()
    val loaded by overview.loaded.collectAsStateWithLifecycle()
    val refreshing by overview.refreshing.collectAsStateWithLifecycle()
    val checkedTasks by overview.checkedTasks.collectAsStateWithLifecycle()
    val notice by overview.notice.collectAsStateWithLifecycle()
    val reminders by alerts.active.collectAsStateWithLifecycle()
    val now by remember { minuteTicks() }.collectAsStateWithLifecycle(initialValue = System.currentTimeMillis())

    // Die Seiten lesen diese Werte als Zustand: So bekommen sie Änderungen mit, ohne dass die Navigation neu
    // aufgebaut wird.
    val ringAllowed by rememberUpdatedState(canRing)
    val allowNotifications by rememberUpdatedState(onAllowNotifications)
    val openAlerts by rememberUpdatedState(onOpenAlerts)

    AppScaffold {
        if (showIntro) {
            NotificationIntro(onAllow = onAllowNotifications)
        } else {
            val navController = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = navController, startDestination = HOME_ROUTE) {
                composable(HOME_ROUTE) {
                    HomeScreen(
                        snapshot = snapshot,
                        loaded = loaded,
                        refreshing = refreshing,
                        now = now,
                        checkedTasks = checkedTasks,
                        reminderCount = reminders.size,
                        canRing = ringAllowed,
                        onToggleRoutine = overview::setRoutineDone,
                        onToggleTask = overview::setTaskDone,
                        onRefresh = overview::refreshNow,
                        onOpenPage = { page -> navController.navigate(page.id) { launchSingleTop = true } },
                        onOpenAlerts = { openAlerts() },
                        onAllowNotifications = { allowNotifications() },
                    )
                }
                WatchPage.entries.forEach { page ->
                    composable(page.id) {
                        PageScreen(
                            page = page,
                            snapshot = snapshot,
                            now = now,
                            checkedTasks = checkedTasks,
                            onToggleRoutine = overview::setRoutineDone,
                            onToggleTask = overview::setTaskDone,
                        )
                    }
                }
            }
        }
        NoticeDialog(notice = notice, onDismiss = overview::dismissNotice)
    }
}

/** Erklärt vor der Frage des Systems kurz, wozu die Uhr-App Benachrichtigungen braucht. */
@Composable
private fun NotificationIntro(onAllow: () -> Unit) {
    WatchList { spec ->
        title("Erinnerungen", spec)
        hint("intro", "Damit dich die Uhr erinnern kann, braucht sie die Erlaubnis für Benachrichtigungen.")
        item(key = "allow") {
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                label = { CenteredLabel("Weiter") },
            )
        }
    }
}

/** Kurzer Hinweis über den ganzen Bildschirm, wenn etwas nicht geklappt hat. Verschwindet von selbst. */
@Composable
private fun NoticeDialog(notice: OverviewController.Notice, onDismiss: () -> Unit) {
    val style = ConfirmationDialogDefaults.curvedTextStyle
    FailureConfirmationDialog(
        visible = notice.visible,
        onDismissRequest = onDismiss,
        curvedText = { confirmationDialogCurvedText(notice.text, style) },
    )
}

/** Die Uhrzeit in Millisekunden: sofort und danach kurz nach jeder vollen Minute. */
private fun minuteTicks(): Flow<Long> = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(now)
        delay(OverviewLogic.untilNextMinute(now))
    }
}

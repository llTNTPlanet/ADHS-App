package de.lltntplanet.adhsapp.wear.alert

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.wear.WearApp
import de.lltntplanet.adhsapp.wear.ui.theme.AdhsWearTheme

/**
 * Vollbild der Erinnerungen: zeigt alles, was gerade auf der Uhr steht, mit großen Knöpfen. Nur Oberfläche –
 * geklingelt wird im [AlertRingService]. Schließt sich von selbst, sobald nichts mehr zu zeigen ist.
 */
class AlertActivity : ComponentActivity() {

    private val alerts: AlertController get() = (application as WearApp).container.alerts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val controller = alerts
        setContent {
            AdhsWearTheme {
                val ringing by controller.ringing.collectAsStateWithLifecycle()
                // Solange es klingelt, bleibt der Bildschirm an.
                LaunchedEffect(ringing) {
                    if (ringing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                AlertScreen(alerts = controller, onClose = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val controller = alerts
        controller.setUiVisible(true)
        // Inzwischen am Handy bestätigt: Dann gibt es hier nichts mehr zu sehen.
        if (controller.active.value.isEmpty() && controller.notice.value == null && controller.sending.value == 0) {
            finish()
        }
    }

    override fun onStop() {
        alerts.setUiVisible(false)
        super.onStop()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AlertActivity::class.java)
    }
}

@Composable
private fun AlertScreen(alerts: AlertController, onClose: () -> Unit) {
    val reminders by alerts.active.collectAsStateWithLifecycle()
    val notice by alerts.notice.collectAsStateWithLifecycle()
    val sending by alerts.sending.collectAsStateWithLifecycle()

    val noticeText = notice
    val waiting = sending > 0
    val nothingLeft = reminders.isEmpty() && noticeText == null && !waiting
    LaunchedEffect(nothingLeft) {
        if (nothingLeft) onClose()
    }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    AppScaffold {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                if (noticeText != null) {
                    item(key = "notice") {
                        Text(
                            text = noticeText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item(key = "notice-ok") {
                        Button(
                            onClick = { alerts.clearNotice() },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            label = { Text(text = "OK", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        )
                    }
                } else if (reminders.isEmpty() && waiting) {
                    item(key = "sending") {
                        Text(
                            text = "Wird ans Handy gesendet …",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                reminders.forEach { reminder ->
                    val key = reminder.originKey
                    item(key = "title:$key") {
                        ListHeader(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        ) {
                            Text(text = reminder.title, textAlign = TextAlign.Center, maxLines = 3)
                        }
                    }
                    val text = AlertLogic.text(reminder)
                    if (text.isNotBlank()) {
                        item(key = "text:$key") {
                            Text(
                                text = text,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    AlertLogic.buttons(reminder).forEachIndexed { index, button ->
                        item(key = "button:$key:${button.action}") {
                            val modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                            val transformation = SurfaceTransformation(spec)
                            val onClick = { alerts.act(reminder, button) }
                            when {
                                // Der erste Knopf ist der Hauptknopf.
                                index == 0 -> Button(
                                    onClick = onClick,
                                    modifier = modifier,
                                    transformation = transformation,
                                    label = { ButtonLabel(button.label) },
                                )
                                button.action == ReminderActions.MUTE -> OutlinedButton(
                                    onClick = onClick,
                                    modifier = modifier,
                                    transformation = transformation,
                                    label = { ButtonLabel(button.label) },
                                )
                                else -> FilledTonalButton(
                                    onClick = onClick,
                                    modifier = modifier,
                                    transformation = transformation,
                                    label = { ButtonLabel(button.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

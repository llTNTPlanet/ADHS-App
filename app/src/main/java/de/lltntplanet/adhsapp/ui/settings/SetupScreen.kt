package de.lltntplanet.adhsapp.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.Permissions
import kotlinx.coroutines.launch

/** Einrichtungs-Assistent: alle Berechtigungen für zuverlässige Vollbild-Erinnerungen auf einen Blick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf(Permissions.state(context)) }
    val refresh = {
        state = Permissions.state(context)
        container.requestReschedule()
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Einrichtung") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Damit dich die App zuverlässig an Termine, Geburtstage und Aufgaben erinnert – " +
                    "auch über den ganzen Bildschirm und bei gesperrtem Handy –, braucht sie ein paar Erlaubnisse.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("Wichtig", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SetupItem(
                title = "Google Kalender",
                text = "Termine anzeigen, neue Termine speichern und rechtzeitig erinnern.",
                granted = state.calendar,
                essential = true,
            ) { calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
            SetupItem(
                title = "Benachrichtigungen",
                text = "Ohne diese Erlaubnis können keine Erinnerungen erscheinen.",
                granted = state.notifications,
                essential = true,
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !state.notifications) {
                    singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Permissions.open(context, Permissions.notificationSettings(context))
                }
            }
            SetupItem(
                title = "Pünktliche Alarme",
                text = "Erlaubt minutengenaue Erinnerungen – auch im Energiesparmodus.",
                granted = state.exactAlarms,
                essential = true,
            ) { Permissions.open(context, Permissions.exactAlarmSettings(context)) }
            SetupItem(
                title = "Vollbild-Benachrichtigungen",
                text = "Erinnerungen erscheinen wie ein Wecker über den ganzen Bildschirm, auch auf dem Sperrbildschirm.",
                granted = state.fullScreen,
                essential = true,
            ) { Permissions.open(context, Permissions.fullScreenSettings(context)) }

            Text("Empfohlen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SetupItem(
                title = "Über anderen Apps anzeigen",
                text = "Damit die Erinnerung auch dann den ganzen Bildschirm füllt, wenn du gerade das Handy benutzt.",
                granted = state.overlay,
                essential = false,
            ) { Permissions.open(context, Permissions.overlaySettings(context)) }
            SetupItem(
                title = "Kontakte (Geburtstage)",
                text = "Geburtstage aus Google Kontakte automatisch übernehmen.",
                granted = state.contacts,
                essential = false,
            ) { singleLauncher.launch(Manifest.permission.READ_CONTACTS) }
            SetupItem(
                title = "Akku-Optimierung ausschalten",
                text = "Verhindert, dass Android die App im Hintergrund schlafen legt und Erinnerungen verschluckt.",
                granted = state.batteryUnrestricted,
                essential = false,
            ) { Permissions.open(context, Permissions.batterySettings(context)) }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        container.reminderEngine.scheduleTest(10)
                        snackbar.showSnackbar("Test-Erinnerung kommt in 10 Sekunden – sperr ruhig das Handy.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test-Erinnerung in 10 Sekunden") }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.essentialsMissing == 0) "Fertig" else "Später erledigen")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupItem(title: String, text: String, granted: Boolean, essential: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    granted -> Icons.Filled.CheckCircle
                    essential -> Icons.Filled.ErrorOutline
                    else -> Icons.Outlined.Circle
                },
                contentDescription = if (granted) "Erledigt" else "Offen",
                tint = when {
                    granted -> AppColors.Task
                    essential -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onClick) { Text("Erlauben") }
            }
        }
    }
}

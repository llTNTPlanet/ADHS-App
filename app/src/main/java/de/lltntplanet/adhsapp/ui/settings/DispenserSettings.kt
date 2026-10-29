package de.lltntplanet.adhsapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.data.db.DispenserEventEntity
import de.lltntplanet.adhsapp.data.db.DispenserEventKind
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.dispenser.DispenserListener
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.Permissions
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Wahl für die eigene Erinnerung bei Medikamenten im Spender: Minuten nach der Einnahmezeit, -1 = keine. */
internal val DispenserDelayChoices = listOf(0 to "Gleichzeitig", 15 to "15 Min. später", 30 to "30 Min. später", 60 to "60 Min. später", -1 to "Keine")

/** Kurztext zur gewählten Rückfall-Erinnerung. */
internal fun dispenserDelaySummary(delay: Int): String = when {
    delay < 0 -> "Keine eigene Erinnerung – nur der Alarm am Spender."
    delay == 0 -> "Die App erinnert gleichzeitig mit dem Spender."
    else -> "Die App erinnert $delay Min. nach der Einnahmezeit, falls der Spender bis dahin keine Entnahme gemeldet hat."
}

/** Eine Zeile für die Liste der mitgelesenen Meldungen, z. B. „Genommen → 08:00 abgehakt: Medikinet“. */
internal fun dispenserEventSummary(e: DispenserEventEntity, today: LocalDate = LocalDate.now()): String {
    val minutes = e.matchedMinutes
    val match = when {
        e.kind != DispenserEventKind.TAKEN -> null
        minutes == null || e.matchedNames.isBlank() -> "kein passendes Medikament im Spender"
        else -> {
            val day = e.matchedEpochDay?.let { LocalDate.ofEpochDay(it) }
            val dayText = if (day != null && day != today) "${TimeFormat.relativeDay(day, today)} " else ""
            "$dayText${TimeFormat.minutesOfDay(minutes)}: ${e.matchedNames}"
        }
    }
    return listOfNotNull(e.kind.label, match).joinToString(" → ")
}

/** Einstellungen-Abschnitt „Medikamentenspender“ (Memio Go über die App iDispenser). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DispenserSettingsSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routines by remember { container.routines.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList<RoutineEntity>())
    val events by remember { container.dispenser.observeRecent(10) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var enabled by remember { mutableStateOf(DispenserListener.isEnabled(context)) }
    var installed by remember { mutableStateOf(DispenserListener.isDispenserAppInstalled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = DispenserListener.isEnabled(context)
        installed = DispenserListener.isDispenserAppInstalled(context)
        onPauseOrDispose { }
    }
    val inDispenser = routines.filter { it.dispenser && it.kind == RoutineKind.MEDICATION }

    Section("Medikamentenspender")
    Text(
        "Für den Memio Go: Die App liest die Benachrichtigungen von iDispenser mit. Meldet der Spender „genommen“, " +
            "wird das passende Medikament automatisch abgehakt. Am Spender selbst ändert sich nichts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Benachrichtigungen mitlesen", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (enabled) "Erlaubt" else "Noch nicht erlaubt",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (enabled) {
            TextButton(onClick = { Permissions.open(context, DispenserListener.settingsIntent(context)) }) { Text("Ändern") }
        } else {
            Button(onClick = { Permissions.open(context, DispenserListener.settingsIntent(context)) }) { Text("Erlauben") }
        }
    }
    if (!enabled) {
        Text(
            "Ist der Schalter dort ausgegraut („Eingeschränkte Einstellung“)? Dann in der App-Info oben rechts auf ⋮ " +
                "tippen, „Eingeschränkte Einstellungen zulassen“ wählen und es noch einmal versuchen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { Permissions.open(context, Permissions.appDetails(context)) }) { Text("App-Info öffnen") }
    }
    if (!installed) {
        Text(
            "Die App iDispenser ist auf diesem Handy nicht installiert – ohne sie kommen keine Meldungen vom Spender.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Label("Im Spender")
    Text(
        if (inDispenser.isEmpty()) {
            "Noch kein Medikament markiert. Unter „Routinen“ ein Medikament öffnen und „Im Medikamentenspender“ einschalten."
        } else {
            inDispenser.joinToString("\n") { r ->
                val times = RoutineLogic.times(r).joinToString(", ") { TimeFormat.minutesOfDay(it) }
                "${r.name} · $times Uhr · ${RoutineLogic.scheduleText(r)}"
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    Label("Eigene Erinnerung für Medikamente im Spender")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DispenserDelayChoices.forEach { (delay, label) ->
            FilterChip(
                selected = s.dispenserReminderDelay == delay,
                onClick = { update { it.copy(dispenserReminderDelay = delay) } },
                label = { Text(label) },
            )
        }
    }
    Text(
        dispenserDelaySummary(s.dispenserReminderDelay),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Label("Zuletzt mitgelesen")
    if (events.isEmpty()) {
        Text(
            "Noch keine Meldung vom Spender empfangen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val today = LocalDate.now()
        events.forEach { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "${TimeFormat.relativeDay(Dates.toLocalDate(e.postedAt), today)} ${TimeFormat.time(e.postedAt)} · " +
                        dispenserEventSummary(e, today),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOf(e.title, e.text).filter { it.isNotBlank() }.joinToString(" – "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Steht hier bei einer Meldung „Unbekannt“, kennt die App ihren Text noch nicht – schick ihn gern, " +
                "dann wird die Erkennung angepasst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { scope.launch { container.dispenser.clear() } }) { Text("Liste leeren") }
    }
}

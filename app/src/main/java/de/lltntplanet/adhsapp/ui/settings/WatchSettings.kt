package de.lltntplanet.adhsapp.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat

/** Anleitung zur Uhr-App im README (Abschnitt „Uhr-App (Wear OS / Pixel Watch)“). */
private val WatchGuideUrl =
    "https://github.com/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}#uhr-app-wear-os--pixel-watch"

/**
 * Einstellungen-Abschnitt „Uhr (Wear OS)“: Zustand der Uhr-App, wo Erinnerungen klingeln, Seiten auf der Uhr.
 * Geänderte Einstellungen gehen von selbst an die Uhr: Jedes Speichern stößt den Abgleich der Übersicht an.
 */
@Composable
fun WatchSettingsSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val status by container.watch.status.collectAsStateWithLifecycle()
    // Sucht im Hintergrund und kehrt sofort zurück – auch ohne Uhr oder ohne Google-Dienste.
    LifecycleResumeEffect(Unit) {
        container.watch.refreshStatus()
        onPauseOrDispose { }
    }
    val text = watchStatusText(status, s.ringTarget) { at ->
        "${TimeFormat.relativeDay(Dates.toLocalDate(at))} ${TimeFormat.time(at)}"
    }

    Section("Uhr (Wear OS)")
    Text(text.headline, style = MaterialTheme.typography.bodyLarge)
    text.details.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    text.warnings.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.lastSync,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Ohne Uhr-App gibt es nichts zu senden – der Status darüber sagt, was fehlt.
        TextButton(enabled = status.watchName != null, onClick = { container.watchSync.syncNow() }) {
            Text("Übersicht jetzt senden")
        }
    }

    Label("Wo sollen Erinnerungen klingeln?")
    RingTarget.entries.forEach { target ->
        val selected = s.ringTarget == target
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton) { update { it.copy(ringTarget = target) } }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(target.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    target.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Ist die Uhr nicht erreichbar oder antwortet sie nicht, klingelt immer das Handy.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Ausprobieren: „Test in 10 s“ ganz oben unter „Status“ läuft über diese Auswahl – und testet damit auch die Uhr.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    SwitchSetting(
        "Ton auf der Uhr",
        "Zusätzlich zur Vibration ein Weckton – nur, wenn die Uhr einen Lautsprecher hat.",
        s.watchSound,
    ) { v -> update { it.copy(watchSound = v) } }
    watchVibrationHint(s.vibrate, s.watchSound, s.ringTarget)?.let { hint ->
        // Nur mit Ton ist es eine Warnung (nicht jede Uhr hat einen Lautsprecher); ohne Ton vibriert die Uhr ohnehin.
        val color = if (s.watchSound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        Text(hint, style = MaterialTheme.typography.bodySmall, color = color)
    }

    Label("Seiten auf der Uhr")
    WatchPage.entries.forEach { page ->
        val visible = page.id !in s.watchHiddenPages
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = visible, role = Role.Checkbox) { show ->
                    update { it.copy(watchHiddenPages = if (show) it.watchHiddenPages - page.id else it.watchHiddenPages + page.id) }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = visible, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(page.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Text(
        "Die Startseite „Heute“ ist immer dabei.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        "Die Uhr-App liegt jedem Release als adhs-watch.zip bei. Du installierst und aktualisierst sie per adb – " +
            "die Anleitung steht im README, Abschnitt „Uhr-App (Wear OS / Pixel Watch)“.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, WatchGuideUrl.toUri())) } }) {
        Text("Anleitung öffnen")
    }
}

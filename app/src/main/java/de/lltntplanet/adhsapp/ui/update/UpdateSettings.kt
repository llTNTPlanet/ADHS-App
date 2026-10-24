package de.lltntplanet.adhsapp.ui.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.ui.settings.Label
import de.lltntplanet.adhsapp.ui.settings.Section
import de.lltntplanet.adhsapp.ui.settings.SwitchSetting
import de.lltntplanet.adhsapp.update.UpdateState
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.launch

/** Vorausgefüllter Link für einen fine-grained Token mit reinem Lesezugriff. */
private val TokenUrl =
    "https://github.com/settings/personal-access-tokens/new" +
        "?name=ADHS%20App%20Updates" +
        "&description=Liest%20Releases%20f%C3%BCr%20das%20Selbst-Update%20der%20ADHS%20App" +
        "&target_name=${BuildConfig.UPDATE_REPO_OWNER}" +
        "&expires_in=none" +
        "&contents=read"

/** Einstellungen-Abschnitt "App-Updates". */
@Composable
fun UpdateSettingsSection() {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
    val state by container.updates.state.collectAsStateWithLifecycle()
    val savedToken by container.secrets.githubToken.collectAsStateWithLifecycle()
    var tokenInput by remember { mutableStateOf("") }
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    Section("App-Updates")
    Text("Installiert: Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
    val last = settings?.lastUpdateCheck ?: 0L
    Text(
        when (state) {
            is UpdateState.Available -> "Neue Version ${(state as UpdateState.Available).info.versionName} verfügbar"
            UpdateState.UpToDate -> "Du hast die neueste Version."
            else -> if (last > 0) {
                "Zuletzt gesucht: ${TimeFormat.relativeDay(Dates.toLocalDate(last))} ${TimeFormat.time(last)}"
            } else {
                "Noch nicht nach Updates gesucht."
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = container.updates::checkNow) { Text("Jetzt nach Updates suchen") }
    }
    SwitchSetting(
        "Updates automatisch installieren",
        "Neue Versionen werden im Hintergrund geladen und installiert. Beim ersten Mal fragt Android einmal nach.",
        settings?.autoInstallUpdates ?: true,
    ) { v -> scope.launch { container.settings.update { it.copy(autoInstallUpdates = v) } } }
    if (!canInstall) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Noch nötig: „Unbekannte Apps installieren“ für ADHS App erlauben.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = container.updates::openInstallPermissionSettings) { Text("Erlauben") }
        }
    }

    Label("GitHub-Token (nur für ein privates Repository)")
    Text(
        if (savedToken != null) "Token ist gespeichert." else "Kein Token gespeichert – bei einem öffentlichen Repository nicht nötig.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = tokenInput,
        onValueChange = { tokenInput = it },
        placeholder = { Text(if (savedToken != null) "••••••••  (neu eingeben zum Ersetzen)" else "github_pat_…") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, TokenUrl.toUri())) } },
        ) { Text("Token erstellen") }
        FilledTonalButton(
            enabled = tokenInput.isNotBlank(),
            onClick = {
                container.secrets.setGithubToken(tokenInput)
                tokenInput = ""
                container.updates.checkNow()
            },
        ) { Text("Speichern") }
        if (savedToken != null) {
            TextButton(onClick = { container.secrets.setGithubToken(null) }) { Text("Löschen") }
        }
    }
    Text(
        "Beim Erstellen unter „Repository access“ nur ${BuildConfig.UPDATE_REPO_NAME} auswählen. " +
            "Der Token darf nur lesen und wird nicht gesichert oder weitergegeben.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

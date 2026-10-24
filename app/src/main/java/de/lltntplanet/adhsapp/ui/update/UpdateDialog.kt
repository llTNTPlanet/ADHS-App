package de.lltntplanet.adhsapp.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.update.UpdateInfo
import de.lltntplanet.adhsapp.update.UpdateState

/** Fragt bei einer neuen Version sofort nach, ob aktualisiert werden soll, und zeigt den Fortschritt. */
@Composable
fun UpdateDialogHost() {
    val updates = appContainer().updates
    val visible by updates.dialogVisible.collectAsStateWithLifecycle()
    val state by updates.state.collectAsStateWithLifecycle()
    if (!visible) return
    when (val s = state) {
        is UpdateState.Available -> UpdateDialog(
            title = "Neue Version verfügbar",
            info = s.info,
            text = "Version ${s.info.versionName} ist da (installiert: ${BuildConfig.VERSION_NAME}).",
            confirm = "Jetzt aktualisieren" to updates::startUpdate,
            dismiss = "Später" to updates::dismiss,
        )
        is UpdateState.NeedsPermission -> UpdateDialog(
            title = "Einmal erlauben",
            info = null,
            text = "Damit sich die ADHS App selbst aktualisieren kann, schalte im nächsten Bildschirm " +
                "„Aus dieser Quelle zulassen“ ein und komm dann zurück – das Update startet automatisch.",
            confirm = "Einstellungen öffnen" to updates::openInstallPermissionSettings,
            dismiss = "Später" to updates::dismiss,
        )
        is UpdateState.Downloading -> UpdateDialog(
            title = "Update wird geladen",
            info = null,
            text = "Version ${s.info.versionName} – ${(s.progress * 100).toInt()} %",
            progress = s.progress,
            confirm = null,
            dismiss = "Im Hintergrund" to updates::dismiss,
        )
        is UpdateState.Installing -> UpdateDialog(
            title = "Wird installiert",
            info = null,
            text = "Android installiert jetzt Version ${s.info.versionName}. Falls gefragt, bitte „Aktualisieren“ " +
                "bestätigen. Die App startet danach neu.",
            progress = -1f,
            confirm = null,
            dismiss = "OK" to updates::dismiss,
        )
        is UpdateState.Failed -> UpdateDialog(
            title = "Update nicht möglich",
            info = null,
            text = s.message,
            confirm = if (s.info != null) "Erneut versuchen" to updates::startUpdate else null,
            dismiss = "Schließen" to updates::dismiss,
        )
        UpdateState.Checking -> UpdateDialog(
            title = "Suche nach Updates …",
            info = null,
            text = "",
            progress = -1f,
            confirm = null,
            dismiss = "Abbrechen" to updates::dismiss,
        )
        UpdateState.UpToDate -> UpdateDialog(
            title = "Alles aktuell",
            info = null,
            text = "Du hast die neueste Version (${BuildConfig.VERSION_NAME}).",
            confirm = null,
            dismiss = "OK" to updates::dismiss,
        )
        UpdateState.Idle -> Unit
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    info: UpdateInfo?,
    text: String,
    confirm: Pair<String, () -> Unit>?,
    dismiss: Pair<String, () -> Unit>,
    progress: Float? = null,
) {
    AlertDialog(
        onDismissRequest = dismiss.second,
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyLarge)
                if (progress != null) {
                    Spacer(Modifier.height(16.dp))
                    if (progress < 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                val notes = info?.notes?.takeIf { it.isNotBlank() }
                if (notes != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { confirm?.let { (label, action) -> Button(onClick = action) { Text(label) } } },
        dismissButton = { TextButton(onClick = dismiss.second) { Text(dismiss.first) } },
    )
}

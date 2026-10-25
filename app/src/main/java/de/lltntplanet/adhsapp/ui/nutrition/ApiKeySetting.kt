package de.lltntplanet.adhsapp.ui.nutrition

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.ui.settings.Label

/** Einstellungen: API-Schlüssel für die Foto-Erkennung von Mahlzeiten. */
@Composable
fun FoodVisionKeySetting() {
    val container = appContainer()
    val context = LocalContext.current
    val saved by container.secrets.anthropicApiKey.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Label("Foto-Erkennung (Claude-API-Schlüssel)")
    Text(
        if (saved != null) "Schlüssel ist gespeichert – Fotos von Tellern und Verpackungen werden automatisch erkannt."
        else "Kein Schlüssel gespeichert. Mit Schlüssel erkennt die Seite „Essen“ Mahlzeiten auf Fotos und trägt die Kalorien ein.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text(if (saved != null) "••••••••  (neu eingeben zum Ersetzen)" else "sk-ant-…") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ApiKeyUrl.toUri())) } }) {
            Text("Schlüssel erstellen")
        }
        FilledTonalButton(
            enabled = input.isNotBlank(),
            onClick = {
                container.secrets.setAnthropicApiKey(input)
                input = ""
            },
        ) { Text("Speichern") }
        if (saved != null) {
            TextButton(onClick = { container.secrets.setAnthropicApiKey(null) }) { Text("Löschen") }
        }
    }
    Text(
        "Ein Foto kostet ungefähr einen Cent von deinem Anthropic-Guthaben. Der Schlüssel bleibt auf dem Handy und wird nicht gesichert; " +
            "Fotos werden nur für die Erkennung übertragen und nicht gespeichert.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

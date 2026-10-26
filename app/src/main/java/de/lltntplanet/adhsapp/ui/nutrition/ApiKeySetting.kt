package de.lltntplanet.adhsapp.ui.nutrition

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionProvider
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.ui.settings.Label
import de.lltntplanet.adhsapp.ui.settings.SwitchSetting
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Einstellungen: Dienst und API-Schlüssel für die Foto-Erkennung von Mahlzeiten. */
@Composable
fun FoodVisionSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val geminiKey by container.secrets.geminiApiKey.collectAsStateWithLifecycle()
    val claudeKey by container.secrets.anthropicApiKey.collectAsStateWithLifecycle()
    var input by remember(settings.foodVisionProvider) { mutableStateOf("") }
    val provider = settings.foodVisionProvider
    val saved = when (provider) {
        FoodVisionProvider.CHAT -> null
        FoodVisionProvider.GEMINI -> geminiKey
        FoodVisionProvider.CLAUDE -> claudeKey
    }

    Label("Foto-Erkennung von Mahlzeiten")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodVisionProvider.entries.forEach { p ->
            FilterChip(
                selected = provider == p,
                onClick = { update { it.copy(foodVisionProvider = p) } },
                label = { Text(p.label) },
            )
        }
    }
    Text(
        when (provider) {
            FoodVisionProvider.CHAT ->
                "Ohne Schlüssel und ohne Kosten: Das Foto geht mit einer fertigen Frage über „Teilen“ an eine Chat-App deiner Wahl " +
                    "(Gemini, ChatGPT, Claude, Copilot …). Die Antwort fügst du in der App ein oder teilst sie aus dem Chat zurück " +
                    "an „Kalorien eintragen (ADHS App)“."
            FoodVisionProvider.GEMINI ->
                if (saved != null) "Gemini-Schlüssel ist gespeichert – die Erkennung ist kostenlos (Gratis-Kontingent von Google AI Studio)."
                else "Kostenlos: Einmalig einen Gemini-API-Schlüssel in Google AI Studio erstellen (Google-Konto genügt, keine Zahlungsdaten)."
            FoodVisionProvider.CLAUDE ->
                if (saved != null) "Claude-Schlüssel ist gespeichert. Ein Foto kostet ungefähr einen Cent."
                else "Claude braucht einen Anthropic-Schlüssel mit Guthaben; ein Foto kostet ungefähr einen Cent."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!provider.needsKey) return
    val scope = rememberCoroutineScope()
    var testResult by remember(provider) { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text(if (saved != null) "••••••••  (neu eingeben zum Ersetzen)" else provider.keyPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, provider.keyUrl.toUri())) } }) {
            Text("Schlüssel erstellen")
        }
        FilledTonalButton(
            enabled = input.isNotBlank(),
            onClick = {
                when (provider) {
                    FoodVisionProvider.CHAT -> Unit
                    FoodVisionProvider.GEMINI -> container.secrets.setGeminiApiKey(input)
                    FoodVisionProvider.CLAUDE -> container.secrets.setAnthropicApiKey(input)
                }
                input = ""
            },
        ) { Text("Speichern") }
        if (saved != null) {
            TextButton(
                onClick = {
                    when (provider) {
                        FoodVisionProvider.CHAT -> Unit
                        FoodVisionProvider.GEMINI -> container.secrets.setGeminiApiKey(null)
                        FoodVisionProvider.CLAUDE -> container.secrets.setAnthropicApiKey(null)
                    }
                    testResult = null
                },
            ) { Text("Löschen") }
        }
    }
    if (saved != null) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val started = System.currentTimeMillis()
                        testResult = runCatching { container.foodVision.analyzeText("1 Apfel") }.fold(
                            onSuccess = { r ->
                                val secs = (System.currentTimeMillis() - started) / 1000.0
                                "Verbindung OK (${"%.1f".format(secs)} s): " +
                                    (r.items.firstOrNull()?.let { "${it.name} ≈ ${it.kcal} kcal" } ?: "keine Einträge, aber Antwort erhalten")
                            },
                            onFailure = { "Fehler: ${it.message ?: it.javaClass.simpleName}" },
                        )
                        testing = false
                    }
                },
            ) { Text(if (testing) "Teste …" else "Verbindung testen") }
            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Fehler")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
    Text(
        "Der Schlüssel bleibt auf dem Handy und wird nicht gesichert; Fotos werden nur für die Erkennung übertragen und nicht gespeichert.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Einstellungen: Mahlzeiten in Health Connect schreiben, damit Google Health sie übernimmt. */
@Composable
fun HealthConnectSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val container = appContainer()
    val hc = container.healthConnect
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Int?>(null) }
    suspend fun refresh() {
        granted = hc.hasPermission()
    }
    LaunchedEffect(Unit) { refresh() }
    LifecycleResumeEffect(Unit) {
        scope.launch { refresh() }
        onPauseOrDispose { }
    }
    val requestPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        val ok = hc.permission in result
        granted = ok
        update { it.copy(healthConnectExport = ok) }
        if (ok) scope.launch { exported = container.nutrition.exportDay(LocalDate.now()) }
    }

    Label("Google Health / Health Connect")
    if (!hc.available) {
        Text(
            if (hc.needsUpdate) "Health Connect muss aktualisiert werden (Play Store), dann lassen sich Mahlzeiten dorthin übertragen."
            else "Health Connect ist auf diesem Handy nicht verfügbar – Mahlzeiten können nicht an Google Health übergeben werden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SwitchSetting(
        "Mahlzeiten an Health Connect übergeben",
        if (settings.healthConnectExport && granted) "An – Google Health übernimmt die Einträge von dort"
        else if (settings.healthConnectExport) "Berechtigung fehlt – antippen zum Erteilen"
        else "Kostenlos, bleibt auf dem Handy",
        settings.healthConnectExport && granted,
    ) { on ->
        if (on) {
            requestPermission.launch(setOf(hc.permission))
        } else {
            update { it.copy(healthConnectExport = false) }
        }
    }
    exported?.let { n ->
        Text(
            if (n > 0) "$n Einträge von heute übertragen." else "Eingeschaltet – neue Einträge landen ab jetzt in Health Connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        "In der Google-Health-App unter Einstellungen → Health Connect der ADHS App Zugriff auf Ernährung erlauben, " +
            "dann erscheinen die Mahlzeiten dort. Fotos selbst werden nicht übergeben – Health Connect speichert nur Daten.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

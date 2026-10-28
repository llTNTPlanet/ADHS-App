package de.lltntplanet.adhsapp.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import de.lltntplanet.adhsapp.data.mood.MoodLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Daten für die Symptom-Abfrage: Symptome, bereits gespeicherte Werte, Vorbelegung. */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckupViewModel(private val c: AppContainer) : ViewModel() {
    val symptoms: StateFlow<List<SymptomEntity>> = c.moods.observeSymptoms()
        .map { list -> list.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val target = MutableStateFlow<Pair<LocalDate, Int>?>(null)

    /** Schon gespeicherte Werte des geöffneten Checkups (beim Bearbeiten). */
    val existing: StateFlow<List<MoodEntryEntity>> = target
        .flatMapLatest { t -> if (t == null) MutableStateFlow(emptyList()) else c.moods.observeCheckup(t.first, t.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(o: RoutineOccurrence) {
        target.value = o.date to o.minutes
        viewModelScope.launch { c.moods.ensureDefaults() }
    }

    suspend fun lastValues(): Map<Long, Int> = c.moods.lastValues()

    fun addSymptom(name: String) {
        viewModelScope.launch { c.moods.addSymptom(name) }
    }

    fun removeSymptom(s: SymptomEntity) {
        viewModelScope.launch { c.moods.removeSymptom(s) }
    }

    /** Werte speichern und den Checkup in der Tagesliste abhaken. */
    fun save(o: RoutineOccurrence, values: Map<Long, Int>) {
        viewModelScope.launch {
            c.moods.saveCheckup(o.date, o.minutes, values)
            c.routines.setDone(o.routine.id, o.date, o.minutes, true)
        }
    }

    /** Eintrag samt Werten löschen (Haken entfernen). */
    fun clear(o: RoutineOccurrence) {
        viewModelScope.launch {
            c.moods.deleteCheckup(o.date, o.minutes)
            c.routines.setDone(o.routine.id, o.date, o.minutes, false)
        }
    }

    fun skip(o: RoutineOccurrence) {
        viewModelScope.launch { c.routines.skip(o.routine.id, o.date, o.minutes) }
    }
}

/**
 * Gefühls-Checkup wie in MyTherapy: jedes Symptom mit einem Schieberegler 1–10 bewerten.
 * Wird aus der Tagesliste (Abhak-Kreis), vom Hauptbildschirm und aus der Erinnerung geöffnet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckupSheet(
    o: RoutineOccurrence,
    onDismiss: () -> Unit,
    onOpenStats: (() -> Unit)? = null,
) {
    val vm = appViewModel { CheckupViewModel(it) }
    val symptoms by vm.symptoms.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val values = remember { mutableStateMapOf<Long, Int>() }
    var prefill by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var editingSymptoms by remember { mutableStateOf(false) }

    LaunchedEffect(o.key) {
        vm.open(o)
        prefill = vm.lastValues()
    }
    // Gespeicherte Werte (Bearbeiten) haben Vorrang vor der Vorbelegung aus dem letzten Checkup.
    LaunchedEffect(existing) { existing.forEach { values[it.symptomId] = it.value } }

    fun valueOf(s: SymptomEntity): Int = values[s.id] ?: prefill[s.id] ?: DEFAULT_VALUE

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mood, null, tint = AppColors.Mood)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.routine.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${TimeFormat.relativeDay(o.date)}, ${TimeFormat.minutesOfDay(o.minutes)}" + if (o.done) " · ausgefüllt" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { editingSymptoms = !editingSymptoms }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Symptome anpassen", tint = if (editingSymptoms) AppColors.Mood else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Wie stark ist es gerade? 1 = gar nicht, 10 = sehr stark.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (editingSymptoms) {
                Spacer(Modifier.height(12.dp))
                SymptomEditor(symptoms, onAdd = vm::addSymptom, onRemove = vm::removeSymptom)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }

            Spacer(Modifier.height(8.dp))
            if (symptoms.isEmpty()) {
                Text(
                    "Keine Symptome in der Abfrage. Tippe oben auf das Schieberegler-Symbol, um welche hinzuzufügen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            symptoms.forEach { s ->
                SymptomSlider(s, valueOf(s)) { values[s.id] = it }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = symptoms.isNotEmpty(),
                onClick = {
                    vm.save(o, symptoms.associate { it.id to valueOf(it) })
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("checkup-save"),
            ) { Text(if (o.done) "Änderungen speichern" else "Speichern", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            if (!o.done && !o.skipped) {
                OutlinedButton(onClick = { vm.skip(o); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Diesmal überspringen") }
            } else {
                TextButton(
                    onClick = { vm.clear(o); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (o.skipped) "Haken entfernen" else "Eintrag löschen") }
            }
            if (onOpenStats != null) {
                TextButton(onClick = { onDismiss(); onOpenStats() }, modifier = Modifier.fillMaxWidth()) { Text("Statistik ansehen") }
            }
        }
    }
}

private const val DEFAULT_VALUE = 1

/** Eine Zeile: Name, großer Wert, Schieberegler 1–10 mit Rasterpunkten. */
@Composable
private fun SymptomSlider(s: SymptomEntity, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                MoodLogic.valueLabel(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor(value),
                textAlign = TextAlign.End,
                modifier = Modifier.width(32.dp),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(MoodLogic.MIN_VALUE, MoodLogic.MAX_VALUE)) },
            valueRange = MoodLogic.MIN_VALUE.toFloat()..MoodLogic.MAX_VALUE.toFloat(),
            steps = MoodLogic.MAX_VALUE - MoodLogic.MIN_VALUE - 1,
            colors = SliderDefaults.colors(thumbColor = valueColor(value), activeTrackColor = valueColor(value)),
            modifier = Modifier.fillMaxWidth().testTag("slider-${s.id}"),
        )
    }
}

/** Grün bei niedrigen, Rot bei hohen Werten – höher heißt überall „stärker belastet“. */
@Composable
fun valueColor(value: Int): androidx.compose.ui.graphics.Color = when {
    value <= 3 -> AppColors.Task
    value <= 6 -> AppColors.Warning
    else -> MaterialTheme.colorScheme.error
}

/** Symptome der Abfrage verwalten: Chips mit „×“ zum Entfernen, Eingabefeld und Vorschläge zum Hinzufügen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymptomEditor(
    symptoms: List<SymptomEntity>,
    onAdd: (String) -> Unit,
    onRemove: (SymptomEntity) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val names = symptoms.map { it.name.lowercase() }.toSet()
    val suggestions = (MoodLogic.DEFAULT_SYMPTOMS + MoodLogic.SUGGESTED_SYMPTOMS).filter { it.lowercase() !in names }
    fun add() {
        val n = input.trim()
        if (n.isNotEmpty()) onAdd(n)
        input = ""
    }
    Column {
        Text("Abgefragte Symptome", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (symptoms.isEmpty()) {
            Text("Noch keins – unten eintippen oder einen Vorschlag antippen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            symptoms.forEach { s ->
                InputChip(
                    selected = true,
                    onClick = {},
                    label = { Text(s.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "${s.name} entfernen",
                            modifier = Modifier.padding(2.dp).clickable { onRemove(s) },
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Symptom hinzufügen") },
            placeholder = { Text("z. B. Schwindel") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = { add() }, enabled = input.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Hinzufügen") }
            },
            modifier = Modifier.fillMaxWidth().testTag("symptom-input"),
        )
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Vorschläge", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                suggestions.forEach { name ->
                    SuggestionChip(onClick = { onAdd(name) }, label = { Text(name) }, icon = { Icon(Icons.Filled.Add, null, Modifier.width(16.dp)) })
                }
            }
        }
    }
}

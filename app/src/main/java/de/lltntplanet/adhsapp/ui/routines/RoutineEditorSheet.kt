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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import de.lltntplanet.adhsapp.data.routines.Medication
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.ui.components.TimePickDialog
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.delay

/** Anlegen / Bearbeiten – Name, Art, Dosis, Uhrzeiten und Wochentage, angelehnt an MyTherapy. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineEditorSheet(
    routine: RoutineEntity?,
    onDismiss: () -> Unit,
    onSave: (RoutineEntity) -> Unit,
    onDelete: (() -> Unit)?,
    /** Vorschläge aus der Medikamenten-Liste zur aktuellen Eingabe. */
    suggest: suspend (String) -> List<Medication> = { emptyList() },
    /** Symptome für die Art „Checkup“ (aktive), plus Hinzufügen/Entfernen. */
    symptoms: List<SymptomEntity> = emptyList(),
    onAddSymptom: (String) -> Unit = {},
    onRemoveSymptom: (SymptomEntity) -> Unit = {},
    /** Wird aufgerufen, sobald „Checkup“ gewählt ist – legt beim ersten Mal die Standard-Symptome an. */
    onCheckupChosen: () -> Unit = {},
    /** Vorausgewählte Art beim Neuanlegen (z. B. direkt „Checkup“). */
    initialKind: RoutineKind = RoutineKind.MEDICATION,
) {
    var name by remember { mutableStateOf(routine?.name.orEmpty()) }
    var suggestions by remember { mutableStateOf<List<Medication>>(emptyList()) }
    /** Aus der Liste gewähltes Medikament – dann erscheinen seine üblichen Stärken zum Antippen. */
    var picked by remember { mutableStateOf<Medication?>(null) }
    var kind by remember { mutableStateOf(routine?.kind ?: initialKind) }
    var dose by remember { mutableStateOf(routine?.dose.orEmpty()) }
    val times = remember { (routine?.let { RoutineLogic.times(it) } ?: listOf(8 * 60)).toMutableStateList() }
    var weekdays by remember { mutableIntStateOf(routine?.weekdays ?: RoutineEntity.ALL_DAYS) }
    var pickingTime by remember { mutableStateOf<Int?>(null) } // Index in times, -1 = neu
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && times.isNotEmpty() && weekdays != 0
    val checkup = kind == RoutineKind.CHECKUP

    LaunchedEffect(kind) {
        if (checkup) {
            onCheckupChosen()
            if (name.isBlank()) name = DEFAULT_CHECKUP_NAME
        }
    }

    // Beim Bearbeiten: Passt der Name zu einem Listeneintrag (z. B. „Medikinet adult 20 mg“)? Dann Stärken anbieten.
    LaunchedEffect(Unit) {
        if (routine != null && routine.kind == RoutineKind.MEDICATION) {
            picked = suggest(routine.name.substringBefore(' ')).filter { routine.name.startsWith(it.name) }.maxByOrNull { it.name.length }
        }
    }
    LaunchedEffect(name, kind) {
        val p = picked
        if (kind != RoutineKind.MEDICATION || name.length < 2 || (p != null && name.startsWith(p.name))) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        suggestions = suggest(name)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                if (routine == null) "Neu anlegen" else "Bearbeiten",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RoutineKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, RoutineKind.entries.size),
                        icon = { Icon(routineIcon(k), null) },
                    ) { Text(k.label) }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(when (kind) { RoutineKind.MEDICATION -> "Medikament"; RoutineKind.ROUTINE -> "Routine"; RoutineKind.CHECKUP -> "Name" }) },
                placeholder = { Text(when (kind) { RoutineKind.MEDICATION -> "z. B. Methylphenidat 10 mg"; RoutineKind.ROUTINE -> "z. B. Zähne putzen"; RoutineKind.CHECKUP -> "z. B. $DEFAULT_CHECKUP_NAME" }) },
                supportingText = if (kind == RoutineKind.MEDICATION && name.isEmpty()) {
                    { Text("Tipp: Einfach lostippen – Vorschläge aus der Liste gängiger Medikamente (Deutschland).") }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) {
                MedicationSuggestions(suggestions) { m ->
                    picked = m
                    name = m.name
                    suggestions = emptyList()
                }
            }
            picked?.takeIf { kind == RoutineKind.MEDICATION && it.strengths.isNotEmpty() }?.let { m ->
                Spacer(Modifier.height(8.dp))
                Text("Stärke", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    m.strengths.forEach { strength ->
                        val selected = name == "${m.name} $strength"
                        FilterChip(
                            selected = selected,
                            onClick = { name = if (selected) m.name else "${m.name} $strength" },
                            label = { Text(strength) },
                        )
                    }
                }
            }
            if (checkup) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Zu jeder Uhrzeit fragt die App alle Symptome unten mit einem Schieberegler von 1 bis 10 ab. " +
                        "Die Werte landen in der Statistik.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SymptomEditor(symptoms, onAdd = onAddSymptom, onRemove = onRemoveSymptom)
            }
            if (!checkup) Spacer(Modifier.height(8.dp))
            if (!checkup) OutlinedTextField(
                value = dose,
                onValueChange = { dose = it },
                label = { Text(if (kind == RoutineKind.MEDICATION) "Dosis / Hinweis" else "Hinweis") },
                placeholder = { Text(if (kind == RoutineKind.MEDICATION) "z. B. 1 Tablette, mit Wasser" else "z. B. 10 Minuten") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("Uhrzeiten", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                times.sorted().forEach { t ->
                    InputChip(
                        selected = true,
                        onClick = { pickingTime = times.indexOf(t) },
                        label = { Text(TimeFormat.minutesOfDay(t)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Uhrzeit entfernen",
                                modifier = Modifier
                                    .padding(2.dp)
                                    .clickable { times.remove(t) },
                            )
                        },
                    )
                }
                InputChip(
                    selected = false,
                    onClick = { pickingTime = -1 },
                    label = { Text("Uhrzeit") },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                )
            }
            if (times.isEmpty()) {
                Text("Mindestens eine Uhrzeit angeben.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Text("Wochentage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = RoutineLogic.hasWeekday(weekdays, d),
                        onClick = { weekdays = RoutineLogic.toggleWeekday(weekdays, d) },
                        label = { Text(RoutineLogic.shortDay(d)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { weekdays = RoutineEntity.ALL_DAYS }) { Text("Täglich") }
                TextButton(onClick = { weekdays = 0b0011111 }) { Text("Mo–Fr") }
            }
            if (weekdays == 0) {
                Text("Mindestens einen Wochentag wählen.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Löschen")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            (routine ?: RoutineEntity(name = "", times = "")).copy(
                                name = name.trim(),
                                kind = kind,
                                dose = if (checkup) "" else dose.trim(),
                                times = RoutineLogic.encodeTimes(times),
                                weekdays = weekdays,
                            ),
                        )
                    },
                ) { Text("Speichern") }
            }
        }
    }

    pickingTime?.let { index ->
        val initial = if (index >= 0) times.getOrNull(index)?.let { LocalTime.of(it / 60, it % 60) } ?: LocalTime.of(8, 0) else LocalTime.of(8, 0)
        TimePickDialog(
            initial = initial,
            onDismiss = { pickingTime = null },
            onPick = { t ->
                val minutes = t.hour * 60 + t.minute
                if (index >= 0 && index < times.size) times.removeAt(index)
                if (minutes !in times) times.add(minutes)
            },
        )
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Löschen?") },
            text = {
                Text(
                    if (checkup) "„$name“ wird entfernt und nicht mehr abgefragt. Die bisher gespeicherten Werte bleiben in der Statistik."
                    else "„$name“ und alle Abhak-Einträge werden entfernt. Erinnerungen dazu gibt es dann nicht mehr.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

const val DEFAULT_CHECKUP_NAME = "Gefühls-Checkup"

/** Aufklappliste unter dem Namensfeld: Treffer aus der Medikamenten-Liste. */
@Composable
private fun MedicationSuggestions(items: List<Medication>, onPick: (Medication) -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column {
            items.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(m) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(m.kind.label, m.group).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Details zu einer einzelnen Einnahme: abhaken, überspringen, bearbeiten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccurrenceSheet(
    o: RoutineOccurrence,
    now: Long,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSkip: () -> Unit,
    onEdit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(routineIcon(o.routine.kind), null, tint = routineColor(o.routine.kind))
                Spacer(Modifier.width(10.dp))
                Text(o.routine.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    "${TimeFormat.relativeDay(o.date)}, ${TimeFormat.minutesOfDay(o.minutes)}",
                    o.routine.dose.ifBlank { null },
                    when {
                        o.skipped -> "übersprungen"
                        o.done -> doneWord(o.routine.kind)
                        RoutineLogic.isOverdue(o, now) -> "noch offen"
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            val checked = o.done || o.skipped
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(if (checked) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(if (checked) "Haken entfernen" else doneAction(o.routine.kind))
            }
            if (!checked) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.SkipNext, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Heute überspringen")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text(when (o.routine.kind) { RoutineKind.MEDICATION -> "Medikament bearbeiten"; RoutineKind.ROUTINE -> "Routine bearbeiten"; RoutineKind.CHECKUP -> "Checkup bearbeiten" })
            }
        }
    }
}

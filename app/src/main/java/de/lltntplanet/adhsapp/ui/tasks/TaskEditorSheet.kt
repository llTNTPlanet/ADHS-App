package de.lltntplanet.adhsapp.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.ui.components.DatePickDialog
import de.lltntplanet.adhsapp.ui.components.TimePickDialog
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    initial: TaskEntity,
    googleConnected: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit,
) {
    var task by remember { mutableStateOf(initial) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val focus = remember { FocusRequester() }
    val isNew = initial.id == 0L
    val isGoogle = task.googleTaskId != null || task.googleListId != null

    LaunchedEffect(Unit) { if (isNew) runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isNew) "Neue Aufgabe" else "Aufgabe bearbeiten", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = task.title,
                onValueChange = { task = task.copy(title = it) },
                label = { Text("Was ist zu tun?") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            OutlinedTextField(
                value = task.notes,
                onValueChange = { task = task.copy(notes = it) },
                label = { Text("Notiz oder erster kleiner Schritt") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Wann?", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val due = task.dueEpochDay?.let(LocalDate::ofEpochDay)
                FilterChip(
                    selected = due == today,
                    onClick = { task = task.copy(dueEpochDay = today.toEpochDay()) },
                    label = { Text("Heute") },
                )
                FilterChip(
                    selected = due == today.plusDays(1),
                    onClick = { task = task.copy(dueEpochDay = today.plusDays(1).toEpochDay()) },
                    label = { Text("Morgen") },
                )
                FilterChip(
                    selected = due != null && due != today && due != today.plusDays(1),
                    onClick = { pickDate = true },
                    label = { Text(if (due != null && due != today && due != today.plusDays(1)) TimeFormat.relativeDay(due, today) else "Datum …") },
                )
                FilterChip(
                    selected = due == null,
                    onClick = { task = task.copy(dueEpochDay = null, dueMinutes = null) },
                    label = { Text("Kein Datum") },
                )
            }
            if (task.dueEpochDay != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime = true }) {
                        Text(task.dueMinutes?.let { "Uhrzeit: ${TimeFormat.minutesOfDay(it)}" } ?: "Uhrzeit wählen")
                    }
                    if (task.dueMinutes != null) {
                        TextButton(onClick = { task = task.copy(dueMinutes = null) }) { Text("Ohne Uhrzeit") }
                    }
                }
                SwitchRow("Erinnern (Vollbild)", task.remind) { task = task.copy(remind = it) }
            }

            if (!isGoogle) {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }) {
                        Icon(Icons.Filled.Repeat, null)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text("Wiederholen: ${task.repeat.label}")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        Repeat.entries.forEach { r ->
                            DropdownMenuItem(text = { Text(r.label) }, onClick = {
                                task = task.copy(
                                    repeat = r,
                                    dueEpochDay = if (r != Repeat.NONE && task.dueEpochDay == null) today.toEpochDay() else task.dueEpochDay,
                                )
                                open = false
                            })
                        }
                    }
                }
                if (task.repeat != Repeat.NONE) {
                    Text(
                        "Ideal für Routinen wie Medikamente: Beim Abhaken springt die Aufgabe automatisch auf den nächsten Termin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Diese Aufgabe wird mit Google Tasks abgeglichen. Uhrzeit und Erinnerung bleiben nur in dieser App.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow("Wichtig ⭐", task.important) { task = task.copy(important = it) }
            if (isNew && googleConnected && task.repeat == Repeat.NONE) {
                Text(
                    "Wird auch in Google Tasks gespeichert (änderbar in den Einstellungen).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Löschen") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                Button(enabled = task.title.isNotBlank(), onClick = { onSave(task.copy(title = task.title.trim())) }) {
                    Text("Speichern")
                }
            }
        }
    }

    if (pickDate) {
        DatePickDialog(task.dueEpochDay?.let(LocalDate::ofEpochDay) ?: today, { pickDate = false }) {
            task = task.copy(dueEpochDay = it.toEpochDay())
        }
    }
    if (pickTime) {
        val initialTime = task.dueMinutes?.let { LocalTime.of(it / 60, it % 60) } ?: LocalTime.now().plusHours(1).withMinute(0)
        TimePickDialog(initialTime, { pickTime = false }) {
            task = task.copy(dueMinutes = it.hour * 60 + it.minute, remind = true)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Aufgabe löschen?") },
            text = { Text(if (isGoogle) "Sie wird auch in Google Tasks gelöscht." else "\"${task.title}\" wird gelöscht.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

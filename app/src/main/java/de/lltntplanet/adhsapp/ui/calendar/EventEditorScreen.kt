package de.lltntplanet.adhsapp.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.calendar.CalendarInfo
import de.lltntplanet.adhsapp.data.calendar.CalendarRepository
import de.lltntplanet.adhsapp.data.calendar.EventDraft
import de.lltntplanet.adhsapp.ui.components.ColorDot
import de.lltntplanet.adhsapp.ui.components.DatePickDialog
import de.lltntplanet.adhsapp.ui.components.TimePickDialog
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

private val ReminderChoices = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60)

private enum class PickTarget { START_DATE, START_TIME, END_DATE, END_TIME }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(eventId: Long?, initialStart: LocalDateTime?, onClose: () -> Unit) {
    val vm = appViewModel { CalendarViewModel(it) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }
    var draft by remember { mutableStateOf<EventDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<PickTarget?>(null) }

    LaunchedEffect(eventId) {
        val writable = vm.loadWritableCalendars()
        calendars = writable
        if (eventId != null) {
            draft = vm.loadDraft(eventId)
            if (draft == null) error = "Dieser Termin kann hier nicht bearbeitet werden. Öffne ihn in Google Kalender."
        } else {
            val cal = writable.find { it.id == vm.settings.value.defaultCalendarId }
                ?: writable.find { it.isPrimary && it.isGoogle } ?: writable.firstOrNull()
            if (cal == null) {
                error = "Kein beschreibbarer Kalender gefunden. Ist dein Google-Konto auf dem Handy eingerichtet und die Kalender-Synchronisierung an?"
            } else {
                val start = initialStart ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                draft = CalendarRepository.defaultDraft(cal.id, start)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                },
                title = { Text(if (eventId == null) "Neuer Termin" else "Termin bearbeiten") },
                actions = {
                    val d = draft
                    Button(
                        enabled = d != null && d.title.isNotBlank() && !saving,
                        onClick = {
                            val toSave = d ?: return@Button
                            saving = true
                            scope.launch {
                                val result = vm.save(toSave)
                                saving = false
                                if (result.isSuccess) onClose()
                                else snackbar.showSnackbar("Speichern fehlgeschlagen: ${result.exceptionOrNull()?.message}")
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Speichern") }
                },
            )
        },
    ) { padding ->
        val d = draft
        when {
            error != null -> Text(error.orEmpty(), Modifier.padding(padding).padding(24.dp))
            d == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Column(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = d.title,
                    onValueChange = { draft = d.copy(title = it) },
                    placeholder = { Text("Titel hinzufügen", style = MaterialTheme.typography.headlineSmall) },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp),
                )
                HorizontalDivider()
                EditorRow(Icons.Filled.AccessTime) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ganztägig", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = d.allDay, onCheckedChange = { draft = d.copy(allDay = it) })
                    }
                }
                DateTimeLine(
                    dateText = TimeFormat.fullDate(d.start.toLocalDate()),
                    timeText = if (d.allDay) null else TimeFormat.time(d.start.toLocalTime()),
                    onDate = { pick = PickTarget.START_DATE },
                    onTime = { pick = PickTarget.START_TIME },
                )
                DateTimeLine(
                    dateText = TimeFormat.fullDate(d.end.toLocalDate()),
                    timeText = if (d.allDay) null else TimeFormat.time(d.end.toLocalTime()),
                    onDate = { pick = PickTarget.END_DATE },
                    onTime = { pick = PickTarget.END_TIME },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val cals = calendars.orEmpty()
                EditorRow(Icons.Filled.Event) {
                    var open by remember { mutableStateOf(false) }
                    val current = cals.find { it.id == d.calendarId }
                    Box {
                        Row(Modifier.clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(Color(current?.color ?: 0xFF0B57D0.toInt()))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(current?.name ?: "Kalender", style = MaterialTheme.typography.bodyLarge)
                                current?.accountName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            cals.forEach { cal ->
                                DropdownMenuItem(
                                    leadingIcon = { ColorDot(Color(cal.color)) },
                                    text = { Text("${cal.name} · ${cal.accountName}") },
                                    onClick = {
                                        draft = d.copy(calendarId = cal.id)
                                        open = false
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                EditorRow(Icons.Filled.Notifications) {
                    var open by remember { mutableStateOf(false) }
                    FlowRow {
                        d.reminders.sorted().forEach { minutes ->
                            InputChip(
                                selected = false,
                                onClick = { draft = d.copy(reminders = d.reminders - minutes) },
                                label = { Text(TimeFormat.formatMinutesBefore(minutes)) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Entfernen") },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Box {
                            TextButton(onClick = { open = true }) {
                                Icon(Icons.Filled.Add, null)
                                Text("Erinnerung")
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                ReminderChoices.filter { it !in d.reminders }.forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text(TimeFormat.formatMinutesBefore(minutes)) },
                                        onClick = {
                                            draft = d.copy(reminders = d.reminders + minutes)
                                            open = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Zusätzlich erinnert dich die App im Vollbild (Einstellungen → Erinnerungen).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                )
                HorizontalDivider()
                EditorRow(Icons.Filled.Place) {
                    OutlinedTextField(
                        value = d.location,
                        onValueChange = { draft = d.copy(location = it) },
                        label = { Text("Ort") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EditorRow(Icons.AutoMirrored.Filled.Notes) {
                    OutlinedTextField(
                        value = d.description,
                        onValueChange = { draft = d.copy(description = it) },
                        label = { Text("Beschreibung") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.padding(bottom = 48.dp))
            }
        }
    }

    val d = draft
    if (d != null) {
        when (pick) {
            PickTarget.START_DATE -> DatePickDialog(d.start.toLocalDate(), { pick = null }) { date ->
                val duration = Duration.between(d.start, d.end)
                val start = date.atTime(d.start.toLocalTime())
                draft = d.copy(start = start, end = start.plus(duration))
            }
            PickTarget.END_DATE -> DatePickDialog(d.end.toLocalDate(), { pick = null }) { date ->
                val end = date.atTime(d.end.toLocalTime())
                draft = d.copy(end = if (end.isBefore(d.start)) d.start.plusHours(1) else end)
            }
            PickTarget.START_TIME -> TimePickDialog(d.start.toLocalTime(), { pick = null }) { time ->
                val duration = Duration.between(d.start, d.end)
                val start = d.start.toLocalDate().atTime(time)
                draft = d.copy(start = start, end = start.plus(duration))
            }
            PickTarget.END_TIME -> TimePickDialog(d.end.toLocalTime(), { pick = null }) { time ->
                val end = d.end.toLocalDate().atTime(time)
                draft = d.copy(end = if (end.isBefore(d.start)) end.plusDays(1) else end)
            }
            null -> Unit
        }
    }
}

@Composable
private fun EditorRow(icon: ImageVector, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun DateTimeLine(dateText: String, timeText: String?, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDate, modifier = Modifier.weight(1f)) {
            Text(dateText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
        }
        if (timeText != null) {
            TextButton(onClick = onTime) { Text(timeText, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

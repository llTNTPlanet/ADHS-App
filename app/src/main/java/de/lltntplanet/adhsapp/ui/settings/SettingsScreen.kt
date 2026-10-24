package de.lltntplanet.adhsapp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.data.calendar.CalendarInfo
import de.lltntplanet.adhsapp.data.google.GoogleFeature
import de.lltntplanet.adhsapp.data.google.TokenResult
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderText
import de.lltntplanet.adhsapp.ui.components.ColorDot
import de.lltntplanet.adhsapp.ui.components.TimePickDialog
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.components.rememberGoogleConnector
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.Permissions
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    private val _next = MutableStateFlow<Reminder?>(null)
    val next: StateFlow<Reminder?> = _next.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun load() {
        viewModelScope.launch {
            _calendars.value = c.calendar.calendars()
            _next.value = runCatching { c.reminderEngine.next() }.getOrNull()
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            c.settings.update(transform)
            c.requestReschedule()
            _next.value = runCatching { c.reminderEngine.next() }.getOrNull()
        }
    }

    fun onConnect(feature: GoogleFeature, result: TokenResult) {
        when (result) {
            is TokenResult.Token -> viewModelScope.launch {
                c.settings.update {
                    when (feature) {
                        GoogleFeature.HEALTH -> it.copy(healthConnected = true)
                        GoogleFeature.TASKS -> it.copy(tasksConnected = true)
                    }
                }
                _messages.tryEmit("${feature.label} verbunden")
                if (feature == GoogleFeature.TASKS) syncTasks()
            }
            is TokenResult.Failed -> _messages.tryEmit(result.message)
            is TokenResult.NeedsConsent -> Unit
        }
    }

    fun disconnect(feature: GoogleFeature) {
        viewModelScope.launch {
            c.auth.revoke(feature)
            c.settings.update {
                when (feature) {
                    GoogleFeature.HEALTH -> it.copy(healthConnected = false)
                    GoogleFeature.TASKS -> it.copy(tasksConnected = false)
                }
            }
            if (feature == GoogleFeature.HEALTH) c.health.clear()
            _messages.tryEmit("${feature.label} getrennt")
        }
    }

    fun syncTasks() {
        viewModelScope.launch {
            runCatching { c.tasksSync.sync() }
                .onSuccess { _messages.tryEmit("Google Tasks: ${it.imported} neu, ${it.updated} aktualisiert, ${it.pushed} übertragen") }
                .onFailure { _messages.tryEmit(it.message ?: "Abgleich fehlgeschlagen") }
        }
    }

    fun test() {
        viewModelScope.launch {
            c.reminderEngine.scheduleTest(10)
            _messages.tryEmit("Test-Erinnerung kommt in 10 Sekunden.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val next by vm.next.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var timePicker by remember { mutableStateOf<String?>(null) }
    val connect = rememberGoogleConnector { feature, result -> vm.onConnect(feature, result) }
    var permissions by remember { mutableStateOf(Permissions.state(context)) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        vm.load()
        permissions = Permissions.state(context)
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Status
            Section("Status")
            Text(
                next?.let {
                    "Nächste Erinnerung: ${TimeFormat.relativeDay(Dates.toLocalDate(it.triggerAt))} ${TimeFormat.time(it.triggerAt)} – " +
                        ReminderText.notificationTitle(it)
                } ?: "Keine Erinnerung in den nächsten 3 Tagen geplant.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSetup) {
                    Text(if (permissions.essentialsMissing == 0) "Berechtigungen ✓" else "Berechtigungen (${permissions.essentialsMissing} offen)")
                }
                OutlinedButton(onClick = vm::test) { Text("Test in 10 s") }
            }

            // Termine
            Section("Erinnerungen an Termine")
            Label("Vorwarnungen vor jedem Termin")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 30, 60, 120).forEach { m ->
                    FilterChip(
                        selected = m in s.eventOffsets,
                        onClick = { vm.update { it.copy(eventOffsets = it.eventOffsets.toggle(m)) } },
                        label = { Text(if (m == 0) "Zu Beginn" else if (m < 60) "$m Min." else "${m / 60} Std.") },
                    )
                }
            }
            SwitchSetting(
                "Erinnerungen aus Google Kalender übernehmen",
                "Zusätzlich zu den Vorwarnungen oben",
                s.useCalendarReminders,
            ) { v -> vm.update { it.copy(useCalendarReminders = v) } }
            SwitchSetting(
                "Ganztägige Termine",
                "Erinnerung am Tag um ${TimeFormat.minutesOfDay(s.allDayReminderMinutes)} Uhr",
                s.allDayReminders,
                onClick = { timePicker = "allday" },
            ) { v -> vm.update { it.copy(allDayReminders = v) } }
            if (calendars.isNotEmpty()) {
                Label("Erinnern für diese Kalender")
                calendars.filter { !it.isBirthdayCalendar }.forEach { cal ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            vm.update { it.copy(excludedReminderCalendars = it.excludedReminderCalendars.toggle(cal.id)) }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(Color(cal.color))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                            Text(cal.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = cal.id !in s.excludedReminderCalendars,
                            onCheckedChange = { vm.update { it.copy(excludedReminderCalendars = it.excludedReminderCalendars.toggle(cal.id)) } },
                        )
                    }
                }
            }

            // Geburtstage
            Section("Geburtstage")
            SwitchSetting(
                "An Geburtstage erinnern",
                "um ${TimeFormat.minutesOfDay(s.birthdayReminderMinutes)} Uhr",
                s.birthdayReminders,
                onClick = { timePicker = "birthday" },
            ) { v -> vm.update { it.copy(birthdayReminders = v) } }
            Label("Wann?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Am Tag", 1 to "1 Tag vorher", 3 to "3 Tage vorher", 7 to "1 Woche vorher").forEach { (d, label) ->
                    FilterChip(
                        selected = d in s.birthdayDaysBefore,
                        onClick = { vm.update { it.copy(birthdayDaysBefore = it.birthdayDaysBefore.toggle(d).ifEmpty { setOf(0) }) } },
                        label = { Text(label) },
                    )
                }
            }

            // Aufgaben
            Section("Aufgaben")
            ClickSetting("Uhrzeit für Aufgaben ohne Uhrzeit", "${TimeFormat.minutesOfDay(s.taskDefaultMinutes)} Uhr") {
                timePicker = "task"
            }
            GoogleRow(
                title = "Google Tasks",
                connected = s.tasksConnected,
                onConnect = { connect(GoogleFeature.TASKS) },
                onDisconnect = { vm.disconnect(GoogleFeature.TASKS) },
            )
            if (s.tasksConnected) {
                SwitchSetting("Neue Aufgaben in Google Tasks speichern", null, s.saveNewTasksToGoogle) { v ->
                    vm.update { it.copy(saveNewTasksToGoogle = v) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.lastTasksSync > 0) "Zuletzt abgeglichen: ${TimeFormat.relativeDay(Dates.toLocalDate(s.lastTasksSync))} ${TimeFormat.time(s.lastTasksSync)}"
                        else "Noch nicht abgeglichen",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::syncTasks) { Text("Jetzt abgleichen") }
                }
            }

            // Vollbild & Ton
            Section("Vollbild & Ton")
            SwitchSetting(
                "Vollbild auch bei entsperrtem Handy",
                if (permissions.overlay) "Aktiv" else "Braucht „Über anderen Apps anzeigen“ – tippen zum Erlauben",
                s.launchOverApps,
                onClick = if (!permissions.overlay) ({ Permissions.open(context, Permissions.overlaySettings(context)) }) else null,
            ) { v -> vm.update { it.copy(launchOverApps = v) } }
            SwitchSetting("Weckton abspielen", "Bis zur Bestätigung, höchstens ${s.ringSeconds} Sekunden", s.alarmSound) { v ->
                vm.update { it.copy(alarmSound = v) }
            }
            SwitchSetting("Vibration", null, s.vibrate) { v -> vm.update { it.copy(vibrate = v) } }
            Label("Klingeldauer")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120, 300).forEach { sec ->
                    FilterChip(
                        selected = s.ringSeconds == sec,
                        onClick = { vm.update { it.copy(ringSeconds = sec) } },
                        label = { Text(if (sec < 60) "$sec s" else "${sec / 60} Min.") },
                    )
                }
            }
            SwitchSetting(
                "Wecker-Modus",
                "Maximale Zuverlässigkeit (auch im tiefsten Energiesparmodus). Zeigt ein Wecker-Symbol in der Statusleiste.",
                s.alarmClockMode,
            ) { v -> vm.update { it.copy(alarmClockMode = v) } }

            // Gesundheit
            Section("Gesundheit")
            GoogleRow(
                title = "Google Health",
                connected = s.healthConnected,
                onConnect = { connect(GoogleFeature.HEALTH) },
                onDisconnect = { vm.disconnect(GoogleFeature.HEALTH) },
            )
            Label("Tagesziel Schritte")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4000, 6000, 8000, 10000, 12000).forEach { goal ->
                    FilterChip(
                        selected = s.stepGoal == goal,
                        onClick = { vm.update { it.copy(stepGoal = goal) } },
                        label = { Text("%,d".format(TimeFormat.locale, goal)) },
                    )
                }
            }
            s.googleAccount?.let {
                Text("Google-Konto: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Section("Über")
            Text("ADHS App ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Alle Daten bleiben auf deinem Handy. Kalender und Kontakte kommen aus deinem Google-Konto auf dem Gerät, " +
                    "Gesundheitsdaten werden nur gelesen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    timePicker?.let { which ->
        val minutes = when (which) {
            "allday" -> s.allDayReminderMinutes
            "birthday" -> s.birthdayReminderMinutes
            else -> s.taskDefaultMinutes
        }
        TimePickDialog(LocalTime.of(minutes / 60, minutes % 60), { timePicker = null }) { t ->
            val m = t.hour * 60 + t.minute
            vm.update {
                when (which) {
                    "allday" -> it.copy(allDayReminderMinutes = m)
                    "birthday" -> it.copy(birthdayReminderMinutes = m)
                    else -> it.copy(taskDefaultMinutes = m)
                }
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onClick: (() -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (onClick != null) onClick() else onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickSetting(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GoogleRow(title: String, connected: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (connected) "Verbunden" else "Nicht verbunden",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (connected) TextButton(onClick = onDisconnect) { Text("Trennen") }
        else Button(onClick = onConnect) { Text("Verbinden") }
    }
}

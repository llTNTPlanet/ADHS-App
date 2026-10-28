package de.lltntplanet.adhsapp.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.birthdays.UpcomingBirthday
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.ui.birthdays.BirthdayRow
import de.lltntplanet.adhsapp.ui.calendar.EventChip
import de.lltntplanet.adhsapp.ui.calendar.EventDetailSheet
import de.lltntplanet.adhsapp.ui.components.EmptyHint
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.SectionTitle
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.ui.routines.CheckupSheet
import de.lltntplanet.adhsapp.ui.routines.OccurrenceRow
import de.lltntplanet.adhsapp.ui.tasks.TaskRow
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.PermissionState
import de.lltntplanet.adhsapp.util.Permissions
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Termine von heute und morgen (für "Als Nächstes" auch über Mitternacht hinaus). */
    /** Läuft gerade eine Aktualisierung (automatisch oder per Herunterziehen)? */
    val refreshing: StateFlow<Boolean> = c.refresher.refreshing

    val events: StateFlow<List<EventInstance>?> = combine(
        c.calendar.changes(),
        combine(refresh, c.refresher.changes) { a, b -> a + b },
        now.map { Dates.toLocalDate(it) }.distinctUntilChanged(),
        settings.map { it.hiddenCalendars }.distinctUntilChanged(),
    ) { _, _, today, hidden -> today to hidden }
        .mapLatest { (today, hidden) ->
            c.calendar.instances(today, today.plusDays(2))
                .filter { it.calendarId !in hidden && !it.canceled }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tasks: StateFlow<List<TaskEntity>> = c.tasks.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val birthdays: StateFlow<List<UpcomingBirthday>> = combine(refresh, c.refresher.changes) { a, b -> a + b }
        .flatMapLatest { c.birthdays.observeAll() }
        .map { list -> BirthdayMath.upcoming(list, LocalDate.now()).filter { it.daysUntil <= 14 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val routines: StateFlow<List<RoutineEntity>> =
        c.routines.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val routineLog: StateFlow<List<RoutineLogEntity>> = now.map { Dates.toLocalDate(it) }.distinctUntilChanged()
        .flatMapLatest { d -> c.routines.observeLog(d, d.plusDays(1)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Die nächsten zwei offenen Routinen / Medikamente (heute und morgen) – wie in MyTherapy. */
    val nextRoutines: StateFlow<List<RoutineOccurrence>> = combine(routines, routineLog, now) { r, l, n ->
        RoutineLogic.upcoming(r, l, n, ZoneId.systemDefault(), limit = 2)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gibt es überhaupt Routinen? Sonst bleibt der Abschnitt auf der Hauptseite weg. */
    val hasRoutines: StateFlow<Boolean> = routines.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleRoutine(o: RoutineOccurrence) {
        viewModelScope.launch { c.routines.setDone(o.routine.id, o.date, o.minutes, !(o.done || o.skipped)) }
    }

    val health: StateFlow<HealthSnapshot?> = c.health.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Nur die Ansicht neu lesen (Kalender, Kontakte). */
    fun refresh() {
        refresh.value++
    }

    /** Alles neu laden – Herunterziehen auf dem Heute-Bildschirm. */
    fun refreshAll() {
        refresh.value++
        c.refresher.refreshLater(userInitiated = true)
    }

    suspend fun reminders(eventId: Long): List<Int> =
        c.calendar.eventReminders(listOf(eventId))[eventId].orEmpty().sorted()

    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            runCatching { c.calendar.delete(eventId) }
            c.requestReschedule()
        }
    }

    fun toggle(task: TaskEntity) {
        viewModelScope.launch { c.tasks.setDone(task, !task.done) }
    }

    fun quickAdd(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            val s = settings.value
            c.tasks.save(
                TaskEntity(
                    title = t,
                    dueEpochDay = LocalDate.now().toEpochDay(),
                    googleListId = if (s.tasksConnected && s.saveNewTasksToGoogle) "@default" else null,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenBirthdays: () -> Unit,
    onOpenHealth: () -> Unit,
    onEditEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { TodayViewModel(it) }
    val context = LocalContext.current
    val now by vm.now.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val birthdays by vm.birthdays.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val nextRoutines by vm.nextRoutines.collectAsStateWithLifecycle()
    val hasRoutines by vm.hasRoutines.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(Permissions.state(context)) }
    var checkup by remember { mutableStateOf<RoutineOccurrence?>(null) }
    var selected by remember { mutableStateOf<EventInstance?>(null) }
    var quick by rememberSaveable { mutableStateOf("") }
    val zone = remember { ZoneId.systemDefault() }
    val today = Dates.toLocalDate(now, zone)

    LifecycleResumeEffect(Unit) {
        permissions = Permissions.state(context)
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting(LocalTime.now()), fontWeight = FontWeight.SemiBold)
                        Text(
                            TimeFormat.fullDate(today),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Einstellungen") } },
            )
        },
    ) { padding ->
        val todayEvents = events.orEmpty().filter { it.occursOn(today, zone) }
        val todayTasks = tasks.filter { !it.done && TaskLogic.dueDate(it)?.let { d -> !d.isAfter(today) } == true }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            if (permissions.essentialsMissing > 0) {
                item {
                    InfoCard(
                        icon = Icons.Filled.NotificationsActive,
                        title = "Erinnerungen einrichten",
                        text = setupText(permissions),
                        buttonText = "Jetzt einrichten",
                        onClick = onOpenSetup,
                        container = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item { NowNextCard(events, now, zone, onClick = { selected = it }, onOpenCalendar = onOpenCalendar) }

            item { SectionTitle("Heute im Kalender", action = { TextButton(onClick = onOpenCalendar) { Text("Kalender") } }) }
            if (events != null && todayEvents.isEmpty()) item { EmptyHint("Keine Termine heute.") }
            items(todayEvents, key = { "e${it.eventId}-${it.begin}" }) { e ->
                val past = !e.allDay && e.end < now
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.allDay) "ganzt." else TimeFormat.time(e.begin, zone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(56.dp),
                    )
                    EventChip(
                        e,
                        Modifier.weight(1f).alpha(if (past) 0.5f else 1f),
                        zone = zone,
                        onClick = { selected = e },
                    )
                }
            }

            if (hasRoutines) {
                item { SectionTitle("Routinen & Medikamente", action = { TextButton(onClick = onOpenRoutines) { Text("Alle") } }) }
                if (nextRoutines.isEmpty()) item { EmptyHint("Alles abgehakt – nichts mehr offen. 💊✅") }
                items(nextRoutines, key = { it.key }) { o ->
                    OccurrenceRow(
                        o,
                        now,
                        onToggle = { if (o.routine.kind == RoutineKind.CHECKUP) checkup = o else vm.toggleRoutine(o) },
                        onClick = { if (o.routine.kind == RoutineKind.CHECKUP) checkup = o else onOpenRoutines() },
                        today = today,
                        zone = zone,
                        showDay = true,
                    )
                }
            }

            item { SectionTitle("Aufgaben für heute", action = { TextButton(onClick = onOpenTasks) { Text("Alle") } }) }
            item {
                OutlinedTextField(
                    value = quick,
                    onValueChange = { quick = it },
                    placeholder = { Text("Was möchtest du heute schaffen?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.quickAdd(quick); quick = "" }),
                    trailingIcon = {
                        IconButton(onClick = { vm.quickAdd(quick); quick = "" }, enabled = quick.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Hinzufügen")
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            if (todayTasks.isEmpty()) item { EmptyHint("Nichts offen für heute. 🎉") }
            items(todayTasks.take(8), key = { "t${it.id}" }) { t ->
                TaskRow(t, onToggle = { vm.toggle(t) }, onClick = onOpenTasks, today = today)
            }
            if (todayTasks.size > 8) item { TextButton(onClick = onOpenTasks) { Text("+ ${todayTasks.size - 8} weitere") } }

            if (birthdays.isNotEmpty()) {
                item { SectionTitle("Geburtstage", action = { TextButton(onClick = onOpenBirthdays) { Text("Alle") } }) }
                items(birthdays.take(3), key = { "b${it.birthday.id}" }) { BirthdayRow(it, onClick = onOpenBirthdays) }
            }

            if (settings.healthConnected) {
                item { SectionTitle("Gesundheit", action = { TextButton(onClick = onOpenHealth) { Text("Details") } }) }
                item { HealthMini(health, settings.stepGoal, today, onOpenHealth) }
            }
        }
        }
    }

    selected?.let { e ->
        EventDetailSheet(
            event = e,
            loadReminders = vm::reminders,
            onDismiss = { selected = null },
            onEdit = {
                selected = null
                onEditEvent(e.eventId)
            },
            onDelete = {
                selected = null
                vm.deleteEvent(e.eventId)
            },
        )
    }
    checkup?.let { o -> CheckupSheet(o = o, onDismiss = { checkup = null }) }
}

private fun greeting(time: LocalTime): String = when (time.hour) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

private fun setupText(p: PermissionState): String {
    val missing = buildList {
        if (!p.calendar) add("Kalender")
        if (!p.notifications) add("Benachrichtigungen")
        if (!p.exactAlarms) add("pünktliche Alarme")
        if (!p.fullScreen) add("Vollbild")
    }
    return "Es fehlt noch: ${missing.joinToString(", ")}. Ohne das können Erinnerungen ausbleiben."
}

/** Gegen Zeitblindheit: Was läuft gerade, was kommt als Nächstes – und wie viel Zeit bleibt? */
@Composable
private fun NowNextCard(
    events: List<EventInstance>?,
    now: Long,
    zone: ZoneId,
    onClick: (EventInstance) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val timed = events.orEmpty().filter { !it.allDay && !it.declined }
    val current = timed.filter { it.begin <= now && it.end > now }.minByOrNull { it.end }
    val next = timed.filter { it.begin > now }.minByOrNull { it.begin }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { (next ?: current)?.let(onClick) ?: onOpenCalendar() },
    ) {
        Column(Modifier.padding(20.dp)) {
            if (current != null) {
                Text("JETZT", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text(current.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val total = (current.end - current.begin).coerceAtLeast(1)
                Text(
                    "noch ${TimeFormat.duration((current.end - now) / 60_000L + 1)} · bis ${TimeFormat.time(current.end, zone)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ((now - current.begin).toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    drawStopIndicator = {},
                )
                if (next != null) Spacer(Modifier.height(16.dp))
            }
            if (next != null) {
                Text("ALS NÄCHSTES", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(6.dp).height(40.dp).background(Color(next.color), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(next.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val day = Dates.toLocalDate(next.begin, zone)
                        Text(
                            "${TimeFormat.relativeDay(day)} ${TimeFormat.time(next.begin, zone)}" + (next.location?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    TimeFormat.countdown(next.begin, now).replaceFirstChar { it.titlecase(TimeFormat.locale) },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (current == null && next == null) {
                Text(
                    if (events == null) "Lade Termine …" else "Heute und morgen keine weiteren Termine 🎉",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun HealthMini(health: HealthSnapshot?, stepGoal: Int, today: LocalDate, onClick: () -> Unit) {
    val steps = health?.day(today)?.steps
    val sleep = health?.lastSleep
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniStat(
            Icons.AutoMirrored.Filled.DirectionsWalk,
            AppColors.Health,
            steps?.let { java.text.NumberFormat.getIntegerInstance(TimeFormat.locale).format(it) } ?: "–",
            "Schritte · Ziel ${java.text.NumberFormat.getIntegerInstance(TimeFormat.locale).format(stepGoal)}",
            Modifier.weight(1f),
        )
        MiniStat(
            Icons.Filled.Bedtime,
            AppColors.Sleep,
            sleep?.let { TimeFormat.duration(it.minutesAsleep) } ?: "–",
            "Schlaf letzte Nacht",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

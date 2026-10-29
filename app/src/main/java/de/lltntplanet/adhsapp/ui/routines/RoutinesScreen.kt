package de.lltntplanet.adhsapp.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.routines.Medication
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.ui.components.EmptyHint
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.SectionTitle
import de.lltntplanet.adhsapp.ui.components.SettingsAction
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RoutinesViewModel(private val c: AppContainer) : ViewModel() {
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    private val today = now.map { Dates.toLocalDate(it) }.distinctUntilChanged()

    val routines: StateFlow<List<RoutineEntity>?> =
        c.routines.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Abgehakte Einträge von gestern bis morgen (für Tagesliste und Nachholen über Mitternacht). */
    val log: StateFlow<List<RoutineLogEntity>> = today
        .flatMapLatest { d -> c.routines.observeLog(d.minusDays(1), d.plusDays(1)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Alle Vorkommen von heute, zeitlich sortiert. */
    val todayList: StateFlow<List<RoutineOccurrence>> = combine(routines, log, today) { r, l, d ->
        RoutineLogic.occurrences(r.orEmpty(), l, d, d, ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Aktive Symptome für den Checkup-Editor. */
    val symptoms: StateFlow<List<SymptomEntity>> = c.moods.observeSymptoms()
        .map { list -> list.filter { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Checkup-Werte der letzten 7 Tage für die kleine Übersichtskarte. */
    val recentMood: StateFlow<List<MoodEntryEntity>> = today
        .flatMapLatest { d -> c.moods.observeEntries(d.minusDays(6), d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Wunsch aus der Erinnerung, einen bestimmten Checkup zu öffnen. */
    val checkupRequest = c.checkupRequest

    fun clearCheckupRequest() {
        c.checkupRequest.value = null
    }

    fun ensureDefaultSymptoms() {
        viewModelScope.launch { c.moods.ensureDefaults() }
    }

    fun addSymptom(name: String) {
        viewModelScope.launch { c.moods.addSymptom(name) }
    }

    fun removeSymptom(s: SymptomEntity) {
        viewModelScope.launch { c.moods.removeSymptom(s) }
    }

    /** Vorschläge aus der eingebauten Medikamenten-Liste. */
    suspend fun suggest(query: String): List<Medication> = c.medications.suggest(query)

    fun save(routine: RoutineEntity) {
        viewModelScope.launch { c.routines.save(routine) }
    }

    fun delete(routine: RoutineEntity) {
        viewModelScope.launch { c.routines.delete(routine) }
    }

    /** Vergangene einmalige Einträge aufräumen (Checkup-Werte bleiben in der Statistik). */
    fun deleteAll(routines: List<RoutineEntity>) {
        viewModelScope.launch { routines.forEach { c.routines.delete(it) } }
    }

    fun setActive(routine: RoutineEntity, active: Boolean) {
        viewModelScope.launch { c.routines.save(routine.copy(active = active)) }
    }

    fun toggle(o: RoutineOccurrence) {
        viewModelScope.launch { c.routines.setDone(o.routine.id, o.date, o.minutes, !(o.done || o.skipped)) }
    }

    fun skip(o: RoutineOccurrence) {
        viewModelScope.launch { c.routines.skip(o.routine.id, o.date, o.minutes) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}, onOpenMoodStats: () -> Unit = {}) {
    val vm = appViewModel { RoutinesViewModel(it) }
    val now by vm.now.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val todayList by vm.todayList.collectAsStateWithLifecycle()
    val symptoms by vm.symptoms.collectAsStateWithLifecycle()
    val recentMood by vm.recentMood.collectAsStateWithLifecycle()
    val checkupRequest by vm.checkupRequest.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutineEntity?>(null) }
    var creating by remember { mutableStateOf<RoutineKind?>(null) }
    /** Neuer Eintrag direkt als „Einmalig“ (Knopf bei „Einmalige Erinnerungen“). */
    var creatingOnce by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RoutineOccurrence?>(null) }
    var checkup by remember { mutableStateOf<RoutineOccurrence?>(null) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    var confirmDeletePast by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val today = Dates.toLocalDate(now, zone)
    val hasCheckup = routines.orEmpty().any { it.kind == RoutineKind.CHECKUP }
    val regular = routines.orEmpty().filter { !it.isOnce }
    val onceUpcoming = routines.orEmpty()
        .filter { it.isOnce && !RoutineLogic.isPastOnce(it, today) }
        .sortedWith(compareBy({ it.onceEpochDay }, { RoutineLogic.times(it).firstOrNull() }, { it.name }))
    val oncePast = routines.orEmpty()
        .filter { RoutineLogic.isPastOnce(it, today) }
        .sortedWith(compareByDescending<RoutineEntity> { it.onceEpochDay }.thenBy { it.name })

    /** Checkup-Vorkommen: Abhaken heißt ausfüllen – das Blatt mit den Schiebereglern öffnen. */
    fun openOccurrence(o: RoutineOccurrence, toggle: Boolean) {
        if (o.routine.kind == RoutineKind.CHECKUP) checkup = o
        else if (toggle) vm.toggle(o)
        else selected = o
    }

    // „Jetzt ausfüllen“ aus der Erinnerung: passendes Vorkommen suchen (notfalls aus Routine + Zeitpunkt bauen).
    LaunchedEffect(checkupRequest, routines) {
        val req = checkupRequest ?: return@LaunchedEffect
        val r = routines?.firstOrNull { it.id == req.routineId } ?: return@LaunchedEffect
        val z = Dates.toZoned(req.atMillis, zone)
        val date = z.toLocalDate()
        val minutes = z.hour * 60 + z.minute
        checkup = todayList.firstOrNull { it.routine.id == r.id && it.date == date && it.minutes == minutes }
            ?: RoutineOccurrence(routine = r, date = date, minutes = minutes, at = req.atMillis)
        vm.clearCheckupRequest()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Routinen & Medikamente") },
                actions = {
                    if (hasCheckup) {
                        IconButton(onClick = onOpenMoodStats, modifier = Modifier.testTag("mood-stats")) {
                            Icon(Icons.Filled.Insights, contentDescription = "Statistik Gefühlslage")
                        }
                    }
                    SettingsAction(onOpenSettings)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creatingOnce = false; creating = RoutineKind.MEDICATION }, modifier = Modifier.testTag("routine-add")) {
                Icon(Icons.Filled.Add, "Routine oder Medikament hinzufügen")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            if (routines != null && routines!!.isEmpty()) {
                item {
                    InfoCard(
                        icon = Icons.Filled.Medication,
                        title = "Noch nichts eingetragen",
                        text = "Lege Medikamente oder feste Routinen mit Uhrzeiten an – die App erinnert dich pünktlich " +
                            "im Vollbild, und du hakst jede Einnahme ab wie in MyTherapy. Etwas nur ein einziges Mal? " +
                            "Dann beim Anlegen „Einmalig“ wählen und ein Datum setzen.",
                        buttonText = "Erstes Medikament anlegen",
                        onClick = { creatingOnce = false; creating = RoutineKind.MEDICATION },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            item { TodayProgressCard(todayList, now) }

            item { SectionTitle("Heute · ${TimeFormat.dayMonth(today)}") }
            if (routines != null && todayList.isEmpty()) item { EmptyHint("Heute steht nichts an.") }
            items(todayList, key = { it.key }) { o ->
                OccurrenceRow(o, now, onToggle = { openOccurrence(o, toggle = true) }, onClick = { openOccurrence(o, toggle = false) }, today = today, zone = zone)
            }

            if (hasCheckup) {
                item { SectionTitle("Gefühlslage", action = { TextButton(onClick = onOpenMoodStats) { Text("Statistik") } }) }
                item { MoodSummaryCard(recentMood, symptoms, onOpenMoodStats) }
            } else if (routines != null) {
                item {
                    InfoCard(
                        icon = Icons.Filled.Mood,
                        title = "Gefühls-Checkup",
                        text = "Lass dich regelmäßig fragen, wie es dir geht: Müdigkeit, Unruhe, Stimmung … jeweils auf einer Skala von 1 bis 10. " +
                            "Die Werte sammelt die App im Hintergrund für deine Statistik.",
                        buttonText = "Checkup einrichten",
                        onClick = { creatingOnce = false; creating = RoutineKind.CHECKUP },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            if (regular.isNotEmpty() || routines?.isEmpty() == true) item { SectionTitle("Meine Routinen & Medikamente") }
            if (routines != null && routines!!.isEmpty()) item { EmptyHint("Tippe auf +, um etwas anzulegen.") }
            items(regular, key = { "r${it.id}" }) { r ->
                RoutineCard(r, today, onClick = { editing = r }, onActiveChange = { vm.setActive(r, it) })
            }

            if (routines != null) {
                item {
                    SectionTitle("Einmalige Erinnerungen", action = {
                        TextButton(
                            onClick = { creatingOnce = true; creating = RoutineKind.MEDICATION },
                            modifier = Modifier.testTag("routine-add-once"),
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Einmalig")
                        }
                    })
                }
                if (onceUpcoming.isEmpty() && routines!!.isNotEmpty()) {
                    item {
                        EmptyHint("Etwas nur ein einziges Mal – z. B. eine einzelne Tablette oder ein zusätzlicher Checkup? Tippe auf „Einmalig“.")
                    }
                }
            }
            items(onceUpcoming, key = { "r${it.id}" }) { r ->
                RoutineCard(r, today, onClick = { editing = r }, onActiveChange = { vm.setActive(r, it) })
            }

            if (oncePast.isNotEmpty()) {
                item(key = "past-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showPast = !showPast }
                            .padding(top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Vorbei (${oncePast.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showPast) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (showPast) "Vergangene einklappen" else "Vergangene anzeigen",
                        )
                    }
                }
                if (showPast) {
                    items(oncePast, key = { "r${it.id}" }) { r ->
                        RoutineCard(r, today, onClick = { editing = r }, onActiveChange = { vm.setActive(r, it) }, past = true)
                    }
                    item(key = "past-delete") {
                        TextButton(
                            onClick = { confirmDeletePast = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Alle vergangenen löschen") }
                    }
                }
            }
        }
    }

    if (confirmDeletePast) {
        AlertDialog(
            onDismissRequest = { confirmDeletePast = false },
            title = { Text("Vergangene löschen?") },
            text = {
                val n = oncePast.size
                Text(
                    (if (n == 1) "Eine vergangene einmalige Erinnerung wird" else "$n vergangene einmalige Erinnerungen werden") +
                        " samt Abhak-Einträgen entfernt." +
                        if (oncePast.any { it.kind == RoutineKind.CHECKUP }) " Werte aus Checkups bleiben in der Statistik." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDeletePast = false; vm.deleteAll(oncePast); showPast = false }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDeletePast = false }) { Text("Abbrechen") } },
        )
    }

    creating?.let { kind ->
        RoutineEditorSheet(
            routine = null,
            initialKind = kind,
            initialOnce = creatingOnce,
            onDismiss = { creating = null },
            onSave = { vm.save(it); creating = null },
            onDelete = null,
            suggest = vm::suggest,
            symptoms = symptoms,
            onAddSymptom = vm::addSymptom,
            onRemoveSymptom = vm::removeSymptom,
            onCheckupChosen = vm::ensureDefaultSymptoms,
        )
    }
    editing?.let { r ->
        RoutineEditorSheet(
            routine = r,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(r); editing = null },
            suggest = vm::suggest,
            symptoms = symptoms,
            onAddSymptom = vm::addSymptom,
            onRemoveSymptom = vm::removeSymptom,
            onCheckupChosen = vm::ensureDefaultSymptoms,
        )
    }
    checkup?.let { o ->
        CheckupSheet(o = o, onDismiss = { checkup = null }, onOpenStats = onOpenMoodStats)
    }
    selected?.let { o ->
        OccurrenceSheet(
            o = o,
            now = now,
            onDismiss = { selected = null },
            onToggle = { vm.toggle(o); selected = null },
            onSkip = { vm.skip(o); selected = null },
            onEdit = { selected = null; editing = o.routine },
        )
    }
}

/** „3 von 5 geschafft“ – Fortschritt des Tages auf einen Blick. */
@Composable
private fun TodayProgressCard(list: List<RoutineOccurrence>, now: Long) {
    if (list.isEmpty()) return
    val done = list.count { it.done || it.skipped }
    val overdue = list.count { RoutineLogic.isOverdue(it, now) }
    val next = list.firstOrNull { !it.done && !it.skipped && it.at >= now - 60_000L }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (done == list.size) "Alles geschafft für heute 🎉" else "$done von ${list.size} geschafft",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { done.toFloat() / list.size },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            val hint = when {
                overdue > 0 -> "$overdue noch offen – bitte nachholen"
                next != null -> "Als Nächstes: ${next.routine.name} um ${TimeFormat.minutesOfDay(next.minutes)} (${TimeFormat.countdown(next.at, now)})"
                else -> "Nichts mehr offen"
            }
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = if (overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun RoutineCard(
    r: RoutineEntity,
    today: LocalDate,
    onClick: () -> Unit,
    onActiveChange: (Boolean) -> Unit,
    /** Vergangener einmaliger Eintrag: abgeblendet, ohne Pausieren-Schalter (es kommt ohnehin nichts mehr). */
    past: Boolean = false,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick).alpha(if (r.active && !past) 1f else 0.6f),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(routineIcon(r.kind), null, tint = routineColor(r.kind))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val times = RoutineLogic.times(r).joinToString(", ") { TimeFormat.minutesOfDay(it) }
                Text(
                    listOfNotNull(r.dose.ifBlank { null }, times, RoutineLogic.scheduleText(r, today), if (!r.active) "pausiert" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!past) Switch(checked = r.active, onCheckedChange = onActiveChange)
        }
    }
}

/** Kleine Übersicht der letzten 7 Tage: Anzahl Checkups und die drei stärksten Symptome. */
@Composable
private fun MoodSummaryCard(entries: List<MoodEntryEntity>, symptoms: List<SymptomEntity>, onOpen: () -> Unit) {
    val checkups = entries.distinctBy { it.epochDay to it.minutes }.size
    val top = entries.groupBy { it.symptomId }
        .mapNotNull { (id, list) -> symptoms.firstOrNull { it.id == id }?.let { it.name to list.map { e -> e.value }.average() } }
        .sortedByDescending { it.second }
        .take(3)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mood, null, tint = AppColorsMood)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (checkups == 0) "Noch kein Checkup in den letzten 7 Tagen" else "$checkups Checkup${if (checkups == 1) "" else "s"} in den letzten 7 Tagen",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (top.isEmpty()) "Die Werte erscheinen hier und in der Statistik."
                    else "Am stärksten: " + top.joinToString(", ") { "${it.first} ${"%.1f".format(TimeFormat.locale, it.second)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val AppColorsMood get() = de.lltntplanet.adhsapp.ui.theme.AppColors.Mood

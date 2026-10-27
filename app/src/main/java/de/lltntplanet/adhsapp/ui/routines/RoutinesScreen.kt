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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import de.lltntplanet.adhsapp.data.db.RoutineEntity
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

    /** Vorschläge aus der eingebauten Medikamenten-Liste. */
    suspend fun suggest(query: String): List<Medication> = c.medications.suggest(query)

    fun save(routine: RoutineEntity) {
        viewModelScope.launch { c.routines.save(routine) }
    }

    fun delete(routine: RoutineEntity) {
        viewModelScope.launch { c.routines.delete(routine) }
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
fun RoutinesScreen(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val vm = appViewModel { RoutinesViewModel(it) }
    val now by vm.now.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val todayList by vm.todayList.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RoutineEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RoutineOccurrence?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val today = Dates.toLocalDate(now, zone)

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Routinen & Medikamente") }, actions = { SettingsAction(onOpenSettings) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, modifier = Modifier.testTag("routine-add")) {
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
                            "im Vollbild, und du hakst jede Einnahme ab wie in MyTherapy.",
                        buttonText = "Erstes Medikament anlegen",
                        onClick = { creating = true },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            item { TodayProgressCard(todayList, now) }

            item { SectionTitle("Heute · ${TimeFormat.dayMonth(today)}") }
            if (routines != null && todayList.isEmpty()) item { EmptyHint("Heute steht nichts an.") }
            items(todayList, key = { it.key }) { o ->
                OccurrenceRow(o, now, onToggle = { vm.toggle(o) }, onClick = { selected = o }, today = today, zone = zone)
            }

            item { SectionTitle("Meine Routinen & Medikamente") }
            if (routines != null && routines!!.isEmpty()) item { EmptyHint("Tippe auf +, um etwas anzulegen.") }
            items(routines.orEmpty(), key = { "r${it.id}" }) { r ->
                RoutineCard(r, onClick = { editing = r }, onActiveChange = { vm.setActive(r, it) })
            }
        }
    }

    if (creating) {
        RoutineEditorSheet(
            routine = null,
            onDismiss = { creating = false },
            onSave = { vm.save(it); creating = false },
            onDelete = null,
            suggest = vm::suggest,
        )
    }
    editing?.let { r ->
        RoutineEditorSheet(
            routine = r,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(r); editing = null },
            suggest = vm::suggest,
        )
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
private fun RoutineCard(r: RoutineEntity, onClick: () -> Unit, onActiveChange: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick).alpha(if (r.active) 1f else 0.6f),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(routineIcon(r.kind), null, tint = routineColor(r.kind))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val times = RoutineLogic.times(r).joinToString(", ") { TimeFormat.minutesOfDay(it) }
                Text(
                    listOfNotNull(r.dose.ifBlank { null }, times, RoutineLogic.weekdaysText(r.weekdays), if (!r.active) "pausiert" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = r.active, onCheckedChange = onActiveChange)
        }
    }
}

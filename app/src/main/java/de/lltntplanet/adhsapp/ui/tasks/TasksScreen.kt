package de.lltntplanet.adhsapp.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.ui.components.SettingsAction
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.data.tasks.TaskSection
import de.lltntplanet.adhsapp.ui.components.EmptyHint
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.Dates
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val vm = appViewModel { TasksViewModel(it) }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Aufgaben") },
                actions = {
                    if (settings.tasksConnected) {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { vm.sync() }) { Icon(Icons.Filled.Sync, "Mit Google Tasks abgleichen") }
                        }
                    }
                    SettingsAction(onOpenSettings)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = TaskEntity(title = "", dueEpochDay = today.toEpochDay()) }, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Add, "Neue Aufgabe")
            }
        },
    ) { padding ->
        val all = tasks
        val grouped = remember(all, today) { TaskLogic.grouped(all.orEmpty(), today) }
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            item {
                OutlinedTextField(
                    value = quick,
                    onValueChange = { quick = it },
                    placeholder = { Text("Schnell notieren – für heute") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.quickAdd(quick, true); quick = "" }),
                    trailingIcon = {
                        IconButton(onClick = { vm.quickAdd(quick, true); quick = "" }, enabled = quick.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Hinzufügen")
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { TodayProgress(all.orEmpty(), today) }
            if (all != null && all.none { !it.done }) {
                item {
                    EmptyHint(
                        "Keine offenen Aufgaben. Tipp: Große Dinge in kleine Schritte zerlegen – jeder Haken zählt.",
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            grouped.forEach { (section, list) ->
                if (section == TaskSection.DONE) {
                    item(key = "done-header") {
                        Row(
                            Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Erledigt (${list.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(if (showDone) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                    }
                    if (showDone) {
                        items(list.sortedByDescending { it.doneAt ?: 0L }, key = { "t${it.id}" }) { t ->
                            TaskRow(t, onToggle = { vm.toggle(t) }, onClick = { editing = t }, today = today)
                        }
                    }
                } else {
                    item(key = "h-${section.name}") {
                        Text(
                            "${section.label} (${list.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (section == TaskSection.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { "t${it.id}" }) { t ->
                        Column {
                            TaskRow(t, onToggle = { vm.toggle(t) }, onClick = { editing = t }, today = today)
                            if (section == TaskSection.OVERDUE) {
                                TextButton(onClick = { vm.postpone(t) }, modifier = Modifier.padding(start = 48.dp)) {
                                    Text("Auf morgen verschieben")
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    editing?.let { t ->
        TaskEditorSheet(
            initial = t,
            googleConnected = settings.tasksConnected && settings.saveNewTasksToGoogle,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onDelete = {
                vm.delete(it)
                editing = null
            },
        )
    }
}

/** Kleiner Motivationsbalken: Wie viel von heute ist schon geschafft? */
@Composable
private fun TodayProgress(tasks: List<TaskEntity>, today: LocalDate) {
    val todayStart = Dates.startOfDayMillis(today)
    val doneToday = tasks.count { it.done && (it.doneAt ?: 0L) >= todayStart }
    val openToday = tasks.count { !it.done && TaskLogic.dueDate(it)?.let { d -> !d.isAfter(today) } == true }
    val total = doneToday + openToday
    if (total == 0) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            if (openToday == 0) "Alles für heute geschafft! 🎉" else "$doneToday von $total für heute geschafft",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { doneToday.toFloat() / total },
            color = AppColors.Task,
            modifier = Modifier.fillMaxWidth().height(8.dp),
            drawStopIndicator = {},
        )
    }
}

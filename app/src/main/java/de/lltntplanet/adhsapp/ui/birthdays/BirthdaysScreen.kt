package de.lltntplanet.adhsapp.ui.birthdays

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.birthdays.BirthdaySource
import de.lltntplanet.adhsapp.data.birthdays.UpcomingBirthday
import de.lltntplanet.adhsapp.data.db.BirthdayEntity
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BirthdaysViewModel(private val c: AppContainer) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    val refreshing: StateFlow<Boolean> = c.refresher.refreshing

    val upcoming: StateFlow<List<UpcomingBirthday>?> = combine(refresh, c.refresher.changes) { a, b -> a + b }
        .flatMapLatest { c.birthdays.observeAll() }
        .map { BirthdayMath.upcoming(it, LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun hasContacts() = c.birthdays.hasContactsPermission()

    fun reload() {
        refresh.value++
        c.requestReschedule()
    }

    /** Alles neu laden – Herunterziehen in der Geburtstagsliste. */
    fun refreshAll() {
        refresh.value++
        c.refresher.refreshLater(userInitiated = true)
    }

    fun save(entity: BirthdayEntity) {
        viewModelScope.launch {
            c.birthdays.saveManual(entity)
            c.requestReschedule()
        }
    }

    fun delete(entity: BirthdayEntity) {
        viewModelScope.launch {
            c.birthdays.deleteManual(entity)
            c.requestReschedule()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaysScreen(modifier: Modifier = Modifier) {
    val vm = appViewModel { BirthdaysViewModel(it) }
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var hasContacts by remember { mutableStateOf(vm.hasContacts()) }
    var editing by remember { mutableStateOf<BirthdayEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasContacts = vm.hasContacts()
        vm.reload()
    }
    LifecycleResumeEffect(Unit) {
        if (vm.hasContacts() != hasContacts) {
            hasContacts = vm.hasContacts()
            vm.reload()
        }
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Geburtstage") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = BirthdayEntity(name = "", month = 1, day = 1) }, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Add, "Geburtstag hinzufügen")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!hasContacts) {
                item {
                    InfoCard(
                        icon = Icons.Filled.Contacts,
                        title = "Geburtstage aus deinen Kontakten",
                        text = "Erlaube den Zugriff auf Kontakte, dann erscheinen alle Geburtstage aus Google Kontakte automatisch.",
                        buttonText = "Kontakte erlauben",
                        onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            item {
                val reminderText = if (settings.birthdayReminders) {
                    val days = settings.birthdayDaysBefore.sorted().joinToString(" und ") {
                        when (it) {
                            0 -> "am Tag"
                            1 -> "1 Tag vorher"
                            else -> "$it Tage vorher"
                        }
                    }
                    "Erinnerung $days um ${TimeFormat.minutesOfDay(settings.birthdayReminderMinutes)} Uhr"
                } else {
                    "Geburtstags-Erinnerungen sind ausgeschaltet (Einstellungen)."
                }
                Text(
                    reminderText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            val list = upcoming
            if (list != null && list.isEmpty()) {
                item { Text("Noch keine Geburtstage. Tippe auf +, um einen einzutragen.", Modifier.padding(vertical = 16.dp)) }
            }
            items(list.orEmpty(), key = { it.birthday.id }) { up ->
                BirthdayRow(up) {
                    val b = up.birthday
                    if (b.source == BirthdaySource.MANUAL && b.manualId != null) {
                        editing = BirthdayEntity(b.manualId, b.name, b.month, b.day, b.year, b.note)
                    }
                }
            }
        }
        }
    }

    editing?.let { entity ->
        BirthdayDialog(
            initial = entity,
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

@Composable
fun BirthdayRow(up: UpcomingBirthday, onClick: () -> Unit) {
    val b = up.birthday
    val soon = up.daysUntil <= 1
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (up.daysUntil == 0L) AppColors.Birthday.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(AppColors.Birthday.copy(alpha = if (soon) 1f else 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (soon) {
                Icon(Icons.Filled.Cake, null, tint = MaterialTheme.colorScheme.surface)
            } else {
                Text(b.name.take(1).uppercase(), color = AppColors.Birthday, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (soon) FontWeight.Bold else FontWeight.Normal)
            val age = up.age?.let { " · wird $it" }.orEmpty()
            Text(
                "${TimeFormat.dayMonth(up.date)}$age" + if (b.source == BirthdaySource.CONTACT) " · Kontakt" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when (up.daysUntil) {
                0L -> "Heute 🎉"
                1L -> "Morgen"
                else -> "in ${up.daysUntil} T."
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (soon) AppColors.Birthday else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BirthdayDialog(
    initial: BirthdayEntity,
    onDismiss: () -> Unit,
    onSave: (BirthdayEntity) -> Unit,
    onDelete: (BirthdayEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var day by remember { mutableStateOf(if (initial.id == 0L) "" else initial.day.toString()) }
    var month by remember { mutableStateOf(if (initial.id == 0L) "" else initial.month.toString()) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var attempt by remember { mutableIntStateOf(0) }
    val d = day.toIntOrNull()
    val m = month.toIntOrNull()
    val y = year.toIntOrNull()
    val valid = name.isNotBlank() && m != null && m in 1..12 && d != null &&
        d in 1..java.time.Month.of(m).maxLength() && (year.isBlank() || (y != null && y in 1900..LocalDate.now().year))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Geburtstag eintragen" else "Geburtstag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(day, { day = it.take(2) }, Modifier.weight(1f), label = { Text("Tag") }, singleLine = true, keyboardOptions = numbers)
                    OutlinedTextField(month, { month = it.take(2) }, Modifier.weight(1f), label = { Text("Monat") }, singleLine = true, keyboardOptions = numbers)
                    OutlinedTextField(year, { year = it.take(4) }, Modifier.weight(1.4f), label = { Text("Jahr") }, singleLine = true, keyboardOptions = numbers)
                }
                if (attempt > 0 && !valid) Text("Bitte Name und ein gültiges Datum eingeben.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                attempt++
                if (valid) onSave(initial.copy(name = name.trim(), day = d!!, month = m!!, year = y))
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) TextButton(onClick = { onDelete(initial) }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

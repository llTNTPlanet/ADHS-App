package de.lltntplanet.adhsapp.ui.calendar

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.settings.CalendarViewMode
import de.lltntplanet.adhsapp.ui.components.DatePickDialog
import de.lltntplanet.adhsapp.ui.components.InfoCard
import de.lltntplanet.adhsapp.ui.components.appViewModel
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private const val CENTER_PAGE = 5_000
private const val PAGE_COUNT = 10_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onCreateEvent: (LocalDateTime) -> Unit,
    onEditEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { CalendarViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var hasPermission by remember { mutableStateOf(vm.hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = vm.hasPermission()
        vm.refresh()
    }
    LifecycleResumeEffect(Unit) {
        val now = vm.hasPermission()
        if (now != hasPermission) {
            hasPermission = now
            vm.refresh()
        }
        onPauseOrDispose { }
    }

    // Basisdatum der Pager; ändert sich bei "Heute" oder Datumsauswahl → Pager wird neu aufgebaut.
    var base by remember { mutableStateOf(LocalDate.now()) }
    var jumpCounter by remember { mutableIntStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<EventInstance?>(null) }
    val mode = settings.calendarView

    fun jumpTo(date: LocalDate) {
        base = date
        vm.setAnchor(date)
        jumpCounter++
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(
                        "Google Kalender",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                    CalendarViewMode.entries.forEach { m ->
                        NavigationDrawerItem(
                            label = { Text(m.label) },
                            icon = { Icon(m.icon(), null) },
                            selected = m == mode,
                            onClick = {
                                vm.setView(m)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    calendars.groupBy { it.accountName }.forEach { (account, list) ->
                        Text(
                            account,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                        list.forEach { cal ->
                            Row(
                                Modifier
                                    .clickable { vm.toggleCalendar(cal.id) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = cal.id !in settings.hiddenCalendars,
                                    onCheckedChange = { vm.toggleCalendar(cal.id) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(cal.color),
                                        uncheckedColor = Color(cal.color),
                                    ),
                                )
                                Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menü")
                        }
                    },
                    title = {
                        Row(
                            Modifier.clickable { showDatePicker = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(TimeFormat.monthYear(anchor), fontWeight = FontWeight.Medium)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Datum wählen")
                        }
                    },
                    actions = {
                        TodayButton(onClick = { jumpTo(LocalDate.now()) })
                    },
                )
            },
            floatingActionButton = {
                if (hasPermission) {
                    FloatingActionButton(
                        onClick = {
                            val date = anchor
                            val nextHour = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                            onCreateEvent(date.atTime(if (nextHour.hour == 0) LocalTime.of(23, 0) else nextHour))
                        },
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Filled.Add, contentDescription = "Neuer Termin") }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (!hasPermission) {
                    Column(Modifier.padding(16.dp)) {
                        InfoCard(
                            icon = Icons.Outlined.CalendarToday,
                            title = "Zugriff auf Google Kalender",
                            text = "Die App liest deine Termine aus dem Google Kalender auf diesem Handy. " +
                                "Neue Termine landen direkt in deinem Google Kalender.",
                            buttonText = "Zugriff erlauben",
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                                )
                            },
                        )
                    }
                } else {
                    key(mode, jumpCounter) {
                        when (mode) {
                            CalendarViewMode.SCHEDULE -> ScheduleView(
                                start = base,
                                version = version,
                                hidden = settings.hiddenCalendars,
                                load = vm::load,
                                onVisibleDate = vm::setAnchor,
                                onEventClick = { selectedEvent = it },
                            )
                            CalendarViewMode.MONTH -> MonthPager(
                                base = base,
                                version = version,
                                hidden = settings.hiddenCalendars,
                                load = vm::load,
                                onPageDate = vm::setAnchor,
                                onDayClick = { date ->
                                    vm.setView(CalendarViewMode.DAY)
                                    jumpTo(date)
                                },
                                onEventClick = { selectedEvent = it },
                            )
                            else -> TimeGridPager(
                                mode = mode,
                                base = base,
                                version = version,
                                hidden = settings.hiddenCalendars,
                                load = vm::load,
                                zone = zone,
                                onPageDate = vm::setAnchor,
                                onEventClick = { selectedEvent = it },
                                onSlotClick = onCreateEvent,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickDialog(initial = anchor, onDismiss = { showDatePicker = false }, onPick = { jumpTo(it) })
    }

    selectedEvent?.let { event ->
        EventDetailSheet(
            event = event,
            loadReminders = vm::reminders,
            onDismiss = { selectedEvent = null },
            onEdit = {
                selectedEvent = null
                onEditEvent(event.eventId)
            },
            onDelete = {
                selectedEvent = null
                vm.delete(event.eventId)
            },
        )
    }
}

private fun CalendarViewMode.icon(): ImageVector = when (this) {
    CalendarViewMode.SCHEDULE -> Icons.Filled.ViewAgenda
    CalendarViewMode.DAY -> Icons.Filled.CalendarViewDay
    CalendarViewMode.THREE_DAY -> Icons.Filled.ViewColumn
    CalendarViewMode.WEEK -> Icons.Filled.CalendarViewWeek
    CalendarViewMode.MONTH -> Icons.Filled.CalendarMonth
}

/** Wie in Google Kalender: ein Kalenderblatt mit dem heutigen Tag. */
@Composable
private fun TodayButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = "Heute")
            Text(
                LocalDate.now().dayOfMonth.toString(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Lädt Termine für einen Zeitraum neu, sobald sich der Kalender oder die Filter ändern. */
@Composable
fun rememberEvents(
    from: LocalDate,
    toExclusive: LocalDate,
    version: Int,
    hidden: Set<Long>,
    load: suspend (LocalDate, LocalDate) -> List<EventInstance>,
): List<EventInstance> = rememberEventsOrNull(from, toExclusive, version, hidden, load).orEmpty()

/** Wie [rememberEvents], aber null, solange noch nichts geladen wurde. */
@Composable
fun rememberEventsOrNull(
    from: LocalDate,
    toExclusive: LocalDate,
    version: Int,
    hidden: Set<Long>,
    load: suspend (LocalDate, LocalDate) -> List<EventInstance>,
): List<EventInstance>? {
    val events by produceState<List<EventInstance>?>(initialValue = null, from, toExclusive, version, hidden) {
        value = load(from, toExclusive)
    }
    return events
}

@Composable
private fun TimeGridPager(
    mode: CalendarViewMode,
    base: LocalDate,
    version: Int,
    hidden: Set<Long>,
    load: suspend (LocalDate, LocalDate) -> List<EventInstance>,
    zone: ZoneId,
    onPageDate: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
    onSlotClick: (LocalDateTime) -> Unit,
) {
    val days = when (mode) {
        CalendarViewMode.DAY -> 1
        CalendarViewMode.THREE_DAY -> 3
        else -> 7
    }
    val start0 = if (mode == CalendarViewMode.WEEK) Dates.weekStart(base) else base
    fun pageStart(page: Int): LocalDate = start0.plusDays((page - CENTER_PAGE).toLong() * days)

    val pagerState = rememberPagerState(initialPage = CENTER_PAGE) { PAGE_COUNT }
    val verticalScroll = rememberScrollState()
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val start = pageStart(page)
            val today = LocalDate.now()
            val inPage = !today.isBefore(start) && today.isBefore(start.plusDays(days.toLong()))
            onPageDate(if (inPage) today else start)
        }
    }
    HorizontalPager(state = pagerState, beyondViewportPageCount = 1) { page ->
        val start = pageStart(page)
        val events = rememberEvents(start, start.plusDays(days.toLong()), version, hidden, load)
        TimeGrid(
            days = (0 until days).map { start.plusDays(it.toLong()) },
            events = events,
            zone = zone,
            scrollState = verticalScroll,
            onEventClick = onEventClick,
            onSlotClick = onSlotClick,
        )
    }
}

@Composable
private fun MonthPager(
    base: LocalDate,
    version: Int,
    hidden: Set<Long>,
    load: suspend (LocalDate, LocalDate) -> List<EventInstance>,
    onPageDate: (LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
) {
    val baseMonth = YearMonth.from(base)
    val pagerState = rememberPagerState(initialPage = CENTER_PAGE) { PAGE_COUNT }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val month = baseMonth.plusMonths((page - CENTER_PAGE).toLong())
            onPageDate(if (YearMonth.now() == month) LocalDate.now() else month.atDay(1))
        }
    }
    HorizontalPager(state = pagerState, beyondViewportPageCount = 1) { page ->
        val month = baseMonth.plusMonths((page - CENTER_PAGE).toLong())
        val gridStart = Dates.weekStart(month.atDay(1))
        val gridEnd = Dates.weekStart(month.atEndOfMonth()).plusDays(7)
        val events = rememberEvents(gridStart, gridEnd, version, hidden, load)
        MonthGrid(
            month = month,
            gridStart = gridStart,
            gridEndExclusive = gridEnd,
            events = events,
            onDayClick = onDayClick,
            onEventClick = onEventClick,
        )
    }
}

/** Farbiger Termin-Baustein wie in Google Kalender. */
@Composable
fun EventChip(
    event: EventInstance,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    compact: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    onClick: () -> Unit,
) {
    val color = Color(event.color)
    val content = de.lltntplanet.adhsapp.ui.theme.contentColorFor(color)
    Column(
        modifier
            .background(color.copy(alpha = if (event.declined) 0.4f else 1f), RoundedCornerShape(if (compact) 4.dp else 8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = if (compact) 1.dp else 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            event.displayTitle,
            color = content,
            fontSize = if (compact) 11.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            lineHeight = if (compact) 13.sp else 18.sp,
        )
        if (showTime && !compact) {
            Text(
                eventTimeText(event, zone),
                color = content.copy(alpha = 0.9f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

fun eventTimeText(event: EventInstance, zone: ZoneId = ZoneId.systemDefault()): String {
    if (event.allDay) {
        val start = event.startDate(zone)
        val end = event.endDateInclusive(zone)
        return if (start == end) "Ganztägig" else "Ganztägig · ${TimeFormat.dayMonthShort(start)} – ${TimeFormat.dayMonthShort(end)}"
    }
    return "${TimeFormat.time(event.begin, zone)} – ${TimeFormat.time(event.end, zone)}" +
        (event.location?.let { " · $it" } ?: "")
}

@Composable
fun DayBadge(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier, compact: Boolean = false) {
    val isToday = date == today
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            TimeFormat.weekdayShort(date.dayOfWeek).removeSuffix("."),
            fontSize = 11.sp,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .size(if (compact) 30.dp else 36.dp)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = if (compact) 16.sp else 20.sp,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

internal val ScheduleContentPadding = PaddingValues(bottom = 96.dp)

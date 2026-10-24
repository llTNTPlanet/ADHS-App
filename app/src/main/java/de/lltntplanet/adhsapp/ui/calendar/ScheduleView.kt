package de.lltntplanet.adhsapp.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TreeMap

sealed class ScheduleItem(val key: String, val date: LocalDate) {
    class MonthHeader(val month: YearMonth) : ScheduleItem("m-$month", month.atDay(1))
    class Day(date: LocalDate, val events: List<EventInstance>) : ScheduleItem("d-$date", date)
    class WeekRange(val start: LocalDate) : ScheduleItem("w-$start", start)
}

object ScheduleBuilder {
    /** Baut die Liste der Terminübersicht: Monatsüberschriften, Tage mit Terminen, leere Wochen. */
    fun build(
        events: List<EventInstance>,
        from: LocalDate,
        toExclusive: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): List<ScheduleItem> {
        val byDay = TreeMap<LocalDate, MutableList<EventInstance>>()
        val lastDay = toExclusive.minusDays(1)
        events.forEach { e ->
            var d = maxOf(e.startDate(zone), from)
            val last = minOf(e.endDateInclusive(zone), lastDay)
            while (!d.isAfter(last)) {
                byDay.getOrPut(d) { mutableListOf() }.add(e)
                d = d.plusDays(1)
            }
        }
        if (!today.isBefore(from) && today.isBefore(toExclusive)) byDay.getOrPut(today) { mutableListOf() }

        val items = mutableListOf<ScheduleItem>()
        var currentMonth: YearMonth? = null
        fun header(month: YearMonth) {
            if (month != currentMonth) {
                items += ScheduleItem.MonthHeader(month)
                currentMonth = month
            }
        }
        var week = Dates.weekStart(from)
        while (week.isBefore(toExclusive)) {
            val weekEnd = week.plusDays(7)
            val days = byDay.subMap(maxOf(week, from), true, weekEnd, false)
            if (days.isEmpty()) {
                header(YearMonth.from(week))
                items += ScheduleItem.WeekRange(week)
            } else {
                days.forEach { (date, list) ->
                    header(YearMonth.from(date))
                    items += ScheduleItem.Day(
                        date,
                        list.sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.begin }),
                    )
                }
            }
            week = weekEnd
        }
        return items
    }

    /** Position des Tages [date] (bzw. der Woche, in der er liegt). */
    fun indexFor(items: List<ScheduleItem>, date: LocalDate): Int = items.indexOfFirst {
        when (it) {
            is ScheduleItem.Day -> !it.date.isBefore(date)
            is ScheduleItem.WeekRange -> !it.start.plusDays(6).isBefore(date)
            is ScheduleItem.MonthHeader -> false
        }
    }
}

/** Terminübersicht (Agenda) wie in der Google-Kalender-App. */
@Composable
fun ScheduleView(
    start: LocalDate,
    version: Int,
    hidden: Set<Long>,
    load: suspend (LocalDate, LocalDate) -> List<EventInstance>,
    onVisibleDate: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now()
    val rangeStart = remember(start) { Dates.weekStart(start.minusDays(14)) }
    var rangeEnd by remember(start) { mutableStateOf(start.plusDays(120)) }
    val loaded = rememberEventsOrNull(rangeStart, rangeEnd, version, hidden, load)
    val events = loaded.orEmpty()
    val items = remember(events, rangeStart, rangeEnd) { ScheduleBuilder.build(events, rangeStart, rangeEnd, today, zone) }
    val listState = rememberLazyListState()
    var initialScrollDone by remember { mutableStateOf(false) }

    LaunchedEffect(items, loaded == null) {
        if (!initialScrollDone && loaded != null) {
            val index = ScheduleBuilder.indexFor(items, start)
            if (index >= 0) listState.scrollToItem(index)
            initialScrollDone = true
        }
    }
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (items.isNotEmpty() && last >= items.size - 5) rangeEnd = rangeEnd.plusDays(90)
            }
    }
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { i -> items.getOrNull(i)?.date?.let(onVisibleDate) }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ScheduleContentPadding) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ScheduleItem.MonthHeader -> Text(
                    TimeFormat.monthYear(item.month.atDay(1)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 72.dp, top = 24.dp, bottom = 8.dp),
                )
                is ScheduleItem.WeekRange -> Text(
                    "${TimeFormat.dayMonthShort(item.start)} – ${TimeFormat.dayMonthShort(item.start.plusDays(6))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 72.dp, top = 8.dp, bottom = 8.dp),
                )
                is ScheduleItem.Day -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    DayBadge(item.date, today, Modifier.width(56.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp, top = 4.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (item.events.isEmpty()) {
                            Text(
                                "Heute nichts geplant",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        item.events.forEach { e ->
                            EventChip(e, Modifier.fillMaxWidth(), zone = zone, onClick = { onEventClick(e) })
                        }
                    }
                }
            }
        }
    }
}

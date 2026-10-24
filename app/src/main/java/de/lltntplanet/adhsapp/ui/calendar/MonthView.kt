package de.lltntplanet.adhsapp.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Monatsansicht: Wochen untereinander, pro Tag einige Termine als farbige Streifen. */
@Composable
fun MonthGrid(
    month: YearMonth,
    gridStart: LocalDate,
    gridEndExclusive: LocalDate,
    events: List<EventInstance>,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val weeks = (Dates.daysBetween(gridStart, gridEndExclusive) / 7).toInt()
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    TimeFormat.weekdayNarrow(d),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = lineColor)
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (d in 0 until 7) {
                    val date = gridStart.plusDays((w * 7 + d).toLong())
                    val dayEvents = events
                        .filter { it.occursOn(date, zone) }
                        .sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.begin })
                    MonthCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        events = dayEvents,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onDayClick(date) },
                        onEventClick = onEventClick,
                    )
                }
            }
            HorizontalDivider(color = lineColor)
        }
    }
}

@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    events: List<EventInstance>,
    modifier: Modifier,
    onClick: () -> Unit,
    onEventClick: (EventInstance) -> Unit,
) {
    BoxWithConstraints(modifier.clickable(onClick = onClick).padding(1.dp)) {
        val maxChips = ((maxHeight - 26.dp) / 17.dp).toInt().coerceAtLeast(0)
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 2.dp)
                    .size(22.dp)
                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    fontSize = 12.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        inMonth -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
            val visible = if (events.size > maxChips) events.take((maxChips - 1).coerceAtLeast(0)) else events
            visible.forEach { e ->
                EventChip(
                    e,
                    compact = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp),
                    onClick = { onEventClick(e) },
                )
            }
            if (events.size > visible.size) {
                Text(
                    "+${events.size - visible.size}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

package de.lltntplanet.adhsapp.ui.calendar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.ui.theme.contentColorFor
import de.lltntplanet.adhsapp.util.TimeFormat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val HourHeight = 56.dp
private val Gutter = 48.dp

/** Tages-, 3-Tages- und Wochenansicht mit Stundenraster wie in Google Kalender. */
@Composable
fun TimeGrid(
    days: List<LocalDate>,
    events: List<EventInstance>,
    zone: ZoneId,
    scrollState: ScrollState,
    onEventClick: (EventInstance) -> Unit,
    onSlotClick: (LocalDateTime) -> Unit,
) {
    val today = LocalDate.now()
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalTime.now()
        }
    }
    val density = LocalDensity.current
    val hourPx = with(density) { HourHeight.toPx() }
    LaunchedEffect(Unit) {
        if (scrollState.value == 0) scrollState.scrollTo((maxOf(0, LocalTime.now().hour - 1) * hourPx).toInt())
    }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val compact = days.size > 3

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Gutter, top = 4.dp, bottom = 4.dp)) {
            days.forEach { DayBadge(it, today, Modifier.weight(1f), compact = compact) }
        }
        val allDay = days.map { d -> events.filter { it.allDay && it.occursOn(d, zone) } }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(start = Gutter, bottom = 4.dp)) {
                allDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        list.take(3).forEach { e ->
                            EventChip(
                                e,
                                compact = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                onClick = { onEventClick(e) },
                            )
                        }
                        if (list.size > 3) {
                            Text("+${list.size - 3}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = lineColor)
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Box(Modifier.width(Gutter).height(HourHeight * 24)) {
                for (h in 1..23) {
                    Text(
                        "${h.toString().padStart(2, '0')}:00",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = HourHeight * h - 8.dp)
                            .padding(end = 6.dp),
                    )
                }
            }
            days.forEach { day ->
                val positioned = remember(events, day) { EventLayout.layoutDay(events, day, zone) }
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .height(HourHeight * 24)
                        .drawBehind {
                            drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                            for (h in 1..23) {
                                val y = h * hourPx
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            }
                        }
                        .pointerInput(day) {
                            detectTapGestures { offset ->
                                val minutes = ((offset.y / hourPx) * 60).toInt().coerceIn(0, 23 * 60 + 30)
                                val slot = (minutes / 30) * 30
                                onSlotClick(day.atTime(slot / 60, slot % 60))
                            }
                        },
                ) {
                    val columnWidth = maxWidth
                    positioned.forEach { p ->
                        val width = columnWidth / p.columns
                        Box(
                            Modifier
                                .offset(x = width * p.column, y = HourHeight * (p.startMinute / 60f))
                                .width(width)
                                .height(HourHeight * ((p.endMinute - p.startMinute) / 60f))
                                .padding(start = 1.dp, end = 2.dp, bottom = 1.dp),
                        ) {
                            GridEvent(p.event, zone, compact, onClick = { onEventClick(p.event) })
                        }
                    }
                    if (day == today) {
                        val y = HourHeight * (now.toSecondOfDay() / 3600f)
                        Box(
                            Modifier
                                .offset(y = y - 1.dp)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(AppColors.NowRed),
                        )
                        Box(
                            Modifier
                                .offset(x = (-6).dp, y = y - 6.dp)
                                .size(12.dp)
                                .background(AppColors.NowRed, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridEvent(event: EventInstance, zone: ZoneId, compact: Boolean, onClick: () -> Unit) {
    val color = Color(event.color)
    val content = contentColorFor(color)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(color.copy(alpha = if (event.declined) 0.4f else 1f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        val showTime = maxHeight > 34.dp
        Column {
            Text(
                event.displayTitle,
                color = content,
                fontSize = if (compact) 11.sp else 13.sp,
                lineHeight = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (showTime) 2 else 1,
            )
            if (showTime) {
                Spacer(Modifier.height(1.dp))
                Text(
                    "${TimeFormat.time(event.begin, zone)}–${TimeFormat.time(event.end, zone)}",
                    color = content.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

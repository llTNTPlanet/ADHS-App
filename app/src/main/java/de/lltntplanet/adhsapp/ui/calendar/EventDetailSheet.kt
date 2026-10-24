package de.lltntplanet.adhsapp.ui.calendar

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    event: EventInstance,
    loadReminders: suspend (Long) -> List<Int>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val reminders by produceState(initialValue = emptyList<Int>(), event.eventId) { value = loadReminders(event.eventId) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(18.dp)
                        .background(Color(event.color), RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(event.displayTitle, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(dateLine(event, zone), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (event.recurring) DetailRow(Icons.Filled.Repeat, "Wiederkehrender Termin")
            event.location?.let { location ->
                DetailRow(Icons.Filled.Place, location) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(location)).toUri()))
                    }
                }
            }
            DetailRow(Icons.Filled.Event, event.calendarName)
            if (reminders.isNotEmpty()) {
                DetailRow(Icons.Filled.Notifications, reminders.joinToString(", ") { TimeFormat.formatMinutesBefore(it) })
            }
            event.description?.let {
                val plain = HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                if (plain.isNotEmpty()) DetailRow(Icons.AutoMirrored.Filled.Notes, plain)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (event.canEdit && !event.recurring) {
                    OutlinedButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Bearbeiten")
                    }
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Löschen")
                    }
                }
            }
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
                    ).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
                    runCatching { context.startActivity(intent) }
                },
            ) { Text(if (event.recurring) "In Google Kalender öffnen (Serie bearbeiten)" else "In Google Kalender öffnen") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Termin löschen?") },
            text = { Text("\"${event.displayTitle}\" wird auch aus deinem Google Kalender gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun dateLine(e: EventInstance, zone: ZoneId): String {
    val start = e.startDate(zone)
    val end = e.endDateInclusive(zone)
    if (e.allDay) {
        return if (start == end) "${TimeFormat.fullDate(start)} · ganztägig"
        else "${TimeFormat.fullDate(start)} – ${TimeFormat.fullDate(end)}"
    }
    val endDay = Dates.toLocalDate(e.end, zone)
    return if (endDay == start) {
        "${TimeFormat.fullDate(start)} · ${TimeFormat.time(e.begin, zone)} – ${TimeFormat.time(e.end, zone)}"
    } else {
        "${TimeFormat.dayMonthShort(start)} ${TimeFormat.time(e.begin, zone)} – ${TimeFormat.dayMonthShort(endDay)} ${TimeFormat.time(e.end, zone)}"
    }
}

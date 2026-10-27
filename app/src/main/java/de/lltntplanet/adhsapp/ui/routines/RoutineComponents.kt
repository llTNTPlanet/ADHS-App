package de.lltntplanet.adhsapp.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.LocalDate
import java.time.ZoneId

fun routineColor(kind: RoutineKind): Color = when (kind) {
    RoutineKind.MEDICATION -> AppColors.Medication
    RoutineKind.ROUTINE -> AppColors.Routine
}

fun routineIcon(kind: RoutineKind): ImageVector = when (kind) {
    RoutineKind.MEDICATION -> Icons.Filled.Medication
    RoutineKind.ROUTINE -> Icons.Filled.Repeat
}

/** Zeile „Uhrzeit · Name · Dosis“ mit großem Abhak-Kreis – wie die Tagesliste in MyTherapy. */
@Composable
fun OccurrenceRow(
    o: RoutineOccurrence,
    now: Long,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    showDay: Boolean = false,
) {
    val color = routineColor(o.routine.kind)
    val checked = o.done || o.skipped
    val overdue = RoutineLogic.isOverdue(o, now)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (checked) "Haken entfernen" else if (o.routine.kind == RoutineKind.MEDICATION) "Genommen" else "Erledigt",
                tint = if (o.done) color else if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                o.routine.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (checked) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val time = TimeFormat.minutesOfDay(o.minutes)
            val dayPrefix = if (showDay && o.date != today) "${TimeFormat.relativeDay(o.date, today)}, " else ""
            val status = when {
                o.skipped -> "übersprungen"
                o.done -> if (o.routine.kind == RoutineKind.MEDICATION) "genommen" else "erledigt"
                overdue -> "noch offen"
                o.at > now -> TimeFormat.countdown(o.at, now)
                else -> "jetzt"
            }
            Text(
                listOfNotNull("$dayPrefix$time", o.routine.dose.ifBlank { null }, status).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

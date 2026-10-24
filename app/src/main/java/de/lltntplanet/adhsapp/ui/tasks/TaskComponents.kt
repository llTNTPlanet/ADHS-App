package de.lltntplanet.adhsapp.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.ui.theme.AppColors
import de.lltntplanet.adhsapp.util.TimeFormat
import java.time.LocalDate

fun taskDueText(task: TaskEntity, today: LocalDate = LocalDate.now()): String? {
    val date = TaskLogic.dueDate(task) ?: return null
    val day = TimeFormat.relativeDay(date, today)
    return task.dueMinutes?.let { "$day, ${TimeFormat.minutesOfDay(it)}" } ?: day
}

/** Eine Aufgabe mit großem Abhak-Kreis. */
@Composable
fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val overdue = !task.done && TaskLogic.dueDate(task)?.isBefore(today) == true
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (task.done) "Als offen markieren" else "Erledigt",
                tint = if (task.done) AppColors.Task else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val parts = buildList {
                taskDueText(task, today)?.let { add(it) }
                task.googleListTitle?.let { add("Google Tasks · $it") }
                if (task.notes.isNotBlank()) add(task.notes.lines().first())
            }
            if (parts.isNotEmpty() || task.repeat != Repeat.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.repeat != Repeat.NONE) {
                        Icon(Icons.Filled.Repeat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (task.remind && task.dueEpochDay != null && !task.done) {
                        Icon(Icons.Filled.Notifications, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (task.important) Icon(Icons.Filled.Star, contentDescription = "Wichtig", tint = AppColors.Warning)
    }
}

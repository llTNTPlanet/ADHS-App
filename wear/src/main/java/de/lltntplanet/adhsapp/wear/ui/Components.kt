package de.lltntplanet.adhsapp.wear.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardColors
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.LinearProgressIndicatorDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import de.lltntplanet.adhsapp.wear.ui.theme.WearColors

/**
 * Gerüst jeder Seite: eine Liste, die sich dem runden Bildschirm anpasst. [content] baut die Zeilen und bekommt
 * dazu die Vorgabe, wie sie sich am Rand verkleinern.
 */
@Composable
internal fun WatchList(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            content(this, spec)
        }
    }
}

/** Überschrift einer Seite. */
internal fun TransformingLazyColumnScope.title(text: String, spec: TransformationSpec) {
    item(key = "title") {
        ListHeader(
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        ) {
            Text(text = text, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Überschrift eines Abschnitts. [key] muss in der Liste einmalig sein. */
internal fun TransformingLazyColumnScope.section(key: String, text: String, spec: TransformationSpec) {
    item(key = key) {
        ListSubHeader(
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        ) {
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kurzer Text ohne Fläche, z. B. „Nichts offen“. Mit [warning] in der Warnfarbe. */
internal fun TransformingLazyColumnScope.hint(key: String, text: String, warning: Boolean = false) {
    item(key = key) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (warning) WearColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Aufgaben zum Abhaken: erst die offenen ([open]), dann die zuletzt auf der Uhr abgehakten ([done]). */
internal fun TransformingLazyColumnScope.taskItems(
    open: List<WatchTask>,
    done: List<WatchTask>,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchTask, Boolean) -> Unit,
) {
    items(items = open, key = { task -> "task:${task.id}" }) { task ->
        TaskRow(task = task, checked = false, today = today, spec = spec, onToggle = onToggle)
    }
    items(items = done, key = { task -> "done:${task.id}" }) { task ->
        TaskRow(task = task, checked = true, today = today, spec = spec, onToggle = onToggle)
    }
}

/** Ein Vorkommen einer Routine: zum Abhaken – ein Checkup nur zum Lesen, er wird am Handy ausgefüllt. */
@Composable
internal fun TransformingLazyColumnItemScope.RoutineRow(
    routine: WatchRoutine,
    now: Long,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchRoutine, Boolean) -> Unit,
) {
    val rowModifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
    val transformation = SurfaceTransformation(spec)
    val line = WatchFormat.routineLine(routine, now, today)
    if (routine.canToggle) {
        val overdue = WatchSnapshotLogic.isOverdue(routine, now)
        CheckboxButton(
            checked = routine.done,
            onCheckedChange = { checked -> onToggle(routine, checked) },
            modifier = rowModifier,
            transformation = transformation,
            secondaryLabel = {
                Text(text = line, color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified)
            },
            label = { Text(text = routine.name) },
        )
    } else {
        Card(onClick = {}, modifier = rowModifier, enabled = false, transformation = transformation) {
            Text(text = routine.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = line,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Eine Aufgabe zum Abhaken. [checked] = gerade auf der Uhr abgehakt; der Haken lässt sich dann wieder entfernen –
 * außer bei wiederkehrenden Aufgaben, die am Handy schon weitergerückt sind.
 */
@Composable
internal fun TransformingLazyColumnItemScope.TaskRow(
    task: WatchTask,
    checked: Boolean,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchTask, Boolean) -> Unit,
) {
    val line = if (checked) "erledigt" else WatchFormat.taskLine(task, today)
    val overdue = !checked && WatchSnapshotLogic.isOverdue(task, today)
    // Stern und zweite Zeile gibt es nicht bei jeder Aufgabe – deshalb stehen sie hier mit ausgeschriebenem Typ.
    val star: @Composable BoxScope.() -> Unit = {
        Icon(imageVector = Icons.Filled.Star, contentDescription = "Wichtig", tint = WearColors.Warning)
    }
    val secondLine: @Composable RowScope.() -> Unit = {
        Text(text = line.orEmpty(), color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    CheckboxButton(
        checked = checked,
        onCheckedChange = { value -> onToggle(task, value) },
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = !checked || OverviewLogic.canUncheck(task),
        transformation = SurfaceTransformation(spec),
        icon = if (task.important) star else null,
        secondaryLabel = if (line != null) secondLine else null,
        label = { Text(text = task.title) },
    )
}

/**
 * Karte mit einer Überschrift und – wenn [line] gesetzt ist – einer Zeile darunter; mit [progress] (0..1) dazu
 * ein Balken. Mit [onClick] führt sie weiter, ohne ist sie nur zum Lesen. [highlighted] hebt sie farbig hervor.
 */
@Composable
internal fun TransformingLazyColumnItemScope.InfoCard(
    title: String,
    spec: TransformationSpec,
    line: String? = null,
    highlighted: Boolean = false,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = onClick != null,
        colors = infoCardColors(highlighted),
        transformation = SurfaceTransformation(spec),
    ) {
        Text(text = title, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (line != null) {
            Text(
                text = line,
                color = secondaryTextColor(highlighted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (progress != null) {
            ProgressBar(fraction = progress, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Ein Geburtstag: Name, wie weit er weg ist und das Alter. Heute farbig hervorgehoben. */
@Composable
internal fun TransformingLazyColumnItemScope.BirthdayCard(
    birthday: WatchBirthday,
    today: Long,
    spec: TransformationSpec,
    onClick: (() -> Unit)? = null,
) {
    InfoCard(
        title = "🎂 ${birthday.name}",
        spec = spec,
        line = WatchFormat.birthdayLine(birthday, today),
        highlighted = birthday.epochDay == today,
        onClick = onClick,
    )
}

/**
 * Karte mit einem Messwert: klein, was es ist ([label]), groß der Wert ([value]) in [color]. Mit [progress]
 * (0..1) steht darunter ein Balken, mit [detail] eine weitere Zeile.
 */
@Composable
internal fun TransformingLazyColumnItemScope.StatCard(
    label: String,
    value: String,
    color: Color,
    spec: TransformationSpec,
    detail: String? = null,
    progress: Float? = null,
) {
    val accent = WearColors.onDark(color)
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = false,
        transformation = SurfaceTransformation(spec),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(text = value, color = accent, maxLines = 1, style = MaterialTheme.typography.titleLarge)
        if (progress != null) {
            ProgressBar(fraction = progress, modifier = Modifier.padding(top = 6.dp), color = accent)
        }
        if (detail != null) {
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Schmaler Fortschrittsbalken über die ganze Breite. [fraction] von 0 bis 1. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    // Der Balken beobachtet nur, was er beim ersten Zeichnen bekommen hat. Deshalb liest er den Wert aus einem
    // Zustand – sonst bliebe er auf dem ersten Stand stehen.
    val current by rememberUpdatedState(fraction)
    LinearProgressIndicator(
        progress = { current },
        modifier = modifier.fillMaxWidth(),
        colors = ProgressIndicatorDefaults.colors(indicatorColor = color),
        strokeWidth = LinearProgressIndicatorDefaults.StrokeWidthSmall,
    )
}

/** Beschriftung eines Knopfs ohne Symbol: mittig. */
@Composable
internal fun CenteredLabel(text: String) {
    Text(text = text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

/** Farben einer Karte – hervorgehoben in der Farbe der App. */
@Composable
internal fun infoCardColors(highlighted: Boolean): CardColors =
    if (highlighted) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }

/** Farbe der kleineren Zeilen auf einer Karte. */
@Composable
internal fun secondaryTextColor(highlighted: Boolean): Color =
    if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

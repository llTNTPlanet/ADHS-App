package de.lltntplanet.adhsapp.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import de.lltntplanet.adhsapp.wear.ui.theme.WearColors
import java.time.ZoneId

/**
 * Startseite „Heute“: was gerade läuft und als Nächstes kommt, die nächsten Routinen, die Aufgaben von heute,
 * Geburtstage und Gesundheit in Kürze – darunter die Unterseiten und der Stand der Daten.
 */
@Composable
internal fun HomeScreen(
    snapshot: WatchSnapshot?,
    loaded: Boolean,
    refreshing: Boolean,
    now: Long,
    checkedTasks: List<WatchTask>,
    reminderCount: Int,
    canRing: Boolean,
    onToggleRoutine: (WatchRoutine, Boolean) -> Unit,
    onToggleTask: (WatchTask, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenPage: (WatchPage) -> Unit,
    onOpenAlerts: () -> Unit,
    onAllowNotifications: () -> Unit,
) {
    val zoneId = snapshot?.zoneId.orEmpty()
    val zone = remember(zoneId) { WatchFormat.zone(zoneId) }
    WatchList { spec ->
        title("Heute", spec)
        if (reminderCount > 0) {
            item(key = "alerts") { AlertsButton(count = reminderCount, spec = spec, onClick = onOpenAlerts) }
        }
        // Steht am Handy „Nur Handy“, klingelt die Uhr ohnehin nicht – dann fehlt die Erlaubnis auch nicht.
        if (!canRing && snapshot?.ringTarget != RingTarget.PHONE.name) {
            item(key = "ring-warning") { RingWarning(spec = spec, onClick = onAllowNotifications) }
        }
        if (snapshot == null) {
            if (loaded) {
                hint("empty", "Noch keine Daten. Öffne die ADHS App am Handy.")
                item(key = "refresh") {
                    RefreshButton(
                        text = "Vom Handy holen",
                        refreshing = refreshing,
                        primary = true,
                        spec = spec,
                        onClick = onRefresh,
                    )
                }
            } else {
                hint("loading", "Lade …")
            }
        } else {
            val today = WatchFormat.epochDay(now, zone)
            val pages = WatchSnapshotLogic.visiblePages(snapshot)
            val stale = WatchSnapshotLogic.isStale(snapshot, now)
            val asOf = WatchFormat.asOf(snapshot.generatedAt, now, zone)

            // Karten führen nur dann zu ihrer Unterseite, wenn sie am Handy nicht ausgeblendet ist.
            fun opener(page: WatchPage): (() -> Unit)? = if (page in pages) {
                { onOpenPage(page) }
            } else {
                null
            }

            if (stale) hint("stale", "Veraltet – $asOf", warning = true)
            eventItems(snapshot, now, today, zone, spec, opener(WatchPage.CALENDAR))
            routineItems(snapshot, now, today, spec, onToggleRoutine)
            taskSection(snapshot, checkedTasks, today, spec, onToggleTask, opener(WatchPage.TASKS))
            birthdayItems(snapshot, today, spec, opener(WatchPage.BIRTHDAYS))
            healthItem(snapshot, today, spec, opener(WatchPage.HEALTH))
            if (pages.isNotEmpty()) {
                section("pages", "Mehr", spec)
                items(items = pages, key = { page -> "page:${page.id}" }) { page ->
                    PageButton(page = page, spec = spec, onClick = { onOpenPage(page) })
                }
            }
            hint("as-of", asOf, warning = stale)
            item(key = "refresh") {
                RefreshButton(
                    text = "Aktualisieren",
                    refreshing = refreshing,
                    primary = false,
                    spec = spec,
                    onClick = onRefresh,
                )
            }
        }
    }
}

/** „Jetzt“ und „Als Nächstes“ – oder der Hinweis, dass nichts mehr ansteht. */
private fun TransformingLazyColumnScope.eventItems(
    snapshot: WatchSnapshot,
    now: Long,
    today: Long,
    zone: ZoneId,
    spec: TransformationSpec,
    onOpen: (() -> Unit)?,
) {
    val nowNext = WatchSnapshotLogic.nowNext(snapshot.events, now)
    val current = nowNext.current
    val next = nowNext.next
    if (current != null) {
        item(key = "now") { NowCard(event = current, now = now, zone = zone, spec = spec, onClick = onOpen) }
    }
    if (next != null) {
        item(key = "next") {
            NextCard(event = next, now = now, today = today, zone = zone, spec = spec, onClick = onOpen)
        }
    }
    if (current == null && next == null) {
        item(key = "no-events") {
            if (snapshot.calendarAvailable) {
                InfoCard(title = "Keine weiteren Termine 🎉", spec = spec, onClick = onOpen)
            } else {
                InfoCard(title = "Kalender nicht freigegeben", spec = spec, line = "Bitte am Handy erlauben.")
            }
        }
    }
}

/** Die nächsten offenen Routinen und Medikamente – erst Überfälliges, dann Kommendes. */
private fun TransformingLazyColumnScope.routineItems(
    snapshot: WatchSnapshot,
    now: Long,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchRoutine, Boolean) -> Unit,
) {
    if (!snapshot.hasRoutines) return
    section("routines", "Routinen", spec)
    val open = WatchSnapshotLogic.openRoutines(snapshot.routines)
        .distinctBy { it.key }
        .take(OverviewLogic.HOME_ROUTINES)
    if (open.isEmpty()) {
        hint("routines-done", "Alles abgehakt ✅")
    } else {
        items(items = open, key = { routine -> "routine:${routine.key}" }) { routine ->
            RoutineRow(routine = routine, now = now, today = today, spec = spec, onToggle = onToggle)
        }
    }
}

/** Die Aufgaben von heute (und überfällige) zum Abhaken. */
private fun TransformingLazyColumnScope.taskSection(
    snapshot: WatchSnapshot,
    checkedTasks: List<WatchTask>,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchTask, Boolean) -> Unit,
    onOpen: (() -> Unit)?,
) {
    section("tasks", "Aufgaben", spec)
    val open = snapshot.tasks.distinctBy { it.id }
    val done = OverviewLogic.doneTasks(checkedTasks, open)
    if (open.isEmpty()) hint("tasks-empty", "Nichts offen für heute 🎉")
    taskItems(open.take(OverviewLogic.HOME_TASKS), done, today, spec, onToggle)
    val more = (open.size - OverviewLogic.HOME_TASKS).coerceAtLeast(0) + snapshot.moreTasks
    if (more > 0) {
        if (onOpen != null) {
            item(key = "tasks-more") {
                ChildButton(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { CenteredLabel(WatchFormat.more(more, onPhone = false)) },
                )
            }
        } else {
            hint("tasks-more", WatchFormat.more(more, onPhone = true))
        }
    }
}

/** Die nächsten Geburtstage – nur, wenn welche anstehen. */
private fun TransformingLazyColumnScope.birthdayItems(
    snapshot: WatchSnapshot,
    today: Long,
    spec: TransformationSpec,
    onOpen: (() -> Unit)?,
) {
    val birthdays = OverviewLogic.upcomingBirthdays(snapshot.birthdays, today, OverviewLogic.HOME_BIRTHDAYS)
    if (birthdays.isEmpty()) return
    section("birthdays", "Geburtstage", spec)
    itemsIndexed(items = birthdays, key = { index, _ -> "birthday:$index" }) { _, birthday ->
        BirthdayCard(birthday = birthday, today = today, spec = spec, onClick = onOpen)
    }
}

/** Schritte und Schlaf in Kürze – nur, wenn es Werte gibt. */
private fun TransformingLazyColumnScope.healthItem(
    snapshot: WatchSnapshot,
    today: Long,
    spec: TransformationSpec,
    onOpen: (() -> Unit)?,
) {
    val health = snapshot.health ?: return
    // Schritte gelten nur für ihren Tag: Die von gestern wären heute irreführend.
    val steps = health.steps?.takeIf { health.epochDay == today }
    val sleep = health.sleepMinutes
    if (steps == null && sleep == null) return
    val progress = WatchSnapshotLogic.stepProgress(health)
    item(key = "health") {
        HealthCard(steps = steps, stepProgress = progress, sleepMinutes = sleep, spec = spec, onClick = onOpen)
    }
}

/** Führt zu den Erinnerungen, die noch auf der Uhr stehen. */
@Composable
private fun TransformingLazyColumnItemScope.AlertsButton(count: Int, spec: TransformationSpec, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        icon = { Icon(imageVector = Icons.Filled.Notifications, contentDescription = null) },
        label = { Text(text = WatchFormat.reminders(count)) },
    )
}

/** Warnung: Die Uhr-App darf keine Benachrichtigungen zeigen und kann deshalb nicht klingeln. */
@Composable
private fun TransformingLazyColumnItemScope.RingWarning(spec: TransformationSpec, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            secondaryContentColor = MaterialTheme.colorScheme.onErrorContainer,
            iconColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        transformation = SurfaceTransformation(spec),
        icon = { Icon(imageVector = Icons.Filled.Warning, contentDescription = null) },
        secondaryLabel = { Text(text = "Tippen zum Erlauben") },
        label = { Text(text = "Uhr klingelt nicht") },
    )
}

/** Was gerade läuft: wie lange noch, bis wann – und ein Balken, wie weit es schon ist. */
@Composable
private fun TransformingLazyColumnItemScope.NowCard(
    event: WatchEvent,
    now: Long,
    zone: ZoneId,
    spec: TransformationSpec,
    onClick: (() -> Unit)?,
) {
    val total = (event.end - event.begin).coerceAtLeast(1L)
    Card(
        onClick = onClick ?: {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = onClick != null,
        colors = infoCardColors(highlighted = true),
        transformation = SurfaceTransformation(spec),
    ) {
        CardCaption(text = "JETZT", highlighted = true)
        Text(text = event.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = "${WatchFormat.remaining(event.end, now)} · bis ${WatchFormat.time(event.end, zone)}",
            color = secondaryTextColor(highlighted = true),
            style = MaterialTheme.typography.bodySmall,
        )
        ProgressBar(
            fraction = ((now - event.begin).toFloat() / total).coerceIn(0f, 1f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Was als Nächstes kommt: wann, wo – und groß, wie lange es noch dauert. */
@Composable
private fun TransformingLazyColumnItemScope.NextCard(
    event: WatchEvent,
    now: Long,
    today: Long,
    zone: ZoneId,
    spec: TransformationSpec,
    onClick: (() -> Unit)?,
) {
    val place = event.location
    val start = WatchFormat.startLine(event, today, zone)
    Card(
        onClick = onClick ?: {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = onClick != null,
        transformation = SurfaceTransformation(spec),
    ) {
        CardCaption(text = "ALS NÄCHSTES", highlighted = false)
        Text(text = event.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = if (place.isNullOrBlank()) start else "$start · $place",
            color = secondaryTextColor(highlighted = false),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = WatchFormat.countdown(event.begin, now).replaceFirstChar { it.uppercaseChar() },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Kleine Zeile in Großbuchstaben über dem Titel einer Karte. */
@Composable
private fun CardCaption(text: String, highlighted: Boolean) {
    Text(
        text = text,
        color = secondaryTextColor(highlighted),
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Schritte (mit Balken bis zum Tagesziel) und der Schlaf der letzten Nacht. */
@Composable
private fun TransformingLazyColumnItemScope.HealthCard(
    steps: Long?,
    stepProgress: Float,
    sleepMinutes: Long?,
    spec: TransformationSpec,
    onClick: (() -> Unit)?,
) {
    Card(
        onClick = onClick ?: {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = onClick != null,
        transformation = SurfaceTransformation(spec),
    ) {
        if (steps != null) {
            Text(text = "👟 ${WatchFormat.number(steps)} Schritte", maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressBar(
                fraction = stepProgress,
                modifier = Modifier.padding(top = 4.dp, bottom = if (sleepMinutes != null) 6.dp else 0.dp),
                color = WearColors.onDark(WearColors.Health),
            )
        }
        if (sleepMinutes != null) {
            Text(
                text = "😴 ${WatchFormat.duration(sleepMinutes)} Schlaf",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Knopf zu einer Unterseite. */
@Composable
private fun TransformingLazyColumnItemScope.PageButton(page: WatchPage, spec: TransformationSpec, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        icon = { Text(text = WatchFormat.symbol(page), style = MaterialTheme.typography.titleMedium) },
        label = { Text(text = page.label) },
    )
}

/** „Aktualisieren“ bzw. „Vom Handy holen“. Solange geholt wird, ist der Knopf gesperrt. */
@Composable
private fun TransformingLazyColumnItemScope.RefreshButton(
    text: String,
    refreshing: Boolean,
    primary: Boolean,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        enabled = !refreshing,
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        transformation = SurfaceTransformation(spec),
        icon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
        label = { Text(text = if (refreshing) "Wird geholt …" else text) },
    )
}

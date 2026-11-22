package de.lltntplanet.adhsapp.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import de.lltntplanet.adhsapp.wear.ui.theme.WearColors
import java.time.ZoneId

/** Eine Unterseite der Uhr-App. Welche es gibt, legt die Handy-App fest ([WatchPage]). */
@Composable
internal fun PageScreen(
    page: WatchPage,
    snapshot: WatchSnapshot?,
    now: Long,
    checkedTasks: List<WatchTask>,
    onToggleRoutine: (WatchRoutine, Boolean) -> Unit,
    onToggleTask: (WatchTask, Boolean) -> Unit,
) {
    val zoneId = snapshot?.zoneId.orEmpty()
    val zone = remember(zoneId) { WatchFormat.zone(zoneId) }
    WatchList { spec ->
        title(page.label, spec)
        if (snapshot == null) {
            hint("empty", "Noch keine Daten vom Handy.")
        } else {
            val today = WatchFormat.epochDay(now, zone)
            when (page) {
                WatchPage.CALENDAR -> calendarPage(snapshot, now, today, zone, spec)
                WatchPage.TASKS -> tasksPage(snapshot, checkedTasks, today, spec, onToggleTask)
                WatchPage.ROUTINES -> routinesPage(snapshot, now, today, spec, onToggleRoutine)
                WatchPage.BIRTHDAYS -> birthdaysPage(snapshot, today, spec)
                WatchPage.HEALTH -> healthPage(snapshot, now, today, zone, spec)
                WatchPage.NUTRITION -> nutritionPage(snapshot, today, spec)
            }
        }
    }
}

/** Kalender: heute und die kommenden Tage mit Terminen, ganztägige je Tag zuerst. */
private fun TransformingLazyColumnScope.calendarPage(
    snapshot: WatchSnapshot,
    now: Long,
    today: Long,
    zone: ZoneId,
    spec: TransformationSpec,
) {
    if (!snapshot.calendarAvailable) {
        hint("calendar-off", "Kalender nicht freigegeben. Bitte am Handy erlauben.")
        return
    }
    OverviewLogic.calendarDays(snapshot.events, today, zone).forEach { day ->
        section("day:${day.epochDay}", WatchFormat.relativeDay(day.epochDay, today), spec)
        if (day.events.isEmpty()) hint("day-empty:${day.epochDay}", "Keine Termine")
        itemsIndexed(items = day.events, key = { index, _ -> "event:${day.epochDay}:$index" }) { _, event ->
            EventCard(event = event, day = day, now = now, zone = zone, spec = spec)
        }
    }
}

/** Aufgaben: alles, was heute fällig oder überfällig ist, zum Abhaken. */
private fun TransformingLazyColumnScope.tasksPage(
    snapshot: WatchSnapshot,
    checkedTasks: List<WatchTask>,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchTask, Boolean) -> Unit,
) {
    val open = snapshot.tasks.distinctBy { it.id }
    val done = OverviewLogic.doneTasks(checkedTasks, open)
    if (open.isEmpty()) hint("tasks-empty", "Nichts offen für heute 🎉")
    taskItems(open, done, today, spec, onToggle)
    if (snapshot.moreTasks > 0) hint("tasks-more", WatchFormat.more(snapshot.moreTasks, onPhone = true))
}

/** Routinen: alle Vorkommen von heute mit ihrem Zustand. */
private fun TransformingLazyColumnScope.routinesPage(
    snapshot: WatchSnapshot,
    now: Long,
    today: Long,
    spec: TransformationSpec,
    onToggle: (WatchRoutine, Boolean) -> Unit,
) {
    val routines = WatchSnapshotLogic.routinesOn(snapshot.routines, today).distinctBy { it.key }
    when {
        !snapshot.hasRoutines -> hint("routines-none", "Noch keine Routinen. Lege sie am Handy an.")
        routines.isEmpty() -> hint("routines-empty", "Heute steht nichts an.")
        else -> {
            // Wie am Handy zählt auch Übersprungenes als geschafft.
            val finished = routines.count { !it.open }
            item(key = "routines-summary") {
                InfoCard(
                    title = WatchFormat.routineSummary(finished, routines.size),
                    spec = spec,
                    highlighted = true,
                    progress = finished.toFloat() / routines.size,
                )
            }
            items(items = routines, key = { routine -> "routine:${routine.key}" }) { routine ->
                RoutineRow(routine = routine, now = now, today = today, spec = spec, onToggle = onToggle)
            }
        }
    }
}

/** Geburtstage der nächsten zwei Wochen. */
private fun TransformingLazyColumnScope.birthdaysPage(
    snapshot: WatchSnapshot,
    today: Long,
    spec: TransformationSpec,
) {
    val birthdays = OverviewLogic.upcomingBirthdays(snapshot.birthdays, today)
    if (birthdays.isEmpty()) hint("birthdays-empty", "Keine Geburtstage in den nächsten 14 Tagen.")
    itemsIndexed(items = birthdays, key = { index, _ -> "birthday:$index" }) { _, birthday ->
        BirthdayCard(birthday = birthday, today = today, spec = spec)
    }
}

/** Gesundheit: Schritte mit Tagesziel, Schlaf, Ruhepuls und Aktivzonenminuten. */
private fun TransformingLazyColumnScope.healthPage(
    snapshot: WatchSnapshot,
    now: Long,
    today: Long,
    zone: ZoneId,
    spec: TransformationSpec,
) {
    val health = snapshot.health
    if (health == null) {
        hint("health-off", "Google Health ist am Handy nicht verbunden.")
        return
    }
    val steps = health.steps
    val sleep = health.sleepMinutes
    val heartRate = health.restingHeartRate
    val activeMinutes = health.activeZoneMinutes
    if (steps == null && sleep == null && heartRate == null && activeMinutes == null) {
        hint("health-empty", "Noch keine Werte. Öffne „Gesundheit“ am Handy.")
        return
    }
    if (health.epochDay != today) {
        hint("health-day", "Werte von: ${WatchFormat.relativeDay(health.epochDay, today)}", warning = true)
    }
    if (steps != null) {
        val goal = "Ziel: ${WatchFormat.number(health.stepGoal.toLong())}"
        val progress = WatchSnapshotLogic.stepProgress(health)
        item(key = "steps") {
            StatCard(
                label = "Schritte",
                value = WatchFormat.number(steps),
                color = WearColors.Health,
                spec = spec,
                detail = goal,
                progress = progress,
            )
        }
    }
    if (sleep != null) {
        item(key = "sleep") {
            StatCard(
                label = "Schlaf letzte Nacht",
                value = WatchFormat.duration(sleep),
                color = WearColors.Sleep,
                spec = spec,
            )
        }
    }
    if (heartRate != null) {
        item(key = "heart-rate") {
            StatCard(label = "Ruhepuls", value = "$heartRate pro Minute", color = WearColors.NowRed, spec = spec)
        }
    }
    if (activeMinutes != null) {
        item(key = "active-minutes") {
            StatCard(
                label = "Aktivzonenminuten",
                value = "$activeMinutes Min.",
                color = WearColors.Routine,
                spec = spec,
            )
        }
    }
    if (health.fetchedAt > 0L) hint("health-as-of", WatchFormat.asOf(health.fetchedAt, now, zone))
}

/** Essen: was vom Kalorienbudget übrig ist und was heute eingetragen wurde. */
private fun TransformingLazyColumnScope.nutritionPage(
    snapshot: WatchSnapshot,
    today: Long,
    spec: TransformationSpec,
) {
    val nutrition = snapshot.nutrition
    if (nutrition == null) {
        hint("nutrition-off", "Noch keine Daten. Öffne „Essen“ am Handy.")
        return
    }
    if (nutrition.epochDay != today) {
        hint("nutrition-day", "Werte von: ${WatchFormat.relativeDay(nutrition.epochDay, today)}", warning = true)
    }
    val eaten = WatchFormat.kcalEaten(nutrition)
    if (nutrition.budgetKcal > 0) {
        val left = WatchFormat.kcalLeft(nutrition)
        val over = nutrition.remainingKcal < 0
        val fraction = WatchSnapshotLogic.eatenFraction(nutrition)
        item(key = "budget") {
            StatCard(
                label = "Heute",
                value = left,
                color = if (over) WearColors.NowRed else WearColors.Task,
                spec = spec,
                detail = eaten,
                progress = fraction,
            )
        }
    } else {
        item(key = "budget") { InfoCard(title = eaten, spec = spec) }
    }
    section("food", "Heute gegessen", spec)
    if (nutrition.entries.isEmpty()) hint("food-empty", "Noch nichts eingetragen.")
    itemsIndexed(items = nutrition.entries, key = { index, _ -> "food:$index" }) { _, food ->
        InfoCard(title = food.name, spec = spec, line = WatchFormat.foodLine(food))
    }
}

/**
 * Ein Termin an einem Tag der Seite „Kalender“: Farbe des Kalenders, Zeit, Titel und Ort. Was gerade läuft, ist
 * hervorgehoben; was heute schon vorbei ist, tritt zurück.
 */
@Composable
private fun TransformingLazyColumnItemScope.EventCard(
    event: WatchEvent,
    day: OverviewLogic.Day,
    now: Long,
    zone: ZoneId,
    spec: TransformationSpec,
) {
    val running = !event.allDay && event.begin <= now && event.end > now
    val past = !event.allDay && !running && event.end <= now
    val time = WatchFormat.eventTime(event, day.start, day.end, zone)
    val place = event.location
    val dot = event.color?.let { Color(it).copy(alpha = 1f) } ?: MaterialTheme.colorScheme.primary
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).alpha(if (past) 0.5f else 1f),
        enabled = false,
        colors = infoCardColors(highlighted = running),
        transformation = SurfaceTransformation(spec),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color = dot, shape = CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (running) "$time · jetzt" else time,
                color = secondaryTextColor(highlighted = running),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(text = event.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!place.isNullOrBlank()) {
            Text(
                text = place,
                color = secondaryTextColor(highlighted = running),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

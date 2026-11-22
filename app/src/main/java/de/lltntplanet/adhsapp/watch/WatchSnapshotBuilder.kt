package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.data.birthdays.Birthday
import de.lltntplanet.adhsapp.data.birthdays.BirthdayMath
import de.lltntplanet.adhsapp.data.calendar.EventInstance
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.nutrition.NutritionMath
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchFood
import de.lltntplanet.adhsapp.shared.WatchHealth
import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchNutrition
import de.lltntplanet.adhsapp.shared.WatchPage
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchTask
import de.lltntplanet.adhsapp.util.Dates
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** Alles, woraus die Übersicht für die Uhr entsteht – schon gelesen, ohne Android. */
data class WatchSnapshotInput(
    val now: Long,
    /** Zeitzone des Handys: In ihr werden „heute“ und die ganztägigen Termine berechnet. */
    val zone: ZoneId,
    val settings: AppSettings,
    /** Darf die App den Kalender lesen? */
    val calendarAvailable: Boolean = true,
    /** Termine mindestens von heute bis [WatchSnapshotBuilder.lastEventDay]; was nicht passt, wird aussortiert. */
    val events: List<EventInstance> = emptyList(),
    /** Alle Routinen und Medikamente, auch pausierte. */
    val routines: List<RoutineEntity> = emptyList(),
    /** Abgehakte und übersprungene Vorkommen, mindestens von heute und morgen. */
    val routineLog: List<RoutineLogEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val birthdays: List<Birthday> = emptyList(),
    /** Zuletzt geladene Gesundheitsdaten; `null` = noch keine. */
    val health: HealthSnapshot? = null,
    /** Essens-Einträge; nur die von heute zählen. */
    val food: List<FoodEntryEntity> = emptyList(),
)

/**
 * Baut die kompakte Übersicht für die Uhr – reine Logik ohne Android, damit sie sich einfach testen lässt.
 *
 * Die Startseite der Uhr spiegelt die Startseite des Handys: dieselben Filter, dieselbe Reihenfolge. Was nur eine
 * Unterseite braucht, die auf der Uhr ausgeblendet ist, bleibt weg.
 */
object WatchSnapshotBuilder {

    /** Mit der Unterseite „Kalender“: Termine von heute und den nächsten sieben Tagen. */
    const val EVENT_DAYS_AHEAD = 7L

    /** Ohne die Unterseite „Kalender“: heute und morgen – so weit schaut „Als Nächstes“ auf der Startseite. */
    const val HOME_EVENT_DAYS_AHEAD = 1L

    /** Geburtstage so viele Tage im Voraus, wie die Startseite des Handys sie zeigt. */
    const val BIRTHDAY_DAYS_AHEAD = 14L

    const val MAX_EVENTS = 60
    const val MAX_ROUTINES = 40
    const val MAX_TASKS = 30
    const val MAX_BIRTHDAYS = 12
    const val MAX_FOOD = 20

    /** So viele Zeichen bleiben von langen Texten (Titel, Ort, Dosis) höchstens stehen. */
    const val MAX_TEXT = 80

    /** Obergrenze für das JSON der Übersicht; das Datenelement der Uhr fasst höchstens 100 kB. */
    const val MAX_BYTES = 90_000

    /** So viele offene Routinen zeigt die Startseite der Uhr höchstens – sie überstehen jedes Kürzen. */
    private const val HOME_ROUTINES = 6

    private const val HEX = "0123456789abcdef"

    /** Reihenfolge der Aufgaben wie in der Handy-App: nach Tag, Uhrzeit (ohne Uhrzeit zuletzt), Wichtiges zuerst. */
    private val taskOrder: Comparator<TaskEntity> = compareBy<TaskEntity>(
        { it.dueEpochDay },
        { it.dueMinutes == null },
        { it.dueMinutes },
        { !it.important },
        { it.createdAt },
        { it.id },
    )

    private val eventOrder: Comparator<WatchEvent> =
        compareBy<WatchEvent>({ it.begin }, { !it.allDay }, { it.title }, { it.id })

    private val routineOrder: Comparator<WatchRoutine> = compareBy<WatchRoutine>({ it.at }, { it.name })

    fun build(input: WatchSnapshotInput): WatchSnapshot {
        val s = input.settings
        val zone = input.zone
        val today = Dates.toLocalDate(input.now, zone)
        val pages = WatchPage.visible(s.watchHiddenPages)
        val tasks = dueTasks(input.tasks, today)
        return WatchSnapshot(
            generatedAt = input.now,
            zoneId = zone.id,
            pages = pages.map { it.id },
            ringTarget = s.ringTarget.name,
            calendarAvailable = input.calendarAvailable,
            events = events(input.events, s.hiddenCalendars, today, lastEventDay(today, s.watchHiddenPages), zone),
            // Wie am Handy: Der Abschnitt erscheint, sobald es Routinen gibt – auch wenn gerade alle pausiert sind.
            hasRoutines = input.routines.isNotEmpty(),
            routines = limitRoutines(routines(input.routines, input.routineLog, today, zone), MAX_ROUTINES),
            tasks = tasks.take(MAX_TASKS),
            moreTasks = (tasks.size - MAX_TASKS).coerceAtLeast(0),
            birthdays = birthdays(input.birthdays, today),
            health = if (s.healthConnected) health(input.health, s, today, detailed = WatchPage.HEALTH in pages) else null,
            // „Essen“ steht nicht auf der Startseite: ohne die Unterseite braucht die Uhr nichts davon.
            nutrition = if (WatchPage.NUTRITION in pages) nutrition(input.food, input.health, s, today) else null,
        )
    }

    /**
     * Letzter Tag, dessen Termine in die Übersicht kommen. Die Startseite braucht heute und morgen; nur die
     * Unterseite „Kalender“ schaut weiter voraus. [hiddenPages] sind die IDs der ausgeblendeten Unterseiten.
     */
    fun lastEventDay(today: LocalDate, hiddenPages: Set<String>): LocalDate =
        today.plusDays(if (WatchPage.CALENDAR.id in hiddenPages) HOME_EVENT_DAYS_AHEAD else EVENT_DAYS_AHEAD)

    /** Die Übersicht als JSON-Bytes, so wie sie im Datenelement für die Uhr steht. */
    fun encode(snapshot: WatchSnapshot): ByteArray = WatchJson.encodeToString(snapshot).encodeToByteArray()

    /**
     * Kürzt die Listen, bis die Übersicht in [maxBytes] passt. Es fällt jeweils das Späteste weg; gestrichene
     * Aufgaben zählen in [WatchSnapshot.moreTasks] weiter. Von jeder Liste bleibt mindestens ein Eintrag.
     */
    fun fit(snapshot: WatchSnapshot, maxBytes: Int = MAX_BYTES): WatchSnapshot {
        var current = snapshot
        while (encode(current).size > maxBytes) {
            val smaller = halve(current)
            if (smaller == current) break
            current = smaller
        }
        return current
    }

    /**
     * Fingerabdruck des Inhalts – ohne die beiden Zeitstempel, die sich bei jedem Erzeugen bzw. bei jedem Laden der
     * Gesundheitsdaten ändern. Ist er gleich geblieben, muss die Übersicht nicht neu an die Uhr.
     */
    fun contentHash(snapshot: WatchSnapshot): String {
        val content = snapshot.copy(generatedAt = 0, health = snapshot.health?.copy(fetchedAt = 0))
        val digest = MessageDigest.getInstance("SHA-256").digest(encode(content))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    /** Kürzt lange Texte für die kleine Anzeige auf höchstens [max] Zeichen; Leerraum außen fällt weg. */
    fun shorten(text: String, max: Int = MAX_TEXT): String {
        val trimmed = text.trim()
        if (trimmed.length <= max) return trimmed
        var cut = (max - 1).coerceAtLeast(0)
        // Kein halbes Emoji (erste Hälfte eines Zeichenpaars) stehen lassen.
        if (cut > 0 && Character.isHighSurrogate(trimmed[cut - 1])) cut--
        return trimmed.substring(0, cut).trimEnd() + "…"
    }

    /**
     * Termine wie auf der Startseite des Handys: ohne ausgeblendete Kalender und ohne abgesagte Termine. Abgelehnte
     * Termine fehlen ganz, weil „Als Nächstes“ sie auch am Handy übergeht.
     */
    private fun events(
        all: List<EventInstance>,
        hiddenCalendars: Set<Long>,
        today: LocalDate,
        lastDay: LocalDate,
        zone: ZoneId,
    ): List<WatchEvent> = all
        .filter { it.calendarId !in hiddenCalendars && !it.canceled && !it.declined }
        .filter { !it.endDateInclusive(zone).isBefore(today) && !it.startDate(zone).isAfter(lastDay) }
        .map { toWatch(it, zone) }
        .sortedWith(eventOrder)
        .take(MAX_EVENTS)

    private fun toWatch(e: EventInstance, zone: ZoneId): WatchEvent = WatchEvent(
        id = e.eventId,
        title = shorten(e.displayTitle),
        // Ganztägige Termine liegen in UTC im Kalender. Die Uhr bekommt sie als Tage in der Zeitzone des Handys:
        // Mitternacht des ersten Tages bis Mitternacht nach dem letzten Tag.
        begin = if (e.allDay) Dates.startOfDayMillis(e.startDate(zone), zone) else e.begin,
        end = if (e.allDay) Dates.startOfDayMillis(e.endDateInclusive(zone).plusDays(1), zone) else e.end,
        allDay = e.allDay,
        color = e.color,
        location = e.location?.let { shorten(it) }?.takeIf { it.isNotEmpty() },
    )

    /** Alle Vorkommen von heute und morgen (auch abgehakte), nach Uhrzeit. */
    private fun routines(
        routines: List<RoutineEntity>,
        log: List<RoutineLogEntity>,
        today: LocalDate,
        zone: ZoneId,
    ): List<WatchRoutine> = RoutineLogic.occurrences(routines, log, today, today.plusDays(1), zone).map { o ->
        WatchRoutine(
            key = o.key,
            routineId = o.routine.id,
            name = shorten(o.routine.name),
            dose = shorten(o.routine.dose),
            kind = o.routine.kind.name,
            epochDay = o.epochDay,
            minutes = o.minutes,
            at = o.at,
            done = o.done,
            skipped = o.skipped,
            byDispenser = o.byDispenser,
        )
    }

    /**
     * Höchstens [max] Vorkommen. Die nächsten offenen bleiben immer erhalten (die zeigt die Startseite), dazu kommen
     * die frühesten übrigen.
     */
    private fun limitRoutines(all: List<WatchRoutine>, max: Int): List<WatchRoutine> {
        if (all.size <= max) return all
        val home = all.filter { it.open }.take(minOf(HOME_ROUTINES, max))
        val homeKeys = home.mapTo(HashSet()) { it.key }
        val rest = all.filterNot { it.key in homeKeys }.take(max - home.size)
        return (home + rest).sortedWith(routineOrder)
    }

    /** Offene Aufgaben, die heute fällig oder überfällig sind – in der Reihenfolge der Handy-App. */
    private fun dueTasks(tasks: List<TaskEntity>, today: LocalDate): List<WatchTask> = tasks
        .filter { !it.done && TaskLogic.dueDate(it)?.let { due -> !due.isAfter(today) } == true }
        .sortedWith(taskOrder)
        .map {
            WatchTask(
                id = it.id,
                title = shorten(it.title),
                dueEpochDay = it.dueEpochDay,
                dueMinutes = it.dueMinutes,
                important = it.important,
                repeating = it.repeat != Repeat.NONE,
            )
        }

    private fun birthdays(all: List<Birthday>, today: LocalDate): List<WatchBirthday> =
        BirthdayMath.upcoming(all, today)
            .filter { it.daysUntil <= BIRTHDAY_DAYS_AHEAD }
            .take(MAX_BIRTHDAYS)
            .map { WatchBirthday(name = shorten(it.birthday.name), epochDay = it.date.toEpochDay(), age = it.age) }

    /** Schritte und Schlaf wie auf der Startseite; Ruhepuls und Aktivzonenminuten nur für die Unterseite ([detailed]). */
    private fun health(h: HealthSnapshot?, s: AppSettings, today: LocalDate, detailed: Boolean): WatchHealth {
        val day = h?.day(today)
        return WatchHealth(
            epochDay = today.toEpochDay(),
            steps = day?.steps,
            stepGoal = s.stepGoal,
            sleepMinutes = h?.lastSleep?.minutesAsleep,
            restingHeartRate = if (detailed) h?.latestRestingHeartRate?.bpm else null,
            activeZoneMinutes = if (detailed) day?.activeZoneMinutes else null,
            fetchedAt = h?.fetchedAt ?: 0,
        )
    }

    /** Kalorien von heute – dieselbe Rechnung wie auf der Seite „Essen“; die neuesten Einträge zuerst. */
    private fun nutrition(food: List<FoodEntryEntity>, h: HealthSnapshot?, s: AppSettings, today: LocalDate): WatchNutrition {
        val entries = food.filter { it.epochDay == today.toEpochDay() }
            .sortedWith(compareBy<FoodEntryEntity>({ it.createdAt }, { it.id }))
        val basal = NutritionMath.basalMetabolicRate(s, today) ?: NutritionMath.DEFAULT_BASAL
        val burned = if (s.healthConnected) h?.day(today)?.kcal else null
        val balance = NutritionMath.balance(basal, burned, s.calorieAdjustment, entries)
        return WatchNutrition(
            epochDay = today.toEpochDay(),
            eatenKcal = balance.eaten,
            budgetKcal = balance.budget,
            entries = entries.takeLast(MAX_FOOD).reversed().map {
                WatchFood(name = shorten(it.name), kcal = it.kcal, meal = it.meal.label)
            },
        )
    }

    /** Ein Schritt beim Kürzen: jede Liste auf die Hälfte (aufgerundet). */
    private fun halve(s: WatchSnapshot): WatchSnapshot {
        val tasks = s.tasks.take(half(s.tasks.size))
        return s.copy(
            events = s.events.take(half(s.events.size)),
            routines = limitRoutines(s.routines, half(s.routines.size)),
            tasks = tasks,
            moreTasks = s.moreTasks + s.tasks.size - tasks.size,
            birthdays = s.birthdays.take(half(s.birthdays.size)),
            nutrition = s.nutrition?.let { it.copy(entries = it.entries.take(half(it.entries.size))) },
        )
    }

    private fun half(size: Int): Int = (size + 1) / 2
}

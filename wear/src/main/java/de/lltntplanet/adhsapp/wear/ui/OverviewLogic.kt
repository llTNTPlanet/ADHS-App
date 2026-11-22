package de.lltntplanet.adhsapp.wear.ui

import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import java.time.ZoneId
import kotlin.math.abs

/** Reine Regeln der Übersicht auf der Uhr – ohne Android, damit sie sich testen lassen. */
object OverviewLogic {

    /** Älter als das? Dann bittet die Uhr das Handy um eine frische Übersicht. */
    const val SYNC_AGE_MILLIS = 10L * 60 * 1000

    /** So oft prüft die sichtbare App, ob die Übersicht zu alt geworden ist. */
    const val SYNC_CHECK_MILLIS = 5L * 60 * 1000

    /** So lange wartet „Aktualisieren“ auf die neue Übersicht vom Handy. */
    const val ANSWER_MILLIS = 8_000L

    /** War die App länger als das nicht zu sehen, vergisst sie, was zuletzt auf der Uhr abgehakt wurde. */
    const val SESSION_MILLIS = 5L * 60 * 1000

    /** So viel zeigt die Startseite höchstens je Abschnitt – wie die Startseite der Handy-App, nur knapper. */
    const val HOME_ROUTINES = 2
    const val HOME_TASKS = 5
    const val HOME_BIRTHDAYS = 3

    /** So weit schaut die Seite „Kalender“ höchstens voraus. */
    const val CALENDAR_DAYS = 14

    const val PHONE_UNREACHABLE = "Handy nicht erreichbar"
    const val NO_ANSWER = "Handy antwortet nicht"
    const val ACTION_FAILED = "Hat nicht geklappt"

    private const val MINUTE_MILLIS = 60_000L

    /** Kleiner Abstand hinter der vollen Minute, damit die Anzeige sicher schon in der neuen Minute rechnet. */
    private const val TICK_MARGIN_MILLIS = 50L

    /** Ein Tag der Seite „Kalender“ mit seinen Terminen ([start] bis [end] in Millisekunden). */
    data class Day(val epochDay: Long, val start: Long, val end: Long, val events: List<WatchEvent>)

    /**
     * Braucht die Uhr eine frische Übersicht? Ja, wenn es noch keine gibt, wenn sie älter als
     * [SYNC_AGE_MILLIS] ist (oder ihr Zeitstempel in der Zukunft liegt) oder wenn sie von einem anderen Tag
     * stammt – dann stimmen „heute“ und „überfällig“ nicht mehr.
     */
    fun needsSync(snapshot: WatchSnapshot?, now: Long): Boolean {
        if (snapshot == null) return true
        if (abs(now - snapshot.generatedAt) > SYNC_AGE_MILLIS) return true
        val zone = WatchFormat.zone(snapshot.zoneId)
        return WatchFormat.epochDay(snapshot.generatedAt, zone) != WatchFormat.epochDay(now, zone)
    }

    /** Wie lange es von [now] bis kurz nach der nächsten vollen Minute dauert – dann rechnet die Anzeige neu. */
    fun untilNextMinute(now: Long): Long = MINUTE_MILLIS - Math.floorMod(now, MINUTE_MILLIS) + TICK_MARGIN_MILLIS

    /**
     * Die Übersicht mit gesetztem oder entferntem Haken an einem Vorkommen ([WatchRoutine.key]). Wie am Handy
     * wird aus „übersprungen“ dabei „erledigt“ bzw. wieder „offen“. Checkups bleiben, wie sie sind.
     */
    fun withRoutine(snapshot: WatchSnapshot, key: String, done: Boolean): WatchSnapshot = snapshot.copy(
        routines = snapshot.routines.map { routine ->
            if (routine.key == key && routine.canToggle) {
                routine.copy(done = done, skipped = false, byDispenser = routine.byDispenser && done)
            } else {
                routine
            }
        },
    )

    /** Die Übersicht ohne eine (gerade abgehakte) Aufgabe. */
    fun withoutTask(snapshot: WatchSnapshot, taskId: Long): WatchSnapshot =
        snapshot.copy(tasks = snapshot.tasks.filterNot { it.id == taskId })

    /** Die Übersicht mit einer Aufgabe, deren Haken wieder entfernt wurde. */
    fun withTask(snapshot: WatchSnapshot, task: WatchTask): WatchSnapshot =
        if (snapshot.tasks.any { it.id == task.id }) snapshot else snapshot.copy(tasks = snapshot.tasks + task)

    /** Was das Handy tun soll, wenn auf der Uhr ein Vorkommen abgehakt wird (oder der Haken wegkommt). */
    fun routineAction(routine: WatchRoutine, done: Boolean): ItemAction = ItemAction(
        type = ItemAction.TYPE_ROUTINE,
        id = routine.routineId,
        done = done,
        epochDay = routine.epochDay,
        minutes = routine.minutes,
    )

    /** Was das Handy tun soll, wenn auf der Uhr eine Aufgabe abgehakt wird (oder der Haken wegkommt). */
    fun taskAction(task: WatchTask, done: Boolean): ItemAction =
        ItemAction(type = ItemAction.TYPE_TASK, id = task.id, done = done)

    /** Die Liste der zuletzt abgehakten Aufgaben mit [task] (einmal) am Ende. */
    fun checked(list: List<WatchTask>, task: WatchTask): List<WatchTask> = unchecked(list, task.id) + task

    /** Die Liste der zuletzt abgehakten Aufgaben ohne die Aufgabe [taskId]. */
    fun unchecked(list: List<WatchTask>, taskId: Long): List<WatchTask> = list.filterNot { it.id == taskId }

    /**
     * Was von den zuletzt auf der Uhr abgehakten Aufgaben ([checked]) noch als erledigt gezeigt wird: alles,
     * was nicht (wieder) offen in der Übersicht steht.
     */
    fun doneTasks(checked: List<WatchTask>, open: List<WatchTask>): List<WatchTask> {
        val openIds = open.mapTo(HashSet()) { it.id }
        return checked.filter { it.id !in openIds }
    }

    /**
     * Lässt sich der Haken einer auf der Uhr abgehakten Aufgabe wieder entfernen? Nicht bei wiederkehrenden:
     * Die sind am Handy schon auf den nächsten Termin gerückt.
     */
    fun canUncheck(task: WatchTask): Boolean = !task.repeating

    /**
     * Die kommenden Geburtstage ab heute, die nächsten zuerst – höchstens [limit]. Vergangene (aus einer alten
     * Übersicht) fallen weg.
     */
    fun upcomingBirthdays(
        birthdays: List<WatchBirthday>,
        todayEpochDay: Long,
        limit: Int = Int.MAX_VALUE,
    ): List<WatchBirthday> =
        birthdays
            .filter { it.epochDay >= todayEpochDay }
            .sortedWith(compareBy({ it.epochDay }, { it.name }))
            .take(limit)

    /**
     * Die Tage der Seite „Kalender“: heute immer (auch ohne Termine), danach jeder Tag mit Terminen – höchstens
     * [CALENDAR_DAYS] Tage voraus. Ganztägige Termine stehen je Tag zuerst.
     */
    fun calendarDays(events: List<WatchEvent>, todayEpochDay: Long, zone: ZoneId): List<Day> {
        val days = ArrayList<Day>()
        for (offset in 0 until CALENDAR_DAYS) {
            val epochDay = todayEpochDay + offset
            val start = WatchFormat.dayStart(epochDay, zone)
            val end = WatchFormat.dayStart(epochDay + 1, zone)
            val onDay = WatchSnapshotLogic.eventsOn(events, start, end)
            if (offset == 0 || onDay.isNotEmpty()) days += Day(epochDay, start, end, onDay)
        }
        return days
    }
}

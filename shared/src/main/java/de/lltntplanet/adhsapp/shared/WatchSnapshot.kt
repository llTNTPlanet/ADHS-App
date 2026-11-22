package de.lltntplanet.adhsapp.shared

import kotlinx.serialization.Serializable

/**
 * Unterseiten der Uhr-App. Die [id] entspricht der Route der Seite in der Handy-App. Die Startseite („Heute“)
 * gibt es auf der Uhr immer; welche Unterseiten zusätzlich erscheinen, legt die Handy-App fest.
 */
enum class WatchPage(val id: String, val label: String) {
    CALENDAR("calendar", "Kalender"),
    TASKS("tasks", "Aufgaben"),
    ROUTINES("routines", "Routinen"),
    BIRTHDAYS("birthdays", "Geburtstage"),
    HEALTH("health", "Gesundheit"),
    NUTRITION("nutrition", "Essen"),
    ;

    companion object {
        fun fromId(id: String?): WatchPage? = entries.find { it.id == id }

        /** Sichtbare Seiten in fester Reihenfolge, wenn [hidden] (IDs) ausgeblendet sind. */
        fun visible(hidden: Set<String>): List<WatchPage> = entries.filter { it.id !in hidden }
    }
}

/**
 * Kompakte Übersicht für die Uhr. Das Handy erzeugt sie bei jeder Änderung neu und legt sie als Datenelement
 * unter [WatchPaths.SNAPSHOT] ab; die Uhr liest nur.
 */
@Serializable
data class WatchSnapshot(
    val version: Int = WATCH_PROTOCOL_VERSION,
    /** Wann das Handy die Übersicht erzeugt hat. */
    val generatedAt: Long = 0,
    /** Zeitzone des Handys (IANA-Name), in der Tage und ganztägige Termine berechnet wurden. */
    val zoneId: String = "",
    /** IDs der Unterseiten ([WatchPage.id]), die auf der Uhr angezeigt werden. */
    val pages: List<String> = WatchPage.entries.map { it.id },
    /** Name von [RingTarget] – nur zur Anzeige auf der Uhr. */
    val ringTarget: String = RingTarget.DEFAULT.name,
    /** Hat das Handy Zugriff auf den Kalender? Sonst bleibt [events] leer. */
    val calendarAvailable: Boolean = true,
    /** Termine von heute bis einschließlich übermorgen, nach Beginn sortiert. */
    val events: List<WatchEvent> = emptyList(),
    /** Gibt es überhaupt Routinen/Medikamente? Sonst lässt die Uhr den Abschnitt weg. */
    val hasRoutines: Boolean = false,
    /** Alle Vorkommen von heute und morgen (auch abgehakte), nach Uhrzeit sortiert. */
    val routines: List<WatchRoutine> = emptyList(),
    /** Offene Aufgaben, die heute fällig oder überfällig sind. */
    val tasks: List<WatchTask> = emptyList(),
    /** So viele weitere fällige Aufgaben stehen nicht in [tasks]. */
    val moreTasks: Int = 0,
    /** Geburtstage der nächsten 14 Tage. */
    val birthdays: List<WatchBirthday> = emptyList(),
    /** `null`, wenn Google Health nicht verbunden ist. */
    val health: WatchHealth? = null,
    val nutrition: WatchNutrition? = null,
)

@Serializable
data class WatchEvent(
    val id: Long,
    val title: String,
    /**
     * Beginn und Ende in Millisekunden. Bei ganztägigen Terminen schon in die Zeitzone des Handys umgerechnet:
     * Mitternacht des ersten Tages bis Mitternacht nach dem letzten Tag.
     */
    val begin: Long,
    val end: Long,
    val allDay: Boolean = false,
    val color: Int? = null,
    val location: String? = null,
)

@Serializable
data class WatchRoutine(
    /** Schlüssel des Vorkommens („rt:<id>:<epochDay>:<minutes>“). */
    val key: String,
    val routineId: Long,
    val name: String,
    val dose: String = "",
    /** MEDICATION, ROUTINE oder CHECKUP. */
    val kind: String = KIND_ROUTINE,
    val epochDay: Long,
    /** Uhrzeit in Minuten seit Mitternacht. */
    val minutes: Int,
    /** Zeitpunkt in Millisekunden. */
    val at: Long,
    val done: Boolean = false,
    val skipped: Boolean = false,
    /** Vom Medikamentenspender als genommen gemeldet. */
    val byDispenser: Boolean = false,
) {
    val open: Boolean get() = !done && !skipped

    /** Checkups werden am Handy ausgefüllt und lassen sich auf der Uhr nicht abhaken. */
    val canToggle: Boolean get() = kind != KIND_CHECKUP

    companion object {
        const val KIND_MEDICATION = "MEDICATION"
        const val KIND_ROUTINE = "ROUTINE"
        const val KIND_CHECKUP = "CHECKUP"
    }
}

@Serializable
data class WatchTask(
    val id: Long,
    val title: String,
    /** Fälligkeitstag (LocalDate.toEpochDay), `null` = ohne Datum. */
    val dueEpochDay: Long? = null,
    /** Uhrzeit in Minuten seit Mitternacht, `null` = ohne Uhrzeit. */
    val dueMinutes: Int? = null,
    val important: Boolean = false,
    /** Wiederkehrende Aufgabe: Abhaken verschiebt sie auf den nächsten Termin. */
    val repeating: Boolean = false,
)

@Serializable
data class WatchBirthday(
    val name: String,
    /** Tag des nächsten Geburtstags (LocalDate.toEpochDay). */
    val epochDay: Long,
    /** Alter, das an diesem Tag erreicht wird – falls das Geburtsjahr bekannt ist. */
    val age: Int? = null,
)

@Serializable
data class WatchHealth(
    /** Tag, zu dem [steps] gehört. */
    val epochDay: Long,
    val steps: Long? = null,
    val stepGoal: Int = 8000,
    /** Schlaf der letzten Nacht in Minuten. */
    val sleepMinutes: Long? = null,
    val restingHeartRate: Long? = null,
    val activeZoneMinutes: Long? = null,
    /** Wann die Gesundheitsdaten zuletzt geladen wurden. */
    val fetchedAt: Long = 0,
)

@Serializable
data class WatchNutrition(
    val epochDay: Long,
    /** Heute gegessene Kalorien. */
    val eatenKcal: Int = 0,
    /** Tagesbudget (Grundumsatz + Aktivität + Anpassung). */
    val budgetKcal: Int = 0,
    /** Die letzten Einträge von heute. */
    val entries: List<WatchFood> = emptyList(),
) {
    val remainingKcal: Int get() = budgetKcal - eatenKcal
}

@Serializable
data class WatchFood(
    val name: String,
    val kcal: Int,
    /** Mahlzeit als Text, z. B. „Frühstück“. */
    val meal: String = "",
)

/** Reine Auswertung der Übersicht für die Anzeige auf der Uhr. */
object WatchSnapshotLogic {

    /** Ab diesem Alter gilt die Übersicht als veraltet (die Uhr zeigt dann „Stand: …“ deutlich an). */
    const val STALE_MILLIS = 3L * 60 * 60 * 1000

    data class NowNext(val current: WatchEvent?, val next: WatchEvent?)

    /** Was läuft gerade, was kommt als Nächstes? (Wie die Karte auf der Startseite der Handy-App.) */
    fun nowNext(events: List<WatchEvent>, now: Long): NowNext {
        val timed = events.filter { !it.allDay }
        val current = timed.filter { it.begin <= now && it.end > now }.minByOrNull { it.end }
        val next = timed.filter { it.begin > now }.minByOrNull { it.begin }
        return NowNext(current, next)
    }

    /** Termine, die den Tag [dayStart, dayEnd) berühren – ganztägige zuerst, dann nach Beginn. */
    fun eventsOn(events: List<WatchEvent>, dayStart: Long, dayEnd: Long): List<WatchEvent> =
        events
            .filter { e -> if (e.end > e.begin) e.begin < dayEnd && e.end > dayStart else e.begin in dayStart until dayEnd }
            .sortedWith(compareBy({ !it.allDay }, { it.begin }, { it.title }))

    /** Die nächsten offenen Routinen/Medikamente: zuerst Überfälliges, dann Kommendes. */
    fun openRoutines(routines: List<WatchRoutine>, limit: Int = Int.MAX_VALUE): List<WatchRoutine> =
        routines.filter { it.open }.sortedWith(compareBy({ it.at }, { it.name })).take(limit)

    /** Alle Vorkommen eines Tages, nach Uhrzeit. */
    fun routinesOn(routines: List<WatchRoutine>, epochDay: Long): List<WatchRoutine> =
        routines.filter { it.epochDay == epochDay }.sortedWith(compareBy({ it.at }, { it.name }))

    fun isOverdue(r: WatchRoutine, now: Long): Boolean = r.open && r.at < now - 60_000L

    /** Aufgabe überfällig? (Fälligkeitstag vor [todayEpochDay].) */
    fun isOverdue(t: WatchTask, todayEpochDay: Long): Boolean = t.dueEpochDay != null && t.dueEpochDay < todayEpochDay

    fun isStale(snapshot: WatchSnapshot, now: Long): Boolean = now - snapshot.generatedAt > STALE_MILLIS

    /** Sichtbare Unterseiten in fester Reihenfolge; unbekannte IDs (neuere Handy-App) werden übergangen. */
    fun visiblePages(snapshot: WatchSnapshot): List<WatchPage> = WatchPage.entries.filter { it.id in snapshot.pages }

    /** Schritt-Fortschritt 0..1. */
    fun stepProgress(health: WatchHealth): Float {
        val steps = health.steps ?: return 0f
        if (health.stepGoal <= 0) return 0f
        return (steps.toFloat() / health.stepGoal).coerceIn(0f, 1f)
    }

    /** Anteil des Kalorienbudgets, der gegessen ist (0..1, bei Überschreitung 1). */
    fun eatenFraction(n: WatchNutrition): Float =
        if (n.budgetKcal <= 0) 0f else (n.eatenKcal.toFloat() / n.budgetKcal).coerceIn(0f, 1f)
}

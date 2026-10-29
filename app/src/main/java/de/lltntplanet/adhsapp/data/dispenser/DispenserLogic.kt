package de.lltntplanet.adhsapp.data.dispenser

import de.lltntplanet.adhsapp.data.db.DispenserEventKind
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineOccurrence
import de.lltntplanet.adhsapp.util.Dates
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Zuordnung einer Meldung „genommen“ zu einer Einnahmezeit: alle Spender-Medikamente dieser Uhrzeit. */
data class DispenserMatch(
    val date: LocalDate,
    val minutes: Int,
    /** Alle Medikamente im Spender mit genau dieser Einnahmezeit (sie liegen im selben Fach). */
    val occurrences: List<RoutineOccurrence>,
) {
    /** Davon noch nicht abgehakt oder übersprungen – diese werden abgehakt. */
    val open: List<RoutineOccurrence> get() = occurrences.filter { !it.done && !it.skipped }
}

/**
 * Reine Logik für den Medikamentenspender (Memio Go mit der App iDispenser): Benachrichtigungen einordnen
 * und einer Einnahme aus dem Medikamenten-Modul zuordnen. Die genauen Texte von iDispenser sind noch nicht
 * bekannt; die Erkennung arbeitet deshalb mit Stichwörtern auf Deutsch und Englisch.
 */
object DispenserLogic {

    /** App des Memio Go (Tuya-Hersteller-App von Joyage). Alle Meldungen werden gespeichert. */
    const val IDISPENSER_PACKAGE = "com.dispenser"

    /**
     * Allgemeine Tuya-Apps, falls der Spender dorthin umzieht. Sie melden auch andere Geräte – von dort
     * werden nur erkannte Spender-Meldungen gespeichert.
     */
    val SMART_HOME_PACKAGES = setOf("com.tuya.smartlife", "com.tuya.smart")

    val PACKAGES = SMART_HOME_PACKAGES + IDISPENSER_PACKAGE

    /** So weit vor der Einnahmezeit zählt „genommen“ noch dazu (Uhr von Spender und Handy weichen etwas ab). */
    const val EARLY_TOLERANCE_MILLIS = 5 * 60_000L

    /** Höchstens so lange nach der Einnahmezeit wird „genommen“ noch zugeordnet. */
    const val LATE_LIMIT_MILLIS = 12 * 60 * 60_000L

    // Reihenfolge zählt: „nicht genommen“ ist verpasst, „bereits genommen“ ist genommen.
    private val MISSED = listOf(
        "verpasst", "versäumt", "vergessen", "nicht genommen", "nicht eingenommen", "nicht entnommen",
        "missed", "not taken", "forgot",
    )
    private val REFILL = listOf(
        "nachfüll", "auffüll", "neu befüll", "fast leer", "refill", "reload", "running low",
    )
    private val TAKEN = listOf("genommen", "eingenommen", "entnommen", "taken", "took")
    private val DUE = listOf(
        "zeit für", "einnahmezeit", "fällig", "bereit", "bitte nehmen", "bitte einnehmen", "erinnerung",
        "time to take", "time for", "it's time", "ready", "please take", "reminder",
    )

    // „am“/„pm“ nur am Ende oder vor Satzzeichen – im Deutschen heißt „12:00 am Montag“ nicht 0:00 Uhr.
    private val TIME = Regex(
        """(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?!\d)(?:\s*(am|pm|a\.m\.|p\.m\.)(?!\p{L})(?!\s+\p{L}))?""",
        RegexOption.IGNORE_CASE,
    )

    fun isDedicatedApp(packageName: String): Boolean = packageName == IDISPENSER_PACKAGE

    /** Welche Art Meldung ist das? Unbekanntes bleibt [DispenserEventKind.OTHER] – lieber nichts abhaken als falsch. */
    fun classify(title: String, text: String): DispenserEventKind {
        val t = normalize("$title $text")
        return when {
            MISSED.any { it in t } -> DispenserEventKind.MISSED
            REFILL.any { it in t } -> DispenserEventKind.REFILL
            TAKEN.any { it in t } -> DispenserEventKind.TAKEN
            DUE.any { it in t } -> DispenserEventKind.DUE
            else -> DispenserEventKind.OTHER
        }
    }

    /** Uhrzeiten im Text („08:00“, „8:00 Uhr“, „8:00 PM“) als Minuten seit Mitternacht. */
    fun timesIn(text: String): List<Int> = TIME.findAll(text).map { m ->
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val suffix = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        hour * 60 + minute
    }.distinct().toList()

    /**
     * Zu welcher Einnahme gehört eine Meldung „genommen“ um [postedAt]? Der Spender öffnet ein Fach erst zur
     * Einnahmezeit – also zählt die letzte Einnahmezeit davor (höchstens [LATE_LIMIT_MILLIS] zurück). Nennt die
     * Meldung eine passende Uhrzeit ([textTimes]), gilt diese. Nur Medikamente, die als „im Spender“ markiert sind.
     */
    fun matchTaken(
        routines: List<RoutineEntity>,
        log: List<RoutineLogEntity>,
        postedAt: Long,
        zone: ZoneId,
        textTimes: List<Int> = emptyList(),
    ): DispenserMatch? {
        val inDispenser = routines.filter { it.dispenser && it.kind == RoutineKind.MEDICATION }
        if (inDispenser.isEmpty()) return null
        val day = Dates.toLocalDate(postedAt, zone)
        val candidates = RoutineLogic.occurrences(inDispenser, log, day.minusDays(1), day, zone)
            .filter { it.at <= postedAt + EARLY_TOLERANCE_MILLIS && it.at >= postedAt - LATE_LIMIT_MILLIS }
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { it.minutes in textTimes }.ifEmpty { candidates }
        val slotAt = pool.maxOf { it.at }
        val slot = pool.filter { it.at == slotAt }
        return DispenserMatch(slot.first().date, slot.first().minutes, slot)
    }

    private fun normalize(s: String): String = s.lowercase(Locale.GERMAN).replace(Regex("\\s+"), " ").trim()
}

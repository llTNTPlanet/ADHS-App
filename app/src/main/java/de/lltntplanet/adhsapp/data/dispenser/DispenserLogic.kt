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

    // Stichwörter als ganze Wörter („Video aufgenommen“ ist kein „genommen“); ein * erlaubt Endungen.
    private val MISSED = words("verpass*", "versäumt", "vergessen", "missed", "forgot*")
    private val REFILL = words("nachfüll*", "auffüll*", "neu befüll*", "fast leer", "refill*", "reload*", "running low")
    private val TAKEN = words("genommen", "eingenommen", "entnommen", "taken", "took")
    private val NEGATION = Regex(
        """(?<!\p{L})(?:nicht\p{L}*|kein\p{L}*|ohne|no|none|nothing|not|never|""" +
            """(?:was|were|has|have|had|did|does|do|is|are)nt)(?!\p{L})|n['’´`]t(?!\p{L})""",
    )

    /**
     * Fragen, Aufforderungen, Bedingungen und Rückblicke („Hast du … genommen?“, „sollte … eingenommen werden“,
     * „Falls bereits eingenommen, ignorieren“, „Zuletzt genommen um 08:00“) melden keine Einnahme.
     */
    private val REQUEST = words(
        "soll*", "muss", "musst", "müssen", "bitte", "werden", "hast du", "haben sie", "falls", "wenn", "ignorier*",
        "zuletzt", "letzte*", "should", "must", "need*", "to be taken", "have you", "did you", "please", "if",
        "ignore*", "last",
    )

    /** Satzweise prüfen: Ein „Bitte Fach schließen“ hinter „Medikament entnommen.“ ändert nichts an der Einnahme. */
    private val SENTENCE = Regex("""[^.!?;\n–—]+[.!?;\n–—]*""")
    private val DUE = words(
        "zeit für", "einnahmezeit*", "fällig*", "bereit*", "bitte nehmen", "bitte einnehmen", "erinnerung*",
        "time to take", "time for", "it's time", "ready", "please take", "reminder*",
    )

    /** Woran man in Smart Life & Co. eine Meldung des Spenders erkennt (dort melden auch Lampen und Kameras). */
    private val DISPENSER_WORDS = words(
        "medikament*", "medizin*", "tablette*", "pille*", "kapsel*", "dosis", "spender*", "memio*",
        "medication*", "medicine*", "pill", "pills", "dose*", "dispenser*",
    )

    /** Uhrzeiten danach gehören zu einer anderen Einnahme („Nächste Einnahme: 14:00“). */
    private val OTHER_DOSE = words("nächst*", "next", "bis", "until", "tomorrow")

    // Englisches AM/PM groß oder mit Punkten immer; kleines „am“ nur am Ende oder vor Satzzeichen –
    // im Deutschen heißt „12:30 am Montag“ nicht 0:30 Uhr.
    private val TIME = Regex(
        """(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?!\d)(?:\s*((?i:pm|p\.m\.|a\.m\.)|AM)(?!\p{L})|\s*(am)(?!\p{L})(?!\s+\p{L}))?""",
    )

    /** Nur die App des Spenders selbst darf Einnahmen abhaken. */
    fun isDedicatedApp(packageName: String): Boolean = packageName == IDISPENSER_PACKAGE

    /** Geht es in der Meldung überhaupt um den Spender? (Für Smart Life & Co.) */
    fun mentionsDispenser(title: String, text: String): Boolean = DISPENSER_WORDS.containsMatchIn(normalize("$title $text"))

    /**
     * Welche Art Meldung ist das? Unbekanntes bleibt [DispenserEventKind.OTHER] – lieber nichts abhaken als falsch.
     * „Genommen“ zählt nur in einem Satz ohne Verneinung, Frage, Aufforderung oder Bedingung.
     */
    fun classify(title: String, text: String): DispenserEventKind {
        val t = normalize("$title $text")
        val takenSentences = sentences(title, text).filter { TAKEN.containsMatchIn(it) }
        return when {
            takenSentences.any { NEGATION.containsMatchIn(it) } -> DispenserEventKind.MISSED
            takenSentences.any { '?' !in it && !REQUEST.containsMatchIn(it) } -> DispenserEventKind.TAKEN
            MISSED.containsMatchIn(t) -> DispenserEventKind.MISSED
            REFILL.containsMatchIn(t) -> DispenserEventKind.REFILL
            takenSentences.isNotEmpty() || DUE.containsMatchIn(t) -> DispenserEventKind.DUE
            else -> DispenserEventKind.OTHER
        }
    }

    /**
     * Uhrzeiten im Text („08:00“, „8:00 Uhr“, „8:00 PM“) als Minuten seit Mitternacht – ohne solche, die zu einer
     * anderen Einnahme gehören („nächste Einnahme 14:00“).
     */
    fun timesIn(text: String): List<Int> = TIME.findAll(text)
        .filterNot { m -> refersToOtherDose(text, m.range.first) }
        .map { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val suffix = (m.groupValues[3].ifEmpty { m.groupValues[4] }).lowercase(Locale.ROOT).replace(".", "")
            if (suffix == "pm" && hour in 1..11) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            hour * 60 + minute
        }.distinct().toList()

    /**
     * Zu welcher Einnahme gehört eine Meldung „genommen“ um [postedAt]? Der Spender öffnet ein Fach erst zur
     * Einnahmezeit – also zählt die letzte Einnahmezeit davor (höchstens [LATE_LIMIT_MILLIS] zurück). Nennt die
     * Meldung genau eine der Einnahmezeiten ([textTimes]), gilt diese – ist sie schon abgehakt, wird nichts abgehakt.
     * Nur Medikamente, die als „im Spender“ markiert sind.
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
        val named = candidates.filter { it.minutes in textTimes }
        val pool = if (named.map { it.minutes }.distinct().size == 1) named else candidates
        val slotAt = pool.maxOf { it.at }
        val slot = pool.filter { it.at == slotAt }
        return DispenserMatch(slot.first().date, slot.first().minutes, slot)
    }

    private fun refersToOtherDose(text: String, start: Int): Boolean {
        val before = text.substring(maxOf(0, start - 40), start)
        val clause = before.substring(before.indexOfLast { it in ".,;!?\n" } + 1)
        return OTHER_DOSE.containsMatchIn(clause.lowercase(Locale.GERMAN))
    }

    private fun words(vararg keywords: String): Regex = Regex(
        keywords.joinToString("|", prefix = "(?<!\\p{L})(?:", postfix = ")") { w ->
            if (w.endsWith("*")) Regex.escape(w.dropLast(1)) else Regex.escape(w) + "(?!\\p{L})"
        },
    )

    private fun sentences(title: String, text: String): List<String> =
        SENTENCE.findAll("$title\n$text".lowercase(Locale.GERMAN)).map { normalize(it.value) }.filter { it.isNotEmpty() }.toList()

    private fun normalize(s: String): String = s.lowercase(Locale.GERMAN).replace(Regex("\\s+"), " ").trim()
}

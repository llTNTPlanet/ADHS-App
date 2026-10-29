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
        """(?<!\p{L})(?:nicht\p{L}*|kein\p{L}*|ohne|nein|no|none|nothing|not|never|""" +
            """(?:was|were|has|have|had|did|does|do|is|are)nt)(?!\p{L})|n['’´`]t(?!\p{L})""",
    )

    /**
     * Fragen, Aufforderungen, Bedingungen und Rückblicke („Hast du … genommen?“, „sollte … eingenommen werden“,
     * „Falls bereits eingenommen, ignorieren“, „Zuletzt genommen um 08:00“, „Tippe auf Genommen“) melden keine Einnahme.
     */
    private val REQUEST = words(
        "soll*", "muss", "musst", "müssen", "bitte", "werden", "hast du", "haben sie", "falls", "wenn", "ignorier*",
        "zuletzt", "letzte*", "markier*", "bestätig*", "tipp*", "should", "must", "need*", "to be taken", "have you",
        "did you", "please", "if", "when", "ignore*", "last", "tap", "confirm*", "mark as",
    )

    /** Zählerstände wie „1 von 2 Dosen eingenommen“ sagen nicht, ob gerade jetzt genommen wurde. */
    private val COUNTER = Regex("""\d+\s*(?:von|of|/)\s*\d+""")

    /** Vor dem „genommen“-Satz im Text: Hier geht es um eine fällige Einnahme („Zeit für …“). */
    private val PROMPT = words(
        "zeit für", "einnahmezeit*", "fällig*", "bitte nehmen", "bitte einnehmen", "erinnerung*",
        "time to take", "time for", "it's time", "please take", "reminder*",
    )

    /** Nach dem „genommen“-Satz: Aufforderung, jetzt (noch etwas) einzunehmen. */
    private val TAKE_NOW = words("nimm", "nehmen", "einnehmen", "take", "zeit für", "time for", "time to")

    /**
     * Satzgrenzen nur dort, wo danach ein neuer Satz beginnt (Großbuchstabe, Zahl, Anführungszeichen), dazu Zeilen und
     * Gedankenstriche. Punkte in „29.10.“ oder „8:00 a.m. pills“ trennen so nicht.
     */
    private val SENTENCE_BREAK = Regex("""(?<=[.!?;])\s+(?=[\p{Lu}\p{N}„“"'])|\n|\s[–—-]\s""")
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

    /** Eingeordnete Meldung; bei „genommen“ auch die Sätze, die das melden (nur aus ihnen zählen Uhrzeiten). */
    data class Reading(val kind: DispenserEventKind, val takenText: String = "")

    fun classify(title: String, text: String): DispenserEventKind = read(title, text).kind

    /**
     * Welche Art Meldung ist das? Unbekanntes bleibt [DispenserEventKind.OTHER] – lieber nichts abhaken als falsch.
     * „Genommen“ zählt nur, wenn kein „verpasst“ vorkommt, bis zum letzten „genommen“-Satz keine Verneinung steht,
     * davor keine Frage, Aufforderung oder Erinnerung, jeder „genommen“-Satz eine schlichte Feststellung ist und danach
     * nicht zum Einnehmen aufgefordert wird. Nachsätze wie „Bitte Fach schließen.“ stören nicht.
     */
    fun read(title: String, text: String): Reading {
        val t = normalize("$title $text")
        val titleSentences = splitSentences(title)
        val original = titleSentences + splitSentences(text)
        val all = original.map(::normalize)
        val taken = all.indices.filter { TAKEN.containsMatchIn(all[it]) }
        val other = when {
            REFILL.containsMatchIn(t) -> DispenserEventKind.REFILL
            taken.isNotEmpty() || DUE.containsMatchIn(t) -> DispenserEventKind.DUE
            else -> DispenserEventKind.OTHER
        }
        if (MISSED.containsMatchIn(t)) return Reading(DispenserEventKind.MISSED)
        if (taken.isEmpty()) return Reading(other)
        val first = taken.first()
        val last = taken.last()
        if (all.subList(0, last + 1).any { NEGATION.containsMatchIn(it) }) return Reading(DispenserEventKind.MISSED)
        val plain = taken.all { i -> '?' !in all[i] && !REQUEST.containsMatchIn(all[i]) && !COUNTER.containsMatchIn(all[i]) }
        // Vor dem ersten „genommen“: Frage oder Aufforderung, im Text auch eine Erinnerung (im Titel steht die oft immer).
        val promptBefore = (0 until first).any { i ->
            '?' in all[i] || REQUEST.containsMatchIn(all[i]) || (i >= titleSentences.size && PROMPT.containsMatchIn(all[i]))
        }
        val takeAfter = all.drop(last + 1).any { TAKE_NOW.containsMatchIn(it) }
        if (!plain || promptBefore || takeAfter) return Reading(other)
        return Reading(DispenserEventKind.TAKEN, taken.joinToString("\n") { original[it] })
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

    private fun splitSentences(s: String): List<String> = SENTENCE_BREAK.split(s).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalize(s: String): String = s.lowercase(Locale.GERMAN).replace(Regex("\\s+"), " ").trim()
}

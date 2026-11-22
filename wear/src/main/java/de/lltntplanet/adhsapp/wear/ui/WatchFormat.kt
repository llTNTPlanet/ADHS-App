package de.lltntplanet.adhsapp.wear.ui

import de.lltntplanet.adhsapp.shared.WatchBirthday
import de.lltntplanet.adhsapp.shared.WatchEvent
import de.lltntplanet.adhsapp.shared.WatchNutrition
import de.lltntplanet.adhsapp.shared.WatchRoutine
import de.lltntplanet.adhsapp.shared.WatchSnapshotLogic
import de.lltntplanet.adhsapp.shared.WatchTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Texte für die Anzeige auf der Uhr: Uhrzeiten, Dauer, „in 25 Min.“, Tage. Reine Logik ohne Android, damit sie
 * sich testen lässt. Wochentag und Datum sind selbst gebaut – so sehen sie auf jedem Gerät gleich aus.
 */
object WatchFormat {

    private const val MINUTE_MILLIS = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24L * 60

    /** Näher als das: Dann steht bei einer Routine „in 25 Min.“ statt nur der Uhrzeit. */
    private const val SOON_MILLIS = 2L * 60 * 60 * 1000

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
    private val weekdays = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")

    /** Zeitzone des Handys ([zoneId] aus der Übersicht). Ist sie leer oder unbekannt, gilt [fallback]. */
    fun zone(zoneId: String, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        runCatching { ZoneId.of(zoneId) }.getOrDefault(fallback)

    /** Uhrzeit, z. B. „14:05“. */
    fun time(millis: Long, zone: ZoneId): String = clock.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Uhrzeit aus Minuten seit Mitternacht: 485 → „08:05“. */
    fun minutesOfDay(minutes: Int): String {
        val safe = minutes.coerceIn(0, MINUTES_PER_DAY.toInt() - 1)
        return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
    }

    /** Tag eines Zeitpunkts in [zone] (LocalDate.toEpochDay). */
    fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Beginn eines Tages in Millisekunden. */
    fun dayStart(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Dauer in Worten, z. B. „45 Min.“, „2 Std. 15 Min.“ oder „1 Tag 3 Std.“ – wie in der Handy-App.
     * Mit [dative] für Sätze wie „in 2 Tagen“.
     */
    fun duration(minutes: Long, dative: Boolean = false): String {
        val total = if (minutes < 0) -minutes else minutes
        val days = total / MINUTES_PER_DAY
        val hours = (total % MINUTES_PER_DAY) / MINUTES_PER_HOUR
        val rest = total % MINUTES_PER_HOUR
        val dayWord = when {
            days == 1L -> "Tag"
            dative -> "Tagen"
            else -> "Tage"
        }
        return when {
            days > 0 && hours > 0 -> "$days $dayWord $hours Std."
            days > 0 -> "$days $dayWord"
            hours > 0 && rest > 0 -> "$hours Std. $rest Min."
            hours > 0 -> "$hours Std."
            else -> "$rest Min."
        }
    }

    /** Abstand zu jetzt: „in 25 Min.“, „jetzt“ oder „seit 5 Min.“ – wie in der Handy-App. */
    fun countdown(target: Long, now: Long): String {
        val diff = target - now
        return when {
            diff >= MINUTE_MILLIS -> "in ${duration((diff + MINUTE_MILLIS - 1) / MINUTE_MILLIS, dative = true)}"
            diff > -MINUTE_MILLIS -> "jetzt"
            else -> "seit ${duration(-diff / MINUTE_MILLIS, dative = true)}"
        }
    }

    /** Restzeit eines laufenden Termins, auf volle Minuten aufgerundet: „noch 25 Min.“ */
    fun remaining(end: Long, now: Long): String {
        val minutes = ((end - now).coerceAtLeast(0L) + MINUTE_MILLIS - 1) / MINUTE_MILLIS
        return "noch ${duration(minutes.coerceAtLeast(1L))}"
    }

    /** Wochentag und Datum, kurz: „Sa. 28.11.“ */
    fun shortDate(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${weekdays[date.dayOfWeek.value - 1]} ${date.dayOfMonth}.${date.monthValue}."
    }

    /** „Heute“, „Morgen“, „Gestern“ – sonst das kurze Datum. */
    fun relativeDay(epochDay: Long, todayEpochDay: Long): String = when (epochDay - todayEpochDay) {
        0L -> "Heute"
        1L -> "Morgen"
        -1L -> "Gestern"
        else -> shortDate(epochDay)
    }

    /** Wie weit ein Tag entfernt ist: „Heute“, „Morgen“, „in 5 Tagen“. */
    fun daysUntil(epochDay: Long, todayEpochDay: Long): String {
        val days = epochDay - todayEpochDay
        return when {
            days == 0L -> "Heute"
            days == 1L -> "Morgen"
            days == -1L -> "Gestern"
            days > 0 -> "in $days Tagen"
            else -> "vor ${-days} Tagen"
        }
    }

    /** Wann ein kommender Termin beginnt: heute nur die Uhrzeit, sonst mit Tag („Morgen 09:00“). */
    fun startLine(event: WatchEvent, todayEpochDay: Long, zone: ZoneId): String {
        val day = epochDay(event.begin, zone)
        val start = time(event.begin, zone)
        return if (day == todayEpochDay) start else "${relativeDay(day, todayEpochDay)} $start"
    }

    /**
     * Zeitangabe eines Termins an einem Tag ([dayStart] bis [dayEnd]): „14:00–15:00“, „ganztägig“ oder – wenn
     * er über Mitternacht reicht – „bis 09:30“ bzw. „ab 22:00“.
     */
    fun eventTime(event: WatchEvent, dayStart: Long, dayEnd: Long, zone: ZoneId): String = when {
        event.allDay -> "ganztägig"
        event.begin <= dayStart && event.end >= dayEnd -> "ganztägig"
        event.begin < dayStart -> "bis ${time(event.end, zone)}"
        event.end > dayEnd -> "ab ${time(event.begin, zone)}"
        event.end <= event.begin -> time(event.begin, zone)
        else -> "${time(event.begin, zone)}–${time(event.end, zone)}"
    }

    /** Wort für den abgehakten Zustand je Art – wie in der Handy-App. */
    fun doneWord(kind: String): String = when (kind) {
        WatchRoutine.KIND_MEDICATION -> "genommen"
        WatchRoutine.KIND_CHECKUP -> "ausgefüllt"
        else -> "erledigt"
    }

    /**
     * Zeile unter einer Routine: Uhrzeit (an anderen Tagen mit Tag), Dosis und Zustand – z. B.
     * „08:05 · 1 Tablette · in 25 Min.“ oder „Morgen 08:05 · 1 Tablette“.
     */
    fun routineLine(routine: WatchRoutine, now: Long, todayEpochDay: Long): String {
        val day = if (routine.epochDay == todayEpochDay) null else relativeDay(routine.epochDay, todayEpochDay)
        val status = when {
            routine.skipped -> "übersprungen"
            routine.done && routine.byDispenser -> "${doneWord(routine.kind)} (Spender)"
            routine.done -> doneWord(routine.kind)
            !routine.canToggle -> "am Handy ausfüllen"
            WatchSnapshotLogic.isOverdue(routine, now) -> "noch offen"
            routine.at - now > SOON_MILLIS -> null
            else -> countdown(routine.at, now)
        }
        val start = listOfNotNull(day, minutesOfDay(routine.minutes)).joinToString(" ")
        return listOfNotNull(start, routine.dose.ifBlank { null }, status).joinToString(" · ")
    }

    /** Stand der Routinen von heute: „3 von 5 geschafft“. */
    fun routineSummary(done: Int, total: Int): String =
        if (done >= total) "Alles geschafft für heute 🎉" else "$done von $total geschafft"

    /** Zeile unter einer offenen Aufgabe: seit wann überfällig, sonst die Uhrzeit. `null` = nichts anzuzeigen. */
    fun taskLine(task: WatchTask, todayEpochDay: Long): String? {
        val day = task.dueEpochDay
        if (day != null && day < todayEpochDay) {
            val days = todayEpochDay - day
            return if (days == 1L) "überfällig seit gestern" else "überfällig seit $days Tagen"
        }
        return task.dueMinutes?.let { minutesOfDay(it) }
    }

    /** Zeile unter einem Geburtstag: „Heute 🎉 · wird 30“ oder „in 5 Tagen · Fr. 27.11. · wird 30“. */
    fun birthdayLine(birthday: WatchBirthday, todayEpochDay: Long): String {
        val days = birthday.epochDay - todayEpochDay
        val distance = if (days == 0L) "Heute 🎉" else daysUntil(birthday.epochDay, todayEpochDay)
        val date = if (days in -1L..1L) null else shortDate(birthday.epochDay)
        val age = birthday.age?.let { "wird $it" }
        return listOfNotNull(distance, date, age).joinToString(" · ")
    }

    /** Zahl mit Tausenderpunkt: 6230 → „6.230“. */
    fun number(value: Long): String {
        val digits = value.toString().removePrefix("-")
        val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
        return if (value < 0) "-$grouped" else grouped
    }

    /** Was vom Kalorienbudget übrig ist: „1.250 kcal frei“ oder „150 kcal drüber“ – wie in der Handy-App. */
    fun kcalLeft(nutrition: WatchNutrition): String {
        val left = nutrition.remainingKcal.toLong()
        return if (left >= 0) "${number(left)} kcal frei" else "${number(-left)} kcal drüber"
    }

    /** Wie alt die Daten sind: „Stand: 14:05“, an anderen Tagen mit Tag („Stand: Gestern 23:10“). */
    fun asOf(generatedAt: Long, now: Long, zone: ZoneId): String {
        val day = epochDay(generatedAt, zone)
        val today = epochDay(now, zone)
        val created = time(generatedAt, zone)
        return if (day == today) "Stand: $created" else "Stand: ${relativeDay(day, today)} $created"
    }

    /** Hinweis auf Erinnerungen, die noch auf der Uhr stehen. */
    fun reminders(count: Int): String = if (count == 1) "1 Erinnerung offen" else "$count Erinnerungen offen"

    /** Was nicht mehr in die Liste passt: „+ 3 weitere“, mit [onPhone] „+ 3 weitere am Handy“. */
    fun more(count: Int, onPhone: Boolean): String = if (onPhone) "+ $count weitere am Handy" else "+ $count weitere"
}

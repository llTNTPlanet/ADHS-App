package de.lltntplanet.adhsapp.wear.alert

import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.shared.WatchReminder
import de.lltntplanet.adhsapp.wear.ui.WatchFormat
import java.time.ZoneId

/** Wie eine Erinnerung gerade als Benachrichtigung auf der Uhr steht. */
enum class AlertStyle {
    /** Klingelt: Der Dienst vibriert, die Benachrichtigung lässt sich nicht wegwischen. */
    RINGING,

    /** Rückfall: Das System vibriert in Schleife, weil der Dienst nicht starten durfte. */
    INSISTENT,

    /** Still: bleibt stehen, bis bestätigt wird. */
    QUIET,
}

/** Wer gerade klingelt. */
enum class RingMode {
    /** Der Dienst ist angefordert, läuft aber noch nicht. */
    STARTING,

    /** Der Dienst vibriert. */
    SERVICE,

    /** Der Dienst durfte nicht starten: Eine Benachrichtigung vibriert über das System. */
    FALLBACK,
}

/** Ein Knopf einer Erinnerung – in der Benachrichtigung und im Vollbild derselbe. */
data class AlertButton(
    /** Eine der Konstanten aus [ReminderActions]. */
    val action: String,
    val label: String,
    /** Minuten bei [ReminderActions.SNOOZE]. */
    val minutes: Int = 0,
)

/** Reine Logik rund um die Erinnerungen auf der Uhr – ohne Android, damit sie sich testen lässt. */
object AlertLogic {

    const val SNOOZE_MINUTES = 10

    const val NOTICE_TITLE = "Handy nicht erreichbar"
    const val NOTICE_TEXT = "Bitte am Handy bestätigen."

    /** Hinweis, wenn ein Knopfdruck das Handy nicht erreicht hat. */
    const val PHONE_UNREACHABLE = "Handy nicht erreichbar – bitte am Handy bestätigen"

    /** Neue Erinnerungen kommen nach vorn; eine schon gezeigte mit demselben [WatchReminder.originKey] wird ersetzt. */
    fun merge(active: List<WatchReminder>, incoming: List<WatchReminder>): List<WatchReminder> {
        val fresh = incoming.distinctBy { it.originKey }
        val keys = fresh.map { it.originKey }.toSet()
        return fresh + active.filter { it.originKey !in keys }
    }

    /**
     * Knöpfe in fester Reihenfolge: zuerst das, was man meistens will („Genommen“/„Erledigt“, sonst „OK“).
     * Der erste Knopf ist der Hauptknopf.
     */
    fun buttons(reminder: WatchReminder): List<AlertButton> = buildList {
        if (reminder.canDone) add(AlertButton(ReminderActions.DONE, reminder.doneLabel.ifBlank { "Erledigt" }))
        add(AlertButton(ReminderActions.SNOOZE, "$SNOOZE_MINUTES Min. später", SNOOZE_MINUTES))
        add(AlertButton(ReminderActions.DISMISS, "OK"))
        if (reminder.canMute) add(AlertButton(ReminderActions.MUTE, "Keine weiteren"))
    }

    /** Nummer der Benachrichtigung zu einer Erinnerung. Nie 0, weil ein Vordergrunddienst die 0 nicht annimmt. */
    fun notificationId(originKey: String): Int = originKey.hashCode().let { if (it == 0) 1 else it }

    /**
     * Die Erinnerung, deren Benachrichtigung der Klingel-Dienst trägt: die bisherige ([current]), solange sie
     * noch klingelt – sonst die erste klingelnde.
     */
    fun anchor(active: List<WatchReminder>, ringing: Set<String>, current: String?): WatchReminder? =
        active.firstOrNull { it.originKey == current && it.originKey in ringing }
            ?: active.firstOrNull { it.originKey in ringing }

    /**
     * Wie die Benachrichtigung einer Erinnerung aussehen soll. [mode] ist `null`, wenn nichts klingelt. Im
     * Rückfall vibriert nur eine Benachrichtigung ([fallbackKey]) – das System spielt immer nur eine in Schleife.
     * Sind Vibration und Ton beide abgeschaltet ([signal] = `false`), bleibt auch sie still.
     */
    fun style(
        originKey: String,
        ringing: Set<String>,
        mode: RingMode?,
        fallbackKey: String?,
        signal: Boolean = true,
    ): AlertStyle = when {
        mode == null || originKey !in ringing -> AlertStyle.QUIET
        mode == RingMode.FALLBACK -> if (signal && originKey == fallbackKey) AlertStyle.INSISTENT else AlertStyle.QUIET
        else -> AlertStyle.RINGING
    }

    /**
     * Text unter der Überschrift: die Zeitzeile ([timeLine]), Ort/Dosis/Notiz und der Hinweis aufs Handy – je
     * eine Zeile.
     */
    fun text(reminder: WatchReminder, now: Long, zone: ZoneId): String =
        listOfNotNull(timeLine(reminder, now, zone), reminder.detail, reminder.phoneHint)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /**
     * Die Zeile unter der Überschrift, für [now] neu gerechnet – mit denselben Wendungen wie am Handy
     * („Beginnt in 15 Min. · 14:00–15:00“, „Läuft seit 5 Min. · bis 15:00“). So stimmt sie auch noch, wenn die
     * Erinnerung länger auf der Uhr steht.
     *
     * Der feste Text vom Handy ([WatchReminder.subtitle]) bleibt, wo sich die Zeile nicht sicher nachbilden
     * lässt: ohne [WatchReminder.targetTime], beim Geburtstag (das Alter kennt nur das Handy), bei unbekannten
     * Arten, bei Routinen, deren Dosis sich in der Zeile vom Handy nicht wiederfindet, und bei ganztägigen
     * Terminen, wenn die Uhr in einer anderen Zeitzone rechnet als das Handy.
     */
    fun timeLine(reminder: WatchReminder, now: Long, zone: ZoneId): String {
        val target = reminder.targetTime ?: return reminder.subtitle
        val line = when (reminder.kind) {
            KIND_EVENT -> eventLine(reminder, target, now, zone)
            KIND_TASK -> taskLine(reminder, target, now, zone)
            KIND_MEDICATION, KIND_ROUTINE -> routineLine(reminder, target, now, zone)
            KIND_CHECKUP -> checkupLine(target, now, zone)
            else -> null
        }
        return line ?: reminder.subtitle
    }

    /** Termin: „Beginnt …“, „Beginnt jetzt“, „Läuft seit …“ – ganztägig nur der Tag. */
    private fun eventLine(reminder: WatchReminder, start: Long, now: Long, zone: ZoneId): String? {
        val today = WatchFormat.epochDay(now, zone)
        val day = WatchFormat.epochDay(start, zone)
        if (reminder.allDay) {
            // Ganztägiges beginnt um Mitternacht in der Zeitzone des Handys. Ist das hier keine Mitternacht,
            // rechnet die Uhr in einer anderen Zeitzone und käme auf den falschen Tag.
            if (WatchFormat.dayStart(day, zone) != start) return null
            return "${WatchFormat.relativeDay(day, today)} · ganztägig"
        }
        val end = reminder.targetEnd
        val begin = WatchFormat.time(start, zone)
        val range = if (end != null && end > start) "$begin–${WatchFormat.time(end, zone)}" else begin
        val prefix = dayPrefix(day, today)
        val distance = WatchFormat.countdown(start, now)
        return when {
            start - now >= MINUTE_MILLIS -> "Beginnt $distance · $prefix$range"
            start >= now - MINUTE_MILLIS -> "Beginnt jetzt · $range"
            end != null && end > now -> "Läuft $distance · bis ${WatchFormat.time(end, zone)}"
            // Schon vorbei (oder ohne Ende): Auf der Uhr bleibt die Erinnerung stehen – dann steht da nur noch,
            // wann der Termin war. („Beginnt jetzt“ wie am Handy wäre hier falsch.)
            else -> "$prefix$range"
        }
    }

    /** Aufgabe: „Fällig: Heute“, „Fällig in 2 Std. · 14:00“ oder „Jetzt erledigen · fällig heute 14:00“. */
    private fun taskLine(reminder: WatchReminder, due: Long, now: Long, zone: ZoneId): String {
        val day = WatchFormat.relativeDay(WatchFormat.epochDay(due, zone), WatchFormat.epochDay(now, zone))
        val time = WatchFormat.time(due, zone)
        return when {
            reminder.allDay -> "Fällig: $day"
            due > now + MINUTE_MILLIS -> "Fällig ${WatchFormat.countdown(due, now)} · $time"
            else -> "Jetzt erledigen · fällig ${day.lowercase()} $time"
        }
    }

    /** Medikament und Routine: „Einnahme in 15 Min. · 08:00“, „Jetzt · 08:00“, „Einnahme war um 08:00 · noch offen“. */
    private fun routineLine(reminder: WatchReminder, at: Long, now: Long, zone: ZoneId): String? {
        val time = WatchFormat.time(at, zone)
        // Die Dosis steht nur in der Zeile vom Handy. Findet sie sich dort nicht sicher wieder, bleibt der feste Text.
        val dose = doseSuffix(reminder.subtitle, time) ?: return null
        val verb = if (reminder.kind == KIND_MEDICATION) "Einnahme" else "Geplant"
        val prefix = dayPrefix(WatchFormat.epochDay(at, zone), WatchFormat.epochDay(now, zone))
        return when {
            at - now >= MINUTE_MILLIS -> "$verb ${WatchFormat.countdown(at, now)} · $prefix$time$dose"
            at < now - MINUTE_MILLIS -> "$verb war um $prefix$time · noch offen$dose"
            else -> "Jetzt · $time$dose"
        }
    }

    /** Gefühls-Checkup: „Checkup in 15 Min. · 18:00“ bzw. „Checkup war um 18:00 · noch offen – wie geht es dir?“. */
    private fun checkupLine(at: Long, now: Long, zone: ZoneId): String {
        val time = WatchFormat.time(at, zone)
        val prefix = dayPrefix(WatchFormat.epochDay(at, zone), WatchFormat.epochDay(now, zone))
        return when {
            at - now >= MINUTE_MILLIS -> "Checkup ${WatchFormat.countdown(at, now)} · $prefix$time"
            at < now - MINUTE_MILLIS -> "Checkup war um $prefix$time · noch offen – wie geht es dir?"
            else -> "Wie geht es dir gerade? Kurz die Symptome bewerten."
        }
    }

    /** „Morgen, “ vor der Uhrzeit, wenn der Tag nicht heute ist – sonst leer. */
    private fun dayPrefix(epochDay: Long, todayEpochDay: Long): String =
        if (epochDay == todayEpochDay) "" else "${WatchFormat.relativeDay(epochDay, todayEpochDay)}, "

    /**
     * Was das Handy in seiner Zeile hinter die Uhrzeit [time] gehängt hat: die Dosis („ · 1 Tablette“) oder
     * nichts. `null`, wenn die Uhrzeit dort nicht steht oder etwas anderes folgt – dann lässt sich die Zeile
     * nicht sicher neu bilden.
     */
    private fun doseSuffix(subtitle: String, time: String): String? {
        val at = subtitle.indexOf(time)
        if (at < 0) return null
        val rest = subtitle.substring(at + time.length).removePrefix(STILL_OPEN)
        return rest.takeIf { it.isEmpty() || it.startsWith(" · ") }
    }

    private const val MINUTE_MILLIS = 60_000L
    private const val STILL_OPEN = " · noch offen"

    // Arten einer Erinnerung ([WatchReminder.kind]) – die Namen von ReminderKind in der Handy-App.
    private const val KIND_EVENT = "EVENT"
    private const val KIND_TASK = "TASK"
    private const val KIND_MEDICATION = "MEDICATION"
    private const val KIND_ROUTINE = "ROUTINE"
    private const val KIND_CHECKUP = "CHECKUP"
}

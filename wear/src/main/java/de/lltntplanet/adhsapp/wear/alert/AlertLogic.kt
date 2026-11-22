package de.lltntplanet.adhsapp.wear.alert

import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.shared.WatchReminder

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
     */
    fun style(originKey: String, ringing: Set<String>, mode: RingMode?, fallbackKey: String?): AlertStyle = when {
        mode == null || originKey !in ringing -> AlertStyle.QUIET
        mode == RingMode.FALLBACK -> if (originKey == fallbackKey) AlertStyle.INSISTENT else AlertStyle.QUIET
        else -> AlertStyle.RINGING
    }

    /** Text unter der Überschrift: Zeile vom Handy, Ort/Dosis/Notiz und der Hinweis aufs Handy – je eine Zeile. */
    fun text(reminder: WatchReminder): String =
        listOfNotNull(reminder.subtitle, reminder.detail, reminder.phoneHint)
            .filter { it.isNotBlank() }
            .joinToString("\n")
}

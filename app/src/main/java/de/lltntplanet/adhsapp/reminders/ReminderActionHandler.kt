package de.lltntplanet.adhsapp.reminders

import de.lltntplanet.adhsapp.data.google.GoogleJson
import de.lltntplanet.adhsapp.data.routines.RoutineRepository
import de.lltntplanet.adhsapp.data.tasks.TaskRepository
import kotlinx.serialization.encodeToString

/**
 * Eine Erinnerung als JSON. So tragen sie die Knöpfe der Handy-Benachrichtigung als Extra mit sich – und genauso
 * schickt die Uhr sie bei einem Knopfdruck zurück. Das Handy kann die Aktion damit ohne gemerkten Zustand ausführen.
 */
object ReminderPayload {
    fun encode(reminder: Reminder): String = GoogleJson.encodeToString(reminder)

    /** `null`, wenn sich der Text nicht als Erinnerung lesen lässt. */
    fun decode(json: String): Reminder? = runCatching { GoogleJson.decodeFromString<Reminder>(json) }.getOrNull()
}

/** Welche Knöpfe eine Erinnerung hat – gleich in der Handy-Benachrichtigung und auf der Uhr. */
object ReminderButtons {
    /** „Erledigt“ bzw. „Genommen“: nur bei Aufgaben, Routinen und Medikamenten mit Bezug zum Eintrag. */
    fun canDone(r: Reminder): Boolean = (r.kind == ReminderKind.TASK || r.kind.isRoutine) && r.refId != null

    fun doneLabel(r: Reminder): String = if (r.kind == ReminderKind.MEDICATION) "Genommen" else "Erledigt"
}

/**
 * Führt aus, was ein Knopf einer Erinnerung auslöst – egal ob er in der Handy-Benachrichtigung oder auf der Uhr
 * gedrückt wurde.
 */
class ReminderActionHandler(
    private val notifier: ReminderNotifier,
    private val engine: ReminderEngine,
    private val tasks: TaskRepository,
    private val routines: RoutineRepository,
) {
    enum class Action { SNOOZE, DONE, MUTE, DISMISS }

    /**
     * Entfernt die Erinnerung (Benachrichtigung, offenes Vollbild, Uhr) und führt [action] aus.
     * [snoozeMinutes] zählt nur beim Schlummern; 0 oder weniger heißt [DEFAULT_SNOOZE_MINUTES].
     */
    suspend fun perform(action: Action, reminder: Reminder, snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES) {
        notifier.cancel(reminder)
        when (action) {
            Action.SNOOZE -> engine.snooze(reminder, if (snoozeMinutes > 0) snoozeMinutes else DEFAULT_SNOOZE_MINUTES)
            Action.DONE -> markDone(reminder)
            Action.MUTE -> engine.mute(reminder)
            Action.DISMISS -> Unit
        }
    }

    private suspend fun markDone(reminder: Reminder) {
        val id = reminder.refId ?: return
        if (!ReminderButtons.canDone(reminder)) return
        if (reminder.kind.isRoutine) {
            routines.setDoneAt(id, reminder.targetTime ?: reminder.triggerAt)
            return
        }
        val task = tasks.byId(id) ?: return
        // Wiederkehrende Aufgabe schon weitergerückt (z. B. auf dem anderen Gerät abgehakt)? Dann gehört diese
        // Erinnerung zum alten Termin und schaltet nicht noch einmal weiter.
        if (!ReminderPlanner.isCurrentTaskReminder(reminder.originKey ?: reminder.key, task)) return
        tasks.setDone(task, true)
    }

    companion object {
        /** „10 Min. später“ – der Schlummer-Knopf der Benachrichtigung. */
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}

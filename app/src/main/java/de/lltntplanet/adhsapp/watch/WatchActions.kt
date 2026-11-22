package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.reminders.ReminderActionHandler
import de.lltntplanet.adhsapp.reminders.ReminderPayload
import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.ReminderAction
import de.lltntplanet.adhsapp.shared.ReminderActions
import de.lltntplanet.adhsapp.shared.WatchJson
import java.time.LocalDate

/**
 * Führt aus, was auf der Uhr gedrückt wurde. Jede Funktion meldet, ob es geklappt hat – das Handy antwortet der Uhr
 * damit „ausgeführt“ oder „fehlgeschlagen“.
 */
internal object WatchActions {

    private const val MINUTES_PER_DAY = 24 * 60

    /** Knopf einer Erinnerung auf der Uhr → dieselbe Aktion wie der Knopf in der Handy-Benachrichtigung. */
    fun actionFor(name: String): ReminderActionHandler.Action? = when (name) {
        ReminderActions.SNOOZE -> ReminderActionHandler.Action.SNOOZE
        ReminderActions.DONE -> ReminderActionHandler.Action.DONE
        ReminderActions.MUTE -> ReminderActionHandler.Action.MUTE
        ReminderActions.DISMISS -> ReminderActionHandler.Action.DISMISS
        else -> null
    }

    /** Knopf in einer Erinnerung ([ReminderAction] als JSON). */
    suspend fun reminderAction(c: AppContainer, bytes: ByteArray): Boolean {
        val request = runCatching { WatchJson.decodeFromString<ReminderAction>(bytes.decodeToString()) }.getOrNull()
            ?: return false
        val action = actionFor(request.action) ?: return false
        val reminder = ReminderPayload.decode(request.payload) ?: return false
        c.reminderActions.perform(action, reminder, request.minutes)
        return true
    }

    /** Aufgabe oder Routine in der Übersicht der Uhr abgehakt bzw. Haken entfernt ([ItemAction] als JSON). */
    suspend fun itemAction(c: AppContainer, bytes: ByteArray): Boolean {
        val action = runCatching { WatchJson.decodeFromString<ItemAction>(bytes.decodeToString()) }.getOrNull()
            ?: return false
        return when (action.type) {
            ItemAction.TYPE_TASK -> taskAction(c, action)
            ItemAction.TYPE_ROUTINE -> routineAction(c, action)
            else -> false
        }
    }

    private suspend fun taskAction(c: AppContainer, action: ItemAction): Boolean {
        val task = c.tasks.byId(action.id) ?: return false
        // Schon im gewünschten Zustand (z. B. doppelt gesendet): nichts ändern, aber „ausgeführt“ melden.
        if (task.done != action.done) c.tasks.setDone(task, action.done)
        return true
    }

    private suspend fun routineAction(c: AppContainer, action: ItemAction): Boolean {
        val epochDay = action.epochDay ?: return false
        val minutes = action.minutes?.takeIf { it in 0 until MINUTES_PER_DAY } ?: return false
        val date = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return false
        val routine = c.db.routineDao().byId(action.id) ?: return false
        // Checkups werden am Handy ausgefüllt, nicht abgehakt.
        if (routine.kind == RoutineKind.CHECKUP) return false
        val entry = c.routines.log(date, date).find { it.routineId == routine.id && it.minutes == minutes }
        if (action.done) {
            // Die Uhrzeit gibt es bei dieser Routine nicht (mehr): Die Übersicht der Uhr ist veraltet.
            if (minutes !in RoutineLogic.times(routine)) return false
            // Ein vorhandener Haken (z. B. vom Medikamentenspender) bleibt stehen; „Übersprungen“ wird zu „erledigt“.
            if (entry == null || entry.skipped) c.routines.setDone(routine.id, date, minutes, true)
            // Eine noch angezeigte Erinnerung dazu ist damit erledigt – am Handy und auf der Uhr.
            c.notifier.cancelKey(RoutineLogic.occurrenceKey(routine.id, epochDay, minutes))
        } else if (entry != null) {
            c.routines.setDone(routine.id, date, minutes, false)
        }
        return true
    }
}

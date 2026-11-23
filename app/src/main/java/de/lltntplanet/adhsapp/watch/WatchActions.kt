package de.lltntplanet.adhsapp.watch

import android.os.SystemClock
import de.lltntplanet.adhsapp.AppContainer
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.reminders.ReminderActionHandler
import de.lltntplanet.adhsapp.reminders.ReminderPayload
import de.lltntplanet.adhsapp.reminders.ReminderPlanner
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

    /**
     * Nur so lange nach Beginn einer Aktion nimmt das Handy die Erinnerung noch einmal von der Uhr. Hing die Aktion
     * länger (das Handy schlief zwischendurch), kann dort schon die geschlummerte Wiederholung stehen – sie hat
     * denselben Schlüssel und darf nicht verschwinden. Geschlummert wird mindestens eine Minute.
     */
    const val LATE_CANCEL_MILLIS = 20_000L

    /** Darf die Erinnerung nach der Aktion noch einmal von der Uhr genommen werden? [elapsedMillis] = Dauer der Aktion. */
    fun mayCancelAfterAction(elapsedMillis: Long): Boolean = elapsedMillis < LATE_CANCEL_MILLIS

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
        val startedAt = SystemClock.elapsedRealtime()
        c.reminderActions.perform(action, reminder, request.minutes)
        // Hat die Uhr inzwischen aufgegeben (keine Antwort in ihrer Wartezeit), steht die Erinnerung dort wieder.
        // Jetzt ist sie ausgeführt: noch einmal entfernen, damit sie nicht ein zweites Mal bestätigt wird.
        if (mayCancelAfterAction(SystemClock.elapsedRealtime() - startedAt)) {
            c.watch.cancel(listOf(reminder.originKey ?: reminder.key))
        }
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

    /**
     * Zeigt die Uhr noch einen früheren Termin einer wiederkehrenden Aufgabe? Dann ist die Aufgabe am Handy schon
     * weitergerückt, und der Haken darf sie nicht noch einmal weiterschalten. Die Uhr schickt dafür den
     * Fälligkeitstag mit, den sie anzeigt; ohne Tag (ältere Uhr-App, Aufgabe ohne Datum) wird nicht geprüft.
     */
    fun isStaleTaskAction(task: TaskEntity, action: ItemAction): Boolean =
        task.repeat != Repeat.NONE && action.epochDay != null && action.epochDay != task.dueEpochDay

    private suspend fun taskAction(c: AppContainer, action: ItemAction): Boolean {
        val task = c.tasks.byId(action.id) ?: return false
        // Schon weitergerückt (z. B. am Handy abgehakt oder doppelt gesendet): nichts ändern, aber „ausgeführt“ melden.
        if (isStaleTaskAction(task, action)) return true
        // Schon im gewünschten Zustand (z. B. doppelt gesendet): nichts ändern, aber „ausgeführt“ melden.
        if (task.done != action.done) c.tasks.setDone(task, action.done)
        // Eine noch angezeigte Erinnerung dazu ist damit erledigt – am Handy und auf der Uhr. Der Schlüssel gehört
        // zum Termin vor dem Weiterrücken; ohne Datum gibt es keine Erinnerung.
        if (action.done && task.dueEpochDay != null) c.notifier.cancelKey(ReminderPlanner.taskKey(task))
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

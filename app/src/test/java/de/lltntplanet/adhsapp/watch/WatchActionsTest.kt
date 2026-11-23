package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.reminders.ReminderActionHandler
import de.lltntplanet.adhsapp.reminders.ReminderPlanner
import de.lltntplanet.adhsapp.shared.ItemAction
import de.lltntplanet.adhsapp.shared.ReminderActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Was das Handy mit einem Knopfdruck auf der Uhr macht – soweit es reine Logik ist. */
class WatchActionsTest {

    private val today = LocalDate.of(2026, 10, 24)
    private val day = today.toEpochDay()
    private val daily = TaskEntity(id = 5, title = "Tabletten bestellen", dueEpochDay = day, repeat = Repeat.DAILY)

    /** Haken auf der Uhr; [shownDay] ist der Fälligkeitstag, den die Uhr anzeigt. */
    private fun tick(shownDay: Long?, done: Boolean = true) =
        ItemAction(type = ItemAction.TYPE_TASK, id = daily.id, done = done, epochDay = shownDay)

    @Test
    fun buttonsOfTheWatchMapToTheSameActionsAsOnThePhone() {
        assertEquals(ReminderActionHandler.Action.SNOOZE, WatchActions.actionFor(ReminderActions.SNOOZE))
        assertEquals(ReminderActionHandler.Action.DONE, WatchActions.actionFor(ReminderActions.DONE))
        assertEquals(ReminderActionHandler.Action.MUTE, WatchActions.actionFor(ReminderActions.MUTE))
        assertEquals(ReminderActionHandler.Action.DISMISS, WatchActions.actionFor(ReminderActions.DISMISS))
        assertNull(WatchActions.actionFor("unbekannt"))
    }

    @Test
    fun reminderIsOnlyTakenOffTheWatchShortlyAfterTheAction() {
        assertTrue(WatchActions.mayCancelAfterAction(0))
        assertTrue(WatchActions.mayCancelAfterAction(5_000))
        assertTrue(WatchActions.mayCancelAfterAction(WatchActions.LATE_CANCEL_MILLIS - 1))
        // Hing die Aktion so lange, kann auf der Uhr schon die geschlummerte Wiederholung stehen – sie bleibt.
        assertFalse(WatchActions.mayCancelAfterAction(WatchActions.LATE_CANCEL_MILLIS))
        assertFalse(WatchActions.mayCancelAfterAction(10 * 60_000L))
        // Geschlummert wird mindestens eine Minute; fällig wird die Wiederholung frühestens 30 s davor.
        assertTrue(WatchActions.LATE_CANCEL_MILLIS < 60_000L - ReminderPlanner.EARLY_MILLIS)
    }

    @Test
    fun tickForTheCurrentDueDayCounts() {
        assertFalse(WatchActions.isStaleTaskAction(daily, tick(day)))
    }

    @Test
    fun tickForAnEarlierDueDayOfARepeatingTaskIsStale() {
        // Am Handy schon abgehakt: Die Aufgabe steht auf morgen, die Uhr zeigt noch heute.
        val advanced = daily.copy(dueEpochDay = TaskLogic.nextDue(today, Repeat.DAILY, today).toEpochDay())
        assertEquals(day + 1, advanced.dueEpochDay)
        assertTrue(WatchActions.isStaleTaskAction(advanced, tick(day)))
        // Auch der zurückgenommene Haken ändert dann nichts mehr.
        assertTrue(WatchActions.isStaleTaskAction(advanced, tick(day, done = false)))
        // Zeigt die Uhr schon den neuen Termin, zählt der Haken wieder.
        assertFalse(WatchActions.isStaleTaskAction(advanced, tick(day + 1)))
    }

    @Test
    fun withoutDayFromTheWatchNothingIsChecked() {
        // Ältere Uhr-App oder Aufgabe ohne Datum: kein Tag dabei – dann gilt der Haken wie bisher.
        assertFalse(WatchActions.isStaleTaskAction(daily.copy(dueEpochDay = day + 1), tick(null)))
        assertFalse(WatchActions.isStaleTaskAction(daily.copy(dueEpochDay = null), tick(null)))
    }

    @Test
    fun dateRemovedAtThePhoneMakesTheTickStale() {
        assertTrue(WatchActions.isStaleTaskAction(daily.copy(dueEpochDay = null), tick(day)))
    }

    @Test
    fun plainTasksAreNeverStale() {
        // Nicht wiederkehrend: Doppeltes Abhaken ist harmlos – auch wenn der Tag inzwischen verschoben wurde.
        val once = daily.copy(repeat = Repeat.NONE, dueEpochDay = day + 1)
        assertFalse(WatchActions.isStaleTaskAction(once, tick(day)))
        assertFalse(WatchActions.isStaleTaskAction(once, tick(null)))
    }
}

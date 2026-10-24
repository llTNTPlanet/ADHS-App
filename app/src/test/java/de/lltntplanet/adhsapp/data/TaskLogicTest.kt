package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.tasks.TaskLogic
import de.lltntplanet.adhsapp.data.tasks.TaskSection
import de.lltntplanet.adhsapp.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskLogicTest {
    private val today = LocalDate.of(2026, 10, 23) // Freitag

    @Test
    fun dailyRepeatSkipsMissedDays() {
        assertEquals(today.plusDays(1), TaskLogic.nextDue(today, Repeat.DAILY, today))
        assertEquals(today.plusDays(1), TaskLogic.nextDue(today.minusDays(3), Repeat.DAILY, today))
        // Schon für morgen erledigt → übermorgen
        assertEquals(today.plusDays(2), TaskLogic.nextDue(today.plusDays(1), Repeat.DAILY, today))
    }

    @Test
    fun weekdaysSkipWeekend() {
        assertEquals(LocalDate.of(2026, 10, 26), TaskLogic.nextDue(today, Repeat.WEEKDAYS, today))
    }

    @Test
    fun weeklyAndMonthly() {
        assertEquals(today.plusWeeks(1), TaskLogic.nextDue(today, Repeat.WEEKLY, today))
        assertEquals(LocalDate.of(2026, 11, 30), TaskLogic.nextDue(LocalDate.of(2026, 10, 31), Repeat.MONTHLY, today))
    }

    @Test
    fun reminderTime() {
        val withTime = TaskEntity(title = "Tabletten", dueEpochDay = today.toEpochDay(), dueMinutes = 8 * 60)
        assertEquals(millis("2026-10-23T08:00"), TaskLogic.reminderAt(withTime, 9 * 60, Berlin))
        val dateOnly = withTime.copy(dueMinutes = null)
        assertEquals(millis("2026-10-23T09:00"), TaskLogic.reminderAt(dateOnly, 9 * 60, Berlin))
        assertNull(TaskLogic.reminderAt(withTime.copy(done = true), 9 * 60, Berlin))
        assertNull(TaskLogic.reminderAt(withTime.copy(remind = false), 9 * 60, Berlin))
        assertNull(TaskLogic.reminderAt(withTime.copy(dueEpochDay = null), 9 * 60, Berlin))
    }

    @Test
    fun sections() {
        fun t(day: LocalDate?, done: Boolean = false) = TaskEntity(title = "x", dueEpochDay = day?.toEpochDay(), done = done)
        assertEquals(TaskSection.OVERDUE, TaskLogic.section(t(today.minusDays(1)), today))
        assertEquals(TaskSection.TODAY, TaskLogic.section(t(today), today))
        assertEquals(TaskSection.TOMORROW, TaskLogic.section(t(today.plusDays(1)), today))
        assertEquals(TaskSection.UPCOMING, TaskLogic.section(t(today.plusDays(5)), today))
        assertEquals(TaskSection.NO_DATE, TaskLogic.section(t(null), today))
        assertEquals(TaskSection.DONE, TaskLogic.section(t(today, done = true), today))
    }
}

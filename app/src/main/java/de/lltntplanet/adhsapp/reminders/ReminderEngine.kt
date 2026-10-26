package de.lltntplanet.adhsapp.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import de.lltntplanet.adhsapp.MainActivity
import de.lltntplanet.adhsapp.data.birthdays.BirthdayRepository
import de.lltntplanet.adhsapp.data.calendar.CalendarRepository
import de.lltntplanet.adhsapp.data.db.FiredReminderEntity
import de.lltntplanet.adhsapp.data.db.ReminderDao
import de.lltntplanet.adhsapp.data.db.SnoozeEntity
import de.lltntplanet.adhsapp.data.db.TaskDao
import de.lltntplanet.adhsapp.data.google.GoogleJson
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.util.Dates
import de.lltntplanet.adhsapp.util.Permissions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.time.ZoneId

/**
 * Sammelt alle Erinnerungen und stellt immer genau einen exakten Alarm auf die nächste.
 * Löst der Alarm aus, werden alle fälligen Erinnerungen angezeigt und der nächste Alarm gestellt.
 */
class ReminderEngine(
    private val context: Context,
    private val calendar: CalendarRepository,
    private val birthdays: BirthdayRepository,
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    /** Alle Erinnerungen mit Auslösezeit zwischen [from] und [to]. */
    suspend fun collect(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<Reminder> {
        val s = settings.current()
        val result = mutableListOf<Reminder>()

        val allBirthdays = runCatching { birthdays.all() }.getOrElse { emptyList() }

        // Termine: Erinnerungen bis zu einer Woche vor Beginn berücksichtigen.
        if (calendar.hasReadPermission()) {
            val calendars = calendar.calendars()
            val excluded = s.excludedReminderCalendars.toMutableSet()
            if (s.birthdayReminders && (s.birthdaysFromCalendar || birthdays.hasContactsPermission())) {
                // Geburtstage kommen schon aus Kontakten bzw. dem Geburtstagskalender – nicht doppelt erinnern.
                excluded += calendars.filter { it.isBirthdayCalendar }.map { it.id }
            }
            val instances = calendar.instancesBetween(from - DAY, to + ReminderPlanner.MAX_OFFSET_MINUTES * 60_000L + DAY)
            val calReminders = if (s.useCalendarReminders) {
                calendar.eventReminders(instances.filter { !it.allDay }.map { it.eventId })
            } else {
                emptyMap()
            }
            result += ReminderPlanner.planEvents(instances, calReminders, s, excluded, allBirthdays, zone)
        }

        result += ReminderPlanner.planBirthdays(
            allBirthdays, s, Dates.toLocalDate(from, zone), Dates.toLocalDate(to, zone), zone,
        )
        result += ReminderPlanner.planTasks(taskDao.open(), s, zone)
        result += reminderDao.snoozes().mapNotNull { snooze ->
            runCatching { GoogleJson.decodeFromString<Reminder>(snooze.payload) }.getOrNull()
                ?.let { it.copy(key = "snooze:${snooze.id}", triggerAt = snooze.triggerAt, snoozed = true, originKey = it.originKey ?: it.key) }
        }
        return result.filter { it.snoozed || it.triggerAt in from..to }
    }

    /** Vom Alarm aufgerufen: fällige Erinnerungen ermitteln, als ausgelöst merken, nächsten Alarm stellen. */
    suspend fun fireDue(): List<Reminder> = mutex.withLock {
        val now = System.currentTimeMillis()
        val fired = firedKeys(now)
        val due = collect(now - LOOKBACK, now + LOOKAHEAD)
            .filter { !isBlocked(it, fired) && ReminderPlanner.isDue(it, now) }
        if (due.isNotEmpty()) {
            reminderDao.markFired(due.map { FiredReminderEntity(it.key, now) })
            due.filter { it.snoozed }.forEach { r -> r.key.removePrefix("snooze:").toLongOrNull()?.let { reminderDao.deleteSnooze(it) } }
        }
        scheduleNextLocked(now, fired + due.map { it.key })
        ReminderPlanner.collapse(due)
    }

    suspend fun reschedule() = mutex.withLock {
        val now = System.currentTimeMillis()
        scheduleNextLocked(now, firedKeys(now))
    }

    /** Die nächste anstehende Erinnerung (für die Anzeige in den Einstellungen). */
    suspend fun next(): Reminder? {
        val now = System.currentTimeMillis()
        val fired = firedKeys(now)
        return collect(now, now + LOOKAHEAD).filter { !isBlocked(it, fired) && it.triggerAt >= now }.minByOrNull { it.triggerAt }
    }

    suspend fun snooze(reminder: Reminder, minutes: Int) {
        val payload = GoogleJson.encodeToString(reminder.copy(snoozed = false, key = reminder.originKey ?: reminder.key, originKey = null))
        reminderDao.insertSnooze(SnoozeEntity(triggerAt = System.currentTimeMillis() + minutes * 60_000L, payload = payload))
        reschedule()
    }

    /**
     * „Keine weiteren Erinnerungen“: Alle noch ausstehenden Vorwarnungen und Schlummer-Wiederholungen dieses
     * Termin-Vorkommens (dieser Tag) werden unterdrückt. Andere Tage desselben Termins bleiben unberührt.
     */
    suspend fun mute(reminder: Reminder) {
        val muteKey = reminder.muteKey
        reminderDao.markFired(listOf(FiredReminderEntity(muteMarker(muteKey), System.currentTimeMillis())))
        reminderDao.snoozes().forEach { snooze ->
            val original = runCatching { GoogleJson.decodeFromString<Reminder>(snooze.payload) }.getOrNull() ?: return@forEach
            if (original.muteKey == muteKey) reminderDao.deleteSnooze(snooze.id)
        }
        reschedule()
    }

    /** Schon ausgelöst oder das ganze Vorkommen stummgeschaltet? */
    private fun isBlocked(r: Reminder, fired: Set<String>): Boolean = r.key in fired || muteMarker(r.muteKey) in fired

    private fun muteMarker(muteKey: String) = "mute:$muteKey"

    /** Test-Erinnerung in [seconds] Sekunden – prüft den kompletten Weg bis zum Vollbild. */
    suspend fun scheduleTest(seconds: Int = 10) {
        val now = System.currentTimeMillis()
        val test = Reminder(
            key = "test:$now",
            kind = ReminderKind.EVENT,
            triggerAt = now + seconds * 1000L,
            title = "Test-Erinnerung",
            detail = "So sehen Erinnerungen aus. Sperre ruhig das Handy zum Ausprobieren.",
            targetTime = now + 15 * 60_000L,
            targetEnd = now + 45 * 60_000L,
        )
        reminderDao.insertSnooze(SnoozeEntity(triggerAt = test.triggerAt, payload = GoogleJson.encodeToString(test)))
        reschedule()
    }

    private suspend fun firedKeys(now: Long): Set<String> {
        reminderDao.purgeFired(now - 30L * DAY)
        return reminderDao.firedKeysSince(now - 8L * DAY).toSet()
    }

    private suspend fun scheduleNextLocked(now: Long, fired: Set<String>) {
        val pending = runCatching { collect(now - LOOKBACK, now + LOOKAHEAD) }
            .onFailure { Log.e(TAG, "Erinnerungen konnten nicht gesammelt werden", it) }
            .getOrElse { emptyList() }
            .filter { !isBlocked(it, fired) && (it.triggerAt > now || ReminderPlanner.isDue(it, now)) }
        val next = pending.minOfOrNull { it.triggerAt }
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmIntent(context)
        if (next == null) {
            alarmManager.cancel(operation)
            Log.d(TAG, "Keine anstehenden Erinnerungen")
            return
        }
        val at = maxOf(next, now + 1_000L)
        val s = settings.current()
        try {
            when {
                Permissions.exactAlarmsAllowed(context) && s.alarmClockMode -> {
                    val show = PendingIntent.getActivity(
                        context, 1, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
                }
                Permissions.exactAlarmsAllowed(context) ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                else -> alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
            Log.d(TAG, "Nächster Alarm: ${java.time.Instant.ofEpochMilli(at)} (${pending.size} anstehend)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Exakter Alarm nicht erlaubt – ungenauer Alarm", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    companion object {
        private const val TAG = "ReminderEngine"
        private const val DAY = 24L * 60 * 60 * 1000
        /** Wie weit zurück verpasste Erinnerungen noch berücksichtigt werden. */
        private const val LOOKBACK = 6L * 60 * 60 * 1000
        /** Wie weit im Voraus geplant wird; der Wartungs-Job plant regelmäßig nach. */
        private const val LOOKAHEAD = 3L * DAY

        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

package de.lltntplanet.adhsapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class CalendarViewMode(val label: String) {
    SCHEDULE("Terminübersicht"),
    DAY("Tag"),
    THREE_DAY("3 Tage"),
    WEEK("Woche"),
    MONTH("Monat"),
}

data class AppSettings(
    /** Zusätzliche Vorwarnungen vor Terminen in Minuten (0 = zu Beginn). */
    val eventOffsets: Set<Int> = setOf(15, 0),
    /** Die in Google Kalender eingestellten Erinnerungen eines Termins übernehmen. */
    val useCalendarReminders: Boolean = true,
    val allDayReminders: Boolean = true,
    val allDayReminderMinutes: Int = 8 * 60,
    val birthdayReminders: Boolean = true,
    val birthdayReminderMinutes: Int = 9 * 60,
    /** 0 = am Tag selbst, 1 = einen Tag vorher usw. */
    val birthdayDaysBefore: Set<Int> = setOf(0, 1),
    /** Geburtstage automatisch aus dem Google Kalender übernehmen. */
    val birthdaysFromCalendar: Boolean = true,
    /** Uhrzeit für Aufgaben, die nur ein Datum haben. */
    val taskDefaultMinutes: Int = 9 * 60,
    /** Kalender, für die keine Erinnerungen erzeugt werden. */
    val excludedReminderCalendars: Set<Long> = emptySet(),
    /** Kalender, die in der Kalenderansicht ausgeblendet sind. */
    val hiddenCalendars: Set<Long> = emptySet(),
    val defaultCalendarId: Long? = null,
    val calendarView: CalendarViewMode = CalendarViewMode.SCHEDULE,
    /** Vollbild auch bei entsperrtem Handy (benötigt "Über anderen Apps anzeigen"). */
    val launchOverApps: Boolean = true,
    val alarmSound: Boolean = true,
    val vibrate: Boolean = true,
    val ringSeconds: Int = 60,
    /** Wecker-Modus: setAlarmClock() – maximal zuverlässig, zeigt aber ein Wecker-Symbol. */
    val alarmClockMode: Boolean = false,
    val stepGoal: Int = 8000,
    val healthConnected: Boolean = false,
    val tasksConnected: Boolean = false,
    val googleAccount: String? = null,
    val saveNewTasksToGoogle: Boolean = true,
    val lastTasksSync: Long = 0L,
    /** Google-Bereiche, die das Cloud-Projekt nicht freigibt – werden bei der Anmeldung weggelassen. */
    val blockedGoogleScopes: Set<String> = emptySet(),
    val onboardingDone: Boolean = false,
    /** Neue Versionen von GitHub automatisch herunterladen und installieren. */
    val autoInstallUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    /** Version, deren Hinweis mit "Später" weggeklickt wurde, und wann. */
    val dismissedUpdateCode: Int = 0,
    val dismissedUpdateAt: Long = 0L,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val eventOffsets = stringSetPreferencesKey("event_offsets")
        val useCalendarReminders = booleanPreferencesKey("use_calendar_reminders")
        val allDayReminders = booleanPreferencesKey("all_day_reminders")
        val allDayReminderMinutes = intPreferencesKey("all_day_reminder_minutes")
        val birthdayReminders = booleanPreferencesKey("birthday_reminders")
        val birthdayReminderMinutes = intPreferencesKey("birthday_reminder_minutes")
        val birthdayDaysBefore = stringSetPreferencesKey("birthday_days_before")
        val birthdaysFromCalendar = booleanPreferencesKey("birthdays_from_calendar")
        val taskDefaultMinutes = intPreferencesKey("task_default_minutes")
        val excludedReminderCalendars = stringSetPreferencesKey("excluded_reminder_calendars")
        val hiddenCalendars = stringSetPreferencesKey("hidden_calendars")
        val defaultCalendarId = longPreferencesKey("default_calendar_id")
        val calendarView = stringPreferencesKey("calendar_view")
        val launchOverApps = booleanPreferencesKey("launch_over_apps")
        val alarmSound = booleanPreferencesKey("alarm_sound")
        val vibrate = booleanPreferencesKey("vibrate")
        val ringSeconds = intPreferencesKey("ring_seconds")
        val alarmClockMode = booleanPreferencesKey("alarm_clock_mode")
        val stepGoal = intPreferencesKey("step_goal")
        val healthConnected = booleanPreferencesKey("health_connected")
        val tasksConnected = booleanPreferencesKey("tasks_connected")
        val googleAccount = stringPreferencesKey("google_account")
        val saveNewTasksToGoogle = booleanPreferencesKey("save_new_tasks_to_google")
        val lastTasksSync = longPreferencesKey("last_tasks_sync")
        val blockedGoogleScopes = stringSetPreferencesKey("blocked_google_scopes")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val autoInstallUpdates = booleanPreferencesKey("auto_install_updates")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val dismissedUpdateCode = intPreferencesKey("dismissed_update_code")
        val dismissedUpdateAt = longPreferencesKey("dismissed_update_at")
        val healthCache = stringPreferencesKey("health_cache")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            eventOffsets = this[Keys.eventOffsets]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.eventOffsets,
            useCalendarReminders = this[Keys.useCalendarReminders] ?: d.useCalendarReminders,
            allDayReminders = this[Keys.allDayReminders] ?: d.allDayReminders,
            allDayReminderMinutes = this[Keys.allDayReminderMinutes] ?: d.allDayReminderMinutes,
            birthdayReminders = this[Keys.birthdayReminders] ?: d.birthdayReminders,
            birthdayReminderMinutes = this[Keys.birthdayReminderMinutes] ?: d.birthdayReminderMinutes,
            birthdayDaysBefore = this[Keys.birthdayDaysBefore]?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: d.birthdayDaysBefore,
            birthdaysFromCalendar = this[Keys.birthdaysFromCalendar] ?: d.birthdaysFromCalendar,
            taskDefaultMinutes = this[Keys.taskDefaultMinutes] ?: d.taskDefaultMinutes,
            excludedReminderCalendars = this[Keys.excludedReminderCalendars]?.mapNotNull { it.toLongOrNull() }
                ?.toSet() ?: d.excludedReminderCalendars,
            hiddenCalendars = this[Keys.hiddenCalendars]?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: d.hiddenCalendars,
            defaultCalendarId = this[Keys.defaultCalendarId],
            calendarView = this[Keys.calendarView]?.let { v -> CalendarViewMode.entries.find { it.name == v } }
                ?: d.calendarView,
            launchOverApps = this[Keys.launchOverApps] ?: d.launchOverApps,
            alarmSound = this[Keys.alarmSound] ?: d.alarmSound,
            vibrate = this[Keys.vibrate] ?: d.vibrate,
            ringSeconds = this[Keys.ringSeconds] ?: d.ringSeconds,
            alarmClockMode = this[Keys.alarmClockMode] ?: d.alarmClockMode,
            stepGoal = this[Keys.stepGoal] ?: d.stepGoal,
            healthConnected = this[Keys.healthConnected] ?: d.healthConnected,
            tasksConnected = this[Keys.tasksConnected] ?: d.tasksConnected,
            googleAccount = this[Keys.googleAccount],
            saveNewTasksToGoogle = this[Keys.saveNewTasksToGoogle] ?: d.saveNewTasksToGoogle,
            lastTasksSync = this[Keys.lastTasksSync] ?: d.lastTasksSync,
            blockedGoogleScopes = this[Keys.blockedGoogleScopes] ?: d.blockedGoogleScopes,
            onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
            autoInstallUpdates = this[Keys.autoInstallUpdates] ?: d.autoInstallUpdates,
            lastUpdateCheck = this[Keys.lastUpdateCheck] ?: d.lastUpdateCheck,
            dismissedUpdateCode = this[Keys.dismissedUpdateCode] ?: d.dismissedUpdateCode,
            dismissedUpdateAt = this[Keys.dismissedUpdateAt] ?: d.dismissedUpdateAt,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val new = transform(prefs.toSettings())
            prefs.write(new)
        }
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.eventOffsets] = s.eventOffsets.map { it.toString() }.toSet()
        this[Keys.useCalendarReminders] = s.useCalendarReminders
        this[Keys.allDayReminders] = s.allDayReminders
        this[Keys.allDayReminderMinutes] = s.allDayReminderMinutes
        this[Keys.birthdayReminders] = s.birthdayReminders
        this[Keys.birthdayReminderMinutes] = s.birthdayReminderMinutes
        this[Keys.birthdayDaysBefore] = s.birthdayDaysBefore.map { it.toString() }.toSet()
        this[Keys.birthdaysFromCalendar] = s.birthdaysFromCalendar
        this[Keys.taskDefaultMinutes] = s.taskDefaultMinutes
        this[Keys.excludedReminderCalendars] = s.excludedReminderCalendars.map { it.toString() }.toSet()
        this[Keys.hiddenCalendars] = s.hiddenCalendars.map { it.toString() }.toSet()
        if (s.defaultCalendarId != null) this[Keys.defaultCalendarId] = s.defaultCalendarId
        else remove(Keys.defaultCalendarId)
        this[Keys.calendarView] = s.calendarView.name
        this[Keys.launchOverApps] = s.launchOverApps
        this[Keys.alarmSound] = s.alarmSound
        this[Keys.vibrate] = s.vibrate
        this[Keys.ringSeconds] = s.ringSeconds
        this[Keys.alarmClockMode] = s.alarmClockMode
        this[Keys.stepGoal] = s.stepGoal
        this[Keys.healthConnected] = s.healthConnected
        this[Keys.tasksConnected] = s.tasksConnected
        if (s.googleAccount != null) this[Keys.googleAccount] = s.googleAccount else remove(Keys.googleAccount)
        this[Keys.saveNewTasksToGoogle] = s.saveNewTasksToGoogle
        this[Keys.lastTasksSync] = s.lastTasksSync
        this[Keys.blockedGoogleScopes] = s.blockedGoogleScopes
        this[Keys.onboardingDone] = s.onboardingDone
        this[Keys.autoInstallUpdates] = s.autoInstallUpdates
        this[Keys.lastUpdateCheck] = s.lastUpdateCheck
        this[Keys.dismissedUpdateCode] = s.dismissedUpdateCode
        this[Keys.dismissedUpdateAt] = s.dismissedUpdateAt
    }

    /** Zwischenspeicher der zuletzt geladenen Gesundheitsdaten (JSON). */
    val healthCache: Flow<String?> = context.dataStore.data.map { it[Keys.healthCache] }

    suspend fun saveHealthCache(json: String?) {
        context.dataStore.edit { if (json == null) it.remove(Keys.healthCache) else it[Keys.healthCache] = json }
    }
}

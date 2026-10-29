package de.lltntplanet.adhsapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionProvider
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

enum class Sex(val label: String) { MALE("Männlich"), FEMALE("Weiblich") }

data class AppSettings(
    /** Zusätzliche Vorwarnungen vor Terminen in Minuten (0 = zu Beginn). */
    val eventOffsets: Set<Int> = setOf(30, 15),
    /** Termine mit genau diesem Titel (Groß-/Kleinschreibung egal) lösen keine Erinnerung aus. */
    val ignoredEventTitles: Set<String> = setOf("Frühstück", "Mittagessen"),
    /** Die in Google Kalender eingestellten Erinnerungen eines Termins übernehmen. */
    val useCalendarReminders: Boolean = true,
    /** Nach Essens-Terminen per Benachrichtigung ans Eintragen der Mahlzeit erinnern (mit „Per Foto“/„Per Chat“). */
    val mealReminders: Boolean = true,
    /** Minuten nach dem Ende des Essens-Termins. */
    val mealReminderMinutesAfter: Int = 0,
    /** Wörter, an denen ein Essens-Termin erkannt wird (im Titel enthalten, Groß-/Kleinschreibung egal). */
    val mealEventTitles: Set<String> = setOf("Frühstück", "Mittagessen", "Abendessen", "Essen"),
    /**
     * Vorwarnungen vor Essens-Terminen in Minuten (0 = zu Beginn). Gelten statt [eventOffsets] und ohne die
     * Erinnerungen aus Google Kalender, damit vor dem Essen genau zu diesen Zeiten erinnert wird. Leer = keine.
     */
    val mealOffsets: Set<Int> = setOf(30, 10),
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
    /** Ernährungsprofil für den Grundumsatz (Mifflin-St-Jeor). */
    val sex: Sex? = null,
    val birthYear: Int? = null,
    val heightCm: Int? = null,
    val weightKg: Double? = null,
    /** Kalorien, die täglich vom Budget abgezogen (negativ) oder draufgelegt werden (z. B. -300 zum Abnehmen). */
    val calorieAdjustment: Int = 0,
    /** Dienst für die Foto-Erkennung von Mahlzeiten. */
    val foodVisionProvider: FoodVisionProvider = FoodVisionProvider.GEMINI,
    /** Mahlzeiten zusätzlich in Health Connect schreiben (Google Health liest sie von dort). */
    val healthConnectExport: Boolean = false,
    val healthConnected: Boolean = false,
    val tasksConnected: Boolean = false,
    val googleAccount: String? = null,
    val saveNewTasksToGoogle: Boolean = true,
    val lastTasksSync: Long = 0L,
    /** Google-Bereiche, die das Cloud-Projekt nicht freigibt – werden bei der Anmeldung weggelassen. */
    val blockedGoogleScopes: Set<String> = emptySet(),
    val onboardingDone: Boolean = false,
    /** Seiten (Routen) in der unteren Leiste; leer = alle Seiten. Nicht gewählte Seiten stehen unter „Weitere“. */
    val barTabs: Set<String> = emptySet(),
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
        val ignoredEventTitles = stringSetPreferencesKey("ignored_event_titles")
        /** Stand der Einstellungen; fehlt er, stammen die Werte aus einer Version mit anderen Vorgaben. */
        val settingsVersion = intPreferencesKey("settings_version")
        val useCalendarReminders = booleanPreferencesKey("use_calendar_reminders")
        val mealReminders = booleanPreferencesKey("meal_reminders")
        val mealReminderMinutesAfter = intPreferencesKey("meal_reminder_minutes_after")
        val mealOffsets = stringSetPreferencesKey("meal_offsets")
        val mealEventTitles = stringSetPreferencesKey("meal_event_titles")
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
        val sex = stringPreferencesKey("sex")
        val birthYear = intPreferencesKey("birth_year")
        val heightCm = intPreferencesKey("height_cm")
        val weightKg = doublePreferencesKey("weight_kg")
        val calorieAdjustment = intPreferencesKey("calorie_adjustment")
        val foodVisionProvider = stringPreferencesKey("food_vision_provider")
        val healthConnectExport = booleanPreferencesKey("health_connect_export")
        val healthConnected = booleanPreferencesKey("health_connected")
        val tasksConnected = booleanPreferencesKey("tasks_connected")
        val googleAccount = stringPreferencesKey("google_account")
        val saveNewTasksToGoogle = booleanPreferencesKey("save_new_tasks_to_google")
        val lastTasksSync = longPreferencesKey("last_tasks_sync")
        val blockedGoogleScopes = stringSetPreferencesKey("blocked_google_scopes")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val barTabs = stringSetPreferencesKey("bar_tabs")
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
        val storedOffsets = this[Keys.eventOffsets]?.mapNotNull { it.toIntOrNull() }?.toSet()
        return AppSettings(
            // Vor Version 1 war „15 Min. + zu Beginn“ die Vorgabe; wer sie nie geändert hat, bekommt die neue Vorgabe.
            eventOffsets = storedOffsets?.takeUnless { this[Keys.settingsVersion] == null && it == LEGACY_EVENT_OFFSETS } ?: d.eventOffsets,
            ignoredEventTitles = this[Keys.ignoredEventTitles] ?: d.ignoredEventTitles,
            useCalendarReminders = this[Keys.useCalendarReminders] ?: d.useCalendarReminders,
            mealReminders = this[Keys.mealReminders] ?: d.mealReminders,
            mealReminderMinutesAfter = this[Keys.mealReminderMinutesAfter] ?: d.mealReminderMinutesAfter,
            mealEventTitles = this[Keys.mealEventTitles] ?: d.mealEventTitles,
            mealOffsets = this[Keys.mealOffsets]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.mealOffsets,
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
            sex = this[Keys.sex]?.let { v -> Sex.entries.find { it.name == v } },
            birthYear = this[Keys.birthYear],
            heightCm = this[Keys.heightCm],
            weightKg = this[Keys.weightKg],
            calorieAdjustment = this[Keys.calorieAdjustment] ?: d.calorieAdjustment,
            foodVisionProvider = this[Keys.foodVisionProvider]?.let { v -> FoodVisionProvider.entries.find { it.name == v } }
                ?: d.foodVisionProvider,
            healthConnectExport = this[Keys.healthConnectExport] ?: d.healthConnectExport,
            healthConnected = this[Keys.healthConnected] ?: d.healthConnected,
            tasksConnected = this[Keys.tasksConnected] ?: d.tasksConnected,
            googleAccount = this[Keys.googleAccount],
            saveNewTasksToGoogle = this[Keys.saveNewTasksToGoogle] ?: d.saveNewTasksToGoogle,
            lastTasksSync = this[Keys.lastTasksSync] ?: d.lastTasksSync,
            blockedGoogleScopes = this[Keys.blockedGoogleScopes] ?: d.blockedGoogleScopes,
            onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
            barTabs = this[Keys.barTabs] ?: d.barTabs,
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
        this[Keys.settingsVersion] = SETTINGS_VERSION
        this[Keys.eventOffsets] = s.eventOffsets.map { it.toString() }.toSet()
        this[Keys.ignoredEventTitles] = s.ignoredEventTitles
        this[Keys.useCalendarReminders] = s.useCalendarReminders
        this[Keys.mealReminders] = s.mealReminders
        this[Keys.mealReminderMinutesAfter] = s.mealReminderMinutesAfter
        this[Keys.mealEventTitles] = s.mealEventTitles
        this[Keys.mealOffsets] = s.mealOffsets.map { it.toString() }.toSet()
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
        if (s.sex != null) this[Keys.sex] = s.sex.name else remove(Keys.sex)
        if (s.birthYear != null) this[Keys.birthYear] = s.birthYear else remove(Keys.birthYear)
        if (s.heightCm != null) this[Keys.heightCm] = s.heightCm else remove(Keys.heightCm)
        if (s.weightKg != null) this[Keys.weightKg] = s.weightKg else remove(Keys.weightKg)
        this[Keys.calorieAdjustment] = s.calorieAdjustment
        this[Keys.foodVisionProvider] = s.foodVisionProvider.name
        this[Keys.healthConnectExport] = s.healthConnectExport
        this[Keys.healthConnected] = s.healthConnected
        this[Keys.tasksConnected] = s.tasksConnected
        if (s.googleAccount != null) this[Keys.googleAccount] = s.googleAccount else remove(Keys.googleAccount)
        this[Keys.saveNewTasksToGoogle] = s.saveNewTasksToGoogle
        this[Keys.lastTasksSync] = s.lastTasksSync
        this[Keys.blockedGoogleScopes] = s.blockedGoogleScopes
        this[Keys.onboardingDone] = s.onboardingDone
        this[Keys.barTabs] = s.barTabs
        this[Keys.autoInstallUpdates] = s.autoInstallUpdates
        this[Keys.lastUpdateCheck] = s.lastUpdateCheck
        this[Keys.dismissedUpdateCode] = s.dismissedUpdateCode
        this[Keys.dismissedUpdateAt] = s.dismissedUpdateAt
    }

    private companion object {
        const val SETTINGS_VERSION = 1
        val LEGACY_EVENT_OFFSETS = setOf(15, 0)
    }

    /** Zwischenspeicher der zuletzt geladenen Gesundheitsdaten (JSON). */
    val healthCache: Flow<String?> = context.dataStore.data.map { it[Keys.healthCache] }

    suspend fun saveHealthCache(json: String?) {
        context.dataStore.edit { if (json == null) it.remove(Keys.healthCache) else it[Keys.healthCache] = json }
    }
}

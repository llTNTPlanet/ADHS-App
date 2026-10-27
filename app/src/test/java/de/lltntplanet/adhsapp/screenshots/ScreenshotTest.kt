package de.lltntplanet.adhsapp.screenshots

import android.Manifest
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.lltntplanet.adhsapp.AdhsApp
import de.lltntplanet.adhsapp.MainActivity
import de.lltntplanet.adhsapp.data.db.BirthdayEntity
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.settings.Sex
import de.lltntplanet.adhsapp.data.db.Repeat
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.db.TaskEntity
import de.lltntplanet.adhsapp.data.google.GoogleJson
import de.lltntplanet.adhsapp.data.health.HealthDay
import de.lltntplanet.adhsapp.data.health.HealthSnapshot
import de.lltntplanet.adhsapp.data.health.HeartDay
import de.lltntplanet.adhsapp.data.health.SleepNight
import de.lltntplanet.adhsapp.data.settings.CalendarViewMode
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderActivity
import de.lltntplanet.adhsapp.reminders.ReminderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Robolectric startet WorkManager nicht automatisch. */
class ScreenshotApp : AdhsApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        super.onCreate()
    }
}

/**
 * Erzeugt Screenshots der wichtigsten Bildschirme mit Beispieldaten.
 * Nur auf Wunsch: ./gradlew testDebugUnitTest -Pscreenshots=true --tests "*ScreenshotTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-rDE-w400dp-h860dp-xhdpi", application = ScreenshotApp::class)
class ScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<AdhsApp>()
    private val outDir by lazy { File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() } }

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("screenshots") == "true")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        shadowOf(app as Application).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, "com.android.calendar")
        seed()
    }

    private fun seed() = runBlocking {
        val c = app.container
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        c.settings.update { it.copy(onboardingDone = false, healthConnected = true, stepGoal = 8000, alarmSound = false, vibrate = false) }
        val dao = c.db.taskDao()
        dao.insert(TaskEntity(title = "Tabletten nehmen", dueEpochDay = today.toEpochDay(), dueMinutes = 8 * 60, repeat = Repeat.DAILY))
        dao.insert(TaskEntity(title = "Rezept beim Arzt abholen", dueEpochDay = today.minusDays(1).toEpochDay(), important = true))
        dao.insert(TaskEntity(title = "Wäsche aufhängen", dueEpochDay = today.toEpochDay(), dueMinutes = 18 * 60))
        dao.insert(TaskEntity(title = "Mails beantworten (nur 15 Min.)", dueEpochDay = today.toEpochDay(), notes = "Timer stellen"))
        dao.insert(
            TaskEntity(
                title = "Geschenk für Anna besorgen", dueEpochDay = today.plusDays(1).toEpochDay(),
                googleTaskId = "x1", googleListId = "L1", googleListTitle = "Meine Aufgaben",
            ),
        )
        dao.insert(TaskEntity(title = "Steuererklärung: Belege sortieren", dueEpochDay = today.plusDays(4).toEpochDay()))
        dao.insert(TaskEntity(title = "Neue Laufschuhe ansehen"))
        dao.insert(TaskEntity(title = "Pflanzen gießen", dueEpochDay = today.toEpochDay(), done = true, doneAt = System.currentTimeMillis()))
        val routines = c.db.routineDao()
        routines.upsert(RoutineEntity(name = "Methylphenidat 10 mg", dose = "1 Tablette, mit Wasser", times = "480,840"))
        routines.upsert(RoutineEntity(name = "Vitamin D", dose = "1 Tropfen", times = "480"))
        routines.upsert(RoutineEntity(name = "Zähne putzen", kind = RoutineKind.ROUTINE, times = "470,1320"))
        routines.upsert(RoutineEntity(name = "Spaziergang", kind = RoutineKind.ROUTINE, dose = "20 Minuten", times = "1050", weekdays = 0b0011111))
        routines.insertLog(RoutineLogEntity(routineId = 2, epochDay = today.toEpochDay(), minutes = 480))
        routines.insertLog(RoutineLogEntity(routineId = 3, epochDay = today.toEpochDay(), minutes = 470))
        val b = c.db.birthdayDao()
        b.upsert(BirthdayEntity(name = "Anna Schmidt", month = today.plusDays(1).monthValue, day = today.plusDays(1).dayOfMonth, year = 1992))
        b.upsert(BirthdayEntity(name = "Papa", month = today.monthValue, day = today.dayOfMonth, year = 1961))
        b.upsert(BirthdayEntity(name = "Jonas", month = today.plusDays(9).monthValue, day = today.plusDays(9).dayOfMonth))
        b.upsert(BirthdayEntity(name = "Tante Ilse", month = today.plusDays(40).monthValue, day = today.plusDays(40).dayOfMonth, year = 1950))
        b.upsert(BirthdayEntity(name = "Mia", month = today.plusDays(120).monthValue, day = today.plusDays(120).dayOfMonth, year = 2015))

        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val steps = listOf(9120L, 4310L, 10450L, 7800L, 12030L, 6020L, 5240L)
        val sleep = listOf(412L, 335L, 455L, 390L, 470L, 420L, 352L)
        val zone = ZoneId.of("Europe/Berlin")
        val snapshot = HealthSnapshot(
            fetchedAt = System.currentTimeMillis(),
            days = days.mapIndexed { i, d -> HealthDay(d.toEpochDay(), steps[i], steps[i] * 0.72, 1900.0 + i * 40, 12L + i * 3) },
            sleepNights = days.mapIndexed { i, d ->
                val end = d.atTime(6, 50).atZone(zone).toInstant().toEpochMilli()
                SleepNight(d.toEpochDay(), end - (sleep[i] + 30) * 60_000L, end, sleep[i], 30, sleep[i] / 6, sleep[i] / 2, sleep[i] / 4)
            },
            restingHeartRate = days.mapIndexed { i, d -> HeartDay(d.toEpochDay(), 58L + (i % 3)) },
        )
        c.settings.saveHealthCache(GoogleJson.encodeToString(snapshot))

        c.settings.update { it.copy(sex = Sex.MALE, birthYear = 1994, heightCm = 180, weightKg = 78.0, calorieAdjustment = -300) }
        val food = c.db.foodDao()
        val t0 = today.atTime(7, 40).atZone(zone).toInstant().toEpochMilli()
        food.upsert(FoodEntryEntity(epochDay = today.toEpochDay(), meal = MealType.BREAKFAST, name = "Haferflocken mit Banane", kcal = 420, amount = "1 Schüssel", createdAt = t0))
        food.upsert(FoodEntryEntity(epochDay = today.toEpochDay(), meal = MealType.BREAKFAST, name = "Kaffee mit Milch", kcal = 40, createdAt = t0 + 5 * 60_000L))
        food.upsert(FoodEntryEntity(epochDay = today.toEpochDay(), meal = MealType.LUNCH, name = "Linsensuppe", kcal = 510, amount = "1 Teller", createdAt = t0 + 5 * 3_600_000L))
        food.upsert(FoodEntryEntity(epochDay = today.toEpochDay(), meal = MealType.SNACK, name = "Apfel", kcal = 95, amount = "1 Stück", createdAt = t0 + 8 * 3_600_000L))
    }

    /** Daten werden auf IO-Threads geladen – kurz warten und die Oberfläche nachziehen lassen. */
    private fun settle(rounds: Int = 10) {
        repeat(rounds) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
    }

    /** Zeichnet das Fenster direkt in eine Bitmap (captureToImage wartet unter Robolectric ewig auf PixelCopy). */
    private fun <A : Activity> shot(scenario: ActivityScenario<A>, name: String) {
        settle()
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun clickTab(route: String) {
        compose.onNode(androidx.compose.ui.test.hasTestTag("tab-$route")).performClick()
    }

    private fun setView(mode: CalendarViewMode) = runBlocking {
        app.container.settings.update { it.copy(calendarView = mode) }
    }

    @Test
    fun screens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun shot(name: String) = shot(scenario, name)
            shot("01-einrichtung")
            compose.onNode(hasText("Fertig").or(hasText("Später erledigen"))).performScrollTo().performClick()
            shot("02-heute")
            setView(CalendarViewMode.SCHEDULE)
            clickTab("calendar")
            shot("03-kalender-terminuebersicht")
            setView(CalendarViewMode.WEEK)
            shot("04-kalender-woche")
            setView(CalendarViewMode.THREE_DAY)
            shot("05-kalender-3-tage")
            setView(CalendarViewMode.MONTH)
            shot("06-kalender-monat")
            clickTab("tasks")
            shot("07-aufgaben")
            clickTab("routines")
            shot("13-routinen")
            clickTab("birthdays")
            shot("08-geburtstage")
            clickTab("health")
            shot("09-gesundheit")
            clickTab("nutrition")
            shot("10-ernaehrung")
        }
    }

    @Test
    fun fullScreenReminders() {
        val now = System.currentTimeMillis()
        val event = Reminder(
            key = "ev:1:1:15", kind = ReminderKind.EVENT, triggerAt = now, title = "Zahnarzt Dr. Weber",
            detail = "Hauptstraße 12", targetTime = now + 15 * 60_000L + 5_000L, targetEnd = now + 75 * 60_000L,
            refId = 1, color = 0xFF33B679.toInt(),
        )
        ActivityScenario.launch<ReminderActivity>(ReminderActivity.intent(app, listOf(event))).use {
            shot(it, "11-vollbild-termin")
        }
        val task = Reminder(
            key = "task:2:1:480", kind = ReminderKind.TASK, triggerAt = now, title = "Tabletten nehmen",
            detail = "Mit einem Glas Wasser", targetTime = now, refId = 2,
        )
        val birthday = Reminder(
            key = "bd:m:1:x:0", kind = ReminderKind.BIRTHDAY, triggerAt = now, title = "Anna Schmidt",
            targetTime = now, allDay = true, age = 34, daysBefore = 0,
        )
        ActivityScenario.launch<ReminderActivity>(ReminderActivity.intent(app, listOf(task, birthday))).use {
            shot(it, "12-vollbild-aufgabe")
        }
    }
}

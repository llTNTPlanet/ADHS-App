package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.data.db.DispenserDao
import de.lltntplanet.adhsapp.data.db.DispenserEventEntity
import de.lltntplanet.adhsapp.data.db.DispenserEventKind
import de.lltntplanet.adhsapp.data.db.RoutineDao
import de.lltntplanet.adhsapp.data.db.RoutineEntity
import de.lltntplanet.adhsapp.data.db.RoutineKind
import de.lltntplanet.adhsapp.data.db.RoutineLogEntity
import de.lltntplanet.adhsapp.data.dispenser.DispenserLogic
import de.lltntplanet.adhsapp.data.dispenser.DispenserNotification
import de.lltntplanet.adhsapp.data.dispenser.DispenserRepository
import de.lltntplanet.adhsapp.data.routines.RoutineLogic
import de.lltntplanet.adhsapp.data.routines.RoutineRepository
import de.lltntplanet.adhsapp.millis
import de.lltntplanet.adhsapp.reminders.ReminderPlanner
import de.lltntplanet.adhsapp.ui.settings.dispenserEventSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DispenserLogicTest {

    // 2026-10-26 ist ein Montag.
    private val monday: LocalDate = LocalDate.of(2026, 10, 26)
    private val morning = RoutineEntity(id = 1, name = "Medikinet adult 20 mg", times = "480,840", dispenser = true)
    private val vitamin = RoutineEntity(id = 2, name = "Vitamin D", times = "480", dispenser = true)
    private val notInDispenser = RoutineEntity(id = 3, name = "Ibuprofen 400 mg", times = "480")

    @Test
    fun classifiesGermanAndEnglishTexts() {
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("iDispenser", "Medikament wurde genommen"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("Memio Go", "Pills taken late at 08:12"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Bereits eingenommen"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("Erinnerung", "Medikament nicht genommen"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("Dose missed", "Not taken"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("Einnahme verpasst", ""))
        assertEquals(DispenserEventKind.REFILL, DispenserLogic.classify("Bitte nachfüllen", "Nur noch 2 Dosen"))
        assertEquals(DispenserEventKind.REFILL, DispenserLogic.classify("Refill reminder", "Running low"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("Zeit für deine Medikamente", "Fach ist offen"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("It's time to take your pills", ""))
        assertEquals(DispenserEventKind.OTHER, DispenserLogic.classify("Gerät offline", "WLAN prüfen"))
    }

    @Test
    fun readsTimesFromText() {
        assertEquals(listOf(480), DispenserLogic.timesIn("Einnahme um 08:00 Uhr"))
        assertEquals(listOf(480, 1230), DispenserLogic.timesIn("8:00 und 20:30"))
        assertEquals(listOf(1200), DispenserLogic.timesIn("Dose at 8:00 PM"))
        assertEquals(listOf(30), DispenserLogic.timesIn("12:30 am"))
        // „am“ als deutsches Wort ist kein AM.
        assertEquals(listOf(750), DispenserLogic.timesIn("Einnahme 12:30 am Montag"))
        // Datum und ungültige Zeiten zählen nicht.
        assertEquals(emptyList<Int>(), DispenserLogic.timesIn("29.10.2026, 25:00, 123:45"))
    }

    @Test
    fun takenMatchesLatestDispenserSlotAndTicksAllMedsInThatCompartment() {
        val match = DispenserLogic.matchTaken(
            listOf(morning, vitamin, notInDispenser), emptyList(), millis("2026-10-26T08:12"), Berlin,
        )
        assertNotNull(match)
        assertEquals(monday, match!!.date)
        assertEquals(480, match.minutes)
        assertEquals(listOf(1L, 2L), match.open.map { it.routine.id }.sorted())
    }

    @Test
    fun takenAfterAfternoonSlotBelongsToAfternoon() {
        val match = DispenserLogic.matchTaken(listOf(morning, vitamin), emptyList(), millis("2026-10-26T14:20"), Berlin)
        assertEquals(840, match!!.minutes)
        assertEquals(listOf(1L), match.open.map { it.routine.id })
    }

    @Test
    fun takenSlightlyBeforeAlarmStillCountsButNotEarlier() {
        // Uhr des Spenders geht 3 Minuten vor.
        assertEquals(480, DispenserLogic.matchTaken(listOf(vitamin), emptyList(), millis("2026-10-26T07:57"), Berlin)!!.minutes)
        // Eine Stunde vorher: Das Fach ist noch zu, und die Einnahme vom Vortag liegt mehr als 12 Stunden zurück.
        assertNull(DispenserLogic.matchTaken(listOf(vitamin), emptyList(), millis("2026-10-26T07:00"), Berlin))
        // Abends gemeldet, ohne Einnahmezeit in den letzten 12 Stunden: keine Zuordnung.
        assertNull(DispenserLogic.matchTaken(listOf(vitamin), emptyList(), millis("2026-10-26T20:30"), Berlin))
    }

    @Test
    fun timeInTextWins() {
        // Um 14:30 gemeldet, aber der Text nennt die Morgen-Einnahme (verspätet genommen).
        val match = DispenserLogic.matchTaken(listOf(morning), emptyList(), millis("2026-10-26T14:30"), Berlin, listOf(480))
        assertEquals(480, match!!.minutes)
    }

    @Test
    fun alreadyCheckedDosesStayAndOnlyDispenserMedsCount() {
        val log = listOf(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480))
        val match = DispenserLogic.matchTaken(listOf(morning, vitamin), log, millis("2026-10-26T08:05"), Berlin)
        assertEquals(listOf(2L), match!!.open.map { it.routine.id })
        assertNull(DispenserLogic.matchTaken(listOf(notInDispenser), emptyList(), millis("2026-10-26T08:05"), Berlin))
        // Nur Medikamente, keine Routinen.
        val routine = RoutineEntity(id = 9, name = "Zähne", kind = RoutineKind.ROUTINE, times = "480", dispenser = true)
        assertNull(DispenserLogic.matchTaken(listOf(routine), emptyList(), millis("2026-10-26T08:05"), Berlin))
    }

    @Test
    fun plannerDelaysOwnReminderForDispenserMedsOrDropsIt() {
        val all = listOf(vitamin, notInDispenser)
        val delayed = ReminderPlanner.planRoutines(all, emptyList(), monday, monday, Berlin, dispenserDelayMinutes = 30)
        val byId = delayed.associateBy { it.refId }
        assertEquals(millis("2026-10-26T08:30"), byId[2L]!!.triggerAt)
        assertEquals(millis("2026-10-26T08:00"), byId[2L]!!.targetTime)
        assertTrue(byId[2L]!!.detail!!.contains("Spender"))
        assertEquals(millis("2026-10-26T08:00"), byId[3L]!!.triggerAt)

        val none = ReminderPlanner.planRoutines(all, emptyList(), monday, monday, Berlin, dispenserDelayMinutes = null)
        assertEquals(listOf(3L), none.map { it.refId })

        // Vom Spender abgehakt: keine Erinnerung mehr.
        val log = listOf(RoutineLogEntity(routineId = 2, epochDay = monday.toEpochDay(), minutes = 480, byDispenser = true))
        val afterTaken = ReminderPlanner.planRoutines(listOf(vitamin), log, monday, monday, Berlin, dispenserDelayMinutes = 30)
        assertTrue(afterTaken.isEmpty())
        assertTrue(RoutineLogic.occurrences(listOf(vitamin), log, monday, monday, Berlin).single().byDispenser)
    }

    @Test
    fun repositoryTicksOnceAndKeepsManualChecks() = runTest {
        val routineDao = FakeRoutineDao(listOf(morning, vitamin))
        // Medikinet um 8:00 schon von Hand abgehakt.
        routineDao.insertLog(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480, doneAt = 1L))
        val dispenserDao = FakeDispenserDao()
        val cancelled = mutableListOf<String>()
        var changes = 0
        val repo = DispenserRepository(dispenserDao, RoutineRepository(routineDao) { changes++ }) { cancelled += it }

        val taken = DispenserNotification("com.dispenser", "0|com.dispenser|1|null|10", millis("2026-10-26T08:07"), "iDispenser", "Medikament genommen")
        val event = repo.onNotification(taken, Berlin)
        assertEquals(DispenserEventKind.TAKEN, event!!.kind)
        assertEquals(480, event.matchedMinutes)
        assertEquals("Medikinet adult 20 mg, Vitamin D", event.matchedNames)
        assertEquals("Genommen → 08:00: Medikinet adult 20 mg, Vitamin D", dispenserEventSummary(event, monday))

        val log = routineDao.logEntries.associateBy { it.routineId }
        assertFalse("Haken von Hand bleibt", log.getValue(1L).byDispenser)
        assertEquals(1L, log.getValue(1L).doneAt)
        assertTrue(log.getValue(2L).byDispenser)
        assertEquals(listOf("rt:2:${monday.toEpochDay()}:480"), cancelled)
        assertEquals(1, changes)

        // Dieselbe Benachrichtigung noch einmal (z. B. nach Neustart des Dienstes): nichts passiert.
        assertNull(repo.onNotification(taken, Berlin))
        assertEquals(1, dispenserDao.events.size)
        assertEquals(1, changes)
    }

    @Test
    fun repositoryKeepsUnknownTextsOnlyFromIDispenser() = runTest {
        val dispenserDao = FakeDispenserDao()
        val repo = DispenserRepository(dispenserDao, RoutineRepository(FakeRoutineDao(emptyList())) {}) {}
        val at = millis("2026-10-26T09:00")
        assertEquals(
            DispenserEventKind.OTHER,
            repo.onNotification(DispenserNotification("com.dispenser", "a", at, "Gerät offline", ""), Berlin)!!.kind,
        )
        // Smart Life meldet auch Lampen: Unbekanntes von dort wird nicht gespeichert.
        assertNull(repo.onNotification(DispenserNotification("com.tuya.smartlife", "b", at, "Licht an", "Wohnzimmer"), Berlin))
        assertEquals(
            DispenserEventKind.MISSED,
            repo.onNotification(DispenserNotification("com.tuya.smartlife", "c", at, "Pillendose", "Einnahme verpasst"), Berlin)!!.kind,
        )
        assertNull(repo.onNotification(DispenserNotification("com.dispenser", "d", at, " ", ""), Berlin))
        assertEquals(2, dispenserDao.events.size)
    }
}

private class FakeRoutineDao(routines: List<RoutineEntity>) : RoutineDao {
    val routines = routines.toMutableList()
    val logEntries = mutableListOf<RoutineLogEntity>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<RoutineEntity>> = MutableStateFlow(routines.toList())
    override suspend fun active() = routines.filter { it.active }
    override suspend fun byId(id: Long) = routines.find { it.id == id }
    override suspend fun upsert(routine: RoutineEntity): Long {
        routines.removeAll { it.id == routine.id }
        routines += routine
        return routine.id
    }
    override suspend fun delete(routine: RoutineEntity) {
        routines.removeAll { it.id == routine.id }
    }
    override fun observeLog(fromDay: Long, toDay: Long): Flow<List<RoutineLogEntity>> =
        MutableStateFlow(logEntries.toList()).map { list -> list.filter { it.epochDay in fromDay..toDay } }
    override suspend fun log(fromDay: Long, toDay: Long) = logEntries.filter { it.epochDay in fromDay..toDay }
    private fun same(a: RoutineLogEntity, b: RoutineLogEntity) =
        a.routineId == b.routineId && a.epochDay == b.epochDay && a.minutes == b.minutes
    override suspend fun insertLog(entry: RoutineLogEntity): Long {
        logEntries.removeAll { same(it, entry) }
        logEntries += entry.copy(id = nextId)
        return nextId++
    }
    override suspend fun insertLogIfAbsent(entry: RoutineLogEntity): Long =
        if (logEntries.any { same(it, entry) }) -1L else insertLog(entry)
    override suspend fun deleteLog(routineId: Long, epochDay: Long, minutes: Int) {
        logEntries.removeAll { it.routineId == routineId && it.epochDay == epochDay && it.minutes == minutes }
    }
    override suspend fun deleteLogFor(routineId: Long) {
        logEntries.removeAll { it.routineId == routineId }
    }
    override suspend fun purgeLog(beforeDay: Long) {
        logEntries.removeAll { it.epochDay < beforeDay }
    }
}

private class FakeDispenserDao : DispenserDao {
    val events = mutableListOf<DispenserEventEntity>()

    override fun observeRecent(limit: Int): Flow<List<DispenserEventEntity>> =
        MutableStateFlow(events.sortedByDescending { it.postedAt }.take(limit))
    override suspend fun insert(event: DispenserEventEntity): Long {
        if (events.any { it.notificationKey == event.notificationKey && it.postedAt == event.postedAt }) return -1L
        events += event.copy(id = events.size + 1L)
        return events.size.toLong()
    }
    override suspend fun purge(before: Long) {
        events.removeAll { it.postedAt < before }
    }
    override suspend fun clear() = events.clear()
}

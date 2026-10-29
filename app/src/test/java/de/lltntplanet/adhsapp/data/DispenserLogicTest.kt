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
    fun takenOnlyAsWholeWordWithoutNegationOrQuestion() {
        // Andere Geräte und Wörter, die „genommen“ nur enthalten.
        assertEquals(DispenserEventKind.OTHER, DispenserLogic.classify("Kamera", "Bewegung erkannt – Video aufgenommen"))
        assertEquals(DispenserEventKind.OTHER, DispenserLogic.classify("Türklingel", "Anruf angenommen"))
        // Verneint: verpasst, nicht genommen.
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Medikament wurde nicht rechtzeitig eingenommen"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Medication hasn't been taken yet"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Pills not yet taken"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Keine Tablette entnommen"))
        // Fragen und Aufforderungen sind höchstens fällig.
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Ihre Medikamente sollten um 08:00 eingenommen werden"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Have you taken your pills?"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Hast du deine Medikamente genommen?"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Your medication needs to be taken now"))
        // Weitere Verneinungen.
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "No pills taken yet"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Fach geöffnet, aber nichts entnommen"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Es wurden noch keinerlei Medikamente entnommen"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Fach geöffnet, ohne dass Medikamente entnommen wurden"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Medication hasn´t been taken"))
        assertEquals(DispenserEventKind.MISSED, DispenserLogic.classify("", "Medication wasnt taken"))
        // Bedingungen und Rückblicke in Erinnerungen.
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Zeit für deine Medikamente. Falls bereits eingenommen, diese Meldung ignorieren."))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Einnahmezeit! Wenn du deine Medikamente schon genommen hast, ignoriere diese Nachricht."))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Time to take your medicine. Ignore if already taken"))
        assertEquals(DispenserEventKind.DUE, DispenserLogic.classify("", "Time for your 2:00 PM dose. Last dose taken at 8:00 AM"))
    }

    @Test
    fun otherSentencesDoNotUndoATakenMessage() {
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Medikament entnommen. Bitte Fach schließen."))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Medication taken. Next dose not until 14:00"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Medikament genommen – keine weiteren Einnahmen heute"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Medikament genommen. Vergiss nicht, das Fach zu schließen"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("", "Pills taken. Don't forget to refill"))
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("Medikament genommen", "Bitte bald nachfüllen"))
        // Medikament endet auf „nt“ – das ist keine Verneinung.
        assertEquals(DispenserEventKind.TAKEN, DispenserLogic.classify("Memio Go", "Medikament entnommen"))
    }

    @Test
    fun recognisesDispenserMessagesFromSmartHomeApps() {
        assertTrue(DispenserLogic.mentionsDispenser("Memio Go", "Fach 2 geöffnet"))
        assertTrue(DispenserLogic.mentionsDispenser("", "Medikament genommen"))
        assertTrue(DispenserLogic.mentionsDispenser("Pill box", "Dose taken"))
        assertFalse(DispenserLogic.mentionsDispenser("Kamera", "Snapshot taken"))
        assertFalse(DispenserLogic.mentionsDispenser("Licht", "Wohnzimmer an"))
    }

    @Test
    fun readsTimesFromText() {
        assertEquals(listOf(480), DispenserLogic.timesIn("Einnahme um 08:00 Uhr"))
        assertEquals(listOf(480, 1230), DispenserLogic.timesIn("8:00 und 20:30"))
        assertEquals(listOf(1200), DispenserLogic.timesIn("Dose at 8:00 PM"))
        assertEquals(listOf(30), DispenserLogic.timesIn("12:30 am"))
        // „am“ als deutsches Wort ist kein AM.
        assertEquals(listOf(750), DispenserLogic.timesIn("Einnahme 12:30 am Montag"))
        // Englisches AM/PM auch vor weiteren Wörtern.
        assertEquals(listOf(1200), DispenserLogic.timesIn("Your 8:00 PM dose was taken"))
        assertEquals(listOf(1200), DispenserLogic.timesIn("8:00 pm Medikament genommen"))
        assertEquals(listOf(30), DispenserLogic.timesIn("12:30 AM dose"))
        // Uhrzeiten der nächsten Einnahme zählen nicht.
        assertEquals(emptyList<Int>(), DispenserLogic.timesIn("Medikament genommen. Nächste Einnahme: 14:00"))
        assertEquals(listOf(480), DispenserLogic.timesIn("Taken at 8:00 a.m., next dose at 2:00 p.m."))
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
    fun textTimeCountsWhenItNamesExactlyOneSlot() {
        // Einnahmezeit und Entnahmezeit im Text: Die genannte Einnahmezeit 08:00 gilt.
        assertEquals(480, DispenserLogic.matchTaken(listOf(morning), emptyList(), millis("2026-10-26T14:06"), Berlin, listOf(480, 845))!!.minutes)
        // Zwei Einnahmezeiten im Text: unklar, also die letzte Einnahmezeit davor.
        assertEquals(840, DispenserLogic.matchTaken(listOf(morning), emptyList(), millis("2026-10-26T14:30"), Berlin, listOf(480, 840))!!.minutes)
        // 08:00 ist schon abgehakt und wird verspätet noch einmal gemeldet: nichts abhaken, auch nicht 14:00.
        val log = listOf(RoutineLogEntity(routineId = 1, epochDay = monday.toEpochDay(), minutes = 480))
        val match = DispenserLogic.matchTaken(listOf(morning), log, millis("2026-10-26T14:30"), Berlin, listOf(480))
        assertEquals(480, match!!.minutes)
        assertTrue(match.open.isEmpty())
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
        assertEquals(setOf("rt:2:${monday.toEpochDay()}:480"), RoutineLogic.doneKeys(log))

        // Ohne Benachrichtigungszugriff wie jedes andere Medikament: pünktlich und ohne Spender-Hinweis.
        val unwatched = ReminderPlanner.planRoutines(listOf(vitamin), emptyList(), monday, monday, Berlin, 30, dispenserWatched = false)
        assertEquals(millis("2026-10-26T08:00"), unwatched.single().triggerAt)
        assertNull(unwatched.single().detail)
    }

    @Test
    fun tickFromReminderKeepsDispenserEntry() = runTest {
        val routineDao = FakeRoutineDao(listOf(vitamin))
        routineDao.insertLog(RoutineLogEntity(routineId = 2, epochDay = monday.toEpochDay(), minutes = 480, doneAt = 5L, byDispenser = true))
        var changes = 0
        RoutineRepository(routineDao) { changes++ }.setDoneAt(2, millis("2026-10-26T08:00"), Berlin)
        val entry = routineDao.logEntries.single()
        assertTrue(entry.byDispenser)
        assertEquals(5L, entry.doneAt)
        assertEquals(1, changes)
    }

    @Test
    fun tickFromReminderTurnsSkippedIntoTaken() = runTest {
        val routineDao = FakeRoutineDao(listOf(vitamin))
        routineDao.insertLog(RoutineLogEntity(routineId = 2, epochDay = monday.toEpochDay(), minutes = 480, skipped = true))
        RoutineRepository(routineDao) {}.setDoneAt(2, millis("2026-10-26T08:00"), Berlin)
        assertFalse(routineDao.logEntries.single().skipped)
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
        // Smart Life meldet auch Lampen und Kameras: Was den Spender nicht nennt, wird nicht gespeichert.
        assertNull(repo.onNotification(DispenserNotification("com.tuya.smartlife", "b", at, "Licht an", "Wohnzimmer"), Berlin))
        assertNull(repo.onNotification(DispenserNotification("com.tuya.smartlife", "e", at, "Kamera", "Snapshot taken"), Berlin))
        assertEquals(
            DispenserEventKind.MISSED,
            repo.onNotification(DispenserNotification("com.tuya.smartlife", "c", at, "Pillendose", "Einnahme verpasst"), Berlin)!!.kind,
        )
        assertNull(repo.onNotification(DispenserNotification("com.dispenser", "d", at, " ", ""), Berlin))
        assertEquals(2, dispenserDao.events.size)
    }

    @Test
    fun onlyIDispenserTicksAndOnlyItsNewestLineCounts() = runTest {
        val routineDao = FakeRoutineDao(listOf(morning))
        val repo = DispenserRepository(FakeDispenserDao(), RoutineRepository(routineDao) {}) {}
        val at = millis("2026-10-26T08:07")
        // Smart Life: gespeichert, aber nicht abgehakt.
        val viaSmartLife = repo.onNotification(DispenserNotification("com.tuya.smartlife", "a", at, "Memio Go", "Medikament genommen"), Berlin)
        assertEquals(DispenserEventKind.TAKEN, viaSmartLife!!.kind)
        assertNull(viaSmartLife.matchedMinutes)
        assertEquals("Genommen → nicht abgehakt – das tut nur iDispenser selbst", dispenserEventSummary(viaSmartLife, monday))
        // Sammel-Benachrichtigung: Die alte Zeile „genommen“ gilt nicht für die neue Meldung.
        val inbox = DispenserNotification(
            "com.dispenser", "b", at, "iDispenser", "Gestern 20:00 genommen\nZeit für deine Medikamente",
            latestLine = "Zeit für deine Medikamente",
        )
        assertEquals(DispenserEventKind.DUE, repo.onNotification(inbox, Berlin)!!.kind)
        assertTrue(routineDao.logEntries.isEmpty())
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

    override fun observeRecent(after: Long, limit: Int): Flow<List<DispenserEventEntity>> =
        MutableStateFlow(events.filter { it.postedAt > after }.sortedByDescending { it.postedAt }.take(limit))
    override suspend fun insert(event: DispenserEventEntity): Long {
        if (events.any { it.notificationKey == event.notificationKey && it.postedAt == event.postedAt }) return -1L
        events += event.copy(id = events.size + 1L)
        return events.size.toLong()
    }
    override suspend fun purge(before: Long) {
        events.removeAll { it.postedAt < before }
    }
}

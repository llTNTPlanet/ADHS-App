package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import de.lltntplanet.adhsapp.data.mood.MoodLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoodLogicTest {

    private val tired = SymptomEntity(id = 1, name = "Müdigkeit")
    private val restless = SymptomEntity(id = 2, name = "Innere Unruhe")
    private val from: LocalDate = LocalDate.of(2026, 10, 22)
    private val to: LocalDate = LocalDate.of(2026, 10, 28)

    private fun entry(symptom: Long, date: LocalDate, minutes: Int, value: Int) =
        MoodEntryEntity(symptomId = symptom, epochDay = date.toEpochDay(), minutes = minutes, value = value)

    @Test
    fun defaultSymptomsFollowMyTherapyAndAreUnique() {
        assertTrue(MoodLogic.DEFAULT_SYMPTOMS.containsAll(listOf("Müdigkeit", "Niedergeschlagenheit", "Innere Unruhe")))
        assertEquals(MoodLogic.DEFAULT_SYMPTOMS.size, MoodLogic.DEFAULT_SYMPTOMS.distinct().size)
        assertTrue(MoodLogic.SUGGESTED_SYMPTOMS.none { it in MoodLogic.DEFAULT_SYMPTOMS })
    }

    @Test
    fun valueLabelsCoverScale() {
        assertEquals("gar nicht", MoodLogic.valueLabel(1))
        assertEquals("mäßig", MoodLogic.valueLabel(5))
        assertEquals("sehr stark", MoodLogic.valueLabel(10))
    }

    @Test
    fun statsAverageTwoCheckupsOfOneDayAndLeaveGaps() {
        val entries = listOf(
            entry(1, from, 480, 4),
            entry(1, from, 1200, 6),
            entry(1, to, 480, 2),
        )
        val stats = MoodLogic.stats(listOf(tired, restless), entries, from, to)
        assertEquals(2, stats.size)
        val t = stats[0]
        assertEquals(7, t.days.size)
        assertEquals(5f, t.days.first().average)
        assertNull(t.days[1].average)
        assertEquals(2f, t.days.last().average)
        assertEquals(4f, t.average)
        assertEquals(2, t.min)
        assertEquals(6, t.max)
        assertEquals(2, t.last)
        assertEquals(3, t.count)
        assertNull(t.previousAverage)
        assertNull(t.change)
        // Ohne Werte: alles leer, aber das Symptom bleibt in der Liste.
        val r = stats[1]
        assertEquals(0, r.count)
        assertNull(r.average)
        assertTrue(r.days.all { it.average == null })
    }

    @Test
    fun trendComparesWithPreviousPeriodOfSameLength() {
        val entries = listOf(
            entry(1, from.minusDays(7), 480, 8), // Vorzeitraum
            entry(1, from.minusDays(1), 480, 6), // Vorzeitraum (letzter Tag)
            entry(1, from.minusDays(8), 480, 10), // zu alt – zählt nicht
            entry(1, from.plusDays(3), 480, 3),
        )
        val t = MoodLogic.stats(listOf(tired), entries, from, to).single()
        assertEquals(7f, t.previousAverage)
        assertEquals(3f, t.average)
        assertEquals(-4f, t.change)
    }

    @Test
    fun checkupCountCountsTimesNotValues() {
        val entries = listOf(
            entry(1, from, 480, 4),
            entry(2, from, 480, 4),
            entry(1, from, 1200, 4),
            entry(1, from.minusDays(1), 480, 4),
        )
        assertEquals(2, MoodLogic.checkupCount(entries, from, to))
    }
}

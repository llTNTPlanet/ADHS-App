package de.lltntplanet.adhsapp.data.mood

import de.lltntplanet.adhsapp.data.db.MoodDao
import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Symptome und Checkup-Werte für die Gefühlslage. */
class MoodRepository(private val dao: MoodDao) {

    /** Alle Symptome, aktive zuerst. */
    fun observeSymptoms(): Flow<List<SymptomEntity>> = dao.observeSymptoms()

    fun observeEntries(from: LocalDate, toInclusive: LocalDate): Flow<List<MoodEntryEntity>> =
        dao.observeEntries(from.toEpochDay(), toInclusive.toEpochDay())

    /** Die Werte eines einzelnen Checkups (Tag + geplante Uhrzeit). */
    fun observeCheckup(date: LocalDate, minutes: Int): Flow<List<MoodEntryEntity>> =
        dao.observeCheckup(date.toEpochDay(), minutes)

    /** Zuletzt erfasster Wert je Symptom-ID. */
    suspend fun lastValues(): Map<Long, Int> = dao.lastValues().associate { it.symptomId to it.value }

    /** Beim ersten Checkup die Standard-Symptome anlegen (nur, wenn noch gar keine existieren). */
    suspend fun ensureDefaults() {
        if (dao.symptomCount() > 0) return
        dao.upsertSymptoms(MoodLogic.DEFAULT_SYMPTOMS.mapIndexed { i, name -> SymptomEntity(name = name, sortOrder = i) })
    }

    /** Symptom hinzufügen; ein früher entferntes mit gleichem Namen wird wieder aktiviert (alte Werte bleiben). */
    suspend fun addSymptom(name: String): Long {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return 0
        val existing = dao.symptomByName(clean)
        if (existing != null) {
            if (!existing.active) dao.upsertSymptom(existing.copy(active = true, sortOrder = dao.maxSortOrder() + 1))
            return existing.id
        }
        return dao.upsertSymptom(SymptomEntity(name = clean, sortOrder = dao.maxSortOrder() + 1))
    }

    /** Symptom aus der Abfrage nehmen. Ohne gespeicherte Werte wird es ganz gelöscht, sonst nur deaktiviert. */
    suspend fun removeSymptom(symptom: SymptomEntity) {
        if (dao.entryCount(symptom.id) == 0) dao.deleteSymptom(symptom.id)
        else dao.upsertSymptom(symptom.copy(active = false))
    }

    /** Einen Checkup speichern: ein Wert je Symptom; vorhandene Werte zum gleichen Zeitpunkt werden ersetzt. */
    suspend fun saveCheckup(date: LocalDate, minutes: Int, values: Map<Long, Int>) {
        dao.deleteCheckup(date.toEpochDay(), minutes)
        dao.insertEntries(
            values.map { (symptomId, value) ->
                MoodEntryEntity(
                    symptomId = symptomId,
                    epochDay = date.toEpochDay(),
                    minutes = minutes,
                    value = value.coerceIn(MoodLogic.MIN_VALUE, MoodLogic.MAX_VALUE),
                )
            },
        )
    }

    suspend fun deleteCheckup(date: LocalDate, minutes: Int) = dao.deleteCheckup(date.toEpochDay(), minutes)
}

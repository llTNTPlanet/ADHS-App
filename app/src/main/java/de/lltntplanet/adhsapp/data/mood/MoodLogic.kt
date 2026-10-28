package de.lltntplanet.adhsapp.data.mood

import de.lltntplanet.adhsapp.data.db.MoodEntryEntity
import de.lltntplanet.adhsapp.data.db.SymptomEntity
import java.time.LocalDate

/** Ein Tag in der Statistik: Durchschnitt aller Checkups dieses Tages (null = kein Checkup). */
data class MoodDay(val date: LocalDate, val average: Float?)

/** Auswertung eines Symptoms über einen Zeitraum. */
data class SymptomStats(
    val symptom: SymptomEntity,
    /** Ein Eintrag je Tag im Zeitraum, lückenlos (ohne Checkup = null). */
    val days: List<MoodDay>,
    /** Durchschnitt im Zeitraum, null ohne Werte. */
    val average: Float?,
    /** Durchschnitt im gleich langen Zeitraum davor (für den Trend), null ohne Werte. */
    val previousAverage: Float?,
    val min: Int?,
    val max: Int?,
    /** Letzter erfasster Wert im Zeitraum. */
    val last: Int?,
    /** Anzahl Checkups mit Wert für dieses Symptom im Zeitraum. */
    val count: Int,
) {
    /** Veränderung gegenüber dem Vorzeitraum (negativ = weniger stark = besser). */
    val change: Float? get() = if (average != null && previousAverage != null) average - previousAverage else null
}

/** Reine Logik rund um den Gefühls-Checkup: Standard-Symptome, Skala, Statistik. */
object MoodLogic {

    const val MIN_VALUE = 1
    const val MAX_VALUE = 10

    /** Vorbelegte Symptome nach dem Vorbild von MyTherapy – alle als „Stärke“ bewertet (1 = gar nicht, 10 = sehr stark). */
    val DEFAULT_SYMPTOMS: List<String> = listOf(
        "Müdigkeit",
        "Niedergeschlagenheit",
        "Innere Unruhe",
        "Konzentrationsprobleme",
        "Reizbarkeit",
        "Angst",
        "Stress",
        "Antriebslosigkeit",
        "Schlafprobleme",
        "Vergesslichkeit",
        "Impulsivität",
        "Kopfschmerzen",
        "Appetitlosigkeit",
    )

    /** Weitere Vorschläge zum Antippen beim Hinzufügen (ebenfalls aus dem MyTherapy-Umfeld). */
    val SUGGESTED_SYMPTOMS: List<String> = listOf(
        "Stimmungsschwankungen",
        "Hyperaktivität",
        "Schwindel",
        "Übelkeit",
        "Schmerzen",
        "Herzrasen",
        "Einsamkeit",
        "Grübeln",
        "Heißhunger",
        "Mundtrockenheit",
        "Nervosität",
        "Energielosigkeit",
    )

    /** Kurzer Text zum Wert, z. B. unter dem Schieberegler. */
    fun valueLabel(value: Int): String = when {
        value <= 1 -> "gar nicht"
        value <= 3 -> "leicht"
        value <= 5 -> "mäßig"
        value <= 7 -> "deutlich"
        value <= 9 -> "stark"
        else -> "sehr stark"
    }

    /**
     * Statistik je Symptom für die Tage [from]..[toInclusive]. [entries] müssen auch den gleich langen
     * Zeitraum davor enthalten, damit der Trend berechnet werden kann.
     */
    fun stats(
        symptoms: List<SymptomEntity>,
        entries: List<MoodEntryEntity>,
        from: LocalDate,
        toInclusive: LocalDate,
    ): List<SymptomStats> {
        if (toInclusive.isBefore(from)) return emptyList()
        val length = toInclusive.toEpochDay() - from.toEpochDay() + 1
        val prevFrom = from.toEpochDay() - length
        val bySymptom = entries.groupBy { it.symptomId }
        return symptoms.map { s ->
            val own = bySymptom[s.id].orEmpty()
            val current = own.filter { it.epochDay in from.toEpochDay()..toInclusive.toEpochDay() }
            val previous = own.filter { it.epochDay in prevFrom until from.toEpochDay() }
            val byDay = current.groupBy { it.epochDay }
            val days = (0 until length).map { i ->
                val d = from.plusDays(i)
                MoodDay(d, byDay[d.toEpochDay()]?.map { it.value }?.average()?.toFloat())
            }
            SymptomStats(
                symptom = s,
                days = days,
                average = current.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.toFloat(),
                previousAverage = previous.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.toFloat(),
                min = current.minOfOrNull { it.value },
                max = current.maxOfOrNull { it.value },
                last = current.maxWithOrNull(compareBy({ it.epochDay }, { it.minutes }))?.value,
                count = current.size,
            )
        }
    }

    /** Anzahl Checkups (Zeitpunkte mit mindestens einem Wert) im Zeitraum. */
    fun checkupCount(entries: List<MoodEntryEntity>, from: LocalDate, toInclusive: LocalDate): Int =
        entries.filter { it.epochDay in from.toEpochDay()..toInclusive.toEpochDay() }
            .distinctBy { it.epochDay to it.minutes }
            .size
}

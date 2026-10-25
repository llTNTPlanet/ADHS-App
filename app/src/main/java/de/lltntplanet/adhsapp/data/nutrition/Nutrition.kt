package de.lltntplanet.adhsapp.data.nutrition

import de.lltntplanet.adhsapp.data.db.FoodDao
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import de.lltntplanet.adhsapp.data.settings.Sex
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.roundToInt

/** Tagesbilanz in Kilokalorien. */
data class CalorieBalance(
    /** Grundumsatz – was der Körper in Ruhe verbraucht. */
    val basal: Int,
    /** Zusätzlich verbrannt durch Bewegung und Training (laut Google Health), 0 wenn unbekannt. */
    val activity: Int,
    /** Hat Google Health für diesen Tag überhaupt Verbrauchsdaten geliefert? */
    val activityKnown: Boolean,
    /** Tägliche Anpassung, z. B. -300 zum Abnehmen. */
    val adjustment: Int,
    val eaten: Int,
) {
    /** Was heute gegessen werden darf: Grundumsatz + Bewegung + Anpassung. */
    val budget: Int get() = max(0, basal + activity + adjustment)

    /** Positiv: darf noch gegessen werden; negativ: schon drüber. */
    val remaining: Int get() = budget - eaten

    val over: Boolean get() = remaining < 0

    /** Anteil des Budgets, der schon gegessen ist (0..1, bei Überschreitung >1). */
    val eatenFraction: Float get() = if (budget == 0) 0f else eaten.toFloat() / budget
}

object NutritionMath {
    /** Grundumsatz, solange kein Profil eingetragen ist. */
    const val DEFAULT_BASAL = 2000

    /**
     * Grundumsatz nach Mifflin-St Jeor: 10·kg + 6,25·cm − 5·Alter (+5 Männer / −161 Frauen).
     * Null, wenn das Profil unvollständig ist.
     */
    fun basalMetabolicRate(sex: Sex?, birthYear: Int?, heightCm: Int?, weightKg: Double?, today: LocalDate = LocalDate.now()): Int? {
        if (sex == null || birthYear == null || heightCm == null || weightKg == null) return null
        val age = (today.year - birthYear).coerceIn(1, 120)
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        return (base + if (sex == Sex.MALE) 5.0 else -161.0).roundToInt().coerceAtLeast(500)
    }

    fun basalMetabolicRate(s: AppSettings, today: LocalDate = LocalDate.now()): Int? =
        basalMetabolicRate(s.sex, s.birthYear, s.heightCm, s.weightKg, today)

    /**
     * Bilanz eines Tages. [totalBurnedKcal] ist der Gesamtverbrauch laut Google Health (inkl. Grundumsatz);
     * alles darüber hinaus zählt als Bewegung. Für den laufenden Tag ist der Wert noch unvollständig.
     */
    fun balance(basal: Int, totalBurnedKcal: Double?, adjustment: Int, entries: List<FoodEntryEntity>): CalorieBalance {
        val activity = totalBurnedKcal?.let { max(0, (it - basal).roundToInt()) } ?: 0
        return CalorieBalance(
            basal = basal,
            activity = activity,
            activityKnown = totalBurnedKcal != null,
            adjustment = adjustment,
            eaten = entries.sumOf { it.kcal },
        )
    }

    /** Welche Mahlzeit passt zur Uhrzeit? */
    fun mealFor(time: LocalTime): MealType = when (time.hour) {
        in 4..10 -> MealType.BREAKFAST
        in 11..14 -> MealType.LUNCH
        in 17..21 -> MealType.DINNER
        else -> MealType.SNACK
    }
}

class NutritionRepository(
    private val dao: FoodDao,
    private val healthConnect: HealthConnectNutrition? = null,
    private val settings: SettingsRepository? = null,
) {
    fun observeDay(date: LocalDate): Flow<List<FoodEntryEntity>> = dao.observeDay(date.toEpochDay())

    suspend fun recent(limit: Int = 12): List<FoodEntryEntity> = dao.recent(limit)

    private suspend fun exportEnabled(): Boolean =
        healthConnect != null && settings?.current()?.healthConnectExport == true && healthConnect.available

    /** Speichert lokal und – falls eingeschaltet – auch in Health Connect (Änderung ersetzt den alten Eintrag). */
    suspend fun save(entry: FoodEntryEntity): Long {
        val id = dao.upsert(entry).let { if (entry.id != 0L) entry.id else it }
        if (exportEnabled()) healthConnect!!.write(entry.copy(id = id))
        return id
    }

    suspend fun delete(entry: FoodEntryEntity) {
        dao.delete(entry)
        if (exportEnabled()) healthConnect!!.delete(entry.id)
    }

    /** Alle Einträge eines Tages (neu) nach Health Connect schreiben, z. B. nach dem Einschalten. */
    suspend fun exportDay(date: LocalDate): Int {
        if (!exportEnabled()) return 0
        val entries = dao.forDay(date.toEpochDay())
        return entries.count { healthConnect!!.write(it) }
    }
}

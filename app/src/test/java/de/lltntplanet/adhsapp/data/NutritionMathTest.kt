package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.nutrition.NutritionMath
import de.lltntplanet.adhsapp.data.settings.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NutritionMathTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun mifflinStJeor() {
        // 10·78 + 6,25·180 − 5·32 + 5 = 1750
        assertEquals(1750, NutritionMath.basalMetabolicRate(Sex.MALE, 1994, 180, 78.0, today))
        // 10·60 + 6,25·165 − 5·40 − 161 = 1270 (gerundet von 1270,25)
        assertEquals(1270, NutritionMath.basalMetabolicRate(Sex.FEMALE, 1986, 165, 60.0, today))
        assertNull(NutritionMath.basalMetabolicRate(Sex.MALE, null, 180, 78.0, today))
    }

    private fun entry(kcal: Int, meal: MealType = MealType.LUNCH) =
        FoodEntryEntity(epochDay = today.toEpochDay(), meal = meal, name = "x", kcal = kcal)

    @Test
    fun balanceWithActivityAndDeficit() {
        val b = NutritionMath.balance(basal = 1750, totalBurnedKcal = 2300.0, adjustment = -300, entries = listOf(entry(400), entry(500)))
        assertEquals(550, b.activity)
        assertTrue(b.activityKnown)
        assertEquals(2000, b.budget)
        assertEquals(900, b.eaten)
        assertEquals(1100, b.remaining)
        assertFalse(b.over)
        assertEquals(0.45f, b.eatenFraction, 0.001f)
    }

    @Test
    fun balanceWithoutHealthDataAndOverBudget() {
        val b = NutritionMath.balance(basal = 1750, totalBurnedKcal = null, adjustment = 0, entries = listOf(entry(2000)))
        assertEquals(0, b.activity)
        assertFalse(b.activityKnown)
        assertEquals(-250, b.remaining)
        assertTrue(b.over)
        // Google Health meldet am Morgen weniger als den Grundumsatz – Bewegung darf nicht negativ werden.
        assertEquals(0, NutritionMath.balance(1750, 900.0, 0, emptyList()).activity)
    }

    @Test
    fun mealByTime() {
        assertEquals(MealType.BREAKFAST, NutritionMath.mealFor(LocalTime.of(7, 30)))
        assertEquals(MealType.LUNCH, NutritionMath.mealFor(LocalTime.of(12, 15)))
        assertEquals(MealType.SNACK, NutritionMath.mealFor(LocalTime.of(15, 30)))
        assertEquals(MealType.DINNER, NutritionMath.mealFor(LocalTime.of(19, 0)))
        assertEquals(MealType.SNACK, NutritionMath.mealFor(LocalTime.of(23, 30)))
    }
}

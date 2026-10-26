package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.nutrition.ChatFoodEstimate
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatFoodEstimateTest {
    private val json = """{"items":[{"name":"Linsensuppe","amount":"1 Teller (350 g)","kcal":420,"confidence":"mittel"},
        {"name":"Brötchen","amount":"1 Stück","kcal":150,"confidence":"hoch"}],"mealGuess":"LUNCH","note":"Portion geschätzt"}"""

    @Test
    fun parsesPlainJson() {
        val r = ChatFoodEstimate.parseAnswer(json)
        assertEquals(listOf("Linsensuppe", "Brötchen"), r.items.map { it.name })
        assertEquals(570, r.totalKcal)
        assertEquals(MealType.LUNCH, r.mealGuess)
        assertEquals("Portion geschätzt", r.note)
    }

    @Test
    fun parsesJsonInsideMarkdownAndChatter() {
        val answer = "Klar, hier ist meine Schätzung:\n\n```json\n$json\n```\n\nGuten Appetit! Sag Bescheid, wenn ich etwas anpassen soll {…}."
        val r = ChatFoodEstimate.parseAnswer(answer)
        assertEquals(2, r.items.size)
        assertEquals(MealType.LUNCH, r.mealGuess)
    }

    @Test
    fun ignoresBracesInsideStrings() {
        val r = ChatFoodEstimate.parseAnswer("""{"items":[{"name":"Suppe {scharf}","amount":"1 Schale","kcal":300,"confidence":"hoch"}],"mealGuess":null,"note":""}""")
        assertEquals("Suppe {scharf}", r.items.single().name)
        assertNull(r.mealGuess)
    }

    @Test
    fun fallsBackToLinesWithKcal() {
        val answer = """
            Das sieht nach einem Mittagessen aus:
            - **Reis**: 200 g – ca. 260 kcal
            - Hähnchenbrust, 150 g, 250 kcal
            * Soße – 80 kcal
            Gesamt: ca. 590 kcal
        """.trimIndent()
        val r = ChatFoodEstimate.parseAnswer(answer)
        assertEquals(listOf("Reis", "Hähnchenbrust", "Soße"), r.items.map { it.name })
        assertEquals(listOf(260, 250, 80), r.items.map { it.kcal })
        assertEquals("200 g", r.items[0].amount)
        assertEquals("150 g", r.items[1].amount)
        assertEquals("", r.items[2].amount)
        assertTrue(r.items.all { it.confidence == "niedrig" })
        assertEquals(MealType.LUNCH, r.mealGuess)
    }

    @Test
    fun rejectsUnusableText() {
        try {
            ChatFoodEstimate.parseAnswer("Ich kann auf dem Bild leider nichts erkennen.")
            fail("expected exception")
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun rejectsEmptyText() {
        try {
            ChatFoodEstimate.parseAnswer("   \n")
            fail("expected exception")
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("leer"))
        }
    }

    @Test
    fun textPromptContainsDescriptionAndFormat() {
        val p = ChatFoodEstimate.textPrompt("  2 Brötchen, 1 Apfel ")
        assertTrue(p.contains("„2 Brötchen, 1 Apfel“"))
        assertTrue(p.contains("NUR mit diesem JSON"))
    }

    @Test
    fun promptAsksForJsonOnly() {
        assertTrue(ChatFoodEstimate.PROMPT.contains("NUR mit diesem JSON"))
        MealType.entries.forEach { assertTrue(ChatFoodEstimate.PROMPT.contains(it.name)) }
    }
}

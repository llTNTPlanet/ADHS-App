package de.lltntplanet.adhsapp.data.nutrition

import de.lltntplanet.adhsapp.data.db.MealType

/**
 * Kostenloser Weg ohne API-Schlüssel: Das Foto wird zusammen mit [PROMPT] an eine beliebige Chat-App
 * (Gemini, ChatGPT, Claude, Copilot …) geteilt. Die Antwort kopiert der Nutzer zurück in die App
 * (oder teilt sie an die App), und [parseAnswer] macht daraus eine [FoodRecognition].
 */
object ChatFoodEstimate {
    /** Anweisung, die zusammen mit dem Foto geteilt und zusätzlich in die Zwischenablage gelegt wird. */
    val PROMPT: String = buildString {
        append("Schätze bitte die Kalorien auf diesem Foto. ")
        append("Erkenne jedes Lebensmittel bzw. Produkt (Teller, Snack oder Verpackung – bei Verpackungen Produktname und Nährwerttabelle lesen), ")
        append("schätze pro Eintrag die gegessene Menge und die Kilokalorien. Beilagen als eigene Einträge (z. B. Reis, Hähnchenbrust, Soße). ")
        append("Namen kurz und deutsch, kcal als ganze Zahl, Menge wie „250 g“ oder „1 Stück“, confidence = hoch/mittel/niedrig. ")
        append("mealGuess ist deine Vermutung zur Mahlzeit: BREAKFAST, LUNCH, DINNER, SNACK oder null.\n\n")
        append("Antworte NUR mit diesem JSON, ohne Erklärung davor oder danach:\n")
        append("{\"items\":[{\"name\":\"Reis\",\"amount\":\"200 g\",\"kcal\":260,\"confidence\":\"mittel\"}],\"mealGuess\":\"LUNCH\",\"note\":\"kurzer Hinweis\"}")
    }

    /**
     * Macht aus der Chat-Antwort eine Erkennung. Verträgt Markdown-Codeblöcke, Text vor und nach dem JSON
     * und – falls der Chat kein JSON geliefert hat – einfache Zeilen wie „Reis, 200 g, 260 kcal“.
     */
    fun parseAnswer(text: String): FoodRecognition {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw FoodVisionException(0, "Die Antwort ist leer. Kopiere die Antwort aus dem Chat und füge sie hier ein.")
        extractJson(trimmed)?.let { json ->
            runCatching { FoodVisionClient.parseRecognition(json) }.getOrNull()?.let { return it }
        }
        val items = parseLines(trimmed)
        if (items.isEmpty()) {
            throw FoodVisionException(
                0,
                "In der Antwort stand kein auswertbares Ergebnis. Bitte den Chat um die Antwort im JSON-Format " +
                    "(der Text aus der Zwischenablage erklärt es) und füge sie dann noch einmal ein.",
            )
        }
        return FoodRecognition(items, mealGuess = guessMeal(trimmed), note = "Aus einer Chat-Antwort gelesen – bitte prüfen.")
    }

    /** Das äußerste {…}-Objekt aus der Antwort, auch wenn es in ```json … ``` steckt oder Text drumherum steht. */
    internal fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private val kcalLine = Regex("""^[\s\-*•·\d.)]*(.+?)\s*[:\-–|,;]\s*(?:(.+?)\s*[:\-–|,;]\s*)?~?\s*(?:ca\.?\s*)?(\d{1,5})\s*(?:kcal|Kalorien|cal)\b.*$""", RegexOption.IGNORE_CASE)
    private val totalWords = listOf("gesamt", "summe", "total", "insgesamt", "zusammen")

    /** Notlösung für Freitext: eine Zeile pro Lebensmittel mit kcal-Angabe. Summenzeilen werden übersprungen. */
    internal fun parseLines(text: String): List<RecognizedFood> =
        text.lines().mapNotNull { raw ->
            val line = raw.replace("**", "").trim()
            val m = kcalLine.find(line) ?: return@mapNotNull null
            val name = m.groupValues[1].trim().trim('"', '„', '“', '*')
            if (name.isEmpty() || totalWords.any { name.contains(it, ignoreCase = true) }) return@mapNotNull null
            val amount = m.groupValues[2].trim()
            val kcal = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            RecognizedFood(name = name, amount = amount, kcal = kcal, confidence = "niedrig")
        }

    private fun guessMeal(text: String): MealType? {
        val lower = text.lowercase()
        return when {
            "frühstück" in lower || "breakfast" in lower -> MealType.BREAKFAST
            "mittag" in lower || "lunch" in lower -> MealType.LUNCH
            "abend" in lower || "dinner" in lower -> MealType.DINNER
            "snack" in lower || "zwischendurch" in lower -> MealType.SNACK
            else -> null
        }
    }
}

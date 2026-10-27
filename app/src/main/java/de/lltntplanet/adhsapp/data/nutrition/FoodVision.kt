package de.lltntplanet.adhsapp.data.nutrition

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.google.GoogleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Ein erkanntes Lebensmittel auf dem Foto. */
@Serializable
data class RecognizedFood(
    val name: String,
    /** Geschätzte Menge, z. B. "250 g" oder "1 Stück". */
    val amount: String = "",
    val kcal: Int,
    /** Wie sicher die Schätzung ist: "hoch", "mittel", "niedrig". */
    val confidence: String = "mittel",
)

@Serializable
data class FoodRecognition(
    val items: List<RecognizedFood>,
    /** Vorschlag, zu welcher Mahlzeit das Foto gehört. */
    val mealGuess: MealType? = null,
    /** Kurzer Hinweis, z. B. "Nährwerte von der Packung gelesen, Portion geschätzt". */
    val note: String = "",
    /** Welches Modell geantwortet hat (zur Diagnose in „Verbindung testen“); null beim Chat-Weg. */
    val model: String? = null,
) {
    val totalKcal: Int get() = items.sumOf { it.kcal }
}

class FoodVisionException(val code: Int, message: String) : Exception(message)

/**
 * Erkennt Mahlzeiten und Verpackungen auf Fotos mit Claude (Messages API, Bildeingabe + strukturierte Ausgabe).
 * Das Foto wird nur für diese eine Anfrage übertragen und nirgends gespeichert.
 */
class FoodVisionClient(
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://api.anthropic.com/v1/messages",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : FoodRecognizer {
    override val configured: Boolean get() = !apiKey().isNullOrBlank()

    /**
     * [jpeg] ist das (verkleinerte) Foto, [hint] ein optionaler Freitext des Nutzers
     * ("halbe Portion", "Packung ist 400 g").
     */
    override suspend fun analyze(jpeg: ByteArray, hint: String?): FoodRecognition =
        request(java.util.Base64.getEncoder().encodeToString(jpeg), FoodVisionPrompt.photoText(hint))

    override suspend fun analyzeText(description: String): FoodRecognition =
        request(null, FoodVisionPrompt.descriptionText(description))

    private suspend fun request(imageBase64: String?, userText: String): FoodRecognition {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw FoodVisionException(0, "Kein Claude-API-Schlüssel hinterlegt. Trage ihn in den Einstellungen unter „Ernährung“ ein.")
        val body = buildRequest(imageBase64, userText)
        val (code, text) = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl)
                .header("x-api-key", key)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", FALLBACK_BETA)
                .header("content-type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (code !in 200..299) throw FoodVisionException(code, errorMessage(code, text))
        return parseResponse(text)
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val API_VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val JSON = "application/json".toMediaType()

        /** [imageBase64] null → reine Textanfrage (Beschreibung des Essens in [userText]). */
        internal fun buildRequest(imageBase64: String?, userText: String): String {
            val schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("items") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("amount") { put("type", "string") }
                                putJsonObject("kcal") { put("type", "integer") }
                                putJsonObject("confidence") {
                                    put("type", "string")
                                    putJsonArray("enum") { add(JsonPrimitive("hoch")); add(JsonPrimitive("mittel")); add(JsonPrimitive("niedrig")) }
                                }
                            }
                            putJsonArray("required") { listOf("name", "amount", "kcal", "confidence").forEach { add(JsonPrimitive(it)) } }
                            put("additionalProperties", false)
                        }
                    }
                    putJsonObject("mealGuess") {
                        putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }
                        putJsonArray("enum") { MealType.entries.forEach { add(JsonPrimitive(it.name)) }; add(kotlinx.serialization.json.JsonNull) }
                    }
                    putJsonObject("note") { put("type", "string") }
                }
                putJsonArray("required") { listOf("items", "mealGuess", "note").forEach { add(JsonPrimitive(it)) } }
                put("additionalProperties", false)
            }
            val request = buildJsonObject {
                put("model", MODEL)
                put("max_tokens", 4000)
                put("fallbacks", "default")
                put("system", FoodVisionPrompt.SYSTEM)
                putJsonObject("output_config") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", schema)
                    }
                }
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                if (imageBase64 != null) {
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", "image/jpeg")
                                                put("data", imageBase64)
                                            }
                                        },
                                    )
                                }
                                add(buildJsonObject { put("type", "text"); put("text", userText) })
                            }
                        },
                    )
                }
            }
            return GoogleJson.encodeToString(JsonObject.serializer(), request)
        }

        internal fun parseResponse(body: String): FoodRecognition {
            val root = GoogleJson.parseToJsonElement(body).jsonObject
            val stop = root["stop_reason"]?.jsonPrimitive?.content
            if (stop == "refusal") {
                val why = root["stop_details"]?.jsonObject?.get("explanation")?.jsonPrimitive?.content
                throw FoodVisionException(200, "Die Erkennung wurde abgelehnt" + (why?.let { ": $it" } ?: "."))
            }
            val text = root["content"]?.jsonArray
                ?.map { it.jsonObject }
                ?.lastOrNull { it["type"]?.jsonPrimitive?.content == "text" }
                ?.get("text")?.jsonPrimitive?.content
                ?: throw FoodVisionException(200, "Keine Antwort erhalten (stop_reason: $stop).")
            return parseRecognition(text)
        }

        internal fun parseRecognition(json: String): FoodRecognition {
            val obj = GoogleJson.parseToJsonElement(json).jsonObject
            val items = obj["items"]?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val name = o["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RecognizedFood(
                    name = name,
                    amount = o["amount"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    kcal = o["kcal"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0,
                    confidence = o["confidence"]?.jsonPrimitive?.content ?: "mittel",
                )
            }
            val meal = obj["mealGuess"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                ?.let { v -> MealType.entries.find { it.name == v } }
            return FoodRecognition(items, meal, obj["note"]?.jsonPrimitive?.content.orEmpty())
        }

        private fun errorMessage(code: Int, body: String): String {
            val apiMessage = runCatching {
                GoogleJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            return when (code) {
                401 -> "Der API-Schlüssel ist ungültig. Bitte in den Einstellungen unter „Ernährung“ prüfen."
                403 -> "Der API-Schlüssel hat keine Berechtigung" + (apiMessage?.let { ": $it" } ?: ".")
                429 -> "Zu viele Anfragen oder Guthaben aufgebraucht – bitte kurz warten oder Guthaben prüfen."
                in 500..599 -> "Der Erkennungsdienst ist gerade nicht erreichbar (HTTP $code). Bitte später noch einmal."
                else -> "Erkennung fehlgeschlagen (HTTP $code)" + (apiMessage?.let { ": $it" } ?: "")
            }
        }
    }
}

/** Gemeinsame Anweisung für alle Erkenner. */
object FoodVisionPrompt {
    const val SYSTEM =
        "Du bist eine Ernährungsassistenz in einer deutschen App. Der Nutzer fotografiert sein Essen " +
                "oder beschreibt es in Worten: " +
                "einen Teller, eine Schüssel, einen Snack oder die Verpackung eines Produkts (Vorder- oder Rückseite mit Nährwerttabelle). " +
                "Erkenne jedes Lebensmittel bzw. Produkt auf dem Bild oder in der Beschreibung und schätze pro Eintrag die gegessene Menge und die Kilokalorien. " +
                "Bei Beschreibungen: Übernimm genannte Mengen, sonst nimm übliche Portionen an und sag das in note; " +
                "mehrere Lebensmittel in einem Satz oder einer Liste werden einzelne Einträge. " +
                "Bei Verpackungen: Lies Produktname und Nährwerte von der Packung; ist die Nährwerttabelle sichtbar, rechne mit ihr; " +
                "nimm als Menge eine typische Portion bzw. den Packungsinhalt, wenn er ablesbar ist, und sag das in note. " +
                "Bei Tellern: Schätze die Portionsgröße anhand von Teller, Besteck und Händen. " +
                "Fasse Beilagen als eigene Einträge zusammen (z. B. „Reis“, „Hähnchenbrust“, „Soße“). " +
                "Namen kurz und deutsch, kcal als ganze Zahl, amount wie „250 g“ oder „1 Stück“. " +
                "mealGuess ist eine Vermutung zur Mahlzeit aus Art und Uhrzeit, sonst null. " +
                "Ist kein Essen erkennbar, gib eine leere items-Liste und erkläre es kurz in note."

    fun photoText(hint: String?): String = buildString {
        append("Bitte erkenne das Essen auf dem Foto und schätze die Kalorien.")
        if (!hint.isNullOrBlank()) append(" Hinweis des Nutzers: ").append(hint.trim())
    }

    fun descriptionText(description: String): String =
        "Bitte schätze die Kalorien für folgendes Essen, das der Nutzer beschreibt:\n" + description.trim()
}

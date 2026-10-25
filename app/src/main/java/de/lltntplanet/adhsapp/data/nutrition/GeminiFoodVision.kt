package de.lltntplanet.adhsapp.data.nutrition

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.google.GoogleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Gemeinsame Schnittstelle der Foto-Erkenner. */
interface FoodRecognizer {
    val configured: Boolean
    suspend fun analyze(jpeg: ByteArray, hint: String? = null): FoodRecognition
}

/**
 * Erkennung über die Gemini API (Google AI Studio). Das Gratis-Kontingent reicht für viele Fotos am Tag,
 * ein Konto ohne Zahlungsdaten genügt. Foto wird nur für die Anfrage übertragen.
 */
class GeminiFoodVisionClient(
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : FoodRecognizer {
    override val configured: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun analyze(jpeg: ByteArray, hint: String?): FoodRecognition {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw FoodVisionException(0, "Kein Gemini-API-Schlüssel hinterlegt. Trage ihn in den Einstellungen unter „Ernährung“ ein.")
        val body = buildRequest(java.util.Base64.getEncoder().encodeToString(jpeg), hint)
        var lastError: FoodVisionException? = null
        for (model in MODELS) {
            val (code, text) = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .header("content-type", "application/json")
                    .post(body.toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
            if (code in 200..299) return parseResponse(text)
            lastError = FoodVisionException(code, errorMessage(code, text))
            // Modell nicht (mehr) verfügbar → nächstes probieren; alles andere ist endgültig.
            if (code != 404) throw lastError
        }
        throw lastError ?: FoodVisionException(0, "Erkennung fehlgeschlagen")
    }

    companion object {
        /** Reihenfolge: zuerst das neueste kostenlose Flash-Modell, dann Ersatz, falls es abgeschaltet wurde. */
        val MODELS = listOf("gemini-2.5-flash", "gemini-3.8-flash", "gemini-2.5-flash-lite")
        private val JSON = "application/json".toMediaType()

        internal fun buildRequest(imageBase64: String, hint: String?): String {
            val userText = buildString {
                append("Bitte erkenne das Essen auf dem Foto und schätze die Kalorien.")
                if (!hint.isNullOrBlank()) append(" Hinweis des Nutzers: ").append(hint.trim())
            }
            val schema = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("items") {
                        put("type", "ARRAY")
                        putJsonObject("items") {
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "STRING") }
                                putJsonObject("amount") { put("type", "STRING") }
                                putJsonObject("kcal") { put("type", "INTEGER") }
                                putJsonObject("confidence") {
                                    put("type", "STRING")
                                    putJsonArray("enum") { listOf("hoch", "mittel", "niedrig").forEach { add(JsonPrimitive(it)) } }
                                }
                            }
                            putJsonArray("required") { listOf("name", "amount", "kcal", "confidence").forEach { add(JsonPrimitive(it)) } }
                        }
                    }
                    putJsonObject("mealGuess") {
                        put("type", "STRING")
                        put("nullable", true)
                        putJsonArray("enum") { MealType.entries.forEach { add(JsonPrimitive(it.name)) } }
                    }
                    putJsonObject("note") { put("type", "STRING") }
                }
                putJsonArray("required") { listOf("items", "note").forEach { add(JsonPrimitive(it)) } }
            }
            val request = buildJsonObject {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", FoodVisionPrompt.SYSTEM) }) }
                }
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", userText) })
                                add(
                                    buildJsonObject {
                                        putJsonObject("inline_data") {
                                            put("mime_type", "image/jpeg")
                                            put("data", imageBase64)
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                    put("temperature", 0.2)
                }
            }
            return GoogleJson.encodeToString(JsonObject.serializer(), request)
        }

        internal fun parseResponse(body: String): FoodRecognition {
            val root = GoogleJson.parseToJsonElement(body).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: run {
                    val reason = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
                    throw FoodVisionException(200, "Die Erkennung wurde abgelehnt" + (reason?.let { " ($it)" } ?: "") + ".")
                }
            val finish = candidate["finishReason"]?.jsonPrimitive?.content
            val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }
                ?: throw FoodVisionException(200, "Keine Antwort erhalten (finishReason: $finish).")
            return FoodVisionClient.parseRecognition(text)
        }

        private fun errorMessage(code: Int, body: String): String {
            val apiMessage = runCatching {
                GoogleJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            return when (code) {
                400, 401, 403 -> "Der Gemini-API-Schlüssel ist ungültig oder nicht freigeschaltet" + (apiMessage?.let { ": $it" } ?: ".")
                429 -> "Das kostenlose Kontingent ist für den Moment aufgebraucht – bitte eine Minute warten und noch einmal versuchen."
                404 -> "Das Modell ist nicht verfügbar" + (apiMessage?.let { ": $it" } ?: ".")
                in 500..599 -> "Der Erkennungsdienst ist gerade nicht erreichbar (HTTP $code). Bitte später noch einmal."
                else -> "Erkennung fehlgeschlagen (HTTP $code)" + (apiMessage?.let { ": $it" } ?: "")
            }
        }
    }
}

/** Wählt den eingestellten Erkenner (Gemini kostenlos oder Claude). */
class FoodVisionRouter(
    private val provider: () -> FoodVisionProvider,
    private val gemini: FoodRecognizer,
    private val claude: FoodRecognizer,
) : FoodRecognizer {
    private val active: FoodRecognizer get() = if (provider() == FoodVisionProvider.CLAUDE) claude else gemini

    override val configured: Boolean get() = active.configured

    override suspend fun analyze(jpeg: ByteArray, hint: String?): FoodRecognition = active.analyze(jpeg, hint)
}

enum class FoodVisionProvider(val label: String, val keyUrl: String, val keyPlaceholder: String) {
    GEMINI("Gemini (kostenlos)", "https://aistudio.google.com/apikey", "AIza…"),
    CLAUDE("Claude (Anthropic)", "https://platform.claude.com/settings/keys", "sk-ant-…"),
}

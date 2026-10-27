package de.lltntplanet.adhsapp.data.nutrition

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.google.GoogleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** Foto erkennen; [hint] ist ein optionaler Freitext des Nutzers ("halbe Portion"). */
    suspend fun analyze(jpeg: ByteArray, hint: String? = null): FoodRecognition
    /** Kalorien aus einer Beschreibung in Worten schätzen ("2 Brötchen mit Käse, 1 Apfel, Milchkaffee"). */
    suspend fun analyzeText(description: String): FoodRecognition
}

/**
 * Erkennung über die Gemini API (Google AI Studio). Das Gratis-Kontingent reicht für viele Fotos am Tag,
 * ein Konto ohne Zahlungsdaten genügt. Foto wird nur für die Anfrage übertragen.
 */
class GeminiFoodVisionClient(
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Erkennt eine still getrennte HTTP/2-Verbindung (Mobilfunk, Router), bevor eine Anfrage darauf hängen bleibt.
        .pingInterval(10, TimeUnit.SECONDS)
        .build(),
    /** Wartezeit vor dem einmaligen Wiederholungsversuch, wenn Google „überlastet“ (HTTP 503) meldet. */
    private val overloadRetryDelayMs: Long = 3_000,
) : FoodRecognizer {
    override val configured: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun analyze(jpeg: ByteArray, hint: String?): FoodRecognition =
        request(java.util.Base64.getEncoder().encodeToString(jpeg), FoodVisionPrompt.photoText(hint))

    override suspend fun analyzeText(description: String): FoodRecognition =
        request(null, FoodVisionPrompt.descriptionText(description))

    /** Zuletzt bei Google abgefragte Modellliste (Kandidaten in Reihenfolge) und Zeitpunkt der Abfrage. */
    @Volatile private var cachedModels: Pair<List<String>, Long>? = null

    /**
     * Welche Modelle probiert werden: Google liefert die Liste der für diesen Schlüssel verfügbaren Modelle
     * (`GET /models`); daraus werden die bevorzugten in [MODELS]-Reihenfolge genommen und weitere Flash-Modelle
     * angehängt. Schlägt die Abfrage fehl, bleibt es bei [MODELS]. Die Liste wird eine Stunde zwischengespeichert.
     */
    internal suspend fun candidateModels(key: String, forceRefresh: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        cachedModels?.let { (models, at) -> if (!forceRefresh && now - at < MODEL_LIST_TTL_MS) return models }
        val available = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/models?pageSize=1000")
                    .header("x-goog-api-key", key)
                    .get()
                    .build()
                client.newCall(request).execute().use { if (it.isSuccessful) parseModelList(it.body?.string().orEmpty()) else null }
            }
        } catch (e: java.io.IOException) {
            null
        }
        val models = available?.let { selectModels(it) }?.takeIf { it.isNotEmpty() } ?: MODELS
        cachedModels = models to now
        return models
    }

    private suspend fun request(imageBase64: String?, userText: String): FoodRecognition {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw FoodVisionException(0, "Kein Gemini-API-Schlüssel hinterlegt. Trage ihn in den Einstellungen unter „Ernährung“ ein.")
        var lastError: FoodVisionException? = null
        val attempts = mutableListOf<String>()
        var retriedFreshConnection = false
        val models = candidateModels(key)
        for (model in models) {
            val body = buildRequest(imageBase64, userText, model)
            var retriedOverload = false
            while (true) {
                val started = System.nanoTime()
                val (code, text) = try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url("$baseUrl/models/$model:generateContent")
                            .header("x-goog-api-key", key)
                            .header("content-type", "application/json")
                            .post(body.toRequestBody(JSON))
                            .build()
                        client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                    }
                } catch (e: java.io.IOException) {
                    val secs = (System.nanoTime() - started) / 1_000_000_000
                    if (e is java.net.SocketTimeoutException && !retriedFreshConnection) {
                        // Erste Zeitüberschreitung: oft hängt nur eine alte, still getrennte Verbindung aus dem Pool.
                        // Pool leeren und dasselbe Modell einmal über eine frische Verbindung probieren.
                        retriedFreshConnection = true
                        // Schließt Sockets und darf deshalb nicht auf dem Haupt-Thread laufen (NetworkOnMainThreadException).
                        withContext(Dispatchers.IO) { client.connectionPool.evictAll() }
                        attempts += "$model: keine Antwort nach $secs s, neuer Versuch mit frischer Verbindung"
                        continue
                    }
                    attempts += "$model: ${shortNetworkMessage(e)} nach $secs s"
                    // Zeitüberschreitung oder Netzfehler: nächstes (schnelleres) Modell probieren.
                    lastError = FoodVisionException(-1, networkMessage(e, attempts))
                    break
                }
                if (code in 200..299) return parseResponse(text).copy(model = model)
                if (code == 503 && !retriedOverload) {
                    // Überlastung ist meist nur kurz: einmal kurz warten und dasselbe Modell noch einmal probieren.
                    retriedOverload = true
                    attempts += "$model: überlastet (HTTP 503), neuer Versuch nach ${overloadRetryDelayMs / 1000} s"
                    delay(overloadRetryDelayMs)
                    continue
                }
                attempts += "$model: HTTP $code"
                lastError = FoodVisionException(code, errorMessage(code, text, attempts))
                // Modell nicht (mehr) verfügbar oder überlastet → nächstes probieren; alles andere ist endgültig.
                if (code != 404 && code != 503) throw lastError
                break
            }
        }
        // Alle Modelle 404: Die zwischengespeicherte Liste ist offenbar veraltet, beim nächsten Mal neu abfragen.
        if (lastError?.code == 404) cachedModels = null
        throw lastError ?: FoodVisionException(0, "Erkennung fehlgeschlagen")
    }

    companion object {
        /**
         * Bevorzugte Modelle in Reihenfolge, alle mit Gratis-Kontingent: zuerst die Lite-Modelle (schnell, selten überlastet,
         * höheres Kontingent, für eine Kalorienschätzung ausreichend), dann die normalen Flash-Modelle. Die 2.5er-Modelle
         * stehen nur noch Konten offen, die sie früher schon genutzt haben; neue Schlüssel bekommen dafür 404.
         * Welche davon es wirklich gibt, fragt [candidateModels] bei Google ab.
         */
        val MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
        )
        /** So lange gilt die bei Google abgefragte Modellliste. */
        const val MODEL_LIST_TTL_MS = 60L * 60 * 1000
        /** Stabile Text-/Bildmodelle der Flash-Familie, z. B. gemini-3.5-flash-lite oder gemini-4.2-flash (keine Previews, kein TTS/Live/Image). */
        private val FLASH_MODEL = Regex("""^gemini-(\d+(?:\.\d+)?)-flash(-lite)?$""")

        /** Modellnamen (ohne „models/“-Präfix) aus der Antwort von `GET /models`, nur solche mit generateContent. */
        internal fun parseModelList(body: String): List<String> =
            runCatching {
                GoogleJson.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .filter { m ->
                        m["supportedGenerationMethods"]?.jsonArray?.any { it.jsonPrimitive.content == "generateContent" } ?: true
                    }
                    .mapNotNull { it["name"]?.jsonPrimitive?.content?.removePrefix("models/") }
            }.getOrDefault(emptyList())

        /**
         * Kandidaten aus den verfügbaren Modellen: erst die bevorzugten aus [MODELS] (in deren Reihenfolge), dann weitere
         * stabile Flash-Modelle (Lite zuerst, neuere Versionen zuerst). Unbekannte oder Spezialmodelle (TTS, Live, Bild) nie.
         */
        internal fun selectModels(available: List<String>): List<String> {
            val set = available.toSet()
            val preferred = MODELS.filter { it in set }
            val others = available
                .filter { it !in preferred }
                .mapNotNull { name -> FLASH_MODEL.matchEntire(name)?.let { m -> Triple(name, m.groupValues[2].isNotEmpty(), m.groupValues[1].toDoubleOrNull() ?: 0.0) } }
                .sortedWith(compareByDescending<Triple<String, Boolean, Double>> { it.second }.thenByDescending { it.third })
                .map { it.first }
            return preferred + others
        }
        /** Pro Versuch höchstens so lange auf die Antwort warten; danach wird das nächste Modell probiert. */
        const val READ_TIMEOUT_SECONDS = 25L
        private val JSON = "application/json".toMediaType()

        /** [imageBase64] null → reine Textanfrage (Beschreibung des Essens in [userText]). */
        internal fun buildRequest(imageBase64: String?, userText: String, model: String = MODELS[0]): String {
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
                                if (imageBase64 != null) {
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inline_data") {
                                                put("mime_type", "image/jpeg")
                                                put("data", imageBase64)
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                    put("temperature", 0.2)
                    // Die 2.5er-Modelle „denken“ sonst erst lange nach – für eine Kalorienschätzung unnötig und der Hauptgrund für Zeitüberschreitungen.
                    // Neuere Modelle steuern das über thinkingLevel; dort bleibt es bei der Voreinstellung (Lite-Modelle: minimal).
                    if ("2.5" in model) putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
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

        internal fun networkMessage(e: java.io.IOException, attempts: List<String> = emptyList()): String {
            val detail = if (attempts.isEmpty()) "" else " Versuche: ${attempts.joinToString("; ")}."
            return when (e) {
                is java.net.SocketTimeoutException ->
                    "Zeitüberschreitung – die Verbindung steht, aber Gemini hat nicht geantwortet. " +
                        "Meist ist das Gratis-Kontingent gerade langsam; bitte gleich noch einmal versuchen." + detail
                is java.net.UnknownHostException -> "Keine Internetverbindung – generativelanguage.googleapis.com ist nicht erreichbar." + detail
                else -> "Netzwerkfehler: ${e.message ?: e.javaClass.simpleName}." + detail
            }
        }

        private fun shortNetworkMessage(e: java.io.IOException): String = when (e) {
            is java.net.SocketTimeoutException -> "keine Antwort"
            is java.net.UnknownHostException -> "Server nicht erreichbar"
            else -> e.message ?: e.javaClass.simpleName
        }

        private fun errorMessage(code: Int, body: String, attempts: List<String> = emptyList()): String {
            val apiMessage = runCatching {
                GoogleJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            val detail = if (attempts.isEmpty()) "" else " Versuche: ${attempts.joinToString("; ")}."
            return baseErrorMessage(code, apiMessage) + detail
        }

        private fun baseErrorMessage(code: Int, apiMessage: String?): String {
            return when {
                apiMessage?.contains("location is not supported", ignoreCase = true) == true ->
                    "Google bietet die kostenlose Gemini-API in deinem Land nicht an (Standort nicht unterstützt). " +
                        "In Google AI Studio ein Abrechnungskonto hinterlegen oder in der App den Weg „Chat-App“ wählen."
                else -> errorForCode(code, apiMessage)
            }
        }

        private fun errorForCode(code: Int, apiMessage: String?): String =
            when (code) {
                400, 401, 403 -> "Der Gemini-API-Schlüssel ist ungültig oder nicht freigeschaltet" + (apiMessage?.let { ": $it" } ?: ".")
                429 -> "Das kostenlose Kontingent ist für den Moment aufgebraucht – bitte eine Minute warten und noch einmal versuchen."
                404 -> "Das Modell ist nicht verfügbar" + (apiMessage?.let { ": $it" } ?: ".")
                503 -> "Gemini ist gerade überlastet (HTTP 503), auch nach Wiederholung und Modellwechsel. Bitte in einer Minute noch einmal."
                in 500..599 -> "Der Erkennungsdienst ist gerade nicht erreichbar (HTTP $code). Bitte später noch einmal."
                else -> "Erkennung fehlgeschlagen (HTTP $code)" + (apiMessage?.let { ": $it" } ?: "")
            }
    }
}

/** Wählt den eingestellten Erkenner (Gemini kostenlos oder Claude). Der Chat-Weg läuft ohne Erkenner über das Teilen-Menü. */
class FoodVisionRouter(
    private val provider: () -> FoodVisionProvider,
    private val gemini: FoodRecognizer,
    private val claude: FoodRecognizer,
) : FoodRecognizer {
    private val active: FoodRecognizer? get() = when (provider()) {
        FoodVisionProvider.CHAT -> null
        FoodVisionProvider.GEMINI -> gemini
        FoodVisionProvider.CLAUDE -> claude
    }

    /** Der Chat-Weg braucht keinen Schlüssel und gilt immer als eingerichtet. */
    override val configured: Boolean get() = active?.configured ?: true

    override suspend fun analyze(jpeg: ByteArray, hint: String?): FoodRecognition =
        (active ?: throw FoodVisionException(0, "Beim Chat-Weg wird das Foto geteilt und die Antwort eingefügt."))
            .analyze(jpeg, hint)

    override suspend fun analyzeText(description: String): FoodRecognition =
        (active ?: throw FoodVisionException(0, "Beim Chat-Weg wird die Beschreibung geteilt und die Antwort eingefügt."))
            .analyzeText(description)
}

enum class FoodVisionProvider(val label: String, val keyUrl: String, val keyPlaceholder: String) {
    /** Foto an eine beliebige Chat-App teilen, Antwort einfügen – kostenlos, ohne Schlüssel. */
    CHAT("Chat-App (kostenlos, ohne Schlüssel)", "", ""),
    GEMINI("Gemini-API (kostenlos, mit Schlüssel)", "https://aistudio.google.com/apikey", "AIza…"),
    CLAUDE("Claude-API (Anthropic)", "https://platform.claude.com/settings/keys", "sk-ant-…");

    val needsKey: Boolean get() = keyUrl.isNotEmpty()
}

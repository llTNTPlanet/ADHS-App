package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionException
import de.lltntplanet.adhsapp.data.nutrition.GeminiFoodVisionClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiFoodVisionClientTest {
    private lateinit var server: MockWebServer

    /** Antworten für generateContent in Reihenfolge; `GET /models` wird getrennt mit [modelList] beantwortet. */
    private val queue = ArrayDeque<MockResponse>()
    private val generateRequests = java.util.concurrent.LinkedBlockingQueue<okhttp3.mockwebserver.RecordedRequest>()
    private var modelListRequests = 0
    private var modelList: MockResponse = modelsResponse(GeminiFoodVisionClient.MODELS)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method == "GET" && request.path!!.startsWith("/v1beta/models")) {
                    modelListRequests++
                    return modelList
                }
                generateRequests.add(request)
                return queue.removeFirstOrNull() ?: MockResponse().setResponseCode(500).setBody("unexpected request")
            }
        }
    }

    private fun enqueue(response: MockResponse) = queue.addLast(response)
    private fun takeGenerate() = generateRequests.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
    private val generateCount get() = generateRequests.size

    private fun modelsResponse(names: List<String>, methods: List<String> = listOf("generateContent")): MockResponse {
        val methodsJson = methods.joinToString(",") { "\"" + it + "\"" }
        val models = names.joinToString(",") { n -> """{"name":"models/$n","supportedGenerationMethods":[$methodsJson]}""" }
        return MockResponse().setBody("""{"models":[$models]}""")
    }

    private fun client(key: String? = "AIza-test") =
        GeminiFoodVisionClient({ key }, server.url("/v1beta").toString(), overloadRetryDelayMs = 0)

    private val answer = """{"items":[{"name":"Müsli","amount":"80 g","kcal":300,"confidence":"hoch"}],"mealGuess":"BREAKFAST","note":"Packung gelesen"}"""

    private fun ok(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}],"role":"model"},"finishReason":"STOP"}]}""",
    )

    @Test
    fun sendsInlineImageWithSchemaAndParses() = runTest {
        enqueue(ok(answer))
        val result = client().analyze(byteArrayOf(1, 2, 3))
        assertEquals("Müsli", result.items.single().name)
        assertEquals(300, result.totalKcal)
        assertEquals(MealType.BREAKFAST, result.mealGuess)

        val request = takeGenerate()
        assertEquals("/v1beta/models/${GeminiFoodVisionClient.MODELS[0]}:generateContent", request.path)
        assertEquals("AIza-test", request.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[1].jsonObject["inline_data"]!!.jsonObject
        assertEquals("image/jpeg", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", inline["data"]!!.jsonPrimitive.content)
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", gen["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("OBJECT", gen["responseSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["system_instruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Ernährungsassistenz"))
    }

    @Test
    fun textDescriptionIsSentWithoutImage() = runTest {
        enqueue(ok(answer))
        val result = client().analyzeText("2 Brötchen mit Käse, 1 Apfel")
        assertEquals(300, result.totalKcal)

        val body = Json.parseToJsonElement(takeGenerate().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        val text = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("2 Brötchen mit Käse, 1 Apfel"))
        assertTrue(text.contains("beschreibt"))
        assertEquals("application/json", body["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
    }

    private fun fastClient(timeoutMs: Long) = GeminiFoodVisionClient(
        { "AIza-test" },
        server.url("/v1beta").toString(),
        okhttp3.OkHttpClient.Builder().readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        overloadRetryDelayMs = 0,
    )

    private fun noResponse() = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)

    @Test
    fun firstTimeoutRetriesSameModelOnFreshConnectionAndThinkingIsDisabledOnlyFor25Models() = runTest {
        enqueue(noResponse())
        enqueue(ok(answer))
        val result = fastClient(300).analyzeText("1 Apfel")
        assertEquals(300, result.totalKcal)
        val first = takeGenerate()
        assertTrue(first.path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        val firstBody = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        // Nur die 2.5er-Modelle bekommen thinkingBudget 0; neuere Modelle bleiben bei ihrer Voreinstellung.
        assertEquals(null, firstBody["generationConfig"]!!.jsonObject["thinkingConfig"])
        val legacy = Json.parseToJsonElement(GeminiFoodVisionClient.buildRequest(null, "1 Apfel", "gemini-2.5-flash-lite")).jsonObject
        assertEquals("0", legacy["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        // Zweiter Versuch geht noch einmal an dasselbe Modell (frische Verbindung), nicht sofort an das nächste.
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
    }

    @Test
    fun secondTimeoutFallsBackToNextModel() = runTest {
        enqueue(noResponse())
        enqueue(noResponse())
        enqueue(ok(answer))
        val result = fastClient(300).analyzeText("1 Apfel")
        assertEquals(300, result.totalKcal)
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun allModelsTimingOutGivesReadableMessageWithAttempts() = runTest {
        repeat(GeminiFoodVisionClient.MODELS.size + 1) { enqueue(noResponse()) }
        try {
            fastClient(200).analyzeText("1 Apfel")
            fail("expected timeout")
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("Zeitüberschreitung"))
            assertTrue(e.message!!.contains("frischer Verbindung"))
            GeminiFoodVisionClient.MODELS.forEach { assertTrue(e.message!!.contains(it)) }
        }
        assertEquals(GeminiFoodVisionClient.MODELS.size + 1, generateCount)
    }

    private fun overloaded() = MockResponse().setResponseCode(503).setBody("""{"error":{"message":"The model is overloaded. Please try again later."}}""")

    @Test
    fun liteModelIsTriedFirst() {
        assertEquals("gemini-3.5-flash-lite", GeminiFoodVisionClient.MODELS[0])
    }

    @Test
    fun onlyModelsGoogleListsAreTriedInPreferredOrder() = runTest {
        // Google kennt für diesen Schlüssel nur das zweite bevorzugte Modell und ein neueres, unbekanntes Flash-Modell.
        modelList = modelsResponse(listOf("gemini-4.2-flash", "gemini-3.8-flash-tts", "gemini-3.8-flash", GeminiFoodVisionClient.MODELS[1], "gemini-4.2-flash-lite"))
        enqueue(ok(answer))
        val result = client().analyzeText("1 Apfel")
        assertEquals(GeminiFoodVisionClient.MODELS[1], result.model)
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
        assertEquals(1, modelListRequests)

        // Liste wird zwischengespeichert: zweite Anfrage fragt /models nicht erneut.
        enqueue(ok(answer))
        client().analyzeText("1 Apfel")
        assertEquals(2, modelListRequests) // neue Client-Instanz → eigener Cache
    }

    @Test
    fun selectModelsPrefersKnownThenOtherStableFlashModelsLiteFirst() {
        val selected = GeminiFoodVisionClient.selectModels(
            listOf("gemini-4.2-flash", "gemini-3.8-flash-tts", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4.2-flash-lite", "gemini-5-flash-preview", "gemini-3.1-flash-lite-image"),
        )
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4.2-flash-lite", "gemini-4.2-flash"), selected)
        assertEquals(listOf("gemini-3.8-flash"), GeminiFoodVisionClient.parseModelList("""{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]}]}"""))
    }

    @Test
    fun modelListFailureFallsBackToPreferredList() = runTest {
        modelList = MockResponse().setResponseCode(403).setBody("{}")
        enqueue(ok(answer))
        val result = client().analyzeText("1 Apfel")
        assertEquals(GeminiFoodVisionClient.MODELS[0], result.model)
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
    }

    @Test
    fun cachedModelListIsRefreshedAfterAllModels404() = runTest {
        val c = client()
        modelList = modelsResponse(listOf("gemini-3.8-flash"))
        enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        try {
            c.analyzeText("1 Apfel")
            fail("expected 404")
        } catch (e: FoodVisionException) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("gemini-3.8-flash"))
        }
        modelList = modelsResponse(listOf("gemini-3.5-flash-lite"))
        enqueue(ok(answer))
        assertEquals("gemini-3.5-flash-lite", c.analyzeText("1 Apfel").model)
        assertEquals(2, modelListRequests)
    }

    @Test
    fun overloadRetriesSameModelOnceThenFallsBack() = runTest {
        enqueue(overloaded())
        enqueue(ok(answer))
        assertEquals(300, client().analyzeText("1 Apfel").totalKcal)
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))

        enqueue(overloaded())
        enqueue(overloaded())
        enqueue(ok(answer))
        assertEquals(300, client().analyzeText("1 Apfel").totalKcal)
        takeGenerate(); takeGenerate()
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun allModelsOverloadedGivesReadableMessageWithAttempts() = runTest {
        repeat(GeminiFoodVisionClient.MODELS.size * 2) { enqueue(overloaded()) }
        try {
            client().analyzeText("1 Apfel")
            fail("expected 503")
        } catch (e: FoodVisionException) {
            assertEquals(503, e.code)
            assertTrue(e.message!!.contains("überlastet"))
            GeminiFoodVisionClient.MODELS.forEach { assertTrue(e.message!!.contains(it)) }
        }
        assertEquals(GeminiFoodVisionClient.MODELS.size * 2, generateCount)
    }

    @Test
    fun fallsBackToNextModelOn404() = runTest {
        enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        enqueue(ok(answer))
        client().analyze(byteArrayOf(1))
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(takeGenerate().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun errorsAreReadable() = runTest {
        enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        try {
            client().analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertEquals(429, e.code)
            assertTrue(e.message!!.contains("Kontingent"))
        }
        enqueue(MockResponse().setBody("""{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            client().analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("SAFETY"))
        }
        try {
            client(key = null).analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertEquals(0, e.code)
        }
    }
}

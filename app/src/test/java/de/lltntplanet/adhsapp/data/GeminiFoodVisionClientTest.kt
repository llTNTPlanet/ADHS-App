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

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(key: String? = "AIza-test") =
        GeminiFoodVisionClient({ key }, server.url("/v1beta").toString(), overloadRetryDelayMs = 0)

    private val answer = """{"items":[{"name":"Müsli","amount":"80 g","kcal":300,"confidence":"hoch"}],"mealGuess":"BREAKFAST","note":"Packung gelesen"}"""

    private fun ok(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"parts":[{"text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}],"role":"model"},"finishReason":"STOP"}]}""",
    )

    @Test
    fun sendsInlineImageWithSchemaAndParses() = runTest {
        server.enqueue(ok(answer))
        val result = client().analyze(byteArrayOf(1, 2, 3))
        assertEquals("Müsli", result.items.single().name)
        assertEquals(300, result.totalKcal)
        assertEquals(MealType.BREAKFAST, result.mealGuess)

        val request = server.takeRequest()
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
        server.enqueue(ok(answer))
        val result = client().analyzeText("2 Brötchen mit Käse, 1 Apfel")
        assertEquals(300, result.totalKcal)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
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
    fun firstTimeoutRetriesSameModelOnFreshConnectionAndThinkingIsDisabledFor25Models() = runTest {
        server.enqueue(noResponse())
        server.enqueue(ok(answer))
        val result = fastClient(300).analyzeText("1 Apfel")
        assertEquals(300, result.totalKcal)
        val first = server.takeRequest()
        assertTrue(first.path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        val firstBody = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("0", firstBody["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        // Zweiter Versuch geht noch einmal an dasselbe Modell (frische Verbindung), nicht sofort an das nächste.
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
    }

    @Test
    fun secondTimeoutFallsBackToNextModel() = runTest {
        server.enqueue(noResponse())
        server.enqueue(noResponse())
        server.enqueue(ok(answer))
        val result = fastClient(300).analyzeText("1 Apfel")
        assertEquals(300, result.totalKcal)
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun allModelsTimingOutGivesReadableMessageWithAttempts() = runTest {
        repeat(GeminiFoodVisionClient.MODELS.size + 1) { server.enqueue(noResponse()) }
        try {
            fastClient(200).analyzeText("1 Apfel")
            fail("expected timeout")
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("Zeitüberschreitung"))
            assertTrue(e.message!!.contains("frischer Verbindung"))
            GeminiFoodVisionClient.MODELS.forEach { assertTrue(e.message!!.contains(it)) }
        }
        assertEquals(GeminiFoodVisionClient.MODELS.size + 1, server.requestCount)
    }

    private fun overloaded() = MockResponse().setResponseCode(503).setBody("""{"error":{"message":"The model is overloaded. Please try again later."}}""")

    @Test
    fun liteModelIsTriedFirst() {
        assertEquals("gemini-2.5-flash-lite", GeminiFoodVisionClient.MODELS[0])
    }

    @Test
    fun overloadRetriesSameModelOnceThenFallsBack() = runTest {
        server.enqueue(overloaded())
        server.enqueue(ok(answer))
        assertEquals(300, client().analyzeText("1 Apfel").totalKcal)
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))

        server.enqueue(overloaded())
        server.enqueue(overloaded())
        server.enqueue(ok(answer))
        assertEquals(300, client().analyzeText("1 Apfel").totalKcal)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun allModelsOverloadedGivesReadableMessageWithAttempts() = runTest {
        repeat(GeminiFoodVisionClient.MODELS.size * 2) { server.enqueue(overloaded()) }
        try {
            client().analyzeText("1 Apfel")
            fail("expected 503")
        } catch (e: FoodVisionException) {
            assertEquals(503, e.code)
            assertTrue(e.message!!.contains("überlastet"))
            GeminiFoodVisionClient.MODELS.forEach { assertTrue(e.message!!.contains(it)) }
        }
        assertEquals(GeminiFoodVisionClient.MODELS.size * 2, server.requestCount)
    }

    @Test
    fun fallsBackToNextModelOn404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        server.enqueue(ok(answer))
        client().analyze(byteArrayOf(1))
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun errorsAreReadable() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        try {
            client().analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertEquals(429, e.code)
            assertTrue(e.message!!.contains("Kontingent"))
        }
        server.enqueue(MockResponse().setBody("""{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}"""))
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

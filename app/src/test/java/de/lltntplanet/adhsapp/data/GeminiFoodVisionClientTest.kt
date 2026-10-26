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

    private fun client(key: String? = "AIza-test") = GeminiFoodVisionClient({ key }, server.url("/v1beta").toString())

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

    @Test
    fun timeoutFallsBackToNextModelAndThinkingIsDisabledFor25Models() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(ok(answer))
        val fast = GeminiFoodVisionClient(
            { "AIza-test" },
            server.url("/v1beta").toString(),
            okhttp3.OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        val result = fast.analyzeText("1 Apfel")
        assertEquals(300, result.totalKcal)
        val first = server.takeRequest()
        assertTrue(first.path!!.contains(GeminiFoodVisionClient.MODELS[0]))
        val firstBody = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("0", firstBody["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        assertTrue(server.takeRequest().path!!.contains(GeminiFoodVisionClient.MODELS[1]))
    }

    @Test
    fun allModelsTimingOutGivesReadableMessage() = runTest {
        repeat(GeminiFoodVisionClient.MODELS.size) { server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)) }
        val fast = GeminiFoodVisionClient(
            { "AIza-test" },
            server.url("/v1beta").toString(),
            okhttp3.OkHttpClient.Builder().readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        try {
            fast.analyzeText("1 Apfel")
            fail("expected timeout")
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("Zeitüberschreitung"))
        }
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

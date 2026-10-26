package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.data.db.MealType
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionClient
import de.lltntplanet.adhsapp.data.nutrition.FoodVisionException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FoodVisionClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(key: String? = "sk-ant-test") = FoodVisionClient({ key }, server.url("/v1/messages").toString())

    private val answer = """{"items":[{"name":"Linsensuppe","amount":"1 Teller (350 g)","kcal":420,"confidence":"mittel"},
        {"name":"Brötchen","amount":"1 Stück","kcal":150,"confidence":"hoch"}],"mealGuess":"LUNCH","note":"Portion geschätzt"}"""

    @Test
    fun textDescriptionIsSentWithoutImage() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"end_turn",
                   "content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), answer)}}]}""",
            ),
        )
        val result = client().analyzeText("Linsensuppe und ein Brötchen")
        assertEquals(570, result.totalKcal)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Linsensuppe und ein Brötchen"))
    }

    @Test
    fun sendsImageAndSchemaAndParsesResult() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"end_turn",
                   "content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), answer)}}]}""",
            ),
        )
        val result = client().analyze(byteArrayOf(1, 2, 3), hint = "halbe Portion")
        assertEquals(listOf("Linsensuppe", "Brötchen"), result.items.map { it.name })
        assertEquals(570, result.totalKcal)
        assertEquals(MealType.LUNCH, result.mealGuess)
        assertEquals("Portion geschätzt", result.note)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("sk-ant-test", request.getHeader("x-api-key"))
        assertEquals(FoodVisionClient.API_VERSION, request.getHeader("anthropic-version"))
        assertEquals(FoodVisionClient.FALLBACK_BETA, request.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(FoodVisionClient.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.contains("halbe Portion"))
    }

    @Test
    fun refusalAndErrorsBecomeReadableMessages() = runTest {
        server.enqueue(MockResponse().setBody("""{"stop_reason":"refusal","stop_details":{"type":"refusal","category":null,"explanation":"x"},"content":[]}"""))
        try {
            client().analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertTrue(e.message!!.contains("abgelehnt"))
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try {
            client().analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("ungültig"))
        }
        try {
            client(key = null).analyze(byteArrayOf(1))
            fail()
        } catch (e: FoodVisionException) {
            assertEquals(0, e.code)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun parsesEmptyAndMalformedItems() {
        val r = FoodVisionClient.parseRecognition("""{"items":[{"name":"","kcal":5},{"name":"Apfel","kcal":"95.4"}],"mealGuess":null,"note":""}""")
        assertEquals(1, r.items.size)
        assertEquals(95, r.items[0].kcal)
        assertNull(r.mealGuess)
    }
}

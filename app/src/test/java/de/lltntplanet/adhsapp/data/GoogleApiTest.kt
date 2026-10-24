package de.lltntplanet.adhsapp.data

import de.lltntplanet.adhsapp.Berlin
import de.lltntplanet.adhsapp.data.google.GTask
import de.lltntplanet.adhsapp.data.google.GTaskList
import de.lltntplanet.adhsapp.data.google.GoogleApiException
import de.lltntplanet.adhsapp.data.google.GoogleFeature
import de.lltntplanet.adhsapp.data.google.GoogleHttp
import de.lltntplanet.adhsapp.data.google.GoogleTasksApi
import de.lltntplanet.adhsapp.data.google.GoogleTasksSync
import de.lltntplanet.adhsapp.data.google.HealthApi
import de.lltntplanet.adhsapp.data.google.TokenProvider
import de.lltntplanet.adhsapp.data.health.HealthMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import java.time.LocalDate

private class FakeTokens : TokenProvider {
    var issued = 0
    val invalidated = mutableListOf<String>()
    override suspend fun token(feature: GoogleFeature): String = "token-${++issued}"
    override suspend fun invalidate(token: String) {
        invalidated += token
    }
}

class GoogleApiTest {
    private lateinit var server: MockWebServer
    private val tokens = FakeTokens()
    private lateinit var http: GoogleHttp

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = GoogleHttp(tokens)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/v4/").toString()

    @Test
    fun dailyRollUpSendsCivilRangeAndParsesSteps() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"rollupDataPoints":[
                  {"civilStartTime":{"date":{"year":2026,"month":10,"day":23}},"steps":{"countSum":"8123"}},
                  {"civilStartTime":{"date":{"year":2026,"month":10,"day":24}},"steps":{"countSum":"1500"}}
                ],"nextPageToken":"p2"}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"rollupDataPoints":[]}"""))
        val api = HealthApi(http, base())
        val points = api.dailyRollUp("steps", LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 25))
        assertEquals(listOf("8123", "1500"), points.map { it.steps?.countSum })

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/v4/users/me/dataTypes/steps/dataPoints:dailyRollUp", first.path)
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        val start = body["range"]!!.jsonObject["start"]!!.jsonObject["date"]!!.jsonObject
        assertEquals(18, start["day"]!!.jsonPrimitive.int)
        assertEquals(1, body["windowSizeDays"]!!.jsonPrimitive.int)
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("p2", second["pageToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun listUsesFilterQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"dataPoints":[]}"""))
        HealthApi(http, base()).list("sleep", "sleep.interval.civil_end_time >= \"2026-10-18\"")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v4/users/me/dataTypes/sleep/dataPoints", request.requestUrl!!.encodedPath)
        assertEquals("sleep.interval.civil_end_time >= \"2026-10-18\"", request.requestUrl!!.queryParameter("filter"))
    }

    @Test
    fun retriesOnceWithFreshTokenAfter401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"dataPoints":[]}"""))
        HealthApi(http, base()).list("sleep", "x")
        assertEquals(listOf("token-1"), tokens.invalidated)
        server.takeRequest()
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun errorMessageFromApi() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"Google Health API has not been used in project 123","status":"PERMISSION_DENIED"}}"""),
        )
        try {
            HealthApi(http, base()).list("sleep", "x")
            fail("Exception erwartet")
        } catch (e: GoogleApiException) {
            assertEquals(403, e.code)
            assertTrue(e.message!!.contains("Google Health API has not been used"))
        }
    }

    @Test
    fun healthMapperBuildsSnapshot() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"dataPoints":[
                  {"sleep":{"interval":{"startTime":"2026-10-23T21:40:00Z","endTime":"2026-10-24T05:05:00Z",
                     "civilEndTime":{"date":{"year":2026,"month":10,"day":24}}},
                   "metadata":{"mainSleep":true},
                   "summary":{"minutesAsleep":"412","minutesAwake":"33","stagesSummary":[
                     {"type":"DEEP","minutes":"70"},{"type":"LIGHT","minutes":"250"},{"type":"REM","minutes":"92"},{"type":"AWAKE","minutes":"33"}]}}},
                  {"sleep":{"interval":{"startTime":"2026-10-24T13:00:00Z","endTime":"2026-10-24T13:30:00Z"},
                   "metadata":{"nap":true},"summary":{"minutesAsleep":"25"}}}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"dataPoints":[{"dailyRestingHeartRate":{"date":{"year":2026,"month":10,"day":24},"beatsPerMinute":"58"}}]}""",
            ),
        )
        val api = HealthApi(http, base())
        val sleep = api.list("sleep", "x")
        val rhr = api.list("daily-resting-heart-rate", "x")
        val start = LocalDate.of(2026, 10, 18)
        val snapshot = HealthMapper.build(
            fetchedAt = 1L,
            start = start,
            endExclusive = start.plusDays(7),
            steps = null,
            distance = null,
            calories = null,
            azm = null,
            sleep = sleep,
            restingHeartRate = rhr,
            zone = Berlin,
        )
        assertEquals(7, snapshot.days.size)
        assertNull(snapshot.days.first().steps)
        val night = snapshot.lastSleep!!
        assertEquals(LocalDate.of(2026, 10, 24), night.date)
        assertEquals(412L, night.minutesAsleep)
        assertEquals(70L, night.deep)
        assertEquals(250L, night.light)
        assertEquals(92L, night.rem)
        assertEquals(1, snapshot.sleepNights.size)
        assertEquals(58L, snapshot.latestRestingHeartRate!!.bpm)
    }

    @Test
    fun tasksApiAndMapping() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"L1","title":"Meine Aufgaben"}]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"items":[
                  {"id":"T1","title":"Steuer","status":"needsAction","due":"2026-10-30T00:00:00.000Z"},
                  {"id":"T2","title":"Müll","status":"completed","completed":"2026-10-23T10:00:00.000Z"}
                ]}""",
            ),
        )
        val api = GoogleTasksApi(http, server.url("/tasks/v1/").toString())
        val list = api.lists().single()
        val tasks = api.tasks(list.id)
        assertEquals("/tasks/v1/users/@me/lists?maxResults=100", server.takeRequest().path)
        assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("showCompleted"))

        val open = GoogleTasksSync.fromRemote(tasks[0], list)
        assertEquals(LocalDate.of(2026, 10, 30).toEpochDay(), open.dueEpochDay)
        assertEquals(false, open.done)
        assertEquals(true, open.remind)
        assertEquals("Meine Aufgaben", open.googleListTitle)
        val done = GoogleTasksSync.fromRemote(tasks[1], list)
        assertEquals(true, done.done)
        assertNull(done.dueEpochDay)

        // Lokale Uhrzeit bleibt erhalten, solange Google das Datum behält
        val local = open.copy(id = 5, dueMinutes = 18 * 60)
        val merged = GoogleTasksSync.mergeRemote(local, tasks[0].copy(title = "Steuererklärung"), list)
        assertEquals("Steuererklärung", merged.title)
        assertEquals(18 * 60, merged.dueMinutes)
        val noDate = GoogleTasksSync.mergeRemote(local, tasks[0].copy(due = null), GTaskList("L1", "Meine Aufgaben"))
        assertNull(noDate.dueMinutes)
        assertEquals("2026-10-30T00:00:00.000Z", GTask.dueString(LocalDate.of(2026, 10, 30)))
    }
}

package de.lltntplanet.adhsapp.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class GitHubReleaseClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: GitHubReleaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubReleaseClient(apiBase = server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(
        code: Int? = 7,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "adhs-app.apk",
        digest: String? = "sha256:abc",
    ) = GitHubRelease(
        tagName = "v1.0.6",
        body = (code?.let { "<!-- versionCode: $it -->\n" } ?: "") + "Änderungen:\n- Neue Funktion",
        draft = draft,
        prerelease = prerelease,
        htmlUrl = "https://github.com/x/y/releases/tag/v1.0.6",
        assets = listOf(GitHubAsset(id = 1, name = assetName, size = 1234, url = "https://api.github.com/assets/1", digest = digest)),
    )

    @Test
    fun newerReleaseBecomesUpdate() {
        val info = GitHubReleaseClient.toUpdate(release(), currentVersionCode = 3)!!
        assertEquals(7, info.versionCode)
        assertEquals("1.0.6", info.versionName)
        assertEquals("Änderungen:\n- Neue Funktion", info.notes)
        assertEquals("https://api.github.com/assets/1", info.apkUrl)
        assertEquals(1234L, info.apkSize)
        assertEquals("abc", info.sha256)
    }

    @Test
    fun noUpdateWhenNotNewerOrNotUsable() {
        assertNull(GitHubReleaseClient.toUpdate(release(code = 7), currentVersionCode = 7))
        assertNull(GitHubReleaseClient.toUpdate(release(code = 5), currentVersionCode = 7))
        assertNull(GitHubReleaseClient.toUpdate(release(code = null), currentVersionCode = 1))
        assertNull(GitHubReleaseClient.toUpdate(release(draft = true), currentVersionCode = 1))
        assertNull(GitHubReleaseClient.toUpdate(release(prerelease = true), currentVersionCode = 1))
        assertNull(GitHubReleaseClient.toUpdate(release(assetName = "notes.txt"), currentVersionCode = 1))
        assertNull(GitHubReleaseClient.toUpdate(release(digest = "md5:x"), currentVersionCode = 1)!!.sha256)
    }

    @Test
    fun latestReleaseSendsTokenOnlyWhenSet() = runTest {
        val json = """{"tag_name":"v1.0.6","body":"<!-- versionCode: 7 -->","assets":[{"id":1,"name":"adhs-app.apk","size":10,"url":"u","digest":"sha256:ff"}]}"""
        server.enqueue(MockResponse().setBody(json))
        server.enqueue(MockResponse().setBody(json))
        val withToken = client.latestRelease("llTNTPlanet", "ADHS-App", " github_pat_123 ")
        assertEquals("v1.0.6", withToken.tagName)
        assertEquals("ff", GitHubReleaseClient.toUpdate(withToken, 1)!!.sha256)
        val first = server.takeRequest()
        assertEquals("/repos/llTNTPlanet/ADHS-App/releases/latest", first.path)
        assertEquals("Bearer github_pat_123", first.getHeader("Authorization"))
        client.latestRelease("llTNTPlanet", "ADHS-App", null)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun privateRepoWithoutTokenExplainsWhatToDo() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        try {
            client.latestRelease("o", "r", null)
            fail("Exception erwartet")
        } catch (e: UpdateCheckException) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("Token"))
        }
    }

    @Test
    fun downloadVerifiesChecksumAndReportsProgress() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val info = UpdateInfo(7, "1.0.6", "", server.url("/asset").toString(), bytes.size.toLong(), sha, null)
        val progress = mutableListOf<Float>()
        val file = client.download(info, "tok", tmp.root.resolve("u/app.apk")) { progress += it }
        assertTrue(file.exists())
        assertEquals(bytes.size.toLong(), file.length())
        assertEquals(1f, progress.last())
        val request = server.takeRequest()
        assertEquals("application/octet-stream", request.getHeader("Accept"))
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun downloadRejectsWrongChecksum() = runTest {
        server.enqueue(MockResponse().setBody("kaputt"))
        val target = tmp.root.resolve("app.apk")
        val info = UpdateInfo(7, "1.0.6", "", server.url("/asset").toString(), 6, "00".repeat(32), null)
        try {
            client.download(info, null, target) {}
            fail("Exception erwartet")
        } catch (e: UpdateCheckException) {
            assertTrue(e.message!!.contains("Prüfsumme"))
        }
        assertFalse(target.exists())
    }
}

package de.lltntplanet.adhsapp.wear.link

import de.lltntplanet.adhsapp.shared.WatchJson
import de.lltntplanet.adhsapp.shared.WatchSnapshot
import de.lltntplanet.adhsapp.shared.WatchTask
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotLogicTest {

    private fun snapshot(generatedAt: Long) = WatchSnapshot(generatedAt = generatedAt)

    private fun bytes(generatedAt: Long): ByteArray =
        WatchJson.encodeToString(snapshot(generatedAt)).encodeToByteArray()

    // ---- decode ----

    @Test
    fun decodeReturnsNullForMissingOrUnreadableBytes() {
        assertNull(SnapshotLogic.decode(null))
        assertNull(SnapshotLogic.decode(ByteArray(0)))
        assertNull(SnapshotLogic.decode("kein json".encodeToByteArray()))
        assertNull(SnapshotLogic.decode("[]".encodeToByteArray()))
    }

    @Test
    fun decodeReadsWhatThePhoneWrote() {
        val original = WatchSnapshot(
            generatedAt = 42,
            zoneId = "Europe/Berlin",
            tasks = listOf(WatchTask(id = 1, title = "Müll rausbringen", important = true)),
            moreTasks = 2,
        )
        val bytes = WatchJson.encodeToString(original).encodeToByteArray()
        assertEquals(original, SnapshotLogic.decode(bytes))
    }

    @Test
    fun decodeIgnoresFieldsOfANewerPhoneApp() {
        val decoded = SnapshotLogic.decode("""{"generatedAt":5,"neuesFeld":true}""".encodeToByteArray())
        assertEquals(snapshot(5), decoded)
    }

    @Test
    fun decodeFillsMissingFieldsWithDefaults() {
        assertEquals(WatchSnapshot(), SnapshotLogic.decode("{}".encodeToByteArray()))
    }

    // ---- isNewer ----

    @Test
    fun firstSnapshotIsAlwaysTaken() {
        assertTrue(SnapshotLogic.isNewer(current = null, incoming = snapshot(0), now = 1_000_000))
    }

    @Test
    fun laterGeneratedSnapshotWins() {
        assertTrue(SnapshotLogic.isNewer(snapshot(100), snapshot(200), now = 1_000_000))
        assertFalse(SnapshotLogic.isNewer(snapshot(200), snapshot(200), now = 1_000_000))
        assertFalse(SnapshotLogic.isNewer(snapshot(200), snapshot(100), now = 1_000_000))
    }

    @Test
    fun snapshotFromAnImpossibleFutureIsReplaced() {
        // Jetzt = 1 000 000; glaubhaft ist alles bis 1 600 000 (zehn Minuten später).
        assertTrue(SnapshotLogic.isNewer(snapshot(2_000_000), snapshot(1_000_500), now = 1_000_000))
        assertTrue(SnapshotLogic.isNewer(snapshot(1_600_001), snapshot(1_600_000), now = 1_000_000))
    }

    @Test
    fun snapshotSlightlyAheadIsKept() {
        assertFalse(SnapshotLogic.isNewer(snapshot(1_500_000), snapshot(1_000_500), now = 1_000_000))
        assertFalse(SnapshotLogic.isNewer(snapshot(1_600_000), snapshot(1_000_500), now = 1_000_000))
    }

    @Test
    fun amongTwoFutureSnapshotsTheLaterStillWins() {
        assertFalse(SnapshotLogic.isNewer(snapshot(3_000_000), snapshot(2_000_000), now = 1_000_000))
        assertTrue(SnapshotLogic.isNewer(snapshot(3_000_000), snapshot(4_000_000), now = 1_000_000))
    }

    // ---- newest ----

    @Test
    fun newestIsNullWithoutReadableCandidates() {
        assertNull(SnapshotLogic.newest(emptyList()))
        assertNull(SnapshotLogic.newest(listOf("kaputt".encodeToByteArray())))
    }

    @Test
    fun newestPicksTheLatestAndKeepsItsBytes() {
        val older = bytes(10)
        val latest = bytes(30)
        val picked = SnapshotLogic.newest(listOf(older, "kaputt".encodeToByteArray(), latest))
        assertEquals(snapshot(30), picked?.first)
        assertSame(latest, picked?.second)
    }
}

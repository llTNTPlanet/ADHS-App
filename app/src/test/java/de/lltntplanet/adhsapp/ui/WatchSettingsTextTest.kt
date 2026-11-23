package de.lltntplanet.adhsapp.ui

import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WATCH_PROTOCOL_VERSION
import de.lltntplanet.adhsapp.shared.WatchInfo
import de.lltntplanet.adhsapp.ui.settings.watchStatusText
import de.lltntplanet.adhsapp.ui.settings.watchVibrationHint
import de.lltntplanet.adhsapp.watch.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSettingsTextTest {
    /** Feste Formatierung statt echter Uhrzeit, damit der Test nicht von der Zeitzone abhängt. */
    private val time: (Long) -> String = { "um $it" }

    private val connected = WatchStatus(
        checked = true,
        available = true,
        watchName = "Pixel Watch 4",
        reachable = true,
        info = WatchInfo(versionName = "1.0.42"),
        lastSyncAt = 5,
    )

    @Test
    fun searchingUntilChecked() {
        val text = watchStatusText(WatchStatus(), RingTarget.BOTH, time)
        assertEquals("Suche Uhr …", text.headline)
        assertTrue(text.details.isEmpty())
        assertTrue(text.warnings.isEmpty())
        assertEquals("Noch nicht abgeglichen", text.lastSync)
    }

    @Test
    fun noWatchPaired() {
        val text = watchStatusText(WatchStatus(checked = true), RingTarget.BOTH, time)
        assertEquals("Keine Uhr gefunden.", text.headline)
        assertEquals(
            listOf("Mit diesem Handy ist keine Wear-OS-Uhr gekoppelt.", "Erinnerungen klingeln deshalb am Handy."),
            text.details,
        )
        assertTrue(text.warnings.isEmpty())
        // Bei „Nur Handy“ ist der Hinweis aufs Handy überflüssig.
        assertEquals(
            listOf("Mit diesem Handy ist keine Wear-OS-Uhr gekoppelt."),
            watchStatusText(WatchStatus(checked = true), RingTarget.PHONE, time).details,
        )
    }

    @Test
    fun watchWithoutWatchApp() {
        val text = watchStatusText(WatchStatus(checked = true, available = true), RingTarget.WATCH, time)
        assertEquals("Keine Uhr mit der Uhr-App gefunden.", text.headline)
        assertEquals(
            listOf("Installiere die Uhr-App auf deiner Uhr – die Anleitung steht unten.", "Erinnerungen klingeln deshalb am Handy."),
            text.details,
        )
        assertTrue(text.warnings.isEmpty())
    }

    @Test
    fun connectedWatchShowsVersionAndLastSync() {
        val text = watchStatusText(connected, RingTarget.BOTH, time)
        assertEquals("Uhr-App auf Pixel Watch 4 gefunden und verbunden.", text.headline)
        assertEquals(
            listOf("Version der Uhr-App: 1.0.42", "Vollbild auf der Uhr: erst nach Antippen der Benachrichtigung."),
            text.details,
        )
        assertTrue(text.warnings.isEmpty())
        assertEquals("Zuletzt abgeglichen: um 5", text.lastSync)

        val overlay = connected.copy(info = WatchInfo(versionName = "1.0.42", overlayAllowed = true))
        assertEquals(
            listOf("Version der Uhr-App: 1.0.42", "Vollbild auf der Uhr: öffnet sich von selbst."),
            watchStatusText(overlay, RingTarget.WATCH, time).details,
        )
        // Bei „Nur Handy“ bleibt nur die Version stehen.
        assertEquals(listOf("Version der Uhr-App: 1.0.42"), watchStatusText(connected, RingTarget.PHONE, time).details)
    }

    @Test
    fun watchNotConnectedRightNow() {
        val away = connected.copy(reachable = false, info = null, lastSyncAt = 0)
        val text = watchStatusText(away, RingTarget.WATCH_IF_WORN, time)
        assertEquals("Uhr-App auf Pixel Watch 4 gefunden – gerade nicht verbunden.", text.headline)
        assertEquals(
            listOf(
                "Solange die Uhr nicht verbunden ist, klingelt das Handy.",
                "Die Uhr-App hat sich noch nicht gemeldet. Öffne sie einmal auf der Uhr.",
            ),
            text.details,
        )
        // Ohne Meldung der Uhr-App gibt es nichts zu bemängeln.
        assertTrue(text.warnings.isEmpty())
        assertEquals("Noch nicht abgeglichen", text.lastSync)
        assertEquals(
            listOf("Die Uhr-App hat sich noch nicht gemeldet. Öffne sie einmal auf der Uhr."),
            watchStatusText(away, RingTarget.PHONE, time).details,
        )
    }

    @Test
    fun warnsWhenNotificationsAreOffOnTheWatch() {
        val muted = connected.copy(info = WatchInfo(versionName = "1.0.42", notificationsAllowed = false))
        val warning = "Benachrichtigungen sind auf der Uhr aus – sie kann nicht klingeln. " +
            "Öffne die App einmal auf der Uhr und erlaube Benachrichtigungen."
        assertEquals(listOf(warning), watchStatusText(muted, RingTarget.BOTH, time).warnings)
        assertEquals(listOf(warning), watchStatusText(muted, RingTarget.WATCH, time).warnings)
        // Soll die Uhr gar nicht klingeln, stört das nicht.
        assertTrue(watchStatusText(muted, RingTarget.PHONE, time).warnings.isEmpty())
    }

    @Test
    fun warnsAboutMissingWornDetectionOnlyWhenItMatters() {
        val noSensor = connected.copy(info = WatchInfo(versionName = "1.0.42", wornDetection = false))
        assertEquals(
            listOf("Die Uhr kann nicht erkennen, ob du sie trägst. Das Handy klingelt deshalb immer mit."),
            watchStatusText(noSensor, RingTarget.WATCH_IF_WORN, time).warnings,
        )
        assertTrue(watchStatusText(noSensor, RingTarget.WATCH, time).warnings.isEmpty())
        assertTrue(watchStatusText(noSensor, RingTarget.BOTH, time).warnings.isEmpty())
        assertTrue(watchStatusText(noSensor, RingTarget.PHONE, time).warnings.isEmpty())
    }

    @Test
    fun warnsWhenPhoneAndWatchAppDoNotMatch() {
        val other = connected.copy(info = WatchInfo(versionName = "0.9.0", protocol = WATCH_PROTOCOL_VERSION + 1))
        assertEquals(
            listOf("Handy-App und Uhr-App passen nicht zusammen. Installiere beide aus demselben Release."),
            watchStatusText(other, RingTarget.PHONE, time).warnings,
        )
    }

    @Test
    fun allWarningsInFixedOrder() {
        val broken = connected.copy(
            info = WatchInfo(protocol = WATCH_PROTOCOL_VERSION + 1, notificationsAllowed = false, wornDetection = false),
        )
        val text = watchStatusText(broken, RingTarget.WATCH_IF_WORN, time)
        assertEquals(3, text.warnings.size)
        assertTrue(text.warnings[0].startsWith("Handy-App und Uhr-App passen nicht zusammen."))
        assertTrue(text.warnings[1].startsWith("Benachrichtigungen sind auf der Uhr aus"))
        assertTrue(text.warnings[2].startsWith("Die Uhr kann nicht erkennen"))
        // Ohne Versionsangabe entfällt die Versionszeile.
        assertEquals(listOf("Vollbild auf der Uhr: erst nach Antippen der Benachrichtigung."), text.details)
    }

    @Test
    fun blankWatchNameGetsFriendlyFallback() {
        val text = watchStatusText(connected.copy(watchName = "  "), RingTarget.BOTH, time)
        assertEquals("Uhr-App auf deiner Uhr gefunden und verbunden.", text.headline)
    }

    @Test
    fun vibrationHintSaysHowTheWatchRingsWithVibrationOff() {
        // Vibration an: kein Hinweis.
        assertNull(watchVibrationHint(vibrate = true, watchSound = false, target = RingTarget.WATCH))
        assertNull(watchVibrationHint(vibrate = true, watchSound = true, target = RingTarget.BOTH))
        // „Nur Handy“: Die Uhr klingelt ohnehin nicht.
        assertNull(watchVibrationHint(vibrate = false, watchSound = false, target = RingTarget.PHONE))
        assertNull(watchVibrationHint(vibrate = false, watchSound = true, target = RingTarget.PHONE))
        assertEquals(
            "„Vibration“ ist oben unter „Vollbild & Ton“ aus – das gilt auch für die Uhr. Sie meldet sich nur mit dem Ton.",
            watchVibrationHint(vibrate = false, watchSound = true, target = RingTarget.BOTH),
        )
        // Vibration und Ton aus: Ganz still bleibt die Uhr nie – sie vibriert trotzdem.
        assertEquals(
            "„Vibration“ ist oben unter „Vollbild & Ton“ aus. Weil auch der Ton auf der Uhr aus ist, vibriert die Uhr " +
                "trotzdem – sonst ginge die Erinnerung unter.",
            watchVibrationHint(vibrate = false, watchSound = false, target = RingTarget.WATCH_IF_WORN),
        )
    }
}

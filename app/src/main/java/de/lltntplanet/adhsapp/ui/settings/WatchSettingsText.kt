package de.lltntplanet.adhsapp.ui.settings

import de.lltntplanet.adhsapp.shared.RingRouting
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WATCH_PROTOCOL_VERSION
import de.lltntplanet.adhsapp.watch.WatchStatus

// Texte für den Einstellungen-Abschnitt „Uhr (Wear OS)“ – ohne Compose, damit sie sich einfach testen lassen.

/** Was der Abschnitt „Uhr (Wear OS)“ über die Uhr anzeigt. */
internal data class WatchStatusText(
    /** Hauptzeile, z. B. „Uhr-App auf Pixel Watch 4 gefunden und verbunden.“ */
    val headline: String,
    /** Ruhige Zusatzzeilen, z. B. die Version der Uhr-App. */
    val details: List<String> = emptyList(),
    /** Hinweise, bei denen du etwas tun solltest – die Oberfläche hebt sie hervor. */
    val warnings: List<String> = emptyList(),
    /** „Zuletzt abgeglichen: …“ oder „Noch nicht abgeglichen“. */
    val lastSync: String,
)

private const val PHONE_RINGS_INSTEAD = "Erinnerungen klingeln deshalb am Handy."

/**
 * Zustand der Uhr in Worten. [target] ist die gewählte Klingel-Einstellung: Hinweise, die nur fürs Klingeln auf der
 * Uhr wichtig sind, entfallen bei „Nur Handy“. [formatTime] macht aus dem Zeitpunkt des letzten Abgleichs einen Text.
 */
internal fun watchStatusText(status: WatchStatus, target: RingTarget, formatTime: (Long) -> String): WatchStatusText {
    val lastSync = if (status.lastSyncAt > 0) "Zuletzt abgeglichen: ${formatTime(status.lastSyncAt)}" else "Noch nicht abgeglichen"
    val asksWatch = RingRouting.asksWatch(target)
    val watchName = status.watchName?.trim()
    return when {
        !status.checked -> WatchStatusText(headline = "Suche Uhr …", lastSync = lastSync)
        !status.available -> WatchStatusText(
            headline = "Keine Uhr gefunden.",
            details = listOfNotNull(
                "Mit diesem Handy ist keine Wear-OS-Uhr gekoppelt.",
                PHONE_RINGS_INSTEAD.takeIf { asksWatch },
            ),
            lastSync = lastSync,
        )
        watchName == null -> WatchStatusText(
            headline = "Keine Uhr mit der Uhr-App gefunden.",
            details = listOfNotNull(
                "Installiere die Uhr-App auf deiner Uhr – die Anleitung steht unten.",
                PHONE_RINGS_INSTEAD.takeIf { asksWatch },
            ),
            lastSync = lastSync,
        )
        else -> foundWatchText(status, watchName.ifEmpty { "deiner Uhr" }, target, lastSync)
    }
}

/**
 * Hinweis, wenn die Uhr bei einer Erinnerung nicht vibriert: Der Schalter „Vibration“ unter „Vollbild & Ton“ gilt
 * auch für die Uhr. `null`, wenn sie vibriert oder gar nicht klingeln soll ([target] „Nur Handy“).
 */
internal fun watchVibrationHint(vibrate: Boolean, watchSound: Boolean, target: RingTarget): String? = when {
    vibrate || !RingRouting.asksWatch(target) -> null
    watchSound -> "„Vibration“ ist oben unter „Vollbild & Ton“ aus – das gilt auch für die Uhr. Sie meldet sich nur mit dem Ton."
    else -> "„Vibration“ ist oben unter „Vollbild & Ton“ aus – das gilt auch für die Uhr. Ohne Ton zeigt sie Erinnerungen nur still an."
}

/** Die Uhr-App ist auf der Uhr [watchName] installiert: Verbindung, Version und was noch fehlt. */
private fun foundWatchText(status: WatchStatus, watchName: String, target: RingTarget, lastSync: String): WatchStatusText {
    val asksWatch = RingRouting.asksWatch(target)
    val info = status.info
    val details = buildList {
        if (!status.reachable && asksWatch) add("Solange die Uhr nicht verbunden ist, klingelt das Handy.")
        if (info == null) {
            add("Die Uhr-App hat sich noch nicht gemeldet. Öffne sie einmal auf der Uhr.")
        } else {
            if (info.versionName.isNotBlank()) add("Version der Uhr-App: ${info.versionName}")
            if (asksWatch) {
                add(
                    if (info.overlayAllowed) "Vollbild auf der Uhr: öffnet sich von selbst."
                    else "Vollbild auf der Uhr: erst nach Antippen der Benachrichtigung.",
                )
            }
        }
    }
    val warnings = buildList {
        if (info != null) {
            if (info.protocol != WATCH_PROTOCOL_VERSION) {
                add("Handy-App und Uhr-App passen nicht zusammen. Installiere beide aus demselben Release.")
            }
            if (asksWatch && !info.notificationsAllowed) {
                add(
                    "Benachrichtigungen sind auf der Uhr aus – sie kann nicht klingeln. " +
                        "Öffne die App einmal auf der Uhr und erlaube Benachrichtigungen.",
                )
            }
            if (RingRouting.onlyIfWorn(target) && !info.wornDetection) {
                add("Die Uhr kann nicht erkennen, ob du sie trägst. Das Handy klingelt deshalb immer mit.")
            }
        }
    }
    return WatchStatusText(
        headline = if (status.reachable) {
            "Uhr-App auf $watchName gefunden und verbunden."
        } else {
            "Uhr-App auf $watchName gefunden – gerade nicht verbunden."
        },
        details = details,
        warnings = warnings,
        lastSync = lastSync,
    )
}

package de.lltntplanet.adhsapp.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Das Protokoll zwischen Handy-App und Uhr-App über den Wearable Data Layer.
 *
 * - Nachrichten (MessageClient) sind flüchtig: Erinnerungen, Antworten, Knöpfe.
 * - Datenelemente (DataClient) bleiben gespeichert und werden nachgeliefert: die Übersicht für die Uhr und
 *   der Zustand der Uhr-App.
 *
 * Alle Inhalte sind JSON ([WatchJson]). Unbekannte Felder werden ignoriert, neue Felder brauchen Vorgabewerte –
 * so vertragen sich auch unterschiedlich alte Versionen von Handy- und Uhr-App.
 */
object WatchPaths {
    /** Capability der Handy-App (res/values/wear.xml in :app). */
    const val CAPABILITY_PHONE = "adhs_phone"

    /** Capability der Uhr-App (res/values/wear.xml in :wear). */
    const val CAPABILITY_WATCH = "adhs_watch"

    /** Gemeinsamer Anfang aller Pfade (für die Filter der Listener-Services im Manifest). */
    const val PREFIX = "/adhs"

    /** Datenelement Handy → Uhr: Übersicht ([WatchSnapshot]). */
    const val SNAPSHOT = "/adhs/snapshot"

    /** Datenelement Uhr → Handy: Zustand der Uhr-App ([WatchInfo]). */
    const val WATCH_INFO = "/adhs/watch-info"

    /** Nachricht Handy → Uhr: Erinnerungen jetzt anzeigen ([RingRequest]). */
    const val RING = "/adhs/ring"

    /** Nachricht Uhr → Handy: Antwort auf [RING] ([RingAck]). */
    const val RING_ACK = "/adhs/ring-ack"

    /** Nachricht Handy → Uhr: Erinnerungen wieder entfernen ([CancelRequest]). */
    const val CANCEL = "/adhs/cancel"

    /** Nachricht Uhr → Handy: Knopf in einer Erinnerung gedrückt ([ReminderAction]). */
    const val REMINDER_ACTION = "/adhs/reminder-action"

    /** Nachricht Uhr → Handy: Aufgabe oder Routine abhaken ([ItemAction]). */
    const val ITEM_ACTION = "/adhs/item-action"

    /** Nachricht Uhr → Handy: Übersicht bitte neu schicken (ohne Inhalt). */
    const val SYNC_REQUEST = "/adhs/sync-request"
}

/** Wird erhöht, wenn sich das Protokoll unverträglich ändert. */
const val WATCH_PROTOCOL_VERSION = 1

val WatchJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Eine Erinnerung, wie die Uhr sie anzeigt. Texte kommen fertig vom Handy. */
@Serializable
data class WatchReminder(
    /** Schlüssel der Erinnerung auf dem Handy (bei geschlummerten: „snooze:…“). */
    val key: String,
    /** Schlüssel der ursprünglichen Erinnerung – daran erkennt die Uhr, was sie bei [CancelRequest] entfernt. */
    val originKey: String,
    /** Art: EVENT, BIRTHDAY, TASK, MEDICATION, ROUTINE, CHECKUP (Name von ReminderKind in der Handy-App). */
    val kind: String,
    /** Überschrift, z. B. „💊 Methylphenidat“. */
    val title: String,
    /** Zeile darunter zum Zeitpunkt des Sendens, z. B. „Beginnt in 15 Min. · 14:00–15:00“. */
    val subtitle: String,
    /** Ort, Dosis, Notiz. */
    val detail: String? = null,
    /** Beginn des Termins bzw. geplante Uhrzeit – für einen mitlaufenden Countdown auf der Uhr. */
    val targetTime: Long? = null,
    val targetEnd: Long? = null,
    val allDay: Boolean = false,
    val color: Int? = null,
    /** Gibt es einen „Erledigt“/„Genommen“-Knopf? */
    val canDone: Boolean = false,
    val doneLabel: String = "Erledigt",
    /** „Keine weiteren Erinnerungen zu diesem Termin“ möglich? */
    val canMute: Boolean = false,
    /** Hinweis, wenn der eigentliche Schritt nur am Handy geht, z. B. „Am Handy ausfüllen“ beim Checkup. */
    val phoneHint: String? = null,
    /** Die Erinnerung im Format der Handy-App. Die Uhr schickt sie bei einem Knopfdruck unverändert zurück. */
    val payload: String,
)

/** Handy → Uhr: diese Erinnerungen jetzt zeigen und klingeln. */
@Serializable
data class RingRequest(
    /** Eindeutig je Anfrage; kommt in [RingAck] zurück. */
    val requestId: String,
    val sentAt: Long,
    /** Nur klingeln, wenn die Uhr getragen wird (sonst mit `shown = false` antworten). */
    val onlyIfWorn: Boolean = false,
    val reminders: List<WatchReminder>,
    val vibrate: Boolean = true,
    /** Zusätzlich einen Weckton auf der Uhr abspielen. */
    val sound: Boolean = false,
    /** Wie lange höchstens geklingelt wird. */
    val ringSeconds: Int = 60,
    val version: Int = WATCH_PROTOCOL_VERSION,
)

/** Uhr → Handy: Antwort auf [RingRequest]. */
@Serializable
data class RingAck(
    val requestId: String,
    /**
     * Die Uhr zeigt die Erinnerung an UND steht dafür ein, dass der Nutzer sie mitbekommt (bei „nur wenn
     * getragen“: sie wird sicher getragen). Nur dann darf das Handy still bleiben.
     */
    val shown: Boolean,
    /** Die Erinnerung wird auf der Uhr angezeigt – auch wenn [shown] false ist (Trage-Zustand unbekannt). */
    val displayed: Boolean = shown,
    /** Wird die Uhr getragen? `null` = lässt sich nicht feststellen. */
    val worn: Boolean? = null,
    val reason: String? = null,
)

/** Handy → Uhr: Erinnerungen entfernen (auf dem Handy bestätigt, abgehakt oder vom Spender gemeldet). */
@Serializable
data class CancelRequest(
    /** [WatchReminder.originKey] der zu entfernenden Erinnerungen; leer = alle. */
    val originKeys: List<String> = emptyList(),
)

/** Uhr → Handy: Knopf in einer Erinnerung. */
@Serializable
data class ReminderAction(
    /** Eine der Konstanten aus [ReminderActions]. */
    val action: String,
    /** [WatchReminder.payload], unverändert. */
    val payload: String,
    /** Minuten bei [ReminderActions.SNOOZE]. */
    val minutes: Int = 0,
)

object ReminderActions {
    const val SNOOZE = "snooze"
    const val DONE = "done"
    const val DISMISS = "dismiss"
    const val MUTE = "mute"
}

/** Uhr → Handy: Aufgabe oder Routine in der Übersicht abhaken bzw. den Haken entfernen. */
@Serializable
data class ItemAction(
    /** [TYPE_TASK] oder [TYPE_ROUTINE]. */
    val type: String,
    /** Aufgaben-ID bzw. Routinen-ID. */
    val id: Long,
    val done: Boolean,
    /** Nur Routinen: Tag und Uhrzeit (Minuten seit Mitternacht) des Vorkommens. */
    val epochDay: Long? = null,
    val minutes: Int? = null,
) {
    companion object {
        const val TYPE_TASK = "task"
        const val TYPE_ROUTINE = "routine"
    }
}

/** Uhr → Handy (Datenelement): Zustand der Uhr-App, damit die Handy-Einstellungen ihn anzeigen können. */
@Serializable
data class WatchInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val protocol: Int = WATCH_PROTOCOL_VERSION,
    /** Gerätename, z. B. „Google Pixel Watch 4“. */
    val model: String = "",
    val updatedAt: Long = 0,
    /** Dürfen auf der Uhr Benachrichtigungen gezeigt werden? */
    val notificationsAllowed: Boolean = true,
    /** Darf die Uhr-App Erinnerungen im Vollbild zeigen? */
    val fullScreenAllowed: Boolean = true,
    /** Kann die Uhr feststellen, ob sie getragen wird? */
    val wornDetection: Boolean = true,
)

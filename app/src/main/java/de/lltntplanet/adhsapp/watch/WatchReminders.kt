package de.lltntplanet.adhsapp.watch

import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderButtons
import de.lltntplanet.adhsapp.reminders.ReminderKind
import de.lltntplanet.adhsapp.reminders.ReminderPayload
import de.lltntplanet.adhsapp.reminders.ReminderText
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.RingRouting
import de.lltntplanet.adhsapp.shared.WatchReminder
import java.time.ZoneId
import java.util.UUID

/** Bereitet Erinnerungen für die Uhr auf – reine Logik ohne Android, damit sie sich einfach testen lässt. */
object WatchReminders {

    /** Hinweis auf der Uhr beim Gefühls-Checkup: Die Symptom-Abfrage gibt es nur am Handy. */
    const val CHECKUP_HINT = "Am Handy ausfüllen"

    /**
     * Die Erinnerung, wie die Uhr sie anzeigt. Überschrift und Zeile darunter sind dieselben wie in der
     * Handy-Benachrichtigung; [now] und [zone] bestimmen den Zeitbezug („in 15 Min.“).
     */
    fun toWatch(r: Reminder, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): WatchReminder {
        val subtitle = ReminderText.subtitle(r, now, zone)
        return WatchReminder(
            key = r.key,
            originKey = r.originKey ?: r.key,
            kind = r.kind.name,
            title = ReminderText.notificationTitle(r),
            subtitle = subtitle,
            // Die Dosis steht bei Routinen und Medikamenten oft schon in der Zeile darüber – nicht doppelt zeigen.
            detail = r.detail?.takeUnless { it.isBlank() || it in subtitle },
            targetTime = r.targetTime,
            // Nur bei Terminen ist das Ende eine Uhrzeit zum Anzeigen; sonst markiert es das Ende der Nachhol-Frist.
            targetEnd = if (r.kind == ReminderKind.EVENT) r.targetEnd else null,
            allDay = r.allDay,
            color = r.color,
            canDone = ReminderButtons.canDone(r),
            doneLabel = ReminderButtons.doneLabel(r),
            canMute = r.canMute,
            phoneHint = if (r.kind.isCheckup) CHECKUP_HINT else null,
            payload = ReminderPayload.encode(r),
        )
    }

    /**
     * Die Anfrage „jetzt klingeln“ an die Uhr. Stille Erinnerungen (Mahlzeit eintragen) bleiben am Handy und
     * fehlen deshalb. Wie geklingelt wird, kommt aus den Einstellungen.
     */
    fun ringRequest(
        due: List<Reminder>,
        settings: AppSettings,
        requestId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): RingRequest = RingRequest(
        requestId = requestId,
        sentAt = now,
        onlyIfWorn = RingRouting.onlyIfWorn(settings.ringTarget),
        reminders = due.filterNot { it.kind.isQuiet }.map { toWatch(it, now, zone) },
        vibrate = settings.vibrate,
        sound = settings.watchSound,
        ringSeconds = settings.ringSeconds,
    )
}

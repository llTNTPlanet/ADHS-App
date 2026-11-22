package de.lltntplanet.adhsapp.watch

import android.util.Log
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.reminders.Reminder
import de.lltntplanet.adhsapp.reminders.ReminderNotifier
import de.lltntplanet.adhsapp.shared.RingAck
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.RingRouting
import de.lltntplanet.adhsapp.shared.RingTarget
import de.lltntplanet.adhsapp.shared.WatchOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Was das Handy mit den fälligen Erinnerungen macht. */
data class PhonePlan(
    /** Klingelt das Handy (Vollbild, Ton, Vibration)? Sonst zeigt es nur stille Benachrichtigungen. */
    val ring: Boolean,
    /** Bleiben die Benachrichtigungen auf dem Handy, statt zusätzlich vom System auf die Uhr gespiegelt zu werden? */
    val localOnly: Boolean,
)

/**
 * Die Klingel-Weiche: stellt fällige Erinnerungen zu – je nach Einstellung auf der Uhr, am Handy oder auf beiden.
 *
 * Grundsatz: Eine Erinnerung darf nie verloren gehen. Egal, was auf dem Weg zur Uhr passiert (keine Uhr, keine
 * Antwort, Fehler, Abbruch) – am Ende zeigt das Handy die Erinnerungen an. Still bleibt es nur, wenn die Uhr
 * ausdrücklich bestätigt hat, dass sie klingelt.
 */
class ReminderDelivery(
    /** Fragt die Uhr und wartet höchstens die angegebene Zeit auf ihre Antwort; `null` = keine Bestätigung. */
    private val ringWatch: suspend (request: RingRequest, ackTimeoutMillis: Long) -> RingAck?,
    /** Zeigt die Erinnerungen am Handy – klingelnd oder still. */
    private val showOnPhone: (due: List<Reminder>, settings: AppSettings, ring: Boolean, localOnly: Boolean) -> Unit,
) {
    constructor(watch: WatchLink, notifier: ReminderNotifier) : this(
        ringWatch = { request, ackTimeoutMillis -> watch.ring(request, ackTimeoutMillis) },
        showOnPhone = { due, settings, ring, localOnly -> notifier.show(due, settings, ring, localOnly) },
    )

    /** Stellt die fälligen Erinnerungen zu. Kehrt erst zurück, wenn das Handy sie anzeigt. */
    suspend fun deliver(due: List<Reminder>, settings: AppSettings) {
        if (due.isEmpty()) return
        val outcome = askWatch(due, settings)
        val plan = phonePlan(settings.ringTarget, outcome)
        // Zwischen der Antwort der Uhr und dem Anzeigen liegt kein Wartepunkt: Das Handy zeigt in jedem Fall an.
        showOnPhone(due, settings, plan.ring, plan.localOnly)
        currentCoroutineContext().ensureActive()
    }

    /** Fragt die Uhr, falls sie klingeln soll. Wirft nie – jeder Fehler heißt „nicht erreichbar“. */
    private suspend fun askWatch(due: List<Reminder>, settings: AppSettings): WatchOutcome {
        val target = settings.ringTarget
        if (!asksWatch(target, due)) return WatchOutcome.NOT_REQUESTED
        return try {
            val ackTimeout = RingRouting.ackTimeoutMillis(target)
            // Eigenes Zeitlimit als Sicherheitsnetz, falls die Verbindung zur Uhr wider Erwarten hängt.
            val ack = withTimeoutOrNull(ackTimeout + SEARCH_ALLOWANCE_MILLIS) {
                ringWatch(WatchReminders.ringRequest(due, settings), ackTimeout)
            }
            RingRouting.outcome(ack)
        } catch (e: Throwable) {
            // Auch ein Abbruch landet hier, damit das Handy trotzdem noch anzeigt; deliver reicht ihn danach weiter.
            Log.w(TAG, "Die Uhr konnte nicht gefragt werden – das Handy klingelt", e)
            WatchOutcome.UNREACHABLE
        }
    }

    companion object {
        private const val TAG = "ReminderDelivery"

        /** Zuschlag auf die Wartezeit der Uhr: so lange darf die Suche nach der Uhr davor höchstens dauern. */
        private const val SEARCH_ALLOWANCE_MILLIS = 2_000L

        /** Wird die Uhr gefragt? Nur wenn sie klingeln soll und es etwas Lautes gibt – stille Erinnerungen bleiben am Handy. */
        fun asksWatch(target: RingTarget, due: List<Reminder>): Boolean =
            RingRouting.asksWatch(target) && due.any { !it.kind.isQuiet }

        /**
         * Die Entscheidung für das Handy, nachdem feststeht, was aus der Anfrage an die Uhr geworden ist: Ohne
         * ausdrückliche Bestätigung der Uhr klingelt das Handy immer.
         */
        fun phonePlan(target: RingTarget, outcome: WatchOutcome): PhonePlan = PhonePlan(
            ring = RingRouting.phoneRings(target, outcome),
            localOnly = target == RingTarget.PHONE || RingRouting.watchShows(outcome),
        )
    }
}

package de.lltntplanet.adhsapp.wear.alert

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import de.lltntplanet.adhsapp.shared.ReminderAction
import de.lltntplanet.adhsapp.shared.RingRequest
import de.lltntplanet.adhsapp.shared.WatchReminder
import de.lltntplanet.adhsapp.wear.link.PhoneLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Die Erinnerungen, die gerade auf der Uhr stehen – und alles, was dazugehört: Benachrichtigungen,
 * Klingel-Dienst, Rückfall und die Knöpfe.
 *
 * Je Erinnerung gibt es eine Benachrichtigung. Solange es klingelt, vibriert der [AlertRingService]; er trägt
 * dabei die Benachrichtigung einer der klingelnden Erinnerungen. Darf der Dienst nicht starten, vibriert
 * stattdessen das System über eine Benachrichtigung (Rückfall). Nach der Klingelzeit bleiben die Erinnerungen
 * still stehen, bis sie bestätigt werden oder das Handy sie entfernt.
 *
 * Alle Funktionen dürfen von jedem Thread aus aufgerufen werden.
 */
class AlertController(
    private val context: Context,
    private val phone: PhoneLink,
    private val scope: CoroutineScope,
) {

    /** Was gerade klingelt. [until] zählt in `SystemClock.elapsedRealtime()`. */
    private data class RingSession(
        val keys: Set<String>,
        val until: Long,
        val vibrate: Boolean,
        val sound: Boolean,
        val mode: RingMode,
        /** Nur im Rückfall: die Erinnerung, deren Benachrichtigung vibriert. */
        val fallbackKey: String? = null,
    )

    private val notifications = AlertNotifications(context)
    private val handler = Handler(Looper.getMainLooper())
    private val timeUp = Runnable { onRingTimeUp() }
    private val lock = Any()

    // Alles ab hier nur unter [lock] ändern.
    private var session: RingSession? = null
    private var service: AlertRingService? = null

    /** Die Erinnerung, deren Benachrichtigung der Dienst gerade trägt. */
    private var anchorKey: String? = null

    /** Welche Benachrichtigungen stehen – und in welcher Form. */
    private val posted = mutableMapOf<String, AlertStyle>()

    /**
     * Benachrichtigungen, die nach einem Neustart der App noch über das System vibrieren. Sie bleiben, wie sie
     * sind, bis ihre Klingelzeit endet oder jemand sie bestätigt.
     */
    private val orphans = mutableSetOf<String>()
    private var restored = false
    private var noticeJob: Job? = null

    private val _active = MutableStateFlow<List<WatchReminder>>(emptyList())

    /** Erinnerungen, die gerade auf der Uhr stehen – neueste zuerst. */
    val active: StateFlow<List<WatchReminder>> = _active.asStateFlow()

    private val _ringing = MutableStateFlow(false)

    /** Klingelt gerade etwas? */
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** Hinweis für das Vollbild, wenn ein Knopfdruck das Handy nicht erreicht hat – sonst `null`. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _sending = MutableStateFlow(0)

    /** So viele Knopfdrücke sind gerade auf dem Weg zum Handy. */
    val sending: StateFlow<Int> = _sending.asStateFlow()

    @Volatile
    private var uiVisible = false

    /** Beim Start der App: Kanäle anlegen und wiederfinden, was noch auf der Uhr steht. */
    fun start() {
        notifications.createChannels()
        synchronized(lock) { restoreLocked() }
    }

    /** Darf die Uhr-App gerade Erinnerungen zeigen? */
    fun canShow(): Boolean = notifications.canShow()

    /**
     * Zeigt die Erinnerungen der Anfrage und klingelt. `true` nur, wenn danach wirklich mindestens eine davon
     * auf der Uhr steht.
     */
    fun show(request: RingRequest): Boolean {
        val incoming = request.reminders.distinctBy { it.originKey }
        if (incoming.isEmpty() || !notifications.canShow()) return false
        return synchronized(lock) {
            restoreLocked()
            val keys = incoming.map { it.originKey }.toSet()
            // Steht eine davon schon da, soll sie neu aufblenden: alte Benachrichtigung zuerst entfernen.
            keys.forEach { key ->
                orphans.remove(key)
                if (key != anchorKey && posted.remove(key) != null) notifications.cancel(key)
            }
            _active.value = AlertLogic.merge(_active.value, incoming)

            val previous = session
            val now = SystemClock.elapsedRealtime()
            val next = RingSession(
                keys = previous?.keys.orEmpty() + keys,
                until = maxOf(previous?.until ?: 0L, now + VibrationPlan.ringMillis(request.ringSeconds)),
                vibrate = request.vibrate,
                sound = request.sound,
                mode = RingMode.STARTING,
            )
            session = when {
                service != null -> next.copy(mode = RingMode.SERVICE)
                previous?.mode == RingMode.STARTING -> next
                requestService() -> next
                else -> next.copy(mode = RingMode.FALLBACK, fallbackKey = incoming.first().originKey)
            }
            syncServiceLocked(force = true)
            armTimerLocked()
            renderLocked()

            val displayed = keys.any { posted[it] != null }
            if (!displayed) removeLocked(keys)
            displayed
        }
    }

    /**
     * Entfernt Erinnerungen von der Uhr, ohne das Handy zu benachrichtigen (das Handy hat sie selbst entfernt,
     * oder die stille Benachrichtigung wurde weggewischt). Leere Liste = alle.
     */
    fun dismiss(originKeys: List<String>) {
        synchronized(lock) {
            restoreLocked()
            val keys = if (originKeys.isEmpty()) _active.value.map { it.originKey }.toSet() else originKeys.toSet()
            removeLocked(keys)
        }
    }

    /**
     * Knopf einer Erinnerung: Die Uhr ist sofort still und die Erinnerung verschwindet, dann geht die Aktion
     * ans Handy. `true`, wenn das Handy sie ausgeführt hat. Sonst kommt die Erinnerung still zurück auf die Uhr
     * (der Knopf lässt sich dann noch einmal drücken) und der Hinweis „Handy nicht erreichbar“ erscheint.
     * [action] ist eine der Konstanten aus `ReminderActions`.
     */
    suspend fun perform(reminder: WatchReminder, action: String, minutes: Int = 0): Boolean {
        begin(reminder)
        return report(reminder, action, minutes)
    }

    /** Wie [perform], kehrt aber sofort zurück – für die Knöpfe im Vollbild. */
    fun act(reminder: WatchReminder, button: AlertButton) {
        begin(reminder)
        scope.launch { report(reminder, button.action, button.minutes) }
    }

    /** Der Hinweis „Handy nicht erreichbar“ wurde gelesen. */
    fun clearNotice() {
        synchronized(lock) {
            noticeJob?.cancel()
            noticeJob = null
        }
        _notice.value = null
        notifications.cancelNotice()
    }

    /** Das Vollbild meldet, ob es gerade zu sehen ist. Dann steht der Hinweis dort und nicht als Benachrichtigung. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible && _notice.value != null) notifications.cancelNotice()
    }

    /**
     * Die im Rückfall vibrierende Benachrichtigung ist verschwunden, weil ihre Klingelzeit vorbei ist. Die
     * Erinnerung bleibt still stehen.
     */
    internal fun onInsistentExpired(reminder: WatchReminder) {
        synchronized(lock) {
            restoreLocked()
            val key = reminder.originKey
            val known = posted[key]
            // Inzwischen neu gezeigt: Dann gilt die Meldung einer älteren Benachrichtigung.
            if (known != null && known != AlertStyle.INSISTENT) return
            orphans.remove(key)
            posted.remove(key)
            val current = session
            if (current != null && current.mode == RingMode.FALLBACK && current.fallbackKey == key) {
                session = null
                handler.removeCallbacks(timeUp)
            }
            if (_active.value.none { it.originKey == key }) _active.value = _active.value + reminder
            renderLocked()
        }
    }

    /** Der Klingel-Dienst läuft (oder wurde erneut angestoßen). */
    internal fun onServiceStarted(svc: AlertRingService) {
        synchronized(lock) {
            service = svc
            val current = session
            val wanted = current != null && current.mode != RingMode.FALLBACK &&
                AlertLogic.anchor(_active.value, current.keys, anchorKey) != null
            if (wanted) {
                syncServiceLocked(force = true)
            } else {
                // Nichts mehr zu klingeln: kurz anmelden, wie es das System verlangt, und gleich wieder gehen.
                if (anchorKey == null) svc.foreground(AlertNotifications.PLACEHOLDER_ID, notifications.placeholder())
                releaseServiceLocked(svc)
            }
            renderLocked()
        }
    }

    /** Der Klingel-Dienst wurde beendet. Hat das System das getan, bleiben die Erinnerungen still stehen. */
    internal fun onServiceDestroyed(svc: AlertRingService) {
        synchronized(lock) {
            if (service !== svc) return
            service = null
            anchorKey?.let { posted.remove(it) }
            anchorKey = null
            session = null
            handler.removeCallbacks(timeUp)
            renderLocked()
        }
    }

    // ---- Knöpfe ----

    private fun begin(reminder: WatchReminder) {
        // Zuerst zählen, dann entfernen: So bleibt das Vollbild offen, bis feststeht, ob das Handy erreicht wurde.
        _sending.update { it + 1 }
        synchronized(lock) {
            restoreLocked()
            removeLocked(setOf(reminder.originKey))
        }
    }

    private suspend fun report(reminder: WatchReminder, action: String, minutes: Int): Boolean {
        var ok = false
        try {
            ok = phone.sendReminderAction(ReminderAction(action, reminder.payload, minutes))
        } finally {
            try {
                if (ok) {
                    // Das Handy ist erreichbar: Der Hinweis von einem früheren Versuch hat sich erledigt.
                    if (_notice.value != null) clearNotice()
                } else {
                    // Nicht angekommen (oder ein Fehler): Die Erinnerung darf nicht verschwinden. Sie und der
                    // Hinweis stehen, bevor der Zähler sinkt – sonst schlösse sich das Vollbild vorher.
                    putBack(reminder)
                    showNotice()
                }
            } finally {
                _sending.update { it - 1 }
            }
        }
        return ok
    }

    /**
     * Legt eine Erinnerung still zurück auf die Uhr, deren Knopfdruck das Handy nicht erreicht hat: als
     * Benachrichtigung mit Knöpfen und im Vollbild, ohne neues Klingeln. Die Uhr fragt nicht von selbst noch
     * einmal nach – ein zweites „später“ würde am Handy doppelt eingetragen. Entfernt das Handy die Erinnerung
     * danach doch noch, verschwindet sie wie gewohnt ([dismiss]).
     */
    private fun putBack(reminder: WatchReminder) {
        val key = reminder.originKey
        synchronized(lock) {
            if (_active.value.any { it.originKey == key }) return
            // Nach vorn: Im Vollbild steht sie dann direkt unter dem Hinweis.
            _active.value = listOf(reminder) + _active.value
            renderLocked()
        }
        // Trug eben noch der Klingel-Dienst diese Benachrichtigung, entfernt das System sie mit etwas Verzögerung –
        // und träfe dann die neue mit derselben Nummer. Deshalb kurz darauf noch einmal zeigen.
        handler.postDelayed({ showAgain(key) }, PUT_BACK_CHECK_MILLIS)
    }

    /** Zeigt die stille Benachrichtigung einer Erinnerung noch einmal – nur wenn sie noch still dasteht. */
    private fun showAgain(key: String) {
        synchronized(lock) {
            if (posted[key] != AlertStyle.QUIET) return
            val reminder = _active.value.firstOrNull { it.originKey == key } ?: return
            // Steht sie noch, ändert sich nichts: Sie blendet nicht erneut auf.
            if (notifications.post(reminder, AlertStyle.QUIET)) return
            posted.remove(key)
        }
    }

    private fun showNotice() {
        _notice.value = AlertLogic.PHONE_UNREACHABLE
        if (!uiVisible) notifications.postNotice()
        // Der Arm ist nach dem Knopfdruck oft schon wieder unten: Ein kurzer Stoß macht auf den Hinweis aufmerksam.
        buzzOnce()
        synchronized(lock) {
            noticeJob?.cancel()
            noticeJob = scope.launch {
                delay(AlertNotifications.NOTICE_MILLIS)
                _notice.value = null
            }
        }
    }

    /** Ein einzelner kurzer Stoß – als „Wecker“ eingestuft wie das Klingeln, damit er auch im Hintergrund ankommt. */
    private fun buzzOnce() {
        runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(VibrationPlan.NOTICE_BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }.onFailure { Log.w(TAG, "Vibrieren nicht möglich", it) }
    }

    // ---- Zustand ----

    /** Einmal je App-Start: Was als Benachrichtigung noch auf der Uhr steht, gehört wieder zu den Erinnerungen. */
    private fun restoreLocked() {
        if (restored) return
        restored = true
        val shown = notifications.restore()
        if (shown.isEmpty()) return
        shown.forEach { (reminder, style) ->
            posted[reminder.originKey] = style
            if (style == AlertStyle.INSISTENT) orphans.add(reminder.originKey)
        }
        _active.value = AlertLogic.merge(shown.map { it.first }, _active.value)
        renderLocked()
    }

    /** Fordert den Klingel-Dienst an. `false`, wenn das System den Start gerade nicht erlaubt. */
    private fun requestService(): Boolean = try {
        context.startForegroundService(Intent(context, AlertRingService::class.java)) != null
    } catch (e: Exception) {
        Log.w(TAG, "Klingel-Dienst darf nicht starten – das System vibriert stattdessen", e)
        false
    }

    private fun removeLocked(keys: Set<String>) {
        if (keys.isEmpty()) return
        _active.value = _active.value.filter { it.originKey !in keys }
        session = session?.let { current ->
            val left = current.keys - keys
            when {
                left.isEmpty() -> null
                current.mode == RingMode.FALLBACK -> {
                    // Im Rückfall vibriert nur eine Benachrichtigung. Ist sie weg, übernimmt die nächste.
                    val fallback = current.fallbackKey?.takeIf { it in left }
                        ?: _active.value.firstOrNull { it.originKey in left }?.originKey
                    if (fallback == null) null else current.copy(keys = left, fallbackKey = fallback)
                }
                else -> current.copy(keys = left)
            }
        }
        if (session == null) handler.removeCallbacks(timeUp)
        // Erst den Dienst umstellen: Solange er eine Benachrichtigung trägt, lässt sie sich nicht entfernen.
        syncServiceLocked()
        keys.forEach { key ->
            orphans.remove(key)
            posted.remove(key)
            notifications.cancel(key)
        }
        renderLocked()
    }

    private fun armTimerLocked() {
        handler.removeCallbacks(timeUp)
        val current = session ?: return
        handler.postDelayed(timeUp, VibrationPlan.remaining(SystemClock.elapsedRealtime(), current.until))
    }

    /** Die Klingelzeit ist vorbei: Der Dienst hört auf, die Erinnerungen bleiben still stehen. */
    private fun onRingTimeUp() {
        synchronized(lock) {
            val current = session ?: return
            if (SystemClock.elapsedRealtime() < current.until) {
                // Inzwischen kam eine weitere Erinnerung dazu: Es klingelt noch.
                armTimerLocked()
                return
            }
            session = null
            syncServiceLocked()
            renderLocked()
        }
    }

    /** Bringt den Dienst auf den Stand von [session]: richtige Benachrichtigung tragen und klingeln – oder gehen. */
    private fun syncServiceLocked(force: Boolean = false) {
        val svc = service ?: return
        val current = session
        val anchor = if (current != null && current.mode != RingMode.FALLBACK) {
            AlertLogic.anchor(_active.value, current.keys, anchorKey)
        } else {
            null
        }
        if (current == null || anchor == null) {
            releaseServiceLocked(svc)
            return
        }
        val previous = anchorKey
        if (force || anchor.originKey != previous) {
            val id = AlertLogic.notificationId(anchor.originKey)
            if (!svc.foreground(id, notifications.build(anchor, AlertStyle.RINGING))) {
                // Das System lässt den Dienst nicht in den Vordergrund: Dann vibriert es selbst.
                svc.quiet()
                svc.finish(keepNotification = false)
                service = null
                anchorKey = null
                if (previous != null) posted.remove(previous)
                session = current.copy(mode = RingMode.FALLBACK, fallbackKey = anchor.originKey)
                return
            }
            // Wechselt der Dienst die Benachrichtigung, entfernt das System die bisherige.
            if (previous != null && previous != anchor.originKey) posted.remove(previous)
            anchorKey = anchor.originKey
            posted[anchor.originKey] = AlertStyle.RINGING
        }
        session = current.copy(mode = RingMode.SERVICE)
        svc.ring(current.vibrate, current.sound, current.until)
    }

    /** Beendet den Dienst. Die Benachrichtigung, die er trägt, bleibt stehen, solange ihre Erinnerung noch da ist. */
    private fun releaseServiceLocked(svc: AlertRingService) {
        val key = anchorKey
        val keep = key != null && _active.value.any { it.originKey == key }
        svc.quiet()
        svc.finish(keepNotification = keep)
        service = null
        anchorKey = null
        if (key != null && !keep) posted.remove(key)
    }

    /** Bringt die Benachrichtigungen auf den Stand von [active] und [session]. */
    private fun renderLocked() {
        val reminders = _active.value
        val current = session
        val keys = reminders.map { it.originKey }.toSet()
        posted.keys.filter { it !in keys }.forEach { key ->
            posted.remove(key)
            notifications.cancel(key)
        }
        orphans.retainAll(keys)
        val ringingKeys = current?.keys.orEmpty()
        // Sind Vibration und Ton abgeschaltet, vibriert auch der Rückfall nicht. Ist nur der Ton an, vibriert er
        // trotzdem: Einen Ton kann das System dort nicht spielen, und ganz still ginge die Erinnerung unter.
        val signal = current == null || current.vibrate || current.sound
        reminders.forEach { reminder ->
            val key = reminder.originKey
            // Die Benachrichtigung des Dienstes pflegt [syncServiceLocked]; verwaiste bleiben, wie sie sind.
            if (key == anchorKey || key in orphans) return@forEach
            val style = AlertLogic.style(key, ringingKeys, current?.mode, current?.fallbackKey, signal)
            val before = posted[key]
            if (before == style) return@forEach
            // Eine vom System vibrierende Benachrichtigung hört sicher erst auf, wenn sie ganz verschwindet.
            if (before == AlertStyle.INSISTENT) notifications.cancel(key)
            val timeout = if (style == AlertStyle.INSISTENT && current != null) {
                VibrationPlan.remaining(SystemClock.elapsedRealtime(), current.until) + FALLBACK_MARGIN_MILLIS
            } else {
                0L
            }
            if (notifications.post(reminder, style, timeout)) {
                posted[key] = style
            } else {
                posted.remove(key)
            }
        }
        _ringing.value = current != null
    }

    private companion object {
        const val TAG = "AlertController"

        /** So viel länger als die Klingelzeit lebt die vibrierende Benachrichtigung höchstens. */
        const val FALLBACK_MARGIN_MILLIS = 2_000L

        /** So lange nach dem Zurücklegen wird die stille Benachrichtigung noch einmal gezeigt. */
        const val PUT_BACK_CHECK_MILLIS = 1_000L
    }
}

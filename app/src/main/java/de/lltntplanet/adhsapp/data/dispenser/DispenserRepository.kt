package de.lltntplanet.adhsapp.data.dispenser

import de.lltntplanet.adhsapp.data.db.DispenserDao
import de.lltntplanet.adhsapp.data.db.DispenserEventEntity
import de.lltntplanet.adhsapp.data.db.DispenserEventKind
import de.lltntplanet.adhsapp.data.routines.RoutineRepository
import de.lltntplanet.adhsapp.util.Dates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** Eine mitgelesene Benachrichtigung, unabhängig von Android (für Tests). */
data class DispenserNotification(
    val packageName: String,
    val key: String,
    val postedAt: Long,
    val title: String,
    val text: String,
)

/** Meldungen des Medikamentenspenders speichern und „genommen“ im Medikamenten-Modul abhaken. */
class DispenserRepository(
    private val dao: DispenserDao,
    private val routines: RoutineRepository,
    /** Eine schon angezeigte Erinnerung zu dieser Einnahme (Schlüssel „rt:…“) wegräumen. */
    private val cancelReminder: (String) -> Unit,
) {
    private val mutex = Mutex()

    fun observeRecent(limit: Int = 20): Flow<List<DispenserEventEntity>> = dao.observeRecent(limit)

    /**
     * Neue Benachrichtigung der Spender-App verarbeiten. Gibt das gespeicherte Ereignis zurück, oder null,
     * wenn sie schon verarbeitet war oder nicht zum Spender gehört.
     */
    suspend fun onNotification(n: DispenserNotification, zone: ZoneId = ZoneId.systemDefault()): DispenserEventEntity? {
        return mutex.withLock {
            if (n.title.isBlank() && n.text.isBlank()) return null
            val kind = DispenserLogic.classify(n.title, n.text)
            // Smart Life & Co. melden auch Lampen und Steckdosen – von dort nur erkannte Spender-Meldungen behalten.
            if (kind == DispenserEventKind.OTHER && !DispenserLogic.isDedicatedApp(n.packageName)) return null
            var event = DispenserEventEntity(
                postedAt = n.postedAt,
                notificationKey = n.key,
                packageName = n.packageName,
                title = n.title.take(MAX_TITLE),
                text = n.text.take(MAX_TEXT),
                kind = kind,
            )
            val match = if (kind == DispenserEventKind.TAKEN) {
                val day = Dates.toLocalDate(n.postedAt, zone)
                DispenserLogic.matchTaken(
                    routines.active(),
                    routines.log(day.minusDays(1), day),
                    n.postedAt,
                    zone,
                    DispenserLogic.timesIn("${n.title} ${n.text}"),
                )
            } else {
                null
            }
            if (match != null) {
                event = event.copy(
                    matchedEpochDay = match.date.toEpochDay(),
                    matchedMinutes = match.minutes,
                    matchedNames = match.occurrences.joinToString(", ") { it.routine.name },
                )
            }
            if (dao.insert(event) == -1L) return null
            var changed = false
            match?.open?.forEach { o ->
                if (routines.setDoneByDispenser(o.routine.id, o.date, o.minutes, n.postedAt)) {
                    changed = true
                    cancelReminder(o.key)
                }
            }
            if (changed) routines.notifyChanged()
            event
        }
    }

    suspend fun clear() = dao.clear()

    suspend fun purgeOld(days: Long, now: Long = System.currentTimeMillis()) = dao.purge(now - days * DAY_MILLIS)

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_TEXT = 1000
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

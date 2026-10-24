package de.lltntplanet.adhsapp.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Zugriff auf Google Kalender über den Kalender-Speicher von Android.
 * Google synchronisiert die Kalender des Kontos automatisch dorthin – dadurch funktioniert
 * alles auch offline und ohne zusätzliche Google-Cloud-Freigaben.
 */
class CalendarRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
        )
        safeQuery {
            resolver.query(Calendars.CONTENT_URI, projection, "${Calendars.SYNC_EVENTS} = 1", null, null)
        }?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val access = c.getInt(6)
                    val account = c.getString(2).orEmpty()
                    val owner = c.getStringOrNull(4)
                    add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.getStringOrNull(1) ?: account,
                            accountName = account,
                            accountType = c.getString(3).orEmpty(),
                            ownerAccount = owner,
                            color = c.getInt(5),
                            canWrite = access >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                            isPrimary = (!c.isNull(7) && c.getInt(7) == 1) || (owner != null && owner == account),
                        ),
                    )
                }
            }.sortedWith(compareByDescending<CalendarInfo> { it.isPrimary }.thenBy { it.accountName }.thenBy { it.name })
        } ?: emptyList()
    }

    /**
     * Alle Termin-Vorkommen, die zwischen [from] (inklusive) und [toExclusive] stattfinden.
     * Ganztägige Termine werden über ihr UTC-Datum korrekt den lokalen Tagen zugeordnet.
     */
    suspend fun instances(from: LocalDate, toExclusive: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<EventInstance> {
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val lastDay = toExclusive.minusDays(1)
        return instancesBetween(begin, end).filter { e ->
            !e.endDateInclusive(zone).isBefore(from) && !e.startDate(zone).isAfter(lastDay)
        }
    }

    suspend fun instancesBetween(beginMillis: Long, endMillis: Long): List<EventInstance> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, beginMillis)
            ContentUris.appendId(it, endMillis)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.BEGIN,
            Instances.END,
            Instances.TITLE,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
            Instances.EVENT_LOCATION,
            Instances.DESCRIPTION,
            Instances.CALENDAR_ID,
            Instances.CALENDAR_DISPLAY_NAME,
            Instances.RRULE,
            Instances.ORIGINAL_ID,
            Instances.SELF_ATTENDEE_STATUS,
            Instances.STATUS,
            Instances.CALENDAR_ACCESS_LEVEL,
            Instances.RDATE,
        )
        safeQuery { resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC") }?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        EventInstance(
                            eventId = c.getLong(0),
                            begin = c.getLong(1),
                            end = c.getLong(2),
                            title = c.getStringOrNull(3).orEmpty(),
                            allDay = c.getInt(4) == 1,
                            color = c.getInt(5),
                            location = c.getStringOrNull(6)?.takeIf { it.isNotBlank() },
                            description = c.getStringOrNull(7)?.takeIf { it.isNotBlank() },
                            calendarId = c.getLong(8),
                            calendarName = c.getStringOrNull(9).orEmpty(),
                            recurring = !c.getStringOrNull(10).isNullOrBlank() || !c.isNull(11) ||
                                !c.getStringOrNull(15).isNullOrBlank(),
                            declined = !c.isNull(12) &&
                                c.getInt(12) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                            canceled = !c.isNull(13) && c.getInt(13) == Events.STATUS_CANCELED,
                            canEdit = c.getInt(14) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /** In Google Kalender hinterlegte Erinnerungen (Minuten vor Beginn) je Termin. */
    suspend fun eventReminders(eventIds: Collection<Long>): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        if (!hasReadPermission() || eventIds.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<Long, MutableList<Int>>()
        eventIds.distinct().chunked(400).forEach { chunk ->
            val selection = "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")}) AND " +
                "${Reminders.METHOD} IN (${Reminders.METHOD_ALERT}, ${Reminders.METHOD_DEFAULT})"
            safeQuery {
                resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES), selection, null, null)
            }?.use { c ->
                while (c.moveToNext()) {
                    val minutes = c.getInt(1)
                    // -1 bedeutet "Standard des Kalenders" – den kennen wir nicht, also 10 Minuten wie bei Google.
                    result.getOrPut(c.getLong(0)) { mutableListOf() }.add(if (minutes < 0) 10 else minutes)
                }
            }
        }
        result
    }

    /** Lädt einen Termin zum Bearbeiten. Wiederkehrende Termine werden nicht unterstützt (null). */
    suspend fun loadDraft(eventId: Long, zone: ZoneId = ZoneId.systemDefault()): EventDraft? = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext null
        val projection = arrayOf(
            Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.RRULE,
        )
        val draft = safeQuery {
            resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), projection, null, null, null)
        }?.use { c ->
            if (!c.moveToFirst() || !c.getStringOrNull(7).isNullOrBlank() || c.isNull(5)) return@use null
            val allDay = c.getInt(6) == 1
            val z = if (allDay) ZoneOffset.UTC else zone
            val start = java.time.Instant.ofEpochMilli(c.getLong(4)).atZone(z).toLocalDateTime()
            var end = java.time.Instant.ofEpochMilli(c.getLong(5)).atZone(z).toLocalDateTime()
            if (allDay) end = end.minusDays(1)
            EventDraft(
                eventId = eventId,
                calendarId = c.getLong(0),
                title = c.getStringOrNull(1).orEmpty(),
                description = c.getStringOrNull(2).orEmpty(),
                location = c.getStringOrNull(3).orEmpty(),
                allDay = allDay,
                start = start,
                end = end,
            )
        } ?: return@withContext null
        draft.copy(reminders = eventReminders(listOf(eventId))[eventId].orEmpty().sorted())
    }

    suspend fun save(draft: EventDraft, zone: ZoneId = ZoneId.systemDefault()): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, draft.calendarId)
            put(Events.TITLE, draft.title)
            put(Events.DESCRIPTION, draft.description)
            put(Events.EVENT_LOCATION, draft.location)
            put(Events.HAS_ALARM, if (draft.reminders.isEmpty()) 0 else 1)
            if (draft.allDay) {
                val startDay = draft.start.toLocalDate()
                val endDay = maxOf(draft.end.toLocalDate(), startDay).plusDays(1)
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DTSTART, startDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, endDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            } else {
                val start = draft.start.atZone(zone).toInstant().toEpochMilli()
                val end = maxOf(draft.end.atZone(zone).toInstant().toEpochMilli(), start)
                put(Events.ALL_DAY, 0)
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.DTSTART, start)
                put(Events.DTEND, end)
            }
        }
        val id = if (draft.eventId == null) {
            val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Termin konnte nicht gespeichert werden")
            ContentUris.parseId(uri)
        } else {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, draft.eventId), values, null, null)
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(draft.eventId.toString()))
            draft.eventId
        }
        draft.reminders.distinct().forEach { minutes ->
            resolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, id)
                    put(Reminders.MINUTES, minutes)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
        id
    }

    suspend fun delete(eventId: Long) = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    /** Meldet jede Änderung im Kalender-Speicher (z. B. nach einer Google-Synchronisierung). */
    fun changes(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }.isSuccess
        awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
    }.conflate()

    private inline fun safeQuery(block: () -> Cursor?): Cursor? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "Keine Kalenderberechtigung", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Kalenderabfrage fehlgeschlagen", e)
        null
    }

    companion object {
        private const val TAG = "CalendarRepository"

        fun defaultDraft(calendarId: Long, start: LocalDateTime): EventDraft =
            EventDraft(calendarId = calendarId, title = "", start = start, end = start.plusHours(1))
    }
}

internal fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

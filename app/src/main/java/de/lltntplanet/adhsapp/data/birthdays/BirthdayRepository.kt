package de.lltntplanet.adhsapp.data.birthdays

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.util.Log
import androidx.core.content.ContextCompat
import de.lltntplanet.adhsapp.data.calendar.CalendarRepository
import de.lltntplanet.adhsapp.data.db.BirthdayDao
import de.lltntplanet.adhsapp.data.db.BirthdayEntity
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Geburtstage aus den (Google-)Kontakten des Geräts, aus dem Google Kalender
 * (Geburtstagskalender und jährliche Termine wie „Annas Geburtstag“) plus selbst eingetragene.
 */
class BirthdayRepository(
    private val context: Context,
    private val dao: BirthdayDao,
    private val calendar: CalendarRepository,
    private val settings: SettingsRepository,
) {

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun all(): List<Birthday> = withContext(Dispatchers.IO) {
        BirthdayMath.dedupe(contactBirthdays() + calendarBirthdays() + dao.all().map { it.toBirthday() })
    }

    fun observeAll(): Flow<List<Birthday>> =
        combine(
            contactChanges().map { contactBirthdays() }.flowOn(Dispatchers.IO),
            combine(calendar.changes(), settings.settings.map { it.birthdaysFromCalendar }.distinctUntilChanged()) { _, _ -> }
                .map { calendarBirthdays() }
                .flowOn(Dispatchers.IO),
            dao.observeAll(),
        ) { contacts, fromCalendar, manual ->
            BirthdayMath.dedupe(contacts + fromCalendar + manual.map { it.toBirthday() })
        }

    /**
     * Geburtstage aus dem Google Kalender: alle Einträge des automatischen Geburtstagskalenders sowie
     * ganztägige, wiederkehrende Termine mit „Geburtstag“/„birthday“ im Titel aus allen Kalendern.
     * Betrachtet wird das kommende Jahr, so kommt jeder Geburtstag genau einmal vor.
     */
    suspend fun calendarBirthdays(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Birthday> {
        if (!settings.current().birthdaysFromCalendar || !calendar.hasReadPermission()) return emptyList()
        val birthdayCalendars = calendar.calendars().filter { it.isBirthdayCalendar }.map { it.id }.toSet()
        val instances = runCatching { calendar.instances(today, today.plusYears(1), zone) }.getOrElse { emptyList() }
        return BirthdayMath.fromCalendar(instances, birthdayCalendars, zone)
    }

    suspend fun saveManual(entity: BirthdayEntity) = dao.upsert(entity)

    suspend fun deleteManual(entity: BirthdayEntity) = dao.delete(entity)

    private fun contactBirthdays(): List<Birthday> {
        if (!hasContactsPermission()) return emptyList()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME,
            Event.START_DATE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} = ?"
        val args = arrayOf(Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString())
        return try {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val parsed = BirthdayMath.parseContactDate(c.getString(2)) ?: continue
                        val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        add(
                            Birthday(
                                id = "c:${c.getLong(0)}",
                                name = name,
                                year = parsed.first,
                                month = parsed.second,
                                day = parsed.third,
                                source = BirthdaySource.CONTACT,
                            ),
                        )
                    }
                }.distinctBy { it.id }
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.w("BirthdayRepository", "Keine Kontaktberechtigung", e)
            emptyList()
        }
    }

    private fun contactChanges(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }.isSuccess
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()
}

fun BirthdayEntity.toBirthday() = Birthday(
    id = "m:$id",
    name = name,
    month = month,
    day = day,
    year = year,
    source = BirthdaySource.MANUAL,
    manualId = id,
    note = note,
)

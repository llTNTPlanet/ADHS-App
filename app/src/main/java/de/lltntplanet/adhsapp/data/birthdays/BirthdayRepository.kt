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
import de.lltntplanet.adhsapp.data.db.BirthdayDao
import de.lltntplanet.adhsapp.data.db.BirthdayEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Geburtstage aus den (Google-)Kontakten des Geräts plus selbst eingetragene. */
class BirthdayRepository(private val context: Context, private val dao: BirthdayDao) {

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun all(): List<Birthday> = withContext(Dispatchers.IO) {
        BirthdayMath.dedupe(contactBirthdays() + dao.all().map { it.toBirthday() })
    }

    fun observeAll(): Flow<List<Birthday>> =
        combine(contactChanges().map { contactBirthdays() }.flowOn(Dispatchers.IO), dao.observeAll()) { contacts, manual ->
            BirthdayMath.dedupe(contacts + manual.map { it.toBirthday() })
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

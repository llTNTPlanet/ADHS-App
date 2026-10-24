package de.lltntplanet.adhsapp.screenshots

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Kalender-Speicher mit Beispielterminen für die Screenshots (Robolectric hat keinen echten). */
class FakeCalendarProvider : ContentProvider() {

    data class Cal(val id: Long, val name: String, val color: Int)

    data class Ev(
        val id: Long,
        val cal: Cal,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean = false,
        val location: String? = null,
    )

    companion object {
        private val zone = ZoneId.of("Europe/Berlin")
        val privat = Cal(1, "Privat", 0xFF33B679.toInt())
        val arbeit = Cal(2, "Arbeit", 0xFF039BE5.toInt())
        val familie = Cal(3, "Familie", 0xFFF4511E.toInt())
        private val calendars = listOf(privat, arbeit, familie)

        private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
        private fun utc(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        fun events(now: Long = System.currentTimeMillis()): List<Ev> {
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val nextStart = ((now / 300_000L) + 15) * 300_000L // in ca. 75 Minuten
            var id = 100L
            val list = mutableListOf(
                Ev(id++, privat, "Tabletten & Frühstück", at(today, 7, 30), at(today, 8, 0)),
                Ev(id++, arbeit, "Team-Meeting", at(today, 9, 30), at(today, 10, 30), location = "Raum 2.14"),
                Ev(id++, privat, "Zahnarzt Dr. Weber", at(today, 10, 0), at(today, 11, 0), location = "Hauptstraße 12"),
                Ev(id++, familie, "Mittagessen mit Lena", at(today, 12, 30), at(today, 13, 30)),
                Ev(id++, privat, "Physiotherapie", nextStart, nextStart + 60 * 60_000L, location = "Praxis am Park"),
                Ev(id++, familie, "Kino", at(today, 20, 0), at(today, 22, 15)),
                Ev(id++, privat, "Gelbe Tonne rausstellen", utc(today), utc(today.plusDays(1)), allDay = true),
                Ev(id++, arbeit, "Elternabend", at(today.plusDays(1), 18, 0), at(today.plusDays(1), 19, 30), location = "Grundschule"),
                Ev(id++, privat, "Sport", at(today.plusDays(1), 7, 0), at(today.plusDays(1), 8, 0)),
                Ev(id++, familie, "Urlaub Ostsee", utc(today.plusDays(5)), utc(today.plusDays(9)), allDay = true),
                Ev(id++, arbeit, "Projekt-Deadline", at(today.plusDays(2), 16, 0), at(today.plusDays(2), 17, 0)),
                Ev(id++, privat, "Friseur", at(today.plusDays(3), 15, 0), at(today.plusDays(3), 16, 0)),
                Ev(id++, arbeit, "1:1 mit Chefin", at(today.plusDays(2), 11, 0), at(today.plusDays(2), 11, 30)),
                Ev(id++, familie, "Oma besuchen", at(today.plusDays(-1), 15, 0), at(today.plusDays(-1), 17, 0)),
                Ev(id++, privat, "Steuerberater", at(today.plusDays(12), 10, 0), at(today.plusDays(12), 11, 0)),
                Ev(id++, familie, "Fußball Training", at(today.plusDays(4), 17, 0), at(today.plusDays(4), 18, 30)),
                Ev(id++, privat, "Arzt: Rezept abholen", at(today.plusDays(-3), 8, 15), at(today.plusDays(-3), 8, 45)),
                Ev(id++, arbeit, "Workshop", at(today.plusDays(15), 9, 0), at(today.plusDays(15), 16, 0)),
            )
            return list
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val segments = uri.pathSegments
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        when (segments.firstOrNull()) {
            "calendars" -> calendars.forEach { c ->
                cursor.addRow(
                    columns.map { col ->
                        when (col) {
                            Calendars._ID -> c.id
                            Calendars.CALENDAR_DISPLAY_NAME -> c.name
                            Calendars.ACCOUNT_NAME -> "ich@gmail.com"
                            Calendars.ACCOUNT_TYPE -> "com.google"
                            Calendars.OWNER_ACCOUNT -> if (c.id == 1L) "ich@gmail.com" else "${c.name.lowercase()}@group.calendar.google.com"
                            Calendars.CALENDAR_COLOR -> c.color
                            Calendars.CALENDAR_ACCESS_LEVEL -> Calendars.CAL_ACCESS_OWNER
                            Calendars.IS_PRIMARY -> if (c.id == 1L) 1 else 0
                            else -> null
                        }
                    },
                )
            }
            "instances" -> {
                val begin = segments.getOrNull(2)?.toLongOrNull() ?: Long.MIN_VALUE
                val end = segments.getOrNull(3)?.toLongOrNull() ?: Long.MAX_VALUE
                events().filter { it.end > begin && it.begin < end }.sortedBy { it.begin }.forEach { e ->
                    cursor.addRow(
                        columns.map { col ->
                            when (col) {
                                Instances.EVENT_ID -> e.id
                                Instances.BEGIN -> e.begin
                                Instances.END -> e.end
                                Instances.TITLE -> e.title
                                Instances.ALL_DAY -> if (e.allDay) 1 else 0
                                Instances.DISPLAY_COLOR -> e.cal.color
                                Instances.EVENT_LOCATION -> e.location
                                Instances.CALENDAR_ID -> e.cal.id
                                Instances.CALENDAR_DISPLAY_NAME -> e.cal.name
                                Instances.CALENDAR_ACCESS_LEVEL -> Calendars.CAL_ACCESS_OWNER
                                else -> null
                            }
                        },
                    )
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

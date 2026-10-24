package de.lltntplanet.adhsapp.ui.calendar

import de.lltntplanet.adhsapp.data.calendar.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Ein Termin im Zeitraster eines Tages: Minuten seit Mitternacht und Spalte bei Überschneidungen. */
data class PositionedEvent(
    val event: EventInstance,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columns: Int,
)

object EventLayout {

    const val MIN_DURATION_MINUTES = 20

    /**
     * Ordnet die zeitgebundenen Termine eines Tages wie in Google Kalender an:
     * Überschneidende Termine teilen sich die Breite nebeneinander.
     */
    fun layoutDay(events: List<EventInstance>, date: LocalDate, zone: ZoneId): List<PositionedEvent> {
        data class Seg(val e: EventInstance, val start: Int, val end: Int)

        val segments = events.filter { !it.allDay && it.occursOn(date, zone) }.map { e ->
            val start = Instant.ofEpochMilli(e.begin).atZone(zone)
            val end = Instant.ofEpochMilli(e.end).atZone(zone)
            val s = if (start.toLocalDate().isBefore(date)) 0 else start.toLocalTime().toSecondOfDay() / 60
            val rawEnd = if (end.toLocalDate().isAfter(date)) 24 * 60 else end.toLocalTime().toSecondOfDay() / 60
            Seg(e, s, maxOf(rawEnd, minOf(s + MIN_DURATION_MINUTES, 24 * 60)))
        }.sortedWith(compareBy<Seg> { it.start }.thenByDescending { it.end - it.start })

        val result = mutableListOf<PositionedEvent>()
        var cluster = mutableListOf<Pair<Seg, Int>>()
        var columnEnds = mutableListOf<Int>()
        var clusterEnd = -1

        fun flush() {
            val count = columnEnds.size.coerceAtLeast(1)
            cluster.forEach { (seg, col) -> result += PositionedEvent(seg.e, seg.start, seg.end, col, count) }
            cluster = mutableListOf()
            columnEnds = mutableListOf()
            clusterEnd = -1
        }

        for (seg in segments) {
            if (cluster.isNotEmpty() && seg.start >= clusterEnd) flush()
            val col = columnEnds.indexOfFirst { it <= seg.start }.let { if (it == -1) columnEnds.size else it }
            if (col == columnEnds.size) columnEnds += seg.end else columnEnds[col] = seg.end
            cluster += seg to col
            clusterEnd = maxOf(clusterEnd, seg.end)
        }
        flush()
        return result
    }
}

package de.lltntplanet.adhsapp.data.health

import de.lltntplanet.adhsapp.data.google.DailyRollupDataPoint
import de.lltntplanet.adhsapp.data.google.DataPoint
import de.lltntplanet.adhsapp.data.google.GoogleJson
import de.lltntplanet.adhsapp.data.google.HealthApi
import de.lltntplanet.adhsapp.data.google.NotAuthorizedException
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@Serializable
data class HealthDay(
    val epochDay: Long,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val kcal: Double? = null,
    val activeZoneMinutes: Long? = null,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
}

@Serializable
data class SleepNight(
    /** Tag, an dem die Nacht endete (Aufwachtag). */
    val epochDay: Long,
    val start: Long,
    val end: Long,
    val minutesAsleep: Long,
    val minutesAwake: Long = 0,
    val deep: Long? = null,
    val light: Long? = null,
    val rem: Long? = null,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
}

@Serializable
data class HeartDay(val epochDay: Long, val bpm: Long) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
}

@Serializable
data class HealthSnapshot(
    val fetchedAt: Long,
    val days: List<HealthDay> = emptyList(),
    val sleepNights: List<SleepNight> = emptyList(),
    val restingHeartRate: List<HeartDay> = emptyList(),
    /** Bereich → Fehlermeldung, falls ein Teil nicht geladen werden konnte. */
    val errors: Map<String, String> = emptyMap(),
) {
    fun day(date: LocalDate): HealthDay? = days.find { it.epochDay == date.toEpochDay() }

    val lastSleep: SleepNight? get() = sleepNights.maxByOrNull { it.end }

    val latestRestingHeartRate: HeartDay? get() = restingHeartRate.maxByOrNull { it.epochDay }
}

class HealthRepository(
    private val api: HealthApi,
    private val settings: SettingsRepository,
) {
    val snapshot: Flow<HealthSnapshot?> = settings.healthCache.map { json ->
        json?.let { runCatching { GoogleJson.decodeFromString<HealthSnapshot>(it) }.getOrNull() }
    }

    /** Lädt die letzten 7 Tage. Einzelne Bereiche dürfen fehlschlagen, ohne alles zu blockieren. */
    suspend fun refresh(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): HealthSnapshot =
        coroutineScope {
            val start = today.minusDays(6)
            val end = today.plusDays(1)
            val steps = async { runCatching { api.dailyRollUp("steps", start, end) } }
            val distance = async { runCatching { api.dailyRollUp("distance", start, end) } }
            val calories = async { runCatching { api.dailyRollUp("total-calories", start, end) } }
            val azm = async { runCatching { api.dailyRollUp("active-zone-minutes", start, end) } }
            val sleep = async {
                runCatching {
                    api.list(
                        "sleep",
                        "sleep.interval.civil_end_time >= \"$start\" AND sleep.interval.civil_end_time < \"$end\"",
                    )
                }
            }
            val rhr = async {
                runCatching {
                    api.list(
                        "daily-resting-heart-rate",
                        "daily_resting_heart_rate.date >= \"$start\" AND daily_resting_heart_rate.date < \"$end\"",
                    )
                }
            }
            val results = mapOf(
                "Schritte" to steps.await(),
                "Strecke" to distance.await(),
                "Kalorien" to calories.await(),
                "Aktivzonenminuten" to azm.await(),
                "Schlaf" to sleep.await(),
                "Ruhepuls" to rhr.await(),
            )
            val failures = results.mapNotNull { (k, v) -> v.exceptionOrNull()?.let { k to it } }
            if (failures.size == results.size) {
                // Nichts ging – meistens fehlt die Anmeldung. Den ersten Fehler weiterreichen.
                throw failures.firstOrNull { it.second is NotAuthorizedException }?.second ?: failures.first().second
            }
            val snapshot = HealthMapper.build(
                fetchedAt = System.currentTimeMillis(),
                start = start,
                endExclusive = end,
                steps = steps.await().getOrNull(),
                distance = distance.await().getOrNull(),
                calories = calories.await().getOrNull(),
                azm = azm.await().getOrNull(),
                sleep = sleep.await().getOrNull(),
                restingHeartRate = rhr.await().getOrNull(),
                zone = zone,
                errors = failures.associate { (k, e) -> k to (e.message ?: e.javaClass.simpleName) },
            )
            settings.saveHealthCache(GoogleJson.encodeToString(snapshot))
            snapshot
        }

    suspend fun clear() = settings.saveHealthCache(null)
}

/** Wandelt API-Antworten in ein kompaktes, cachebares Modell um. */
object HealthMapper {

    fun build(
        fetchedAt: Long,
        start: LocalDate,
        endExclusive: LocalDate,
        steps: List<DailyRollupDataPoint>?,
        distance: List<DailyRollupDataPoint>?,
        calories: List<DailyRollupDataPoint>?,
        azm: List<DailyRollupDataPoint>?,
        sleep: List<DataPoint>?,
        restingHeartRate: List<DataPoint>?,
        zone: ZoneId,
        errors: Map<String, String> = emptyMap(),
    ): HealthSnapshot {
        fun byDay(points: List<DailyRollupDataPoint>?) =
            points.orEmpty().mapNotNull { p -> p.civilStartTime?.date?.toLocalDate()?.let { it to p } }.toMap()

        val stepsByDay = byDay(steps)
        val distanceByDay = byDay(distance)
        val caloriesByDay = byDay(calories)
        val azmByDay = byDay(azm)

        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(endExclusive) }.map { d ->
            HealthDay(
                epochDay = d.toEpochDay(),
                steps = stepsByDay[d]?.steps?.countSum?.toLongOrNull(),
                distanceMeters = distanceByDay[d]?.distance?.millimetersSum?.toLongOrNull()?.let { it / 1000.0 },
                kcal = caloriesByDay[d]?.totalCalories?.kcalSum,
                activeZoneMinutes = azmByDay[d]?.activeZoneMinutes?.total,
            )
        }.toList()

        return HealthSnapshot(
            fetchedAt = fetchedAt,
            days = days,
            sleepNights = sleepNights(sleep.orEmpty(), zone),
            restingHeartRate = restingHeartRate.orEmpty().mapNotNull { p ->
                val data = p.dailyRestingHeartRate ?: return@mapNotNull null
                val date = data.date?.toLocalDate() ?: return@mapNotNull null
                val bpm = data.beatsPerMinute?.toLongOrNull() ?: return@mapNotNull null
                HeartDay(date.toEpochDay(), bpm)
            }.distinctBy { it.epochDay }.sortedBy { it.epochDay },
            errors = errors,
        )
    }

    /** Pro Aufwachtag die Hauptschlafphase (bzw. die längste), Nickerchen werden ignoriert. */
    fun sleepNights(points: List<DataPoint>, zone: ZoneId): List<SleepNight> {
        val nights = points.mapNotNull { p ->
            val s = p.sleep ?: return@mapNotNull null
            if (s.metadata?.nap == true) return@mapNotNull null
            val start = s.interval?.startTime?.let(::parseInstant) ?: return@mapNotNull null
            val end = s.interval.endTime?.let(::parseInstant) ?: return@mapNotNull null
            val wakeDay = s.interval.civilEndTime?.date?.toLocalDate()
                ?: java.time.Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
            val summary = s.summary
            fun stage(vararg types: String): Long? {
                val matching = summary?.stagesSummary.orEmpty().filter { it.type in types }
                return if (matching.isEmpty()) null else matching.sumOf { it.minutes?.toLongOrNull() ?: 0L }
            }
            val asleep = summary?.minutesAsleep?.toLongOrNull() ?: ((end - start) / 60_000L)
            Triple(
                s.metadata?.mainSleep == true,
                asleep,
                SleepNight(
                    epochDay = wakeDay.toEpochDay(),
                    start = start,
                    end = end,
                    minutesAsleep = asleep,
                    minutesAwake = summary?.minutesAwake?.toLongOrNull() ?: 0L,
                    deep = stage("DEEP"),
                    light = stage("LIGHT", "ASLEEP"),
                    rem = stage("REM"),
                ),
            )
        }
        return nights.groupBy { it.third.epochDay }.map { (_, list) ->
            list.maxWith(compareBy<Triple<Boolean, Long, SleepNight>> { it.first }.thenBy { it.second }).third
        }.sortedBy { it.epochDay }
    }

    fun parseInstant(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
}

package de.lltntplanet.adhsapp.data.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate

// Ausschnitt der Google Health API v4 (https://developers.google.com/health/reference/rest).
// int64-Werte werden von der API als Strings geliefert.

@Serializable
data class ApiDate(val year: Int = 0, val month: Int = 0, val day: Int = 0) {
    fun toLocalDate(): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    companion object {
        fun of(date: LocalDate) = ApiDate(date.year, date.monthValue, date.dayOfMonth)
    }
}

@Serializable
data class CivilDateTime(val date: ApiDate? = null)

@Serializable
data class CivilTimeInterval(val start: CivilDateTime, val end: CivilDateTime)

@Serializable
data class DailyRollUpRequest(
    val range: CivilTimeInterval,
    /** Ohne Standardwert, damit das Feld immer gesendet wird. */
    val windowSizeDays: Int,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)

@Serializable
data class DailyRollUpResponse(
    val rollupDataPoints: List<DailyRollupDataPoint> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class DailyRollupDataPoint(
    val civilStartTime: CivilDateTime? = null,
    val civilEndTime: CivilDateTime? = null,
    val steps: StepsRollup? = null,
    val distance: DistanceRollup? = null,
    val totalCalories: TotalCaloriesRollup? = null,
    val activeZoneMinutes: ActiveZoneMinutesRollup? = null,
    val heartRate: HeartRateRollup? = null,
)

@Serializable
data class StepsRollup(val countSum: String? = null)

@Serializable
data class DistanceRollup(val millimetersSum: String? = null)

@Serializable
data class TotalCaloriesRollup(val kcalSum: Double? = null)

@Serializable
data class ActiveZoneMinutesRollup(
    val sumInFatBurnHeartZone: String? = null,
    val sumInCardioHeartZone: String? = null,
    val sumInPeakHeartZone: String? = null,
) {
    val total: Long
        get() = listOfNotNull(sumInFatBurnHeartZone, sumInCardioHeartZone, sumInPeakHeartZone)
            .sumOf { it.toLongOrNull() ?: 0L }
}

@Serializable
data class HeartRateRollup(
    val beatsPerMinuteAvg: Double? = null,
    val beatsPerMinuteMin: Double? = null,
    val beatsPerMinuteMax: Double? = null,
)

@Serializable
data class ListDataPointsResponse(
    val dataPoints: List<DataPoint> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class DataPoint(
    val name: String? = null,
    val sleep: SleepData? = null,
    val dailyRestingHeartRate: DailyRestingHeartRateData? = null,
)

@Serializable
data class SleepData(
    val interval: SessionInterval? = null,
    val summary: SleepSummaryData? = null,
    val metadata: SleepMetadataData? = null,
    val type: String? = null,
)

@Serializable
data class SessionInterval(
    val startTime: String? = null,
    val endTime: String? = null,
    val civilStartTime: CivilDateTime? = null,
    val civilEndTime: CivilDateTime? = null,
)

@Serializable
data class SleepSummaryData(
    val minutesAsleep: String? = null,
    val minutesAwake: String? = null,
    val minutesInSleepPeriod: String? = null,
    val minutesToFallAsleep: String? = null,
    val stagesSummary: List<StageSummaryData> = emptyList(),
)

@Serializable
data class StageSummaryData(val type: String? = null, val minutes: String? = null, val count: String? = null)

@Serializable
data class SleepMetadataData(val mainSleep: Boolean? = null, val nap: Boolean? = null, val processed: Boolean? = null)

@Serializable
data class DailyRestingHeartRateData(val date: ApiDate? = null, val beatsPerMinute: String? = null)

class HealthApi(
    private val http: GoogleHttp,
    private val baseUrl: String = "https://health.googleapis.com/v4/",
) {
    /** Tageswerte (Kalendertage in der Zeitzone des Nutzers) für [start] bis [endExclusive]. */
    suspend fun dailyRollUp(dataType: String, start: LocalDate, endExclusive: LocalDate): List<DailyRollupDataPoint> {
        val url = "${baseUrl}users/me/dataTypes/$dataType/dataPoints:dailyRollUp"
        val result = mutableListOf<DailyRollupDataPoint>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val request = DailyRollUpRequest(
                range = CivilTimeInterval(CivilDateTime(ApiDate.of(start)), CivilDateTime(ApiDate.of(endExclusive))),
                windowSizeDays = 1,
                pageToken = pageToken,
            )
            val text = http.post(GoogleFeature.HEALTH, url, GoogleJson.encodeToString(request))
            val response = GoogleJson.decodeFromString<DailyRollUpResponse>(text)
            result += response.rollupDataPoints
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() } ?: return result
        }
        return result
    }

    /** Rohdatenpunkte mit AIP-160-Filter, z. B. `sleep.interval.civil_end_time >= "2026-10-20"`. */
    suspend fun list(dataType: String, filter: String, pageSize: Int = 100): List<DataPoint> {
        val result = mutableListOf<DataPoint>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = "${baseUrl}users/me/dataTypes/$dataType/dataPoints".toHttpUrl().newBuilder()
                .addQueryParameter("filter", filter)
                .addQueryParameter("pageSize", pageSize.toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
                .toString()
            val response = GoogleJson.decodeFromString<ListDataPointsResponse>(http.get(GoogleFeature.HEALTH, url))
            result += response.dataPoints
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() } ?: return result
        }
        return result
    }

    companion object {
        private const val MAX_PAGES = 5
    }
}

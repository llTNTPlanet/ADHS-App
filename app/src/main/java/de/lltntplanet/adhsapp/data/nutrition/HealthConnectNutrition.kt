package de.lltntplanet.adhsapp.data.nutrition

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType as HcMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import de.lltntplanet.adhsapp.data.db.FoodEntryEntity
import de.lltntplanet.adhsapp.data.db.MealType
import java.time.Instant
import java.time.ZoneId

/**
 * Schreibt Mahlzeiten in Health Connect (kostenlos, lokal auf dem Handy). Die Google-Health-App übernimmt
 * Ernährungsdaten von dort. Jeder Eintrag bekommt eine feste Kennung, damit Änderungen und Löschungen mitgehen.
 */
class HealthConnectNutrition(private val context: Context) {

    val permission: String = HealthPermission.getWritePermission(NutritionRecord::class)

    /** Ist Health Connect auf diesem Gerät nutzbar? */
    val available: Boolean
        get() = runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    val needsUpdate: Boolean
        get() = runCatching {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        }.getOrDefault(false)

    private val client: HealthConnectClient? get() = if (available) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    suspend fun hasPermission(): Boolean =
        runCatching { client?.permissionController?.getGrantedPermissions()?.contains(permission) == true }.getOrDefault(false)

    suspend fun write(entry: FoodEntryEntity, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val hc = client ?: return false
        if (!hasPermission()) return false
        return runCatching {
            val start = Instant.ofEpochMilli(entry.createdAt)
            val offset = zone.rules.getOffset(start)
            hc.insertRecords(
                listOf(
                    NutritionRecord(
                        startTime = start,
                        startZoneOffset = offset,
                        endTime = start.plusSeconds(60),
                        endZoneOffset = offset,
                        energy = Energy.kilocalories(entry.kcal.toDouble()),
                        name = listOf(entry.name, entry.amount).filter { it.isNotBlank() }.joinToString(" · "),
                        mealType = entry.meal.toHealthConnect(),
                        metadata = Metadata.manualEntryWithId(clientId(entry.id)),
                    ),
                ),
            )
            true
        }.onFailure { Log.w(TAG, "Health Connect: Eintrag konnte nicht geschrieben werden", it) }.getOrDefault(false)
    }

    suspend fun delete(entryId: Long): Boolean {
        val hc = client ?: return false
        if (!hasPermission()) return false
        return runCatching {
            hc.deleteRecords(NutritionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = listOf(clientId(entryId)))
            true
        }.onFailure { Log.w(TAG, "Health Connect: Eintrag konnte nicht gelöscht werden", it) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "HealthConnect"
        fun clientId(entryId: Long) = "adhs-food-$entryId"

        fun MealType.toHealthConnect(): Int = when (this) {
            MealType.BREAKFAST -> HcMealType.MEAL_TYPE_BREAKFAST
            MealType.LUNCH -> HcMealType.MEAL_TYPE_LUNCH
            MealType.DINNER -> HcMealType.MEAL_TYPE_DINNER
            MealType.SNACK -> HcMealType.MEAL_TYPE_SNACK
        }
    }
}

package de.lltntplanet.adhsapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Status aller Berechtigungen, die für zuverlässige Vollbild-Erinnerungen nötig sind. */
data class PermissionState(
    val calendar: Boolean,
    val contacts: Boolean,
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val overlay: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** Ohne diese Punkte kommen Erinnerungen gar nicht oder nicht im Vollbild. */
    val essentialsMissing: Int
        get() = listOf(calendar, notifications, exactAlarms, fullScreen).count { !it }

    val allGood: Boolean get() = essentialsMissing == 0 && overlay && batteryUnrestricted && contacts
}

object Permissions {

    fun state(context: Context) = PermissionState(
        calendar = granted(context, Manifest.permission.READ_CALENDAR),
        contacts = granted(context, Manifest.permission.READ_CONTACTS),
        notifications = notificationsGranted(context),
        exactAlarms = exactAlarmsAllowed(context),
        fullScreen = fullScreenAllowed(context),
        overlay = Settings.canDrawOverlays(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.POST_NOTIFICATIONS)
        else context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    private fun packageUri(context: Context) = "package:${context.packageName}".toUri()

    fun exactAlarmSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        else appDetails(context)

    fun fullScreenSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
        else notificationSettings(context)

    fun overlaySettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))

    @SuppressLint("BatteryLife")
    fun batterySettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    /** Startet eine Einstellungsseite; fällt bei Bedarf auf die App-Info zurück. */
    fun open(context: Context, intent: Intent) {
        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            runCatching { context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

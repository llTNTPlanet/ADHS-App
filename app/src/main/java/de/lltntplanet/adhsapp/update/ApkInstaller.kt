package de.lltntplanet.adhsapp.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Installiert eine heruntergeladene APK über den Android-Paketinstaller. */
class ApkInstaller(private val context: Context) {

    /** Darf die App Pakete installieren? ("Unbekannte Apps installieren" für ADHS App) */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Prüft, ob die APK ein Update genau dieser App ist (gleicher Paketname, höhere Version, gleiche Signatur).
     * Gibt eine Fehlermeldung zurück oder null, wenn alles passt.
     */
    fun verify(apk: File): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return "Die heruntergeladene Datei ist keine gültige App."
        if (archive.packageName != context.packageName) return "Die heruntergeladene App ist nicht die ADHS App."
        val installed = installedInfo(pm)
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) {
            return "Diese Version ist bereits installiert."
        }
        val newSigners = signerDigests(archive)
        val currentSigners = signerDigests(installed)
        if (newSigners.isEmpty() || newSigners != currentSigners) {
            return "Das Update ist mit einem anderen Schlüssel signiert als die installierte App. " +
                "Einmalig nötig: ADHS App deinstallieren und die neue Version aus GitHub (Releases) installieren. " +
                "Danach funktionieren Updates automatisch."
        }
        return null
    }

    /**
     * Übergibt die APK an den Paketinstaller. Ab Android 12 versucht die App, sich ohne Rückfrage zu
     * aktualisieren (klappt, sobald sie sich einmal selbst installiert hat); sonst fragt Android nach.
     * Das Ergebnis kommt im [UpdateInstallReceiver] an.
     */
    suspend fun install(apk: File, versionName: String) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("adhs-app.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val status = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                    .putExtra(UpdateInstallReceiver.EXTRA_VERSION_NAME, versionName)
                // Muss veränderbar sein, damit Android Status und Bestätigungs-Intent eintragen kann.
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    status,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = signatureFlags()
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, flags)
        }
    }

    private fun installedInfo(pm: PackageManager): PackageInfo {
        val flags = signatureFlags()
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        }.toSet()
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    companion object {
        fun apkFile(context: Context, versionCode: Int): File =
            File(context.cacheDir, "updates/adhs-app-$versionCode.apk")

        /** Alte Downloads aufräumen. */
        fun cleanup(context: Context) {
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }
    }
}
